import type { PortalLeadStatus } from "@prisma/client";
import { prisma } from "../db.js";
import type { AuthUser } from "../lib/auth.js";
import { PORTAL_NAME, type PortalSource, setLeadStatus } from "../services/portalLeads.js";

/**
 * Spoken or typed commands (English, Hindi, Hinglish, Marathi), answered from the CRM:
 *   "Show today's 99acres leads" · "कल की Housing.com leads दिखाओ" · "आजचे 99acres लीड दाखव"
 *   "Show everyone interested in the Andheri property"
 *   "Mark Rahul as contacted" · "Rahul ko contacted mark karo"
 *   "Set a follow-up with Rahul tomorrow at 5 pm" · "राहुलसोबत उद्या फॉलो-अप"
 *
 * Rules, not guesses: a client is chosen only when exactly one client's saved name is in the
 * command; otherwise the choices are returned. Names are matched as saved (Latin letters
 * match Latin, Devanagari matches Devanagari).
 */

export type DateRange = "TODAY" | "YESTERDAY" | "LAST_7_DAYS";

export type Navigate =
  | { screen: "PORTAL_LEADS"; portal: PortalSource; range: DateRange }
  | { screen: "PORTAL_LISTING"; portal: PortalSource; listingId: string; range: DateRange }
  | { screen: "CLIENT"; clientId: string };

export interface CommandResult {
  intent: "SHOW_PORTAL_LEADS" | "SHOW_LISTING_CLIENTS" | "SET_LEAD_STATUS" | "CREATE_FOLLOW_UP" | "UNKNOWN";
  /** What happened, in plain words. */
  message: string;
  /** True when the command changed data. */
  done: boolean;
  navigate?: Navigate;
  /** When the command was ambiguous: the choices. */
  options?: { label: string; navigate: Navigate }[];
}

const has = (text: string, re: RegExp) => re.test(text);

const ACRES = /99\s?-?\s?acres|ninety\s?nine\s?acres|निन्यानवे|नाइंटी\s?नाइन|९९\s?(एकड़|एकर)|99\s?(एकड़|एकर)/i;
const HOUSING = /housing(\.com|\s?dot\s?com)?|हाउसिंग|हौसिंग/i;
const LEADS = /\b(leads?|enquir\w*|inquir\w*)\b|लीड|पूछताछ|चौकशी|इन्क्वायरी/i;
const SHOW = /\b(show|open|list|see|dikhao|dikha|batao|dikhana)\b|दिखाओ|दिखा|बताओ|दाखव|दाखवा|उघड/i;

const TODAY = /\b(today|todays|today's|aaj|aj)\b|आज/i;
const YESTERDAY = /\b(yesterday|kal\s?ki|kal\s?ke|kal\s?wali)\b|कल\s?(की|के|वाली)|काल(च्या|चे|ची)?(?!\s?उद्या)/i;
const LAST7 = /\b(last|past|previous|pichhle|pichle)\s?(7|seven|saat)\s?(days?|din)\b|\b(this|last)\s?week\b|पिछले\s?(7|सात)\s?दिन|मागील\s?(7|सात)\s?दिवस|गेल्या\s?(7|सात)\s?दिवस|हफ्ते|आठवड्या/i;

function rangeOf(text: string): DateRange | null {
  if (has(text, LAST7)) return "LAST_7_DAYS";
  if (has(text, YESTERDAY)) return "YESTERDAY";
  if (has(text, TODAY)) return "TODAY";
  return null;
}

const STATUS: [PortalLeadStatus, RegExp][] = [
  ["NOT_INTERESTED", /\bnot\s?interested\b|\binterest(ed)?\s?nahi\b|रुचि\s?नहीं|इंटरेस्टेड\s?नहीं|इच्छुक\s?नाही|interested\s?नाही/i],
  ["CONVERTED", /\bconverted\b|\bdeal\s?(done|ho\s?gayi|ho\s?gaya|final)\b|डील\s?(हो\s?गई|फाइनल|झाली)|कन्वर्ट/i],
  ["CONTACTED", /\bcontacted\b|\bsampark\b|\bbaat\s?ho\s?gayi\b|संपर्क|बात\s?हो\s?गई|बोलणं\s?झालं|कॉन्टैक्टेड|कॉन्टॅक्टेड/i],
  ["FOLLOW_UP", /\bas\s?follow[\s-]?up\b|\bfollow[\s-]?up\s?(status|mark)\b/i],
  ["CLOSED", /\bclosed?\b|\bband\s?(karo|kar\s?do)\b|बंद/i],
  ["NEW", /\bas\s?new\b/i],
];
const MARK = /\bmark\b|\bset\b.*\bstatus\b|\bstatus\b|मार्क|कर\s?दो|करो|कर$/i;
const FOLLOW_UP = /\bfollow[\s-]?up\b|\bremind(er)?\b|फॉलो[\s-]?अप|फॉलोअप|रिमाइंडर|याद\s?दिला/i;

const TOMORROW = /\b(tomorrow|kal|kl)\b|कल|उद्या|\budya\b/i;
const DAY_AFTER = /\b(day after tomorrow|parso|parson)\b|परसों|परवा/i;
const TIME = /\b(\d{1,2})(?:[:.](\d{2}))?\s*(am|pm|baje|o'?clock)?\b|(\d{1,2})\s*(बजे|वाजता)/i;
const EVENING = /\b(evening|shaam|sham|night|raat)\b|शाम|रात|संध्याकाळ/i;

export const STATUS_LABEL: Record<PortalLeadStatus, string> = {
  NEW: "New", CONTACTED: "Contacted", FOLLOW_UP: "Follow-up", CONVERTED: "Converted", NOT_INTERESTED: "Not interested", CLOSED: "Closed",
};

const escape = (s: string) => s.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
/** Whole-word, case-insensitive; works for Latin and Devanagari (Marathi suffixes like "ला", "सोबत" allowed). */
const containsName = (text: string, name: string) =>
  new RegExp(`(^|[^\\p{L}\\p{M}])${escape(name)}(?=$|[^\\p{L}\\p{M}]|ला|ना|सोबत|च्या|ची|चे|को|के|की|से)`, "iu").test(text);

/** Exactly one client whose full name — else first name — is in the command; otherwise the candidates. */
async function findClient(brokerageId: string, text: string) {
  const clients = await prisma.client.findMany({ where: { brokerageId }, select: { id: true, name: true, primaryPhone: true }, take: 5000 });
  const full = clients.filter((c) => c.name.trim().length >= 3 && containsName(text, c.name.trim()));
  if (full.length === 1) return { client: full[0], candidates: full };
  const pool = full.length > 1 ? full : clients;
  const first = pool.filter((c) => {
    const f = c.name.trim().split(/\s+/)[0];
    return f.length >= 2 && containsName(text, f);
  });
  return { client: first.length === 1 ? first[0] : null, candidates: first };
}

function portalOf(text: string): PortalSource | null {
  const a = has(text, ACRES), h = has(text, HOUSING);
  return a && !h ? "ACRES_99" : h && !a ? "HOUSING_COM" : null;
}

/** Local date/time → instant, for the agent's UTC offset (minutes). */
function localToInstant(tz: number, dayOffset: number, hh: number, mm: number): Date {
  const now = new Date(Date.now() + tz * 60_000);
  return new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate() + dayOffset, hh, mm) - tz * 60_000);
}

export async function runCommand(me: AuthUser, raw: string, tz: number): Promise<CommandResult> {
  const text = raw.normalize("NFC").trim();
  const portal = portalOf(text);
  const help =
    "Try: “Show today's 99acres leads”, “Show everyone interested in the Andheri property”, " +
    "“Mark Rahul as contacted” or “Set a follow-up with Rahul tomorrow”.";

  // --- status change ---
  const status = STATUS.find(([, re]) => re.test(text))?.[0];
  if (status && (MARK.test(text) || /\bas\b/i.test(text)) && !(has(text, FOLLOW_UP) && status === "FOLLOW_UP" && TOMORROW.test(text))) {
    const { client, candidates } = await findClient(me.brokerageId, text);
    if (!client) return ambiguous(candidates, "status");
    const lead = await prisma.portalLead.findFirst({
      where: { brokerageId: me.brokerageId, clientId: client.id, ...(portal ? { portal } : {}) },
      orderBy: { enquiredAt: "desc" },
    });
    if (lead) {
      await setLeadStatus(me.brokerageId, lead.id, status);
      return {
        intent: "SET_LEAD_STATUS", done: true, navigate: { screen: "CLIENT", clientId: client.id },
        message: `${client.name}'s latest ${PORTAL_NAME[lead.portal as PortalSource]} enquiry is now ${STATUS_LABEL[status]}.`,
      };
    }
    // No portal lead: update the client's own status where the meaning is the same.
    const clientStatus = status === "CONTACTED" ? "CONTACTED" : status === "CONVERTED" ? "CLOSED_WON" : status === "NOT_INTERESTED" ? "CLOSED_LOST" : null;
    if (!clientStatus) {
      return { intent: "SET_LEAD_STATUS", done: false, navigate: { screen: "CLIENT", clientId: client.id },
        message: `${client.name} has no portal enquiry to mark ${STATUS_LABEL[status]}. Open their profile to change their status.` };
    }
    await prisma.client.update({ where: { id: client.id }, data: { status: clientStatus } });
    return { intent: "SET_LEAD_STATUS", done: true, navigate: { screen: "CLIENT", clientId: client.id },
      message: `${client.name} is now marked ${STATUS_LABEL[status]}.` };
  }

  // --- follow-up ---
  if (has(text, FOLLOW_UP) && !(has(text, LEADS) && SHOW.test(text))) {
    const { client, candidates } = await findClient(me.brokerageId, text);
    if (!client) return ambiguous(candidates, "follow-up");
    const day = DAY_AFTER.test(text) ? 2 : TOMORROW.test(text) ? 1 : 0;
    const t = TIME.exec(text.replace(/99\s?acres/gi, ""));
    let hh = 10, mm = 0;
    if (t) {
      hh = Number(t[1] ?? t[4]);
      mm = Number(t[2] ?? 0);
      const ap = t[3]?.toLowerCase();
      if (ap === "pm" && hh < 12) hh += 12;
      else if (ap === "am" && hh === 12) hh = 0;
      else if (!ap || ap === "baje" || t[5]) { if (hh < 8 || EVENING.test(text)) hh = hh < 12 ? hh + 12 : hh; }
      if (hh > 23 || mm > 59) [hh, mm] = [10, 0];
    }
    let dueAt = localToInstant(tz, day, hh, mm);
    if (dueAt.getTime() < Date.now()) dueAt = new Date(Date.now() + 60 * 60_000); // "today" at a time already past
    await prisma.reminder.create({
      data: {
        brokerageId: me.brokerageId, clientId: client.id, assignedToId: me.id, createdById: me.id,
        dueAt, title: `Follow up with ${client.name}`, kind: "FOLLOW_UP",
      },
    });
    const when = new Date(dueAt.getTime() + tz * 60_000);
    const label = `${day === 0 ? "today" : day === 1 ? "tomorrow" : "the day after tomorrow"} at ${String(when.getUTCHours()).padStart(2, "0")}:${String(when.getUTCMinutes()).padStart(2, "0")}`;
    return { intent: "CREATE_FOLLOW_UP", done: true, navigate: { screen: "CLIENT", clientId: client.id },
      message: `Follow-up with ${client.name} set for ${label}.` };
  }

  // --- everyone interested in a property ---
  if (/interested|intrested|interest|रुचि|इंटरेस्ट|इच्छुक|कौन|who/i.test(text) || /\b(property|flat|listing)\b|प्रॉपर्टी|फ्लॅट|फ्लैट/i.test(text) && !has(text, LEADS)) {
    const listings = await prisma.portalListing.findMany({
      where: { brokerageId: me.brokerageId, ...(portal ? { portal } : {}), leads: { some: {} } },
      include: { _count: { select: { leads: true } } },
      take: 2000,
    });
    const hits = listings.filter((l) =>
      [l.locality, l.title, l.externalId].some((f) => f && f.trim().length >= 3 && containsName(text, f.trim())) ||
      (l.locality ?? "").split(/[\s,]+/).some((w) => w.length >= 4 && containsName(text, w)));
    const range = rangeOf(text) ?? "LAST_7_DAYS";
    if (hits.length === 1) {
      const l = hits[0];
      return { intent: "SHOW_LISTING_CLIENTS", done: false, navigate: { screen: "PORTAL_LISTING", portal: l.portal as PortalSource, listingId: l.id, range },
        message: `Clients interested in ${l.title ?? "the listing"}${l.locality ? `, ${l.locality}` : ""} on ${PORTAL_NAME[l.portal as PortalSource]}.` };
    }
    if (hits.length > 1) {
      return { intent: "SHOW_LISTING_CLIENTS", done: false, message: "More than one listing matches. Which one?",
        options: hits.slice(0, 8).map((l) => ({
          label: `${PORTAL_NAME[l.portal as PortalSource]} · ${l.title ?? "Listing"}${l.locality ? `, ${l.locality}` : ""}`,
          navigate: { screen: "PORTAL_LISTING", portal: l.portal as PortalSource, listingId: l.id, range },
        })) };
    }
    if (!has(text, LEADS)) {
      return { intent: "SHOW_LISTING_CLIENTS", done: false, message: "No 99acres or Housing.com listing with enquiries matches that area or name." };
    }
  }

  // --- portal leads for a day ---
  if (portal || has(text, LEADS)) {
    const range = rangeOf(text) ?? "TODAY";
    if (portal) {
      return { intent: "SHOW_PORTAL_LEADS", done: false, navigate: { screen: "PORTAL_LEADS", portal, range },
        message: `${PORTAL_NAME[portal]} leads — ${range === "TODAY" ? "today" : range === "YESTERDAY" ? "yesterday" : "last 7 days"}.` };
    }
    return { intent: "SHOW_PORTAL_LEADS", done: false, message: "Which portal?",
      options: (["ACRES_99", "HOUSING_COM"] as const).map((p) => ({ label: PORTAL_NAME[p], navigate: { screen: "PORTAL_LEADS", portal: p, range } })) };
  }

  return { intent: "UNKNOWN", done: false, message: `Sorry, I didn't understand that. ${help}` };
}

function ambiguous(candidates: { id: string; name: string; primaryPhone: string }[], what: string): CommandResult {
  if (candidates.length === 0) {
    return { intent: what === "status" ? "SET_LEAD_STATUS" : "CREATE_FOLLOW_UP", done: false,
      message: "I couldn't find that client. Say their name as it's saved in BrokerBuddy." };
  }
  return {
    intent: what === "status" ? "SET_LEAD_STATUS" : "CREATE_FOLLOW_UP", done: false,
    message: `More than one client has that name — nothing was changed. Open the right one:`,
    options: candidates.slice(0, 8).map((c) => ({ label: `${c.name} · ${c.primaryPhone}`, navigate: { screen: "CLIENT", clientId: c.id } })),
  };
}
