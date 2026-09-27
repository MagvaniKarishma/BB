import type {
  Availability,
  FloorBand,
  Furnishing,
  Possession,
  PropertyCategory,
  PropertyType,
  RequirementField,
  TransactionType,
} from "@prisma/client";
import { localityMatches } from "./locality.js";

/** The subset of an Inquiry the matcher needs. NULL / empty means "no preference". */
export interface Requirement {
  transactionType: TransactionType;
  category: PropertyCategory;
  budgetMin: bigint | null;
  budgetMax: bigint | null;
  locations: string[];
  furnishing: Furnishing[];
  minParking: number | null;
  /** Lower / middle / higher floors; empty = no preference. */
  floorPreference: FloorBand[];
  /** Apartment, villa…; empty = no preference. */
  propertyTypes: PropertyType[];
  possession: Possession | null;
  possessionBy: Date | null;
  mandatory: RequirementField[];
}

/** The subset of a Property the matcher needs. NULL means "not recorded". */
export interface Listing {
  transactionType: TransactionType;
  category: PropertyCategory;
  availability: Availability;
  price: bigint;
  locality: string;
  furnishing: Furnishing | null;
  parkingSpots: number | null;
  floor: number | null;
  totalFloors: number | null;
  propertyType: PropertyType | null;
  possession: Possession | null;
  possessionDate: Date | null;
}

/**
 * match       – satisfies the requirement
 * near        – slightly outside (only budget: up to 10% over)
 * unknown     – the property is missing the data needed to check
 * mismatch    – does not satisfy the requirement
 * n/a         – the client stated no preference for this field
 */
export type CheckOutcome = "match" | "near" | "unknown" | "mismatch" | "n/a";

export interface FieldCheck {
  field: RequirementField;
  outcome: CheckOutcome;
  mandatory: boolean;
  detail: string;
}

export interface MatchResult {
  eligible: boolean;
  /** 0–100; only meaningful when eligible. */
  score: number;
  checks: FieldCheck[];
  /** Hard reasons the property was excluded. */
  violations: string[];
  /** Mandatory fields that could not be verified from the listing. */
  needsVerification: RequirementField[];
}

const WEIGHTS: Record<RequirementField, number> = {
  BUDGET: 30,
  LOCATION: 30,
  POSSESSION: 15,
  FURNISHING: 10,
  PARKING: 10,
  FLOOR: 5,
  PROPERTY_TYPE: 10,
};

const OUTCOME_CREDIT: Record<Exclude<CheckOutcome, "n/a">, number> = {
  match: 1,
  near: 0.5,
  unknown: 0.5,
  mismatch: 0,
};

/** Budget can be up to this fraction over max and still be shown as a "near" match. */
export const BUDGET_STRETCH = 0.1;

const inr = (v: bigint) => `₹${v.toLocaleString("en-IN")}`;

function checkBudget(r: Requirement, p: Listing): [CheckOutcome, string] {
  if (r.budgetMax == null && r.budgetMin == null) return ["n/a", "No budget specified"];
  if (r.budgetMax != null && p.price > r.budgetMax) {
    // Compare in integer space: price <= max * 1.10  <=>  price * 100 <= max * 110
    const stretch = BigInt(Math.round((1 + BUDGET_STRETCH) * 100));
    if (p.price * 100n <= r.budgetMax * stretch) {
      return ["near", `${inr(p.price)} is within 10% over budget ${inr(r.budgetMax)}`];
    }
    return ["mismatch", `${inr(p.price)} exceeds budget ${inr(r.budgetMax)}`];
  }
  return ["match", `${inr(p.price)} is within budget`];
}

function checkLocation(r: Requirement, p: Listing): [CheckOutcome, string] {
  if (r.locations.length === 0) return ["n/a", "No location preference"];
  const hit = r.locations.find((loc) => localityMatches(loc, p.locality));
  return hit
    ? ["match", `${p.locality} matches preferred ${hit}`]
    : ["mismatch", `${p.locality} is not in ${r.locations.join(", ")}`];
}

function checkFurnishing(r: Requirement, p: Listing): [CheckOutcome, string] {
  if (r.furnishing.length === 0) return ["n/a", "No furnishing preference"];
  if (p.furnishing == null) return ["unknown", "Listing furnishing not recorded"];
  return r.furnishing.includes(p.furnishing)
    ? ["match", `${p.furnishing} accepted`]
    : ["mismatch", `${p.furnishing} not in ${r.furnishing.join("/")}`];
}

function checkParking(r: Requirement, p: Listing): [CheckOutcome, string] {
  if (r.minParking == null || r.minParking <= 0) return ["n/a", "No parking requirement"];
  if (p.parkingSpots == null) return ["unknown", "Listing parking not recorded"];
  return p.parkingSpots >= r.minParking
    ? ["match", `${p.parkingSpots} parking spot(s)`]
    : ["mismatch", `Needs ${r.minParking} parking, listing has ${p.parkingSpots}`];
}

const BAND_LABEL: Record<FloorBand, string> = { LOWER: "lower", MIDDLE: "middle", HIGHER: "higher" };

/**
 * Which third of the building a floor is in: ground and the bottom third are LOWER, the top
 * third HIGHER. Null when the floor or the building's height isn't recorded (or they disagree),
 * so the band is never guessed.
 */
export function floorBandOf(floor: number | null, totalFloors: number | null): FloorBand | null {
  if (floor == null || totalFloors == null || totalFloors <= 0 || floor > totalFloors) return null;
  if (floor * 3 <= totalFloors) return "LOWER";
  if (floor * 3 > totalFloors * 2) return "HIGHER";
  return "MIDDLE";
}

function checkFloor(r: Requirement, p: Listing): [CheckOutcome, string] {
  if (r.floorPreference.length === 0) return ["n/a", "No floor preference"];
  const wanted = r.floorPreference.map((b) => BAND_LABEL[b]).join("/");
  if (p.floor == null) return ["unknown", `Floor not recorded — unverified (client wants ${wanted} floors)`];
  const band = floorBandOf(p.floor, p.totalFloors);
  if (band == null) {
    return ["unknown", `Floor ${p.floor}, but the building's total floors aren't recorded — ${wanted} floor unverified`];
  }
  const where = `Floor ${p.floor} of ${p.totalFloors} is a ${BAND_LABEL[band]} floor`;
  return r.floorPreference.includes(band) ? ["match", where] : ["mismatch", `${where}; client wants ${wanted}`];
}

const TYPE_LABEL: Record<PropertyType, string> = {
  APARTMENT: "Apartment",
  INDEPENDENT_HOUSE: "Independent house",
  VILLA: "Villa",
  PENTHOUSE: "Penthouse",
  BUILDER_FLOOR: "Builder floor",
  COMMERCIAL: "Commercial",
  PLOT: "Plot",
  OTHER: "Other",
};

function checkPropertyType(r: Requirement, p: Listing): [CheckOutcome, string] {
  if (r.propertyTypes.length === 0) return ["n/a", "No property type preference"];
  if (p.propertyType == null) return ["unknown", "Listing property type not recorded"];
  return r.propertyTypes.includes(p.propertyType)
    ? ["match", `${TYPE_LABEL[p.propertyType]} accepted`]
    : ["mismatch", `${TYPE_LABEL[p.propertyType]} not in ${r.propertyTypes.map((t) => TYPE_LABEL[t]).join("/")}`];
}

function checkPossession(r: Requirement, p: Listing): [CheckOutcome, string] {
  if (r.possession == null && r.possessionBy == null) return ["n/a", "No possession preference"];
  if (p.possession == null) return ["unknown", "Listing possession status not recorded"];

  if (r.possession === "UNDER_CONSTRUCTION") {
    return p.possession === "UNDER_CONSTRUCTION"
      ? ["match", "Under construction"]
      : ["mismatch", "Client wants under-construction, listing is ready"];
  }
  if (p.possession === "READY_TO_MOVE") return ["match", "Ready to move"];

  // Listing is under construction; client wants ready (optionally by a date).
  if (r.possessionBy == null) {
    return ["mismatch", "Client wants ready-to-move, listing is under construction"];
  }
  if (p.possessionDate == null) return ["unknown", "Listing possession date not recorded"];
  const by = r.possessionBy.toISOString().slice(0, 10);
  return p.possessionDate <= r.possessionBy
    ? ["match", `Possession before ${by}`]
    : ["mismatch", `Possession ${p.possessionDate.toISOString().slice(0, 10)} is after ${by}`];
}

const CHECKERS: Record<RequirementField, (r: Requirement, p: Listing) => [CheckOutcome, string]> = {
  BUDGET: checkBudget,
  LOCATION: checkLocation,
  FURNISHING: checkFurnishing,
  PARKING: checkParking,
  FLOOR: checkFloor,
  POSSESSION: checkPossession,
  PROPERTY_TYPE: checkPropertyType,
};

/**
 * Evaluates one listing against one requirement.
 *
 * Hard rules (always enforced): same transaction type, same category, listing available.
 * A mandatory field that is a `mismatch` (or `near` for budget) excludes the property.
 * A mandatory field that is `unknown` keeps the property but flags it for verification —
 * missing listing data is not treated as a violation, nor silently as a pass.
 */
export function evaluateMatch(r: Requirement, p: Listing): MatchResult {
  const violations: string[] = [];
  if (p.transactionType !== r.transactionType) violations.push(`Listing is for ${p.transactionType}`);
  if (p.category !== r.category) violations.push(`Listing is ${p.category}, client wants ${r.category}`);
  if (p.availability !== "AVAILABLE") violations.push(`Listing is ${p.availability}`);

  const checks: FieldCheck[] = [];
  const needsVerification: RequirementField[] = [];
  let earned = 0;
  let possible = 0;

  for (const field of Object.keys(CHECKERS) as RequirementField[]) {
    const [outcome, detail] = CHECKERS[field](r, p);
    const mandatory = r.mandatory.includes(field);
    checks.push({ field, outcome, mandatory, detail });
    if (outcome === "n/a") continue;
    if (mandatory && (outcome === "mismatch" || outcome === "near")) {
      violations.push(`Mandatory ${field.toLowerCase()}: ${detail}`);
    }
    if (mandatory && outcome === "unknown") needsVerification.push(field);
    possible += WEIGHTS[field];
    earned += WEIGHTS[field] * OUTCOME_CREDIT[outcome];
  }

  const eligible = violations.length === 0;
  const score = possible === 0 ? 100 : Math.round((earned / possible) * 100);
  return { eligible, score: eligible ? score : 0, checks, violations, needsVerification };
}

export interface RankedMatch<T> {
  item: T;
  result: MatchResult;
}

/** Evaluate and rank; only eligible entries are returned, best first. */
export function rankMatches<T>(
  items: T[],
  evaluate: (item: T) => MatchResult,
  priceOf: (item: T) => bigint,
): RankedMatch<T>[] {
  return items
    .map((item) => ({ item, result: evaluate(item) }))
    .filter((m) => m.result.eligible)
    .sort((a, b) => {
      if (b.result.score !== a.result.score) return b.result.score - a.result.score;
      const pa = priceOf(a.item);
      const pb = priceOf(b.item);
      return pa < pb ? -1 : pa > pb ? 1 : 0;
    });
}
