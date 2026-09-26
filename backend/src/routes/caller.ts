import { Router } from "express";
import { prisma } from "../db.js";
import { currentUser } from "../lib/auth.js";
import { normalizePhone } from "../lib/phone.js";
import { matchCounts } from "../services/matching.js";

export const callerRouter = Router();

export interface LastInteraction {
  kind: "NOTE" | "VOICE_NOTE";
  text: string;
  at: Date;
  by: string | null;
}

/** Most recent conversation record: a client note or a voice-note transcript. */
async function lastInteraction(clientId: string): Promise<LastInteraction | null> {
  const [note, voice] = await Promise.all([
    prisma.clientNote.findFirst({
      where: { clientId },
      orderBy: { createdAt: "desc" },
      include: { author: { select: { name: true } } },
    }),
    prisma.voiceNote.findFirst({
      where: { clientId, transcript: { not: null }, status: { not: "DISCARDED" } },
      orderBy: { createdAt: "desc" },
      select: { transcript: true, createdAt: true, createdBy: { select: { name: true } } },
    }),
  ]);
  const candidates: LastInteraction[] = [];
  if (note) candidates.push({ kind: "NOTE", text: note.body, at: note.createdAt, by: note.author?.name ?? null });
  if (voice?.transcript) {
    candidates.push({ kind: "VOICE_NOTE", text: voice.transcript, at: voice.createdAt, by: voice.createdBy?.name ?? null });
  }
  candidates.sort((a, b) => b.at.getTime() - a.at.getTime());
  return candidates[0] ?? null;
}

/**
 * Everything the caller screen shows for an incoming number. `client` is null for
 * unknown numbers; `number` is null when the number is hidden or not a valid phone.
 */
callerRouter.get("/lookup", async (req, res) => {
  const me = currentUser(req);
  const number = normalizePhone(String(req.query.phone ?? ""));
  if (!number) {
    res.json({ number: null, client: null });
    return;
  }
  const phone = await prisma.clientPhone.findUnique({
    where: { brokerageId_e164: { brokerageId: me.brokerageId, e164: number } },
    include: {
      client: {
        include: {
          assignedTo: { select: { id: true, name: true } },
          inquiries: { orderBy: { updatedAt: "desc" } },
          reminders: {
            where: { status: "PENDING" },
            orderBy: { dueAt: "asc" },
            take: 5,
            include: { assignedTo: { select: { id: true, name: true } } },
          },
        },
      },
    },
  });
  if (!phone) {
    res.json({ number, client: null });
    return;
  }
  const { inquiries, ...client } = phone.client;
  const open = inquiries.filter((i) => i.status === "ACTIVE" || i.status === "PAUSED");
  const counts = await matchCounts(me.brokerageId, open.filter((i) => i.status === "ACTIVE"));
  res.json({
    number,
    client: {
      ...client,
      inquiries: open.map((i) => ({ ...i, matchCount: counts.get(i.id) ?? null })),
      closedInquiries: inquiries.length - open.length,
      lastInteraction: await lastInteraction(client.id),
    },
  });
});

/**
 * Compact phone → client directory cached on the device so the caller screen can
 * show a name instantly (and offline). Only names and ids — no requirements.
 */
callerRouter.get("/directory", async (req, res) => {
  const me = currentUser(req);
  const phones = await prisma.clientPhone.findMany({
    where: { brokerageId: me.brokerageId },
    select: { e164: true, client: { select: { id: true, name: true } } },
    take: 50_000,
  });
  res.json({
    generatedAt: new Date().toISOString(),
    entries: phones.map((p) => ({ e164: p.e164, clientId: p.client.id, name: p.client.name })),
  });
});
