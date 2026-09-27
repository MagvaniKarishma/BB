import { createHash } from "node:crypto";
import { Router } from "express";
import { z } from "zod";
import { Prisma, type WhatsAppMessage } from "@prisma/client";
import { prisma } from "../db.js";
import { absentIfNull } from "../schemas.js";
import { type AuthUser, currentUser, requireRole } from "../lib/auth.js";
import { encrypt, encryptionConfigured, decrypt, randomToken } from "../lib/crypto.js";
import { HttpError, badRequest, notFound } from "../lib/errors.js";
import { normalizePhone } from "../lib/phone.js";
import { createInquirySchema, updateInquirySchema } from "../schemas.js";
import { addPhoneChecked, createClientChecked, findByPhones } from "../services/clients.js";
import { linkLeadsOfMessage } from "../services/portalLeads.js";
import { createInquiryTx, updateInquiryTx } from "./inquiries.js";
import { parseChatExport } from "../whatsapp/chatExport.js";
import { GraphApiError, GraphClient } from "../whatsapp/graph.js";
import type { PortalLead } from "../whatsapp/portalLeads.js";
import { processMessage, suggestInquiry } from "../whatsapp/processor.js";
import type { Extraction } from "../voice/draft.js";
import { localityMatches } from "../domain/locality.js";
import { withPhotoIds } from "../services/photos.js";

export const whatsappRouter = Router();

const SERVICE_WINDOW_MS = 24 * 60 * 60 * 1000;

const messageInclude = {
  contact: { select: { id: true, waId: true, profileName: true, clientId: true } },
  client: { select: { id: true, name: true, primaryPhone: true } },
} satisfies Prisma.WhatsAppMessageInclude;

type MessageView = Prisma.WhatsAppMessageGetPayload<{ include: typeof messageInclude }>;

// Raw webhook payloads stay server-side.
const view = ({ raw: _raw, ...m }: MessageView) => m;

async function loadMessage(me: AuthUser, id: string) {
  const msg = await prisma.whatsAppMessage.findFirst({ where: { id, brokerageId: me.brokerageId }, include: messageInclude });
  if (!msg) throw notFound("Message");
  return msg;
}

async function detail(me: AuthUser, id: string) {
  const msg = await loadMessage(me, id);
  const inquiries = msg.clientId
    ? await prisma.inquiry.findMany({ where: { clientId: msg.clientId }, orderBy: [{ status: "asc" }, { updatedAt: "desc" }] })
    : [];
  const suggestedInquiryId =
    msg.inquiryId ?? (msg.clientId ? await suggestInquiry(msg.clientId, msg.extraction as unknown as Extraction | null) : null);
  // Would creating a client from this message duplicate an existing profile?
  const existingClient =
    !msg.clientId && msg.leadPhone
      ? await findByPhones(me.brokerageId, [msg.leadPhone]).then((c) => (c ? { id: c.id, name: c.name } : null))
      : null;
  return { message: view(msg), inquiries, suggestedInquiryId, existingClient, enquiredProperties: await withPhotoIds(await enquiredProperties(me, msg)) };
}

/**
 * Portal enquiries usually describe the agent's OWN listing ("your 1 BHK … ₹27,000 in
 * Mulund West", "1 BHK Flat in Veena Nagar … 99acres.com/I94007278"). Suggest inventory
 * that fits: the portal's listing ID written in the property's title/notes, or the same
 * property type and area (and exact price when the message states one). Suggestions
 * only; nothing is linked.
 */
async function enquiredProperties(me: AuthUser, msg: { portalLead: Prisma.JsonValue; extraction: Prisma.JsonValue }) {
  const lead = msg.portalLead as unknown as PortalLead | null;
  if (!lead) return [];
  if (lead.listingRef) {
    const byRef = await prisma.property.findMany({
      where: {
        brokerageId: me.brokerageId,
        OR: [
          { notes: { contains: lead.listingRef, mode: "insensitive" } },
          { title: { contains: lead.listingRef, mode: "insensitive" } },
        ],
      },
      orderBy: { updatedAt: "desc" },
      take: 3,
    });
    if (byRef.length) return byRef;
  }
  const draft = (msg.extraction as unknown as Extraction | null)?.draft;
  const price = lead.listingPrice?.value;
  const wanted = draft?.locations?.map((l) => l.value) ?? [];
  // Without a price, type + area are the only clues; don't guess from type alone.
  if (price == null && (!draft?.category || wanted.length === 0)) return [];
  const candidates = await prisma.property.findMany({
    where: {
      brokerageId: me.brokerageId,
      ...(price != null ? { price: BigInt(price) } : { availability: "AVAILABLE" as const }),
      ...(draft?.category ? { category: draft.category.value } : {}),
      ...(draft?.transactionType ? { transactionType: draft.transactionType.value } : {}),
    },
    orderBy: { updatedAt: "desc" },
    take: 50,
  });
  const inArea = (w: string, p: (typeof candidates)[number]) =>
    localityMatches(w, p.locality) ||
    [p.locality, p.building, p.address].some((f) => f != null && f.toLowerCase().includes(w.toLowerCase()));
  return candidates.filter((p) => wanted.length === 0 || wanted.some((w) => inArea(w, p))).slice(0, 3);
}

// ---------- connection (owners/admins) ----------

const connectSchema = z.object({
  phoneNumberId: z.string().trim().regex(/^\d{5,30}$/, "Phone number ID is the numeric ID from Meta"),
  wabaId: absentIfNull(z.string().trim().regex(/^\d{5,30}$/)),
  displayPhone: absentIfNull(z.string().trim().max(30)),
  accessToken: z.string().trim().min(20).max(1000),
  appSecret: z.string().trim().min(16).max(200),
});

const accountView = (a: { id: string; phoneNumberId: string; wabaId: string | null; displayPhone: string | null; webhookKey: string; verifyToken: string; verifiedAt: Date | null; lastEventAt: Date | null }) => ({
  id: a.id,
  phoneNumberId: a.phoneNumberId,
  wabaId: a.wabaId,
  displayPhone: a.displayPhone,
  webhookPath: `/webhooks/whatsapp/${a.webhookKey}`,
  verifyToken: a.verifyToken,
  verifiedAt: a.verifiedAt,
  lastEventAt: a.lastEventAt,
});

whatsappRouter.get("/account", requireRole("OWNER", "ADMIN"), async (req, res) => {
  const me = currentUser(req);
  const account = await prisma.whatsAppAccount.findFirst({ where: { brokerageId: me.brokerageId } });
  res.json({ connected: !!account, account: account ? accountView(account) : null, encryptionConfigured: encryptionConfigured() });
});

whatsappRouter.put("/account", requireRole("OWNER", "ADMIN"), async (req, res) => {
  const me = currentUser(req);
  const body = connectSchema.parse(req.body);
  if (!encryptionConfigured()) {
    throw new HttpError(503, "ENCRYPTION_NOT_CONFIGURED", "Set DATA_ENCRYPTION_KEY on the server before connecting WhatsApp");
  }
  const taken = await prisma.whatsAppAccount.findUnique({ where: { phoneNumberId: body.phoneNumberId } });
  if (taken && taken.brokerageId !== me.brokerageId) {
    throw new HttpError(409, "NUMBER_IN_USE", "This WhatsApp number is connected to another brokerage");
  }
  const existing = await prisma.whatsAppAccount.findFirst({ where: { brokerageId: me.brokerageId } });
  const data = {
    phoneNumberId: body.phoneNumberId,
    wabaId: body.wabaId ?? null,
    displayPhone: body.displayPhone ? normalizePhone(body.displayPhone) ?? body.displayPhone : null,
    accessTokenEnc: encrypt(body.accessToken),
    appSecretEnc: encrypt(body.appSecret),
  };
  const account = existing
    ? await prisma.whatsAppAccount.update({ where: { id: existing.id }, data })
    : await prisma.whatsAppAccount.create({
        data: { ...data, brokerageId: me.brokerageId, webhookKey: randomToken(24), verifyToken: randomToken(18) },
      });
  res.json({ account: accountView(account) });
});

whatsappRouter.delete("/account", requireRole("OWNER", "ADMIN"), async (req, res) => {
  const me = currentUser(req);
  await prisma.whatsAppAccount.deleteMany({ where: { brokerageId: me.brokerageId } });
  res.status(204).end();
});

// ---------- inbox & history ----------

whatsappRouter.get("/inbox", async (req, res) => {
  const me = currentUser(req);
  const { filter } = z.object({ filter: z.enum(["attention", "needs_client", "needs_review", "all"]).default("attention") }).parse(req.query);
  const where: Prisma.WhatsAppMessageWhereInput = { brokerageId: me.brokerageId, direction: "INBOUND" };
  if (filter === "needs_client") Object.assign(where, { clientId: null, OR: [{ leadPhone: { not: null } }, { portal: { not: null } }] });
  if (filter === "needs_review") Object.assign(where, { review: "PENDING" });
  if (filter === "attention") {
    where.OR = [{ review: "PENDING" }, { clientId: null, leadPhone: { not: null } }, { clientId: null, portal: { not: null } }];
  }
  const messages = await prisma.whatsAppMessage.findMany({ where, orderBy: { sentAt: "desc" }, include: messageInclude, take: 100 });
  res.json({ messages: messages.map(view) });
});

whatsappRouter.get("/clients/:clientId/messages", async (req, res) => {
  const me = currentUser(req);
  const client = await prisma.client.findFirst({ where: { id: req.params.clientId, brokerageId: me.brokerageId } });
  if (!client) throw notFound("Client");
  const messages = await prisma.whatsAppMessage.findMany({
    where: { clientId: client.id },
    orderBy: { sentAt: "asc" },
    include: messageInclude,
    take: 500,
  });
  const contact = await prisma.whatsAppContact.findFirst({ where: { clientId: client.id }, orderBy: { lastInboundAt: "desc" } });
  const windowOpen = !!contact?.lastInboundAt && Date.now() - contact.lastInboundAt.getTime() < SERVICE_WINDOW_MS;
  res.json({ messages: messages.map(view), serviceWindowOpen: windowOpen, lastInboundAt: contact?.lastInboundAt ?? null });
});

whatsappRouter.get("/messages/:id", async (req, res) => {
  const me = currentUser(req);
  res.json(await detail(me, req.params.id));
});

// ---------- linking to clients (duplicate-safe) ----------

/** Attaches a client to this message and to other unlinked messages about the same person. */
async function attachClient(me: AuthUser, msg: WhatsAppMessage, clientId: string) {
  await prisma.whatsAppMessage.update({ where: { id: msg.id }, data: { clientId, inquiryId: null } });
  await linkLeadsOfMessage(me, msg.id, clientId);
  if (msg.leadPhone) {
    await prisma.whatsAppMessage.updateMany({
      where: { brokerageId: msg.brokerageId, clientId: null, leadPhone: msg.leadPhone },
      data: { clientId },
    });
    await prisma.whatsAppContact.updateMany({
      where: { brokerageId: msg.brokerageId, waId: msg.leadPhone, clientId: null },
      data: { clientId },
    });
  }
  await processMessage(msg.id, { clientId }); // re-suggest the inquiry now the client is known
}

const createFromMessageSchema = z.object({
  name: absentIfNull(z.string().trim().min(1).max(120)),
  phone: absentIfNull(z.string().trim().max(30)),
});

/**
 * Creates a client from a lead message. If the number (or email) already belongs to a
 * client, the message is linked to that client instead — no duplicate profile is made.
 */
whatsappRouter.post("/messages/:id/create-client", async (req, res) => {
  const me = currentUser(req);
  const body = createFromMessageSchema.parse(req.body);
  const msg = await loadMessage(me, req.params.id);
  if (msg.clientId) throw badRequest("This message is already linked to a client");
  const phone = body.phone ?? msg.leadPhone;
  const name = body.name ?? msg.leadName;
  if (!phone) throw badRequest("No phone number for this lead — enter one");
  if (!name) throw badRequest("No name for this lead — enter one");
  const portalLead = msg.portalLead as unknown as PortalLead | null;
  let client: { id: string; name: string };
  let linkedExisting = false;
  try {
    client = await createClientChecked(me, {
      name,
      phone,
      altPhones: [],
      email: portalLead?.leadEmail ?? null,
      leadSource: msg.portal ? (msg.portal as "ACRES_99" | "HOUSING_COM" | "MAGICBRICKS") : "WHATSAPP",
      status: "NEW",
      notes: null,
    });
  } catch (err) {
    const existing = (err instanceof HttpError && err.code === "DUPLICATE_CLIENT"
      ? (err.details as { existingClient: { id: string; name: string } }).existingClient
      : null);
    if (!existing) throw err;
    client = existing;
    linkedExisting = true;
  }
  await attachClient(me, msg, client.id);
  res.status(linkedExisting ? 200 : 201).json({ client, linkedExisting, ...(await detail(me, msg.id)) });
});

whatsappRouter.post("/messages/:id/link-client", async (req, res) => {
  const me = currentUser(req);
  const body = z.object({ clientId: z.string().min(1), addNumber: z.boolean().default(false) }).parse(req.body);
  const msg = await loadMessage(me, req.params.id);
  const client = await prisma.client.findFirst({ where: { id: body.clientId, brokerageId: me.brokerageId } });
  if (!client) throw notFound("Client");
  if (msg.leadPhone) {
    const owner = await findByPhones(me.brokerageId, [msg.leadPhone]);
    if (owner && owner.id !== client.id) {
      throw new HttpError(409, "NUMBER_BELONGS_TO_OTHER_CLIENT", `This number belongs to ${owner.name}`, {
        existingClient: { id: owner.id, name: owner.name },
      });
    }
    if (!owner && body.addNumber) await addPhoneChecked(me.brokerageId, client.id, msg.leadPhone, "whatsapp");
  }
  await attachClient(me, msg, client.id);
  res.json(await detail(me, msg.id));
});

whatsappRouter.patch("/messages/:id", async (req, res) => {
  const me = currentUser(req);
  const { inquiryId } = z.object({ inquiryId: z.string().min(1).nullable() }).parse(req.body);
  const msg = await loadMessage(me, req.params.id);
  if (inquiryId) {
    const inquiry = await prisma.inquiry.findFirst({ where: { id: inquiryId, brokerageId: me.brokerageId } });
    if (!inquiry) throw notFound("Inquiry");
    if (inquiry.clientId !== msg.clientId) throw badRequest("That inquiry belongs to a different client");
  }
  await prisma.whatsAppMessage.update({ where: { id: msg.id }, data: { inquiryId } });
  res.json(await detail(me, msg.id));
});

// ---------- requirement drafts ----------

whatsappRouter.post("/messages/:id/extract", async (req, res) => {
  const me = currentUser(req);
  const msg = await loadMessage(me, req.params.id);
  if (msg.review === "APPLIED") throw new HttpError(409, "ALREADY_APPLIED", "Already saved to an inquiry");
  await prisma.whatsAppMessage.update({ where: { id: msg.id }, data: { review: "NONE" } });
  await processMessage(msg.id);
  res.json(await detail(me, msg.id));
});

/** Saves the agent-reviewed requirement, recording a WHATSAPP revision linked to the message. */
whatsappRouter.post("/messages/:id/apply", async (req, res) => {
  const me = currentUser(req);
  // inquiryId null/absent = save as a new requirement.
  const body = z.object({ inquiryId: absentIfNull(z.string().min(1)), requirement: z.unknown() }).parse(req.body);
  const msg = await loadMessage(me, req.params.id);
  if (!msg.clientId) throw badRequest("Link this message to a client first");
  if (msg.review === "APPLIED") throw new HttpError(409, "ALREADY_APPLIED", "Already saved to an inquiry");
  const clientId = msg.clientId;

  const result = await prisma.$transaction(async (tx) => {
    const locked = await tx.$queryRaw<{ review: string }[]>`SELECT review FROM "WhatsAppMessage" WHERE id = ${msg.id} FOR UPDATE`;
    if (locked[0]?.review === "APPLIED") throw new HttpError(409, "ALREADY_APPLIED", "Already saved to an inquiry");
    let inquiryId: string;
    let changed = true;
    if (body.inquiryId) {
      const current = await tx.inquiry.findFirst({ where: { id: body.inquiryId, brokerageId: me.brokerageId } });
      if (!current) throw notFound("Inquiry");
      if (current.clientId !== clientId) throw badRequest("That inquiry belongs to a different client");
      const { source: _s, ...patch } = updateInquirySchema.parse(body.requirement);
      const updated = await updateInquiryTx(tx, me, current, patch, "WHATSAPP");
      inquiryId = updated.inquiry.id;
      changed = updated.changed;
      if (changed) {
        await tx.inquiryRevision.update({
          where: { inquiryId_version: { inquiryId, version: updated.inquiry.version } },
          data: { whatsappMessageId: msg.id },
        });
      }
    } else {
      const requirement = createInquirySchema.parse(body.requirement);
      const created = await createInquiryTx(tx, me, clientId, { ...requirement, source: "WHATSAPP" });
      inquiryId = created.id;
      await tx.inquiryRevision.update({
        where: { inquiryId_version: { inquiryId, version: 1 } },
        data: { whatsappMessageId: msg.id },
      });
    }
    await tx.whatsAppMessage.update({ where: { id: msg.id }, data: { review: "APPLIED", inquiryId } });
    return { inquiryId, changed };
  });
  const inquiry = await prisma.inquiry.findUniqueOrThrow({ where: { id: result.inquiryId } });
  res.json({ inquiry, changed: result.changed, ...(await detail(me, msg.id)) });
});

whatsappRouter.post("/messages/:id/dismiss", async (req, res) => {
  const me = currentUser(req);
  const msg = await loadMessage(me, req.params.id);
  if (msg.review === "APPLIED") throw new HttpError(409, "ALREADY_APPLIED", "Already saved to an inquiry");
  await prisma.whatsAppMessage.update({ where: { id: msg.id }, data: { review: "DISMISSED" } });
  res.json(await detail(me, msg.id));
});

// ---------- manual alternatives (no API needed) ----------

const hash = (...parts: string[]) => createHash("sha256").update(parts.join("\u0000")).digest("hex").slice(0, 40);

async function assertClient(me: AuthUser, clientId?: string | null) {
  if (!clientId) return null;
  const client = await prisma.client.findFirst({ where: { id: clientId, brokerageId: me.brokerageId } });
  if (!client) throw notFound("Client");
  return client;
}

/** A single message shared into the app (Android share sheet / copy-paste). */
whatsappRouter.post("/import", async (req, res) => {
  const me = currentUser(req);
  const body = z
    .object({
      text: z.string().trim().min(1).max(10_000),
      clientId: absentIfNull(z.string()),
      senderName: absentIfNull(z.string().trim().max(120)),
      sentAt: absentIfNull(z.coerce.date()),
    })
    .parse(req.body);
  const client = await assertClient(me, body.clientId);
  const sentAt = body.sentAt ?? new Date();
  const externalId = `manual:${hash(me.brokerageId, client?.id ?? "", body.text, body.sentAt?.toISOString() ?? "")}`;
  let msg = await prisma.whatsAppMessage.findUnique({ where: { brokerageId_externalId: { brokerageId: me.brokerageId, externalId } } });
  const duplicate = !!msg;
  if (!msg) {
    msg = await prisma.whatsAppMessage.create({
      data: {
        brokerageId: me.brokerageId,
        channel: "MANUAL",
        direction: "INBOUND",
        externalId,
        type: "text",
        text: body.text,
        senderName: body.senderName ?? client?.name ?? null,
        sentAt,
        status: "RECEIVED",
        clientId: client?.id ?? null,
      },
    });
    await processMessage(msg.id, { clientId: client?.id });
  }
  res.status(duplicate ? 200 : 201).json({ duplicate, ...(await detail(me, msg.id)) });
});

/** WhatsApp "Export chat" (.txt) — stored as conversation history for a client. */
whatsappRouter.post("/import-chat", async (req, res) => {
  const me = currentUser(req);
  const body = z
    .object({
      clientId: z.string().min(1),
      /** Name of the client as it appears in the export; everyone else is the agent side. */
      clientSenderName: z.string().trim().min(1).max(120),
      exportText: z.string().min(1).max(2_000_000),
      tzOffsetMinutes: z.number().int().min(-720).max(840).default(330),
    })
    .parse(req.body);
  const client = (await assertClient(me, body.clientId))!;
  const parsed = parseChatExport(body.exportText, body.tzOffsetMinutes);
  if (parsed.length === 0) throw badRequest("No messages found — is this a WhatsApp chat export (.txt)?");
  const senders = [...new Set(parsed.map((p) => p.sender))];
  if (!senders.includes(body.clientSenderName)) {
    throw badRequest(`"${body.clientSenderName}" isn't in this chat. Participants: ${senders.join(", ")}`, { senders });
  }
  const rows = parsed.map((p) => ({
    brokerageId: me.brokerageId,
    clientId: client.id,
    channel: "MANUAL" as const,
    direction: p.sender === body.clientSenderName ? ("INBOUND" as const) : ("OUTBOUND" as const),
    externalId: `export:${hash(client.id, p.sentAt.toISOString(), p.sender, p.text ?? "<media>")}`,
    type: p.media ? "media" : "text",
    text: p.text,
    senderName: p.sender,
    sentAt: p.sentAt,
    status: p.sender === body.clientSenderName ? ("RECEIVED" as const) : ("SENT" as const),
    // History import: no automatic drafts for every line; the agent can extract any message.
    processedAt: new Date(),
  }));
  const { count } = await prisma.whatsAppMessage.createMany({ data: rows, skipDuplicates: true });
  res.status(201).json({ imported: count, skippedDuplicates: rows.length - count, participants: senders });
});

// ---------- sending (Business API only) ----------

async function graphFor(me: AuthUser) {
  const account = await prisma.whatsAppAccount.findFirst({ where: { brokerageId: me.brokerageId } });
  if (!account) throw new HttpError(409, "WHATSAPP_NOT_CONNECTED", "WhatsApp Business API isn't connected — use the WhatsApp app instead");
  return { account, graph: new GraphClient(decrypt(account.accessTokenEnc), account.phoneNumberId) };
}

async function recordOutbound(me: AuthUser, accountId: string, clientId: string, to: string, wamid: string, text: string, type: string) {
  const contact = await prisma.whatsAppContact.upsert({
    where: { brokerageId_waId: { brokerageId: me.brokerageId, waId: to } },
    create: { brokerageId: me.brokerageId, accountId, waId: to, clientId },
    update: {},
  });
  return prisma.whatsAppMessage.create({
    data: {
      brokerageId: me.brokerageId, accountId, contactId: contact.id, clientId, channel: "API", direction: "OUTBOUND",
      externalId: wamid, type, text, senderName: me.name, sentAt: new Date(), status: "SENT", processedAt: new Date(),
    },
    include: messageInclude,
  });
}

const sendError = (err: unknown) =>
  err instanceof GraphApiError ? new HttpError(502, "WHATSAPP_SEND_FAILED", `WhatsApp: ${err.message}`) : err;

whatsappRouter.post("/send", async (req, res) => {
  const me = currentUser(req);
  const body = z.object({ clientId: z.string().min(1), text: z.string().trim().min(1).max(4096) }).parse(req.body);
  const client = (await assertClient(me, body.clientId))!;
  const { account, graph } = await graphFor(me);
  // Free-form messages are only allowed within 24 h of the client's last message.
  const contact = await prisma.whatsAppContact.findFirst({ where: { brokerageId: me.brokerageId, clientId: client.id }, orderBy: { lastInboundAt: "desc" } });
  if (!contact?.lastInboundAt || Date.now() - contact.lastInboundAt.getTime() >= SERVICE_WINDOW_MS) {
    throw new HttpError(409, "OUTSIDE_SERVICE_WINDOW",
      "The client hasn't messaged in the last 24 hours. Send an approved template, or message them from the WhatsApp app.");
  }
  let wamid: string;
  try {
    wamid = await graph.sendText(contact.waId, body.text);
  } catch (err) {
    throw sendError(err);
  }
  const message = await recordOutbound(me, account.id, client.id, contact.waId, wamid, body.text, "text");
  res.status(201).json({ message: view(message) });
});

whatsappRouter.post("/send-template", async (req, res) => {
  const me = currentUser(req);
  const body = z
    .object({
      clientId: z.string().min(1),
      template: z.string().trim().regex(/^[a-z0-9_]{1,512}$/),
      language: z.string().trim().default("en"),
      params: z.array(z.string().max(1024)).max(10).default([]),
    })
    .parse(req.body);
  const client = (await assertClient(me, body.clientId))!;
  const { account, graph } = await graphFor(me);
  let wamid: string;
  try {
    wamid = await graph.sendTemplate(client.primaryPhone, body.template, body.language, body.params);
  } catch (err) {
    throw sendError(err);
  }
  const text = `[template ${body.template}] ${body.params.join(" | ")}`.trim();
  const message = await recordOutbound(me, account.id, client.id, client.primaryPhone, wamid, text, "template");
  res.status(201).json({ message: view(message) });
});
