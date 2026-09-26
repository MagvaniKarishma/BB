/**
 * Mumbai locality normalisation. Brokers and clients write the same place many
 * ways: "Andheri (W)", "andheri w", "Andheri West". We normalise to a canonical
 * lowercase form so requirement locations can be compared with inventory.
 */

const DIRECTION: Record<string, string> = {
  w: "west",
  west: "west",
  e: "east",
  east: "east",
  n: "north",
  north: "north",
  s: "south",
  south: "south",
};

// Common abbreviations / alternate spellings → canonical name.
const ALIASES: Record<string, string> = {
  bkc: "bandra kurla complex",
  "navi mumbai": "navi mumbai",
  "vile parle": "vile parle",
  vileparle: "vile parle",
  parle: "vile parle",
  ghatkoper: "ghatkopar",
  "lower parel": "lower parel",
  "l parel": "lower parel",
  bombay: "mumbai",
  "santa cruz": "santacruz",
  "juhu scheme": "juhu",
  "powai hiranandani": "powai",
  "hiranandani powai": "powai",
  "mira rd": "mira road",
  "mira bhayander": "mira road",
  "thane w": "thane west",
  "prabhadevi": "prabhadevi",
  "worli sea face": "worli",
};

export function normalizeLocality(raw: string): string {
  let s = raw
    .toLowerCase()
    .replace(/[()[\],.]/g, " ")
    .replace(/[-_/]/g, " ")
    .replace(/\s+/g, " ")
    .trim();
  if (!s) return s;
  s = s.replace(/\broad\b/g, "road").replace(/\brd\b/g, "road");
  const parts = s.split(" ");
  const last = parts[parts.length - 1];
  if (parts.length > 1 && DIRECTION[last]) {
    parts[parts.length - 1] = DIRECTION[last];
  }
  s = parts.join(" ");
  return ALIASES[s] ?? s;
}

const hasDirection = (normalized: string) =>
  /\b(west|east|north|south)$/.test(normalized);

/**
 * Does a client's preferred location cover a property's locality?
 * "Andheri" (no direction) covers both Andheri East and Andheri West;
 * "Andheri West" covers only Andheri West.
 */
export function localityMatches(preferred: string, propertyLocality: string): boolean {
  const pref = normalizeLocality(preferred);
  const prop = normalizeLocality(propertyLocality);
  if (!pref || !prop) return false;
  if (pref === prop) return true;
  if (!hasDirection(pref) && hasDirection(prop)) {
    return prop.replace(/ (west|east|north|south)$/, "") === pref;
  }
  return false;
}
