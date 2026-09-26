import request from "supertest";
import { beforeEach, describe, expect, it } from "vitest";
import { prisma } from "../src/db.js";
import { parseCsv, parseEnquiryDate } from "../src/services/portalImport.js";
import { normalizeListingUrl } from "../src/services/portalLeads.js";
import { app, registerBroker, resetDb } from "./helpers.js";

beforeEach(resetDb);

const IST = 330 * 60_000;
/** Today (India time) at hh:mm, plus [days], as "26/09/2026 10:15" the way portal exports write it. */
function exportDate(days: number, hh: number, mm = 0) {
  const d = new Date(Date.now() + IST + days * 86_400_000);
  const p = (n: number) => String(n).padStart(2, "0");
  return `${p(d.getUTCDate())}/${p(d.getUTCMonth() + 1)}/${d.getUTCFullYear()} ${p(hh)}:${p(mm)}`;
}
/** Start of today (India time) plus [days], ISO. */
function istDay(days: number) {
  const now = new Date(Date.now() + IST);
  return new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate() + days) - IST).toISOString();
}

const CSV_99 = [
  "Lead ID,Name,Mobile,Email,Enquiry Date,Message,Property ID,Property Title,Locality,Price",
  `L-1,Rahul Sharma,9820011001,rahul@example.com,${exportDate(0, 10, 15)},"Is it still available? Budget 70k",A12345678,2 BHK Apartment for Rent,Andheri West,"70,000"`,
  `L-2,Priya Mehta,9820011002,,${exportDate(0, 11, 0)},Need parking,A12345678,2 BHK Apartment for Rent,Andheri West,"70,000"`,
  `L-3,Amit Patil,9820011003,,${exportDate(-1, 18, 30)},,B99887766,1 BHK Flat for Rent,Mulund West,"27,000"`,
  `L-4,No Date,9820011004,,,Hello,A12345678,,,`,
  `L-5,No Phone,,,${exportDate(0, 9, 0)},Hi,A12345678,,,`,
].join("\n");

describe("portal lead helpers", () => {
  it("parses CSV with quotes and Indian dates (India time)", () => {
    expect(parseCsv('a,"b, c","d ""q"""\n1,2,3\n')).toEqual([["a", "b, c", 'd "q"'], ["1", "2", "3"]]);
    expect(parseEnquiryDate("26/09/2026 10:15 AM")?.toISOString()).toBe("2026-09-26T04:45:00.000Z");
    expect(parseEnquiryDate("26 Sep 2026, 6:05 pm")?.toISOString()).toBe("2026-09-26T12:35:00.000Z");
    expect(parseEnquiryDate("2026-09-26T10:00:00Z")?.toISOString()).toBe("2026-09-26T10:00:00.000Z");
    expect(parseEnquiryDate("31/02/2026")).toBeNull();
    expect(parseEnquiryDate("sometime")).toBeNull();
  });

  it("compares listing links without tracking parameters", () => {
    expect(normalizeListingUrl("https://www.99acres.com/abc-A123?utm_source=wa#top")).toBe("https://99acres.com/abc-A123");
    expect(normalizeListingUrl("javascript:alert(1)")).toBeNull();
  });
});

describe("portal leads: import, listings, clients", () => {
  it("imports a 99acres export: one listing per portal ID, one client per phone, skips unusable rows, no duplicates", async () => {
    const { api } = await registerBroker();
    const res = await api.post("/portal-leads/import", { portal: "ACRES_99", csv: CSV_99, fileName: "leads.csv" });
    expect(res.status).toBe(200);
    expect(res.body).toMatchObject({ rows: 5, created: 3, duplicates: 0, clientsCreated: 3 });
    expect(res.body.skipped).toEqual([{ row: 5, reason: "No enquiry date" }, { row: 6, reason: "No phone number or email" }]);

    const listings = await prisma.portalListing.findMany({ orderBy: { externalId: "asc" } });
    expect(listings.map((l) => [l.portal, l.externalId, l.title, l.category, l.transactionType, l.locality, Number(l.price)])).toEqual([
      ["ACRES_99", "A12345678", "2 BHK Apartment for Rent", "BHK_2", "RENT", "Andheri West", 70000],
      ["ACRES_99", "B99887766", "1 BHK Flat for Rent", "BHK_1", "RENT", "Mulund West", 27000],
    ]);
    const rahul = await prisma.client.findFirstOrThrow({ where: { name: "Rahul Sharma" } });
    expect(rahul).toMatchObject({ primaryPhone: "+919820011001", email: "rahul@example.com", leadSource: "ACRES_99", status: "NEW" });

    // Importing the same file again stores nothing new.
    const again = await api.post("/portal-leads/import", { portal: "ACRES_99", csv: CSV_99 });
    expect(again.body).toMatchObject({ created: 0, duplicates: 3, clientsCreated: 0 });
    expect(await prisma.portalLead.count()).toBe(3);
    expect(await prisma.client.count()).toBe(3);
  });

  it("refuses a file without date and phone/email columns", async () => {
    const { api } = await registerBroker();
    const res = await api.post("/portal-leads/import", { portal: "ACRES_99", csv: "Foo,Bar\n1,2" });
    expect(res.status).toBe(400);
    expect(res.body.error.code).toBe("UNRECOGNISED_FILE");
  });

  it("keeps each enquiry, links clients by phone/email only — never by name", async () => {
    const { api } = await registerBroker();
    const existing = (await api.post("/clients", { name: "Rahul Sharma", phone: "9811199999", email: "rs@example.com", leadSource: "WALK_IN" })).body.client;
    const csv = [
      "Name,Mobile,Email,Enquiry Date,Property ID",
      // Same name, different phone, no email: a different person → new client.
      `Rahul Sharma,9820099999,,${exportDate(0, 9)},H-1`,
      // Different phone but the existing client's email → that client.
      `R. Sharma,9820088888,rs@example.com,${exportDate(0, 10)},H-1`,
      // The existing client's phone → that client, second enquiry kept separately.
      `Rahul,9811199999,,${exportDate(0, 11)},H-2`,
    ].join("\n");
    const res = await api.post("/portal-leads/import", { portal: "HOUSING_COM", csv });
    expect(res.body).toMatchObject({ created: 3, clientsCreated: 1 });
    const leads = await prisma.portalLead.findMany({ orderBy: { enquiredAt: "asc" } });
    expect(leads[0].clientId).not.toBe(existing.id);
    expect(leads[1].clientId).toBe(existing.id);
    expect(leads[2].clientId).toBe(existing.id);
    expect(await prisma.client.count()).toBe(2);

    // The client's profile shows their enquiries with the listing.
    const profile = (await api.get(`/clients/${existing.id}`)).body.client;
    expect(profile.portalLeads).toHaveLength(2);
    expect(profile.portalLeads[0]).toMatchObject({ portal: "HOUSING_COM", listing: { portal: "HOUSING_COM" } });
  });

  it("keeps 99acres and Housing.com apart, even for the same flat", async () => {
    const { api } = await registerBroker();
    const row = `Asha,9820011111,,${exportDate(0, 10)},SAME-ID,2 BHK Flat,Powai,"1.9 Cr"`;
    const csv = `Name,Mobile,Email,Enquiry Date,Property ID,Property Title,Locality,Price\n${row}`;
    await api.post("/portal-leads/import", { portal: "ACRES_99", csv });
    await api.post("/portal-leads/import", { portal: "HOUSING_COM", csv });
    const listings = await prisma.portalListing.findMany();
    expect(listings.map((l) => l.portal).sort()).toEqual(["ACRES_99", "HOUSING_COM"]);
    expect(await prisma.portalLead.count()).toBe(2);
    expect(await prisma.client.count()).toBe(1); // same person (same phone) on both portals

    const acres = (await api.get("/portal-leads/listings?portal=ACRES_99")).body.listings;
    const housing = (await api.get("/portal-leads/listings?portal=HOUSING_COM")).body.listings;
    expect(acres).toHaveLength(1);
    expect(housing).toHaveLength(1);
    expect(acres[0].id).not.toBe(housing[0].id);
    const acresLeads = (await api.get(`/portal-leads/listings/${acres[0].id}`)).body.leads;
    expect(acresLeads.every((l: { portal: string }) => l.portal === "ACRES_99")).toBe(true);
  });

  it("lists listings by most recent enquiry with counts, and respects the date range", async () => {
    const { api } = await registerBroker();
    await api.post("/portal-leads/import", { portal: "ACRES_99", csv: CSV_99 });
    const today = { from: istDay(0), to: istDay(1) };
    const q = (r: { from: string; to: string }) => `from=${encodeURIComponent(r.from)}&to=${encodeURIComponent(r.to)}`;

    const all = (await api.get("/portal-leads/listings?portal=ACRES_99")).body.listings;
    expect(all.map((l: { externalId: string }) => l.externalId)).toEqual(["A12345678", "B99887766"]);
    expect(all[0]).toMatchObject({ interestedClients: 2, newLeads: 2, totalLeads: 2, identified: true, locality: "Andheri West" });

    const todays = (await api.get(`/portal-leads/listings?portal=ACRES_99&${q(today)}`)).body.listings;
    expect(todays.map((l: { externalId: string }) => l.externalId)).toEqual(["A12345678"]);
    const yesterday = (await api.get(`/portal-leads/listings?portal=ACRES_99&${q({ from: istDay(-1), to: istDay(0) })}`)).body.listings;
    expect(yesterday.map((l: { externalId: string }) => l.externalId)).toEqual(["B99887766"]);

    // The listing's interested clients follow the same range and show only that listing's leads.
    const detail = (await api.get(`/portal-leads/listings/${all[0].id}?${q(today)}`)).body;
    expect(detail.leads.map((l: { name: string }) => l.name).sort()).toEqual(["Priya Mehta", "Rahul Sharma"]);
    expect(detail.leads[0].client).toMatchObject({ name: "Priya Mehta" });
    expect((await api.get(`/portal-leads/listings/${all[0].id}?${q({ from: istDay(-1), to: istDay(0) })}`)).body.leads).toEqual([]);
    expect((await api.get("/portal-leads/listings?portal=HOUSING_COM")).body.listings).toEqual([]);
  });

  it("groups enquiries without an identifiable listing separately", async () => {
    const { api } = await registerBroker();
    await api.post("/portal-leads/import", { portal: "ACRES_99", csv: `Name,Mobile,Enquiry Date\nZed,9820012121,${exportDate(0, 9)}` });
    const [only] = (await api.get("/portal-leads/listings?portal=ACRES_99")).body.listings;
    expect(only).toMatchObject({ id: "unidentified", identified: false, title: null, totalLeads: 1 });
    expect((await api.get("/portal-leads/listings/unidentified?portal=ACRES_99")).body.leads).toHaveLength(1);
    expect((await api.get("/portal-leads/listings/unidentified")).status).toBe(400);
  });

  it("updates lead status; contacting moves a New client to Contacted", async () => {
    const { api } = await registerBroker();
    await api.post("/portal-leads/import", { portal: "ACRES_99", csv: CSV_99 });
    const lead = await prisma.portalLead.findFirstOrThrow({ where: { name: "Rahul Sharma" } });
    const res = await api.patch(`/portal-leads/${lead.id}`, { status: "CONTACTED" });
    expect(res.body.lead.status).toBe("CONTACTED");
    expect((await prisma.client.findUniqueOrThrow({ where: { id: lead.clientId! } })).status).toBe("CONTACTED");
    expect((await api.patch(`/portal-leads/${lead.id}`, { status: "MAYBE" })).status).toBe(400);
    const other = await registerBroker("Other");
    expect((await other.api.patch(`/portal-leads/${lead.id}`, { status: "CLOSED" })).status).toBe(404);
  });

  it("links a listing to the broker's own property only when exactly one fits", async () => {
    const { api } = await registerBroker();
    const mine = (await api.post("/properties", {
      title: "2 BHK Oberoi", transactionType: "RENT", category: "BHK_2", price: 70000, locality: "Andheri West", notes: "99acres A12345678",
    })).body.property;
    await api.post("/portal-leads/import", { portal: "ACRES_99", csv: CSV_99 });
    const byId = await prisma.portalListing.findFirstOrThrow({ where: { externalId: "A12345678" } });
    expect(byId.propertyId).toBe(mine.id);
    // Two 1 BHKs at the same price in Mulund West: ambiguous → not linked.
    for (const t of ["A", "B"]) {
      await api.post("/properties", { title: `1 BHK ${t}`, transactionType: "RENT", category: "BHK_1", price: 27000, locality: "Mulund West" });
    }
    await api.post("/portal-leads/import", { portal: "ACRES_99", csv: `Name,Mobile,Enquiry Date,Property Title,Locality,Price\nQ,9820010101,${exportDate(0, 8)},1 BHK Flat for Rent,Mulund West,"27,000"` });
    const noId = await prisma.portalListing.findFirstOrThrow({ where: { externalId: null } });
    expect(noId.propertyId).toBeNull();
  });

  it("imports a pasted lead email", async () => {
    const { api } = await registerBroker();
    const text = "Dear Advertiser, You have received a response on your property ID A55555555 (3 BHK Apartment for Sale in Powai, Mumbai, ₹2.5 Cr). Name: Neel Shah, Mobile: +91-9876500001, Email: neel@example.com. - 99acres";
    const res = await api.post("/portal-leads/import-text", { text });
    expect(res.status).toBe(201);
    expect(res.body.lead).toMatchObject({ portal: "ACRES_99", channel: "EMAIL", name: "Neel Shah", phone: "+919876500001" });
    const listing = await prisma.portalListing.findFirstOrThrow();
    expect(listing).toMatchObject({ externalId: "A55555555", title: "3 BHK Apartment", locality: "Powai" });
    expect(Number(listing.price)).toBe(25000000);
    expect((await api.post("/portal-leads/import-text", { text, portal: "HOUSING_COM" })).status).toBe(400);
    expect((await api.post("/portal-leads/import-text", { text: "Hi, 2 BHK chahiye Andheri mein" })).status).toBe(400);
  });
});

describe("portal integrations", () => {
  it("is Import Required, then Not Connected with a key, then Connected once a lead arrives", async () => {
    const { api } = await registerBroker();
    const status = async () => (await api.get("/portal-leads/integrations")).body.portals;
    expect((await status()).map((p: { status: string }) => p.status)).toEqual(["IMPORT_REQUIRED", "IMPORT_REQUIRED"]);

    const key = (await api.post("/portal-leads/integrations/HOUSING_COM/key", {})).body.key as string;
    expect((await status())[1].status).toBe("NOT_CONNECTED");

    const inbound = (body: object, k = key) => request(app).post(`/api/v1/portal-inbound/${k}`).send(body);
    expect((await inbound({ enquiredAt: "2026-09-26T10:00:00Z", phone: "9820077777" }, "bbpl_wrong")).status).toBe(404);
    const res = await inbound({
      leads: [
        { leadId: "H-100", enquiredAt: "2026-09-26T10:00:00Z", name: "Kiran", phone: "9820077777", listing: { id: "HX1", title: "2 BHK for rent", locality: "Chembur", price: 50000 } },
        { leadId: "H-101", enquiredAt: "2026-09-26T11:00:00Z", name: "No contact" },
      ],
    });
    expect(res.body).toMatchObject({ created: 1, duplicates: 0, rejected: [{ index: 1, reason: "phone or email is required" }] });
    const [, housing] = await status();
    expect(housing).toMatchObject({ status: "CONNECTED", leadsByChannel: { WEBHOOK: 1 } });
    expect((await inbound({ leadId: "H-100", enquiredAt: "2026-09-26T10:00:00Z", phone: "9820077777" })).body.duplicates).toBe(1);
    const lead = await prisma.portalLead.findFirstOrThrow({ include: { listing: true } });
    expect(lead).toMatchObject({ portal: "HOUSING_COM", channel: "WEBHOOK", externalLeadId: "H-100" });
    expect(lead.listing).toMatchObject({ category: "BHK_2", transactionType: "RENT" });
  });

  it("only owners and admins create inbound keys", async () => {
    const { api } = await registerBroker();
    await api.post("/team", { name: "Agent", email: "ag@example.com", password: "password123", role: "AGENT" });
    const login = await request(app).post("/api/v1/auth/login").send({ email: "ag@example.com", password: "password123" });
    const res = await request(app).post("/api/v1/portal-leads/integrations/ACRES_99/key").set({ Authorization: `Bearer ${login.body.token}` });
    expect(res.status).toBe(403);
  });
});

describe("Today's Work", () => {
  it("counts new leads, callbacks, follow-ups and today's portal leads per portal", async () => {
    const { api } = await registerBroker();
    await api.post("/portal-leads/import", { portal: "ACRES_99", csv: CSV_99 }); // 2 today, 1 yesterday; 3 new clients
    await api.post("/portal-leads/import", { portal: "HOUSING_COM", csv: `Name,Mobile,Enquiry Date\nH,9820030303,${exportDate(0, 9)}` });
    const client = await prisma.client.findFirstOrThrow({ where: { name: "Rahul Sharma" } });
    await api.patch(`/clients/${client.id}`, { status: "CONTACTED" });
    const soon = new Date(Date.now() + 60_000).toISOString();
    await api.post("/reminders", { title: "Call back Rahul", dueAt: soon, clientId: client.id, kind: "CALLBACK" });
    await api.post("/reminders", { title: "Send options", dueAt: soon, clientId: client.id });
    await api.post("/reminders", { title: "Later", dueAt: new Date(Date.now() + 5 * 86_400_000).toISOString() });

    const d = (await api.get("/dashboard?tz=330")).body;
    expect(d.todayWork).toEqual({ newLeads: 3, callbacks: 1, followUps: 1, acres99Leads: 2, housingLeads: 1 });
    const callbacks = (await api.get("/reminders?status=PENDING&kind=CALLBACK")).body.reminders;
    expect(callbacks.map((r: { title: string }) => r.title)).toEqual(["Call back Rahul"]);
  });
});

describe("voice commands", () => {
  async function setup() {
    const b = await registerBroker();
    await b.api.post("/portal-leads/import", { portal: "ACRES_99", csv: CSV_99 });
    const cmd = async (text: string) => (await b.api.post("/assistant/command", { text, tz: 330 })).body;
    return { ...b, cmd };
  }

  it("opens portal leads for a day, in English, Hinglish, Hindi and Marathi", async () => {
    const { cmd } = await setup();
    expect((await cmd("Show today's 99acres leads.")).navigate).toEqual({ screen: "PORTAL_LEADS", portal: "ACRES_99", range: "TODAY" });
    expect((await cmd("Show Housing.com leads from yesterday.")).navigate).toEqual({ screen: "PORTAL_LEADS", portal: "HOUSING_COM", range: "YESTERDAY" });
    expect((await cmd("99acres ki pichle 7 din ki leads dikhao")).navigate).toMatchObject({ portal: "ACRES_99", range: "LAST_7_DAYS" });
    expect((await cmd("हाउसिंग की कल की लीड दिखाओ")).navigate).toMatchObject({ portal: "HOUSING_COM", range: "YESTERDAY" });
    expect((await cmd("आजचे 99acres लीड दाखवा")).navigate).toMatchObject({ portal: "ACRES_99", range: "TODAY" });
    expect((await cmd("show my leads")).options).toHaveLength(2);
  });

  it("finds everyone interested in a property by its area", async () => {
    const { cmd } = await setup();
    const res = await cmd("Show everyone interested in the Andheri property");
    const listing = await prisma.portalListing.findFirstOrThrow({ where: { externalId: "A12345678" } });
    expect(res).toMatchObject({ intent: "SHOW_LISTING_CLIENTS", navigate: { screen: "PORTAL_LISTING", listingId: listing.id, portal: "ACRES_99" } });
    expect((await cmd("Andheri wali property mein kaun interested hai")).navigate.listingId).toBe(listing.id);
    expect((await cmd("Show everyone interested in the Bandra property")).navigate).toBeUndefined();
  });

  it("marks a client's latest portal lead, and sets follow-ups", async () => {
    const { cmd } = await setup();
    const res = await cmd("Mark Rahul as contacted.");
    expect(res).toMatchObject({ intent: "SET_LEAD_STATUS", done: true });
    const lead = await prisma.portalLead.findFirstOrThrow({ where: { name: "Rahul Sharma" } });
    expect(lead.status).toBe("CONTACTED");
    expect((await cmd("Priya ko not interested mark karo")).done).toBe(true);
    expect((await prisma.portalLead.findFirstOrThrow({ where: { name: "Priya Mehta" } })).status).toBe("NOT_INTERESTED");

    const f = await cmd("Set a follow-up with Rahul tomorrow at 5 pm.");
    expect(f).toMatchObject({ intent: "CREATE_FOLLOW_UP", done: true });
    const r = await prisma.reminder.findFirstOrThrow();
    expect(r.title).toBe("Follow up with Rahul Sharma");
    const local = new Date(r.dueAt.getTime() + IST);
    expect(local.getUTCHours()).toBe(17);
    expect(new Date(istDay(1)).getTime()).toBeLessThanOrEqual(r.dueAt.getTime());

    await cmd("Amit ke saath kal follow up");
    expect(await prisma.reminder.count()).toBe(2);
  });

  it("never picks between clients with the same name", async () => {
    const { api, cmd } = await setup();
    await api.post("/clients", { name: "Rahul Verma", phone: "9811100000", leadSource: "WALK_IN" });
    const res = await cmd("Mark Rahul as contacted");
    expect(res).toMatchObject({ done: false });
    expect(res.options).toHaveLength(2);
    expect(await prisma.portalLead.count({ where: { status: "CONTACTED" } })).toBe(0);
    // The full name is unambiguous.
    expect((await cmd("Mark Rahul Sharma as contacted")).done).toBe(true);
    expect((await cmd("Mark Zoya as contacted")).message).toMatch(/couldn't find/);
    expect((await cmd("what is the weather")).intent).toBe("UNKNOWN");
  });
});
