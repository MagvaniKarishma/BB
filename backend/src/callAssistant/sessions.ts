import { Prisma, type CallSession, type GreetingLanguage } from "@prisma/client";
import { prisma } from "../db.js";
import { normalizePhone } from "../lib/phone.js";
import { findByPhones } from "../services/clients.js";
import { type CallState, type EngineContext, type EngineReply, newState, opening, respond } from "./engine.js";
import { DEFAULT_SCRIPTS } from "./prompts.js";
import { loadSettings, withinHours } from "./settings.js";
import { signedGreetingUrl } from "./signing.js";
import { saveCallToCrm } from "./crm.js";

/**
 * The call flow, independent of the telephony provider. A provider adapter turns its
 * webhooks into these events and renders the returned instructions in its own format.
 *
 *   incoming call → (Smart mode, business hours: ring the team first)
 *                 → greeting (broker's recording, or the script read by the AI voice)
 *                 → AI disclosure → questions … → callback / transfer / goodbye
 *   call ends     → saved to the CRM once (retries and the sweeper are no-ops)
 */

export type CallEvent =
  | { type: "START"; provider: string; callId: string; from: string | null; to: string | null; brokerageId?: string; isTest?: boolean }
  | { type: "DIAL_RESULT"; provider: string; callId: string; eventId: string; answered: boolean }
  /** text "" = the caller said nothing before the timeout. */
  | { type: "SPEECH"; provider: string; callId: string; eventId: string; text: string }
  | { type: "END"; provider: string; callId: string; durationSec?: number | null };

export type Step = { kind: "play"; url: string } | { kind: "say"; text: string; language: GreetingLanguage };
export type Next =
  | { kind: "listen"; language: GreetingLanguage; turn: number }
  | { kind: "dial"; number: string; timeoutSec: number; turn: number }
  | { kind: "transfer"; number: string }
  | { kind: "hangup" };
export interface Instructions {
  steps: Step[];
  next: Next;
}

interface StoredState {
  phase: "TEAM" | "AI";
  engine: CallState;
  /** Next expected turn number (echoed back by the provider, used to spot retries). */
  turn: number;
  lastReply: Instructions;
  /** What the greeting was, for the call log. */
  greeting?: { language: GreetingLanguage; recorded: boolean };
}

const HANGUP: Instructions = { steps: [], next: { kind: "hangup" } };
const RING_TEAM_SECONDS = 20;

async function brokerageForNumber(to: string | null): Promise<string | null> {
  const n = to ? normalizePhone(to) ?? to : null;
  if (!n) return null;
  const s = await prisma.callAssistantSettings.findFirst({ where: { businessNumbers: { has: n } } });
  return s?.brokerageId ?? null;
}

/** Language to greet in: the caller's language on their previous AI call, else the default. */
async function greetingLanguage(brokerageId: string, from: string | null, fallback: GreetingLanguage) {
  if (!from) return fallback;
  const client = await findByPhones(brokerageId, [from]);
  if (!client) return fallback;
  const prev = await prisma.callSession.findFirst({
    where: { brokerageId, clientId: client.id, language: { not: null } },
    orderBy: { startedAt: "desc" },
  });
  return prev?.language ?? fallback;
}

async function aiOpening(brokerageId: string, lang: GreetingLanguage): Promise<{ steps: Step[]; reply: EngineReply; recorded: boolean }> {
  const settings = await loadSettings(brokerageId);
  const greeting = await prisma.callGreeting.findUnique({ where: { brokerageId_language: { brokerageId, language: lang } } });
  const recorded = settings.customGreetingEnabled && greeting?.storageKey != null;
  const script = greeting?.script ?? DEFAULT_SCRIPTS[lang];
  const steps: Step[] = [recorded ? { kind: "play", url: signedGreetingUrl(greeting!.id) } : { kind: "say", text: script, language: lang }];
  const reply = opening(newState(lang), { recorded, script });
  steps.push(...reply.say.map((text) => ({ kind: "say" as const, text, language: lang })));
  return { steps, reply, recorded };
}

async function engineContext(session: CallSession): Promise<EngineContext> {
  const s = await loadSettings(session.brokerageId);
  return {
    callerNumber: session.fromNumber ? normalizePhone(session.fromNumber) : null,
    canTransfer: s.humanTransfer === "ON_REQUEST" && s.transferNumber != null && withinHours(s.businessHours) && !session.isTest,
    unclearBehavior: s.unclearBehavior,
    maxUnclearRetries: s.maxUnclearRetries,
  };
}

function toInstructions(reply: EngineReply, turn: number, transferNumber: string | null): Instructions {
  const steps: Step[] = reply.say.map((text) => ({ kind: "say", text, language: reply.state.language }));
  if (reply.action === "LISTEN") return { steps, next: { kind: "listen", language: reply.state.language, turn } };
  if (reply.action === "TRANSFER" && transferNumber) return { steps, next: { kind: "transfer", number: transferNumber } };
  return { steps, next: { kind: "hangup" } };
}

/** Runs `fn` with the session row locked, so concurrent webhook deliveries apply one at a time. */
async function withSession<T>(provider: string, callId: string, fn: (tx: Prisma.TransactionClient, s: CallSession) => Promise<T>): Promise<T | null> {
  return prisma.$transaction(
    async (tx) => {
      const rows = await tx.$queryRaw<{ id: string }[]>`SELECT id FROM "CallSession" WHERE provider = ${provider} AND "providerCallId" = ${callId} FOR UPDATE`;
      if (rows.length === 0) return null;
      const s = await tx.callSession.findUniqueOrThrow({ where: { id: rows[0].id } });
      return fn(tx, s);
    },
    { timeout: 30_000, maxWait: 10_000 },
  );
}

async function addTurns(tx: Prisma.TransactionClient, sessionId: string, turns: { role: "CALLER" | "ASSISTANT"; text: string }[]) {
  const last = await tx.callTurn.findFirst({ where: { sessionId }, orderBy: { seq: "desc" } });
  let seq = last?.seq ?? 0;
  for (const t of turns) if (t.text.trim()) await tx.callTurn.create({ data: { sessionId, seq: ++seq, role: t.role, text: t.text.slice(0, 4000) } });
}

export async function handleCallEvent(e: CallEvent): Promise<Instructions> {
  switch (e.type) {
    case "START":
      return start(e);
    case "DIAL_RESULT":
      return dialResult(e);
    case "SPEECH":
      return speech(e);
    case "END":
      await end(e);
      return HANGUP;
  }
}

async function start(e: Extract<CallEvent, { type: "START" }>): Promise<Instructions> {
  // A retried "incoming call" webhook gets the same answer.
  const existing = await prisma.callSession.findUnique({ where: { provider_providerCallId: { provider: e.provider, providerCallId: e.callId } } });
  if (existing) return (existing.state as unknown as StoredState).lastReply;

  const brokerageId = e.brokerageId ?? (await brokerageForNumber(e.to));
  if (!brokerageId) return HANGUP;
  const settings = await loadSettings(brokerageId);
  const from = e.from ? normalizePhone(e.from) : null;

  if (!e.isTest && (!settings.enabled || settings.mode === "DIRECT")) {
    // Assistant off: straight to the team, no AI, nothing stored.
    return settings.transferNumber ? { steps: [], next: { kind: "transfer", number: settings.transferNumber } } : HANGUP;
  }
  const lang = await greetingLanguage(brokerageId, from, settings.defaultLanguage);
  const ringTeamFirst =
    !e.isTest && settings.mode === "SMART_ASSISTANT" && settings.transferNumber != null && withinHours(settings.businessHours);

  let stored: StoredState;
  if (ringTeamFirst) {
    const reply: Instructions = { steps: [], next: { kind: "dial", number: settings.transferNumber!, timeoutSec: RING_TEAM_SECONDS, turn: 0 } };
    stored = { phase: "TEAM", engine: newState(lang), turn: 0, lastReply: reply };
  } else {
    const o = await aiOpening(brokerageId, lang);
    const reply: Instructions = { steps: o.steps, next: { kind: "listen", language: lang, turn: 1 } };
    stored = { phase: "AI", engine: o.reply.state, turn: 1, lastReply: reply, greeting: { language: lang, recorded: o.recorded } };
  }
  try {
    const session = await prisma.callSession.create({
      data: {
        brokerageId, provider: e.provider, providerCallId: e.callId, isTest: e.isTest ?? false,
        fromNumber: from ?? e.from, toNumber: e.to, language: lang, state: stored as unknown as Prisma.InputJsonValue,
        clientId: from ? (await findByPhones(brokerageId, [from]))?.id ?? null : null,
      },
    });
    if (stored.phase === "AI") {
      await addTurns(prisma, session.id, [{ role: "ASSISTANT", text: stored.lastReply.steps.map((s) => (s.kind === "say" ? s.text : "[recorded greeting]")).join(" ") }]);
    }
  } catch (err) {
    // Two deliveries of the same call raced; the other one won.
    if (err instanceof Prisma.PrismaClientKnownRequestError && err.code === "P2002") {
      const s = await prisma.callSession.findUniqueOrThrow({ where: { provider_providerCallId: { provider: e.provider, providerCallId: e.callId } } });
      return (s.state as unknown as StoredState).lastReply;
    }
    throw err;
  }
  return stored.lastReply;
}

async function dialResult(e: Extract<CallEvent, { type: "DIAL_RESULT" }>): Promise<Instructions> {
  const r = await withSession(e.provider, e.callId, async (tx, s) => {
    const st = s.state as unknown as StoredState;
    if (s.seenEvents.includes(e.eventId) || st.phase !== "TEAM") return st.lastReply;
    if (e.answered) {
      // The team took the call; the assistant stays out of it.
      await tx.callSession.update({
        where: { id: s.id },
        data: { seenEvents: { push: e.eventId }, status: "COMPLETED", summary: "Answered by the team", savedAt: new Date(), endedAt: new Date() },
      });
      return HANGUP;
    }
    const o = await aiOpening(s.brokerageId, st.engine.language);
    const reply: Instructions = { steps: o.steps, next: { kind: "listen", language: st.engine.language, turn: 1 } };
    const next: StoredState = { phase: "AI", engine: o.reply.state, turn: 1, lastReply: reply, greeting: { language: st.engine.language, recorded: o.recorded } };
    await tx.callSession.update({ where: { id: s.id }, data: { seenEvents: { push: e.eventId }, state: next as unknown as Prisma.InputJsonValue } });
    await addTurns(tx, s.id, [{ role: "ASSISTANT", text: o.steps.map((x) => (x.kind === "say" ? x.text : "[recorded greeting]")).join(" ") }]);
    return reply;
  });
  return r ?? HANGUP;
}

async function speech(e: Extract<CallEvent, { type: "SPEECH" }>): Promise<Instructions> {
  const r = await withSession(e.provider, e.callId, async (tx, s) => {
    const st = s.state as unknown as StoredState;
    // A retried delivery of a turn we've already answered gets the same answer.
    if (s.seenEvents.includes(e.eventId)) return st.lastReply;
    if (s.status !== "IN_PROGRESS" || st.phase !== "AI" || st.engine.done) return HANGUP;
    const settings = await loadSettings(s.brokerageId);
    const reply = await respond(st.engine, e.text, await engineContext(s));
    const turn = st.turn + 1;
    const out = toInstructions(reply, turn, settings.transferNumber);
    const next: StoredState = { ...st, engine: reply.state, turn, lastReply: out };
    await tx.callSession.update({
      where: { id: s.id },
      data: {
        state: next as unknown as Prisma.InputJsonValue,
        seenEvents: [...s.seenEvents, e.eventId].slice(-200),
        language: reply.state.language,
        callbackRequested: reply.state.callbackRequested === true,
        humanRequested: reply.state.humanRequested,
        ...(out.next.kind === "transfer" ? { status: "TRANSFERRED" as const } : {}),
      },
    });
    await addTurns(tx, s.id, [
      { role: "CALLER", text: e.text },
      { role: "ASSISTANT", text: reply.say.join(" ") },
    ]);
    return out;
  });
  return r ?? HANGUP;
}

async function end(e: Extract<CallEvent, { type: "END" }>) {
  const s = await withSession(e.provider, e.callId, async (tx, s) => {
    if (s.endedAt) return s; // already ended (retry)
    const st = s.state as unknown as StoredState;
    const status = s.status === "IN_PROGRESS" ? (st.engine.done ? "COMPLETED" : "INTERRUPTED") : s.status;
    return tx.callSession.update({
      where: { id: s.id },
      data: {
        status, endedAt: new Date(),
        durationSec: e.durationSec ?? Math.round((Date.now() - s.startedAt.getTime()) / 1000),
      },
    });
  });
  if (s && !s.isTest) await saveCallToCrm(s.id);
}

/**
 * Recovers calls whose "call ended" webhook never arrived (network failure, provider
 * outage) and retries CRM saves that failed. Run periodically.
 */
export async function sweepCalls(now = new Date()) {
  const stale = await prisma.callSession.findMany({
    where: { status: "IN_PROGRESS", endedAt: null, startedAt: { lt: new Date(now.getTime() - 30 * 60_000) } },
    take: 50,
  });
  for (const s of stale) {
    await prisma.callSession.update({ where: { id: s.id }, data: { status: "INTERRUPTED", endedAt: now } });
  }
  const unsaved = await prisma.callSession.findMany({ where: { endedAt: { not: null }, savedAt: null, isTest: false }, take: 50 });
  for (const s of unsaved) await saveCallToCrm(s.id).catch((err) => console.error("AI call save failed", s.id, err));
}
