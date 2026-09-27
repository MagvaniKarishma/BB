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

describe("quality review: duplicates across sources", () => {
  const acres = (text: string) => ({ text });
  it("the same enquiry via WhatsApp and then the CSV export is one lead; separate enquiries stay separate", async () => {
    const { api } = await registerBroker();
    // 1) The portal's WhatsApp/email notification.
    const email = "Dear Advertiser, You have received a response on your property ID A12345678 (2 BHK Apartment for Rent in Andheri West, Mumbai, ₹70,000). Name: Rahul Sharma, Mobile: +91-9820011001. - 99acres";
    await api.post("/portal-leads/import-text", { ...acres(email), enquiredAt: exportDate(0, 10, 15) });
    // 2) The same enquiry in the CSV export (2 minutes' difference in the portal's timestamp).
    const csv = `Lead ID,Name,Mobile,Enquiry Date,Message,Property ID\nL-1,Rahul Sharma,9820011001,${exportDate(0, 10, 17)},Is it available?,A12345678`;
    const res = await api.post("/portal-leads/import", { portal: "ACRES_99", csv });
    expect(res.body).toMatchObject({ created: 0, duplicates: 1, clientsCreated: 0 });
    const [lead] = await prisma.portalLead.findMany();
    expect(lead).toMatchObject({ externalLeadId: "L-1", message: "Is it available?" }); // gaps filled from the second source
    expect(await prisma.portalLead.count()).toBe(1);
    expect(await prisma.client.count()).toBe(1);

    // Same person, same listing, two hours later: a new enquiry.
    await api.post("/portal-leads/import", { portal: "ACRES_99", csv: `Name,Mobile,Enquiry Date,Property ID\nRahul Sharma,9820011001,${exportDate(0, 12, 30)},A12345678` });
    // Same person, a different listing, same minute: a separate enquiry.
    await api.post("/portal-leads/import", { portal: "ACRES_99", csv: `Name,Mobile,Enquiry Date,Property ID\nRahul Sharma,9820011001,${exportDate(0, 10, 15)},B55555555` });
    // Two different portal lead IDs are never merged, however close in time.
    await api.post("/portal-leads/import", { portal: "ACRES_99", csv: `Lead ID,Name,Mobile,Enquiry Date,Property ID\nL-2,Rahul Sharma,9820011001,${exportDate(0, 10, 16)},A12345678` });
    expect(await prisma.portalLead.count()).toBe(4);
    expect(await prisma.client.count()).toBe(1); // still one client
  });

  it("a WhatsApp webhook retry of the same message stores one lead", async () => {
    const { api } = await registerBroker();
    const text = "You have a new lead on Housing.com. Name: Neha Rao, Mobile: 9820066666, Property: 1 BHK Apartment in Mulund West, Price: ₹ 27,000";
    const first = await api.post("/whatsapp/import", { text });
    const again = await api.post("/whatsapp/import", { text });
    expect(again.body.message.id).toBe(first.body.message.id);
    expect(await prisma.portalLead.count()).toBe(1);
  });

  it("without a phone or email nothing is merged or created", async () => {
    const { api } = await registerBroker();
    const text = "Hi, I came across your 1 BHK Apartment listed at Housing.com for ₹ 27,000 in Mulund West, Mumbai. Please let me know if it is available.";
    await api.post("/whatsapp/import", { text });
    await api.post("/whatsapp/import", { text: `${text} Thanks` });
    const leads = await prisma.portalLead.findMany();
    expect(leads).toHaveLength(2);
    expect(leads.every((l) => l.clientId === null)).toBe(true);
    expect(await prisma.client.count()).toBe(0);
    // An invalid number is not a reliable identifier either.
    const res = await api.post("/portal-leads/import", { portal: "HOUSING_COM", csv: `Name,Mobile,Enquiry Date\nX,12,${exportDate(0, 9)}` });
    expect(res.body.clientsCreated).toBe(0);
  });
});

describe("quality review: access control", () => {
  it("one broker can't see or change another broker's leads, listings or clients", async () => {
    const a = await registerBroker("A Realty");
    const b = await registerBroker("B Realty");
    await a.api.post("/portal-leads/import", { portal: "ACRES_99", csv: CSV_99 });
    const listing = await prisma.portalListing.findFirstOrThrow();
    const lead = await prisma.portalLead.findFirstOrThrow();

    expect((await b.api.get("/portal-leads/listings?portal=ACRES_99")).body.listings).toEqual([]);
    expect((await b.api.get(`/portal-leads/listings/${listing.id}`)).status).toBe(404);
    expect((await b.api.get("/portal-leads/listings/unidentified?portal=ACRES_99")).body.leads).toEqual([]);
    expect((await b.api.get("/portal-leads")).body.leads).toEqual([]);
    expect((await b.api.get(`/portal-leads?clientId=${lead.clientId}`)).body.leads).toEqual([]);
    expect((await b.api.patch(`/portal-leads/${lead.id}`, { status: "CLOSED" })).status).toBe(404);
    expect((await b.api.get(`/clients/${lead.clientId}`)).status).toBe(404);
    expect((await b.api.get("/dashboard")).body.todayWork).toMatchObject({ acres99Leads: 0, newLeads: 0 });
    // Voice commands only see the caller's own clients.
    expect((await b.api.post("/assistant/command", { text: "Mark Rahul as contacted" })).body.done).toBe(false);
    expect((await prisma.portalLead.findUniqueOrThrow({ where: { id: lead.id } })).status).toBe("NEW");
    // B importing the same phone makes B's own client, never touching A's.
    await b.api.post("/portal-leads/import", { portal: "ACRES_99", csv: CSV_99 });
    const rahuls = await prisma.client.findMany({ where: { primaryPhone: "+919820011001" } });
    expect(new Set(rahuls.map((c) => c.brokerageId)).size).toBe(2);
  });

  it("every new endpoint needs sign-in except the inbound link, which needs its key", async () => {
    const routes: [string, string][] = [
      ["get", "/api/v1/portal-leads/listings?portal=ACRES_99"],
      ["get", "/api/v1/portal-leads/listings/x"],
      ["get", "/api/v1/portal-leads"],
      ["patch", "/api/v1/portal-leads/x"],
      ["post", "/api/v1/portal-leads/import"],
      ["post", "/api/v1/portal-leads/import-text"],
      ["get", "/api/v1/portal-leads/integrations"],
      ["post", "/api/v1/portal-leads/integrations/ACRES_99/key"],
      ["post", "/api/v1/assistant/command"],
    ];
    for (const [m, url] of routes) {
      const res = await (request(app) as unknown as Record<string, (u: string) => request.Test>)[m](url);
      expect(res.status, `${m} ${url}`).toBe(401);
    }
    expect((await request(app).post("/api/v1/portal-inbound/nokey").send({ enquiredAt: "2026-09-26", phone: "9820000000" })).status).toBe(404);
  });

  it("an inbound key only ever writes to its own brokerage and portal", async () => {
    const a = await registerBroker("A Realty");
    const b = await registerBroker("B Realty");
    const key = (await a.api.post("/portal-leads/integrations/ACRES_99/key", {})).body.key;
    await request(app).post(`/api/v1/portal-inbound/${key}`).send({ enquiredAt: "2026-09-26T10:00:00Z", phone: "9820012345", name: "Z" });
    const lead = await prisma.portalLead.findFirstOrThrow();
    expect(lead.portal).toBe("ACRES_99");
    expect(lead.brokerageId).toBe((await prisma.user.findFirstOrThrow({ where: { id: a.user.id } })).brokerageId);
    expect((await b.api.get("/portal-leads")).body.leads).toEqual([]);
    // Only a hash of the key is stored.
    const stored = await prisma.portalIntegration.findFirstOrThrow();
    expect(JSON.stringify(stored)).not.toContain(key);
  });
});

describe("quality review: logging", () => {
  it("unexpected database errors are logged without client data", async () => {
    const { describeForLog } = await import("../src/lib/errors.js");
    const { Prisma } = await import("@prisma/client");
    const err = new Prisma.PrismaClientValidationError("Invalid value for name: 'Rahul Sharma' phone +919820011001", { clientVersion: "x" });
    const line = describeForLog(err);
    expect(line).not.toContain("Rahul");
    expect(line).not.toContain("9820011001");
  });
});

describe("quality review: upgrade backfill", () => {
  it("adds leads for portal messages received before the upgrade, once, without creating clients", async () => {
    const { api } = await registerBroker();
    await api.post("/whatsapp/import", { text: "You have a new lead on Housing.com. Name: Neha Rao, Mobile: 9820066666, Property: 1 BHK Apartment in Mulund West, Price: ₹ 27,000" });
    // As if the message had been processed before portal leads existed.
    await prisma.portalLead.deleteMany();
    await prisma.portalListing.deleteMany();
    await prisma.whatsAppMessage.updateMany({ data: { clientId: null } });
    await prisma.client.deleteMany();
    const { backfillPortalLeads } = await import("../scripts/backfill-portal-leads.js");
    expect(await backfillPortalLeads(false)).toMatchObject({ added: 1 });
    expect(await prisma.portalLead.count()).toBe(0); // dry run writes nothing
    expect(await backfillPortalLeads(true)).toMatchObject({ added: 1, existing: 0 });
    expect(await backfillPortalLeads(true)).toMatchObject({ added: 0, existing: 1 });
    const lead = await prisma.portalLead.findFirstOrThrow({ include: { listing: true } });
    expect(lead).toMatchObject({ portal: "HOUSING_COM", phone: "+919820066666", clientId: null });
    expect(lead.listing).toMatchObject({ locality: "Mulund West" });
    expect(await prisma.client.count()).toBe(0);
  });
});

describe("requirement from the enquired listing", () => {
  it("a portal enquiry gives the client a requirement filled from the listing", async () => {
    const { api } = await registerBroker();
    await api.post("/portal-leads/import", {
      portal: "HOUSING_COM",
      csv: `Name,Mobile,Enquiry Date,Message,Property ID,Property Title,Locality,Price\nPooja Iyer,9820033002,${exportDate(0, 9)},Is it available?,H88001,1 BHK Apartment for Rent,Mulund West,"27,000"`,
    });
    const client = await prisma.client.findFirstOrThrow({ include: { inquiries: true } });
    expect(client.inquiries).toHaveLength(1);
    const req = client.inquiries[0];
    expect(req).toMatchObject({
      transactionType: "RENT", category: "BHK_1", locations: ["Mulund West"], source: "PORTAL_LEAD", status: "ACTIVE",
      propertyTypes: ["APARTMENT"], budgetMin: null, mandatory: [],
    });
    // No budget in the client's words: the listing's price, and the notes say so.
    expect(Number(req.budgetMax)).toBe(27000);
    expect(req.notes).toContain("Housing.com enquiry about 1 BHK Apartment for Rent, Mulund West listed at ₹27,000/month");
    expect(req.notes).toContain("Filled from the listing — confirm with the client: property type, budget (the listing's price)");
    expect(req.notes).toContain("Is it available?");
    // History starts with the portal lead as its source.
    expect((await api.get(`/inquiries/${req.id}/history`)).body.revisions[0].source).toBe("PORTAL_LEAD");

    // Another enquiry of the same kind doesn't add a second requirement or change the first.
    await api.post("/portal-leads/import", {
      portal: "HOUSING_COM",
      csv: `Name,Mobile,Enquiry Date,Property ID,Property Title,Locality,Price\nPooja Iyer,9820033002,${exportDate(0, 13)},H88009,1 BHK Flat for Rent,Thane West,"25,000"`,
    });
    const after = await prisma.inquiry.findMany();
    expect(after).toHaveLength(1);
    expect(after[0].locations).toEqual(["Mulund West"]);
  });

  it("takes furnishing, parking, floor and possession from the broker's own listing, and the budget from the client's message", async () => {
    const { api } = await registerBroker();
    await api.post("/properties", {
      title: "1 BHK, Mulund West", transactionType: "RENT", category: "BHK_1", propertyType: "APARTMENT", price: 27000,
      locality: "Mulund West", furnishing: "SEMI_FURNISHED", parkingSpots: 1, floor: 12, totalFloors: 14, possession: "READY_TO_MOVE",
    });
    await api.post("/portal-leads/import", {
      portal: "ACRES_99",
      csv: `Name,Mobile,Enquiry Date,Message,Property ID,Property Title,Locality,Price\nKavya Nair,9820022003,${exportDate(0, 9)},Budget 26k,A70005555,1 BHK Flat for Rent,Mulund West,"27,000"`,
    });
    const req = (await prisma.client.findFirstOrThrow({ include: { inquiries: true } })).inquiries[0];
    expect(req).toMatchObject({
      furnishing: ["SEMI_FURNISHED"], minParking: 1, floorPreference: ["HIGHER"], possession: "READY_TO_MOVE",
      propertyTypes: ["APARTMENT"], mandatory: [],
    });
    expect(Number(req.budgetMax)).toBe(26000); // what the client said, not the ₹27,000 listing price
    expect(req.notes).toContain("confirm with the client: property type, furnishing, parking, floor level, possession.");
  });

  it("reads property types from portal titles", async () => {
    const { propertyTypeFromTitle } = await import("../src/services/portalLeads.js");
    expect(propertyTypeFromTitle("1 BHK Flat for Rent")).toBe("APARTMENT");
    expect(propertyTypeFromTitle("4 BHK Independent House for Sale")).toBe("INDEPENDENT_HOUSE");
    expect(propertyTypeFromTitle("3 BHK Villa")).toBe("VILLA");
    expect(propertyTypeFromTitle("Office Space for Rent")).toBe("COMMERCIAL");
    expect(propertyTypeFromTitle("2 BHK for Rent")).toBeNull(); // not said
  });

  it("uses a budget the client stated, and skips listings that don't say rent/sale and type", async () => {
    const { api } = await registerBroker();
    await api.post("/portal-leads/import", {
      portal: "ACRES_99",
      csv: `Name,Mobile,Enquiry Date,Budget,Property ID,Property Title,Locality,Price\nA,9820044001,${exportDate(0, 9)},"1.8 Cr",A1,2 BHK Apartment for Sale,Powai,"1.95 Cr"\nB,9820044002,${exportDate(0, 9)},,A2,Apartment,Powai,"1.95 Cr"`,
    });
    const a = await prisma.client.findFirstOrThrow({ where: { name: "A" }, include: { inquiries: true } });
    expect(a.inquiries[0]).toMatchObject({ transactionType: "BUY", category: "BHK_2" });
    expect(Number(a.inquiries[0].budgetMax)).toBe(18000000);
    const b = await prisma.client.findFirstOrThrow({ where: { name: "B" }, include: { inquiries: true } });
    expect(b.inquiries).toHaveLength(0); // "Apartment" with no rent/sale or BHK: nothing guessed
  });

  it("a WhatsApp enquiry linked to a client later also gives the requirement", async () => {
    const { api } = await registerBroker();
    const text = "Hi, I came across your 1 BHK Apartment for rent listed at Housing.com for ₹ 27,000 in Mulund West, Mumbai. Please let me know if it is available.";
    const msg = (await api.post("/whatsapp/import", { text })).body.message;
    expect(await prisma.inquiry.count()).toBe(0); // no phone yet → no client, no requirement
    await api.post(`/whatsapp/messages/${msg.id}/create-client`, { name: "Sameer", phone: "9820033001" });
    const client = await prisma.client.findFirstOrThrow({ include: { inquiries: true } });
    expect(client.inquiries.map((i) => [i.category, i.locations])).toEqual([["BHK_1", ["Mulund West"]]]);
  });
});
