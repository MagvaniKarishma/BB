import { describe, expect, it } from "vitest";
import { extractWithRules, parseNumber } from "../src/voice/rulesExtractor.js";
import { verifyDraft } from "../src/voice/draft.js";

const values = (transcript: string) => {
  const { draft, warnings } = extractWithRules(transcript);
  return {
    type: draft.transactionType?.value,
    category: draft.category?.value,
    min: draft.budgetMin?.value,
    max: draft.budgetMax?.value,
    locations: draft.locations?.map((l) => l.value),
    furnishing: draft.furnishing?.value,
    parking: draft.minParking?.value,
    floors: draft.floorPreference?.value,
    possession: draft.possession?.value,
    possessionBy: draft.possessionBy?.value,
    warnings,
    draft,
  };
};

describe("parseNumber", () => {
  it.each([
    ["60", 60], ["1,20,000", 120000], ["1.5", 1.5], ["pachaas", 50], ["पचास", 50], ["डेढ़", 1.5],
    ["डेढ", 1.5], ["dhai", 2.5], ["साढ़े तीन", 3.5], ["sava do", 2.25], ["चाळीस", 40], ["४५", 45], ["दोन", 2],
  ])("%s → %d", (raw, n) => expect(parseNumber(raw)).toBe(n));
});

describe("rule-based extraction – realistic notes", () => {
  it("Hinglish rent note with range, two areas, furnishing, parking and floor", () => {
    const r = values(
      "Rahul ji ko Andheri West ya Jogeshwari mein 2 BHK chahiye rent pe, budget 60 se 70 hazaar tak, " +
        "semi furnished chalega, ek car parking zaroori hai, 5th floor ke upar ho to better.",
    );
    expect(r).toMatchObject({
      type: "RENT", category: "BHK_2", min: 60000, max: 70000, locations: ["Andheri West", "Jogeshwari"],
      furnishing: ["SEMI_FURNISHED"], parking: 1,
    });
    // An exact floor is never turned into a requirement; the broker is told what was said.
    expect(r.floors).toBeUndefined();
    expect(r.warnings.join(" ")).toMatch(/"5th floor ke upar".*Lower \/ Middle \/ Higher/);
    expect(r.possession).toBeUndefined();
    expect(r.draft.budgetMax?.evidence).toBe("60 se 70 hazaar");
  });

  it("Hindi (Devanagari) purchase note in crores", () => {
    const r = values("क्लाइंट को पवई में 3 बीएचके खरीदना है, बजट डेढ़ करोड़ से दो करोड़ तक, रेडी टू मूव चाहिए, दो गाड़ी की पार्किंग चाहिए।");
    expect(r).toMatchObject({
      type: "BUY", category: "BHK_3", min: 15_000_000, max: 20_000_000, locations: ["Powai"],
      possession: "READY_TO_MOVE", parking: 2,
    });
    expect(r.furnishing).toBeUndefined();
  });

  it("Hindi with nukta variants and Devanagari digits", () => {
    const r = values("अंधेरी वेस्ट में किराये पर २ बीएचके, पचास हज़ार तक, फुली फर्निश्ड");
    expect(r).toMatchObject({ type: "RENT", category: "BHK_2", max: 50000, locations: ["Andheri West"], furnishing: ["FULLY_FURNISHED"] });
    const r2 = values("अंधेरी वेस्ट में किराये पर २ बीएचके, पचास हजार तक");
    expect(r2.max).toBe(50000);
  });

  it("does not pick a size when two are mentioned, and reads possession deadline", () => {
    const r = values(
      "Mehta sir ko Thane mein 2 ya 3 BHK kharidna hai, 1.2 crore max, under construction bhi chalega agar possession December 2027 tak mil jaye.",
    );
    expect(r).toMatchObject({
      type: "BUY", max: 12_000_000, locations: ["Thane"], possession: "UNDER_CONSTRUCTION", possessionBy: "2027-12-31",
    });
    expect(r.category).toBeUndefined();
    expect(r.warnings.join(" ")).toMatch(/Several property types/);
  });

  it("ignores deposit, excluded areas and 'no parking'", () => {
    const r = values(
      "Bandra mein 1 BHK rent pe chahiye, 45000 rupees tak, deposit 2 lakh max. Khar nahi chahiye. Parking nahi chahiye. Fully furnished.",
    );
    expect(r).toMatchObject({ type: "RENT", category: "BHK_1", max: 45000, locations: ["Bandra"], furnishing: ["FULLY_FURNISHED"] });
    expect(r.parking).toBeUndefined();
    expect(r.warnings.join(" ")).toMatch(/deposit/i);
    expect(r.warnings.join(" ")).toMatch(/Khar.*NOT wanted/);
  });

  it("Marathi rent note", () => {
    const r = values("मला अंधेरी पूर्व मध्ये भाड्याने 1 BHK पाहिजे, भाडे चाळीस हजार पर्यंत, सेमी फर्निश्ड, पार्किंग पाहिजे.");
    expect(r).toMatchObject({
      type: "RENT", category: "BHK_1", max: 40000, locations: ["Andheri East"], furnishing: ["SEMI_FURNISHED"], parking: 1,
    });
  });

  it("English luxury purchase", () => {
    const r = values(
      "Looking to buy a 4 BHK in Worli or Lower Parel, budget 8 to 10 crores, needs 2 car parking, prefers a high floor, ready possession.",
    );
    expect(r).toMatchObject({
      type: "BUY", category: "BHK_4", min: 80_000_000, max: 100_000_000, locations: ["Worli", "Lower Parel"],
      parking: 2, possession: "READY_TO_MOVE",
    });
    expect(r.floors).toEqual(["HIGHER"]);
    expect(r.draft.floorPreference?.evidence).toBe("high floor");
  });

  it("extracts nothing from a note without requirements", () => {
    const r = values("Client ne call kiya tha, kal wapas baat karni hai. Unka beta Pune mein rehta hai.");
    expect(r.draft).toEqual({});
    expect(r.warnings).toEqual([]);
  });

  it("commercial vs 'office ke paas'", () => {
    expect(values("Dadar mein dukaan chahiye kiraye pe, 1 lakh tak")).toMatchObject({
      type: "RENT", category: "COMMERCIAL", locations: ["Dadar"], max: 100000,
    });
    const r = values("Andheri East mein office ke paas 1 RK chahiye");
    expect(r).toMatchObject({ category: "STUDIO", locations: ["Andheri East"] });
  });

  it("handles self-corrections and lone amounts with a warning", () => {
    const r = values("budget 50 hazaar... nahi nahi, 60 hazaar tak chalega");
    expect(r.max).toBe(60000);
    expect(r.warnings.join(" ")).toMatch(/Several maximum budgets/);
    const lone = values("Malad mein 2 BHK, 55 hazaar");
    expect(lone.max).toBe(55000);
    expect(lone.type).toBeUndefined();
    expect(lone.warnings.join(" ")).toMatch(/maximum budget/);
  });

  it("minimum budgets, half rooms and floor ranges", () => {
    const r = values("kam se kam 80 lakh, 2.5 BHK, 3rd se 10th floor, Goregaon East");
    expect(r).toMatchObject({ min: 8_000_000, category: "BHK_2", locations: ["Goregaon East"] });
    expect(r.floors).toBeUndefined();
    expect(r.warnings.join(" ")).toMatch(/"3rd se 10th floor"/);
    expect(r.max).toBeUndefined();
    expect(r.warnings.join(" ")).toMatch(/2 BHK/);
  });

  it("understands negated comparisons", () => {
    expect(values("2 bhk rent, 35000 se zyada nahi").max).toBe(35000);
    expect(values("2 bhk rent, 35000 se zyada nahi").min).toBeUndefined();
    expect(values("80 lakh se kam nahi, 3 BHK").min).toBe(8_000_000);
    expect(values("पैंतीस हज़ार से ज़्यादा नहीं").max).toBe(35000);
  });

  it("every extracted value quotes text that is in the transcript", () => {
    const transcript = "Borivali West mein 3 BHK kharidna hai, dhai crore tak, semi furnished, 2 parking chahiye, 7th floor tak";
    const { draft } = extractWithRules(transcript);
    expect(draft.budgetMax?.value).toBe(25_000_000);
    expect(draft.floorPreference).toBeUndefined();
    expect(verifyDraft(draft, transcript).warnings).toEqual([]);
  });

  it("floor preference: lower / middle / higher only", () => {
    expect(values("upar wala floor chahiye, Powai").floors).toEqual(["HIGHER"]);
    expect(values("ऊंची मंजिल चाहिए").floors).toEqual(["HIGHER"]);
    expect(values("middle floor or higher floor is fine").floors).toEqual(["HIGHER", "MIDDLE"]);
    expect(values("neeche wala floor, papa ke liye").floors).toEqual(["LOWER"]);
    const ground = values("ground floor chahiye");
    expect(ground.floors).toEqual(["LOWER"]);
    expect(ground.warnings.join(" ")).toMatch(/lower floor/);
    const notGround = values("ground floor nahi chahiye");
    expect(notGround.floors).toBeUndefined();
    expect(notGround.warnings.join(" ")).toMatch(/Doesn't want "ground floor"/);
    expect(values("7th floor tak").floors).toBeUndefined();
  });
});

describe("verifyDraft", () => {
  it("drops values whose evidence is not in the transcript (hallucination guard)", () => {
    const { draft, warnings } = verifyDraft(
      {
        category: { value: "BHK_2", evidence: "2 BHK" },
        budgetMax: { value: 90000, evidence: "90 hazaar" },
        locations: [{ value: "Bandra", evidence: "Bandra" }, { value: "Juhu", evidence: "Juhu" }],
      },
      "Bandra mein 2 bhk chahiye",
    );
    expect(draft.category?.value).toBe("BHK_2");
    expect(draft.budgetMax).toBeUndefined();
    expect(draft.locations?.map((l) => l.value)).toEqual(["Bandra"]);
    expect(warnings).toHaveLength(2);
  });
});
