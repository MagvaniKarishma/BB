import { mkdtemp, mkdir, readFile, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import request from "supertest";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { EncryptingStore, LocalDiskStore, setBlobStore } from "../src/storage/blobStore.js";
import { type OtpSender, setOtpSender } from "../src/sms/sender.js";
import { setVoiceServices } from "../src/voice/service.js";
import { app, resetDb } from "./helpers.js";

/**
 * API contract with the Android app.
 *
 * Sends the exact request bodies the app produces (android/core/src/test/resources/contract/
 * requests, written by the app's ContractRequestsTest) through every flow the screens use,
 * and requires each call to succeed. With WRITE_CONTRACT=1 it saves every response to
 * contract/responses, which the app's ContractResponsesTest then decodes with the app's
 * models — so both directions are checked against the real server.
 */
const CONTRACT = path.resolve(__dirname, "../../android/core/src/test/resources/contract");
const WRITE = process.env.WRITE_CONTRACT === "1";

class FakeSms implements OtpSender {
  readonly name = "fake";
  last = "";
  async sendOtp(_to: string, code: string) {
    this.last = code;
  }
}
const sms = new FakeSms();
const vars: Record<string, string> = {};
const saved: string[] = [];

async function body(name: string): Promise<object> {
  const raw = await readFile(path.join(CONTRACT, "requests", `${name}.json`), "utf8");
  const filled = raw.replace(/\{\{(\w+)\}\}/g, (_m, k: string) => {
    if (!(k in vars)) throw new Error(`contract request ${name} needs {{${k}}}`);
    return vars[k];
  });
  return JSON.parse(filled);
}

async function save(name: string, res: request.Response) {
  expect(res.status, `${name}: ${JSON.stringify(res.body).slice(0, 300)}`).toBeGreaterThanOrEqual(200);
  expect(res.status, `${name}: ${JSON.stringify(res.body).slice(0, 300)}`).toBeLessThan(300);
  saved.push(name);
  if (WRITE) await writeFile(path.join(CONTRACT, "responses", `${name}.json`), `${JSON.stringify(res.body, null, 2)}\n`);
  return res.body;
}

let token = "";
const h = () => ({ Authorization: `Bearer ${token}` });
const get = (url: string) => request(app).get(`/api/v1${url}`).set(h());
const send = (method: "post" | "put" | "patch", url: string, b: object) => request(app)[method](`/api/v1${url}`).set(h()).send(b);

function wav(ms: number) {
  const rate = 8000;
  const data = Buffer.alloc((rate * ms) / 1000 * 2);
  const hd = Buffer.alloc(44);
  hd.write("RIFF", 0); hd.writeUInt32LE(36 + data.length, 4); hd.write("WAVE", 8); hd.write("fmt ", 12);
  hd.writeUInt32LE(16, 16); hd.writeUInt16LE(1, 20); hd.writeUInt16LE(1, 22); hd.writeUInt32LE(rate, 24);
  hd.writeUInt32LE(rate * 2, 28); hd.writeUInt16LE(2, 32); hd.writeUInt16LE(16, 34); hd.write("data", 36); hd.writeUInt32LE(data.length, 40);
  return Buffer.concat([hd, data]);
}

beforeAll(async () => {
  await resetDb();
  if (WRITE) await mkdir(path.join(CONTRACT, "responses"), { recursive: true });
  setOtpSender(sms);
  setVoiceServices(null);
  setBlobStore(new EncryptingStore(new LocalDiskStore(await mkdtemp(path.join(tmpdir(), "bb-contract-")))));
  vars.email = `owner-${Date.now()}@example.com`;
  vars.memberEmail = `agent-${Date.now()}@example.com`;
  vars.email2 = `owner2-${Date.now()}@example.com`;
  vars.memberEmail2 = `agent2-${Date.now()}@example.com`;
  vars.dueAt = new Date(Date.now() + 3_600_000).toISOString();
});
afterAll(() => {
  setOtpSender(undefined);
  setBlobStore(undefined);
});

describe("API contract with the Android app", () => {
  it("accepts the app's requests and returns what its screens read", async () => {
    // --- sign-in ---
    await save("auth-register-no-phone", await request(app).post("/api/v1/auth/register").send(await body("auth-register-no-phone")));
    const reg = await save("auth-register", await request(app).post("/api/v1/auth/register").send(await body("auth-register")));
    token = reg.token;
    await save("auth-login", await request(app).post("/api/v1/auth/login").send(await body("auth-login")));
    await save("auth-otp", await request(app).get("/api/v1/auth/otp"));
    await save("auth-otp-request", await request(app).post("/api/v1/auth/otp/request").send(await body("auth-otp-request")));
    vars.otpCode = sms.last;
    await save("auth-otp-verify", await request(app).post("/api/v1/auth/otp/verify").send(await body("auth-otp-verify")));
    await save("auth-phone-request", await send("post", "/auth/phone/request", await body("auth-phone-request")));
    vars.otpCode = sms.last;
    await save("auth-phone-verify", await send("post", "/auth/phone/verify", await body("auth-phone-verify")));
    await save("auth-me", await get("/auth/me"));

    // --- team ---
    const member = await save("team-add", await send("post", "/team", await body("team-add")));
    await save("team-add-no-phone", await send("post", "/team", await body("team-add-no-phone")));
    await save("team-update", await send("patch", `/team/${member.member.id}`, await body("team-update")));
    await save("team-list", await get("/team"));

    // --- clients, requirements, properties ---
    await save("client-check-duplicate", await get("/clients/check-duplicate?phone=9876543210"));
    const client = await save("client-create", await send("post", "/clients", await body("client-create")));
    vars.clientId = client.client.id;
    await save("client-update", await send("patch", `/clients/${vars.clientId}`, await body("client-update")));
    const inquiry = await save("inquiry-create", await send("post", `/clients/${vars.clientId}/inquiries`, await body("inquiry-create")));
    vars.inquiryId = inquiry.inquiry.id;
    await save("inquiry-update", await send("patch", `/inquiries/${vars.inquiryId}`, await body("inquiry-update")));
    const property = await save("property-create", await send("post", "/properties", await body("property-create")));
    const propertyId = property.property.id;
    await save("property-update", await send("patch", `/properties/${propertyId}`, await body("property-update")));
    const jpeg = Buffer.concat([Buffer.from([0xff, 0xd8, 0xff, 0xe0]), Buffer.alloc(300, 7)]);
    await save("property-photo-upload", await request(app).post(`/api/v1/properties/${propertyId}/photos`).set(h()).attach("photo", jpeg, "photo.jpg"));
    await save("property-get", await get(`/properties/${propertyId}`));
    await save("properties-list", await get("/properties?transactionType=RENT&availability=AVAILABLE"));
    await save("property-matches", await get(`/properties/${propertyId}/matches`));
    await save("client-get", await get(`/clients/${vars.clientId}`));
    await save("clients-list", await get("/clients?group=all&tz=330"));
    await save("client-lookup", await get("/clients/lookup?phone=9876543210"));
    await save("inquiry-get", await get(`/inquiries/${vars.inquiryId}`));
    await save("inquiries-list", await get("/inquiries?transactionType=RENT"));
    await save("inquiry-history", await get(`/inquiries/${vars.inquiryId}/history`));
    await save("inquiry-matches", await get(`/inquiries/${vars.inquiryId}/matches`));

    // --- follow-ups, notes, caller screen ---
    const reminder = await save("reminder-create", await send("post", "/reminders", await body("reminder-create")));
    await save("reminders-list", await get("/reminders?status=PENDING"));
    await save("reminder-snooze", await send("patch", `/reminders/${reminder.reminder.id}`, await body("reminder-snooze")));
    await save("reminder-update", await send("patch", `/reminders/${reminder.reminder.id}`, await body("reminder-update")));
    await save("note-add", await send("post", `/clients/${vars.clientId}/notes`, await body("note-add")));
    await save("notes-list", await get(`/clients/${vars.clientId}/notes`));
    await save("caller-lookup-known", await get("/caller/lookup?phone=%2B919876543210"));
    await save("caller-lookup-unknown", await get("/caller/lookup?phone=9811100000"));
    await save("caller-directory", await get("/caller/directory"));
    await save("dashboard", await get("/dashboard?tz=330"));

    // --- voice notes ---
    const note = await save("voice-text", await send("post", "/voice-notes/text", await body("voice-text")));
    await save("voice-transcript", await send("put", `/voice-notes/${note.voiceNote.id}/transcript`, await body("voice-transcript")));
    await save("voice-get", await get(`/voice-notes/${note.voiceNote.id}`));
    await save("voice-apply", await send("post", `/voice-notes/${note.voiceNote.id}/apply`, await body("voice-apply")));
    const m4a = Buffer.concat([Buffer.from([0, 0, 0, 0x20]), Buffer.from("ftypM4A "), Buffer.alloc(400, 1)]);
    const upload = await save(
      "voice-upload",
      await request(app).post("/api/v1/voice-notes").set(h())
        .field("clientId", vars.clientId).field("language", "HINGLISH").field("durationMs", "4000")
        .attach("audio", m4a, { filename: "note.m4a", contentType: "audio/mp4" }),
    );
    await save("voice-discard", await send("post", `/voice-notes/${upload.voiceNote.id}/discard`, {}));
    await save("voice-list", await get(`/voice-notes?clientId=${vars.clientId}`));

    // --- WhatsApp (manual import path; sending needs Meta's live API and isn't exercised) ---
    await save("whatsapp-account", await get("/whatsapp/account"));
    await save("whatsapp-connect", await send("put", "/whatsapp/account", await body("whatsapp-connect")));
    const lead = await save("whatsapp-import", await send("post", "/whatsapp/import", await body("whatsapp-import")));
    await save("whatsapp-message", await get(`/whatsapp/messages/${lead.message.id}`));
    await save("whatsapp-inbox", await get("/whatsapp/inbox?filter=attention"));
    await save("whatsapp-create-client", await send("post", `/whatsapp/messages/${lead.message.id}/create-client`, await body("whatsapp-create-client")));
    await save("whatsapp-extract", await send("post", `/whatsapp/messages/${lead.message.id}/extract`, {}));
    await save("whatsapp-link-inquiry", await send("patch", `/whatsapp/messages/${lead.message.id}`, await body("whatsapp-link-inquiry")));
    await save("whatsapp-apply", await send("post", `/whatsapp/messages/${lead.message.id}/apply`, await body("whatsapp-apply")));
    const other = await send("post", "/whatsapp/import", { text: "Hi, 3 BHK chahiye Powai mein 1.5 cr tak" });
    await save("whatsapp-link-client", await send("post", `/whatsapp/messages/${other.body.message.id}/link-client`, await body("whatsapp-link-client")));
    await save("whatsapp-dismiss", await send("post", `/whatsapp/messages/${other.body.message.id}/dismiss`, {}));
    await save("whatsapp-import-chat", await send("post", "/whatsapp/import-chat", await body("whatsapp-import-chat")));
    await save("whatsapp-conversation", await get(`/whatsapp/clients/${vars.clientId}/messages`));

    // --- AI Call Assistant ---
    await save("call-assistant-settings", await send("put", "/call-assistant/settings", await body("call-assistant-settings")));
    await save("greeting-script", await send("put", "/call-assistant/greetings/HINGLISH", await body("greeting-script")));
    await save("greeting-audio-upload", await request(app).post("/api/v1/call-assistant/greetings/HINGLISH/audio").set(h()).attach("audio", wav(3000), "greeting.wav"));
    await save("call-assistant", await get("/call-assistant"));
    const call = await save("test-call-start", await send("post", "/call-assistant/test-calls", await body("test-call-start")));
    await save("test-call-turn", await send("post", `/call-assistant/test-calls/${call.callId}/turns`, await body("test-call-turn")));
    await save("ai-calls", await get("/call-assistant/calls"));

    // --- Today's Work: callbacks, portal leads, voice commands ---
    await save("reminder-callback", await send("post", "/reminders", await body("reminder-callback")));
    await save("reminders-callbacks", await get("/reminders?status=PENDING&kind=CALLBACK"));
    const ist = new Date(Date.now() + 330 * 60_000);
    const p2 = (n: number) => String(n).padStart(2, "0");
    vars.csvDate = `${p2(ist.getUTCDate())}/${p2(ist.getUTCMonth() + 1)}/${ist.getUTCFullYear()} ${p2(ist.getUTCHours())}:${p2(ist.getUTCMinutes())}`;
    await save("portal-import-csv", await send("post", "/portal-leads/import", await body("portal-import-csv")));
    await save("portal-import-text", await send("post", "/portal-leads/import-text", await body("portal-import-text")));
    const since = encodeURIComponent(new Date(Date.now() - 86_400_000).toISOString());
    const listings = await save("portal-listings", await get(`/portal-leads/listings?portal=ACRES_99&from=${since}`));
    const listing = await save("portal-listing", await get(`/portal-leads/listings/${listings.listings[0].id}?portal=ACRES_99&from=${since}`));
    await save("portal-listing-unidentified", await get("/portal-leads/listings/unidentified?portal=HOUSING_COM"));
    await save("portal-lead-status", await send("patch", `/portal-leads/${listing.leads[0].id}`, await body("portal-lead-status")));
    await save("portal-inbound-key", await send("post", "/portal-leads/integrations/HOUSING_COM/key", {}));
    await save("portal-integrations", await get("/portal-leads/integrations"));
    await save("client-with-portal-leads", await get(`/clients/${vars.clientId}`));
    await save("assistant-command", await send("post", "/assistant/command", await body("assistant-command")));

    // Every request file the app produced was used.
    const { readdir } = await import("node:fs/promises");
    const requests = (await readdir(path.join(CONTRACT, "requests"))).map((f) => f.replace(/\.json$/, ""));
    const used = new Set(saved);
    expect(requests.filter((r) => !used.has(r))).toEqual([]);
  });
});
