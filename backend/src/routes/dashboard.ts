import { Router } from "express";
import { Prisma, PropertyCategory, TransactionType } from "@prisma/client";
import { prisma } from "../db.js";
import { currentUser } from "../lib/auth.js";

export const dashboardRouter = Router();

const CATEGORIES = Object.values(PropertyCategory);

interface Tile {
  category: PropertyCategory;
  inquiries: number;
  clients: number;
}

/**
 * Live counts of ACTIVE inquiries per category, split into Rent and Buy.
 * Every category is always present (zero-filled) so the app can render a fixed grid.
 */
dashboardRouter.get("/", async (req, res) => {
  const me = currentUser(req);
  const rows = await prisma.$queryRaw<
    { transactionType: TransactionType; category: PropertyCategory; inquiries: bigint; clients: bigint }[]
  >(Prisma.sql`
    SELECT "transactionType", "category",
           COUNT(*) AS inquiries,
           COUNT(DISTINCT "clientId") AS clients
    FROM "Inquiry"
    WHERE "brokerageId" = ${me.brokerageId} AND "status" = 'ACTIVE'
    GROUP BY "transactionType", "category"`);

  const board = (type: TransactionType) => {
    const tiles: Tile[] = CATEGORIES.map((category) => {
      const row = rows.find((r) => r.transactionType === type && r.category === category);
      return { category, inquiries: Number(row?.inquiries ?? 0), clients: Number(row?.clients ?? 0) };
    });
    return { total: tiles.reduce((s, t) => s + t.inquiries, 0), tiles };
  };

  const now = new Date();
  const endOfToday = new Date(now);
  endOfToday.setHours(23, 59, 59, 999);
  const [clientsByStatus, overdueReminders, todayReminders, availableProperties] = await Promise.all([
    prisma.client.groupBy({ by: ["status"], where: { brokerageId: me.brokerageId }, _count: true }),
    prisma.reminder.count({
      where: { brokerageId: me.brokerageId, assignedToId: me.id, status: "PENDING", dueAt: { lt: now } },
    }),
    prisma.reminder.count({
      where: {
        brokerageId: me.brokerageId,
        assignedToId: me.id,
        status: "PENDING",
        dueAt: { gte: now, lte: endOfToday },
      },
    }),
    prisma.property.count({ where: { brokerageId: me.brokerageId, availability: "AVAILABLE" } }),
  ]);

  res.json({
    generatedAt: now.toISOString(),
    rent: board("RENT"),
    buy: board("BUY"),
    clientsByStatus: Object.fromEntries(clientsByStatus.map((g) => [g.status, g._count])),
    reminders: { overdue: overdueReminders, dueToday: todayReminders },
    availableProperties,
  });
});
