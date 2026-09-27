import { describe, expect, it } from "vitest";
import { normalizePhone } from "../src/lib/phone.js";
import { localityMatches, normalizeLocality } from "../src/domain/locality.js";
import { type Listing, type Requirement, evaluateMatch, floorBandOf, rankMatches } from "../src/domain/matching.js";
import { diffSnapshots } from "../src/domain/requirementHistory.js";

describe("normalizePhone", () => {
  it.each([
    ["9820012345", "+919820012345"],
    ["98200 12345", "+919820012345"],
    ["098200-12345", "+919820012345"],
    ["+91 98200 12345", "+919820012345"],
    ["919820012345", "+919820012345"],
    ["00919820012345", "+919820012345"],
    ["022 2345 6789", "+912223456789"],
    ["+1 415 555 2671", "+14155552671"],
  ])("%s → %s", (input, expected) => {
    expect(normalizePhone(input)).toBe(expected);
  });

  it.each(["", "abc", "12345", "0000000000"])("rejects %j", (input) => {
    expect(normalizePhone(input)).toBeNull();
  });
});

describe("locality", () => {
  it("normalises direction suffixes and punctuation", () => {
    expect(normalizeLocality("Andheri (W)")).toBe("andheri west");
    expect(normalizeLocality("andheri w")).toBe("andheri west");
    expect(normalizeLocality("Andheri-West")).toBe("andheri west");
    expect(normalizeLocality("BKC")).toBe("bandra kurla complex");
    expect(normalizeLocality("Mira Rd")).toBe("mira road");
  });

  it("undirected preference covers both sides", () => {
    expect(localityMatches("Andheri", "Andheri East")).toBe(true);
    expect(localityMatches("Andheri", "Andheri (W)")).toBe(true);
    expect(localityMatches("Andheri West", "Andheri East")).toBe(false);
    expect(localityMatches("Bandra", "Bandra Kurla Complex")).toBe(false);
    expect(localityMatches("Powai", "Powai")).toBe(true);
  });
});

const req = (over: Partial<Requirement> = {}): Requirement => ({
  transactionType: "RENT",
  category: "BHK_2",
  budgetMin: null,
  budgetMax: null,
  locations: [],
  furnishing: [],
  minParking: null,
  floorPreference: [],
  propertyTypes: [],
  possession: null,
  possessionBy: null,
  mandatory: [],
  ...over,
});

const listing = (over: Partial<Listing> = {}): Listing => ({
  transactionType: "RENT",
  category: "BHK_2",
  availability: "AVAILABLE",
  price: 60_000n,
  locality: "Andheri West",
  furnishing: "SEMI_FURNISHED",
  parkingSpots: 1,
  floor: 5,
  totalFloors: 20,
  propertyType: "APARTMENT",
  possession: "READY_TO_MOVE",
  possessionDate: null,
  ...over,
});

describe("evaluateMatch", () => {
  it("always excludes wrong type, category or unavailable listings", () => {
    expect(evaluateMatch(req(), listing({ transactionType: "BUY" })).eligible).toBe(false);
    expect(evaluateMatch(req(), listing({ category: "BHK_3" })).eligible).toBe(false);
    expect(evaluateMatch(req(), listing({ availability: "RENTED" })).eligible).toBe(false);
  });

  it("scores 100 when there are no preferences beyond type/category", () => {
    const r = evaluateMatch(req(), listing());
    expect(r.eligible).toBe(true);
    expect(r.score).toBe(100);
    expect(r.checks.every((c) => c.outcome === "n/a")).toBe(true);
  });

  it("excludes over-budget listings only when budget is mandatory", () => {
    const over = listing({ price: 80_000n });
    expect(evaluateMatch(req({ budgetMax: 70_000n }), over).eligible).toBe(true);
    const hard = evaluateMatch(req({ budgetMax: 70_000n, mandatory: ["BUDGET"] }), over);
    expect(hard.eligible).toBe(false);
    expect(hard.violations[0]).toMatch(/budget/);
  });

  it("treats up to 10% over budget as 'near', which still violates a mandatory budget", () => {
    const near = listing({ price: 75_000n });
    const soft = evaluateMatch(req({ budgetMax: 70_000n }), near);
    expect(soft.checks.find((c) => c.field === "BUDGET")?.outcome).toBe("near");
    expect(soft.score).toBe(50);
    expect(evaluateMatch(req({ budgetMax: 70_000n, mandatory: ["BUDGET"] }), near).eligible).toBe(false);
  });

  it("enforces mandatory location", () => {
    const r = req({ locations: ["Bandra", "Khar"], mandatory: ["LOCATION"] });
    expect(evaluateMatch(r, listing({ locality: "Bandra (W)" })).eligible).toBe(true);
    expect(evaluateMatch(r, listing({ locality: "Andheri West" })).eligible).toBe(false);
  });

  it("keeps listings with unknown data on mandatory fields but flags them", () => {
    const r = evaluateMatch(req({ minParking: 1, mandatory: ["PARKING"] }), listing({ parkingSpots: null }));
    expect(r.eligible).toBe(true);
    expect(r.needsVerification).toEqual(["PARKING"]);
    expect(r.score).toBe(50);
  });

  it("checks furnishing, floor and parking", () => {
    const r = req({
      furnishing: ["FULLY_FURNISHED"],
      floorPreference: ["LOWER", "MIDDLE"],
      minParking: 2,
      mandatory: ["FURNISHING", "FLOOR", "PARKING"],
    });
    const res = evaluateMatch(r, listing());
    expect(res.eligible).toBe(false);
    expect(res.violations).toHaveLength(2); // furnishing + parking; floor 5 of 20 is a lower floor
    expect(evaluateMatch(r, listing({ furnishing: "FULLY_FURNISHED", parkingSpots: 2, floor: 15 })).eligible).toBe(false);
    expect(evaluateMatch(r, listing({ furnishing: "FULLY_FURNISHED", parkingSpots: 2, floor: 10 })).eligible).toBe(true);
  });

  it("floor preference is lower / middle / higher, never an exact floor", () => {
    expect(floorBandOf(0, 10)).toBe("LOWER"); // ground
    expect(floorBandOf(3, 10)).toBe("LOWER");
    expect(floorBandOf(4, 10)).toBe("MIDDLE");
    expect(floorBandOf(6, 10)).toBe("MIDDLE");
    expect(floorBandOf(7, 10)).toBe("HIGHER");
    expect(floorBandOf(10, 10)).toBe("HIGHER");
    expect(floorBandOf(5, null)).toBeNull(); // building height unknown: not guessed
    expect(floorBandOf(null, 10)).toBeNull();
    expect(floorBandOf(12, 10)).toBeNull(); // inconsistent data

    const higher = req({ floorPreference: ["HIGHER"], mandatory: ["FLOOR"] });
    expect(evaluateMatch(higher, listing({ floor: 18 })).checks.find((c) => c.field === "FLOOR")?.outcome).toBe("match");
    expect(evaluateMatch(higher, listing({ floor: 2 })).eligible).toBe(false);
    // Unknown floor or building height: kept, flagged as unverified.
    for (const unknown of [listing({ floor: null }), listing({ totalFloors: null })]) {
      const res = evaluateMatch(higher, unknown);
      expect(res.eligible).toBe(true);
      expect(res.needsVerification).toEqual(["FLOOR"]);
      expect(res.checks.find((c) => c.field === "FLOOR")?.detail).toMatch(/unverified/);
    }
    // No preference: floor is ignored.
    expect(evaluateMatch(req(), listing({ floor: null })).checks.find((c) => c.field === "FLOOR")?.outcome).toBe("n/a");
  });

  it("checks property type", () => {
    const r = req({ propertyTypes: ["APARTMENT", "BUILDER_FLOOR"] });
    expect(evaluateMatch(r, listing()).checks.find((c) => c.field === "PROPERTY_TYPE")?.outcome).toBe("match");
    const villa = evaluateMatch(r, listing({ propertyType: "VILLA" }));
    expect(villa.checks.find((c) => c.field === "PROPERTY_TYPE")?.outcome).toBe("mismatch");
    expect(villa.eligible).toBe(true); // not a must-have: a partial match
    expect(villa.score).toBe(0);
    expect(evaluateMatch({ ...r, mandatory: ["PROPERTY_TYPE"] }, listing({ propertyType: "VILLA" })).eligible).toBe(false);
    expect(evaluateMatch(r, listing({ propertyType: null })).checks.find((c) => c.field === "PROPERTY_TYPE")?.outcome).toBe("unknown");
  });

  it("handles possession timelines for buyers", () => {
    const by = new Date("2027-06-30");
    const r = req({ transactionType: "BUY", possession: "READY_TO_MOVE", possessionBy: by, mandatory: ["POSSESSION"] });
    const buy = (over: Partial<Listing>) => listing({ transactionType: "BUY", price: 2_50_00_000n, ...over });
    expect(evaluateMatch(r, buy({ possession: "READY_TO_MOVE" })).eligible).toBe(true);
    expect(
      evaluateMatch(r, buy({ possession: "UNDER_CONSTRUCTION", possessionDate: new Date("2027-01-01") })).eligible,
    ).toBe(true);
    expect(
      evaluateMatch(r, buy({ possession: "UNDER_CONSTRUCTION", possessionDate: new Date("2028-01-01") })).eligible,
    ).toBe(false);
    const readyNow = req({ transactionType: "BUY", possession: "READY_TO_MOVE", mandatory: ["POSSESSION"] });
    expect(evaluateMatch(readyNow, buy({ possession: "UNDER_CONSTRUCTION" })).eligible).toBe(false);
  });

  it("ranks by score then price and drops ineligible", () => {
    const r = req({ budgetMax: 70_000n, locations: ["Andheri"], mandatory: ["LOCATION"] });
    const items = [
      listing({ price: 69_000n }),
      listing({ price: 55_000n }),
      listing({ price: 76_000n }),
      listing({ locality: "Borivali West" }),
    ];
    const ranked = rankMatches(items, (p) => evaluateMatch(r, p), (p) => p.price);
    expect(ranked.map((m) => m.item.price)).toEqual([55_000n, 69_000n, 76_000n]);
  });
});

describe("diffSnapshots", () => {
  it("reports changed fields only, ignoring array order", () => {
    const before = { budgetMax: 50000, locations: ["Bandra", "Khar"], notes: null } as never;
    const after = { budgetMax: 60000, locations: ["Khar", "Bandra"], notes: null } as never;
    expect(diffSnapshots(before, after)).toEqual({ budgetMax: { from: 50000, to: 60000 } });
  });
});
