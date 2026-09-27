import { beforeEach, describe, expect, it } from "vitest";
import { prisma } from "../src/db.js";
import { registerBroker, resetDb } from "./helpers.js";

beforeEach(resetDb);

async function setup() {
  const b = await registerBroker();
  const add = (name: string, phone: string, extra: object = {}) =>
    b.api.post("/clients", { name, phone, leadSource: "WALK_IN", ...extra });
  return { ...b, add };
}

describe("client management", () => {
  it("creates with contact details; the same person twice is refused, same name different number is a different person", async () => {
    const { api, add } = await setup();
    const first = await add("Neha Kapoor", "98200 70001", { email: "Neha@Example.com", notes: "Prefers mornings" });
    expect(first.status).toBe(201);
    expect(first.body.client).toMatchObject({ primaryPhone: "+919820070001", email: "neha@example.com", notes: "Prefers mornings", status: "NEW" });

    // Submitted twice (double tap): refused, naming the existing client.
    const again = await add("Neha Kapoor", "+91 98200 70001");
    expect(again.status).toBe(409);
    expect(again.body.error).toMatchObject({ code: "DUPLICATE_CLIENT", details: { existingClient: { id: first.body.client.id }, matchedOn: "phone number" } });
    // Same email, different number: the same person → refused.
    expect((await add("N. Kapoor", "98200 70009", { email: "neha@example.com" })).body.error.details.matchedOn).toBe("email");
    // Same name, different number and no email: a different person.
    expect((await add("Neha Kapoor", "98200 70002")).status).toBe(201);
    expect(await prisma.client.count()).toBe(2);
    // Missing required details are refused, nothing is filled in.
    expect((await api.post("/clients", { name: "", phone: "98200 70003", leadSource: "WALK_IN" })).status).toBe(400);
    expect((await api.post("/clients", { name: "X", phone: "12", leadSource: "WALK_IN" })).body.error.code).toBe("INVALID_PHONE");
  });

  it("edits phone, email and status; the new number replaces the old one everywhere", async () => {
    const { api, add } = await setup();
    const c = (await add("Rohit Jain", "98200 70011")).body.client;
    const other = (await add("Other", "98200 70099", { email: "other@example.com" })).body.client;

    const res = await api.patch(`/clients/${c.id}`, { phone: "98200 70012", email: "rohit@example.com", status: "CONTACTED" });
    expect(res.status).toBe(200);
    expect(res.body.client).toMatchObject({ primaryPhone: "+919820070012", email: "rohit@example.com", status: "CONTACTED" });
    // Caller lookup / duplicate check follow the new number; the old one is free.
    expect((await api.get("/clients/lookup?phone=9820070012")).body.client.id).toBe(c.id);
    expect((await api.get("/clients/lookup?phone=9820070011")).body.client).toBeNull();
    expect((await api.get(`/clients/${c.id}`)).body.client.phones.map((p: { e164: string }) => p.e164)).toEqual(["+919820070012"]);

    // Someone else's number or email: refused with the owner named.
    const taken = await api.patch(`/clients/${c.id}`, { phone: "98200 70099" });
    expect(taken.status).toBe(409);
    expect(taken.body.error.details.existingClient.id).toBe(other.id);
    expect((await api.patch(`/clients/${c.id}`, { email: "other@example.com" })).status).toBe(409);
    expect((await api.patch(`/clients/${c.id}`, { phone: "12" })).body.error.code).toBe("INVALID_PHONE");
    // Sending the same number (as the app does on every save) changes nothing.
    expect((await api.patch(`/clients/${c.id}`, { phone: "+919820070012", name: "Rohit J" })).body.client).toMatchObject({ name: "Rohit J", primaryPhone: "+919820070012" });
    // Status is the same everywhere.
    expect((await api.get("/clients?group=active")).body.clients.map((x: { id: string }) => x.id)).toContain(c.id);
    expect((await api.get("/clients?group=new")).body.clients.map((x: { id: string }) => x.id)).not.toContain(c.id);
  });

  it("searches by name, phone, email and requirement (area or type), and filters by status", async () => {
    const { api, add } = await setup();
    const a = (await add("Kavita Shah", "98200 70021", { email: "kavita@example.com" })).body.client;
    const b = (await add("Suresh Menon", "98200 70022")).body.client;
    await api.post(`/clients/${a.id}/inquiries`, { transactionType: "RENT", category: "BHK_2", locations: ["Andheri West"] });
    await api.post(`/clients/${b.id}/inquiries`, { transactionType: "BUY", category: "BHK_3", locations: ["Powai"] });
    await api.patch(`/clients/${b.id}`, { status: "CLOSED_LOST" });
    const ids = async (q: string, group = "all") =>
      (await api.get(`/clients?group=${group}&q=${encodeURIComponent(q)}`)).body.clients.map((x: { id: string }) => x.id);

    expect(await ids("kavita")).toEqual([a.id]);
    expect(await ids("70022")).toEqual([b.id]);
    expect(await ids("kavita@")).toEqual([a.id]);
    expect(await ids("andheri")).toEqual([a.id]);
    expect(await ids("2 BHK")).toEqual([a.id]);
    expect(await ids("3bhk powai")).toEqual([b.id]);
    expect(await ids("3 BHK Andheri")).toEqual([]);
    expect(await ids("", "lost")).toEqual([b.id]);
    // The result opens the right profile.
    expect((await api.get(`/clients/${a.id}`)).body.client.inquiries[0].locations).toEqual(["Andheri West"]);
  });

  it("keeps activity per client, newest first, and follow-ups can be rescheduled", async () => {
    const { api, add } = await setup();
    const a = (await add("Anil", "98200 70031")).body.client;
    const b = (await add("Bina", "98200 70032")).body.client;
    await api.post(`/clients/${a.id}/notes`, { body: "First call" });
    await new Promise((r) => setTimeout(r, 5));
    await api.post(`/clients/${a.id}/notes`, { body: "Site visit booked" });
    await api.post(`/clients/${b.id}/notes`, { body: "Bina's note" });
    expect((await api.get(`/clients/${a.id}/notes`)).body.notes.map((n: { body: string }) => n.body)).toEqual(["Site visit booked", "First call"]);

    const due = new Date(Date.now() + 3_600_000).toISOString();
    const r = (await api.post("/reminders", { title: "Call Anil", dueAt: due, clientId: a.id })).body.reminder;
    const later = new Date(Date.now() + 3 * 86_400_000).toISOString();
    expect((await api.patch(`/reminders/${r.id}`, { dueAt: later })).body.reminder.dueAt).toBe(later);
    const profile = (await api.get(`/clients/${a.id}`)).body.client;
    expect(profile.reminders.map((x: { title: string }) => x.title)).toEqual(["Call Anil"]);
    expect((await api.get(`/clients/${b.id}`)).body.client.reminders).toEqual([]);
  });
});
