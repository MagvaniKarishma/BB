import type { Furnishing, PropertyCategory, TransactionType } from "@prisma/client";
import type { Evidence, Extraction, RequirementDraft } from "./draft.js";
import { verifyDraft } from "./draft.js";
import { DIRECTIONAL, FRACTION_PREFIX, LOCALITIES, MONEY_UNITS, NUMBER_WORDS } from "./lexicon.js";

/**
 * Deterministic requirement extractor for Hindi, Hinglish, Marathi and English notes.
 *
 * Principles:
 *  - Only phrases actually present produce values; every value carries its source text.
 *  - Ambiguity (two sizes, rent *and* buy, "high floor" without a number) produces a
 *    warning for the agent instead of a guess.
 *  - Interpretations (a lone amount read as the maximum budget) are flagged as warnings.
 */

// ---------- regex helpers ----------

const L = "(?<![\\p{L}\\p{M}\\p{N}])"; // word start (works for Devanagari)
const R = "(?![\\p{L}\\p{M}\\p{N}])"; // word end
const NUKTA = "़";

const esc = (s: string) => s.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");

/** Alternation of words: NFC, nukta optional, flexible spacing, longest first. */
function alt(words: string[]): string {
  return [...new Set(words)]
    .map((w) => w.normalize("NFC"))
    .sort((a, b) => b.length - a.length)
    .map((w) => esc(w).split(NUKTA).join(`${NUKTA}?`).replace(/ /g, "\\s*"))
    .join("|");
}

const rx = (src: string) => new RegExp(src, "giu");
const stripNukta = (s: string) => s.normalize("NFC").split(NUKTA).join("").toLowerCase().replace(/\s+/g, " ").trim();

const numberLookup = new Map(Object.entries(NUMBER_WORDS).map(([k, v]) => [stripNukta(k), v]));
const fractionLookup = new Map(Object.entries(FRACTION_PREFIX).map(([k, v]) => [stripNukta(k), v]));

const DIGITS = "[0-9०-९]+(?:[.,][0-9०-९]+)*";
const NUM_WORD = alt(Object.keys(NUMBER_WORDS));
const FRACTION = alt(Object.keys(FRACTION_PREFIX));
const NUM = `(?:(?:${FRACTION})\\s+)?(?:${DIGITS}|${NUM_WORD})`;

const devanagariDigits = (s: string) => s.replace(/[०-९]/g, (d) => String(d.charCodeAt(0) - 0x0966));

/** Parses a NUM match ("50", "1,20,000", "1.5", "pachaas", "साढ़े तीन", "dedh"). */
export function parseNumber(raw: string): number | null {
  let text = stripNukta(devanagariDigits(raw));
  let fraction = 0;
  for (const [word, f] of fractionLookup) {
    if (text.startsWith(`${word} `)) {
      fraction = f;
      text = text.slice(word.length + 1).trim();
      break;
    }
  }
  let value: number | null = null;
  if (/^\d/.test(text)) {
    if (/^\d{1,3}(,\d{2,3})+$/.test(text)) value = Number(text.replace(/,/g, ""));
    else if (/^\d+(\.\d+)?$/.test(text)) value = Number(text);
    else if (/^\d+,\d$/.test(text)) value = Number(text.replace(",", "."));
  } else {
    value = numberLookup.get(text) ?? null;
  }
  if (value == null || Number.isNaN(value)) return null;
  return value + fraction;
}

interface Span {
  start: number;
  end: number;
}
const overlaps = (a: Span, b: Span) => a.start < b.end && b.start < a.end;

const inr = (n: number) => `₹${n.toLocaleString("en-IN")}`;

// ---------- vocabulary patterns ----------

const UNIT_WORDS = MONEY_UNITS.flatMap((u) => u.words);
const UNIT = alt(UNIT_WORDS);
const unitMultiplier = (w: string) => {
  const key = stripNukta(w);
  return MONEY_UNITS.find((u) => u.words.some((x) => stripNukta(x) === key))?.multiplier ?? 1;
};

const RANGE_SEP = alt(["se", "to", "ते", "से", "or", "ya", "या", "किंवा"]) + "|-|–";
const NEGATION = alt(["nahi", "nahin", "nai", "नहीं", "नही", "नको", "nako", "not", "mat", "मत", "no need", "नहीं चाहिए"]);

const RENT_WORDS = alt([
  "rent", "rental", "renting", "on rent", "kiraya", "kiraaya", "kiraye", "kiraaye", "kiraye", "kirae",
  "किराया", "किराये", "किराए", "किराय", "रेंट", "भाड्याने", "भाड्यावर", "भाडे", "bhadyane", "lease",
  "leave and license",
]);
const BUY_WORDS = alt([
  "buy", "buying", "purchase", "kharidna", "kharidni", "kharidana", "kharid", "khareed", "khareedna",
  "khareedni", "kharidne", "खरीदना", "खरीदनी", "खरीदने", "खरीद", "विकत", "vikat", "resale", "ownership",
]);
const SELL_WORDS = alt(["sell", "selling", "bechna", "bechni", "बेचना", "बेचनी", "विकायचा", "विकायचे"]);

const BHK_WORDS = alt(["bhk", "b h k", "बीएचके", "बी एच के", "bedroom", "bedrooms", "बेडरूम", "bed room"]);
const RK_WORDS = alt(["rk", "r k", "आरके", "आर के"]);
const STUDIO_WORDS = alt(["studio", "स्टूडियो", "स्टुडिओ"]);
const COMMERCIAL_WORDS = alt([
  "shop", "dukan", "dukaan", "दुकान", "office", "ऑफिस", "ओफिस", "commercial", "कमर्शियल", "godown",
  "गोदाम", "showroom", "शोरूम", "गाळा",
]);
const NEAR_WORDS = alt(["ke paas", "के पास", "near", "jaane", "जाने", "जवळ", "se door", "से दूर", "ke pass"]);

const DIRECTION = alt(["west", "east", "w", "e", "पश्चिम", "पूर्व", "वेस्ट", "ईस्ट"]);
const WEST = new Set(["west", "w", "पश्चिम", "वेस्ट"].map(stripNukta));

const FULLY = alt(["fully furnished", "full furnished", "fully furnish", "poora furnished", "pura furnished",
  "पूरा फर्निश्ड", "फुली फर्निश्ड", "फुल फर्निश्ड", "फुल्ली फर्निश्ड"]);
const SEMI = alt(["semi furnished", "semi-furnished", "semifurnished", "semi furnish", "सेमी फर्निश्ड", "सेमी फर्निशड", "सेमी-फर्निश्ड"]);
const UNFURNISHED = alt(["unfurnished", "un-furnished", "non furnished", "without furniture", "bina furniture",
  "बिना फर्नीचर", "अनफर्निश्ड", "फर्निचर नको", "khali flat", "खाली फ्लैट"]);
const FURNISHED = alt(["furnished", "फर्निश्ड", "फर्निशड", "फर्निश"]);

const CAR = alt(["car", "cars", "gaadi", "gadi", "गाड़ी", "गाडी", "कार", "four wheeler", "चारचाकी", "bike", "two wheeler"]);
const PARKING = alt(["parking", "पार्किंग", "पार्किग"]);

const FLOOR = alt(["floor", "फ्लोर", "मंज़िल", "मंजिल", "माळा", "मजला", "majla", "manzil", "storey"]);
const ORDINAL_WORDS: Record<string, number> = {
  pehla: 1, pahla: 1, "पहला": 1, "पहिला": 1, first: 1,
  doosra: 2, dusra: 2, "दूसरा": 2, "दुसरा": 2, second: 2,
  teesra: 3, tisra: 3, "तीसरा": 3, "तिसरा": 3, third: 3,
  chautha: 4, "चौथा": 4, "चौथे": 4, fourth: 4,
  paanchva: 5, "पांचवां": 5, "पाँचवाँ": 5, "पाचवा": 5, fifth: 5,
};
const ordinalLookup = new Map(Object.entries(ORDINAL_WORDS).map(([k, v]) => [stripNukta(k), v]));
const ORD = `(?:[0-9०-९]+\\s*(?:st|nd|rd|th|वां|वीं|वा|वी|वे|वें|वाँ)?|${alt(Object.keys(ORDINAL_WORDS))}|${NUM_WORD})`;
const parseOrdinal = (raw: string): number | null => {
  const t = stripNukta(devanagariDigits(raw)).replace(/\s*(st|nd|rd|th|वां|वीं|वा|वी|वे|वें|वाँ)$/u, "");
  if (/^\d+$/.test(t)) return Number(t);
  return ordinalLookup.get(t) ?? numberLookup.get(t) ?? null;
};
const ABOVE = alt(["upar", "uper", "ऊपर", "उपर", "above", "plus", "वर", "var", "aur upar", "or above", "ya upar", "se upar", "से ऊपर", "ke upar", "के ऊपर", "च्या वर"]);
const BELOW = alt(["tak", "तक", "पर्यंत", "paryant", "ke neeche", "के नीचे", "below", "or below", "se neeche", "से नीचे", "च्या खाली"]);
const LINK = alt(["ke", "के", "se", "से", "च्या", "chya"]);

const READY = alt(["ready to move", "ready-to-move", "ready possession", "ready flat", "ready to shift",
  "रेडी टू मूव", "रेडी पज़ेशन", "रेडी पजेशन", "तैयार फ्लैट", "तयार फ्लॅट", "oc received"]);
const UNDER_CONSTRUCTION = alt(["under construction", "under-construction", "अंडर कंस्ट्रक्शन", "अंडर कन्स्ट्रक्शन",
  "निर्माणाधीन", "new launch", "pre launch", "pre-launch", "प्री लॉन्च"]);
const POSSESSION_CONTEXT = alt(["possession", "posession", "पज़ेशन", "पजेशन", "पोज़ेशन", "ताबा", "taba", "shift", "शिफ्ट", "move", "handover"]);
const MONTHS: Record<string, number> = {
  jan: 1, january: 1, "जनवरी": 1, feb: 2, february: 2, "फरवरी": 2, "फेब्रुवारी": 2, mar: 3, march: 3, "मार्च": 3,
  apr: 4, april: 4, "अप्रैल": 4, "एप्रिल": 4, may: 5, "मई": 5, "मे": 5, jun: 6, june: 6, "जून": 6,
  jul: 7, july: 7, "जुलाई": 7, "जुलै": 7, aug: 8, august: 8, "अगस्त": 8, "ऑगस्ट": 8, sep: 9, sept: 9,
  september: 9, "सितंबर": 9, "सप्टेंबर": 9, oct: 10, october: 10, "अक्टूबर": 10, "ऑक्टोबर": 10,
  nov: 11, november: 11, "नवंबर": 11, "नोव्हेंबर": 11, dec: 12, december: 12, "दिसंबर": 12, "डिसेंबर": 12,
};
const monthLookup = new Map(Object.entries(MONTHS).map(([k, v]) => [stripNukta(k), v]));
const MONTH = alt(Object.keys(MONTHS));
const BY_AFTER = alt(["tak", "तक", "पर्यंत", "paryant", "se pehle", "से पहले", "before", "by"]);
const BY_BEFORE = alt(["by", "before", "till", "until"]);

// ---------- extractor ----------

export interface ExtractOptions {
  /** Character ranges (in the NFC text) to ignore, e.g. a portal listing's price. */
  mask?: Span[];
}

/** Blanks out ranges with spaces so offsets (and verbatim evidence) stay valid. */
function applyMask(text: string, mask: Span[] = []): string {
  if (mask.length === 0) return text;
  const chars = text.split("");
  for (const { start, end } of mask) for (let i = Math.max(0, start); i < Math.min(end, chars.length); i++) chars[i] = " ";
  return chars.join("");
}

export function extractWithRules(transcript: string, options: ExtractOptions = {}): Extraction {
  const text = applyMask(transcript.normalize("NFC"), options.mask);
  const advertisedPrices: Evidence<number>[] = [];
  const draft: RequirementDraft = {};
  const warnings: string[] = [];
  const ev = <T>(value: T, m: { index?: number; 0: string }): Evidence<T> => ({ value, evidence: m[0].trim() });
  const after = (end: number, n = 30) => text.slice(end, end + n);
  const before = (start: number, n = 30) => text.slice(Math.max(0, start - n), start);

  // --- transaction type ---
  const rentHits = [...text.matchAll(rx(`${L}(?:${RENT_WORDS})${R}`))];
  const buyHits = [...text.matchAll(rx(`${L}(?:${BUY_WORDS})[\\p{L}\\p{M}]*`))];
  if (rentHits.length && buyHits.length) {
    warnings.push("Mentions both renting and buying — choose the transaction type (or record two inquiries)");
  } else if (rentHits.length) {
    draft.transactionType = ev<TransactionType>("RENT", rentHits[0]);
  } else if (buyHits.length) {
    draft.transactionType = ev<TransactionType>("BUY", buyHits[0]);
  }
  if (rx(`${L}(?:${SELL_WORDS})${R}`).test(text)) {
    warnings.push("Mentions selling — if this is an owner's listing, add it under Properties instead");
  }

  // --- property category ---
  const categories = new Map<PropertyCategory, Evidence<PropertyCategory>>();
  const toCategory = (n: number): PropertyCategory | null =>
    n >= 5 ? "BHK_5_PLUS" : n >= 4 ? "BHK_4" : n >= 3 ? "BHK_3" : n >= 2 ? "BHK_2" : n >= 1 ? "BHK_1" : null;
  const altSizes = [...text.matchAll(rx(`${L}(${NUM})\\s*(?:${RANGE_SEP}|,|/)\\s*(${NUM})\\s*(?:${BHK_WORDS})${R}`))];
  for (const m of altSizes) {
    for (const raw of [m[1], m[2]]) {
      const n = parseNumber(raw);
      const cat = n == null ? null : toCategory(n);
      if (cat) categories.set(cat, ev(cat, m));
    }
  }
  for (const m of text.matchAll(rx(`${L}(${NUM})\\s*(?:${BHK_WORDS})${R}`))) {
    const n = parseNumber(m[1]);
    const cat = n == null ? null : toCategory(n);
    if (!cat) continue;
    if (n != null && !Number.isInteger(n)) warnings.push(`"${m[0].trim()}" recorded as ${Math.floor(n)} BHK — note the extra half room if it matters`);
    if (!categories.has(cat)) categories.set(cat, ev(cat, m));
  }
  for (const m of text.matchAll(rx(`${L}(?:(?:1|one|ek|एक|वन)\\s*)?(?:${RK_WORDS})${R}|${L}(?:${STUDIO_WORDS})${R}`))) {
    categories.set("STUDIO", ev<PropertyCategory>("STUDIO", m));
  }
  for (const m of text.matchAll(rx(`${L}(?:${COMMERCIAL_WORDS})${R}`))) {
    const end = (m.index ?? 0) + m[0].length;
    if (rx(`^\\s*(?:${NEAR_WORDS})`).test(after(end))) continue; // "office ke paas" is a location hint
    if (!categories.has("COMMERCIAL")) categories.set("COMMERCIAL", ev<PropertyCategory>("COMMERCIAL", m));
  }
  if (categories.size === 1) {
    draft.category = [...categories.values()][0];
  } else if (categories.size > 1) {
    const labels = [...categories.values()].map((c) => c.evidence).join(", ");
    warnings.push(`Several property types mentioned (${labels}) — pick one, or record a separate inquiry for each`);
  }

  // --- money ---
  interface Amount extends Span {
    text: string;
    min?: number;
    max?: number;
    single?: number;
  }
  const amounts: Amount[] = [];
  const addAmount = (a: Amount) => {
    if (!amounts.some((x) => overlaps(x, a))) amounts.push(a);
  };
  for (const m of text.matchAll(rx(`${L}(${NUM})\\s*(${UNIT})?\\s*(?:${RANGE_SEP})\\s*(${NUM})\\s*(${UNIT})${R}`))) {
    const hi = parseNumber(m[3]);
    const lo = parseNumber(m[1]);
    if (hi == null || lo == null) continue;
    const hiMul = unitMultiplier(m[4]);
    const loMul = m[2] ? unitMultiplier(m[2]) : hiMul;
    const start = m.index ?? 0;
    addAmount({ start, end: start + m[0].length, text: m[0].trim(), min: Math.round(lo * loMul), max: Math.round(hi * hiMul) });
  }
  for (const m of text.matchAll(rx(`${L}(${NUM})\\s*(${UNIT})${R}`))) {
    const n = parseNumber(m[1]);
    if (n == null) continue;
    const start = m.index ?? 0;
    addAmount({ start, end: start + m[0].length, text: m[0].trim(), single: Math.round(n * unitMultiplier(m[2])) });
  }
  const RUPEE = alt(["rs", "rs.", "inr", "rupees", "rupaye", "rupaiye", "रुपये", "रुपए", "रु"]);
  for (const m of text.matchAll(rx(`(?:₹|${L}(?:${RUPEE}))\\s*(${DIGITS})${R}|${L}(${DIGITS})\\s*(?:${RUPEE})${R}`))) {
    const n = parseNumber(m[1] ?? m[2]);
    if (n == null) continue;
    const start = m.index ?? 0;
    addAmount({ start, end: start + m[0].length, text: m[0].trim(), single: Math.round(n) });
  }
  const BUDGET_WORDS = alt(["budget", "बजट", "बजेट", "rent", "kiraya", "किराया", "भाडे"]);
  for (const m of text.matchAll(rx(`${L}(?:${BUDGET_WORDS})\\s*(?:\\S+\\s+)?(${DIGITS})${R}`))) {
    const n = parseNumber(m[1]);
    if (n == null || n < 1000) continue; // bare small numbers are too ambiguous
    const numStart = (m.index ?? 0) + m[0].lastIndexOf(m[1]);
    addAmount({ start: numStart, end: numStart + m[1].length, text: m[1], single: Math.round(n) });
  }
  amounts.sort((a, b) => a.start - b.start);

  const NOT_BUDGET = alt(["deposit", "डिपॉजिट", "डिपॉज़िट", "डिपॉझिट", "advance", "एडवांस", "security", "maintenance",
    "मेंटेनेंस", "brokerage", "ब्रोकरेज", "token", "टोकन", "salary", "सैलरी", "पगार", "income"]);
  const MAX_AFTER = alt(["tak", "तक", "पर्यंत", "paryant", "max", "maximum", "se kam", "से कम", "ke andar", "के अंदर",
    "within", "tak chalega", "तक चलेगा", "ke under", "पेक्षा कमी"]);
  const MAX_BEFORE = alt(["under", "upto", "up to", "max", "maximum", "below", "within", "less than", "ज़्यादा से ज़्यादा", "zyada se zyada"]);
  const MIN_BEFORE = alt(["minimum", "min", "at least", "kam se kam", "कम से कम", "above", "more than"]);
  const MIN_AFTER = alt(["se upar", "से ऊपर", "se zyada", "से ज़्यादा", "plus", "above", "पेक्षा जास्त", "च्या वर"]);

  // "asking price 65k", "listed at 1.2 cr", "कीमत 90 लाख": a property's price, not the budget.
  const PRICE_BEFORE = alt(["price", "priced at", "priced", "asking", "asking price", "listed at", "listed for",
    "listing price", "advertised", "expected price", "quoted", "quote", "कीमत", "क़ीमत", "किंमत", "प्राइस",
    "rent is", "rent hai", "ka rent", "ka price", "ki price", "ki kimat", "kimat", "keemat"]);
  const BUDGET_CUE = alt(["budget", "बजट", "बजेट", "tak", "तक", "पर्यंत", "max", "maximum", "within", "upto", "up to", "under"]);

  const mins: Amount[] = [];
  const maxes: Amount[] = [];
  for (const a of amounts) {
    if (
      rx(`(?:${PRICE_BEFORE})\\s*(?:is|of|hai|है|:|-|=)?\\s*$`).test(before(a.start, 30)) &&
      !rx(`(?:${BUDGET_CUE})`).test(before(a.start, 30)) &&
      !rx(`^\\s*(?:${MAX_AFTER}|${MIN_AFTER})`).test(after(a.end))
    ) {
      advertisedPrices.push({ value: a.max ?? a.single!, evidence: a.text });
      warnings.push(`${a.text} is a property's price, not the client's budget — kept separate`);
      continue;
    }
    if (rx(`(?:${NOT_BUDGET})[\\s\\S]{0,12}$`).test(before(a.start, 25)) || rx(`^\\s*(?:ka|की|का|ki)?\\s*(?:${NOT_BUDGET})`).test(after(a.end))) {
      warnings.push(`Ignored ${a.text} — it refers to deposit/maintenance/other costs, not the budget`);
      continue;
    }
    if (a.min != null && a.max != null) {
      mins.push({ ...a, single: a.min });
      maxes.push({ ...a, single: a.max });
    } else if (rx(`^\\s*(?:${MIN_AFTER})\\s*(?:${NEGATION})${R}`).test(after(a.end))) {
      maxes.push(a); // "35000 se zyada nahi" = not more than
    } else if (rx(`^\\s*(?:${alt(["se kam", "से कम", "पेक्षा कमी"])})\\s*(?:${NEGATION})${R}`).test(after(a.end))) {
      mins.push(a); // "50 lakh se kam nahi" = not less than
    } else if (rx(`^\\s*(?:${MIN_AFTER})`).test(after(a.end)) || rx(`(?:${MIN_BEFORE})\\s*$`).test(before(a.start))) {
      mins.push(a);
    } else if (rx(`^\\s*(?:${MAX_AFTER})`).test(after(a.end)) || rx(`(?:${MAX_BEFORE})\\s*$`).test(before(a.start))) {
      maxes.push(a);
    } else {
      maxes.push(a);
      warnings.push(`Read ${a.text} (${inr(a.single!)}) as the maximum budget — adjust if it is a minimum`);
    }
  }
  const pick = (list: Amount[], label: string): Amount | undefined => {
    const distinct = [...new Set(list.map((x) => x.single))];
    if (distinct.length > 1) {
      warnings.push(`Several ${label} budgets mentioned (${distinct.map((v) => inr(v!)).join(", ")}) — used the last one`);
    }
    return list[list.length - 1];
  };
  const max = pick(maxes, "maximum");
  const min = pick(mins, "minimum");
  if (max) draft.budgetMax = { value: max.single!, evidence: max.text };
  if (min) draft.budgetMin = { value: min.single!, evidence: min.text };

  // --- locations ---
  interface Loc extends Span {
    name: string;
    text: string;
  }
  const found: Loc[] = [];
  for (const [canonical, variants] of Object.entries(LOCALITIES)) {
    const re = rx(`${L}(?:${alt(variants)})(?:\\s*\\(?\\s*(${DIRECTION})\\s*\\)?(?![\\p{L}\\p{M}]))?${R}`);
    for (const m of text.matchAll(re)) {
      const start = m.index ?? 0;
      let name = canonical;
      if (m[1] && DIRECTIONAL.has(canonical)) name = `${canonical} ${WEST.has(stripNukta(m[1])) ? "West" : "East"}`;
      found.push({ start, end: start + m[0].length, name, text: m[0].trim() });
    }
  }
  // Longest match wins ("Lower Parel" over "Parel", "Vile Parle" over "Parle").
  found.sort((a, b) => b.end - b.start - (a.end - a.start));
  const kept: Loc[] = [];
  for (const f of found) if (!kept.some((k) => overlaps(k, f))) kept.push(f);
  kept.sort((a, b) => a.start - b.start);
  const EXCEPT = alt(["not in", "except", "chhodke", "chhod ke", "छोड़कर", "छोड़ के", "सिवाय", "सोडून"]);
  const locations: Evidence<string>[] = [];
  for (const k of kept) {
    const negatedAfter = rx(`^\\s*(?:[\\p{L}\\p{M}]+\\s+){0,2}?(?:${NEGATION})${R}`).test(after(k.end, 25));
    const negatedBefore = rx(`(?:${EXCEPT})\\s*$`).test(before(k.start, 20));
    if (negatedAfter || negatedBefore) {
      warnings.push(`"${k.text}" was mentioned as NOT wanted — not added to preferred locations`);
      continue;
    }
    if (!locations.some((l) => l.value === k.name)) locations.push({ value: k.name, evidence: k.text });
  }
  if (locations.length) draft.locations = locations;

  // --- furnishing ---
  const furnishing = new Map<Furnishing, string>();
  const furnishingSpans: Span[] = [];
  const addFurnishing = (f: Furnishing, m: RegExpMatchArray) => {
    const start = m.index ?? 0;
    const span = { start, end: start + m[0].length };
    if (furnishingSpans.some((s) => overlaps(s, span))) return;
    if (rx(`^\\s*(?:${NEGATION})${R}`).test(after(span.end, 15))) {
      warnings.push(`"${m[0].trim()}" was mentioned as not wanted — furnishing left for you to set`);
      furnishingSpans.push(span);
      return;
    }
    furnishingSpans.push(span);
    if (!furnishing.has(f)) furnishing.set(f, m[0].trim());
  };
  for (const m of text.matchAll(rx(`${L}(?:${UNFURNISHED})${R}`))) addFurnishing("UNFURNISHED", m);
  for (const m of text.matchAll(rx(`${L}(?:${SEMI})${R}`))) addFurnishing("SEMI_FURNISHED", m);
  for (const m of text.matchAll(rx(`${L}(?:${FULLY})${R}`))) addFurnishing("FULLY_FURNISHED", m);
  for (const m of text.matchAll(rx(`${L}(?:${FURNISHED})${R}`))) {
    const before6 = before(m.index ?? 0, 8);
    if (/(semi|un|non|सेमी|अन)[\s-]*$/iu.test(before6)) continue;
    const had = furnishing.size;
    addFurnishing("FULLY_FURNISHED", m);
    if (furnishing.size > had) warnings.push(`Read "${m[0].trim()}" as fully furnished — change to semi-furnished if meant`);
  }
  if (furnishing.size) {
    draft.furnishing = { value: [...furnishing.keys()], evidence: [...furnishing.values()].join(", ") };
  }

  // --- parking ---
  for (const m of text.matchAll(rx(`${L}(?:(${NUM})\\s*)?(?:(?:${CAR})\\s*(?:ki|की|के|ke|साठी|sathi|का|ka)?\\s*)?(?:${PARKING})${R}`))) {
    const end = (m.index ?? 0) + m[0].length;
    const negated =
      rx(`^\\s*(?:[\\p{L}\\p{M}]+\\s+){0,2}?(?:${NEGATION}|${alt(["zaroori nahi", "ज़रूरी नहीं", "गरज नाही"])})${R}`).test(after(end, 30)) ||
      rx(`(?:${alt(["no", "without", "bina", "बिना"])})\\s*$`).test(before(m.index ?? 0, 12));
    if (negated) continue; // "parking nahi chahiye" = no parking requirement
    const n = m[1] ? parseNumber(m[1]) : 1;
    if (n == null || n < 1 || n > 10 || !Number.isInteger(n)) continue;
    if (!draft.minParking || n > draft.minParking.value) draft.minParking = ev(n, m);
  }

  // --- floor ---
  const floorSpans: Span[] = [];
  const claimFloor = (m: RegExpMatchArray) => {
    const start = m.index ?? 0;
    const span = { start, end: start + m[0].length };
    if (floorSpans.some((s) => overlaps(s, span))) return false;
    floorSpans.push(span);
    return true;
  };
  for (const m of text.matchAll(rx(`${L}(${ORD})\\s*(?:${RANGE_SEP})\\s*(${ORD})\\s*(?:${FLOOR})${R}`))) {
    const a = parseOrdinal(m[1]);
    const b = parseOrdinal(m[2]);
    if (a == null || b == null || !claimFloor(m)) continue;
    draft.floorMin = ev(Math.min(a, b), m);
    draft.floorMax = ev(Math.max(a, b), m);
  }
  for (const m of text.matchAll(rx(`${L}(${ORD})\\s*\\+?\\s*(?:${FLOOR})\\s*(?:(?:${LINK})\\s*)?(?:${ABOVE})${R}|${L}(?:above|upar|ऊपर)\\s*(${ORD})\\s*(?:${FLOOR})${R}|${L}(${ORD})\\s*\\+\\s*(?:${FLOOR})${R}`))) {
    const n = parseOrdinal(m[1] ?? m[2] ?? m[3]);
    if (n == null || !claimFloor(m)) continue;
    draft.floorMin = ev(n, m);
  }
  for (const m of text.matchAll(rx(`${L}(${ORD})\\s*(?:${FLOOR})\\s*(?:${BELOW})${R}|${L}(?:below|under|neeche|नीचे)\\s*(${ORD})\\s*(?:${FLOOR})${R}`))) {
    const n = parseOrdinal(m[1] ?? m[2]);
    if (n == null || !claimFloor(m)) continue;
    draft.floorMax = ev(n, m);
  }
  for (const m of text.matchAll(rx(`${L}(?:ground|ग्राउंड)\\s*(?:${FLOOR})${R}|${L}(?:तळमजला|talmajla)${R}`))) {
    if (!claimFloor(m)) continue;
    const end = (m.index ?? 0) + m[0].length;
    if (rx(`^\\s*(?:[\\p{L}\\p{M}]+\\s+){0,1}?(?:${NEGATION})${R}`).test(after(end, 20))) {
      draft.floorMin = ev(1, m);
    } else {
      warnings.push(`Mentioned "${m[0].trim()}" — set the floor range if the client wants only the ground floor`);
    }
  }
  for (const m of text.matchAll(rx(`${L}(${ORD})\\s*(?:${FLOOR})${R}`))) {
    if (!claimFloor(m)) continue;
    warnings.push(`Mentioned "${m[0].trim()}" without "above"/"up to" — set a floor range if it is a requirement`);
  }
  if (rx(`${L}(?:${alt(["high", "higher", "upar wala", "upar ka", "upar wali", "ऊंचा", "ऊँचा", "ऊपर वाला", "ऊपर का", "ऊपरी", "वरचा", "वरचा"])})\\s*(?:${FLOOR})${R}`).test(text)) {
    warnings.push("Prefers a higher floor but gave no number — set the minimum floor if needed");
  }
  if (rx(`${L}(?:${alt(["low", "lower", "neeche wala", "neeche ka", "नीचे वाला", "नीचे का", "खालचा"])})\\s*(?:${FLOOR})${R}`).test(text)) {
    warnings.push("Prefers a lower floor but gave no number — set the maximum floor if needed");
  }

  // --- possession ---
  const ready = [...text.matchAll(rx(`${L}(?:${READY})${R}`))];
  const uc = [...text.matchAll(rx(`${L}(?:${UNDER_CONSTRUCTION})${R}`))];
  if (ready.length && uc.length) {
    warnings.push("Mentions both ready-to-move and under-construction — possession left open");
  } else if (ready.length) {
    draft.possession = ev("READY_TO_MOVE", ready[0]);
  } else if (uc.length) {
    draft.possession = ev("UNDER_CONSTRUCTION", uc[0]);
  }
  const byDates = [
    ...text.matchAll(rx(`${L}(?:(${MONTH})\\s+)?(20[2-9][0-9])\\s*(?:${BY_AFTER})${R}`)),
    ...text.matchAll(rx(`${L}(?:${BY_BEFORE})\\s+(?:(${MONTH})\\s+)?(20[2-9][0-9])${R}`)),
  ];
  for (const m of byDates) {
    const start = m.index ?? 0;
    const context = text.slice(Math.max(0, start - 60), start + m[0].length + 30);
    if (!rx(`(?:${POSSESSION_CONTEXT}|${READY}|${UNDER_CONSTRUCTION})`).test(context)) {
      warnings.push(`Mentioned "${m[0].trim()}" but not clearly about possession — not recorded`);
      continue;
    }
    const year = Number(m[2]);
    const month = m[1] ? monthLookup.get(stripNukta(m[1])) ?? 12 : 12;
    const lastDay = new Date(Date.UTC(year, month, 0)).getUTCDate();
    draft.possessionBy = ev(`${year}-${String(month).padStart(2, "0")}-${String(lastDay).padStart(2, "0")}`, m);
    break;
  }

  const verified = verifyDraft(draft, transcript);
  return {
    draft: verified.draft,
    advertisedPrices,
    warnings: [...warnings, ...verified.warnings],
    extractor: "rules",
  };
}
