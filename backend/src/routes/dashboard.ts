import { Router } from "express";
import { z } from "zod";
import { type Inquiry, Prisma, PropertyCategory, TransactionType } from "@prisma/client";
import { prisma } from "../db.js";
import { currentUser } from "../lib/auth.js";
import { evaluateMatch } from "../domain/matching.js";
import type { Extraction } from "../voice/draft.js";
import { withPhotoIds } from "../services/photos.js";

export const dashboardRouter = Router();

const CATEGORIES = Object.values(PropertyCategory);
const DAY = 24 * 60 * 60 * 1000;

interface Tile {
  category: PropertyCategory;
  inquiries: number;
  clients: number;
}

/** Short requirement summary for list rows: "2 BHK · Rent · Andheri West". */
type Summary = { transactionType: TransactionType; category: PropertyCategory; location: string | null } | null;
const summarize = (i: Pick<Inquiry, "transactionType" | "category" | "locations"> | null | undefined): Summary =>
  i ? { transactionType: i.transactionType, category: i.category, location: i.locations[0] ?? null } : null;

/**
 * Everything the home screen shows — all real data, nothing decorative:
 * Rent/Buy category counts, totals with "new this week", today's follow-ups, new
 * leads (new clients + unlinked WhatsApp/portal leads) and the listings that match the
 * most active requirements. `tz` is the device's UTC offset in minutes so "today"
 * means the agent's today, not the server's.
 */
dashboardRouter.get("/", async (req, res) => {
  const me = currentUser(req);
  const { tz } = z.object({ tz: z.coerce.number().int().min(-720).max(840).default(330) }).parse(req.query);
  const b = me.brokerageId;

  const rows = await prisma.$queryRaw<
    { transactionType: TransactionType; category: PropertyCategory; inquiries: bigint; clients: bigint }[]
  >(Prisma.sql`
    SELECT "transactionType", "category",
           COUNT(*) AS inquiries,
           COUNT(DISTINCT "clientId") AS clients
    FROM "Inquiry"
    WHERE "brokerageId" = ${b} AND "status" = 'ACTIVE'
    GROUP BY "transactionType", "category"`);

  const board = (type: TransactionType) => {
    const tiles: Tile[] = CATEGORIES.map((category) => {
      const row = rows.find((r) => r.transactionType === type && r.category === category);
      return { category, inquiries: Number(row?.inquiries ?? 0), clients: Number(row?.clients ?? 0) };
    });
    return { total: tiles.reduce((s, t) => s + t.inquiries, 0), tiles };
  };

  const now = new Date();
  const offset = tz * 60_000;
  const startOfToday = new Date(Math.floor((now.getTime() + offset) / DAY) * DAY - offset);
  const endOfToday = new Date(startOfToday.getTime() + DAY - 1);
  const weekAgo = new Date(now.getTime() - 7 * DAY);
  const pendingMine = { brokerageId: b, assignedToId: me.id, status: "PENDING" as const };

  const [
    clientsByStatus, overdue, dueToday, availableProperties,
    totalClients, newClients, activeRequirements, newRequirements, newProperties, pendingFollowUps,
  ] = await Promise.all([
    prisma.client.groupBy({ by: ["status"], where: { brokerageId: b }, _count: true }),
    prisma.reminder.count({ where: { ...pendingMine, dueAt: { lt: now } } }),
    prisma.reminder.count({ where: { ...pendingMine, dueAt: { gte: now, lte: endOfToday } } }),
    prisma.property.count({ where: { brokerageId: b, availability: "AVAILABLE" } }),
    prisma.client.count({ where: { brokerageId: b } }),
    prisma.client.count({ where: { brokerageId: b, createdAt: { gte: weekAgo } } }),
    prisma.inquiry.count({ where: { brokerageId: b, status: "ACTIVE" } }),
    prisma.inquiry.count({ where: { brokerageId: b, status: "ACTIVE", createdAt: { gte: weekAgo } } }),
    prisma.property.count({ where: { brokerageId: b, availability: "AVAILABLE", createdAt: { gte: weekAgo } } }),
    prisma.reminder.count({ where: pendingMine }),
  ]);

  // Today's Work: what the broker has to act on today. Follow-ups and callbacks include overdue ones;
  // portal leads count enquiries received today (the portal screens open on "Today").
  const todayRange = { gte: startOfToday, lte: endOfToday };
  const [newLeadClients, callbacks, followUps, acres99Leads, housingLeads] = await Promise.all([
    prisma.client.count({ where: { brokerageId: b, status: "NEW" } }),
    prisma.reminder.count({ where: { ...pendingMine, kind: "CALLBACK", dueAt: { lte: endOfToday } } }),
    prisma.reminder.count({ where: { ...pendingMine, kind: "FOLLOW_UP", dueAt: { lte: endOfToday } } }),
    prisma.portalLead.count({ where: { brokerageId: b, portal: "ACRES_99", enquiredAt: todayRange } }),
    prisma.portalLead.count({ where: { brokerageId: b, portal: "HOUSING_COM", enquiredAt: todayRange } }),
  ]);

  // Today's follow-ups (overdue first), each with the requirement it's about.
  const reminders = await prisma.reminder.findMany({
    where: { ...pendingMine, dueAt: { lte: endOfToday } },
    orderBy: { dueAt: "asc" },
    take: 10,
    include: {
      client: {
        select: {
          id: true, name: true, primaryPhone: true,
          inquiries: { where: { status: "ACTIVE" }, orderBy: { updatedAt: "desc" }, take: 1 },
        },
      },
      inquiry: true,
    },
  });
  const todayFollowUps = reminders.map((r) => ({
    id: r.id,
    title: r.title,
    dueAt: r.dueAt,
    overdue: r.dueAt < now,
    client: r.client ? { id: r.client.id, name: r.client.name, primaryPhone: r.client.primaryPhone } : null,
    requirement: summarize(r.inquiry ?? r.client?.inquiries[0]),
  }));

  // New leads: clients added this week still marked NEW, plus WhatsApp/portal leads not yet linked.
  const [freshClients, unlinked] = await Promise.all([
    prisma.client.findMany({
      where: { brokerageId: b, status: "NEW", createdAt: { gte: weekAgo } },
      orderBy: { createdAt: "desc" },
      take: 5,
      include: { inquiries: { where: { status: "ACTIVE" }, orderBy: { updatedAt: "desc" }, take: 1 } },
    }),
    prisma.whatsAppMessage.findMany({
      where: {
        brokerageId: b, direction: "INBOUND", clientId: null, sentAt: { gte: weekAgo },
        OR: [{ leadPhone: { not: null } }, { portal: { not: null } }],
      },
      orderBy: { sentAt: "desc" },
      take: 5,
    }),
  ]);
  const newLeads = [
    ...freshClients.map((c) => ({
      kind: "CLIENT" as const, id: c.id, clientId: c.id, messageId: null, name: c.name, phone: c.primaryPhone,
      source: c.leadSource as string, at: c.createdAt, requirement: summarize(c.inquiries[0]),
    })),
    ...unlinked.map((m) => {
      const d = (m.extraction as unknown as Extraction | null)?.draft;
      return {
        kind: "WHATSAPP" as const, id: m.id, clientId: null, messageId: m.id,
        name: m.leadName ?? m.senderName ?? null, phone: m.leadPhone, source: m.portal ?? "WHATSAPP", at: m.sentAt,
        requirement: d?.category && d.transactionType
          ? { transactionType: d.transactionType.value, category: d.category.value, location: d.locations?.[0]?.value ?? null }
          : null,
      };
    }),
  ].sort((x, y) => y.at.getTime() - x.at.getTime()).slice(0, 5);

  // Listings that satisfy the most active requirements (must-haves respected).
  const [properties, active] = await Promise.all([
    prisma.property.findMany({ where: { brokerageId: b, availability: "AVAILABLE" }, orderBy: { updatedAt: "desc" }, take: 300 }),
    prisma.inquiry.findMany({ where: { brokerageId: b, status: "ACTIVE" }, take: 3000 }),
  ]);
  const buckets = new Map<string, Inquiry[]>();
  for (const i of active) {
    const key = `${i.transactionType}|${i.category}`;
    buckets.set(key, [...(buckets.get(key) ?? []), i]);
  }
  const topMatches = properties
    .map((p) => ({
      property: p,
      matchingRequirements: (buckets.get(`${p.transactionType}|${p.category}`) ?? []).filter((i) => evaluateMatch(i, p).eligible).length,
    }))
    .filter((m) => m.matchingRequirements > 0)
    .sort((x, y) => y.matchingRequirements - x.matchingRequirements)
    .slice(0, 6);
  const topPhotos = await withPhotoIds(topMatches.map((m) => m.property));
  topMatches.forEach((m, i) => (m.property = topPhotos[i]));

  res.json({
    generatedAt: now.toISOString(),
    rent: board("RENT"),
    buy: board("BUY"),
    clientsByStatus: Object.fromEntries(clientsByStatus.map((g) => [g.status, g._count])),
    reminders: { overdue, dueToday },
    availableProperties,
    totals: {
      clients: totalClients,
      newClientsThisWeek: newClients,
      activeRequirements,
      newRequirementsThisWeek: newRequirements,
      availableProperties,
      newPropertiesThisWeek: newProperties,
      pendingFollowUps,
      followUpsDueToday: overdue + dueToday,
    },
    todayWork: { newLeads: newLeadClients, callbacks, followUps, acres99Leads, housingLeads },
    todayFollowUps,
    newLeads,
    topMatches,
  });
});
