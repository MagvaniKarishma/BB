/**
 * Builds the app's offline demo ("Try the demo" on the sign-in screen).
 *
 * Creates a sample brokerage through the real API (fictional clients, listings, follow-ups,
 * WhatsApp leads, a voice note), then saves the server's answer to every read the app's screens
 * make into android/app/src/main/assets/demo/responses.json. The app serves those answers on the
 * phone when demo mode is on, so every screen shows real server output without a server.
 *
 * Run against an EMPTY scratch database — it wipes the one it is given:
 *   DATABASE_URL=postgresql://…/brokerbuddy_demo LISTING_LOOKUP=off ANTHROPIC_API_KEY= \
 *     npx prisma migrate deploy && npx tsx scripts/demo-snapshot.ts
 *
 * Keys must match the app's DemoApi.key(): the path after /api/v1/, then "?" and the query
 * parameters sorted by name (tz, page, pageSize, from and to are ignored).
 */
import { createHash } from "node:crypto";
import { mkdtemp, mkdir, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import request from "supertest";
import { createApp } from "../src/app.js";
import { prisma } from "../src/db.js";
import { EncryptingStore, LocalDiskStore, setBlobStore } from "../src/storage/blobStore.js";
import { setVoiceServices } from "../src/voice/service.js";

const OUT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../../android/app/src/main/assets/demo/responses.json");
const IGNORED = new Set(["tz", "page", "pageSize", "from", "to"]);
const IST_MS = 330 * 60_000;

const app = createApp();
let token = "";
const h = () => ({ Authorization: `Bearer ${token}` });

async function call(method: "get" | "post" | "put" | "patch", url: string, body?: object) {
  const req = request(app)[method](`/api/v1${url}`).set(h());
  const res = method === "get" ? await req : await req.send(body ?? {});
  if (res.status >= 300) throw new Error(`${method.toUpperCase()} ${url} → ${res.status} ${JSON.stringify(res.body)}`);
  return res.body;
}
const post = (url: string, body?: object) => call("post", url, body);

/** Today (IST) at hh:mm, plus [days], as an ISO instant. */
function ist(days: number, hh: number, mm = 0): string {
  const now = new Date(Date.now() + IST_MS);
  const d = Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate() + days, hh, mm) - IST_MS;
  return new Date(d).toISOString();
}

// --- sample data (all people, numbers and listings are fictional) ---
type C = { name: string; phone: string; leadSource: string; status: string; notes?: string; req?: object };
const clients: C[] = [
  { name: "Rahul Sharma", phone: "+919820011001", leadSource: "WALK_IN", status: "SITE_VISIT",
    req: { transactionType: "RENT", category: "BHK_2", budgetMin: 60000, budgetMax: 75000, locations: ["Andheri West"], furnishing: ["SEMI_FURNISHED"], minParking: 1, mandatory: ["BUDGET", "LOCATION"], notes: "Family of 4, needs school nearby" } },
  { name: "Priya Mehta", phone: "+919820011002", leadSource: "ACRES_99", status: "NEW",
    req: { transactionType: "BUY", category: "BHK_3", budgetMin: 22000000, budgetMax: 28000000, locations: ["Powai"], furnishing: [], minParking: 2, floorPreference: ["MIDDLE", "HIGHER"], propertyTypes: ["APARTMENT"], possession: "READY_TO_MOVE", mandatory: ["BUDGET"], notes: "Lake view preferred" } },
  { name: "Amit Patil", phone: "+919820011003", leadSource: "HOUSING_COM", status: "CONTACTED",
    req: { transactionType: "RENT", category: "BHK_1", budgetMin: 25000, budgetMax: 30000, locations: ["Mulund West"], furnishing: ["SEMI_FURNISHED", "FULLY_FURNISHED"], mandatory: ["BUDGET"] } },
  { name: "Sneha Iyer", phone: "+919820011004", leadSource: "REFERRAL", status: "NEGOTIATION",
    req: { transactionType: "BUY", category: "BHK_2", budgetMin: 14000000, budgetMax: 17000000, locations: ["Goregaon East"], furnishing: [], minParking: 1, mandatory: ["BUDGET", "LOCATION"], notes: "Referred by Rahul Sharma" } },
  { name: "Vikram Singh", phone: "+919820011005", leadSource: "WHATSAPP", status: "NEW",
    req: { transactionType: "RENT", category: "BHK_3", budgetMin: 150000, budgetMax: 200000, locations: ["Bandra West"], furnishing: ["FULLY_FURNISHED"], minParking: 2, floorPreference: ["HIGHER"], mandatory: ["FURNISHING"], notes: "Sea-facing if possible" } },
  { name: "Fatima Shaikh", phone: "+919820011006", leadSource: "MAGICBRICKS", status: "CONTACTED",
    req: { transactionType: "RENT", category: "BHK_1", budgetMin: 20000, budgetMax: 28000, locations: ["Malad West"], furnishing: [], mandatory: [] } },
  { name: "Rohan Kulkarni", phone: "+919820011007", leadSource: "PHONE_CALL", status: "ON_HOLD",
    req: { transactionType: "BUY", category: "BHK_1", budgetMin: 7000000, budgetMax: 9000000, locations: ["Thane West"], furnishing: [], possession: "UNDER_CONSTRUCTION", mandatory: [], notes: "Waiting for home-loan approval" } },
  { name: "Neha Joshi", phone: "+919820011008", leadSource: "WEBSITE", status: "CLOSED_WON",
    req: { transactionType: "RENT", category: "BHK_2", status: "FULFILLED", budgetMin: 45000, budgetMax: 55000, locations: ["Chembur"], furnishing: ["SEMI_FURNISHED"], mandatory: [] } },
  { name: "Karan Malhotra", phone: "+919820011009", leadSource: "SOCIAL_MEDIA", status: "CLOSED_LOST",
    req: { transactionType: "BUY", category: "COMMERCIAL", status: "DROPPED", budgetMin: 30000000, budgetMax: 40000000, locations: ["Andheri East"], furnishing: [], mandatory: [], notes: "Office space, bought elsewhere" } },
  { name: "Anjali Rao", phone: "+919820011010", leadSource: "WALK_IN", status: "NEW",
    req: { transactionType: "RENT", category: "BHK_2", budgetMin: 70000, budgetMax: 80000, locations: ["Andheri West", "Juhu"], furnishing: ["FULLY_FURNISHED"], minParking: 1, propertyTypes: ["APARTMENT"], mandatory: ["LOCATION"] } },
  { name: "Deepak Gupta", phone: "+919820011011", leadSource: "REFERRAL", status: "SITE_VISIT",
    req: { transactionType: "BUY", category: "BHK_2", budgetMin: 18000000, budgetMax: 21000000, locations: ["Powai"], furnishing: [], minParking: 1, mandatory: ["BUDGET"] } },
];

const properties = [
  { title: "2 BHK in Oberoi Splendor", transactionType: "RENT", category: "BHK_2", propertyType: "APARTMENT", price: 70000, deposit: 210000, locality: "Andheri West", building: "Oberoi Splendor", carpetAreaSqft: 850, bathrooms: 2, furnishing: "SEMI_FURNISHED", parkingSpots: 1, floor: 8, totalFloors: 22, possession: "READY_TO_MOVE", builtUpAreaSqft: 1050, amenities: ["Lift", "Gym", "Swimming pool", "Power backup", "Security"], ownerName: "Mr Mehta", ownerPhone: "+919811100001" },
  { title: "2 BHK near Lokhandwala", transactionType: "RENT", category: "BHK_2", propertyType: "APARTMENT", price: 65000, deposit: 200000, locality: "Andheri West", building: "Green Acres", carpetAreaSqft: 780, bathrooms: 2, furnishing: "SEMI_FURNISHED", parkingSpots: 1, floor: 4, totalFloors: 12, possession: "READY_TO_MOVE", amenities: ["Lift", "Security"], ownerName: "Mrs Kapoor", ownerPhone: "+919811100002" },
  { title: "Furnished 2 BHK, Juhu", transactionType: "RENT", category: "BHK_2", propertyType: "APARTMENT", price: 78000, deposit: 300000, locality: "Juhu", building: "Sea Breeze", carpetAreaSqft: 900, bathrooms: 2, furnishing: "FULLY_FURNISHED", parkingSpots: 1, floor: 6, totalFloors: 10, possession: "READY_TO_MOVE", builtUpAreaSqft: 1100, amenities: ["Lift", "Sea view", "Power backup"], ownerName: "Mr D'Souza", ownerPhone: "+919811100003" },
  { title: "3 BHK lake view, Hiranandani", transactionType: "BUY", category: "BHK_3", propertyType: "APARTMENT", price: 25500000, locality: "Powai", building: "Hiranandani Gardens", carpetAreaSqft: 1350, bathrooms: 3, furnishing: "SEMI_FURNISHED", parkingSpots: 2, floor: 14, totalFloors: 25, possession: "READY_TO_MOVE", builtUpAreaSqft: 1650, amenities: ["Lift", "Clubhouse", "Swimming pool", "Gym", "Garden"], ownerName: "Mr Rao", ownerPhone: "+919811100004" },
  { title: "2 BHK, Powai", transactionType: "BUY", category: "BHK_2", propertyType: "APARTMENT", price: 19500000, locality: "Powai", building: "Lake Homes", carpetAreaSqft: 900, bathrooms: 2, furnishing: "UNFURNISHED", parkingSpots: 1, floor: 9, totalFloors: 20, possession: "READY_TO_MOVE", amenities: ["Lift", "Garden"], ownerName: "Mrs Nair", ownerPhone: "+919811100005" },
  { title: "1 BHK, Mulund West", transactionType: "RENT", category: "BHK_1", propertyType: "APARTMENT", price: 27000, deposit: 100000, locality: "Mulund West", building: "Nirmal Lifestyle", carpetAreaSqft: 450, bathrooms: 1, furnishing: "SEMI_FURNISHED", parkingSpots: 0, floor: 3, totalFloors: 14, possession: "READY_TO_MOVE", ownerName: "Mr Shah", ownerPhone: "+919811100006" },
  { title: "2 BHK, Goregaon East", transactionType: "BUY", category: "BHK_2", propertyType: "APARTMENT", price: 16000000, locality: "Goregaon East", building: "Oberoi Esquire", carpetAreaSqft: 820, bathrooms: 2, furnishing: "UNFURNISHED", parkingSpots: 1, floor: 11, totalFloors: 30, possession: "READY_TO_MOVE", ownerName: "Mr Bhatt", ownerPhone: "+919811100007" },
  { title: "3 BHK sea view, Bandra", transactionType: "RENT", category: "BHK_3", propertyType: "APARTMENT", price: 185000, deposit: 1000000, locality: "Bandra West", building: "Carter Road Residency", carpetAreaSqft: 1500, bathrooms: 3, furnishing: "FULLY_FURNISHED", parkingSpots: 2, floor: 7, totalFloors: 12, possession: "READY_TO_MOVE", builtUpAreaSqft: 1800, amenities: ["Lift", "Sea view", "Gym", "Power backup"], ownerName: "Mrs Fernandes", ownerPhone: "+919811100008" },
  { title: "1 BHK, Malad West", transactionType: "RENT", category: "BHK_1", propertyType: "APARTMENT", price: 26000, deposit: 80000, locality: "Malad West", building: "Raheja Classique", carpetAreaSqft: 480, bathrooms: 1, furnishing: "UNFURNISHED", parkingSpots: 0, floor: 2, totalFloors: 7, possession: "READY_TO_MOVE", ownerName: "Mr Khan", ownerPhone: "+919811100009" },
  { title: "1 BHK under construction, Thane", transactionType: "BUY", category: "BHK_1", propertyType: "APARTMENT", price: 8200000, locality: "Thane West", building: "Lodha Amara", carpetAreaSqft: 430, bathrooms: 1, furnishing: "UNFURNISHED", parkingSpots: 0, floor: 18, totalFloors: 32, possession: "UNDER_CONSTRUCTION", possessionDate: ist(400, 12), amenities: ["Clubhouse", "Swimming pool"], ownerName: "Builder sales office", ownerPhone: "+919811100010" },
  { title: "2 BHK, Chembur", transactionType: "RENT", category: "BHK_2", propertyType: "APARTMENT", price: 50000, deposit: 150000, locality: "Chembur", building: "Rustomjee Elanza", carpetAreaSqft: 700, bathrooms: 2, furnishing: "SEMI_FURNISHED", parkingSpots: 1, floor: 5, totalFloors: 15, possession: "READY_TO_MOVE", availability: "RENTED", ownerName: "Mr Pillai", ownerPhone: "+919811100011" },
  { title: "4 BHK bungalow, Madh Island", transactionType: "BUY", category: "BHK_4", propertyType: "INDEPENDENT_HOUSE", price: 95000000, locality: "Madh Island", carpetAreaSqft: 3200, builtUpAreaSqft: 4000, bathrooms: 4, furnishing: "SEMI_FURNISHED", parkingSpots: 3, floor: 0, totalFloors: 2, possession: "READY_TO_MOVE", amenities: ["Garden", "Private terrace", "Security"], ownerName: "Mr Almeida", ownerPhone: "+919811100013" },
  { title: "Studio, Andheri East", transactionType: "RENT", category: "STUDIO", propertyType: "APARTMENT", price: 22000, deposit: 60000, locality: "Andheri East", building: "Kanakia Boomerang", carpetAreaSqft: 350, bathrooms: 1, furnishing: "FULLY_FURNISHED", parkingSpots: 0, floor: 3, totalFloors: 9, possession: "READY_TO_MOVE", ownerName: "Mrs Joshi", ownerPhone: "+919811100012" },
];

async function build() {
  await prisma.$executeRawUnsafe(
    `TRUNCATE "WhatsAppMessage","WhatsAppContact","WhatsAppAccount","ClientNote","VoiceNote","Reminder","InquiryRevision","Inquiry","ClientPhone","Client","OtpChallenge","PropertyPhoto","Property","CallTurn","CallSession","CallGreeting","CallAssistantSettings","PortalLead","PortalListing","PortalIntegration","User","Brokerage" CASCADE`,
  );
  const reg = await request(app).post("/api/v1/auth/register").send({
    brokerageName: "Sunrise Realty, Andheri", name: "Riya Desai", email: "demo@brokerbuddy.app", password: "demo-password-1",
  });
  token = reg.body.token;
  const agent = await post("/team", { name: "Arjun Nair", email: "arjun@brokerbuddy.app", password: "demo-password-1", role: "AGENT" });

  const ids: string[] = [];
  const inquiryIds: string[] = [];
  for (const c of clients) {
    const { client } = await post("/clients", { name: c.name, phone: c.phone, leadSource: c.leadSource, status: c.status });
    ids.push(client.id);
    if (c.req) {
      const { inquiry } = await post(`/clients/${client.id}/inquiries`, c.req);
      inquiryIds.push(inquiry.id);
    }
  }
  // A requirement edit, so History has something to show.
  await call("patch", `/inquiries/${inquiryIds[0]}`, { ...clients[0].req, budgetMax: 80000, source: "MANUAL" });
  await call("patch", `/clients/${ids[0]}`, { assignedToId: agent.member.id });

  for (const p of properties) await post("/properties", p);

  const reminders: [number, string, string, number, number][] = [
    [0, "Call about Oberoi Splendor visit", ist(0, 11, 30), 0, 0],
    [1, "Share Hiranandani 3 BHK brochure", ist(0, 15), 0, 0],
    [4, "Send Bandra sea-view options", ist(0, 18, 30), 0, 0],
    [3, "Negotiation call with seller", ist(-1, 17), 0, 0],
    [5, "Follow up on Malad listing", ist(-2, 12), 0, 0],
    [9, "Site visit: Juhu furnished 2 BHK", ist(1, 11), 0, 0],
    [10, "Confirm Powai visit time", ist(2, 16), 0, 0],
    [6, "Check loan status", ist(7, 12), 0, 0],
  ];
  for (const [i, title, dueAt] of reminders) await post("/reminders", { title, dueAt, clientId: ids[i] });
  await post("/reminders", { title: "Call back Priya about Hiranandani", dueAt: ist(0, 13), clientId: ids[1], kind: "CALLBACK" });
  const done = await post("/reminders", { title: "Collect documents for agreement", dueAt: ist(-3, 12), clientId: ids[7] });
  await call("patch", `/reminders/${done.reminder.id}`, { status: "DONE" });

  await post(`/clients/${ids[0]}/notes`, { body: "Liked Oberoi Splendor; wants to see it again with his wife on Sunday.", source: "MANUAL" });
  await post(`/clients/${ids[3]}/notes`, { body: "Seller open to ₹1.58 Cr if registration is done this month.", source: "CALLER_SCREEN" });
  await post(`/clients/${ids[1]}/notes`, { body: "Prefers a higher floor, lake-facing.", source: "MANUAL" });

  // Voice notes: one saved to the requirement, one waiting for review.
  const v1 = await post("/voice-notes/text", { clientId: ids[9], transcript: "Anjali ko 2 BHK chahiye Andheri West ya Juhu mein, fully furnished, budget 80 hazaar tak, ek parking", language: "HINGLISH" });
  await post(`/voice-notes/${v1.voiceNote.id}/apply`, {
    inquiryId: inquiryIds[9],
    requirement: { ...clients[9].req, status: "ACTIVE", budgetMax: 80000, source: "VOICE_NOTE" },
  });
  await post("/voice-notes/text", { clientId: ids[10], transcript: "Deepak wants a 2 BHK in Powai, around 2 crore, ready to move, one car parking", language: "ENGLISH" });

  // WhatsApp: portal enquiries shared into the app, and a chat export for one client.
  await post("/whatsapp/import", { text: "Hi, I came across your 1 BHK Apartment listed at Housing.com for ₹ 27,000 in Mulund West, Mumbai. Please let me know if it is available. - Sameer" });
  await post("/whatsapp/import", { text: "Hi, I am interested in 2 BHK Flat in Powai, Mumbai listed on 99acres for ₹ 1.95 Cr. Please share details. Regards, Meera" });
  await post("/whatsapp/import", { text: "Hello, looking for 3 BHK on rent in Bandra West, fully furnished, budget 1.8 lakh", clientId: ids[4] });
  await post("/whatsapp/import-chat", {
    clientId: ids[2], clientSenderName: "Amit Patil",
    exportText: "20/09/2026, 10:15 am - Amit Patil: 1 BHK chahiye Mulund West mein\n20/09/2026, 10:16 am - Riya: Budget kitna hai?\n20/09/2026, 10:17 am - Amit Patil: 30k tak, semi furnished chalega",
  });

  // Portal lead exports (fictional people): 99acres and Housing.com kept separate, even for similar flats.
  const d = (days: number, hh: number, mm = 0) => {
    const t = new Date(new Date(ist(days, hh, mm)).getTime() + IST_MS);
    const p2 = (n: number) => String(n).padStart(2, "0");
    return `${p2(t.getUTCDate())}/${p2(t.getUTCMonth() + 1)}/${t.getUTCFullYear()} ${p2(t.getUTCHours())}:${p2(t.getUTCMinutes())}`;
  };
  const head = "Lead ID,Name,Mobile,Email,Enquiry Date,Message,Property ID,Property Title,Locality,Price";
  await post("/portal-leads/import", { portal: "ACRES_99", fileName: "99acres-leads.csv", csv: [head,
    `A-1001,Rahul Sharma,+919820011001,,${d(0, 9, 40)},"Is it still available? Can I visit Sunday?",A70001234,2 BHK Apartment for Rent,Andheri West,"70,000"`,
    `A-1002,Meera Joshi,+919820022001,meera.j@example.com,${d(0, 11, 5)},Need parking for 1 car,A70001234,2 BHK Apartment for Rent,Andheri West,"70,000"`,
    `A-1003,Imran Khan,+919820022002,,${d(0, 12, 30)},,A70009876,3 BHK Apartment for Sale,Powai,"2.55 Cr"`,
    `A-1004,Deepak Gupta,+919820011011,,${d(-1, 18, 15)},Looking for ready possession,A70009876,3 BHK Apartment for Sale,Powai,"2.55 Cr"`,
    `A-1005,Kavya Nair,+919820022003,,${d(-3, 10, 0)},Budget 26k,A70005555,1 BHK Flat for Rent,Mulund West,"27,000"`,
  ].join("\n") });
  await post("/portal-leads/import", { portal: "HOUSING_COM", fileName: "housing-leads.csv", csv: [head,
    `H-501,Sameer Desai,+919820033001,,${d(0, 10, 20)},Interested. Is broker fee negotiable?,H88001,1 BHK Apartment for Rent,Mulund West,"27,000"`,
    `H-502,Vikram Singh,+919820011005,,${d(0, 8, 50)},Sea view needed,H88002,3 BHK Apartment for Rent,Bandra West,"1.85 Lac"`,
    `H-503,Pooja Iyer,+919820033002,,${d(-1, 16, 0)},,H88001,1 BHK Apartment for Rent,Mulund West,"27,000"`,
  ].join("\n") });

  await call("put", "/call-assistant/settings", {
    enabled: true, mode: "SMART_ASSISTANT", voice: "RECORDED_STANDARD", customGreetingEnabled: false, defaultLanguage: "HINGLISH",
    businessHours: { days: [1, 2, 3, 4, 5, 6], start: "09:30", end: "20:00", timeZone: "Asia/Kolkata" },
    callbackReminder: true, callbackDelayMinutes: 15, callbackAssigneeId: null, unclearBehavior: "TAKE_CALLBACK",
    maxUnclearRetries: 2, humanTransfer: "ON_REQUEST", transferNumber: "+919820000001", businessNumbers: [],
  });
}

// --- capture every read the screens make ---
const out: Record<string, unknown> = {};
function key(p: string, params: Record<string, string | undefined> = {}) {
  const q = Object.entries(params).filter(([k, v]) => v != null && v !== "" && !IGNORED.has(k)).sort(([a], [b]) => a.localeCompare(b));
  return q.length ? `${p}?${q.map(([k, v]) => `${k}=${v}`).join("&")}` : p;
}
async function grab(p: string, params: Record<string, string | undefined> = {}) {
  const qs = new URLSearchParams(Object.entries(params).filter(([, v]) => v != null) as [string, string][]).toString();
  const body = await call("get", `/${p}${qs ? `?${qs}` : ""}`);
  out[key(p, params)] = body;
  return body;
}

async function capture() {
  const tz = "330";
  for (const p of ["auth/me", "auth/otp", "team", "caller/directory", "whatsapp/account", "call-assistant", "call-assistant/calls"]) await grab(p);
  await grab("dashboard", { tz });

  const clientIds = new Set<string>();
  for (const group of [undefined, "all", "new", "active", "followup", "lost"]) {
    const res = await grab("clients", { group, tz });
    for (const c of res.clients) clientIds.add(c.id);
  }
  for (const id of clientIds) {
    await grab(`clients/${id}`);
    await grab(`clients/${id}/notes`);
    await grab("voice-notes", { clientId: id });
    await grab(`whatsapp/clients/${id}/messages`);
  }

  const inquiryIds = new Set<string>();
  const cats = ["STUDIO", "BHK_1", "BHK_2", "BHK_3", "BHK_4", "BHK_5_PLUS", "COMMERCIAL", "OTHER"];
  for (const transactionType of [undefined, "RENT", "BUY"]) {
    for (const category of [undefined, ...cats]) {
      const res = await grab("inquiries", { transactionType, category });
      for (const i of res.inquiries) inquiryIds.add(i.id);
    }
  }
  for (const id of inquiryIds) {
    await grab(`inquiries/${id}`);
    await grab(`inquiries/${id}/history`);
    await grab(`inquiries/${id}/matches`);
  }

  const propertyIds = new Set<string>();
  for (const transactionType of [undefined, "RENT", "BUY"]) {
    for (const availability of [undefined, "AVAILABLE", "ON_HOLD", "RENTED", "SOLD", "WITHDRAWN"]) {
      const res = await grab("properties", { transactionType, availability });
      for (const p of res.properties) propertyIds.add(p.id);
    }
  }
  for (const id of propertyIds) {
    await grab(`properties/${id}`);
    await grab(`properties/${id}/matches`);
  }

  for (const status of [undefined, "PENDING", "DONE", "CANCELLED"]) await grab("reminders", { status });
  for (const kind of ["CALLBACK", "FOLLOW_UP"]) {
    for (const status of ["PENDING", "DONE"]) await grab("reminders", { status, kind });
  }

  // Portal leads (the demo shows all dates: the app's date range isn't part of the key).
  await grab("portal-leads/integrations");
  for (const portal of ["ACRES_99", "HOUSING_COM"]) {
    const res = await grab("portal-leads/listings", { portal });
    for (const l of res.listings) await grab(`portal-leads/listings/${l.id}`, { portal });
  }

  const notes = await grab("voice-notes");
  for (const n of notes.voiceNotes) await grab(`voice-notes/${n.id}`);

  const messageIds = new Set<string>();
  for (const filter of ["attention", "needs_client", "needs_review", "all"]) {
    const res = await grab("whatsapp/inbox", { filter });
    for (const m of res.messages) messageIds.add(m.id);
  }
  for (const id of messageIds) await grab(`whatsapp/messages/${id}`);
}

/**
 * Stable ids: every generated id is replaced by one derived from the record's own content (not its
 * dates or links), so a re-captured snapshot keeps the same ids and the demo changes a broker saved
 * on the phone still point at the right sample records after an app update.
 */
async function stableIds(): Promise<Map<string, string>> {
  const tables = (await prisma.$queryRawUnsafe<{ t: string }[]>(
    `SELECT table_name AS t FROM information_schema.columns WHERE table_schema = 'public' AND column_name = 'id' AND table_name <> '_prisma_migrations' ORDER BY table_name`,
  )).map((r) => r.t);
  const rows: [string, Record<string, unknown>][] = [];
  for (const t of tables) {
    for (const r of await prisma.$queryRawUnsafe<Record<string, unknown>[]>(`SELECT * FROM "${t}"`)) rows.push([t, r]);
  }
  const ids = new Set(rows.map(([, r]) => String(r.id)));
  // Dates move with the capture day; hashes and ciphertext (random salts and nonces) change every run.
  const plain = (v: unknown): unknown =>
    v instanceof Date || v instanceof Uint8Array ? undefined
    : typeof v === "string" && ids.has(v) ? "#"
    : typeof v === "string" && /^[\w+/=:$.-]{32,}$/.test(v) ? undefined
    : typeof v === "string" ? v.replace(/c[a-z0-9]{24}/g, (m) => (ids.has(m) ? "#" : m))
    : typeof v === "bigint" ? v.toString()
    : Array.isArray(v) ? v.map(plain)
    : v;
  const used = new Map<string, number>();
  const map = new Map<string, string>();
  for (const [t, r] of rows) {
    const content = Object.keys(r).sort().filter((k) => k !== "id").map((k) => [k, plain(r[k])]).filter(([, v]) => v !== undefined);
    const base = `${t}:${JSON.stringify(content)}`;
    const n = used.get(base) ?? 0;
    used.set(base, n + 1);
    map.set(String(r.id), `demo${createHash("sha256").update(`${base}#${n}`).digest("hex").slice(0, 21)}`);
  }
  return map;
}

async function main() {
  setVoiceServices(null);
  setBlobStore(new EncryptingStore(new LocalDiskStore(await mkdtemp(path.join(tmpdir(), "bb-demo-")))));
  await build();
  await capture();
  await mkdir(path.dirname(OUT), { recursive: true });
  const capturedOn = new Date(Date.now() + IST_MS).toISOString().slice(0, 10);
  let json = JSON.stringify({ capturedOn, responses: out });
  for (const [from, to] of await stableIds()) json = json.split(from).join(to);
  await writeFile(OUT, `${json}\n`);
  console.log(`wrote ${Object.keys(out).length} responses (captured ${capturedOn}) to ${OUT}`);
  await prisma.$disconnect();
}

main().catch(async (e) => {
  console.error(e);
  await prisma.$disconnect();
  process.exit(1);
});
