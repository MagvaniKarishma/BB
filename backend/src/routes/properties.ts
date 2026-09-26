import { Router } from "express";
import type { Prisma } from "@prisma/client";
import { prisma } from "../db.js";
import { currentUser, requireRole } from "../lib/auth.js";
import { notFound } from "../lib/errors.js";
import { normalizePhone } from "../lib/phone.js";
import { evaluateMatch, rankMatches } from "../domain/matching.js";
import { createPropertySchema, listPropertiesSchema, updatePropertySchema } from "../schemas.js";

export const propertiesRouter = Router();

const toBig = (v: number | null | undefined) => (v == null ? v : BigInt(v));
// Keep the owner's number as typed if it can't be parsed — it's inventory data, not a client identity.
const ownerPhone = (v: string | null | undefined) => (v ? (normalizePhone(v) ?? v) : v);

propertiesRouter.get("/", async (req, res) => {
  const me = currentUser(req);
  const q = listPropertiesSchema.parse(req.query);
  const where: Prisma.PropertyWhereInput = {
    brokerageId: me.brokerageId,
    transactionType: q.transactionType,
    category: q.category,
    availability: q.availability,
  };
  if (q.q) {
    where.OR = [
      { title: { contains: q.q, mode: "insensitive" } },
      { locality: { contains: q.q, mode: "insensitive" } },
      { building: { contains: q.q, mode: "insensitive" } },
    ];
  }
  const [total, properties] = await Promise.all([
    prisma.property.count({ where }),
    prisma.property.findMany({
      where,
      orderBy: { updatedAt: "desc" },
      skip: (q.page - 1) * q.pageSize,
      take: q.pageSize,
    }),
  ]);
  res.json({ total, page: q.page, pageSize: q.pageSize, properties });
});

propertiesRouter.post("/", async (req, res) => {
  const me = currentUser(req);
  const body = createPropertySchema.parse(req.body);
  const property = await prisma.property.create({
    data: {
      ...body,
      price: BigInt(body.price),
      deposit: toBig(body.deposit),
      ownerPhone: ownerPhone(body.ownerPhone),
      brokerageId: me.brokerageId,
      listedById: me.id,
    },
  });
  res.status(201).json({ property });
});

async function loadProperty(brokerageId: string, id: string) {
  const property = await prisma.property.findFirst({ where: { id, brokerageId } });
  if (!property) throw notFound("Property");
  return property;
}

propertiesRouter.get("/:id", async (req, res) => {
  const me = currentUser(req);
  res.json({ property: await loadProperty(me.brokerageId, req.params.id) });
});

propertiesRouter.patch("/:id", async (req, res) => {
  const me = currentUser(req);
  const body = updatePropertySchema.parse(req.body);
  const existing = await loadProperty(me.brokerageId, req.params.id);
  const property = await prisma.property.update({
    where: { id: existing.id },
    data: {
      ...body,
      price: toBig(body.price) ?? undefined,
      deposit: toBig(body.deposit),
      ownerPhone: ownerPhone(body.ownerPhone),
    },
  });
  res.json({ property });
});

propertiesRouter.delete("/:id", requireRole("OWNER", "ADMIN"), async (req, res) => {
  const me = currentUser(req);
  const existing = await loadProperty(me.brokerageId, String(req.params.id));
  await prisma.property.delete({ where: { id: existing.id } });
  res.status(204).end();
});

/** Reverse matching: which active client inquiries fit this listing? */
propertiesRouter.get("/:id/matches", async (req, res) => {
  const me = currentUser(req);
  const property = await loadProperty(me.brokerageId, req.params.id);
  const inquiries = await prisma.inquiry.findMany({
    where: {
      brokerageId: me.brokerageId,
      transactionType: property.transactionType,
      category: property.category,
      status: "ACTIVE",
    },
    include: { client: { select: { id: true, name: true, primaryPhone: true, status: true } } },
    take: 2000,
  });
  const ranked = rankMatches(
    inquiries,
    (i) => evaluateMatch(i, property),
    () => 0n,
  );
  res.json({
    propertyId: property.id,
    matches: ranked.map(({ item, result }) => ({ inquiry: item, ...result })),
  });
});
