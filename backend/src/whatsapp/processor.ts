import { Prisma, type PropertyCategory, type WhatsAppAccount, type WhatsAppMessage } from "@prisma/client";
import { prisma } from "../db.js";
import { decrypt } from "../lib/crypto.js";
import { normalizePhone } from "../lib/phone.js";
import { findByPhones } from "../services/clients.js";
import type { Extraction } from "../voice/draft.js";
import { getVoiceServices } from "../voice/service.js";
import { analyzeMessage } from "./analyze.js";
import { GraphClient } from "./graph.js";
import type { PortalLead } from "./portalLeads.js";
import { brokerageActor, isPortal, recordLead } from "../services/portalLeads.js";
import type { WebhookChange } from "./webhook.js";

const STATUS_MAP = { sent: "SENT", delivered: "DELIVERED", read: "READ", failed: "FAILED" } as const;
// Delivery states only move forward (a late "sent" must not overwrite "read").
const STATUS_RANK: Record<string, number> = { SENT: 1, DELIVERED: 2, READ: 3, FAILED: 4, RECEIVED: 0 };

/**
 * Stores one webhook delivery. Fast and idempotent: Meta retries deliveries, and the
 * unique (brokerage, wamid) index turns replays into no-ops. Heavy work (extraction,
 * transcription) happens later in processMessage.
 */
export async function ingestChange(account: WhatsAppAccount, change: WebhookChange): Promise<string[]> {
  const created: string[] = [];
  for (const m of change.messages) {
    const waId = normalizePhone(m.from);
    if (!waId) continue;
    const contact = await prisma.whatsAppContact.upsert({
      where: { brokerageId_waId: { brokerageId: account.brokerageId, waId } },
      create: { brokerageId: account.brokerageId, accountId: account.id, waId, profileName: m.profileName, lastInboundAt: m.timestamp },
      update: {
        accountId: account.id,
        ...(m.profileName ? { profileName: m.profileName } : {}),
        lastInboundAt: m.timestamp,
      },
    });
    try {
      const msg = await prisma.whatsAppMessage.create({
        data: {
          brokerageId: account.brokerageId,
          accountId: account.id,
          contactId: contact.id,
          channel: "API",
          direction: "INBOUND",
          externalId: m.id,
          type: m.type,
          text: m.text,
          mediaId: m.mediaId,
          mediaMimeType: m.mimeType,
          senderName: m.profileName ?? contact.profileName,
          sentAt: m.timestamp,
          status: "RECEIVED",
          raw: m.raw as Prisma.InputJsonValue,
        },
      });
      created.push(msg.id);
    } catch (err) {
      if (err instanceof Prisma.PrismaClientKnownRequestError && err.code === "P2002") continue; // retry of a delivered event
      throw err;
    }
  }
  for (const s of change.statuses) {
    const next = STATUS_MAP[s.status];
    const existing = await prisma.whatsAppMessage.findUnique({
      where: { brokerageId_externalId: { brokerageId: account.brokerageId, externalId: s.id } },
    });
    if (!existing || STATUS_RANK[next] <= STATUS_RANK[existing.status]) continue;
    await prisma.whatsAppMessage.update({ where: { id: existing.id }, data: { status: next, errorReason: s.error } });
  }
  await prisma.whatsAppAccount.update({ where: { id: account.id }, data: { lastEventAt: new Date() } });
  return created;
}

const TEXT_TYPES = new Set(["text", "button", "interactive", "image", "video", "document", "location", "contacts"]);

/**
 * Picks the inquiry a message is about: the client's single ACTIVE inquiry matching the
 * draft's type/category, or their only ACTIVE inquiry if the draft doesn't contradict it.
 * Returns null when it isn't clear — the agent chooses.
 */
export async function suggestInquiry(clientId: string, extraction: Extraction | null): Promise<string | null> {
  const active = await prisma.inquiry.findMany({ where: { clientId, status: "ACTIVE" } });
  const d = extraction?.draft;
  const fits = active.filter(
    (i) =>
      (!d?.transactionType || i.transactionType === d.transactionType.value) &&
      (!d?.category || i.category === d.category.value),
  );
  if (fits.length === 1 && (d?.transactionType || d?.category || active.length === 1)) return fits[0].id;
  return null;
}

/** Numbers of the brokerage's own team: a message from them is a forward, not a client. */
async function teamPhones(brokerageId: string): Promise<Set<string>> {
  const users = await prisma.user.findMany({ where: { brokerageId, phone: { not: null } }, select: { phone: true } });
  return new Set(users.map((u) => u.phone!).filter(Boolean));
}

/** Transcribes a voice message sent to the business number (when STT is configured). */
async function transcribeAudio(msg: WhatsAppMessage): Promise<string | null> {
  const { transcriber } = getVoiceServices();
  if (!transcriber || !msg.mediaId || !msg.accountId) return null;
  const account = await prisma.whatsAppAccount.findUnique({ where: { id: msg.accountId } });
  if (!account) return null;
  const graph = new GraphClient(decrypt(account.accessTokenEnc), account.phoneNumberId);
  const media = await graph.downloadMedia(msg.mediaId);
  const mime = media.mimeType.split(";")[0].trim();
  return (await transcriber.transcribe(media.bytes, mime, "AUTO")) || null;
}

/**
 * Identifies who the message is about, recognises portal leads, extracts a requirement
 * draft (never applied automatically) and links the message to a client/inquiry when
 * that is unambiguous. Safe to run again (e.g. after the agent edits links).
 */
export async function processMessage(id: string, opts: { clientId?: string } = {}): Promise<void> {
  const msg = await prisma.whatsAppMessage.findUnique({ where: { id }, include: { contact: true } });
  if (!msg || msg.direction !== "INBOUND") return;
  try {
    let text = msg.text;
    let type = msg.type;
    if (!text && msg.type === "audio") {
      text = await transcribeAudio(msg).catch(() => null);
      if (text) type = "audio"; // stored as the transcript of the voice message
    }

    let portalLead: PortalLead | null = null;
    let extraction: Extraction | null = null;
    if (text && (TEXT_TYPES.has(msg.type) || type === "audio")) {
      const analysis = await analyzeMessage(text);
      portalLead = analysis.portalLead;
      extraction = analysis.extraction;
    }

    // Who is this about?
    const team = await teamPhones(msg.brokerageId);
    const senderWaId = msg.contact?.waId ?? null;
    const senderIsTeam = senderWaId != null && team.has(senderWaId);
    const namesThirdParty = !!(portalLead?.leadName || portalLead?.leadPhone);
    const senderIsLead = msg.channel === "API" && !senderIsTeam && !namesThirdParty;
    const leadPhone = portalLead?.leadPhone ?? (senderIsLead ? senderWaId : null);
    // A sign-off ("Regards, Karishma") is the sender's own name — preferred over the WhatsApp profile name.
    const leadName =
      portalLead?.leadName ??
      (senderIsLead || (msg.channel !== "API" && !namesThirdParty) ? portalLead?.signedName : undefined) ??
      (senderIsLead ? msg.contact?.profileName ?? null : null);

    let clientId = opts.clientId ?? msg.clientId ?? null;
    if (!clientId && leadPhone) clientId = (await findByPhones(msg.brokerageId, [leadPhone]))?.id ?? null;
    const inquiryId = msg.inquiryId ?? (clientId ? await suggestInquiry(clientId, extraction) : null);
    const hasDraft = extraction != null && Object.keys(extraction.draft).length > 0;

    await prisma.$transaction(async (tx) => {
      await tx.whatsAppMessage.update({
        where: { id },
        data: {
          text,
          leadPhone,
          leadName,
          portal: portalLead?.portal ?? null,
          portalLead: portalLead ? (portalLead as unknown as Prisma.InputJsonValue) : Prisma.JsonNull,
          extraction: extraction ? (extraction as unknown as Prisma.InputJsonValue) : Prisma.JsonNull,
          review: msg.review === "APPLIED" || msg.review === "DISMISSED" ? msg.review : hasDraft ? "PENDING" : "NONE",
          clientId,
          inquiryId,
          processedAt: new Date(),
          processError: null,
        },
      });
      // The sender's chat belongs to the client when the sender is the client.
      if (clientId && senderIsLead && msg.contactId && !msg.contact?.clientId) {
        await tx.whatsAppContact.update({ where: { id: msg.contactId }, data: { clientId } });
      }
    });

    // 99acres / Housing.com enquiries are also kept as portal leads, tied to their listing.
    if (portalLead && isPortal(portalLead.portal)) {
      const facts = portalLead.listingFacts ?? {};
      const draft = extraction?.draft;
      const { lead, clientCreated } = await recordLead(await brokerageActor(msg.brokerageId), {
        portal: portalLead.portal,
        channel: "WHATSAPP",
        sourceRef: msg.id,
        dedupeKey: `wa:${msg.id}`,
        enquiredAt: msg.sentAt,
        name: leadName,
        phone: leadPhone,
        email: portalLead.leadEmail,
        message: portalLead.leadMessage ?? text,
        budgetMin: draft?.budgetMin?.value ?? null,
        budgetMax: draft?.budgetMax?.value ?? null,
        listing: {
          externalId: portalLead.listingRef,
          url: portalLead.listingPage?.url ?? portalLead.listingUrl,
          title: portalLead.listingTitle,
          transactionType: facts.transactionType ?? null,
          category: (facts.category as PropertyCategory | undefined) ?? null,
          locality: facts.locality,
          price: portalLead.listingPrice?.value ?? null,
        },
        clientId,
        raw: { text },
      });
      if (clientCreated && lead.clientId && !clientId) {
        await prisma.whatsAppMessage.update({ where: { id }, data: { clientId: lead.clientId } });
      }
    }
  } catch (err) {
    const reason = err instanceof Error ? err.message : String(err);
    await prisma.whatsAppMessage.update({ where: { id }, data: { processError: reason.slice(0, 500) } });
  }
}

/** Processes stored-but-unprocessed inbound messages (after a webhook, and on a timer). */
export async function processPending(limit = 50): Promise<number> {
  const pending = await prisma.whatsAppMessage.findMany({
    where: { processedAt: null, processError: null, direction: "INBOUND" },
    orderBy: { sentAt: "asc" },
    take: limit,
    select: { id: true },
  });
  for (const p of pending) await processMessage(p.id);
  return pending.length;
}
