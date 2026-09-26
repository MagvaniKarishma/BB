import { Router } from "express";
import bcrypt from "bcryptjs";
import { prisma } from "../db.js";
import { currentUser, requireRole } from "../lib/auth.js";
import { HttpError, badRequest, forbidden, notFound } from "../lib/errors.js";
import { normalizePhone } from "../lib/phone.js";
import { createMemberSchema, updateMemberSchema } from "../schemas.js";
import { publicUser } from "./auth.js";

export const teamRouter = Router();

teamRouter.get("/", async (req, res) => {
  const me = currentUser(req);
  const members = await prisma.user.findMany({
    where: { brokerageId: me.brokerageId },
    orderBy: [{ role: "asc" }, { name: "asc" }],
  });
  res.json({
    members: members.map((m) => ({ ...publicUser(m), active: m.active })),
  });
});

teamRouter.post("/", requireRole("OWNER", "ADMIN"), async (req, res) => {
  const me = currentUser(req);
  const body = createMemberSchema.parse(req.body);
  if (me.role === "ADMIN" && body.role !== "AGENT") throw forbidden("Admins can only add agents");
  const taken = await prisma.user.findUnique({ where: { email: body.email } });
  if (taken) throw new HttpError(409, "EMAIL_TAKEN", "An account with this email already exists");
  const member = await prisma.user.create({
    data: {
      brokerageId: me.brokerageId,
      name: body.name,
      email: body.email,
      phone: body.phone ? normalizePhone(body.phone) : null,
      role: body.role,
      passwordHash: await bcrypt.hash(body.password, 10),
    },
  });
  res.status(201).json({ member: { ...publicUser(member), active: member.active } });
});

teamRouter.patch("/:id", requireRole("OWNER", "ADMIN"), async (req, res) => {
  const me = currentUser(req);
  const body = updateMemberSchema.parse(req.body);
  const target = await prisma.user.findFirst({ where: { id: String(req.params.id), brokerageId: me.brokerageId } });
  if (!target) throw notFound("Member");
  if (target.role === "OWNER") throw forbidden("The owner cannot be modified");
  if (target.id === me.id) throw badRequest("You cannot change your own role or status");
  if (me.role === "ADMIN" && (target.role !== "AGENT" || body.role === "ADMIN")) {
    throw forbidden("Admins can only manage agents");
  }
  const member = await prisma.user.update({ where: { id: target.id }, data: body });
  res.json({ member: { ...publicUser(member), active: member.active } });
});

/** Throws unless `userId` is an active member of the brokerage. */
export async function assertMember(brokerageId: string, userId: string) {
  const user = await prisma.user.findFirst({ where: { id: userId, brokerageId, active: true } });
  if (!user) throw badRequest("Assignee is not an active member of this brokerage");
  return user;
}
