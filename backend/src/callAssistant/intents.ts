import { normalizePhone } from "../lib/phone.js";

/**
 * Recognisers for what callers say besides requirements. Deliberately conservative: when
 * in doubt nothing matches, and the engine asks again rather than assuming.
 */
const has = (re: RegExp) => (t: string) => re.test(t.normalize("NFC"));

export const wantsHuman = has(
  /\b(talk|speak|connect|transfer)\b.{0,20}\b(human|person|agent|broker|someone|real)\b|\b(human|real person|actual person)\b|\b(agent|broker|insaan|kisi)\s*(se|sey)\s*(baat|connect)|एजेंट\s*से\s*(बात|जोड़)|किसी\s*इंसान|इंसान\s*से|एजंटशी\s*(बोल|जोड)|माणसाशी/i,
);

export const wantsCallback = has(
  /\bcall\s*back\b|\bcallback\b|\bcall\s*(me|kar\s*(do|dena|lena|lijiye|dijiye))\b|\b(wapas|baad\s*mein|baadme)\s*(call|phone)\b|वापस\s*कॉल|बाद\s*में\s*कॉल|कॉल\s*कर\s*(दो|देना|दीजिए)|परत\s*कॉल|नंतर\s*कॉल/i,
);

/** "Skip this question" / "no preference" / "don't know". */
export const skips = has(
  /\b(skip|pass|next|pata\s*nahi|nahi\s*pata|maloom\s*nahi|malum\s*nahi|don'?t\s*know|not\s*sure|no\s*idea|koi\s*bhi|kuch\s*bhi|any(where|thing)?|chalega|flexible|no\s*preference|baad\s*mein\s*bata\w*|abhi\s*nahi)\b|पता\s*नहीं|मालूम\s*नहीं|कोई\s*भी|कुछ\s*भी|चलेगा|बाद\s*में\s*बता|माहीत\s*नाही|कोणताही|काहीही|चालेल/i,
);

/** "That's all" / goodbye. */
export const isDone = has(
  /^\s*(bas|that'?s\s*(all|it)|nothing(\s*else)?|kuch\s*nahi|aur\s*kuch\s*nahi|no(pe)?|nahi|nahin|bye|thank\s*you|thanks|dhanyavaad|shukriya|ok\s*bye)\b|बस|और\s*कुछ\s*नहीं|कुछ\s*नहीं|धन्यवाद|शुक्रिया|अजून\s*काही\s*नाही|काही\s*नाही|बाय/i,
);

export const isYes = has(
  /^\s*(yes|yeah|yep|sure|ok(ay)?|correct|right|haan?|ha|hanji|haanji|ji\s*haan|ji|bilkul|theek\s*hai|thik\s*hai|sahi|ho|hoy|chalega)\b|^\s*(हाँ|हां|जी|जी\s*हाँ|बिल्कुल|ठीक\s*है|सही|हो|होय|चालेल)/i,
);

export const isNo = has(
  /^\s*(no|nope|nah|nahi|nahin|na|mat|don'?t)\b|^\s*(नहीं|ना|मत|नाही|नको)/i,
);

const DIGIT_WORDS: Record<string, string> = {
  zero: "0", oh: "0", shunya: "0", shoonya: "0", sunya: "0", शून्य: "0", शुन्य: "0",
  one: "1", ek: "1", एक: "1",
  two: "2", do: "2", don: "2", दो: "2", दोन: "2",
  three: "3", teen: "3", tin: "3", तीन: "3",
  four: "4", char: "4", chaar: "4", चार: "4",
  five: "5", paanch: "5", panch: "5", पांच: "5", पाँच: "5", पाच: "5",
  six: "6", chhe: "6", che: "6", chah: "6", छह: "6", छः: "6", सहा: "6",
  seven: "7", saat: "7", sat: "7", सात: "7",
  eight: "8", aath: "8", aat: "8", आठ: "8",
  nine: "9", nau: "9", no: "9", नौ: "9", नऊ: "9",
  double: "x2", triple: "x3",
};
const DEVANAGARI_DIGITS = "०१२३४५६७८९";

/**
 * A phone number spoken or typed by the caller ("98200 12345", "nine eight two double
 * zero…", "नौ आठ…"). Returns E.164 or null. "no" only counts as nine inside a digit run.
 */
export function spokenPhone(text: string): string | null {
  const t = text.replace(/[०-९]/g, (d) => String(DEVANAGARI_DIGITS.indexOf(d))).toLowerCase();
  let digits = "";
  let repeat = 1;
  const tokens = t.split(/[\s,.\-]+/).filter(Boolean);
  for (const tok of tokens) {
    if (/^\+?\d+$/.test(tok)) {
      const n = tok.replace("+", "");
      digits += n.length === 1 ? n.repeat(repeat) : n; // "double 5" → 55
      repeat = 1;
      continue;
    }
    const w = DIGIT_WORDS[tok];
    if (w === "x2" || w === "x3") repeat = Number(w[1]);
    else if (w && (tok !== "no" || digits.length > 0)) {
      digits += w.repeat(repeat);
      repeat = 1;
    }
  }
  if (digits.length < 10) return null;
  return normalizePhone(digits.length === 10 ? digits : `+${digits}`) ?? normalizePhone(digits.slice(-10));
}

const NAME_PATTERNS = [
  /\b(?:mera\s+naam|my\s+name\s+is|name\s+is|naam\s+hai|i\s+am|i'm|this\s+is)\s+([A-Za-z][A-Za-z.']*(?:\s+[A-Za-z][A-Za-z.']*){0,2}?)(?=\s+(?:hai|hoon|hu|here|bol|speaking)\b|[,.!?]|$)/i,
  /\bmain\s+([A-Za-z][A-Za-z.']*(?:\s+[A-Za-z][A-Za-z.']*)?)\s+bol\s+(?:raha|rahi)\b/i,
  /(?:मेरा\s+नाम|माझं\s+नाव|माझे\s+नाव|माझ\s+नाव)\s+([\p{L}\p{M}]+(?:\s+(?!है|आहे)[\p{L}\p{M}]+)?)(?=\s+(?:है|आहे)|[,.!?।]|$)/u,
  /मैं\s+([\p{L}\p{M}]+(?:\s+[\p{L}\p{M}]+)?)\s+बोल\s+(?:रहा|रही)/u,
];
const NOT_NAMES =
  /^(looking|interested|calling|searching|haan|ha|yes|no|nahi|ok|okay|sure|rent|buy|flat|ji|sir|madam|bhai|the|a|an|from|here)$/i;

/** The caller's name as they said it, from "mera naam Rahul hai" / "my name is …". */
export function statedName(text: string): string | null {
  for (const re of NAME_PATTERNS) {
    const m = re.exec(text.normalize("NFC"));
    const name = m?.[1]?.trim();
    if (name && !name.split(/\s+/).some((w) => NOT_NAMES.test(w))) return name;
  }
  return null;
}

/** A bare answer to "What's your name?" ("Rahul Sharma", "Rahul ji"). */
export function bareName(text: string): string | null {
  const t = text.normalize("NFC").trim().replace(/[.!?।]+$/, "").replace(/\s+(ji|जी|hai|है|आहे)$/i, "");
  const words = t.split(/\s+/);
  if (words.length === 0 || words.length > 3) return null;
  if (!words.every((w) => /^[\p{L}\p{M}.']+$/u.test(w))) return null;
  if (words.some((w) => NOT_NAMES.test(w)) || isYes(t) || isNo(t) || skips(t) || isDone(t)) return null;
  return t;
}
