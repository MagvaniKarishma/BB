import express, { Router, type Request, type Response } from "express";
import { prisma } from "../db.js";
import { handleCallEvent } from "../callAssistant/sessions.js";
import { FINAL_STATUSES, TWILIO, renderTwiml, twilioConfigured, validTwilioSignature } from "../callAssistant/providers/twilio.js";
import { publicBaseUrl, verifyGreetingSignature } from "../callAssistant/signing.js";
import { getBlobStore } from "../storage/blobStore.js";

/**
 * Public endpoints called by the telephony provider (no login token; each request is
 * verified by the provider's signature, and greeting links by our own signature).
 */
export const telephonyRouter = Router();
telephonyRouter.use(express.urlencoded({ extended: false, limit: "100kb" }));

// ---------- greeting audio for the provider to play ----------

telephonyRouter.get("/greetings/:id", async (req, res) => {
  const id = String(req.params.id);
  if (!verifyGreetingSignature(id, String(req.query.exp ?? ""), String(req.query.sig ?? ""))) {
    res.status(403).end();
    return;
  }
  const g = await prisma.callGreeting.findUnique({ where: { id } });
  const bytes = g?.storageKey ? await getBlobStore().get(g.storageKey) : null;
  if (!g || !bytes) {
    res.status(404).end();
    return;
  }
  res.setHeader("Content-Type", g.mimeType ?? "application/octet-stream");
  res.setHeader("Cache-Control", "private, max-age=300");
  res.send(bytes);
});

// ---------- Twilio ----------

function twilioGuard(req: Request, res: Response): Record<string, string> | null {
  const token = process.env.TWILIO_AUTH_TOKEN;
  const base = publicBaseUrl();
  if (!twilioConfigured() || !token || !base) {
    res.status(503).type("text/plain").send("Twilio is not configured on this server");
    return null;
  }
  const params = Object.fromEntries(Object.entries(req.body ?? {}).map(([k, v]) => [k, String(v)]));
  if (!validTwilioSignature(token, base + req.originalUrl, params, req.header("X-Twilio-Signature"))) {
    res.status(403).type("text/plain").send("Invalid signature");
    return null;
  }
  return params;
}

async function reply(res: Response, run: () => ReturnType<typeof handleCallEvent>) {
  try {
    const ins = await run();
    res.type("text/xml").send(renderTwiml(ins, publicBaseUrl()!));
  } catch (err) {
    // Never leave the caller in silence: apologise and end; the sweeper saves what we have.
    console.error("AI call handling failed", err);
    res.type("text/xml").send(
      renderTwiml({ steps: [{ kind: "say", text: "Sorry, something went wrong. We will call you back.", language: "ENGLISH" }], next: { kind: "hangup" } }, publicBaseUrl()!),
    );
  }
}

/** Incoming call (configure as the number's "A call comes in" webhook). */
telephonyRouter.post("/twilio/voice", async (req, res) => {
  const p = twilioGuard(req, res);
  if (!p) return;
  await reply(res, () => handleCallEvent({ type: "START", provider: TWILIO, callId: p.CallSid, from: p.From || null, to: p.To || null }));
});

telephonyRouter.post("/twilio/gather", async (req, res) => {
  const p = twilioGuard(req, res);
  if (!p) return;
  const turn = String(req.query.turn ?? "");
  await reply(res, () => handleCallEvent({ type: "SPEECH", provider: TWILIO, callId: p.CallSid, eventId: `turn-${turn}`, text: p.SpeechResult ?? "" }));
});

telephonyRouter.post("/twilio/dial-result", async (req, res) => {
  const p = twilioGuard(req, res);
  if (!p) return;
  const answered = p.DialCallStatus === "completed" || p.DialCallStatus === "answered";
  await reply(res, () => handleCallEvent({ type: "DIAL_RESULT", provider: TWILIO, callId: p.CallSid, eventId: `dial-${req.query.turn ?? 0}`, answered }));
});

/** Call status callback (configure as the number's "Call status changes" webhook). */
telephonyRouter.post("/twilio/status", async (req, res) => {
  const p = twilioGuard(req, res);
  if (!p) return;
  if (FINAL_STATUSES.has(p.CallStatus)) {
    await handleCallEvent({ type: "END", provider: TWILIO, callId: p.CallSid, durationSec: p.CallDuration ? Number(p.CallDuration) : null });
  }
  res.status(204).end();
});
