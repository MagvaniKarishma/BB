import { createHmac, randomInt, timingSafeEqual } from "node:crypto";
import type { OtpChallenge, OtpPurpose } from "@prisma/client";
import { config } from "../config.js";
import { prisma } from "../db.js";
import { HttpError } from "../lib/errors.js";
import { normalizePhone } from "../lib/phone.js";
import { invalidPhone } from "../services/clients.js";
import { getOtpSender } from "../sms/sender.js";

/**
 * SMS one-time codes for mobile-number sign-in and for verifying a user's number.
 *
 * The code is generated here and only its HMAC is stored. A code works once, for 5
 * minutes, with at most 5 tries; asking for a new code cancels the previous one. Sends
 * are rate-limited per number and per network address. For sign-in, unknown numbers get
 * the same answer as known ones (no SMS is sent), so the endpoint can't be used to find
 * out which numbers have accounts.
 */
export const OTP_RULES = {
  digits: 6,
  ttlMinutes: 5,
  maxAttempts: 5,
  resendAfterSeconds: 30,
  perPhonePerHour: 5,
  perIpPerHour: 20,
} as const;

const secret = () => process.env.OTP_SECRET ?? createHmac("sha256", config.jwtSecret).update("otp-codes").digest("hex");
const hashCode = (challengeId: string, code: string) => createHmac("sha256", secret()).update(`${challengeId}:${code}`).digest("hex");

const tooMany = (message: string, retryAfterSec: number) =>
  new HttpError(429, "TOO_MANY_REQUESTS", message, { retryAfterSec });
const invalidCode = (attemptsLeft?: number) =>
  new HttpError(400, "INVALID_CODE", "That code is wrong or has expired. Check the SMS or ask for a new code.", attemptsLeft != null ? { attemptsLeft } : undefined);

export function phoneOrThrow(raw: string): string {
  const e164 = normalizePhone(raw);
  if (!e164) throw invalidPhone(raw);
  return e164;
}

export interface OtpRequest {
  phone: string;
  purpose: OtpPurpose;
  /** VERIFY_PHONE: the signed-in user adding the number. */
  userId?: string;
  ip?: string | null;
}

export async function requestOtp(r: OtpRequest, now = new Date()): Promise<{ retryAfterSec: number; expiresInSec: number }> {
  const sender = getOtpSender();
  if (!sender) throw new HttpError(503, "OTP_NOT_CONFIGURED", "Sign-in by SMS isn't set up on this server. Use email and password.");
  const phone = phoneOrThrow(r.phone);
  const hourAgo = new Date(now.getTime() - 3_600_000);

  const last = await prisma.otpChallenge.findFirst({ where: { phone, purpose: r.purpose }, orderBy: { createdAt: "desc" } });
  if (last) {
    const wait = OTP_RULES.resendAfterSeconds - Math.floor((now.getTime() - last.createdAt.getTime()) / 1000);
    if (wait > 0) throw tooMany(`Please wait ${wait} seconds before asking for another code.`, wait);
  }
  const [perPhone, perIp] = await Promise.all([
    prisma.otpChallenge.count({ where: { phone, purpose: r.purpose, createdAt: { gte: hourAgo } } }),
    r.ip ? prisma.otpChallenge.count({ where: { ip: r.ip, createdAt: { gte: hourAgo } } }) : Promise.resolve(0),
  ]);
  if (perPhone >= OTP_RULES.perPhonePerHour) throw tooMany("Too many codes requested for this number. Try again in an hour.", 3600);
  if (perIp >= OTP_RULES.perIpPerHour) throw tooMany("Too many codes requested from this network. Try again later.", 3600);

  let deliver = true;
  if (r.purpose === "LOGIN") {
    const user = await prisma.user.findUnique({ where: { phone } });
    deliver = user != null && user.active;
  } else {
    const owner = await prisma.user.findUnique({ where: { phone } });
    if (owner && owner.id !== r.userId) throw new HttpError(409, "PHONE_TAKEN", "This number is already used by another BrokerBuddy account");
  }

  // Only the newest code is valid.
  await prisma.otpChallenge.updateMany({
    where: { phone, purpose: r.purpose, consumedAt: null, voidedAt: null },
    data: { voidedAt: now },
  });
  const code = String(randomInt(0, 10 ** OTP_RULES.digits)).padStart(OTP_RULES.digits, "0");
  const challenge = await prisma.otpChallenge.create({
    data: {
      phone, purpose: r.purpose, userId: r.userId ?? null, ip: r.ip ?? null, codeHash: "pending",
      expiresAt: new Date(now.getTime() + OTP_RULES.ttlMinutes * 60_000),
      // Unknown numbers: recorded (so rate limits apply the same) but never usable.
      voidedAt: deliver ? null : now,
    },
  });
  await prisma.otpChallenge.update({ where: { id: challenge.id }, data: { codeHash: hashCode(challenge.id, code) } });

  if (deliver) {
    try {
      await sender.sendOtp(phone, code, OTP_RULES.ttlMinutes);
    } catch (err) {
      await prisma.otpChallenge.update({ where: { id: challenge.id }, data: { voidedAt: new Date() } });
      console.error("OTP SMS failed:", err instanceof Error ? err.message : err);
      throw new HttpError(502, "SMS_FAILED", "Couldn't send the SMS right now. Please try again in a minute.");
    }
  }
  return { retryAfterSec: OTP_RULES.resendAfterSeconds, expiresInSec: OTP_RULES.ttlMinutes * 60 };
}

/** Checks a code; on success the challenge is used up and returned. */
export async function verifyOtp(
  r: { phone: string; code: string; purpose: OtpPurpose; userId?: string },
  now = new Date(),
): Promise<OtpChallenge> {
  const phone = phoneOrThrow(r.phone);
  const code = r.code.replace(/\s/g, "");
  if (!new RegExp(`^\\d{${OTP_RULES.digits}}$`).test(code)) throw invalidCode();

  const challenge = await prisma.otpChallenge.findFirst({
    where: {
      phone, purpose: r.purpose, consumedAt: null, voidedAt: null, expiresAt: { gt: now },
      ...(r.userId ? { userId: r.userId } : {}),
    },
    orderBy: { createdAt: "desc" },
  });
  if (!challenge) throw invalidCode();

  // Count the attempt atomically, so parallel guesses can't exceed the limit.
  const counted = await prisma.otpChallenge.updateMany({
    where: { id: challenge.id, attempts: { lt: OTP_RULES.maxAttempts }, consumedAt: null, voidedAt: null },
    data: { attempts: { increment: 1 } },
  });
  if (counted.count === 0) throw invalidCode(0);
  const attempts = challenge.attempts + 1;

  const expected = Buffer.from(challenge.codeHash, "hex");
  const given = Buffer.from(hashCode(challenge.id, code), "hex");
  if (expected.length !== given.length || !timingSafeEqual(expected, given)) {
    const left = OTP_RULES.maxAttempts - attempts;
    if (left <= 0) await prisma.otpChallenge.update({ where: { id: challenge.id }, data: { voidedAt: now } });
    throw invalidCode(Math.max(0, left));
  }
  const used = await prisma.otpChallenge.updateMany({ where: { id: challenge.id, consumedAt: null }, data: { consumedAt: now } });
  if (used.count === 0) throw invalidCode(); // the same code submitted twice at once
  return challenge;
}
