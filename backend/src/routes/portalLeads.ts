import { createHash, randomBytes } from "node:crypto";
import { Router } from "express";
import { z } from "zod";
import { PortalLeadStatus, PropertyCategory, TransactionType } from "@prisma/client";
import { prisma } from "../db.js";
import { currentUser, requireRole } from "../lib/auth.js";
import { HttpError, badRequest, notFound } from "../lib/errors.js";
import { absentIfNull } from "../schemas.js";
import { leadsFromCsv, listingFactsFromTitle, parseEnquiryDate } from "../services/portalImport.js";
import {
  PORTALS, PORTAL_NAME, type PortalSource, UNIDENTIFIED, brokerageActor, isPortal, leadsOfListing, listingSummaries,
  recordLead, setLeadStatus,
} from "../services/portalLeads.js";
import { analyzeMessage } from "../whatsapp/analyze.js";

/** Signed-in routes: /api/v1/portal-leads/… */
export const portalLeadsRouter = Router();

const portalParam = z.enum(PORTALS);
const range = z.object({
  from: z.coerce.date().optional(),
  to: z.coerce.date().optional(),
});

portalLeadsRouter.get("/listings", async (req, res) => {
  const me = currentUser(req);
  const q = range.extend({ portal: portalParam }).parse(req.query);
  res.json({ listings: await listingSummaries(me.brokerageId, q.portal, q) });
});

portalLeadsRouter.get("/listings/:id", async (req, res) => {
  const me = currentUser(req);
  const q = range.extend({ portal: portalParam.optional() }).parse(req.query);
  const id = String(req.params.id);
  let portal: PortalSource;
  let listing = null;
  if (id === UNIDENTIFIED) {
    if (!q.portal) throw badRequest("portal is required for unidentified listings");
    portal = q.portal;
  } else {
    listing = await prisma.portalListing.findFirst({ where: { id, brokerageId: me.brokerageId } });
    if (!listing || !isPortal(listing.portal)) throw notFound("Listing");
    portal = listing.portal;
  }
  const [summary] = (await listingSummaries(me.brokerageId, portal, {})).filter((s) => s.id === id);
  const leads = await leadsOfListing(me.brokerageId, portal, id, q);
  res.json({
    listing: summary ?? null,
    totalInterestedClients: summary?.interestedClients ?? 0,
    leads,
  });
});

portalLeadsRouter.get("/", async (req, res) => {
  const me = currentUser(req);
  const q = range.extend({ portal: portalParam.optional(), clientId: z.string().optional() }).parse(req.query);
  const leads = await prisma.portalLead.findMany({
    where: {
      brokerageId: me.brokerageId,
      portal: q.portal,
      clientId: q.clientId,
      enquiredAt: q.from || q.to ? { gte: q.from, lt: q.to } : undefined,
    },
    include: {
      client: { select: { id: true, name: true, primaryPhone: true, status: true } },
      listing: { select: { id: true, title: true, locality: true, portal: true } },
    },
    orderBy: { enquiredAt: "desc" },
    take: 500,
  });
  res.json({ leads });
});

portalLeadsRouter.patch("/:id", async (req, res) => {
  const me = currentUser(req);
  const { status } = z.object({ status: z.nativeEnum(PortalLeadStatus) }).parse(req.body);
  res.json({ lead: await setLeadStatus(me.brokerageId, String(req.params.id), status) });
});

// ---------- imports ----------

portalLeadsRouter.post("/import", async (req, res) => {
  const me = currentUser(req);
  const body = z.object({ portal: portalParam, csv: z.string().min(1).max(2_000_000), fileName: absentIfNull(z.string().max(200)) }).parse(req.body);
  const batch = `csv:${new Date().toISOString()}${body.fileName ? `:${body.fileName}` : ""}`;
  const { columns, rows } = leadsFromCsv(body.portal, body.csv, batch);
  if (columns.enquiredAt == null || (columns.phone == null && columns.email == null)) {
    throw new HttpError(400, "UNRECOGNISED_FILE",
      "Couldn't find the enquiry date and phone/email columns. Export the leads from the portal as CSV and try again.",
      { recognisedColumns: Object.keys(columns) });
  }
  let created = 0, duplicates = 0, clientsCreated = 0;
  const skipped: { row: number; reason: string }[] = [];
  for (const r of rows) {
    if (!r.lead) { skipped.push({ row: r.row, reason: r.error ?? "Unreadable row" }); continue; }
    try {
      const out = await recordLead(me, r.lead);
      if (out.created) created++; else duplicates++;
      if (out.clientCreated) clientsCreated++;
    } catch (err) {
      skipped.push({ row: r.row, reason: err instanceof Error ? err.message : "Couldn't save" });
    }
  }
  res.json({ portal: body.portal, rows: rows.length, created, duplicates, clientsCreated, skipped, recognisedColumns: Object.keys(columns) });
});

/** A lead email (or any portal lead text) pasted by the broker. */
portalLeadsRouter.post("/import-text", async (req, res) => {
  const me = currentUser(req);
  const body = z.object({
    text: z.string().trim().min(10).max(20_000),
    portal: absentIfNull(portalParam),
    enquiredAt: absentIfNull(z.string().max(60)),
  }).parse(req.body);
  const { portalLead, extraction } = await analyzeMessage(body.text);
  const portal = body.portal ?? portalLead?.portal;
  if (!portal || !isPortal(portal)) {
    throw badRequest("This doesn't look like a 99acres or Housing.com lead. Choose the portal and paste the whole email.");
  }
  if (portalLead && portalLead.portal !== portal && isPortal(portalLead.portal)) {
    throw badRequest(`This text is from ${PORTAL_NAME[portalLead.portal]}, not ${PORTAL_NAME[portal]}`);
  }
  const enquiredAt = body.enquiredAt ? parseEnquiryDate(body.enquiredAt) ?? new Date(body.enquiredAt) : new Date();
  if (Number.isNaN(enquiredAt.getTime())) throw badRequest("Unrecognised enquiry date");
  const facts = portalLead?.listingFacts ?? {};
  const out = await recordLead(me, {
    portal,
    channel: "EMAIL",
    enquiredAt,
    name: portalLead?.leadName ?? null,
    phone: portalLead?.leadPhone ?? null,
    email: portalLead?.leadEmail ?? null,
    message: portalLead?.leadMessage ?? null,
    budgetMin: extraction.draft.budgetMin?.value ?? null,
    budgetMax: extraction.draft.budgetMax?.value ?? null,
    listing: portalLead ? {
      externalId: portalLead.listingRef,
      url: portalLead.listingPage?.url ?? portalLead.listingUrl,
      title: portalLead.listingTitle,
      transactionType: facts.transactionType ?? null,
      category: (facts.category as PropertyCategory | undefined) ?? null,
      locality: facts.locality,
      price: portalLead.listingPrice?.value ?? null,
    } : null,
    raw: { text: body.text },
  });
  res.status(out.created ? 201 : 200).json(out);
});

// ---------- integration status ----------

const hashKey = (key: string) => createHash("sha256").update(key).digest("hex");

portalLeadsRouter.get("/integrations", async (req, res) => {
  const me = currentUser(req);
  const [integrations, stats] = await Promise.all([
    prisma.portalIntegration.findMany({ where: { brokerageId: me.brokerageId } }),
    prisma.portalLead.groupBy({
      by: ["portal", "channel"],
      where: { brokerageId: me.brokerageId },
      _count: true,
      _max: { createdAt: true },
    }),
  ]);
  res.json({
    portals: PORTALS.map((portal) => {
      const i = integrations.find((x) => x.portal === portal);
      const mine = stats.filter((s) => s.portal === portal);
      return {
        portal,
        // Connected only once the inbound URL has actually received a lead.
        status: !i ? "IMPORT_REQUIRED" : i.lastReceivedAt ? "CONNECTED" : "NOT_CONNECTED",
        inboundKeyPrefix: i?.keyPrefix ?? null,
        keyCreatedAt: i?.createdAt ?? null,
        lastReceivedAt: i?.lastReceivedAt ?? null,
        leadsByChannel: Object.fromEntries(mine.map((s) => [s.channel, s._count])),
        lastLeadAt: mine.reduce<Date | null>((a, s) => (s._max.createdAt && (!a || s._max.createdAt > a) ? s._max.createdAt : a), null),
      };
    }),
  });
});

/** Creates (or replaces) the portal's inbound lead URL. The key is shown only once. */
portalLeadsRouter.post("/integrations/:portal/key", requireRole("OWNER", "ADMIN"), async (req, res) => {
  const me = currentUser(req);
  const portal = portalParam.parse(req.params.portal);
  const key = `bbpl_${randomBytes(24).toString("base64url")}`;
  await prisma.portalIntegration.upsert({
    where: { brokerageId_portal: { brokerageId: me.brokerageId, portal } },
    create: { brokerageId: me.brokerageId, portal, keyHash: hashKey(key), keyPrefix: key.slice(0, 10) },
    update: { keyHash: hashKey(key), keyPrefix: key.slice(0, 10), lastReceivedAt: null },
  });
  res.status(201).json({ portal, key, path: `/api/v1/portal-inbound/${key}` });
});

portalLeadsRouter.delete("/integrations/:portal/key", requireRole("OWNER", "ADMIN"), async (req, res) => {
  const me = currentUser(req);
  const portal = portalParam.parse(req.params.portal);
  await prisma.portalIntegration.deleteMany({ where: { brokerageId: me.brokerageId, portal } });
  res.status(204).end();
});

// ---------- inbound URL (no sign-in; the key in the path identifies the brokerage and portal) ----------

export const portalInboundRouter = Router();

const inboundLead = z.object({
  leadId: absentIfNull(z.string().max(200)),
  enquiredAt: z.string().max(60),
  name: absentIfNull(z.string().max(200)),
  phone: absentIfNull(z.string().max(40)),
  email: absentIfNull(z.string().max(200)),
  message: absentIfNull(z.string().max(5000)),
  budgetMin: absentIfNull(z.number().nonnegative()),
  budgetMax: absentIfNull(z.number().nonnegative()),
  requirement: absentIfNull(z.string().max(2000)),
  listing: absentIfNull(z.object({
    id: absentIfNull(z.string().max(200)),
    url: absentIfNull(z.string().max(2000)),
    title: absentIfNull(z.string().max(300)),
    transactionType: absentIfNull(z.nativeEnum(TransactionType)),
    category: absentIfNull(z.nativeEnum(PropertyCategory)),
    locality: absentIfNull(z.string().max(200)),
    price: absentIfNull(z.number().nonnegative()),
    carpetAreaSqft: absentIfNull(z.number().int().positive()),
    photoUrl: absentIfNull(z.string().max(2000)),
  })),
});

portalInboundRouter.post("/:key", async (req, res) => {
  const integration = await prisma.portalIntegration.findUnique({ where: { keyHash: hashKey(String(req.params.key)) } });
  if (!integration || !isPortal(integration.portal)) throw new HttpError(404, "UNKNOWN_KEY", "Unknown inbound key");
  const body = z.union([z.object({ leads: z.array(inboundLead).min(1).max(500) }), inboundLead]).parse(req.body);
  const leads = "leads" in body ? body.leads : [body];
  const actor = await brokerageActor(integration.brokerageId);
  let created = 0, duplicates = 0;
  const rejected: { index: number; reason: string }[] = [];
  for (const [index, l] of leads.entries()) {
    const enquiredAt = parseEnquiryDate(l.enquiredAt) ?? new Date(l.enquiredAt);
    if (Number.isNaN(enquiredAt.getTime())) { rejected.push({ index, reason: "Unrecognised enquiredAt" }); continue; }
    if (!l.phone && !l.email) { rejected.push({ index, reason: "phone or email is required" }); continue; }
    const out = await recordLead(actor, {
      portal: integration.portal,
      channel: "WEBHOOK",
      sourceRef: `inbound:${integration.keyPrefix}`,
      externalLeadId: l.leadId,
      enquiredAt,
      name: l.name, phone: l.phone, email: l.email, message: l.message,
      budgetMin: l.budgetMin, budgetMax: l.budgetMax, requirement: l.requirement,
      listing: l.listing ? {
        externalId: l.listing.id, url: l.listing.url, title: l.listing.title,
        transactionType: l.listing.transactionType ?? listingFactsFromTitle(l.listing.title).transactionType,
        category: l.listing.category ?? listingFactsFromTitle(l.listing.title).category,
        locality: l.listing.locality, price: l.listing.price, carpetAreaSqft: l.listing.carpetAreaSqft, photoUrl: l.listing.photoUrl,
      } : null,
      raw: l,
    });
    if (out.created) created++; else duplicates++;
  }
  if (created + duplicates > 0) {
    await prisma.portalIntegration.update({ where: { id: integration.id }, data: { lastReceivedAt: new Date() } });
  }
  res.json({ created, duplicates, rejected });
});
