import { createHmac } from "node:crypto";
import { mkdtemp, readdir } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import request from "supertest";
import { afterAll, beforeAll, beforeEach, describe, expect, it } from "vitest";
import { prisma } from "../src/db.js";
import { sweepCalls } from "../src/callAssistant/sessions.js";
import { signedGreetingUrl } from "../src/callAssistant/signing.js";
import { EncryptingStore, LocalDiskStore, setBlobStore } from "../src/storage/blobStore.js";
import { setVoiceServices } from "../src/voice/service.js";
import { app, authed, registerBroker, resetDb } from "./helpers.js";

const BASE = "https://bb.example.com";
const TOKEN = "twilio-test-auth-token";
const BUSINESS = "+912240001234";
const CALLER = "+919820012345";

/** 16-bit mono PCM WAV of the given length. */
function wav(ms: number, rate = 8000): Buffer {
  const data = Buffer.alloc(Math.round((rate * ms) / 1000) * 2);
  const h = Buffer.alloc(44);
  h.write("RIFF", 0); h.writeUInt32LE(36 + data.length, 4); h.write("WAVE", 8);
  h.write("fmt ", 12); h.writeUInt32LE(16, 16); h.writeUInt16LE(1, 20); h.writeUInt16LE(1, 22);
  h.writeUInt32LE(rate, 24); h.writeUInt32LE(rate * 2, 28); h.writeUInt16LE(2, 32); h.writeUInt16LE(16, 34);
  h.write("data", 36); h.writeUInt32LE(data.length, 40);
  return Buffer.concat([h, data]);
}
const MP3 = Buffer.concat([Buffer.from("ID3"), Buffer.alloc(500, 1)]);
const M4A = Buffer.concat([Buffer.from([0, 0, 0, 0x20]), Buffer.from("ftypM4A "), Buffer.alloc(200)]);

/** A request signed the way Twilio signs it. */
function twilio(pathAndQuery: string, params: Record<string, string>, token = TOKEN) {
  const data = Object.keys(params).sort().reduce((acc, k) => acc + k + params[k], BASE + pathAndQuery);
  const sig = createHmac("sha1", token).update(data).digest("base64");
  return request(app).post(pathAndQuery).set("X-Twilio-Signature", sig).type("form").send(params);
}

let dir: string;
beforeAll(() => {
  process.env.TWILIO_AUTH_TOKEN = TOKEN;
  process.env.PUBLIC_BASE_URL = BASE;
  setVoiceServices(null); // rules extractor (no API key in tests)
});
afterAll(() => {
  delete process.env.TWILIO_AUTH_TOKEN;
  delete process.env.PUBLIC_BASE_URL;
});
beforeEach(async () => {
  await resetDb();
  dir = await mkdtemp(path.join(tmpdir(), "bb-greet-"));
  setBlobStore(new EncryptingStore(new LocalDiskStore(dir)));
});

async function setup(settings: object = {}) {
  const b = await registerBroker();
  const put = await b.api.put("/call-assistant/settings", {
    enabled: true, mode: "AI_RECEPTIONIST", customGreetingEnabled: true, businessNumbers: ["022 4000 1234"], ...settings,
  });
  expect(put.status).toBe(200);
  const upload = (buf: Buffer, lang = "hinglish", token = b.token) =>
    request(app).post(`/api/v1/call-assistant/greetings/${lang}/audio`).set("Authorization", `Bearer ${token}`).attach("audio", buf, "greeting.bin");
  return { ...b, upload };
}

describe("greeting management", () => {
  it("starts with the editable default scripts and the assistant switched off", async () => {
    const { api } = await registerBroker();
    const res = (await api.get("/call-assistant")).body;
    expect(res.settings).toMatchObject({ enabled: false, mode: "DIRECT", defaultLanguage: "HINGLISH", voice: "RECORDED_STANDARD" });
    expect(res.greetings.map((g: { language: string }) => g.language)).toEqual(["HINGLISH", "HINDI", "ENGLISH", "MARATHI"]);
    expect(res.greetings[0].script).toMatch(/^Hello! Main BrokerBuddy ki AI assistant hoon\./);
    expect(res.capabilities.voices.CUSTOM_AI_VOICE.available).toBe(false);
    // Unavailable voice options are refused rather than silently accepted.
    expect((await api.put("/call-assistant/settings", { voice: "CUSTOM_AI_VOICE" })).status).toBe(400);
  });

  it("records, previews, replaces and deletes a greeting; files are encrypted and cleaned up", async () => {
    const { api, upload } = await setup();
    const first = await upload(wav(4000));
    expect(first.status).toBe(201);
    expect(first.body.greeting).toMatchObject({ language: "HINGLISH", hasAudio: true, mimeType: "audio/wav", durationMs: 4000 });

    const preview = await api.get("/call-assistant/greetings/HINGLISH/audio").buffer(true);
    expect(preview.status).toBe(200);
    expect(Buffer.compare(preview.body, wav(4000))).toBe(0);

    await upload(MP3); // replace
    const files = await readdir(dir, { recursive: true, withFileTypes: true });
    expect(files.filter((f) => f.isFile())).toHaveLength(1); // the old recording is gone

    expect((await api.delete("/call-assistant/greetings/HINGLISH/audio")).status).toBe(204);
    expect((await readdir(dir, { recursive: true, withFileTypes: true })).filter((f) => f.isFile())).toHaveLength(0);
    expect((await api.get("/call-assistant")).body.greetings[0].hasAudio).toBe(false);
  });

  it("checks format and length, and lets owners edit the script per language", async () => {
    const { api, upload } = await setup();
    expect((await upload(M4A)).status).toBe(415);
    expect((await upload(wav(61_000))).status).toBe(400);
    expect((await upload(wav(300))).status).toBe(400);
    const edit = await api.put("/call-assistant/greetings/marathi", { script: "नमस्कार! शर्मा रियल्टीमध्ये आपले स्वागत आहे." });
    expect(edit.body.greeting).toMatchObject({ language: "MARATHI", isDefaultScript: false });
    expect(edit.body.warnings[0]).toMatch(/doesn't say it's an AI/);
  });

  it("only owners and admins can change settings or greetings", async () => {
    const { api, upload } = await setup();
    const agent = await api.post("/team", { name: "Agent", email: `a${Date.now()}@example.com`, password: "password123", role: "AGENT" });
    const login = await request(app).post("/api/v1/auth/login").send({ email: agent.body.member.email, password: "password123" });
    const asAgent = authed(login.body.token);
    expect((await asAgent.get("/call-assistant")).status).toBe(200);
    expect((await asAgent.put("/call-assistant/settings", { enabled: false })).status).toBe(403);
    expect((await asAgent.put("/call-assistant/greetings/hindi", { script: "नमस्ते, मैं AI असिस्टेंट हूँ।" })).status).toBe(403);
    expect((await upload(wav(3000), "hinglish", login.body.token)).status).toBe(403);
  });

  it("serves greeting audio to the phone provider only through a valid, unexpired signed link", async () => {
    const { upload } = await setup();
    await upload(wav(3000));
    const g = await prisma.callGreeting.findFirstOrThrow();
    const good = new URL(signedGreetingUrl(g.id));
    expect((await request(app).get(good.pathname + good.search)).status).toBe(200);
    expect((await request(app).get(good.pathname + good.search.replace(/sig=[^&]+/, "sig=tampered"))).status).toBe(403);
    const expired = new URL(signedGreetingUrl(g.id, 60, Date.now() - 3_600_000));
    expect((await request(app).get(expired.pathname + expired.search)).status).toBe(403);
  });
});

describe("AI-answered call (Twilio format)", () => {
  const call = (sid: string) => ({
    start: () => twilio("/telephony/twilio/voice", { CallSid: sid, From: CALLER, To: BUSINESS }),
    say: (turn: number, text: string) => twilio(`/telephony/twilio/gather?turn=${turn}`, { CallSid: sid, SpeechResult: text }),
    end: (duration = "95") => twilio("/telephony/twilio/status", { CallSid: sid, CallStatus: "completed", CallDuration: duration }),
  });

  it("plays the broker's greeting, discloses the AI, collects the requirement and saves one lead", async () => {
    const { upload, api } = await setup();
    await upload(wav(3000));
    const c = call("CA100");

    const open = await c.start();
    expect(open.type).toBe("text/xml");
    expect(open.text).toMatch(/<Play>https:\/\/bb\.example\.com\/telephony\/greetings\/\w+\?exp=\d+&amp;sig=[\w-]+<\/Play>/);
    expect(open.text).toContain('<Say language="hi-IN">Bata doon, main ek AI assistant hoon');
    expect(open.text).toContain('<Gather input="speech" language="hi-IN"');
    expect(open.text).toContain("action=\"https://bb.example.com/telephony/twilio/gather?turn=1\"");

    const t1 = await c.say(1, "Mujhe Powai mein 2 BHK rent pe chahiye");
    expect(t1.text).toContain("Mahine ka rent budget kitna hai?");
    // Twilio retries the same turn → same answer, applied once.
    const again = await c.say(1, "Mujhe Powai mein 2 BHK rent pe chahiye");
    expect(again.text).toBe(t1.text);
    await c.say(2, "60 hazaar tak");
    await c.say(3, "Mera naam Sneha Kulkarni hai");
    await c.say(4, "bas");
    const bye = await c.say(5, "haan");
    expect(bye.text).toContain("<Hangup/>");
    expect((await c.end()).status).toBe(204);
    expect((await c.end()).status).toBe(204); // duplicate status callback

    const clients = (await api.get("/clients")).body.clients;
    expect(clients).toHaveLength(1);
    expect(clients[0]).toMatchObject({ name: "Sneha Kulkarni", primaryPhone: CALLER, leadSource: "AI_CALL_ASSISTANT", status: "NEW" });
    const inquiries = await prisma.inquiry.findMany({ include: { revisions: true } });
    expect(inquiries).toHaveLength(1);
    expect(inquiries[0]).toMatchObject({ transactionType: "RENT", category: "BHK_2", locations: ["Powai"], budgetMax: 60000n, source: "AI_CALL" });
    expect(inquiries[0].revisions[0].source).toBe("AI_CALL");
    const note = await prisma.clientNote.findFirstOrThrow();
    expect(note.source).toBe("AI_CALL");
    expect(note.body).toMatch(/^AI call with Sneha Kulkarni: Rent · 2 BHK · Powai · up to ₹60,000\./);
    expect(note.body).toContain("Caller: Mujhe Powai mein 2 BHK rent pe chahiye");
    const reminder = await prisma.reminder.findFirstOrThrow();
    expect(reminder.title).toBe("Call back Sneha Kulkarni (AI call)");
    const log = (await api.get("/call-assistant/calls")).body.calls;
    expect(log[0]).toMatchObject({ status: "COMPLETED", callbackRequested: true, durationSec: 95, clientId: clients[0].id });
    expect(await prisma.callTurn.count({ where: { role: "CALLER" } })).toBe(5); // the retried turn isn't stored twice
  });

  it("links a known caller to their existing profile without overwriting it", async () => {
    const { api } = await setup();
    const existing = (await api.post("/clients", { name: "Amit Patil", phone: "98200 12345", leadSource: "REFERRAL" })).body.client;
    const c = call("CA200");
    await c.start();
    await c.say(1, "mera naam Amit hai, 3 BHK khareedna hai Thane mein");
    await c.say(2, "1.5 crore");
    await c.end();
    const clients = (await api.get("/clients")).body.clients;
    expect(clients).toHaveLength(1);
    expect(clients[0]).toMatchObject({ id: existing.id, name: "Amit Patil", leadSource: "REFERRAL" });
    expect(await prisma.inquiry.count({ where: { clientId: existing.id, source: "AI_CALL" } })).toBe(1);
  });

  it("saves what it has when the caller hangs up mid-call", async () => {
    const { api } = await setup();
    const c = call("CA300");
    await c.start();
    await c.say(1, "1 BHK rent chahiye");
    await c.end("20");
    const s = await prisma.callSession.findFirstOrThrow({ where: { providerCallId: "CA300" } });
    expect(s.status).toBe("INTERRUPTED");
    expect((await api.get("/clients")).body.clients[0].name).toBe("Caller 98200 12345"); // no name given → labelled, not invented
    // What they did say (rent, 1 BHK) is saved; nothing else is filled in.
    const inquiry = await prisma.inquiry.findFirstOrThrow();
    expect(inquiry).toMatchObject({ transactionType: "RENT", category: "BHK_1", locations: [], budgetMax: null });
    // The call dropped: the broker gets a follow-up to call them back.
    expect((await prisma.reminder.findFirstOrThrow()).title).toBe("Call back +919820012345 (AI call ended early)");
  });

  it("recovers a call whose 'ended' webhook never arrived", async () => {
    await setup();
    const c = call("CA400");
    await c.start();
    await c.say(1, "2 BHK rent Andheri");
    await sweepCalls(new Date(Date.now() + 31 * 60_000));
    const s = await prisma.callSession.findFirstOrThrow({ where: { providerCallId: "CA400" } });
    expect(s.status).toBe("INTERRUPTED");
    expect(s.savedAt).not.toBeNull();
    expect(await prisma.client.count()).toBe(1);
    await sweepCalls(new Date(Date.now() + 62 * 60_000)); // idempotent
    expect(await prisma.clientNote.count()).toBe(1);
  });

  it("rejects forged webhooks and respects Direct and Smart modes", async () => {
    await setup({ mode: "DIRECT", transferNumber: "98111 22233" });
    expect((await twilio("/telephony/twilio/voice", { CallSid: "X", From: CALLER, To: BUSINESS }, "wrong-token")).status).toBe(403);
    const direct = await twilio("/telephony/twilio/voice", { CallSid: "CA500", From: CALLER, To: BUSINESS });
    expect(direct.text).toBe('<?xml version="1.0" encoding="UTF-8"?><Response><Dial>+919811122233</Dial></Response>');
    expect(await prisma.callSession.count()).toBe(0); // no AI, nothing stored

    await prisma.callAssistantSettings.updateMany({
      data: { mode: "SMART_ASSISTANT", businessHours: { days: [1, 2, 3, 4, 5, 6, 7], start: "00:00", end: "23:59", timeZone: "Asia/Kolkata" } },
    });
    const smart = await twilio("/telephony/twilio/voice", { CallSid: "CA600", From: CALLER, To: BUSINESS });
    expect(smart.text).toContain('<Dial timeout="20"');
    const missed = await twilio("/telephony/twilio/dial-result?turn=0", { CallSid: "CA600", DialCallStatus: "no-answer" });
    expect(missed.text).toContain("AI assistant");
    expect(missed.text).toContain("<Gather");
  });
});

describe("test your assistant", () => {
  it("runs a typed conversation without touching the CRM", async () => {
    const { api } = await setup();
    const start = await api.post("/call-assistant/test-calls", { callerNumber: "98200 12345" });
    expect(start.status).toBe(201);
    expect(start.body.lines.map((l: { text?: string }) => l.text)).toEqual([
      expect.stringMatching(/^Hello! Main BrokerBuddy ki AI assistant hoon/), // no recording → script read out
      "Boliye, aapko kaisi property chahiye?",
    ]);
    const t = await api.post(`/call-assistant/test-calls/${start.body.callId}/turns`, { text: "2 BHK rent pe Bandra mein" });
    expect(t.body.lines[0].text).toBe("Mahine ka rent budget kitna hai?");
    expect(t.body.collected.draft.category.value).toBe("BHK_2");
    expect(await prisma.client.count()).toBe(0);
    expect((await api.get("/call-assistant/calls")).body.calls).toHaveLength(0);
  });
});
