import { randomBytes } from "node:crypto";
import { Router } from "express";
import multer from "multer";
import { z } from "zod";
import { GreetingLanguage, type Prisma } from "@prisma/client";
import { prisma } from "../db.js";
import { currentUser, requireRole } from "../lib/auth.js";
import { HttpError, badRequest, notFound } from "../lib/errors.js";
import { normalizePhone } from "../lib/phone.js";
import { assertMember } from "./team.js";
import { getBlobStore, newKey } from "../storage/blobStore.js";
import { MAX_GREETING_BYTES, MAX_GREETING_MS, sniffAudio, wavInfo } from "../callAssistant/audio.js";
import { DEFAULT_SCRIPTS, scriptDisclosesAi } from "../callAssistant/prompts.js";
import { businessHoursSchema, greetingsView, loadSettings } from "../callAssistant/settings.js";
import { handleCallEvent } from "../callAssistant/sessions.js";
import { twilioConfigured } from "../callAssistant/providers/twilio.js";
import { publicBaseUrl } from "../callAssistant/signing.js";

export const callAssistantRouter = Router();
const manage = requireRole("OWNER", "ADMIN");

const languageParam = (raw: unknown) => {
  const parsed = z.nativeEnum(GreetingLanguage).safeParse(String(raw).toUpperCase());
  if (!parsed.success) throw notFound("Greeting language");
  return parsed.data;
};

/** What the server can actually do today — the app shows unavailable options as such. */
function capabilities() {
  return {
    provider: twilioConfigured() && publicBaseUrl() ? "twilio" : null,
    voices: {
      RECORDED_STANDARD: { available: true },
      RECORDED_NATURAL: { available: false, reason: "Needs a natural text-to-speech provider (not set up yet)" },
      CUSTOM_AI_VOICE: {
        available: false,
        reason: "Needs a provider that offers consented voice creation; BrokerBuddy does not clone voices",
      },
    },
    greetingFormats: ["audio/wav", "audio/mpeg"],
    maxGreetingSeconds: MAX_GREETING_MS / 1000,
  };
}

callAssistantRouter.get("/", async (req, res) => {
  const me = currentUser(req);
  const [settings, greetings] = await Promise.all([
    loadSettings(me.brokerageId),
    prisma.callGreeting.findMany({ where: { brokerageId: me.brokerageId } }),
  ]);
  res.json({ settings, greetings: greetingsView(greetings), capabilities: capabilities() });
});

const settingsSchema = z.object({
  enabled: z.boolean(),
  mode: z.enum(["DIRECT", "AI_RECEPTIONIST", "SMART_ASSISTANT"]),
  voice: z.enum(["RECORDED_STANDARD", "RECORDED_NATURAL", "CUSTOM_AI_VOICE"]),
  customGreetingEnabled: z.boolean(),
  defaultLanguage: z.nativeEnum(GreetingLanguage),
  businessHours: businessHoursSchema,
  callbackReminder: z.boolean(),
  callbackDelayMinutes: z.number().int().min(0).max(24 * 60),
  callbackAssigneeId: z.string().nullable(),
  unclearBehavior: z.enum(["TAKE_CALLBACK", "TRANSFER"]),
  maxUnclearRetries: z.number().int().min(1).max(4),
  humanTransfer: z.enum(["ON_REQUEST", "NEVER"]),
  transferNumber: z.string().trim().max(30).nullable(),
  businessNumbers: z.array(z.string().trim().max(30)).max(5),
}).partial();

callAssistantRouter.put("/settings", manage, async (req, res) => {
  const me = currentUser(req);
  const body = settingsSchema.parse(req.body);
  if (body.voice && !capabilities().voices[body.voice].available) {
    throw badRequest(`Voice option ${body.voice} isn't available yet`);
  }
  const data: Prisma.CallAssistantSettingsUncheckedCreateInput = { ...(await loadSettings(me.brokerageId)), ...body } as never;
  delete (data as { updatedAt?: unknown }).updatedAt;
  if (body.transferNumber) {
    const n = normalizePhone(body.transferNumber);
    if (!n) throw badRequest(`"${body.transferNumber}" is not a valid phone number`);
    data.transferNumber = n;
  }
  if (body.businessNumbers) {
    const nums = body.businessNumbers.map((raw) => {
      const n = normalizePhone(raw);
      if (!n) throw badRequest(`"${raw}" is not a valid phone number`);
      return n;
    });
    const taken = await prisma.callAssistantSettings.findFirst({
      where: { brokerageId: { not: me.brokerageId }, businessNumbers: { hasSome: nums } },
    });
    if (taken) throw new HttpError(409, "NUMBER_IN_USE", "One of these business numbers is already used by another account");
    data.businessNumbers = [...new Set(nums)];
  }
  if (body.callbackAssigneeId) await assertMember(me.brokerageId, body.callbackAssigneeId);
  if (data.enabled && data.mode === "SMART_ASSISTANT" && !data.transferNumber) {
    throw badRequest("Smart Call Assistant rings your team first: add the number to ring");
  }
  const { brokerageId: _b, ...update } = data;
  const settings = await prisma.callAssistantSettings.upsert({
    where: { brokerageId: me.brokerageId },
    create: { ...data, brokerageId: me.brokerageId, businessHours: data.businessHours as Prisma.InputJsonValue },
    update: { ...update, businessHours: data.businessHours as Prisma.InputJsonValue },
  });
  res.json({ settings });
});

// ---------- greetings ----------

const scriptSchema = z.object({ script: z.string().trim().min(10).max(1000) });

async function upsertGreeting(brokerageId: string, language: GreetingLanguage, data: Partial<Prisma.CallGreetingUncheckedCreateInput>) {
  return prisma.callGreeting.upsert({
    where: { brokerageId_language: { brokerageId, language } },
    create: { brokerageId, language, script: DEFAULT_SCRIPTS[language], ...data },
    update: data,
  });
}

callAssistantRouter.put("/greetings/:language", manage, async (req, res) => {
  const me = currentUser(req);
  const language = languageParam(req.params.language);
  const { script } = scriptSchema.parse(req.body);
  await upsertGreeting(me.brokerageId, language, { script, updatedById: me.id });
  const all = await prisma.callGreeting.findMany({ where: { brokerageId: me.brokerageId } });
  res.json({
    greeting: greetingsView(all).find((g) => g.language === language),
    // The assistant always identifies itself as an AI after the greeting anyway.
    warnings: scriptDisclosesAi(script) ? [] : ["The script doesn't say it's an AI assistant; the assistant will add that right after it."],
  });
});

const upload = multer({ storage: multer.memoryStorage(), limits: { fileSize: MAX_GREETING_BYTES, files: 1 } });

callAssistantRouter.post("/greetings/:language/audio", manage, upload.single("audio"), async (req, res) => {
  const me = currentUser(req);
  const language = languageParam(req.params.language);
  if (!req.file) throw badRequest("Attach the recording as the 'audio' field");
  const mimeType = sniffAudio(req.file.buffer);
  if (!mimeType) {
    throw new HttpError(415, "UNSUPPORTED_AUDIO", "Upload an MP3 or WAV file, or record the greeting in the app");
  }
  let durationMs: number | null = null;
  if (mimeType === "audio/wav") {
    const info = wavInfo(req.file.buffer);
    if (!info) throw new HttpError(415, "UNSUPPORTED_AUDIO", "This WAV file can't be read");
    durationMs = info.durationMs;
  } else if (req.body?.durationMs) {
    durationMs = z.coerce.number().int().min(0).max(10 * 60_000).parse(req.body.durationMs);
  }
  if (durationMs != null && durationMs > MAX_GREETING_MS) throw badRequest(`Keep the greeting under ${MAX_GREETING_MS / 1000} seconds`);
  if (durationMs != null && durationMs < 1000) throw badRequest("The recording is too short");

  const previous = await prisma.callGreeting.findUnique({ where: { brokerageId_language: { brokerageId: me.brokerageId, language } } });
  const storageKey = newKey(me.brokerageId, "greetings", mimeType === "audio/wav" ? ".wav" : ".mp3");
  await getBlobStore().put(storageKey, req.file.buffer);
  try {
    await upsertGreeting(me.brokerageId, language, { storageKey, mimeType, size: req.file.size, durationMs, updatedById: me.id });
  } catch (err) {
    await getBlobStore().delete(storageKey).catch(() => undefined);
    throw err;
  }
  if (previous?.storageKey) await getBlobStore().delete(previous.storageKey).catch(() => undefined);
  const all = await prisma.callGreeting.findMany({ where: { brokerageId: me.brokerageId } });
  res.status(201).json({ greeting: greetingsView(all).find((g) => g.language === language) });
});

/** Preview for the app (members only). */
callAssistantRouter.get("/greetings/:language/audio", async (req, res) => {
  const me = currentUser(req);
  const language = languageParam(req.params.language);
  const g = await prisma.callGreeting.findUnique({ where: { brokerageId_language: { brokerageId: me.brokerageId, language } } });
  const bytes = g?.storageKey ? await getBlobStore().get(g.storageKey) : null;
  if (!g || !bytes) throw notFound("Greeting recording");
  res.setHeader("Content-Type", g.mimeType ?? "application/octet-stream");
  res.setHeader("Cache-Control", "private, no-store");
  res.send(bytes);
});

callAssistantRouter.delete("/greetings/:language/audio", manage, async (req, res) => {
  const me = currentUser(req);
  const language = languageParam(req.params.language);
  const g = await prisma.callGreeting.findUnique({ where: { brokerageId_language: { brokerageId: me.brokerageId, language } } });
  if (!g?.storageKey) throw notFound("Greeting recording");
  await prisma.callGreeting.update({ where: { id: g.id }, data: { storageKey: null, mimeType: null, size: null, durationMs: null, updatedById: me.id } });
  await getBlobStore().delete(g.storageKey).catch(() => undefined);
  res.status(204).end();
});

/** Back to the default script (and no recording). */
callAssistantRouter.delete("/greetings/:language", manage, async (req, res) => {
  const me = currentUser(req);
  const language = languageParam(req.params.language);
  const g = await prisma.callGreeting.findUnique({ where: { brokerageId_language: { brokerageId: me.brokerageId, language } } });
  if (g) {
    await prisma.callGreeting.delete({ where: { id: g.id } });
    if (g.storageKey) await getBlobStore().delete(g.storageKey).catch(() => undefined);
  }
  res.status(204).end();
});

// ---------- call log ----------

callAssistantRouter.get("/calls", async (req, res) => {
  const me = currentUser(req);
  const calls = await prisma.callSession.findMany({
    where: { brokerageId: me.brokerageId, isTest: false },
    orderBy: { startedAt: "desc" },
    take: 100,
    select: {
      id: true, fromNumber: true, status: true, language: true, callbackRequested: true, humanRequested: true,
      summary: true, clientId: true, inquiryId: true, startedAt: true, endedAt: true, durationSec: true,
    },
  });
  res.json({ calls });
});

callAssistantRouter.get("/calls/:id", async (req, res) => {
  const me = currentUser(req);
  const call = await prisma.callSession.findFirst({
    where: { id: String(req.params.id), brokerageId: me.brokerageId },
    include: { turns: { orderBy: { seq: "asc" }, select: { role: true, text: true, createdAt: true } } },
  });
  if (!call) throw notFound("Call");
  const { state: _s, seenEvents: _e, ...rest } = call;
  res.json({ call: rest });
});

// ---------- "Test your assistant" (typed conversation; nothing is saved to the CRM) ----------

const TEST = "test";
const testStartSchema = z.object({ callerNumber: z.string().trim().max(30).nullish() });

function asText(ins: Awaited<ReturnType<typeof handleCallEvent>>) {
  return {
    lines: ins.steps.map((s) => (s.kind === "play" ? { kind: "recording" as const } : { kind: "speech" as const, text: s.text, language: s.language })),
    next: ins.next.kind,
  };
}

callAssistantRouter.post("/test-calls", async (req, res) => {
  const me = currentUser(req);
  const body = testStartSchema.parse(req.body ?? {});
  const callId = `test-${me.id}-${randomBytes(6).toString("hex")}`;
  const ins = await handleCallEvent({
    type: "START", provider: TEST, callId, from: body.callerNumber ?? null, to: null, brokerageId: me.brokerageId, isTest: true,
  });
  res.status(201).json({ callId, ...asText(ins) });
});

callAssistantRouter.post("/test-calls/:callId/turns", async (req, res) => {
  const me = currentUser(req);
  const callId = String(req.params.callId);
  if (!callId.startsWith(`test-${me.id}-`)) throw notFound("Test call");
  const { text } = z.object({ text: z.string().max(1000) }).parse(req.body);
  const session = await prisma.callSession.findUnique({ where: { provider_providerCallId: { provider: TEST, providerCallId: callId } } });
  if (!session || session.brokerageId !== me.brokerageId) throw notFound("Test call");
  const turns = await prisma.callTurn.count({ where: { sessionId: session.id } });
  const ins = await handleCallEvent({ type: "SPEECH", provider: TEST, callId, eventId: `t-${turns}`, text });
  const after = await prisma.callSession.findUniqueOrThrow({ where: { id: session.id } });
  const st = after.state as unknown as { engine: { draft: unknown; name?: unknown; callbackRequested: boolean | null } };
  res.json({ ...asText(ins), collected: { draft: st.engine.draft, name: st.engine.name ?? null, callbackRequested: st.engine.callbackRequested } });
});
