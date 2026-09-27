import { createHash } from "node:crypto";
import { Prisma, type PortalLead, type PortalLeadChannel, type PropertyCategory, type TransactionType } from "@prisma/client";
import { prisma } from "../db.js";
import type { AuthUser } from "../lib/auth.js";
import { HttpError } from "../lib/errors.js";
import { normalizePhone } from "../lib/phone.js";
import { localityMatches } from "../domain/locality.js";
import { createClientChecked, findByPhones } from "./clients.js";

/**
 * Portal leads: enquiries from 99acres and Housing.com, each tied to the listing it was about.
 *
 * - The two portals are kept apart: a flat advertised on both is two listings, and each lead
 *   belongs to exactly one portal.
 * - A listing is identified by the portal's listing ID, else its link, else (only when neither
 *   is known) by the advertised type, area and price together. Nothing is guessed: fields the
 *   source didn't give stay empty.
 * - Every distinct enquiry keeps its own record; the same enquiry arriving twice is stored once.
 * - Leads are linked to clients by phone number or email only, never by name. A client is
 *   created only when the lead has a phone number that no client has.
 */

export const PORTALS = ["ACRES_99", "HOUSING_COM"] as const;
export type PortalSource = (typeof PORTALS)[number];
export const PORTAL_NAME: Record<PortalSource, string> = { ACRES_99: "99acres", HOUSING_COM: "Housing.com" };
export const isPortal = (v: unknown): v is PortalSource => PORTALS.includes(v as PortalSource);

export interface ListingInput {
  externalId?: string | null;
  url?: string | null;
  title?: string | null;
  transactionType?: TransactionType | null;
  category?: PropertyCategory | null;
  locality?: string | null;
  price?: number | null;
  carpetAreaSqft?: number | null;
  photoUrl?: string | null;
}

export interface LeadInput {
  portal: PortalSource;
  channel: PortalLeadChannel;
  sourceRef?: string | null;
  externalLeadId?: string | null;
  enquiredAt: Date;
  name?: string | null;
  phone?: string | null;
  email?: string | null;
  message?: string | null;
  budgetMin?: number | null;
  budgetMax?: number | null;
  requirement?: string | null;
  listing?: ListingInput | null;
  raw?: unknown;
  /** Already known from the source (e.g. the WhatsApp message was linked to this client). */
  clientId?: string | null;
  /** Stable key when the source can re-deliver the same enquiry (e.g. "wa:<message id>"). */
  dedupeKey?: string | null;
  /** false: link to an existing client only, never create one (used by the one-time backfill). */
  createClient?: boolean;
}

export interface RecordResult {
  lead: PortalLead;
  created: boolean;
  clientCreated: boolean;
}

const clean = (v: string | null | undefined) => {
  const t = v?.trim();
  return t ? t : null;
};

/** Listing links without tracking noise, so the same listing's link compares equal. */
export function normalizeListingUrl(raw: string | null | undefined): string | null {
  const u = clean(raw);
  if (!u) return null;
  try {
    const url = new URL(u);
    if (url.protocol !== "https:" && url.protocol !== "http:") return null;
    for (const k of [...url.searchParams.keys()]) if (/^(utm_|fbclid|gclid|ref|src|source)/i.test(k)) url.searchParams.delete(k);
    url.hash = "";
    url.hostname = url.hostname.toLowerCase().replace(/^www\./, "");
    return url.toString().replace(/\/$/, "");
  } catch {
    return null;
  }
}

/** Same type + area + price on the same portal: used only when there is no ID or link. */
function fingerprintOf(l: ListingInput): string | null {
  const kind = l.category ?? clean(l.title)?.toLowerCase();
  const area = clean(l.locality)?.toLowerCase().replace(/\s+/g, " ");
  if (!kind || !area || l.price == null) return null;
  return [l.transactionType ?? "?", kind, area, l.price].join("|");
}

const hash = (parts: (string | number | null | undefined)[]) =>
  createHash("sha256").update(parts.map((p) => p ?? "").join("␟")).digest("hex").slice(0, 40);

/** Finds or creates the portal listing; null when the source identifies no listing. */
export async function resolveListing(brokerageId: string, portal: PortalSource, input: ListingInput | null | undefined) {
  if (!input) return null;
  const externalId = clean(input.externalId);
  const url = normalizeListingUrl(input.url);
  const fingerprint = externalId || url ? null : fingerprintOf(input);
  if (!externalId && !url && !fingerprint) return null;

  const facts = {
    title: clean(input.title),
    transactionType: input.transactionType ?? null,
    category: input.category ?? null,
    locality: clean(input.locality),
    price: input.price != null ? BigInt(Math.round(input.price)) : null,
    carpetAreaSqft: input.carpetAreaSqft ?? null,
    photoUrl: normalizeListingUrl(input.photoUrl),
  };
  const identity: Prisma.PortalListingWhereInput[] = [];
  if (externalId) identity.push({ externalId });
  if (url) identity.push({ url });
  if (fingerprint) identity.push({ fingerprint });

  let listing = await prisma.portalListing.findFirst({ where: { brokerageId, portal, OR: identity } });
  if (listing) {
    // Fill in what was missing; never overwrite what an earlier source said.
    const patch: Prisma.PortalListingUpdateInput = {};
    for (const [k, v] of Object.entries(facts)) {
      if (v != null && (listing as Record<string, unknown>)[k] == null) (patch as Record<string, unknown>)[k] = v;
    }
    if (externalId && !listing.externalId) patch.externalId = externalId;
    if (url && !listing.url) patch.url = url;
    if (Object.keys(patch).length) {
      listing = await prisma.portalListing.update({ where: { id: listing.id }, data: patch }).catch((err) => {
        // Another listing already has that ID/link: keep this one as it was.
        if (err instanceof Prisma.PrismaClientKnownRequestError && err.code === "P2002") return listing!;
        throw err;
      });
    }
  } else {
    try {
      listing = await prisma.portalListing.create({ data: { brokerageId, portal, externalId, url, fingerprint, ...facts } });
    } catch (err) {
      if (!(err instanceof Prisma.PrismaClientKnownRequestError && err.code === "P2002")) throw err;
      listing = await prisma.portalListing.findFirst({ where: { brokerageId, portal, OR: identity } });
      if (!listing) throw err;
    }
  }
  if (!listing.propertyId) await linkOwnProperty(listing.id);
  return listing;
}

/**
 * Links a portal listing to one of the brokerage's own properties when exactly one fits:
 * the portal's listing ID written in the property's title or notes, or the same rent/sale,
 * type, area and exact price. Anything ambiguous stays unlinked.
 */
export async function linkOwnProperty(listingId: string) {
  const l = await prisma.portalListing.findUnique({ where: { id: listingId } });
  if (!l || l.propertyId) return;
  let match: { id: string } | null = null;
  let how: string | null = null;
  if (l.externalId) {
    const byRef = await prisma.property.findMany({
      where: {
        brokerageId: l.brokerageId,
        OR: [{ notes: { contains: l.externalId, mode: "insensitive" } }, { title: { contains: l.externalId, mode: "insensitive" } }],
      },
      select: { id: true },
      take: 2,
    });
    if (byRef.length === 1) [match, how] = [byRef[0], `${PORTAL_NAME[l.portal as PortalSource]} listing ID ${l.externalId} is in the property's details`];
  }
  if (!match && l.transactionType && l.category && l.locality && l.price != null) {
    const same = await prisma.property.findMany({
      where: { brokerageId: l.brokerageId, transactionType: l.transactionType, category: l.category, price: l.price },
      select: { id: true, locality: true },
      take: 20,
    });
    const inArea = same.filter((p) => localityMatches(l.locality!, p.locality));
    if (inArea.length === 1) [match, how] = [inArea[0], "Same rent/sale, type, area and price"];
  }
  if (match) await prisma.portalListing.update({ where: { id: l.id }, data: { propertyId: match.id, propertyMatch: how } });
}

/** The brokerage's owner, who acts for automatic sources (webhooks) that have no signed-in user. */
export async function brokerageActor(brokerageId: string): Promise<AuthUser> {
  const u = await prisma.user.findFirst({
    where: { brokerageId, active: true },
    orderBy: [{ role: "asc" }, { createdAt: "asc" }],
  });
  if (!u) throw new HttpError(409, "NO_USERS", "The brokerage has no active users");
  return { id: u.id, brokerageId, role: u.role, name: u.name };
}

/** Finds the client by phone, then by exact email. Never by name. */
async function matchClient(brokerageId: string, phone: string | null, email: string | null) {
  if (phone) {
    const byPhone = await findByPhones(brokerageId, [phone]);
    if (byPhone) return byPhone;
  }
  if (email) {
    const byEmail = await prisma.client.findMany({ where: { brokerageId, email: { equals: email, mode: "insensitive" } }, take: 2 });
    if (byEmail.length === 1) return byEmail[0];
  }
  return null;
}

/** Stores one enquiry (or returns the existing record when it was already stored). */
export async function recordLead(me: AuthUser, input: LeadInput): Promise<RecordResult> {
  const brokerageId = me.brokerageId;
  const phone = input.phone ? normalizePhone(input.phone) : null;
  const email = clean(input.email)?.toLowerCase() ?? null;
  const name = clean(input.name);
  const message = clean(input.message);
  const externalLeadId = clean(input.externalLeadId);
  const listing = await resolveListing(brokerageId, input.portal, input.listing);

  const dedupeKey = clean(input.dedupeKey)
    ?? (externalLeadId ? `ext:${externalLeadId}` : `h:${hash([
      listing?.id, phone ?? email ?? name, message,
      new Date(Math.floor(input.enquiredAt.getTime() / 60_000) * 60_000).toISOString(),
    ])}`);

  let clientId = input.clientId ?? null;
  let clientCreated = false;
  if (!clientId) clientId = (await matchClient(brokerageId, phone, email))?.id ?? null;

  const existing = (await prisma.portalLead.findUnique({
    where: { brokerageId_portal_dedupeKey: { brokerageId, portal: input.portal, dedupeKey } },
  })) ?? (await sameEnquiryFromAnotherSource(brokerageId, input.portal, listing?.id ?? null, phone, email, externalLeadId, input.enquiredAt));
  if (existing) {
    const patch: Prisma.PortalLeadUncheckedUpdateInput = {};
    if (!existing.clientId && clientId) patch.clientId = clientId;
    if (!existing.listingId && listing) patch.listingId = listing.id;
    // A second source may know more about the same enquiry; fill gaps only.
    if (!existing.externalLeadId && externalLeadId) patch.externalLeadId = externalLeadId;
    if (!existing.name && name) patch.name = name;
    if (!existing.email && email) patch.email = email;
    if (!existing.phone && phone) patch.phone = phone;
    if (!existing.message && message) patch.message = message;
    const lead = Object.keys(patch).length ? await prisma.portalLead.update({ where: { id: existing.id }, data: patch }) : existing;
    if (patch.clientId || patch.listingId) await requirementFromLead(me, lead.id);
    return { lead, created: false, clientCreated: false };
  }

  if (!clientId && phone && input.createClient !== false) {
    try {
      const client = await createClientChecked(me, {
        name: name ?? phone,
        phone,
        altPhones: [],
        email: email ?? undefined,
        leadSource: input.portal,
        status: "NEW",
        notes: null,
      });
      clientId = client.id;
      clientCreated = true;
    } catch (err) {
      // Created meanwhile, or the email belongs to an existing client: link to that one.
      const existingClient = err instanceof HttpError ? (err.details as { existingClient?: { id: string } } | undefined)?.existingClient : undefined;
      if (!existingClient) throw err;
      clientId = existingClient.id;
    }
  }

  try {
    const lead = await prisma.portalLead.create({
      data: {
        brokerageId,
        portal: input.portal,
        listingId: listing?.id ?? null,
        clientId,
        externalLeadId,
        enquiredAt: input.enquiredAt,
        name,
        phone,
        email,
        message,
        budgetMin: input.budgetMin != null ? BigInt(Math.round(input.budgetMin)) : null,
        budgetMax: input.budgetMax != null ? BigInt(Math.round(input.budgetMax)) : null,
        requirement: clean(input.requirement),
        channel: input.channel,
        sourceRef: clean(input.sourceRef),
        raw: input.raw == null ? Prisma.JsonNull : (input.raw as Prisma.InputJsonValue),
        dedupeKey,
      },
    });
    if (lead.clientId) await requirementFromLead(me, lead.id);
    return { lead, created: true, clientCreated };
  } catch (err) {
    if (err instanceof Prisma.PrismaClientKnownRequestError && err.code === "P2002") {
      const lead = await prisma.portalLead.findUniqueOrThrow({
        where: { brokerageId_portal_dedupeKey: { brokerageId, portal: input.portal, dedupeKey } },
      });
      return { lead, created: false, clientCreated: false };
    }
    throw err;
  }
}

/** How close in time two copies of one enquiry from different sources (WhatsApp, CSV, email) can be. */
export const SAME_ENQUIRY_WINDOW_MS = 10 * 60_000;

/**
 * The same enquiry reaching BrokerBuddy by a second route (e.g. the WhatsApp notification and
 * then the portal's CSV export): same portal, same person (phone or email), same listing (or
 * one source didn't say which), within a few minutes. Two different lead IDs from the portal
 * are always two enquiries; with no phone or email nothing is treated as the same.
 */
async function sameEnquiryFromAnotherSource(
  brokerageId: string, portal: PortalSource, listingId: string | null,
  phone: string | null, email: string | null, externalLeadId: string | null, at: Date,
) {
  if (!phone && !email) return null;
  return prisma.portalLead.findFirst({
    where: {
      brokerageId,
      portal,
      AND: [
        { OR: [...(phone ? [{ phone }] : []), ...(email ? [{ email }] : [])] },
        listingId ? { OR: [{ listingId }, { listingId: null }] } : {},
        externalLeadId ? { OR: [{ externalLeadId: null }, { externalLeadId }] } : {},
      ],
      enquiredAt: { gte: new Date(at.getTime() - SAME_ENQUIRY_WINDOW_MS), lte: new Date(at.getTime() + SAME_ENQUIRY_WINDOW_MS) },
    },
    orderBy: { enquiredAt: "asc" },
  });
}

/** A WhatsApp message was linked to a client: its portal leads follow (and give a requirement if they state one). */
export async function linkLeadsOfMessage(me: AuthUser, messageId: string, clientId: string) {
  const leads = await prisma.portalLead.findMany({ where: { brokerageId: me.brokerageId, sourceRef: messageId, clientId: null }, select: { id: true } });
  if (!leads.length) return;
  await prisma.portalLead.updateMany({ where: { id: { in: leads.map((l) => l.id) } }, data: { clientId } });
  for (const l of leads) await requirementFromLead(me, l.id);
}

const inr = (n: bigint | number) => `₹${Number(n).toLocaleString("en-IN")}`;

/**
 * The requirement a portal enquiry states: someone who enquired about a "1 BHK for rent in
 * Mulund West" is looking for that. Created only when the listing says rent/sale and the
 * property type, and the client has no active requirement of that kind already (existing
 * requirements are never changed). The listing's price is noted as the advertised price —
 * it is not a budget the client stated; a budget from the client's own words is used.
 */
export async function requirementFromLead(me: AuthUser, leadId: string) {
  const lead = await prisma.portalLead.findFirst({ where: { id: leadId, brokerageId: me.brokerageId }, include: { listing: true } });
  const l = lead?.listing;
  if (!lead?.clientId || !l?.transactionType || !l.category) return null;
  const existing = await prisma.inquiry.findFirst({
    where: { clientId: lead.clientId, status: "ACTIVE", transactionType: l.transactionType, category: l.category },
  });
  if (existing) return null;
  const portal = PORTAL_NAME[lead.portal as PortalSource] ?? lead.portal;
  const about = [l.title, l.locality].filter(Boolean).join(", ") || "a listing";
  const priced = l.price != null ? ` listed at ${inr(l.price)}${l.transactionType === "RENT" ? "/month" : ""} (the listing's price, not a budget the client stated)` : "";
  const { createInquiry } = await import("../routes/inquiries.js");
  return createInquiry(me, lead.clientId, {
    transactionType: l.transactionType,
    category: l.category,
    status: "ACTIVE",
    locations: l.locality ? [l.locality] : [],
    furnishing: [],
    floorPreference: [],
    propertyTypes: [],
    mandatory: [],
    budgetMin: lead.budgetMin != null ? Number(lead.budgetMin) : null,
    budgetMax: lead.budgetMax != null ? Number(lead.budgetMax) : null,
    notes: `From a ${portal} enquiry about ${about}${priced}.${lead.message ? ` Their message: “${lead.message.slice(0, 500)}”` : ""}`,
    source: "PORTAL_LEAD",
  });
}

// ---------- reading ----------

export interface Range {
  from?: Date;
  to?: Date;
}
export const enquiredIn = (r: Range): Prisma.DateTimeFilter | undefined =>
  r.from || r.to ? { ...(r.from ? { gte: r.from } : {}), ...(r.to ? { lt: r.to } : {}) } : undefined;

/** Who the lead is, for counting distinct interested people. */
const personKey = (l: { id: string; clientId: string | null; phone: string | null; email: string | null }) =>
  l.clientId ? `c:${l.clientId}` : l.phone ? `p:${l.phone}` : l.email ? `e:${l.email}` : `l:${l.id}`;

export const UNIDENTIFIED = "unidentified";

/** Listings of one portal that received enquiries in the range, most recent enquiry first. */
export async function listingSummaries(brokerageId: string, portal: PortalSource, range: Range) {
  const leads = await prisma.portalLead.findMany({
    where: { brokerageId, portal, enquiredAt: enquiredIn(range) },
    select: { id: true, listingId: true, clientId: true, phone: true, email: true, status: true, enquiredAt: true },
  });
  const groups = new Map<string, typeof leads>();
  for (const l of leads) {
    const k = l.listingId ?? UNIDENTIFIED;
    groups.set(k, [...(groups.get(k) ?? []), l]);
  }
  const ids = [...groups.keys()].filter((k) => k !== UNIDENTIFIED);
  const listings = await prisma.portalListing.findMany({ where: { id: { in: ids } } });
  const photos = await prisma.propertyPhoto.findMany({
    where: { propertyId: { in: listings.map((l) => l.propertyId).filter((x): x is string => x != null) } },
    orderBy: [{ position: "asc" }, { createdAt: "asc" }],
    select: { id: true, propertyId: true },
  });
  const firstPhoto = new Map<string, string>();
  for (const p of photos) if (!firstPhoto.has(p.propertyId)) firstPhoto.set(p.propertyId, p.id);

  return [...groups.entries()]
    .map(([key, ls]) => {
      const l = listings.find((x) => x.id === key);
      return {
        id: key,
        portal,
        identified: key !== UNIDENTIFIED,
        title: l?.title ?? null,
        transactionType: l?.transactionType ?? null,
        category: l?.category ?? null,
        locality: l?.locality ?? null,
        price: l?.price ?? null,
        carpetAreaSqft: l?.carpetAreaSqft ?? null,
        url: l?.url ?? null,
        externalId: l?.externalId ?? null,
        photoUrl: l?.photoUrl ?? null,
        propertyId: l?.propertyId ?? null,
        propertyPhotoId: l?.propertyId ? firstPhoto.get(l.propertyId) ?? null : null,
        status: l?.status ?? null,
        interestedClients: new Set(ls.map(personKey)).size,
        newLeads: ls.filter((x) => x.status === "NEW").length,
        totalLeads: ls.length,
        lastEnquiryAt: new Date(Math.max(...ls.map((x) => x.enquiredAt.getTime()))),
      };
    })
    .sort((a, b) => b.lastEnquiryAt.getTime() - a.lastEnquiryAt.getTime());
}

const leadInclude = {
  client: { select: { id: true, name: true, primaryPhone: true, status: true } },
  listing: { select: { id: true, title: true, locality: true, portal: true } },
} satisfies Prisma.PortalLeadInclude;

export type LeadWithClient = Prisma.PortalLeadGetPayload<{ include: typeof leadInclude }>;

/** The enquiries about one listing (or the portal's unidentified ones), newest first. */
export async function leadsOfListing(brokerageId: string, portal: PortalSource, listingId: string, range: Range) {
  return prisma.portalLead.findMany({
    where: {
      brokerageId, portal,
      listingId: listingId === UNIDENTIFIED ? null : listingId,
      enquiredAt: enquiredIn(range),
    },
    include: leadInclude,
    orderBy: { enquiredAt: "desc" },
  });
}

export async function leadsOfClient(brokerageId: string, clientId: string) {
  return prisma.portalLead.findMany({ where: { brokerageId, clientId }, include: leadInclude, orderBy: { enquiredAt: "desc" } });
}

// ---------- status ----------

/**
 * Sets a lead's status. Marking a lead contacted also moves a client still marked New to
 * Contacted (the client status the rest of the app uses); nothing else is changed on the client.
 */
export async function setLeadStatus(brokerageId: string, id: string, status: PortalLead["status"]) {
  const lead = await prisma.portalLead.findFirst({ where: { id, brokerageId } });
  if (!lead) throw new HttpError(404, "NOT_FOUND", "Lead not found");
  const updated = await prisma.portalLead.update({ where: { id }, data: { status }, include: leadInclude });
  if (lead.clientId && status !== "NEW") {
    await prisma.client.updateMany({ where: { id: lead.clientId, brokerageId, status: "NEW" }, data: { status: "CONTACTED" } });
  }
  return updated;
}
