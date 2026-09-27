import type { Inquiry } from "@prisma/client";

/** Requirement fields tracked in the change history. */
export const TRACKED_FIELDS = [
  "transactionType",
  "category",
  "status",
  "budgetMin",
  "budgetMax",
  "locations",
  "furnishing",
  "minParking",
  "floorPreference",
  "propertyTypes",
  "possession",
  "possessionBy",
  "mandatory",
  "notes",
] as const satisfies readonly (keyof Inquiry)[];

type Tracked = (typeof TRACKED_FIELDS)[number];
export type RequirementSnapshot = Record<Tracked, unknown>;
export type RequirementChanges = Partial<Record<Tracked, { from: unknown; to: unknown }>>;

/** JSON-safe representation (BigInt → number, Date → ISO date string). */
function jsonValue(v: unknown): unknown {
  if (typeof v === "bigint") return Number(v);
  if (v instanceof Date) return v.toISOString();
  if (Array.isArray(v)) return [...v].map(jsonValue);
  return v ?? null;
}

export function snapshotOf(inquiry: Pick<Inquiry, Tracked>): RequirementSnapshot {
  const out = {} as RequirementSnapshot;
  for (const f of TRACKED_FIELDS) out[f] = jsonValue(inquiry[f]);
  return out;
}

const sameValue = (a: unknown, b: unknown): boolean => {
  if (Array.isArray(a) && Array.isArray(b)) {
    // Order-insensitive for sets like locations / furnishing / mandatory.
    const sa = [...a].map(String).sort();
    const sb = [...b].map(String).sort();
    return sa.length === sb.length && sa.every((x, i) => x === sb[i]);
  }
  return a === b;
};

export function diffSnapshots(before: RequirementSnapshot, after: RequirementSnapshot): RequirementChanges {
  const changes: RequirementChanges = {};
  for (const f of TRACKED_FIELDS) {
    if (!sameValue(before[f], after[f])) changes[f] = { from: before[f], to: after[f] };
  }
  return changes;
}
