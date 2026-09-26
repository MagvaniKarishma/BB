import { Router } from "express";
import { Prisma } from "@prisma/client";
import { prisma } from "../db.js";
import { currentUser, requireRole } from "../lib/auth.js";
import { HttpError, badRequest, notFound } from "../lib/errors.js";
import { normalizePhone } from "../lib/phone.js";
import {
  addPhoneSchema,
  createClientSchema,
  createInquirySchema,
  listClientsSchema,
  updateClientSchema,
} from "../schemas.js";
import { assertMember } from "./team.js";
import { createInquiry } from "./inquiries.js";

export const clientsRouter = Router();

const invalidPhone = (raw: string) =>
  new HttpError(400, "INVALID_PHONE", `"${raw}" is not a valid phone number`);

const duplicateError = (client: { id: string; name: string; primaryPhone: string }, matchedOn: string) =>
  new HttpError(409, "DUPLICATE_CLIENT", `A client with this ${matchedOn} already exists: ${client.name}`, {
    existingClient: { id: client.id, name: client.name, primaryPhone: client.primaryPhone },
    matchedOn,
  });

/** Finds an existing client in the brokerage owning any of these numbers. */
async function findByPhones(brokerageId: string, e164s: string[]) {
  const hit = await prisma.clientPhone.findFirst({
    where: { brokerageId, e164: { in: e164s } },
    include: { client: true },
  });
  return hit?.client ?? null;
}

clientsRouter.get("/", async (req, res) => {
  const me = currentUser(req);
  const q = listClientsSchema.parse(req.query);
  const where: Prisma.ClientWhereInput = {
    brokerageId: me.brokerageId,
    status: q.status,
    leadSource: q.leadSource,
    assignedToId: q.assignedToId,
  };
  if (q.q) {
    const digits = q.q.replace(/\D/g, "");
    where.OR = [
      { name: { contains: q.q, mode: "insensitive" } },
      ...(digits.length >= 3 ? [{ phones: { some: { e164: { contains: digits } } } }] : []),
    ];
  }
  const [total, clients] = await Promise.all([
    prisma.client.count({ where }),
    prisma.client.findMany({
      where,
      orderBy: { updatedAt: "desc" },
      skip: (q.page - 1) * q.pageSize,
      take: q.pageSize,
      include: {
        assignedTo: { select: { id: true, name: true } },
        _count: { select: { inquiries: { where: { status: "ACTIVE" } } } },
      },
    }),
  ]);
  res.json({
    total,
    page: q.page,
    pageSize: q.pageSize,
    clients: clients.map(({ _count, ...c }) => ({ ...c, activeInquiries: _count.inquiries })),
  });
});

/** Pre-flight check the app uses while the broker types a number. */
clientsRouter.get("/check-duplicate", async (req, res) => {
  const me = currentUser(req);
  const raw = String(req.query.phone ?? "");
  const e164 = normalizePhone(raw);
  if (!e164) throw invalidPhone(raw);
  const client = await findByPhones(me.brokerageId, [e164]);
  res.json({
    normalized: e164,
    duplicate: client ? { id: client.id, name: client.name, primaryPhone: client.primaryPhone } : null,
  });
});

/**
 * Caller-ID lookup: who is this number and what are they looking for?
 * Returns { client: null } for unknown numbers — nothing is guessed.
 */
clientsRouter.get("/lookup", async (req, res) => {
  const me = currentUser(req);
  const e164 = normalizePhone(String(req.query.phone ?? ""));
  if (!e164) {
    res.json({ client: null });
    return;
  }
  const phone = await prisma.clientPhone.findUnique({
    where: { brokerageId_e164: { brokerageId: me.brokerageId, e164 } },
    include: {
      client: {
        include: {
          inquiries: { where: { status: "ACTIVE" }, orderBy: { updatedAt: "desc" } },
          assignedTo: { select: { id: true, name: true } },
          reminders: { where: { status: "PENDING" }, orderBy: { dueAt: "asc" }, take: 3 },
        },
      },
    },
  });
  res.json({ client: phone?.client ?? null });
});

clientsRouter.post("/", async (req, res) => {
  const me = currentUser(req);
  const body = createClientSchema.parse(req.body);

  const rawPhones = [body.phone, ...body.altPhones];
  const e164s: string[] = [];
  for (const raw of rawPhones) {
    const n = normalizePhone(raw);
    if (!n) throw invalidPhone(raw);
    if (!e164s.includes(n)) e164s.push(n);
  }

  const byPhone = await findByPhones(me.brokerageId, e164s);
  if (byPhone) throw duplicateError(byPhone, "phone number");
  if (body.email) {
    const byEmail = await prisma.client.findFirst({ where: { brokerageId: me.brokerageId, email: body.email } });
    if (byEmail) throw duplicateError(byEmail, "email");
  }
  if (body.assignedToId) await assertMember(me.brokerageId, body.assignedToId);

  try {
    const client = await prisma.client.create({
      data: {
        brokerageId: me.brokerageId,
        name: body.name,
        primaryPhone: e164s[0],
        email: body.email ?? null,
        leadSource: body.leadSource,
        status: body.status,
        notes: body.notes ?? null,
        assignedToId: body.assignedToId ?? me.id,
        phones: {
          create: e164s.map((e164, i) => ({
            brokerageId: me.brokerageId,
            e164,
            label: i === 0 ? "primary" : null,
          })),
        },
      },
      include: { phones: true },
    });
    res.status(201).json({ client });
  } catch (err) {
    // Concurrent create with the same number: the unique index is the final arbiter.
    if (err instanceof Prisma.PrismaClientKnownRequestError && err.code === "P2002") {
      const existing = await findByPhones(me.brokerageId, e164s);
      if (existing) throw duplicateError(existing, "phone number");
    }
    throw err;
  }
});

async function loadClient(brokerageId: string, id: string) {
  const client = await prisma.client.findFirst({ where: { id, brokerageId } });
  if (!client) throw notFound("Client");
  return client;
}

clientsRouter.get("/:id", async (req, res) => {
  const me = currentUser(req);
  const client = await prisma.client.findFirst({
    where: { id: req.params.id, brokerageId: me.brokerageId },
    include: {
      phones: true,
      assignedTo: { select: { id: true, name: true } },
      inquiries: { orderBy: [{ status: "asc" }, { updatedAt: "desc" }] },
      reminders: { where: { status: "PENDING" }, orderBy: { dueAt: "asc" } },
    },
  });
  if (!client) throw notFound("Client");
  res.json({ client });
});

clientsRouter.patch("/:id", async (req, res) => {
  const me = currentUser(req);
  const body = updateClientSchema.parse(req.body);
  const client = await loadClient(me.brokerageId, req.params.id);
  if (body.assignedToId) await assertMember(me.brokerageId, body.assignedToId);
  if (body.email) {
    const byEmail = await prisma.client.findFirst({
      where: { brokerageId: me.brokerageId, email: body.email, id: { not: client.id } },
    });
    if (byEmail) throw duplicateError(byEmail, "email");
  }
  const updated = await prisma.client.update({ where: { id: client.id }, data: body });
  res.json({ client: updated });
});

clientsRouter.delete("/:id", requireRole("OWNER", "ADMIN"), async (req, res) => {
  const me = currentUser(req);
  const client = await loadClient(me.brokerageId, String(req.params.id));
  await prisma.client.delete({ where: { id: client.id } });
  res.status(204).end();
});

clientsRouter.post("/:id/phones", async (req, res) => {
  const me = currentUser(req);
  const body = addPhoneSchema.parse(req.body);
  const client = await loadClient(me.brokerageId, req.params.id);
  const e164 = normalizePhone(body.phone);
  if (!e164) throw invalidPhone(body.phone);
  const owner = await findByPhones(me.brokerageId, [e164]);
  if (owner) {
    if (owner.id === client.id) throw badRequest("This number is already on this client");
    throw duplicateError(owner, "phone number");
  }
  const phone = await prisma.clientPhone.create({
    data: { brokerageId: me.brokerageId, clientId: client.id, e164, label: body.label ?? null },
  });
  res.status(201).json({ phone });
});

clientsRouter.delete("/:id/phones/:phoneId", async (req, res) => {
  const me = currentUser(req);
  const client = await loadClient(me.brokerageId, req.params.id);
  const phone = await prisma.clientPhone.findFirst({ where: { id: req.params.phoneId, clientId: client.id } });
  if (!phone) throw notFound("Phone");
  if (phone.e164 === client.primaryPhone) throw badRequest("Cannot remove the primary phone number");
  await prisma.clientPhone.delete({ where: { id: phone.id } });
  res.status(204).end();
});

clientsRouter.post("/:id/inquiries", async (req, res) => {
  const me = currentUser(req);
  const body = createInquirySchema.parse(req.body);
  const client = await loadClient(me.brokerageId, req.params.id);
  const inquiry = await createInquiry(me, client.id, body);
  res.status(201).json({ inquiry });
});
