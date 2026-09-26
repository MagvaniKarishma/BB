import { beforeEach, describe, expect, it } from "vitest";
import request from "supertest";
import { app, authed, registerBroker, resetDb } from "./helpers.js";

beforeEach(resetDb);

const newClient = (over: object = {}) => ({
  name: "Rahul Sharma",
  phone: "98200 12345",
  leadSource: "WALK_IN",
  ...over,
});

const rent2bhk = {
  transactionType: "RENT",
  category: "BHK_2",
  budgetMax: 70000,
  locations: ["Andheri"],
  furnishing: ["SEMI_FURNISHED", "FULLY_FURNISHED"],
  minParking: 1,
  mandatory: ["BUDGET", "LOCATION"],
};

describe("auth", () => {
  it("registers, logs in and rejects bad tokens", async () => {
    const { user } = await registerBroker();
    expect(user.role).toBe("OWNER");
    const login = await request(app)
      .post("/api/v1/auth/login")
      .send({ email: user.email, password: "password123" });
    expect(login.status).toBe(200);
    const bad = await request(app).post("/api/v1/auth/login").send({ email: user.email, password: "nope" });
    expect(bad.status).toBe(401);
    expect((await request(app).get("/api/v1/clients")).status).toBe(401);
    expect((await authed("garbage").get("/clients")).status).toBe(401);
  });

  it("rejects duplicate emails", async () => {
    const { user } = await registerBroker();
    const res = await request(app)
      .post("/api/v1/auth/register")
      .send({ brokerageName: "X", name: "Y", email: user.email, password: "password123" });
    expect(res.status).toBe(409);
  });
});

describe("clients", () => {
  it("creates a client with a normalised phone and never invents missing fields", async () => {
    const { api } = await registerBroker();
    const res = await api.post("/clients", newClient());
    expect(res.status).toBe(201);
    expect(res.body.client.primaryPhone).toBe("+919820012345");
    expect(res.body.client.email).toBeNull();
    expect(res.body.client.notes).toBeNull();
    expect(res.body.client.status).toBe("NEW");
  });

  it("prevents duplicate clients by phone in any format, including alternate numbers", async () => {
    const { api } = await registerBroker();
    const first = await api.post("/clients", newClient({ altPhones: ["+91 99300 11111"] }));
    const dup = await api.post("/clients", newClient({ name: "R. Sharma", phone: "+91-98200-12345" }));
    expect(dup.status).toBe(409);
    expect(dup.body.error.code).toBe("DUPLICATE_CLIENT");
    expect(dup.body.error.details.existingClient.id).toBe(first.body.client.id);

    const altDup = await api.post("/clients", newClient({ name: "Other", phone: "9930011111" }));
    expect(altDup.status).toBe(409);

    const check = await api.get("/clients/check-duplicate?phone=09820012345");
    expect(check.body.duplicate.id).toBe(first.body.client.id);
  });

  it("allows the same phone in different brokerages (tenant isolation)", async () => {
    const a = await registerBroker("A");
    const b = await registerBroker("B");
    expect((await a.api.post("/clients", newClient())).status).toBe(201);
    const inB = await b.api.post("/clients", newClient());
    expect(inB.status).toBe(201);
    // B cannot read A's client.
    const aList = await a.api.get("/clients");
    expect((await b.api.get(`/clients/${aList.body.clients[0].id}`)).status).toBe(404);
  });

  it("rejects invalid phone numbers instead of guessing", async () => {
    const { api } = await registerBroker();
    const res = await api.post("/clients", newClient({ phone: "12345" }));
    expect(res.status).toBe(400);
    expect(res.body.error.code).toBe("INVALID_PHONE");
  });

  it("looks up a caller by any of their numbers", async () => {
    const { api } = await registerBroker();
    const c = await api.post("/clients", newClient({ altPhones: ["9930011111"] }));
    await api.post(`/clients/${c.body.client.id}/inquiries`, rent2bhk);
    const hit = await api.get("/clients/lookup?phone=%2B919930011111");
    expect(hit.body.client.id).toBe(c.body.client.id);
    expect(hit.body.client.inquiries).toHaveLength(1);
    const miss = await api.get("/clients/lookup?phone=9876543210");
    expect(miss.body.client).toBeNull();
  });

  it("searches by name and phone digits", async () => {
    const { api } = await registerBroker();
    await api.post("/clients", newClient());
    await api.post("/clients", newClient({ name: "Priya Nair", phone: "9867000000" }));
    expect((await api.get("/clients?q=priya")).body.total).toBe(1);
    expect((await api.get("/clients?q=98200")).body.clients[0].name).toBe("Rahul Sharma");
  });
});

describe("inquiries & history", () => {
  it("supports multiple separate inquiries per client and records change history", async () => {
    const { api } = await registerBroker();
    const c = await api.post("/clients", newClient());
    const id = c.body.client.id;
    const rent = await api.post(`/clients/${id}/inquiries`, rent2bhk);
    const buy = await api.post(`/clients/${id}/inquiries`, {
      transactionType: "BUY",
      category: "BHK_3",
      budgetMax: 35000000,
    });
    expect(rent.status).toBe(201);
    expect(buy.status).toBe(201);
    expect(buy.body.inquiry.locations).toEqual([]);
    expect(buy.body.inquiry.minParking).toBeNull();

    const detail = await api.get(`/clients/${id}`);
    expect(detail.body.client.inquiries).toHaveLength(2);

    const inquiryId = rent.body.inquiry.id;
    const upd = await api.patch(`/inquiries/${inquiryId}`, { budgetMax: 80000, locations: ["Andheri", "Jogeshwari"] });
    expect(upd.status).toBe(200);
    expect(upd.body.changed).toBe(true);
    expect(upd.body.inquiry.version).toBe(2);

    const noop = await api.patch(`/inquiries/${inquiryId}`, { budgetMax: 80000 });
    expect(noop.body.changed).toBe(false);

    const hist = await api.get(`/inquiries/${inquiryId}/history`);
    expect(hist.body.revisions).toHaveLength(2);
    expect(hist.body.revisions[0].changes).toEqual({
      budgetMax: { from: 70000, to: 80000 },
      locations: { from: ["Andheri"], to: ["Andheri", "Jogeshwari"] },
    });
    expect(hist.body.revisions[1].snapshot.budgetMax).toBe(70000);
  });

  it("validates budget ranges including against existing values", async () => {
    const { api } = await registerBroker();
    const c = await api.post("/clients", newClient());
    const bad = await api.post(`/clients/${c.body.client.id}/inquiries`, { ...rent2bhk, budgetMin: 90000 });
    expect(bad.status).toBe(400);
    const ok = await api.post(`/clients/${c.body.client.id}/inquiries`, rent2bhk);
    const bad2 = await api.patch(`/inquiries/${ok.body.inquiry.id}`, { budgetMin: 75000 });
    expect(bad2.status).toBe(400);
  });
});

describe("dashboard", () => {
  it("counts active inquiries per category for rent and buy", async () => {
    const { api } = await registerBroker();
    const a = (await api.post("/clients", newClient())).body.client.id;
    const b = (await api.post("/clients", newClient({ name: "B", phone: "9867000000" }))).body.client.id;
    await api.post(`/clients/${a}/inquiries`, rent2bhk);
    await api.post(`/clients/${b}/inquiries`, rent2bhk);
    await api.post(`/clients/${a}/inquiries`, { transactionType: "BUY", category: "STUDIO" });
    const paused = await api.post(`/clients/${b}/inquiries`, { transactionType: "BUY", category: "COMMERCIAL" });
    await api.patch(`/inquiries/${paused.body.inquiry.id}`, { status: "PAUSED" });

    const d = await api.get("/dashboard");
    expect(d.status).toBe(200);
    const tile = (board: "rent" | "buy", cat: string) =>
      d.body[board].tiles.find((t: { category: string }) => t.category === cat);
    expect(d.body.rent.tiles).toHaveLength(8);
    expect(tile("rent", "BHK_2")).toEqual({ category: "BHK_2", inquiries: 2, clients: 2 });
    expect(tile("buy", "STUDIO").inquiries).toBe(1);
    expect(tile("buy", "COMMERCIAL").inquiries).toBe(0);
    expect(d.body.rent.total).toBe(2);
    expect(d.body.buy.total).toBe(1);
  });
});

describe("matching", () => {
  it("matches inquiries to properties and excludes mandatory violations", async () => {
    const { api } = await registerBroker();
    const c = (await api.post("/clients", newClient())).body.client.id;
    const inquiry = (await api.post(`/clients/${c}/inquiries`, rent2bhk)).body.inquiry;

    const mk = (over: object) =>
      api.post("/properties", {
        title: "2BHK",
        transactionType: "RENT",
        category: "BHK_2",
        price: 65000,
        locality: "Andheri West",
        furnishing: "SEMI_FURNISHED",
        parkingSpots: 1,
        ...over,
      });
    const perfect = (await mk({ title: "Perfect" })).body.property;
    const noParkingInfo = (await mk({ title: "No parking info", parkingSpots: null, price: 60000 })).body.property;
    await mk({ title: "Too expensive", price: 90000 });
    await mk({ title: "Wrong area", locality: "Borivali West" });
    await mk({ title: "Rented out", availability: "RENTED" });
    await mk({ title: "3BHK", category: "BHK_3" });
    const created = await mk({ title: "Bad" });
    expect(created.status).toBe(201);

    const res = await api.get(`/inquiries/${inquiry.id}/matches`);
    expect(res.status).toBe(200);
    const titles = res.body.matches.map((m: { property: { title: string } }) => m.property.title);
    expect(titles).toContain("Perfect");
    expect(titles).toContain("No parking info");
    expect(titles).not.toContain("Too expensive");
    expect(titles).not.toContain("Wrong area");
    expect(titles).not.toContain("Rented out");
    expect(titles).not.toContain("3BHK");
    expect(res.body.matches[0].property.id).toBe(perfect.id);
    expect(res.body.matches[0].score).toBe(100);
    const partial = res.body.matches.find((m: { property: { id: string } }) => m.property.id === noParkingInfo.id);
    expect(partial.score).toBeLessThan(100);

    const reverse = await api.get(`/properties/${perfect.id}/matches`);
    expect(reverse.body.matches).toHaveLength(1);
    expect(reverse.body.matches[0].inquiry.client.name).toBe("Rahul Sharma");
  });
});

describe("reminders", () => {
  it("creates, lists and completes follow-ups", async () => {
    const { api } = await registerBroker();
    const c = (await api.post("/clients", newClient())).body.client.id;
    const due = new Date(Date.now() - 60_000).toISOString();
    const r = await api.post("/reminders", { title: "Call back about Andheri flat", dueAt: due, clientId: c });
    expect(r.status).toBe(201);
    expect(r.body.reminder.client.name).toBe("Rahul Sharma");

    expect((await api.get("/dashboard")).body.reminders.overdue).toBe(1);
    const list = await api.get("/reminders?status=PENDING");
    expect(list.body.reminders).toHaveLength(1);

    const done = await api.patch(`/reminders/${r.body.reminder.id}`, { status: "DONE" });
    expect(done.body.reminder.completedAt).not.toBeNull();
    expect((await api.get("/reminders?status=PENDING")).body.reminders).toHaveLength(0);
  });

  it("rejects reminders for another brokerage's client", async () => {
    const a = await registerBroker("A");
    const b = await registerBroker("B");
    const c = (await a.api.post("/clients", newClient())).body.client.id;
    const res = await b.api.post("/reminders", { title: "x", dueAt: new Date().toISOString(), clientId: c });
    expect(res.status).toBe(404);
  });
});

describe("team", () => {
  it("lets owners add members and enforces roles", async () => {
    const { api } = await registerBroker();
    const add = await api.post("/team", {
      name: "Agent A",
      email: "agent-a@example.com",
      password: "password123",
      role: "AGENT",
    });
    expect(add.status).toBe(201);
    const login = await request(app)
      .post("/api/v1/auth/login")
      .send({ email: "agent-a@example.com", password: "password123" });
    const agent = authed(login.body.token);

    // Agents can work with shared brokerage data...
    expect((await agent.post("/clients", newClient())).status).toBe(201);
    // ...but cannot manage the team, delete clients, or see everyone's reminders.
    expect((await agent.post("/team", { name: "x", email: "x@example.com", password: "password123" })).status).toBe(403);
    const clientId = (await agent.get("/clients")).body.clients[0].id;
    expect((await agent.delete(`/clients/${clientId}`)).status).toBe(403);
    expect((await agent.get("/reminders?scope=all")).status).toBe(403);

    // Deactivation takes effect immediately.
    await api.patch(`/team/${add.body.member.id}`, { active: false });
    expect((await agent.get("/clients")).status).toBe(401);
  });
});

describe("home screen data", () => {
  it("returns totals, today's follow-ups with requirement, new leads and top matches — all from real records", async () => {
    const { api } = await registerBroker();
    const c = (await api.post("/clients", newClient())).body.client;
    const inquiry = (await api.post(`/clients/${c.id}/inquiries`, rent2bhk)).body.inquiry;
    await api.post("/properties", { title: "Lokhandwala 2BHK", transactionType: "RENT", category: "BHK_2", price: 65000, locality: "Andheri West" });
    await api.post("/properties", { title: "Too pricey", transactionType: "RENT", category: "BHK_2", price: 95000, locality: "Andheri West" });
    await api.post("/reminders", { title: "Send properties", dueAt: new Date(Date.now() - 60_000).toISOString(), clientId: c.id });
    await api.post("/reminders", { title: "Next week", dueAt: new Date(Date.now() + 8 * 86_400_000).toISOString(), clientId: c.id });

    const d = (await api.get("/dashboard?tz=330")).body;
    expect(d.totals).toMatchObject({
      clients: 1, newClientsThisWeek: 1, activeRequirements: 1, newRequirementsThisWeek: 1,
      availableProperties: 2, newPropertiesThisWeek: 2, pendingFollowUps: 2, followUpsDueToday: 1,
    });
    expect(d.todayFollowUps).toHaveLength(1);
    expect(d.todayFollowUps[0]).toMatchObject({
      title: "Send properties", overdue: true, client: { name: "Rahul Sharma" },
      requirement: { transactionType: "RENT", category: "BHK_2", location: "Andheri" },
    });
    expect(d.newLeads[0]).toMatchObject({ kind: "CLIENT", name: "Rahul Sharma", source: "WALK_IN" });
    expect(d.topMatches).toHaveLength(1); // over-budget listing excluded by the mandatory budget
    expect(d.topMatches[0]).toMatchObject({ property: { title: "Lokhandwala 2BHK" }, matchingRequirements: 1 });
    expect(inquiry.id).toBeTruthy();
  });

  it("uses the agent's timezone for 'today'", async () => {
    const { api } = await registerBroker();
    // 23:00 IST today is still today in India even though it may be "tomorrow" in UTC terms.
    const now = new Date();
    const istMidnight = new Date(Math.floor((now.getTime() + 330 * 60_000) / 86_400_000) * 86_400_000 - 330 * 60_000);
    const lateTonightIst = new Date(istMidnight.getTime() + 23 * 3_600_000);
    if (lateTonightIst > now) {
      await api.post("/reminders", { title: "Late call", dueAt: lateTonightIst.toISOString() });
      expect((await api.get("/dashboard?tz=330")).body.totals.followUpsDueToday).toBe(1);
    }
  });
});
