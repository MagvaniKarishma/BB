import type { LeadSource } from "@prisma/client";
import { normalizePhone } from "../lib/phone.js";
import type { Evidence } from "../voice/draft.js";

/**
 * Recognises property-portal leads (99acres, Housing.com, Magicbricks) in message text:
 *  - portal notifications ("You have a new lead… Name: …, Mobile: …, Property: …, Price: …"),
 *    usually forwarded by an agent, where the lead is a person named in the text;
 *  - enquiries sent by the lead from a portal's "Chat on WhatsApp" button
 *    ("Hi, I'm interested in your property on 99acres: 2 BHK … for ₹65,000/month").
 *
 * The advertised price belongs to the LISTING. It is returned separately with its text
 * span so requirement extraction can mask it — it must never become the client's budget.
 * Formats vary and change; unrecognised fields are simply left empty.
 */

export type Portal = Extract<LeadSource, "ACRES_99" | "HOUSING_COM" | "MAGICBRICKS">;

export interface Span {
  start: number;
  end: number;
}

export interface PortalLead {
  portal: Portal;
  leadName?: string;
  leadPhone?: string;
  leadEmail?: string;
  listingRef?: string;
  listingUrl?: string;
  /** The advertised price/rent of the listing (not the client's budget). */
  listingPrice?: Evidence<number>;
  /** Spans of every advertised price in the text — masked before budget extraction. */
  priceSpans: Span[];
  /** The lead's own words, when the notification quotes them ("Message: …"). */
  leadMessage?: string;
  leadMessageSpan?: Span;
}

const PORTALS: { portal: Portal; re: RegExp }[] = [
  { portal: "ACRES_99", re: /99\s?acres/i },
  { portal: "HOUSING_COM", re: /housing\.com/i },
  { portal: "MAGICBRICKS", re: /magic\s?bricks/i },
];

export const PORTAL_LABEL: Record<Portal, string> = {
  ACRES_99: "99acres",
  HOUSING_COM: "Housing.com",
  MAGICBRICKS: "Magicbricks",
};

export function detectPortal(text: string): Portal | null {
  const hits = PORTALS.filter((p) => p.re.test(text));
  return hits.length === 1 ? hits[0].portal : hits[0]?.portal ?? null;
}

const UNIT: Record<string, number> = {
  k: 1e3, thousand: 1e3, l: 1e5, lac: 1e5, lacs: 1e5, lakh: 1e5, lakhs: 1e5, cr: 1e7, crore: 1e7, crores: 1e7,
};

/** Parses "65,000", "1.2 Cr", "45 Lac", "₹ 2.4 crore" → rupees. */
export function parseAmount(num: string, unit?: string): number | null {
  const cleaned = num.replace(/,/g, "");
  if (!/^\d+(\.\d+)?$/.test(cleaned)) return null;
  const mult = unit ? UNIT[unit.toLowerCase().replace(/\.$/, "")] ?? 1 : 1;
  return Math.round(Number(cleaned) * mult);
}

const AMOUNT = String.raw`(?:₹|rs\.?|inr)?\s*(\d[\d,]*(?:\.\d+)?)\s*(cr|crores?|lakhs?|lacs?|lac|l|k|thousand)?\.?(?![a-z])`;
// A price is a ₹/Rs amount, or a bare amount right after "price"/"priced at".
const PRICE_RE = new RegExp(
  String.raw`(?:(?:expected\s+)?price|priced\s+at|asking|rent)\s*[:\-]?\s*${AMOUNT}(?:\s*\/\s*(?:month|mo|m|pm))?` +
    String.raw`|(?:₹|rs\.?|inr)\s*(\d[\d,]*(?:\.\d+)?)\s*(cr|crores?|lakhs?|lacs?|lac|l|k|thousand)?\.?(?![a-z])(?:\s*\/\s*(?:month|mo|m|pm))?`,
  "giu",
);
const BUDGET_CUE = /(budget|बजट|within|up\s*to|upto|max(imum)?|under)\s*(is|of|:)?\s*$/i;

const PHONE_RE =
  /(?:mobile|mob|phone|ph|contact|cell|number|whatsapp)\s*(?:no\.?|number|#)?\s*[:\-]?\s*(\+?\d[\d\s\-]{8,16}\d)/i;
const NAME_WITH_PHONE_RE = /([A-Z][a-zA-Z.']+(?:\s+[A-Z][a-zA-Z.']*){0,3})\s*\(\s*(\+?\d[\d\s\-]{8,16}\d)\s*\)/;
const NAME_RE = /(?:^|[\s,|.])(?:name|lead|buyer|tenant|customer)\s*(?:name)?\s*[:\-]\s*([A-Za-z][A-Za-z.' ]{1,60}?)\s*(?=[,|\n]|\s(?:mobile|mob|phone|contact|email|e-mail)|$)/i;
const EMAIL_RE = /[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}/;
const REF_RE = /(?:property|listing|prop)\s*(?:id|code|no\.?)\s*[:#\-]?\s*([A-Z]{0,3}\d{5,12})/i;
const URL_RE = /https?:\/\/(?:www\.)?(?:99acres\.com|housing\.com|magicbricks\.com)\S*/i;
const MESSAGE_RE = /(?:^|[\s|,.])(?:message|msg|query|comment|remarks?|requirement)\s*[:\-]\s*/i;

export function parsePortalLead(input: string): PortalLead | null {
  const text = input.normalize("NFC");
  const portal = detectPortal(text);
  if (!portal) return null;
  const lead: PortalLead = { portal, priceSpans: [] };

  // The lead's own words run from "Message:" to the end (or to the next labelled field).
  const msg = MESSAGE_RE.exec(text);
  let messageSpan: Span | undefined;
  if (msg) {
    const start = msg.index + msg[0].length;
    const rest = text.slice(start);
    const stop = rest.search(/\s[|]\s|\n\s*(?:name|mobile|phone|contact|email|property|price)\s*[:\-]|\s-\s*(?:99acres|housing\.com|magicbricks)/i);
    const end = start + (stop >= 0 ? stop : rest.length);
    const body = text.slice(start, end).trim();
    if (body) {
      messageSpan = { start, end };
      lead.leadMessage = body;
      lead.leadMessageSpan = messageSpan;
    }
  }
  const inMessage = (i: number) => messageSpan != null && i >= messageSpan.start && i < messageSpan.end;

  const withPhone = NAME_WITH_PHONE_RE.exec(text);
  const phoneMatch = PHONE_RE.exec(text);
  const rawPhone = withPhone?.[2] ?? phoneMatch?.[1];
  if (rawPhone) lead.leadPhone = normalizePhone(rawPhone) ?? undefined;
  const name = NAME_RE.exec(text)?.[1]?.trim() ?? withPhone?.[1]?.trim();
  if (name && !/^(customer|sir|madam|user)$/i.test(name)) lead.leadName = name;
  const email = EMAIL_RE.exec(text)?.[0];
  if (email) lead.leadEmail = email.toLowerCase();
  const ref = REF_RE.exec(text)?.[1];
  if (ref) lead.listingRef = ref;
  const url = URL_RE.exec(text)?.[0];
  if (url) {
    lead.listingUrl = url.replace(/[).,]+$/, "");
    lead.listingRef ??= /[-_/]([A-Z]?\d{5,12})(?:[/?#]|$)/i.exec(lead.listingUrl)?.[1];
  }

  for (const m of text.matchAll(PRICE_RE)) {
    const start = m.index ?? 0;
    if (inMessage(start)) continue; // amounts in the lead's own words are theirs (budget), not the listing's
    if (BUDGET_CUE.test(text.slice(Math.max(0, start - 20), start))) continue;
    const value = parseAmount(m[1] ?? m[3], m[2] ?? m[4]);
    if (value == null || value < 1000) continue;
    const span = { start, end: start + m[0].length };
    lead.priceSpans.push(span);
    lead.listingPrice ??= { value, evidence: m[0].trim() };
  }
  return lead;
}
