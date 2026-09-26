import { createHmac, timingSafeEqual } from "node:crypto";
import { SPEECH_LOCALE } from "../language.js";
import type { Instructions } from "../sessions.js";

/**
 * Adapter for Twilio Programmable Voice (TwiML + <Gather input="speech">).
 *
 * Status: written against Twilio's public documentation and covered by unit tests, but NOT
 * yet exercised with a live Twilio account or an Indian phone number. Before relying on
 * it, confirm with Twilio that your number type supports inbound calls in India and that
 * speech recognition works for hi-IN / mr-IN / en-IN on your account (see docs/AI_CALL_ASSISTANT.md).
 */
export const TWILIO = "twilio";

export const twilioConfigured = () => Boolean(process.env.TWILIO_AUTH_TOKEN);

/**
 * X-Twilio-Signature: base64(HMAC-SHA1(authToken, fullUrl + each POST param name+value,
 * sorted by name)). `fullUrl` must be the exact public URL Twilio called.
 */
export function validTwilioSignature(authToken: string, fullUrl: string, params: Record<string, string>, header: string | undefined): boolean {
  if (!header) return false;
  const data = Object.keys(params)
    .sort()
    .reduce((acc, k) => acc + k + params[k], fullUrl);
  const expected = Buffer.from(createHmac("sha1", authToken).update(data, "utf8").digest("base64"));
  const given = Buffer.from(header);
  return given.length === expected.length && timingSafeEqual(given, expected);
}

const xml = (s: string) =>
  s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;").replace(/'/g, "&apos;");

/** Renders provider-neutral instructions as TwiML. `base` is the public URL prefix of our webhooks. */
export function renderTwiml(ins: Instructions, base: string): string {
  const out: string[] = ['<?xml version="1.0" encoding="UTF-8"?>', "<Response>"];
  for (const step of ins.steps) {
    if (step.kind === "play") out.push(`<Play>${xml(step.url)}</Play>`);
    else out.push(`<Say language="${SPEECH_LOCALE[step.language]}">${xml(step.text)}</Say>`);
  }
  const n = ins.next;
  switch (n.kind) {
    case "listen":
      out.push(
        `<Gather input="speech" language="${SPEECH_LOCALE[n.language]}" speechTimeout="auto" timeout="6" ` +
          `actionOnEmptyResult="true" method="POST" action="${xml(`${base}/telephony/twilio/gather?turn=${n.turn}`)}"/>`,
      );
      break;
    case "dial":
      out.push(
        `<Dial timeout="${n.timeoutSec}" method="POST" action="${xml(`${base}/telephony/twilio/dial-result?turn=${n.turn}`)}">${xml(n.number)}</Dial>`,
      );
      break;
    case "transfer":
      out.push(`<Dial>${xml(n.number)}</Dial>`);
      break;
    case "hangup":
      out.push("<Hangup/>");
      break;
  }
  out.push("</Response>");
  return out.join("");
}

/** Twilio call statuses that mean the call is over. */
export const FINAL_STATUSES = new Set(["completed", "busy", "failed", "no-answer", "canceled"]);
