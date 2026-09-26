import type { GreetingLanguage, UnclearBehavior, VoiceLanguage } from "@prisma/client";
import type { Evidence, Extraction, RequirementDraft } from "../voice/draft.js";
import { extractRequirement } from "../voice/service.js";
import { parseAmount } from "../whatsapp/portalLeads.js";
import { bareName, isDone, isNo, isYes, skips, spokenPhone, statedName, wantsCallback, wantsHuman } from "./intents.js";
import { detectLanguage } from "./language.js";
import { type PromptKey, say, scriptDisclosesAi } from "./prompts.js";

/**
 * The AI receptionist's conversation, independent of any telephony provider: text in
 * (what the speech recogniser heard), text out (what to say next) plus an action.
 *
 * Rules it keeps:
 *  - it speaks as an AI assistant and says so, even after a greeting in the broker's voice;
 *  - one question at a time, and never a question the caller has already answered;
 *  - every saved value comes from the caller's own words (kept as evidence) — nothing is
 *    guessed or filled in; "don't know"/"any" leaves the field empty;
 *  - callers can skip, ask for a callback, or ask for a person at any point.
 */

export type Slot = "transactionType" | "category" | "locations" | "budget" | "name" | "extras" | "callback" | "callbackNumber";

export interface CallState {
  language: GreetingLanguage;
  draft: RequirementDraft;
  name?: Evidence<string>;
  /** Number the caller asked to be called on, when it isn't the one they're calling from. */
  callbackNumber?: Evidence<string>;
  /** "Anything else?" answers, verbatim. */
  otherNotes: string[];
  skipped: Slot[];
  pending: Slot | null;
  /** Consecutive answers the assistant couldn't understand. */
  unclear: number;
  /** null until the caller has answered (or asked for) a callback. */
  callbackRequested: boolean | null;
  /** The callback number has been confirmed or given (or the caller declined a call). */
  callbackSettled: boolean;
  humanRequested: boolean;
  turns: number;
  done: boolean;
}

export type EngineAction = "LISTEN" | "TRANSFER" | "HANGUP";

export interface EngineReply {
  say: string[];
  action: EngineAction;
  state: CallState;
}

export interface EngineContext {
  /** Caller ID in E.164; null when hidden or invalid. */
  callerNumber: string | null;
  /** A person can take the call now (transfer number set, allowed, within business hours). */
  canTransfer: boolean;
  unclearBehavior: UnclearBehavior;
  maxUnclearRetries: number;
  /** Injected for tests; defaults to the configured extractor (Claude or rules). */
  extract?: (text: string, language: VoiceLanguage) => Promise<Extraction>;
}

/** Hard stop so a confused call can't run forever. */
const MAX_TURNS = 24;

export function newState(language: GreetingLanguage): CallState {
  return {
    language, draft: {}, otherNotes: [], skipped: [], pending: null, unclear: 0,
    callbackRequested: null, callbackSettled: false, humanRequested: false, turns: 0, done: false,
  };
}

/**
 * The assistant's first words after the call is answered. The greeting (recorded audio or
 * spoken script) is played by the caller of this function; this adds the AI disclosure
 * whenever the greeting was the broker's recording or its script doesn't mention AI.
 */
export function opening(state: CallState, greeting: { recorded: boolean; script: string }): EngineReply {
  const lines: string[] = [];
  if (greeting.recorded || !scriptDisclosesAi(greeting.script)) lines.push(say(state.language, "disclosure"));
  lines.push(say(state.language, "openQuestion"));
  return { say: lines, action: "LISTEN", state: { ...state, pending: null } };
}

const clone = (s: CallState): CallState => JSON.parse(JSON.stringify(s)) as CallState;
const last4 = (e164: string) => e164.replace(/\D/g, "").slice(-4).split("").join(" ");

function mergeDraft(into: RequirementDraft, from: RequirementDraft): boolean {
  let changed = false;
  for (const [k, v] of Object.entries(from) as [keyof RequirementDraft, RequirementDraft[keyof RequirementDraft]][]) {
    if (v == null) continue;
    if (k === "locations") {
      const add = (v as Evidence<string>[]).filter((l) => !into.locations?.some((x) => x.value === l.value));
      if (add.length) {
        into.locations = [...(into.locations ?? []), ...add];
        changed = true;
      }
    } else if (JSON.stringify(into[k]) !== JSON.stringify(v)) {
      // The caller's latest statement wins ("nahi, 3 BHK chahiye").
      (into as Record<string, unknown>)[k] = v;
      changed = true;
    }
  }
  return changed;
}

const BARE_AMOUNT = /(\d[\d,]*(?:\.\d+)?)\s*(k|thousand|hazaa?r|lakhs?|lacs?|lac|l|cr|crores?)?\b/i;
const UNIT_WORD: Record<string, string> = { hazar: "thousand", hazaar: "thousand" };

function filled(state: CallState, slot: Slot): boolean {
  const d = state.draft;
  switch (slot) {
    case "transactionType": return d.transactionType != null;
    case "category": return d.category != null;
    case "locations": return (d.locations?.length ?? 0) > 0;
    case "budget": return d.budgetMax != null || d.budgetMin != null;
    case "name": return state.name != null;
    case "extras": return (d.furnishing != null && d.minParking != null && (d.floorMin != null || d.floorMax != null));
    case "callback":
    case "callbackNumber": return state.callbackSettled;
  }
}

const ORDER: Slot[] = ["transactionType", "category", "locations", "budget", "name", "extras", "callback"];

function question(state: CallState, slot: Slot, ctx: EngineContext): string {
  const l = state.language;
  switch (slot) {
    case "budget": {
      const t = state.draft.transactionType?.value;
      return say(l, t === "RENT" ? "budgetRent" : t === "BUY" ? "budgetBuy" : "budget");
    }
    case "callback":
      return ctx.callerNumber ? say(l, "callbackConfirm", { last4: last4(ctx.callerNumber) }) : say(l, "askNumber");
    case "callbackNumber":
      return say(l, "askNumber");
    default:
      return say(l, slot as PromptKey);
  }
}

function finish(state: CallState, lines: string[]): EngineReply {
  state.done = true;
  state.pending = null;
  lines.push(say(state.language, state.callbackRequested ? "closingCallback" : "closingNoCallback"));
  return { say: lines, action: "HANGUP", state };
}

/** Moves to the next unanswered question, or ends the call. */
function next(state: CallState, lines: string[], ctx: EngineContext): EngineReply {
  // A requested callback skips the remaining requirement questions (the caller wants to go).
  const order: Slot[] = state.callbackRequested ? ["callback"] : ORDER;
  for (const slot of order) {
    if (filled(state, slot) || state.skipped.includes(slot)) continue;
    // Hidden caller ID: ask for a number instead of confirming one.
    state.pending = slot === "callback" && ctx.callerNumber == null ? "callbackNumber" : slot;
    lines.push(question(state, state.pending, ctx));
    return { say: lines, action: "LISTEN", state };
  }
  return finish(state, lines);
}

/** Handles one thing the caller said (empty text = silence / no input). */
export async function respond(prev: CallState, heard: string, ctx: EngineContext): Promise<EngineReply> {
  const state = clone(prev);
  const lines: string[] = [];
  const text = heard.normalize("NFC").trim();
  state.turns += 1;
  if (state.done) return { say: [], action: "HANGUP", state };
  if (state.turns > MAX_TURNS) return giveUp(state, ctx);

  const lang = detectLanguage(text);
  if (lang) state.language = lang;
  const l = () => state.language;

  // --- things callers can say at any point ---
  if (text && wantsHuman(text)) {
    state.humanRequested = true;
    if (ctx.canTransfer) {
      state.done = true;
      return { say: [say(l(), "transfer")], action: "TRANSFER", state };
    }
    state.callbackRequested = true;
    lines.push(say(l(), "noTransfer"));
    return next(state, lines, ctx);
  }
  if (text && wantsCallback(text) && state.pending !== "callback" && state.pending !== "callbackNumber") {
    state.callbackRequested = true;
    lines.push(say(l(), "ackCallback"));
    return next(state, lines, ctx);
  }

  // --- answers to the callback questions ---
  if (state.pending === "callback") {
    const number = spokenPhone(text);
    if (number) {
      state.callbackRequested = true;
      state.callbackSettled = true;
      if (number !== ctx.callerNumber) state.callbackNumber = { value: number, evidence: text };
      return finish(state, lines);
    }
    if (isYes(text)) {
      state.callbackRequested = true;
      state.callbackSettled = true;
      return finish(state, lines);
    }
    if (isNo(text)) {
      state.pending = "callbackNumber";
      state.unclear = 0;
      lines.push(say(l(), "askNumber"));
      return { say: lines, action: "LISTEN", state };
    }
    return unclear(state, lines, ctx);
  }
  if (state.pending === "callbackNumber") {
    const number = spokenPhone(text);
    if (number) {
      state.callbackRequested = true;
      state.callbackSettled = true;
      state.callbackNumber = { value: number, evidence: text };
      return finish(state, lines);
    }
    if (isNo(text) || (text && skips(text))) {
      state.callbackRequested = false;
      state.callbackSettled = true;
      return finish(state, lines);
    }
    if (text) lines.push(say(l(), "invalidNumber"));
    return unclear(state, lines, ctx, false);
  }

  if (!text) return unclear(state, lines, ctx);

  // --- requirement answers ---
  let understood = false;
  const name = statedName(text) ?? (state.pending === "name" ? bareName(text) : null);
  if (name) {
    state.name = { value: name, evidence: text };
    understood = true;
  }

  const extract = ctx.extract ?? extractRequirement;
  const extraction = await extract(text, state.language as VoiceLanguage).catch(() => null);
  if (extraction && mergeDraft(state.draft, extraction.draft)) understood = true;

  if (state.pending === "budget" && !filled(state, "budget")) {
    const m = BARE_AMOUNT.exec(text);
    const unit = m?.[2] ? (UNIT_WORD[m[2].toLowerCase()] ?? m[2]) : undefined;
    const value = m ? parseAmount(m[1], unit) : null;
    if (m && value != null && value >= 1000) {
      state.draft.budgetMax = { value, evidence: m[0].trim() };
      understood = true;
    }
  }
  if (state.pending === "locations" && !filled(state, "locations") && !skips(text)) {
    // Areas missing from the locality list ("Veena Nagar"): keep them exactly as said.
    const words = text.replace(/[.!?।]+$/, "").split(/\s+/);
    if (words.length <= 5 && !/\d/.test(text) && !isYes(text) && !isNo(text)) {
      state.draft.locations = [{ value: text.replace(/[.!?।]+$/, ""), evidence: text }];
      understood = true;
    }
  }
  if (state.pending === "extras") {
    if (!isDone(text) || understood) state.otherNotes.push(text);
    state.skipped.push("extras"); // asked once; don't ask again
    state.unclear = 0;
    return next(state, lines, ctx);
  }

  if (!understood && skips(text)) {
    if (state.pending) state.skipped.push(state.pending);
    state.unclear = 0;
    lines.push(say(l(), "ackSkip"));
    return next(state, lines, ctx);
  }
  if (!understood && isDone(text) && state.pending == null) {
    return next(state, lines, ctx);
  }
  if (!understood) return unclear(state, lines, ctx);

  state.unclear = 0;
  return next(state, lines, ctx);
}

function unclear(state: CallState, lines: string[], ctx: EngineContext, apologise = true): EngineReply {
  state.unclear += 1;
  if (state.unclear > ctx.maxUnclearRetries) return giveUp(state, ctx);
  if (apologise) lines.push(say(state.language, "unclear"));
  lines.push(state.pending ? question(state, state.pending, ctx) : say(state.language, "openQuestion"));
  return { say: lines, action: "LISTEN", state };
}

function giveUp(state: CallState, ctx: EngineContext): EngineReply {
  state.done = true;
  if (ctx.unclearBehavior === "TRANSFER" && ctx.canTransfer) {
    return { say: [say(state.language, "transfer")], action: "TRANSFER", state };
  }
  if (ctx.callerNumber != null || state.callbackNumber != null) state.callbackRequested = true;
  return { say: [say(state.language, "giveUp")], action: "HANGUP", state };
}

const inr = (n: number) => `₹${n.toLocaleString("en-IN")}`;
const LABEL: Record<string, string> = {
  BHK_1: "1 BHK", BHK_2: "2 BHK", BHK_3: "3 BHK", BHK_4: "4 BHK", BHK_5_PLUS: "5+ BHK", STUDIO: "Studio",
  COMMERCIAL: "Commercial", OTHER: "Other", SEMI_FURNISHED: "semi-furnished", FULLY_FURNISHED: "furnished",
  UNFURNISHED: "unfurnished", READY: "ready to move", UNDER_CONSTRUCTION: "under construction",
};

/** One-paragraph summary for the CRM, built only from what the caller said. */
export function summarize(state: CallState, callerNumber: string | null): string {
  const d = state.draft;
  const parts: string[] = [];
  if (d.transactionType) parts.push(d.transactionType.value === "RENT" ? "Rent" : "Buy");
  if (d.category) parts.push(LABEL[d.category.value] ?? d.category.value);
  if (d.locations?.length) parts.push(d.locations.map((x) => x.value).join(", "));
  if (d.budgetMin && d.budgetMax) parts.push(`${inr(d.budgetMin.value)}–${inr(d.budgetMax.value)}`);
  else if (d.budgetMax) parts.push(`up to ${inr(d.budgetMax.value)}`);
  else if (d.budgetMin) parts.push(`from ${inr(d.budgetMin.value)}`);
  if (d.furnishing) parts.push(d.furnishing.value.map((f) => LABEL[f] ?? f).join("/"));
  if (d.minParking) parts.push(`${d.minParking.value} parking`);
  if (d.floorMin) parts.push(`floor ${d.floorMin.value}+`);
  if (d.floorMax) parts.push(`up to floor ${d.floorMax.value}`);
  if (d.possession) parts.push(LABEL[d.possession.value] ?? d.possession.value);
  const lines = [`AI call${state.name ? ` with ${state.name.value}` : ""}: ${parts.length ? parts.join(" · ") : "no requirements stated"}.`];
  if (state.otherNotes.length) lines.push(`Also said: "${state.otherNotes.join(" / ")}".`);
  const skipped = state.skipped.filter((s) => s !== "extras");
  if (skipped.length) lines.push(`Skipped: ${skipped.join(", ")}.`);
  if (state.humanRequested) lines.push("Asked to speak to a person.");
  if (state.callbackRequested) {
    const n = state.callbackNumber?.value ?? callerNumber;
    lines.push(`Callback requested${n ? ` on ${n}` : ""}.`);
  } else if (state.callbackRequested === false) lines.push("Did not want a callback.");
  return lines.join(" ");
}
