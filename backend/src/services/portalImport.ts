import type { PropertyCategory, TransactionType } from "@prisma/client";
import { parseAmount } from "../whatsapp/portalLeads.js";
import type { LeadInput, PortalSource } from "./portalLeads.js";

/**
 * Reading portal lead exports (the Excel/CSV file the portal lets an advertiser download).
 * Column names differ between portals and change over time, so columns are recognised by
 * common header names. A row is imported only when it has an enquiry date and a way to
 * identify the person (phone or email); nothing is filled in that the file doesn't contain.
 */

/** RFC 4180 CSV: quoted fields, doubled quotes, commas/newlines inside quotes. */
export function parseCsv(text: string): string[][] {
  const rows: string[][] = [];
  let row: string[] = [];
  let field = "";
  let quoted = false;
  const s = text.replace(/^﻿/, "");
  for (let i = 0; i < s.length; i++) {
    const c = s[i];
    if (quoted) {
      if (c === '"' && s[i + 1] === '"') { field += '"'; i++; }
      else if (c === '"') quoted = false;
      else field += c;
    } else if (c === '"' && field === "") quoted = true;
    else if (c === ",") { row.push(field); field = ""; }
    else if (c === "\n" || c === "\r") {
      if (c === "\r" && s[i + 1] === "\n") i++;
      row.push(field); field = "";
      if (row.some((f) => f.trim() !== "")) rows.push(row);
      row = [];
    } else field += c;
  }
  row.push(field);
  if (row.some((f) => f.trim() !== "")) rows.push(row);
  return rows;
}

type Field =
  | "name" | "phone" | "email" | "enquiredAt" | "message" | "externalLeadId"
  | "listingId" | "url" | "title" | "locality" | "price" | "budget" | "area" | "photo";

/** Header names (lower case, without punctuation) recognised for each field; first match wins. */
const HEADERS: Record<Field, string[]> = {
  externalLeadId: ["lead id", "response id", "enquiry id", "inquiry id", "query id"],
  listingId: ["property id", "listing id", "prop id", "property code", "listing code", "ad id"],
  name: ["name", "lead name", "customer name", "buyer name", "tenant name", "contact name", "client name", "sender name"],
  phone: ["mobile", "mobile number", "mobile no", "phone", "phone number", "contact number", "contact no", "mobile no."],
  email: ["email", "email id", "e-mail", "email address"],
  enquiredAt: ["enquiry date", "inquiry date", "lead date", "response date", "received on", "date & time", "date and time", "date", "created at", "created on", "time"],
  message: ["message", "query", "remarks", "comments", "comment", "enquiry message", "user message"],
  url: ["property url", "listing url", "property link", "listing link", "url", "link"],
  title: ["property title", "listing title", "property name", "property", "listing", "project", "project name"],
  locality: ["locality", "location", "area name", "sub locality", "city locality"],
  price: ["price", "expected price", "rent", "listed price", "property price"],
  budget: ["budget", "client budget", "buyer budget"],
  area: ["carpet area", "area sqft", "area (sq ft)", "super area", "built up area"],
  photo: ["photo", "image", "photo url", "image url", "thumbnail"],
};

const norm = (h: string) => h.toLowerCase().replace(/[_.:()]+/g, " ").replace(/\s+/g, " ").trim();

export function mapHeaders(header: string[]): Partial<Record<Field, number>> {
  const cols = header.map(norm);
  const map: Partial<Record<Field, number>> = {};
  const taken = new Set<number>();
  for (const field of Object.keys(HEADERS) as Field[]) {
    for (const name of HEADERS[field]) {
      const i = cols.findIndex((c, idx) => !taken.has(idx) && c === norm(name));
      if (i >= 0) { map[field] = i; taken.add(i); break; }
    }
  }
  return map;
}

const MONTHS = ["jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec"];

/**
 * Enquiry date/time as written in Indian exports: ISO, "26/09/2026 10:15 AM", "26-09-2026",
 * "26 Sep 2026, 10:15 am". Without a time zone the time is India time. Day comes before month.
 */
export function parseEnquiryDate(raw: string, istOffsetMinutes = 330): Date | null {
  const s = raw.trim();
  if (!s) return null;
  if (/^\d{4}-\d{2}-\d{2}T.*(Z|[+-]\d{2}:?\d{2})$/.test(s)) {
    const d = new Date(s);
    return Number.isNaN(d.getTime()) ? null : d;
  }
  let day: number, month: number, year: number;
  let rest: string;
  let m = /^(\d{4})-(\d{1,2})-(\d{1,2})[T\s]*(.*)$/.exec(s);
  if (m) { [year, month, day] = [+m[1], +m[2], +m[3]]; rest = m[4]; }
  else if ((m = /^(\d{1,2})[/.-](\d{1,2})[/.-](\d{2,4})[,\s]*(.*)$/.exec(s))) {
    [day, month, year] = [+m[1], +m[2], +m[3]]; rest = m[4];
  } else if ((m = /^(\d{1,2})[\s-]([A-Za-z]{3,9})[\s-,]*(\d{4})[,\s]*(.*)$/.exec(s))) {
    const mi = MONTHS.indexOf(m[2].slice(0, 3).toLowerCase());
    if (mi < 0) return null;
    [day, month, year] = [+m[1], mi + 1, +m[3]]; rest = m[4];
  } else return null;
  if (year < 100) year += 2000;
  let hh = 0, mm = 0;
  const t = /^(\d{1,2}):(\d{2})(?::\d{2})?\s*(am|pm)?/i.exec(rest.trim());
  if (t) {
    hh = +t[1]; mm = +t[2];
    const ap = t[3]?.toLowerCase();
    if (ap === "pm" && hh < 12) hh += 12;
    if (ap === "am" && hh === 12) hh = 0;
  } else if (rest.trim()) return null;
  if (month < 1 || month > 12 || day < 1 || day > 31 || hh > 23 || mm > 59) return null;
  const utc = Date.UTC(year, month - 1, day, hh, mm) - istOffsetMinutes * 60_000;
  const d = new Date(utc);
  return new Date(d.getTime() + istOffsetMinutes * 60_000).getUTCDate() === day ? d : null;
}

function amount(raw: string | undefined): number | null {
  if (!raw) return null;
  const m = /(\d[\d,]*(?:\.\d+)?)\s*(cr|crores?|lakhs?|lacs?|lac|l|k|thousand)?/i.exec(raw);
  return m ? parseAmount(m[1], m[2]) : null;
}

/** Type/BHK and rent/sale, only when the title says so. */
export function listingFactsFromTitle(title: string | null | undefined): { category?: PropertyCategory; transactionType?: TransactionType } {
  if (!title) return {};
  const out: { category?: PropertyCategory; transactionType?: TransactionType } = {};
  const bhk = /\b(\d)\s?BHK\b/i.exec(title);
  if (bhk) out.category = (+bhk[1] >= 5 ? "BHK_5_PLUS" : `BHK_${bhk[1]}`) as PropertyCategory;
  else if (/\b(1\s?RK|studio)\b/i.test(title)) out.category = "STUDIO";
  else if (/\b(shop|office|commercial|showroom)\b/i.test(title)) out.category = "COMMERCIAL";
  if (/\b(for rent|on rent|rental|lease)\b/i.test(title)) out.transactionType = "RENT";
  else if (/\b(for sale|resale|to buy)\b/i.test(title)) out.transactionType = "BUY";
  return out;
}

export interface CsvRowResult {
  row: number;
  lead?: LeadInput;
  error?: string;
}

export function leadsFromCsv(portal: PortalSource, csv: string, batchRef: string): { columns: Partial<Record<Field, number>>; rows: CsvRowResult[] } {
  const table = parseCsv(csv);
  if (table.length === 0) return { columns: {}, rows: [] };
  const columns = mapHeaders(table[0]);
  const get = (r: string[], f: Field) => (columns[f] != null ? r[columns[f]!]?.trim() || undefined : undefined);
  const rows: CsvRowResult[] = table.slice(1).map((r, i) => {
    const row = i + 2; // 1-based, after the header
    const when = get(r, "enquiredAt");
    const enquiredAt = when ? parseEnquiryDate(when) : null;
    if (!enquiredAt) return { row, error: when ? `Unrecognised date "${when}"` : "No enquiry date" };
    const phone = get(r, "phone");
    const email = get(r, "email");
    if (!phone && !email) return { row, error: "No phone number or email" };
    const title = get(r, "title");
    const budget = amount(get(r, "budget"));
    const area = amount(get(r, "area"));
    const raw = Object.fromEntries(table[0].map((h, idx) => [h, r[idx] ?? ""]));
    return {
      row,
      lead: {
        portal,
        channel: "CSV",
        sourceRef: batchRef,
        externalLeadId: get(r, "externalLeadId"),
        enquiredAt,
        name: get(r, "name"),
        phone,
        email,
        message: get(r, "message"),
        budgetMax: budget,
        listing: {
          externalId: get(r, "listingId"),
          url: get(r, "url"),
          title,
          locality: get(r, "locality"),
          price: amount(get(r, "price")),
          carpetAreaSqft: area != null && area < 1_000_000 ? area : null,
          photoUrl: get(r, "photo"),
          ...listingFactsFromTitle(title),
        },
        raw,
      },
    };
  });
  return { columns, rows };
}
