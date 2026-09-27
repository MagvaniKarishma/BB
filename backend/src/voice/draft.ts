import type { FloorBand, Furnishing, Possession, PropertyCategory, TransactionType } from "@prisma/client";

/** A value extracted from a transcript, with the exact words it came from. */
export interface Evidence<T> {
  value: T;
  evidence: string;
}

/**
 * Draft requirement extracted from a voice note. A field is absent when the
 * speaker did not state it — absence is never filled with a default or guess.
 */
export interface RequirementDraft {
  transactionType?: Evidence<TransactionType>;
  category?: Evidence<PropertyCategory>;
  budgetMin?: Evidence<number>;
  budgetMax?: Evidence<number>;
  locations?: Evidence<string>[];
  furnishing?: Evidence<Furnishing[]>;
  minParking?: Evidence<number>;
  /** Only when a lower / middle / higher floor is asked for; exact floors are never extracted. */
  floorPreference?: Evidence<FloorBand[]>;
  possession?: Evidence<Possession>;
  /** YYYY-MM-DD */
  possessionBy?: Evidence<string>;
}

export type DraftField = keyof RequirementDraft;

export interface Extraction {
  draft: RequirementDraft;
  /**
   * Prices of specific properties mentioned (a listing's asking price or rent). Kept
   * apart from the draft so an advertised price is never mistaken for the client's budget.
   */
  advertisedPrices?: Evidence<number>[];
  /** Things the agent should check: ambiguities, interpretations, ignored mentions. */
  warnings: string[];
  extractor: string;
}

/** Normalisation used for evidence comparison: NFC, lowercase, no punctuation, single spaces. */
export function normalizeForMatch(s: string): string {
  return s
    .normalize("NFC")
    .toLowerCase()
    .replace(/[\p{P}\p{S}]/gu, " ")
    .replace(/\s+/g, " ")
    .trim();
}

/**
 * Enforces "never invent": drops every extracted value whose evidence is not
 * literally present in the transcript, and sanity-checks ranges.
 */
export function verifyDraft(draft: RequirementDraft, transcript: string): { draft: RequirementDraft; warnings: string[] } {
  const haystack = normalizeForMatch(transcript);
  const warnings: string[] = [];
  const present = (e: Evidence<unknown> | undefined) => {
    if (!e) return false;
    const needle = normalizeForMatch(e.evidence);
    return needle.length > 0 && haystack.includes(needle);
  };
  const out: RequirementDraft = {};
  for (const key of Object.keys(draft) as DraftField[]) {
    if (key === "locations") {
      const kept = (draft.locations ?? []).filter((l) => {
        const ok = present(l);
        if (!ok) warnings.push(`Dropped location "${l.value}": not found in the transcript`);
        return ok;
      });
      if (kept.length) out.locations = kept;
      continue;
    }
    const field = draft[key] as Evidence<unknown> | undefined;
    if (!field) continue;
    if (present(field)) {
      (out as Record<string, unknown>)[key] = field;
    } else {
      warnings.push(`Dropped ${key}: the quoted words "${field.evidence}" are not in the transcript`);
    }
  }
  if (out.budgetMin && out.budgetMax && out.budgetMin.value > out.budgetMax.value) {
    warnings.push("Budget minimum was above maximum; both left for you to fill in");
    delete out.budgetMin;
    delete out.budgetMax;
  }
  return { draft: out, warnings };
}
