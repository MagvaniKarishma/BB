import { Router } from "express";
import multer from "multer";
import { z } from "zod";
import { Prisma, VoiceLanguage, type VoiceNote } from "@prisma/client";
import { prisma } from "../db.js";
import { type AuthUser, currentUser } from "../lib/auth.js";
import { HttpError, badRequest, notFound } from "../lib/errors.js";
import { absentIfNull, createInquirySchema, updateInquirySchema } from "../schemas.js";
import { createInquiryTx, updateInquiryTx } from "./inquiries.js";
import type { Extraction } from "../voice/draft.js";
import { extractRequirement, getVoiceServices } from "../voice/service.js";

export const voiceNotesRouter = Router();

const MAX_AUDIO_BYTES = 10 * 1024 * 1024; // ~40 min of 32 kbps AAC; notes are capped far lower in the app
const AUDIO_TYPES = new Set([
  "audio/mp4", "audio/m4a", "audio/x-m4a", "audio/aac", "audio/mpeg", "audio/ogg", "audio/webm",
  "audio/wav", "audio/x-wav", "audio/3gpp", "audio/amr",
]);

const upload = multer({
  storage: multer.memoryStorage(),
  limits: { fileSize: MAX_AUDIO_BYTES, files: 1 },
  fileFilter: (_req, file, cb) => {
    if (AUDIO_TYPES.has(file.mimetype)) cb(null, true);
    else cb(new HttpError(415, "UNSUPPORTED_AUDIO", `Unsupported audio type ${file.mimetype}`));
  },
});

const MAX_TRANSCRIPT = 10_000;

const uploadFieldsSchema = z.object({
  clientId: z.string().min(1),
  inquiryId: absentIfNull(z.string().min(1)),
  language: z.nativeEnum(VoiceLanguage).default(VoiceLanguage.AUTO),
  durationMs: z.coerce.number().int().min(0).max(60 * 60 * 1000).optional(),
});

const textNoteSchema = uploadFieldsSchema.omit({ durationMs: true }).extend({
  transcript: z.string().trim().min(1).max(MAX_TRANSCRIPT),
});

const transcriptSchema = z.object({ transcript: z.string().trim().min(1).max(MAX_TRANSCRIPT) });

/**
 * The agent's reviewed requirement. `inquiryId` updates that inquiry (must belong to
 * the note's client); omitted creates a new inquiry for the client.
 */
const applySchema = z.object({
  inquiryId: absentIfNull(z.string().min(1)),
  requirement: z.unknown(),
});

// Never send audio bytes in JSON responses.
const noteSelect = {
  id: true, clientId: true, inquiryId: true, language: true, status: true, audioMimeType: true,
  audioSize: true, durationMs: true, originalTranscript: true, transcript: true, transcriptSource: true,
  extraction: true, extractor: true, error: true, appliedVersion: true, appliedAt: true, createdAt: true,
  updatedAt: true, createdBy: { select: { id: true, name: true } },
} satisfies Prisma.VoiceNoteSelect;

async function assertTarget(brokerageId: string, clientId: string, inquiryId?: string) {
  const client = await prisma.client.findFirst({ where: { id: clientId, brokerageId } });
  if (!client) throw notFound("Client");
  if (inquiryId) {
    const inquiry = await prisma.inquiry.findFirst({ where: { id: inquiryId, brokerageId } });
    if (!inquiry) throw notFound("Inquiry");
    if (inquiry.clientId !== clientId) throw badRequest("That inquiry belongs to a different client");
  }
  return client;
}

async function loadNote(me: AuthUser, id: string) {
  const note = await prisma.voiceNote.findFirst({ where: { id, brokerageId: me.brokerageId }, select: noteSelect });
  if (!note) throw notFound("Voice note");
  return note;
}

type NoteView = Prisma.VoiceNoteGetPayload<{ select: typeof noteSelect }>;

/**
 * Adds routing help for the review screen: which of the client's inquiries this note
 * most likely belongs to, and warnings when the chosen inquiry doesn't fit the draft.
 */
async function present(note: NoteView) {
  const inquiries = await prisma.inquiry.findMany({
    where: { clientId: note.clientId },
    orderBy: [{ status: "asc" }, { updatedAt: "desc" }],
  });
  const draft = (note.extraction as unknown as Extraction | null)?.draft;
  const targetWarnings: string[] = [];
  let suggestedInquiryId: string | null = note.inquiryId;
  if (draft && note.inquiryId) {
    const chosen = inquiries.find((i) => i.id === note.inquiryId);
    if (chosen && draft.transactionType && draft.transactionType.value !== chosen.transactionType) {
      targetWarnings.push(`The note talks about ${draft.transactionType.value} but the selected inquiry is ${chosen.transactionType}`);
    }
    if (chosen && draft.category && draft.category.value !== chosen.category) {
      targetWarnings.push(`The note mentions ${draft.category.value} but the selected inquiry is ${chosen.category}`);
    }
  } else if (draft && !note.inquiryId && note.status === "READY") {
    const candidates = inquiries.filter(
      (i) =>
        i.status === "ACTIVE" &&
        (!draft.transactionType || i.transactionType === draft.transactionType.value) &&
        (!draft.category || i.category === draft.category.value),
    );
    // Only suggest when the draft pins it down unambiguously.
    if (candidates.length === 1 && (draft.transactionType || draft.category)) suggestedInquiryId = candidates[0].id;
  }
  return {
    voiceNote: { ...note, hasAudio: note.audioSize != null },
    suggestedInquiryId,
    targetWarnings,
    inquiries,
  };
}

/** Transcribe (if needed and possible) and extract. Returns the fields to store. */
async function runPipeline(
  audio: { bytes: Buffer; mimeType: string } | null,
  language: VoiceLanguage,
  transcript?: string,
): Promise<Prisma.VoiceNoteUncheckedUpdateInput> {
  let text = transcript;
  let source = transcript ? "manual" : undefined;
  if (!text && audio) {
    const { transcriber } = getVoiceServices();
    if (!transcriber) {
      return { status: "NEEDS_TRANSCRIPT", error: "Speech-to-text is not configured on the server. Type the note instead." };
    }
    try {
      text = await transcriber.transcribe(audio.bytes, audio.mimeType, language);
      source = transcriber.name;
    } catch (err) {
      const reason = err instanceof Error ? err.message : String(err);
      return { status: "NEEDS_TRANSCRIPT", error: `Transcription failed: ${reason.slice(0, 300)}. Type the note or retry.` };
    }
    if (!text) return { status: "NEEDS_TRANSCRIPT", error: "No speech was recognised. Type the note or record again." };
  }
  const normalized = text!.normalize("NFC");
  const extraction = await extractRequirement(normalized, language);
  return {
    status: "READY",
    transcript: normalized,
    transcriptSource: source,
    extraction: extraction as unknown as Prisma.InputJsonObject,
    extractor: extraction.extractor,
    error: null,
  };
}

voiceNotesRouter.post("/", upload.single("audio"), async (req, res) => {
  const me = currentUser(req);
  if (!req.file) throw badRequest("Attach the recording as the 'audio' field");
  const fields = uploadFieldsSchema.parse(req.body);
  await assertTarget(me.brokerageId, fields.clientId, fields.inquiryId);

  const note = await prisma.voiceNote.create({
    data: {
      brokerageId: me.brokerageId,
      clientId: fields.clientId,
      inquiryId: fields.inquiryId ?? null,
      createdById: me.id,
      language: fields.language,
      status: "NEEDS_TRANSCRIPT",
      audio: new Uint8Array(req.file.buffer),
      audioMimeType: req.file.mimetype,
      audioSize: req.file.size,
      durationMs: fields.durationMs ?? null,
    },
    select: { id: true },
  });
  const result = await runPipeline({ bytes: req.file.buffer, mimeType: req.file.mimetype }, fields.language);
  if (typeof result.transcript === "string") result.originalTranscript = result.transcript;
  await prisma.voiceNote.update({ where: { id: note.id }, data: result });
  res.status(201).json(await present(await loadNote(me, note.id)));
});

/** Typed/dictated note — the fallback when recording or speech-to-text isn't available. */
/**
 * Reads a requirement from spoken (already transcribed on the phone) or typed words, for
 * filling the requirement form. Nothing is stored: the agent reviews the form and saves it.
 * Only what was said is returned; everything else stays empty.
 */
voiceNotesRouter.post("/extract", async (req, res) => {
  currentUser(req);
  const body = z.object({
    text: z.string().trim().min(1).max(MAX_TRANSCRIPT),
    language: z.nativeEnum(VoiceLanguage).default("AUTO"),
  }).parse(req.body);
  res.json(await extractRequirement(body.text.normalize("NFC"), body.language));
});

voiceNotesRouter.post("/text", async (req, res) => {
  const me = currentUser(req);
  const body = textNoteSchema.parse(req.body);
  await assertTarget(me.brokerageId, body.clientId, body.inquiryId);
  const note = await prisma.voiceNote.create({
    data: {
      brokerageId: me.brokerageId,
      clientId: body.clientId,
      inquiryId: body.inquiryId ?? null,
      createdById: me.id,
      language: body.language,
      status: "NEEDS_TRANSCRIPT",
      originalTranscript: body.transcript.normalize("NFC"),
    },
    select: { id: true },
  });
  await prisma.voiceNote.update({ where: { id: note.id }, data: await runPipeline(null, body.language, body.transcript) });
  res.status(201).json(await present(await loadNote(me, note.id)));
});

voiceNotesRouter.get("/", async (req, res) => {
  const me = currentUser(req);
  const { clientId, inquiryId } = z
    .object({ clientId: z.string().optional(), inquiryId: z.string().optional() })
    .parse(req.query);
  const notes = await prisma.voiceNote.findMany({
    where: { brokerageId: me.brokerageId, clientId, inquiryId },
    orderBy: { createdAt: "desc" },
    select: noteSelect,
    take: 200,
  });
  res.json({ voiceNotes: notes.map((n) => ({ ...n, hasAudio: n.audioSize != null })) });
});

voiceNotesRouter.get("/:id", async (req, res) => {
  const me = currentUser(req);
  res.json(await present(await loadNote(me, req.params.id)));
});

voiceNotesRouter.get("/:id/audio", async (req, res) => {
  const me = currentUser(req);
  const note = await prisma.voiceNote.findFirst({
    where: { id: req.params.id, brokerageId: me.brokerageId },
    select: { audio: true, audioMimeType: true },
  });
  if (!note?.audio) throw notFound("Recording");
  res.setHeader("Content-Type", note.audioMimeType ?? "application/octet-stream");
  res.setHeader("Cache-Control", "private, max-age=3600");
  res.send(Buffer.from(note.audio));
});

const assertOpen = (note: NoteView) => {
  if (note.status === "APPLIED") throw new HttpError(409, "ALREADY_APPLIED", "This voice note was already saved to an inquiry");
  if (note.status === "DISCARDED") throw new HttpError(409, "DISCARDED", "This voice note was discarded");
};

/** Agent corrects or types the transcript; the draft is re-extracted from it. */
voiceNotesRouter.put("/:id/transcript", async (req, res) => {
  const me = currentUser(req);
  const { transcript } = transcriptSchema.parse(req.body);
  const note = await loadNote(me, req.params.id);
  assertOpen(note);
  const result = await runPipeline(null, note.language, transcript);
  if (note.originalTranscript == null) result.originalTranscript = transcript.normalize("NFC");
  await prisma.voiceNote.update({ where: { id: note.id }, data: result });
  res.json(await present(await loadNote(me, note.id)));
});

voiceNotesRouter.post("/:id/retry", async (req, res) => {
  const me = currentUser(req);
  const note = await prisma.voiceNote.findFirst({ where: { id: req.params.id, brokerageId: me.brokerageId } });
  if (!note) throw notFound("Voice note");
  if (note.status !== "NEEDS_TRANSCRIPT" || !note.audio) {
    throw badRequest("Only recordings that still need a transcript can be retried");
  }
  const result = await runPipeline({ bytes: Buffer.from(note.audio), mimeType: note.audioMimeType ?? "audio/mp4" }, note.language);
  if (typeof result.transcript === "string") result.originalTranscript = result.transcript;
  await prisma.voiceNote.update({ where: { id: note.id }, data: result });
  res.json(await present(await loadNote(me, note.id)));
});

/**
 * Saves the agent-reviewed requirement. Updates an existing inquiry of the same client
 * (with a VOICE_NOTE revision linked to this note) or creates a new inquiry.
 */
voiceNotesRouter.post("/:id/apply", async (req, res) => {
  const me = currentUser(req);
  const body = applySchema.parse(req.body);
  const note = await loadNote(me, req.params.id);
  assertOpen(note);
  if (note.status !== "READY") throw badRequest("Add a transcript before saving this note");

  const targetId = body.inquiryId;
  const result = await prisma.$transaction(async (tx) => {
    // Lock the note row so a double-tap can't apply it twice.
    const locked = await tx.$queryRaw<{ status: string }[]>`SELECT status FROM "VoiceNote" WHERE id = ${note.id} FOR UPDATE`;
    if (locked[0]?.status !== "READY") throw new HttpError(409, "ALREADY_APPLIED", "This voice note was already saved");

    let inquiryId: string;
    let version: number;
    let changed = true;
    if (targetId) {
      const current = await tx.inquiry.findFirst({ where: { id: targetId, brokerageId: me.brokerageId } });
      if (!current) throw notFound("Inquiry");
      if (current.clientId !== note.clientId) throw badRequest("That inquiry belongs to a different client");
      const { source: _ignored, ...patch } = updateInquirySchema.parse(body.requirement);
      const updated = await updateInquiryTx(tx, me, current, patch, "VOICE_NOTE", note.id);
      inquiryId = updated.inquiry.id;
      version = updated.inquiry.version;
      changed = updated.changed;
    } else {
      const requirement = createInquirySchema.parse(body.requirement);
      const created = await createInquiryTx(tx, me, note.clientId, { ...requirement, source: "VOICE_NOTE" }, note.id);
      inquiryId = created.id;
      version = created.version;
    }
    await tx.voiceNote.update({
      where: { id: note.id },
      data: { status: "APPLIED", inquiryId, appliedVersion: changed ? version : null, appliedAt: new Date() },
    });
    return { inquiryId, version, changed };
  });
  const inquiry = await prisma.inquiry.findUniqueOrThrow({ where: { id: result.inquiryId } });
  res.json({ inquiry, changed: result.changed, voiceNote: (await present(await loadNote(me, note.id))).voiceNote });
});

voiceNotesRouter.post("/:id/discard", async (req, res) => {
  const me = currentUser(req);
  const note = await loadNote(me, req.params.id);
  assertOpen(note);
  await prisma.voiceNote.update({ where: { id: note.id }, data: { status: "DISCARDED" } });
  res.json({ voiceNote: { ...(await loadNote(me, note.id)), hasAudio: note.audioSize != null } });
});

export type { VoiceNote };
