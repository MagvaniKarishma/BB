import { Router } from "express";
import bcrypt from "bcryptjs";
import { prisma } from "../db.js";
import { currentUser, requireAuth, signToken } from "../lib/auth.js";
import { HttpError } from "../lib/errors.js";
import { normalizePhone } from "../lib/phone.js";
import { loginSchema, registerSchema } from "../schemas.js";

export const authRouter = Router();

const publicUser = (u: { id: string; name: string; email: string; role: string; brokerageId: string; phone: string | null }) => ({
  id: u.id,
  name: u.name,
  email: u.email,
  phone: u.phone,
  role: u.role,
  brokerageId: u.brokerageId,
});

/** Creates a new brokerage with the registering user as its OWNER. */
authRouter.post("/register", async (req, res) => {
  const body = registerSchema.parse(req.body);
  const existing = await prisma.user.findUnique({ where: { email: body.email } });
  if (existing) throw new HttpError(409, "EMAIL_TAKEN", "An account with this email already exists");
  const phone = body.phone ? normalizePhone(body.phone) : null;
  const passwordHash = await bcrypt.hash(body.password, 10);
  const user = await prisma.$transaction(async (tx) => {
    const brokerage = await tx.brokerage.create({ data: { name: body.brokerageName } });
    return tx.user.create({
      data: {
        brokerageId: brokerage.id,
        name: body.name,
        email: body.email,
        phone,
        passwordHash,
        role: "OWNER",
      },
    });
  });
  res.status(201).json({ token: signToken(user.id), user: publicUser(user) });
});

authRouter.post("/login", async (req, res) => {
  const body = loginSchema.parse(req.body);
  const user = await prisma.user.findUnique({ where: { email: body.email } });
  const ok = user && user.active && (await bcrypt.compare(body.password, user.passwordHash));
  if (!ok) throw new HttpError(401, "INVALID_CREDENTIALS", "Incorrect email or password");
  res.json({ token: signToken(user.id), user: publicUser(user) });
});

authRouter.get("/me", requireAuth, async (req, res) => {
  const me = currentUser(req);
  const user = await prisma.user.findUniqueOrThrow({
    where: { id: me.id },
    include: { brokerage: { select: { id: true, name: true } } },
  });
  res.json({ user: publicUser(user), brokerage: user.brokerage });
});

export { publicUser };
