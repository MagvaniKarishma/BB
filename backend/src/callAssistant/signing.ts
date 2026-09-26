import { createHmac, timingSafeEqual } from "node:crypto";
import { config } from "../config.js";

/**
 * Short-lived signed links for greeting audio. The telephony provider fetches the
 * recording while the call is ringing and can't send our login token, so the link
 * itself carries an expiry and an HMAC signature.
 */
const secret = () =>
  process.env.TELEPHONY_URL_SECRET ?? createHmac("sha256", config.jwtSecret).update("greeting-audio-links").digest("hex");

const sign = (id: string, exp: number) => createHmac("sha256", secret()).update(`${id}.${exp}`).digest("base64url");

export function publicBaseUrl(): string | null {
  const url = process.env.PUBLIC_BASE_URL?.replace(/\/+$/, "");
  return url || null;
}

export function signedGreetingUrl(greetingId: string, ttlSec = 3600, now = Date.now()): string {
  const exp = Math.floor(now / 1000) + ttlSec;
  return `${publicBaseUrl() ?? ""}/telephony/greetings/${greetingId}?exp=${exp}&sig=${sign(greetingId, exp)}`;
}

export function verifyGreetingSignature(id: string, exp: string, sig: string, now = Date.now()): boolean {
  const e = Number(exp);
  if (!Number.isInteger(e) || e < Math.floor(now / 1000)) return false;
  const expected = Buffer.from(sign(id, e));
  const given = Buffer.from(sig);
  return given.length === expected.length && timingSafeEqual(given, expected);
}
