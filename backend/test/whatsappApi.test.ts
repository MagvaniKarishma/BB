import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from "vitest";
import http from "node:http";
import type { AddressInfo } from "node:net";
import { createHmac } from "node:crypto";
import request from "supertest";
import { REAL_99ACRES_1, REAL_HOUSING_1, REAL_HOUSING_2, app, authed, metaPayload, registerBroker, resetDb } from "./helpers.js";
import { webhookIdle } from "../src/routes/whatsappWebhook.js";
import { prisma } from "../src/db.js";
import { rulesExtractor, setVoiceServices } from "../src/voice/service.js";

const PHONE_NUMBER_ID = "106540352242922";
const APP_SECRET = "meta-app-secret-for-tests-123456";

// Local stand-in for graph.facebook.com.
let graph: http.Server;
const graphCalls: { method: string; url: string; body: string; auth?: string }[] = [];
beforeAll(async () => {
  graph = http.createServer((req, res) => {
    let body = "";
    req.on("data", (c) => (body += c));
    req.on("end", () => {
      graphCalls.push({ method: req.method!, url: req.url!, body, auth: req.headers.authorization });
      res.setHeader("content-type", "application/json");
      if (req.url!.endsWith("/messages")) {
        const to = JSON.parse(body).to;
        if (to === "910000000000") {
          res.statusCode = 400;
          res.end(JSON.stringify({ error: { message: "Recipient phone number not in allowed list", code: 131030 } }));
          return;
        }
        res.end(JSON.stringify({ messaging_product: "whatsapp", messages: [{ id: `wamid.OUT${graphCalls.length}` }] }));
      } else if (req.url!.endsWith("/MEDIA_AUDIO")) {
        const port = (graph.address() as AddressInfo).port;
        res.end(JSON.stringify({ url: `http://127.0.0.1:${port}/download/audio`, mime_type: "audio/ogg; codecs=opus" }));
      } else if (req.url === "/download/audio") {
        res.setHeader("content-type", "audio/ogg");
        res.end("OGG-BYTES");
      } else {
        res.statusCode = 404;
        res.end("{}");
      }
    });
  });
  await new Promise<void>((r) => graph.listen(0, r));
  process.env.WHATSAPP_GRAPH_URL = `http://127.0.0.1:${(graph.address() as AddressInfo).port}`;
});
afterAll(() => {
  graph.close();
  delete process.env.WHATSAPP_GRAPH_URL;
});
beforeEach(async () => {
  await resetDb();
  graphCalls.length = 0;
  setVoiceServices({ transcriber: null, extractor: rulesExtractor });
});
afterEach(() => setVoiceServices(null));

async function setup() {
  const broker = await registerBroker();
  const connect = await broker.api.put("/whatsapp/account", {
    phoneNumberId: PHONE_NUMBER_ID,
    displayPhone: "+91 98200 00000",
    accessToken: "EAAG-test-access-token-0123456789",
    appSecret: APP_SECRET,
  });
  expect(connect.status).toBe(200);
  const { webhookPath, verifyToken } = connect.body.account;
  const deliver = async (payload: object, secret = APP_SECRET) => {
    const body = JSON.stringify(payload);
    const sig = "sha256=" + createHmac("sha256", secret).update(body).digest("hex");
    const res = await request(app).post(webhookPath).set("Content-Type", "application/json").set("X-Hub-Signature-256", sig).send(body);
    await webhookIdle();
    return res;
  };
  let seq = 0;
  const text = (from: string, body: string, name = "WhatsApp User") =>
    metaPayload(PHONE_NUMBER_ID, [{ from, id: `wamid.${++seq}.${Date.now()}`, timestamp: String(Math.floor(Date.now() / 1000)), type: "text", text: { body } }], [
      { profile: { name }, wa_id: from },
    ]);
  return { ...broker, webhookPath, verifyToken, deliver, text };
}

describe("connecting a WhatsApp Business number", () => {
  it("stores secrets encrypted, never returns them, and restricts to owners/admins", async () => {
    const { api, verifyToken, webhookPath } = await setup();
    expect(webhookPath).toMatch(/^\/webhooks\/whatsapp\/[\w-]{20,}$/);
    const got = await api.get("/whatsapp/account");
    expect(got.body.connected).toBe(true);
    expect(JSON.stringify(got.body)).not.toContain("EAAG");
    expect(JSON.stringify(got.body)).not.toContain(APP_SECRET);
    const stored = await prisma.whatsAppAccount.findFirstOrThrow();
    expect(stored.accessTokenEnc).not.toContain("EAAG");
    expect(verifyToken.length).toBeGreaterThan(16);

    await api.post("/team", { name: "Agent", email: "agent@example.com", password: "password123" });
    const agent = authed((await request(app).post("/api/v1/auth/login").send({ email: "agent@example.com", password: "password123" })).body.token);
    expect((await agent.get("/whatsapp/account")).status).toBe(403);

    const other = await registerBroker("Other");
    const steal = await other.api.put("/whatsapp/account", {
      phoneNumberId: PHONE_NUMBER_ID, accessToken: "x".repeat(30), appSecret: "y".repeat(20),
    });
    expect(steal.status).toBe(409);
  });

  it("answers Meta's verification handshake only with the right token", async () => {
    const { webhookPath, verifyToken } = await setup();
    const ok = await request(app).get(webhookPath).query({ "hub.mode": "subscribe", "hub.verify_token": verifyToken, "hub.challenge": "1158201444" });
    expect(ok.status).toBe(200);
    expect(ok.text).toBe("1158201444");
    const bad = await request(app).get(webhookPath).query({ "hub.mode": "subscribe", "hub.verify_token": "wrong", "hub.challenge": "1" });
    expect(bad.status).toBe(403);
    expect((await request(app).get("/webhooks/whatsapp/unknown-key").query({ "hub.mode": "subscribe" })).status).toBe(403);
    expect((await prisma.whatsAppAccount.findFirstOrThrow()).verifiedAt).not.toBeNull();
  });
});

describe("receiving messages", () => {
  it("rejects unsigned/forged deliveries and dedupes Meta's retries", async () => {
    const { deliver, text } = await setup();
    const payload = text("919820012345", "Hi");
    expect((await deliver(payload, "forged-secret")).status).toBe(401);
    expect(await prisma.whatsAppMessage.count()).toBe(0);
    expect((await deliver(payload)).status).toBe(200);
    expect((await deliver(payload)).status).toBe(200); // retry
    expect(await prisma.whatsAppMessage.count()).toBe(1);
  });

  it("links a known client's message to their only matching inquiry with a draft to review", async () => {
    const { api, deliver, text } = await setup();
    const client = (await api.post("/clients", { name: "Rahul Sharma", phone: "9820012345", leadSource: "WALK_IN" })).body.client;
    const rent = (await api.post(`/clients/${client.id}/inquiries`, { transactionType: "RENT", category: "BHK_2", budgetMax: 60000 })).body.inquiry;
    await api.post(`/clients/${client.id}/inquiries`, { transactionType: "BUY", category: "BHK_3" });

    await deliver(text("919820012345", "Budget 70 hazaar tak badha sakte hain, 2 BHK kiraye pe, Jogeshwari bhi chalega", "Rahul"));
    const [msg] = (await api.get(`/whatsapp/clients/${client.id}/messages`)).body.messages;
    expect(msg).toMatchObject({ clientId: client.id, inquiryId: rent.id, review: "PENDING", leadPhone: "+919820012345", channel: "API" });
    expect(msg.raw).toBeUndefined();
    expect(msg.extraction.draft.budgetMax.value).toBe(70000);

    // Agent reviews and saves → WHATSAPP revision linked to the message.
    const applied = await api.post(`/whatsapp/messages/${msg.id}/apply`, {
      inquiryId: rent.id,
      requirement: { budgetMax: 70000, locations: ["Jogeshwari"] },
    });
    expect(applied.status).toBe(200);
    expect(applied.body.inquiry).toMatchObject({ version: 2, budgetMax: 70000, source: "WHATSAPP" });
    const rev = await prisma.inquiryRevision.findFirstOrThrow({ where: { inquiryId: rent.id, version: 2 } });
    expect(rev.whatsappMessageId).toBe(msg.id);
    expect(rev.changes).toMatchObject({ budgetMax: { from: 60000, to: 70000 } });
    expect((await api.post(`/whatsapp/messages/${msg.id}/apply`, { requirement: {} })).status).toBe(409);
  });

  it("unknown sender → inbox lead → create client once; later messages link automatically", async () => {
    const { api, deliver, text } = await setup();
    await deliver(text("919867000000", "Hello, Powai mein 3 BHK kharidna hai, 2.5 crore tak", "Priya Nair"));
    const inbox = (await api.get("/whatsapp/inbox")).body.messages;
    expect(inbox).toHaveLength(1);
    expect(inbox[0]).toMatchObject({ clientId: null, leadPhone: "+919867000000", leadName: "Priya Nair", review: "PENDING" });

    const created = await api.post(`/whatsapp/messages/${inbox[0].id}/create-client`, {});
    expect(created.status).toBe(201);
    expect(created.body).toMatchObject({ linkedExisting: false, client: { name: "Priya Nair", primaryPhone: "+919867000000", leadSource: "WHATSAPP" } });

    await deliver(text("919867000000", "Ready to move chahiye", "Priya Nair"));
    const history = (await api.get(`/whatsapp/clients/${created.body.client.id}/messages`)).body;
    expect(history.messages).toHaveLength(2);
    expect(history.messages.every((m: { clientId: string }) => m.clientId === created.body.client.id)).toBe(true);
    expect(history.serviceWindowOpen).toBe(true);
    expect(await prisma.client.count()).toBe(1);
  });

  it("never creates a duplicate: create-client on an existing number links instead", async () => {
    const { api, deliver, text } = await setup();
    const existing = (await api.post("/clients", { name: "Rahul Sharma", phone: "+91 98200 12345", leadSource: "REFERRAL" })).body.client;
    // Message arrives as a manual import (not auto-linked) naming the same number via a portal lead.
    const shared = await api.post("/whatsapp/import", {
      text: "New lead on Housing.com! Rahul S (+91 98200 12345) is interested in your listing '2 BHK Flat in Bandra for Rent' priced at ₹1.1 L.",
    });
    expect(shared.body.message.clientId).toBe(existing.id); // recognised by phone right away
    await deliver(text("919820012345", "hi"));
    const msgs = await prisma.whatsAppMessage.findMany();
    expect(msgs.every((m) => m.clientId === existing.id)).toBe(true);

    // Unlinked lead whose number belongs to a client (e.g. number added after the message arrived).
    const lateLead = await api.post("/whatsapp/import", { text: "Magicbricks enquiry | Name: Sneha | Contact: 9930011111 | Property: 1 BHK Malad" });
    await api.post(`/clients/${existing.id}/phones`, { phone: "9930011111" });
    const res = await api.post(`/whatsapp/messages/${lateLead.body.message.id}/create-client`, {});
    expect(res.status).toBe(200);
    expect(res.body).toMatchObject({ linkedExisting: true, client: { id: existing.id } });
    expect(await prisma.client.count()).toBe(1);
  });

  it("a portal lead forwarded by an agent is about the lead, not the agent", async () => {
    const { api, deliver, text } = await setup();
    await api.post("/team", { name: "Field Agent", email: "fa@example.com", password: "password123", phone: "9811122233" });
    await deliver(
      text(
        "919811122233",
        "Dear Customer, You have received a response on your property ID A12345678 (2 BHK Apartment for Rent in Andheri West, Mumbai, ₹65,000). Name: Amit Verma, Mobile: +91-9876543210, Email: amit.v@example.com. - 99acres",
        "Field Agent",
      ),
    );
    const [lead] = (await api.get("/whatsapp/inbox")).body.messages;
    expect(lead).toMatchObject({ portal: "ACRES_99", leadName: "Amit Verma", leadPhone: "+919876543210", clientId: null });
    expect(lead.extraction.draft.budgetMax).toBeUndefined();
    expect(lead.extraction.advertisedPrices[0].value).toBe(65000);

    const created = await api.post(`/whatsapp/messages/${lead.id}/create-client`, {});
    expect(created.body.client).toMatchObject({ name: "Amit Verma", primaryPhone: "+919876543210", leadSource: "ACRES_99", email: "amit.v@example.com" });
    // The agent's own number never became a client or a client's chat.
    expect(await prisma.clientPhone.count({ where: { e164: "+919811122233" } })).toBe(0);
    expect((await prisma.whatsAppContact.findFirstOrThrow({ where: { waId: "+919811122233" } })).clientId).toBeNull();

    // Saving the reviewed draft creates the inquiry with no budget (advertised price excluded).
    const applied = await api.post(`/whatsapp/messages/${lead.id}/apply`, {
      requirement: { transactionType: "RENT", category: "BHK_2", locations: ["Andheri West"] },
    });
    expect(applied.body.inquiry).toMatchObject({ source: "WHATSAPP", budgetMax: null, budgetMin: null });
  });

  it("links to an existing client (optionally adding the number) and refuses numbers owned by others", async () => {
    const { api, deliver, text } = await setup();
    const a = (await api.post("/clients", { name: "A", phone: "9820012345", leadSource: "WALK_IN" })).body.client;
    const b = (await api.post("/clients", { name: "B", phone: "9820055555", leadSource: "WALK_IN" })).body.client;
    await deliver(text("919930099999", "It's Rahul from my office number"));
    const [msg] = (await api.get("/whatsapp/inbox?filter=needs_client")).body.messages;
    const linked = await api.post(`/whatsapp/messages/${msg.id}/link-client`, { clientId: a.id, addNumber: true });
    expect(linked.status).toBe(200);
    expect(await prisma.clientPhone.count({ where: { clientId: a.id } })).toBe(2);

    await deliver(text("919820055555", "B here"));
    const bMsg = (await api.get(`/whatsapp/clients/${b.id}/messages`)).body.messages[0];
    const wrong = await api.post(`/whatsapp/messages/${bMsg.id}/link-client`, { clientId: a.id });
    expect(wrong.status).toBe(409);

    const inquiryOfB = (await api.post(`/clients/${b.id}/inquiries`, { transactionType: "RENT", category: "BHK_1" })).body.inquiry;
    expect((await api.patch(`/whatsapp/messages/${msg.id}`, { inquiryId: inquiryOfB.id })).status).toBe(400);
  });

  it("transcribes WhatsApp voice messages when speech-to-text is configured", async () => {
    setVoiceServices({
      transcriber: { name: "stt:fake", transcribe: async (bytes) => (bytes.toString() === "OGG-BYTES" ? "Thane mein 1 BHK kiraye pe, 25 hazaar tak" : "") },
      extractor: rulesExtractor,
    });
    const { api, deliver } = await setup();
    await deliver(metaPayload(PHONE_NUMBER_ID, [
      { from: "919867000000", id: "wamid.AUDIO", timestamp: "1790000000", type: "audio", audio: { id: "MEDIA_AUDIO", mime_type: "audio/ogg; codecs=opus" } },
    ]));
    const [msg] = (await api.get("/whatsapp/inbox")).body.messages;
    expect(msg).toMatchObject({ type: "audio", text: "Thane mein 1 BHK kiraye pe, 25 hazaar tak" });
    expect(msg.extraction.draft.budgetMax.value).toBe(25000);
    expect(graphCalls.some((c) => c.url.endsWith("/MEDIA_AUDIO") && c.auth === "Bearer EAAG-test-access-token-0123456789")).toBe(true);
  });

  it("keeps tenants apart", async () => {
    const { api, deliver, text } = await setup();
    await deliver(text("919867000000", "2 BHK"));
    const [msg] = (await api.get("/whatsapp/inbox")).body.messages;
    const other = await registerBroker("Other");
    expect((await other.api.get(`/whatsapp/messages/${msg.id}`)).status).toBe(404);
    expect((await other.api.get("/whatsapp/inbox")).body.messages).toHaveLength(0);
  });
});

describe("sending (24-hour customer-service window)", () => {
  it("sends within the window, refuses outside it, supports templates and tracks delivery status", async () => {
    const { api, deliver, text } = await setup();
    const client = (await api.post("/clients", { name: "Rahul", phone: "9820012345", leadSource: "WALK_IN" })).body.client;
    expect((await api.post("/whatsapp/send", { clientId: client.id, text: "Hi" })).body.error.code).toBe("OUTSIDE_SERVICE_WINDOW");

    await deliver(text("919820012345", "Hi, any new options?"));
    const sent = await api.post("/whatsapp/send", { clientId: client.id, text: "Yes! Sharing 3 flats in Andheri West" });
    expect(sent.status).toBe(201);
    const call = graphCalls.find((c) => c.url.endsWith(`/${PHONE_NUMBER_ID}/messages`))!;
    expect(call.url).toMatch(/^\/v\d+\.\d+\//);
    expect(JSON.parse(call.body)).toMatchObject({ messaging_product: "whatsapp", to: "919820012345", type: "text", text: { body: "Yes! Sharing 3 flats in Andheri West" } });

    // Delivery receipts move status forward only.
    const wamid = sent.body.message.externalId;
    const status = (s: string) => metaPayload(PHONE_NUMBER_ID, [], [], [{ id: wamid, status: s, timestamp: "1790000100", recipient_id: "919820012345" }]);
    await deliver(status("read"));
    await deliver(status("delivered"));
    expect((await prisma.whatsAppMessage.findFirstOrThrow({ where: { externalId: wamid } })).status).toBe("READ");

    const tpl = await api.post("/whatsapp/send-template", { clientId: client.id, template: "new_listing_alert", language: "en", params: ["Rahul", "2 BHK Andheri West"] });
    expect(tpl.status).toBe(201);
    expect(JSON.parse(graphCalls[graphCalls.length - 1].body).template).toMatchObject({ name: "new_listing_alert", language: { code: "en" } });

    const bad = (await api.post("/clients", { name: "X", phone: "+91 00000 00000", leadSource: "WALK_IN" }));
    expect(bad.status).toBe(400); // invalid numbers never reach WhatsApp
  });

  it("explains when the API isn't connected", async () => {
    const { api } = await registerBroker();
    const client = (await api.post("/clients", { name: "R", phone: "9820012345", leadSource: "WALK_IN" })).body.client;
    expect((await api.post("/whatsapp/send", { clientId: client.id, text: "Hi" })).body.error.code).toBe("WHATSAPP_NOT_CONNECTED");
  });
});

describe("manual alternatives (no Business API)", () => {
  it("imports a shared message once, extracts it and links it to the chosen client", async () => {
    const { api } = await registerBroker(); // no WhatsApp account connected
    const client = (await api.post("/clients", { name: "Rahul", phone: "9820012345", leadSource: "WALK_IN" })).body.client;
    const body = { text: "Andheri West mein 2 BHK chahiye, 60 se 70 hazaar tak", clientId: client.id, sentAt: "2026-09-26T05:00:00Z" };
    const first = await api.post("/whatsapp/import", body);
    expect(first.status).toBe(201);
    expect(first.body.message).toMatchObject({ channel: "MANUAL", clientId: client.id, review: "PENDING" });
    expect(first.body.message.extraction.draft.budgetMin.value).toBe(60000);
    const again = await api.post("/whatsapp/import", body);
    expect(again.body.duplicate).toBe(true);
    expect(await prisma.whatsAppMessage.count()).toBe(1);
  });

  it("imports an exported chat as history, idempotently", async () => {
    const { api } = await registerBroker();
    const client = (await api.post("/clients", { name: "Rahul", phone: "9820012345", leadSource: "WALK_IN" })).body.client;
    const exportText = [
      "26/09/2026, 10:15 am - Rahul Sharma: 2 BHK chahiye Andheri West",
      "26/09/2026, 10:17 am - Priya: Sure, budget?",
      "26/09/2026, 10:18 am - Rahul Sharma: 70 hazaar tak",
    ].join("\n");
    const wrongName = await api.post("/whatsapp/import-chat", { clientId: client.id, clientSenderName: "Rahul", exportText });
    expect(wrongName.status).toBe(400);
    expect(wrongName.body.error.details.senders).toEqual(["Rahul Sharma", "Priya"]);

    const ok = await api.post("/whatsapp/import-chat", { clientId: client.id, clientSenderName: "Rahul Sharma", exportText });
    expect(ok.body).toMatchObject({ imported: 3, skippedDuplicates: 0 });
    const again = await api.post("/whatsapp/import-chat", { clientId: client.id, clientSenderName: "Rahul Sharma", exportText });
    expect(again.body).toMatchObject({ imported: 0, skippedDuplicates: 3 });

    const history = (await api.get(`/whatsapp/clients/${client.id}/messages`)).body.messages;
    expect(history.map((m: { direction: string }) => m.direction)).toEqual(["INBOUND", "OUTBOUND", "INBOUND"]);
    // Extraction on demand for an imported line.
    const extracted = await api.post(`/whatsapp/messages/${history[2].id}/extract`, {});
    expect(extracted.body.message.extraction.draft.budgetMax.value).toBe(70000);
  });
});

describe("real Housing.com enquiries end to end", () => {
  it("points the agent to their own listing, creates the client once and links the second enquiry", async () => {
    const { api, deliver, text } = await setup();
    const mine = (await api.post("/properties", {
      title: "1 BHK, Mulund West (Housing.com)", transactionType: "RENT", category: "BHK_1", price: 27000, locality: "Mulund (W)",
    })).body.property;
    await api.post("/properties", { title: "Other 1 BHK", transactionType: "RENT", category: "BHK_1", price: 27000, locality: "Thane West" });

    await deliver(text("919867012345", REAL_HOUSING_1, "Sneha Kulkarni"));
    const [first] = (await api.get("/whatsapp/inbox")).body.messages;
    expect(first).toMatchObject({ portal: "HOUSING_COM", leadName: "Sneha Kulkarni", leadPhone: "+919867012345", clientId: null });
    const detail = (await api.get(`/whatsapp/messages/${first.id}`)).body;
    expect(detail.enquiredProperties.map((p: { id: string }) => p.id)).toEqual([mine.id]); // same type, area and price

    const created = await api.post(`/whatsapp/messages/${first.id}/create-client`, {});
    expect(created.body.client).toMatchObject({ name: "Sneha Kulkarni", leadSource: "HOUSING_COM" });

    await deliver(text("919867012345", REAL_HOUSING_2, "Sneha Kulkarni"));
    const history = (await api.get(`/whatsapp/clients/${created.body.client.id}/messages`)).body.messages;
    expect(history).toHaveLength(2);
    expect(history[1].portalLead.listingPrice.value).toBe(33000);
    expect(await prisma.client.count()).toBe(1);
  });
});

describe("real 99acres enquiry end to end", () => {
  it("names the lead from their sign-off and finds the listing by its 99acres ID or area", async () => {
    const { api, deliver, text } = await setup();
    const byArea = (await api.post("/properties", {
      title: "1 BHK Mulund", transactionType: "RENT", category: "BHK_1", price: 30000, locality: "Mulund West", building: "Veena Nagar Phase 2",
    })).body.property;
    await api.post("/properties", { title: "2 BHK Veena Nagar", transactionType: "RENT", category: "BHK_2", price: 45000, locality: "Mulund West", building: "Veena Nagar" });

    await deliver(text("919812300001", REAL_99ACRES_1, "K M"));
    const [msg] = (await api.get("/whatsapp/inbox")).body.messages;
    expect(msg).toMatchObject({ portal: "ACRES_99", leadName: "Karishma", leadPhone: "+919812300001" });
    let detail = (await api.get(`/whatsapp/messages/${msg.id}`)).body;
    expect(detail.enquiredProperties.map((p: { id: string }) => p.id)).toEqual([byArea.id]); // 1 BHK in Veena Nagar only

    // The agent noted the portal ID on their listing → that listing wins.
    const byRef = (await api.post("/properties", {
      title: "1 BHK Veena Nagar", transactionType: "BUY", category: "BHK_1", price: 9500000, locality: "Mulund West", notes: "99acres I94007278",
    })).body.property;
    detail = (await api.get(`/whatsapp/messages/${msg.id}`)).body;
    expect(detail.enquiredProperties.map((p: { id: string }) => p.id)).toEqual([byRef.id]);
  });
});
