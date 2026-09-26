import { Router } from "express";
import type { Prisma } from "@prisma/client";
import { prisma } from "../db.js";
import { type AuthUser, currentUser } from "../lib/auth.js";
import { badRequest, forbidden, notFound } from "../lib/errors.js";
import { createReminderSchema, listRemindersSchema, updateReminderSchema } from "../schemas.js";
import { assertMember } from "./team.js";

export const remindersRouter = Router();

const include = {
  client: { select: { id: true, name: true, primaryPhone: true } },
  assignedTo: { select: { id: true, name: true } },
} satisfies Prisma.ReminderInclude;

const isManager = (me: AuthUser) => me.role === "OWNER" || me.role === "ADMIN";

remindersRouter.get("/", async (req, res) => {
  const me = currentUser(req);
  const q = listRemindersSchema.parse(req.query);
  if (q.scope === "all" && !isManager(me)) throw forbidden("Only owners and admins can view all reminders");
  const reminders = await prisma.reminder.findMany({
    where: {
      brokerageId: me.brokerageId,
      assignedToId: q.scope === "mine" ? me.id : undefined,
      status: q.status,
      clientId: q.clientId,
      dueAt: { gte: q.from, lte: q.to },
    },
    orderBy: { dueAt: "asc" },
    include,
    take: 500,
  });
  res.json({ reminders });
});

remindersRouter.post("/", async (req, res) => {
  const me = currentUser(req);
  const body = createReminderSchema.parse(req.body);
  let clientId = body.clientId ?? null;
  if (body.inquiryId) {
    const inquiry = await prisma.inquiry.findFirst({ where: { id: body.inquiryId, brokerageId: me.brokerageId } });
    if (!inquiry) throw notFound("Inquiry");
    if (clientId && clientId !== inquiry.clientId) throw badRequest("Inquiry does not belong to this client");
    clientId = inquiry.clientId;
  }
  if (clientId) {
    const client = await prisma.client.findFirst({ where: { id: clientId, brokerageId: me.brokerageId } });
    if (!client) throw notFound("Client");
  }
  const assignedToId = body.assignedToId ?? me.id;
  if (assignedToId !== me.id) await assertMember(me.brokerageId, assignedToId);

  const reminder = await prisma.reminder.create({
    data: {
      brokerageId: me.brokerageId,
      clientId,
      inquiryId: body.inquiryId ?? null,
      assignedToId,
      createdById: me.id,
      dueAt: body.dueAt,
      title: body.title,
      note: body.note ?? null,
    },
    include,
  });
  res.status(201).json({ reminder });
});

async function loadOwnReminder(me: AuthUser, id: string) {
  const reminder = await prisma.reminder.findFirst({ where: { id, brokerageId: me.brokerageId } });
  if (!reminder) throw notFound("Reminder");
  if (reminder.assignedToId !== me.id && reminder.createdById !== me.id && !isManager(me)) {
    throw forbidden("This reminder belongs to another team member");
  }
  return reminder;
}

remindersRouter.patch("/:id", async (req, res) => {
  const me = currentUser(req);
  const body = updateReminderSchema.parse(req.body);
  const existing = await loadOwnReminder(me, req.params.id);
  if (body.assignedToId) await assertMember(me.brokerageId, body.assignedToId);
  const completedAt =
    body.status === "DONE" ? new Date() : body.status !== undefined ? null : existing.completedAt;
  const reminder = await prisma.reminder.update({
    where: { id: existing.id },
    data: { ...body, completedAt },
    include,
  });
  res.json({ reminder });
});

remindersRouter.delete("/:id", async (req, res) => {
  const me = currentUser(req);
  const existing = await loadOwnReminder(me, req.params.id);
  await prisma.reminder.delete({ where: { id: existing.id } });
  res.status(204).end();
});
