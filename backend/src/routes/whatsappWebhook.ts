import express, { Router } from "express";
import { prisma } from "../db.js";
import { decrypt, validMetaSignature } from "../lib/crypto.js";
import { parseWebhook } from "../whatsapp/webhook.js";
import { ingestChange, processMessage } from "../whatsapp/processor.js";

/**
 * Public endpoints Meta calls. Each connected number has its own unguessable URL
 * (/webhooks/whatsapp/<webhookKey>), verify token and app secret.
 *  - GET: the subscription handshake (hub.mode / hub.verify_token / hub.challenge).
 *  - POST: events, authenticated by X-Hub-Signature-256 (HMAC-SHA256 of the raw body).
 */
export const whatsappWebhookRouter = Router();

let inFlight: Promise<unknown> = Promise.resolve();
/** Resolves when processing triggered by earlier deliveries has finished (used by tests). */
export const webhookIdle = () => inFlight;

whatsappWebhookRouter.get("/:key", async (req, res) => {
  const account = await prisma.whatsAppAccount.findUnique({ where: { webhookKey: req.params.key } });
  const mode = req.query["hub.mode"];
  const token = req.query["hub.verify_token"];
  const challenge = req.query["hub.challenge"];
  if (!account || mode !== "subscribe" || token !== account.verifyToken || typeof challenge !== "string") {
    res.sendStatus(403);
    return;
  }
  await prisma.whatsAppAccount.update({ where: { id: account.id }, data: { verifiedAt: new Date() } });
  res.type("text/plain").send(challenge);
});

whatsappWebhookRouter.post("/:key", express.raw({ type: "*/*", limit: "1mb" }), async (req, res) => {
  const account = await prisma.whatsAppAccount.findUnique({ where: { webhookKey: req.params.key } });
  if (!account) {
    res.sendStatus(404);
    return;
  }
  const raw = Buffer.isBuffer(req.body) ? req.body : Buffer.alloc(0);
  if (!validMetaSignature(raw, req.header("x-hub-signature-256"), decrypt(account.appSecretEnc))) {
    res.sendStatus(401);
    return;
  }
  let payload: unknown;
  try {
    payload = JSON.parse(raw.toString("utf8"));
  } catch {
    res.sendStatus(400);
    return;
  }
  const ids: string[] = [];
  for (const change of parseWebhook(payload)) {
    // A webhook for another number on the same Meta app is not this account's data.
    if (change.phoneNumberId !== account.phoneNumberId) continue;
    ids.push(...(await ingestChange(account, change)));
  }
  // Acknowledge quickly; Meta retries slow or failed deliveries. Stored messages that
  // fail to process are picked up again by the periodic processPending sweep.
  res.sendStatus(200);
  inFlight = inFlight.then(async () => {
    for (const id of ids) await processMessage(id);
  }).catch((err) => console.error("WhatsApp processing failed", err));
});
