import { beforeEach, describe, expect, it } from "vitest";
import { registerBroker, resetDb } from "./helpers.js";

beforeEach(resetDb);

async function seed() {
  const { api } = await registerBroker();
  const mk = async (name: string, phone: string, status: string) =>
    (await api.post("/clients", { name, phone, leadSource: "WALK_IN", status })).body.client;
  const amit = await mk("Amit Patil", "9820011111", "CONTACTED");
  const priya = await mk("Priya Sharma", "9820022222", "NEW");
  const rohit = await mk("Rohit Kulkarni", "9820033333", "CLOSED_LOST");
  await mk("Neha Desai", "9820044444", "NEW");
  await api.post(`/clients/${amit.id}/inquiries`, {
    transactionType: "RENT", category: "BHK_2", locations: ["Andheri West"], budgetMin: 65000, budgetMax: 75000,
  });
  await api.post(`/clients/${priya.id}/inquiries`, { transactionType: "BUY", category: "BHK_1", locations: ["Malad"] });
  await api.post("/reminders", { title: "Discuss 2 BHK options", clientId: amit.id, dueAt: new Date(Date.now() - 3_600_000).toISOString() });
  await api.post("/reminders", { title: "Later", clientId: rohit.id, dueAt: new Date(Date.now() + 5 * 86_400_000).toISOString() });
  await api.post("/properties", { title: "2 BHK Andheri", transactionType: "RENT", category: "BHK_2", price: 70000, locality: "Andheri West" });
  return { api, amit, priya };
}

describe("client list tabs", () => {
  it("counts each tab and filters by it, with a one-line requirement per client", async () => {
    const { api, amit } = await seed();
    const all = (await api.get("/clients")).body;
    expect(all.groupCounts).toEqual({ all: 4, new: 2, active: 1, followup: 1, lost: 1 });
    const row = all.clients.find((c: { id: string }) => c.id === amit.id);
    expect(row).toMatchObject({
      requirement: { transactionType: "RENT", category: "BHK_2", locations: ["Andheri West"], budgetMin: 65000, budgetMax: 75000 },
      followUpDue: true,
    });
    expect((await api.get("/clients?group=new")).body.clients.map((c: { name: string }) => c.name).sort()).toEqual(["Neha Desai", "Priya Sharma"]);
    expect((await api.get("/clients?group=followup")).body.clients.map((c: { name: string }) => c.name)).toEqual(["Amit Patil"]);
    // Search narrows the counts too.
    expect((await api.get("/clients?q=priya")).body.groupCounts).toEqual({ all: 1, new: 1, active: 0, followup: 0, lost: 0 });
  });
});

describe("requirements list", () => {
  it("shows each active requirement with its client, match count and search", async () => {
    const { api } = await seed();
    const res = (await api.get("/inquiries")).body;
    expect(res.total).toBe(2);
    const rent = res.inquiries.find((i: { transactionType: string }) => i.transactionType === "RENT");
    expect(rent).toMatchObject({ client: { name: "Amit Patil" }, matchCount: 1 });
    expect((await api.get("/inquiries?q=malad")).body.inquiries.map((i: { client: { name: string } }) => i.client.name)).toEqual(["Priya Sharma"]);
    expect((await api.get("/inquiries?transactionType=BUY")).body.total).toBe(1);
  });
});
