import request from "supertest";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { prisma } from "../src/db.js";
import { Msg91Sender, type OtpSender, setOtpSender } from "../src/sms/sender.js";
import { app, authed, resetDb } from "./helpers.js";

/** Captures codes instead of sending SMS. */
class FakeSender implements OtpSender {
  readonly name = "fake";
  sent: { to: string; code: string }[] = [];
  fail = false;
  async sendOtp(to: string, code: string) {
    if (this.fail) throw new Error("provider down");
    this.sent.push({ to, code });
  }
  last() {
    return this.sent[this.sent.length - 1];
  }
}

let sms: FakeSender;
let ipCounter = 0;
beforeEach(async () => {
  await resetDb();
  sms = new FakeSender();
  setOtpSender(sms);
});
afterEach(() => setOtpSender(undefined));

const PHONE = "+919820012345";
// Each test gets its own "network address" so the per-IP limit doesn't leak between tests.
const post = (url: string, body: object, ip = `10.0.0.${++ipCounter % 250}`) =>
  request(app).post(`/api/v1${url}`).set("X-Forwarded-For", ip).send(body);

async function owner(phone: string | undefined = "98200 12345") {
  const res = await request(app).post("/api/v1/auth/register").send({
    brokerageName: "Sharma Realty", name: "Rahul Sharma", email: `r${Date.now()}${Math.random()}@example.com`, password: "password123", phone,
  });
  expect(res.status).toBe(201);
  return res.body as { token: string; user: { id: string; phone: string | null } };
}

describe("sign in with an SMS code", () => {
  it("sends a 6-digit code to the account's number and signs in with it once", async () => {
    const { user } = await owner();
    expect((await request(app).get("/api/v1/auth/otp")).body).toMatchObject({ enabled: true, digits: 6 });

    const req1 = await post("/auth/otp/request", { phone: "098200 12345" });
    expect(req1.status).toBe(200);
    expect(req1.body).toMatchObject({ retryAfterSec: 30, expiresInSec: 300 });
    expect(sms.sent).toHaveLength(1);
    expect(sms.last().to).toBe(PHONE);
    expect(sms.last().code).toMatch(/^\d{6}$/);

    const ok = await post("/auth/otp/verify", { phone: PHONE, code: sms.last().code });
    expect(ok.status).toBe(200);
    expect(ok.body.user.id).toBe(user.id);
    expect((await authed(ok.body.token).get("/auth/me")).status).toBe(200);

    // A code works once.
    expect((await post("/auth/otp/verify", { phone: PHONE, code: sms.last().code })).status).toBe(400);
    // Only a hash is stored.
    const row = await prisma.otpChallenge.findFirstOrThrow();
    expect(row.codeHash).not.toContain(sms.last().code);
  });

  it("answers unknown numbers the same way without sending anything", async () => {
    await owner();
    const res = await post("/auth/otp/request", { phone: "9811122233" });
    expect(res.status).toBe(200);
    expect(res.body.message).toMatch(/If this number belongs to a BrokerBuddy account/);
    expect(sms.sent).toHaveLength(0);
    expect((await post("/auth/otp/verify", { phone: "9811122233", code: "123456" })).status).toBe(400);
  });

  it("limits wrong guesses, and a new code cancels the old one", async () => {
    await owner();
    await post("/auth/otp/request", { phone: PHONE });
    const code = sms.last().code;
    const wrong = code === "000000" ? "111111" : "000000";
    for (let i = 4; i >= 1; i--) {
      const r = await post("/auth/otp/verify", { phone: PHONE, code: wrong });
      expect(r.status).toBe(400);
      expect(r.body.error.details.attemptsLeft).toBe(i);
    }
    expect((await post("/auth/otp/verify", { phone: PHONE, code: wrong })).body.error.details.attemptsLeft).toBe(0);
    expect((await post("/auth/otp/verify", { phone: PHONE, code })).status).toBe(400); // locked, even with the right code

    await prisma.otpChallenge.updateMany({ data: { createdAt: new Date(Date.now() - 60_000) } }); // past the resend wait
    await post("/auth/otp/request", { phone: PHONE });
    const newer = sms.last().code;
    await prisma.otpChallenge.updateMany({ data: { createdAt: new Date(Date.now() - 60_000) } });
    await post("/auth/otp/request", { phone: PHONE });
    if (newer !== sms.last().code) expect((await post("/auth/otp/verify", { phone: PHONE, code: newer })).status).toBe(400);
    expect((await post("/auth/otp/verify", { phone: PHONE, code: sms.last().code })).status).toBe(200);
  });

  it("rejects expired codes and rate-limits sends", async () => {
    await owner();
    await post("/auth/otp/request", { phone: PHONE });
    const tooSoon = await post("/auth/otp/request", { phone: PHONE });
    expect(tooSoon.status).toBe(429);
    expect(tooSoon.body.error.details.retryAfterSec).toBeGreaterThan(0);

    await prisma.otpChallenge.updateMany({ data: { expiresAt: new Date(Date.now() - 1000) } });
    expect((await post("/auth/otp/verify", { phone: PHONE, code: sms.last().code })).status).toBe(400);

    // At most 5 codes an hour per number.
    await prisma.otpChallenge.deleteMany();
    for (let i = 0; i < 5; i++) {
      await prisma.otpChallenge.updateMany({ data: { createdAt: new Date(Date.now() - 40_000 * (i + 1)) } });
      expect((await post("/auth/otp/request", { phone: PHONE })).status).toBe(200);
    }
    await prisma.otpChallenge.updateMany({ data: { createdAt: new Date(Date.now() - 45 * 60_000) } });
    expect((await post("/auth/otp/request", { phone: PHONE })).status).toBe(429);
  });

  it("reports a failed SMS and a server without SMS set up", async () => {
    await owner();
    sms.fail = true;
    const failed = await post("/auth/otp/request", { phone: PHONE });
    expect(failed.status).toBe(502);
    expect(failed.body.error.code).toBe("SMS_FAILED");

    setOtpSender(null);
    expect((await request(app).get("/api/v1/auth/otp")).body.enabled).toBe(false);
    expect((await post("/auth/otp/request", { phone: PHONE })).status).toBe(503);
  });
});

describe("adding your own mobile number", () => {
  it("proves the number by SMS before saving it, and keeps numbers unique", async () => {
    const a = await owner(undefined);
    const api = authed(a.token);
    expect((await api.post("/auth/phone/request", { phone: "9811122233" })).status).toBe(200);
    expect(sms.last().to).toBe("+919811122233");
    const done = await api.post("/auth/phone/verify", { phone: "9811122233", code: sms.last().code });
    expect(done.body.user.phone).toBe("+919811122233");

    // Now the number signs in.
    await prisma.otpChallenge.deleteMany();
    await post("/auth/otp/request", { phone: "9811122233" });
    expect((await post("/auth/otp/verify", { phone: "9811122233", code: sms.last().code })).body.user.id).toBe(a.user.id);

    // Someone else can't claim it, at registration or later.
    const reg = await request(app).post("/api/v1/auth/register").send({
      brokerageName: "X", name: "Y", email: `y${Date.now()}@example.com`, password: "password123", phone: "9811122233",
    });
    expect(reg.status).toBe(409);
    const b = await owner(undefined);
    expect((await authed(b.token).post("/auth/phone/request", { phone: "9811122233" })).status).toBe(409);
  });
});

describe("MSG91 sender", () => {
  it("calls the v5 OTP API with our code and the DLT template", async () => {
    const calls: { url: string; init: RequestInit }[] = [];
    const fake = (async (url: URL | string, init?: RequestInit) => {
      calls.push({ url: String(url), init: init! });
      return new Response(JSON.stringify({ type: "success", request_id: "abc" }), { status: 200 });
    }) as typeof fetch;
    await new Msg91Sender("AUTH-KEY", "TEMPLATE-1", fake).sendOtp(PHONE, "482913", 5);
    const u = new URL(calls[0].url);
    expect(u.origin + u.pathname).toBe("https://control.msg91.com/api/v5/otp");
    expect(Object.fromEntries(u.searchParams)).toEqual({ template_id: "TEMPLATE-1", mobile: "919820012345", otp: "482913", otp_expiry: "5" });
    expect(calls[0].init.method).toBe("POST");
    expect((calls[0].init.headers as Record<string, string>).authkey).toBe("AUTH-KEY");
  });

  it("treats MSG91 errors as failures without leaking the code", async () => {
    const fake = (async () => new Response(JSON.stringify({ type: "error", message: "Invalid authkey" }), { status: 401 })) as typeof fetch;
    await expect(new Msg91Sender("bad", "T", fake).sendOtp(PHONE, "482913", 5)).rejects.toThrow(/HTTP 401: Invalid authkey/);
    await expect(new Msg91Sender("bad", "T", fake).sendOtp(PHONE, "482913", 5)).rejects.not.toThrow(/482913/);
  });
});
