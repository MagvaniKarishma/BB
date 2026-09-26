import { beforeEach, describe, expect, it } from "vitest";
import { registerBroker, resetDb } from "./helpers.js";

beforeEach(resetDb);

async function setup() {
  const broker = await registerBroker();
  const { api } = broker;
  const client = (
    await api.post("/clients", { name: "Rahul Sharma", phone: "98200 12345", altPhones: ["022 2345 6789"], leadSource: "ACRES_99" })
  ).body.client;
  return { ...broker, client };
}

const property = (over: object) => ({
  title: "2BHK", transactionType: "RENT", category: "BHK_2", price: 65000, locality: "Andheri West",
  furnishing: "SEMI_FURNISHED", parkingSpots: 1, ...over,
});

describe("caller lookup", () => {
  it("identifies a client from any of their numbers in any format", async () => {
    const { api, client } = await setup();
    for (const raw of ["+919820012345", "09820012345", "919820012345", "+91 22 2345 6789"]) {
      const res = await api.get(`/caller/lookup?phone=${encodeURIComponent(raw)}`);
      expect(res.status).toBe(200);
      expect(res.body.client?.id).toBe(client.id);
    }
  });

  it("returns all open inquiries with budgets, locations and live match counts", async () => {
    const { api, client } = await setup();
    const rent = (
      await api.post(`/clients/${client.id}/inquiries`, {
        transactionType: "RENT", category: "BHK_2", budgetMin: 60000, budgetMax: 70000,
        locations: ["Andheri", "Jogeshwari"], mandatory: ["BUDGET", "LOCATION"],
      })
    ).body.inquiry;
    const buy = (
      await api.post(`/clients/${client.id}/inquiries`, { transactionType: "BUY", category: "BHK_3", budgetMax: 25_000_000, locations: ["Powai"] })
    ).body.inquiry;
    const done = (await api.post(`/clients/${client.id}/inquiries`, { transactionType: "RENT", category: "STUDIO" })).body.inquiry;
    await api.patch(`/inquiries/${done.id}`, { status: "FULFILLED" });
    await api.patch(`/inquiries/${buy.id}`, { status: "PAUSED" });
    await api.post("/properties", property({ title: "fits" }));
    await api.post("/properties", property({ title: "too pricey", price: 90000 }));
    await api.post("/properties", property({ title: "wrong area", locality: "Borivali" }));

    const { client: c } = (await api.get("/caller/lookup?phone=9820012345")).body;
    expect(c.inquiries.map((i: { id: string }) => i.id).sort()).toEqual([rent.id, buy.id].sort());
    expect(c.closedInquiries).toBe(1);
    const r = c.inquiries.find((i: { id: string }) => i.id === rent.id);
    expect(r).toMatchObject({ budgetMin: 60000, budgetMax: 70000, locations: ["Andheri", "Jogeshwari"], matchCount: 1 });
    expect(c.inquiries.find((i: { id: string }) => i.id === buy.id).matchCount).toBeNull(); // paused → not matched
    expect(c.leadSource).toBe("ACRES_99");
  });

  it("shows the last conversation (note or voice note) and pending follow-ups", async () => {
    const { api, client } = await setup();
    expect((await api.get("/caller/lookup?phone=9820012345")).body.client.lastInteraction).toBeNull();

    await api.post("/voice-notes/text", { clientId: client.id, transcript: "2 BHK Andheri, 60 hazaar tak" });
    await new Promise((r) => setTimeout(r, 5));
    await api.post(`/clients/${client.id}/notes`, { body: "Visited Lokhandwala flat, liked it, wants lower rent", source: "CALLER_SCREEN" });
    await api.post("/reminders", { title: "Call about Lokhandwala", dueAt: new Date(Date.now() + 3_600_000).toISOString(), clientId: client.id });
    await api.post("/reminders", { title: "Overdue check-in", dueAt: new Date(Date.now() - 3_600_000).toISOString(), clientId: client.id });

    const c = (await api.get("/caller/lookup?phone=9820012345")).body.client;
    expect(c.lastInteraction).toMatchObject({ kind: "NOTE", text: "Visited Lokhandwala flat, liked it, wants lower rent", by: "Owner" });
    expect(c.reminders.map((r: { title: string }) => r.title)).toEqual(["Overdue check-in", "Call about Lokhandwala"]);

    const notes = (await api.get(`/clients/${client.id}/notes`)).body.notes;
    expect(notes[0]).toMatchObject({ source: "CALLER_SCREEN", author: { name: "Owner" } });
  });

  it("unknown, hidden and other-brokerage numbers return no client", async () => {
    const { client } = await setup();
    const other = await registerBroker("Other");
    expect((await other.api.get("/caller/lookup?phone=9820012345")).body).toEqual({ number: "+919820012345", client: null });
    expect((await other.api.get("/caller/lookup?phone=")).body).toEqual({ number: null, client: null });
    expect((await other.api.get("/caller/lookup?phone=PRIVATE")).body).toEqual({ number: null, client: null });
    expect((await other.api.post(`/clients/${client.id}/notes`, { body: "x" })).status).toBe(404);
  });

  it("serves a compact directory for the on-device cache", async () => {
    const { api, client } = await setup();
    const res = await api.get("/caller/directory");
    expect(res.body.entries).toHaveLength(2);
    expect(res.body.entries).toContainEqual({ e164: "+919820012345", clientId: client.id, name: "Rahul Sharma" });
    expect(JSON.stringify(res.body)).not.toContain("budget");
  });

  it("validates notes", async () => {
    const { api, client } = await setup();
    expect((await api.post(`/clients/${client.id}/notes`, { body: "   " })).status).toBe(400);
    expect((await api.post(`/clients/${client.id}/notes`, { body: "ok", source: "HACK" })).status).toBe(400);
  });
});
