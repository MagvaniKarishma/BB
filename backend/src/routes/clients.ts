import { Router } from "express";
import { Prisma } from "@prisma/client";
import { prisma } from "../db.js";
import { currentUser, requireRole } from "../lib/auth.js";
import { HttpError, badRequest, notFound } from "../lib/errors.js";
import { normalizePhone } from "../lib/phone.js";
import {
  addNoteSchema,
  addPhoneSchema,
  createClientSchema,
  createInquirySchema,
  listClientsSchema,
  updateClientSchema,
} from "../schemas.js";
import { assertMember } from "./team.js";
import { addPhoneChecked, createClientChecked, duplicateError, findByPhones, invalidPhone } from "../services/clients.js";
import { createInquiry } from "./inquiries.js";

export const clientsRouter = Router();

/** Client list tabs: statuses grouped the way agents talk about them. */
const GROUPS = {
  new: { status: { in: ["NEW"] } },
  active: { status: { in: ["CONTACTED", "SITE_VISIT", "NEGOTIATION"] } },
  lost: { status: { in: ["CLOSED_LOST"] } },
} satisfies Record<string, Prisma.ClientWhereInput>;

/** End of "today" in the agent's time zone (offset in minutes, e.g. 330 for India). */
const endOfToday = (tzMinutes: number) => {
  const local = new Date(Date.now() + tzMinutes * 60_000);
  local.setUTCHours(23, 59, 59, 999);
  return new Date(local.getTime() - tzMinutes * 60_000);
};

clientsRouter.get("/", async (req, res) => {
  const me = currentUser(req);
  const q = listClientsSchema.parse(req.query);
  const b = me.brokerageId;
  const followUpWhere: Prisma.ClientWhereInput = { reminders: { some: { status: "PENDING", dueAt: { lte: endOfToday(q.tz) } } } };
  const base: Prisma.ClientWhereInput = {
    brokerageId: b,
    status: q.status,
    leadSource: q.leadSource,
    assignedToId: q.assignedToId,
  };
  if (q.q) {
    const digits = q.q.replace(/\D/g, "");
    base.OR = [
      { name: { contains: q.q, mode: "insensitive" } },
      ...(digits.length >= 3 ? [{ phones: { some: { e164: { contains: digits } } } }] : []),
    ];
  }
  const groupWhere = (g: typeof q.group): Prisma.ClientWhereInput =>
    g === "followup" ? followUpWhere : g && g !== "all" ? GROUPS[g] : {};
  const where: Prisma.ClientWhereInput = { AND: [base, groupWhere(q.group)] };

  const [total, clients, groupCounts] = await Promise.all([
    prisma.client.count({ where }),
    prisma.client.findMany({
      where,
      orderBy: { updatedAt: "desc" },
      skip: (q.page - 1) * q.pageSize,
      take: q.pageSize,
      include: {
        assignedTo: { select: { id: true, name: true } },
        _count: { select: { inquiries: { where: { status: "ACTIVE" } } } },
        // The latest active requirement, for the one-line summary ("2 BHK • Rent • Andheri West").
        inquiries: {
          where: { status: "ACTIVE" },
          orderBy: { updatedAt: "desc" },
          take: 1,
          select: { id: true, transactionType: true, category: true, locations: true, budgetMin: true, budgetMax: true },
        },
        reminders: { where: { status: "PENDING" }, orderBy: { dueAt: "asc" }, take: 1, select: { dueAt: true } },
      },
    }),
    // Counts for the tabs, with the search/filters applied but not the tab itself.
    Promise.all(
      (["all", "new", "active", "followup", "lost"] as const).map(async (g) => [g, await prisma.client.count({ where: { AND: [base, groupWhere(g)] } })] as const),
    ).then(Object.fromEntries),
  ]);
  const dueBy = endOfToday(q.tz);
  res.json({
    total,
    page: q.page,
    pageSize: q.pageSize,
    groupCounts,
    clients: clients.map(({ _count, inquiries, reminders, ...c }) => ({
      ...c,
      activeInquiries: _count.inquiries,
      requirement: inquiries[0] ?? null,
      nextFollowUpAt: reminders[0]?.dueAt ?? null,
      followUpDue: reminders[0] != null && reminders[0].dueAt <= dueBy,
    })),
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
  const client = await createClientChecked(me, body);
  res.status(201).json({ client });
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
      // Portal enquiries: which 99acres / Housing.com listings they asked about, and when.
      portalLeads: {
        orderBy: { enquiredAt: "desc" },
        include: { listing: { select: { id: true, title: true, locality: true, portal: true, url: true } } },
      },
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
  const phone = await addPhoneChecked(me.brokerageId, client.id, body.phone, body.label ?? null);
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

/** Conversation notes, newest first. */
clientsRouter.get("/:id/notes", async (req, res) => {
  const me = currentUser(req);
  const client = await loadClient(me.brokerageId, req.params.id);
  const notes = await prisma.clientNote.findMany({
    where: { clientId: client.id },
    orderBy: { createdAt: "desc" },
    include: { author: { select: { id: true, name: true } } },
    take: 200,
  });
  res.json({ notes });
});

clientsRouter.post("/:id/notes", async (req, res) => {
  const me = currentUser(req);
  const body = addNoteSchema.parse(req.body);
  const client = await loadClient(me.brokerageId, req.params.id);
  const note = await prisma.clientNote.create({
    data: { brokerageId: me.brokerageId, clientId: client.id, authorId: me.id, body: body.body, source: body.source },
    include: { author: { select: { id: true, name: true } } },
  });
  // Touch the client so it surfaces at the top of recently-active lists.
  await prisma.client.update({ where: { id: client.id }, data: { updatedAt: new Date() } });
  res.status(201).json({ note });
});
