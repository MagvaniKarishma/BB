import { beforeEach, describe, expect, it } from "vitest";
import { registerBroker, resetDb } from "./helpers.js";

beforeEach(resetDb);

const fullProperty = {
  title: "2 BHK, Lokhandwala",
  transactionType: "RENT",
  category: "BHK_2",
  propertyType: "APARTMENT",
  price: 65000,
  deposit: 200000,
  locality: "Andheri (W)",
  building: "Sample Heights",
  carpetAreaSqft: 750,
  builtUpAreaSqft: 900,
  furnishing: "SEMI_FURNISHED",
  parkingSpots: 1,
  floor: 14,
  totalFloors: 18,
  possession: "READY_TO_MOVE",
  amenities: ["Lift", "Gym", "Power backup"],
  ownerName: "Sample Owner",
  ownerPhone: "9820000001",
};

describe("property inventory", () => {
  it("stores type, areas and amenities; filters by locality, price, type, furnishing and availability", async () => {
    const { api } = await registerBroker();
    const created = await api.post("/properties", fullProperty);
    expect(created.status).toBe(201);
    expect(created.body.property).toMatchObject({
      propertyType: "APARTMENT", builtUpAreaSqft: 900, carpetAreaSqft: 750, amenities: ["Lift", "Gym", "Power backup"], availability: "AVAILABLE",
    });
    const villa = (await api.post("/properties", {
      title: "4 BHK villa", transactionType: "BUY", category: "BHK_4", propertyType: "VILLA", price: 90_000_000,
      locality: "Juhu", furnishing: "FULLY_FURNISHED",
    })).body.property;
    expect(villa.amenities).toEqual([]);
    await api.post("/properties", { title: "1 BHK Powai", transactionType: "RENT", category: "BHK_1", price: 40000, locality: "Powai" });

    const titles = async (qs: string) =>
      ((await api.get(`/properties?${qs}`)).body.properties as { title: string }[]).map((p) => p.title).sort();
    expect(await titles("locality=andheri")).toEqual(["2 BHK, Lokhandwala"]);
    expect(await titles("transactionType=RENT&maxPrice=50000")).toEqual(["1 BHK Powai"]);
    expect(await titles("minPrice=50000&maxPrice=70000")).toEqual(["2 BHK, Lokhandwala"]);
    expect(await titles("propertyType=VILLA")).toEqual(["4 BHK villa"]);
    expect(await titles("furnishing=SEMI_FURNISHED")).toEqual(["2 BHK, Lokhandwala"]);
    expect(await titles("category=BHK_1")).toEqual(["1 BHK Powai"]);
    expect(await titles("")).toHaveLength(3);

    // Edit price, availability and amenities; mark rented.
    const id = created.body.property.id;
    const edited = await api.patch(`/properties/${id}`, { price: 60000, amenities: ["Lift"], availability: "RENTED" });
    expect(edited.body.property).toMatchObject({ price: 60000, amenities: ["Lift"], availability: "RENTED" });
    expect(await titles("availability=AVAILABLE")).toEqual(["1 BHK Powai", "4 BHK villa"]);
    expect(await titles("availability=RENTED")).toEqual(["2 BHK, Lokhandwala"]);
  });

  it("matches in both directions, follows edits, and uses lower/middle/higher floors", async () => {
    const { api } = await registerBroker();
    const client = (await api.post("/clients", { name: "Test Client", phone: "9820012345", leadSource: "WALK_IN" })).body.client;
    const inquiry = (await api.post(`/clients/${client.id}/inquiries`, {
      transactionType: "RENT", category: "BHK_2", budgetMax: 70000, locations: ["Andheri West"],
      furnishing: ["SEMI_FURNISHED"], floorPreference: ["HIGHER"], propertyTypes: ["APARTMENT"],
    })).body.inquiry;
    expect(inquiry).toMatchObject({ floorPreference: ["HIGHER"], propertyTypes: ["APARTMENT"] });
    // An exact floor range is not accepted as a client preference.
    const stray = await api.patch(`/inquiries/${inquiry.id}`, { floorMin: 5, version: inquiry.version });
    expect(stray.body.inquiry?.floorMin ?? null).toBeNull();

    const p = (await api.post("/properties", fullProperty)).body.property;
    const fromClient = (await api.get(`/inquiries/${inquiry.id}/matches`)).body.matches;
    expect(fromClient).toHaveLength(1);
    expect(fromClient[0].score).toBe(100);
    expect(fromClient[0].checks.find((c: { field: string }) => c.field === "FLOOR").detail).toMatch(/Floor 14 of 18 is a higher floor/);
    const fromProperty = (await api.get(`/properties/${p.id}/matches`)).body.matches;
    expect(fromProperty.map((m: { inquiry: { client: { name: string } } }) => m.inquiry.client.name)).toEqual(["Test Client"]);

    // Edits change the matches: a lower floor makes it partial; unknown building height is unverified.
    await api.patch(`/properties/${p.id}`, { floor: 2 });
    const partial = (await api.get(`/inquiries/${inquiry.id}/matches`)).body.matches[0];
    expect(partial.score).toBeLessThan(100);
    expect(partial.checks.find((c: { field: string }) => c.field === "FLOOR").outcome).toBe("mismatch");
    await api.patch(`/properties/${p.id}`, { floor: 14, totalFloors: null });
    const unverified = (await api.get(`/inquiries/${inquiry.id}/matches`)).body.matches[0];
    expect(unverified.checks.find((c: { field: string }) => c.field === "FLOOR")).toMatchObject({ outcome: "unknown" });
    // Rented: no longer a match, and nothing is deleted.
    await api.patch(`/properties/${p.id}`, { availability: "RENTED" });
    expect((await api.get(`/inquiries/${inquiry.id}/matches`)).body.matches).toEqual([]);
    expect((await api.get(`/properties/${p.id}`)).status).toBe(200);
    expect((await api.get(`/inquiries/${inquiry.id}`)).status).toBe(200);
  });
});
