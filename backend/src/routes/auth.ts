import { Router } from "express";
import bcrypt from "bcryptjs";
import { prisma } from "../db.js";
import { currentUser, requireAuth, signToken } from "../lib/auth.js";
import { HttpError } from "../lib/errors.js";
import { normalizePhone } from "../lib/phone.js";
import { loginSchema, otpRequestSchema, otpVerifySchema, registerSchema } from "../schemas.js";
import { invalidPhone } from "../services/clients.js";
import { OTP_RULES, requestOtp, verifyOtp } from "../auth/otp.js";
import { getOtpSender } from "../sms/sender.js";

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
  const phone = await userPhone(body.phone);
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

/** A user's phone: valid, E.164, and not already on another account (null when not given). */
export async function userPhone(raw: string | null | undefined, exceptUserId?: string): Promise<string | null> {
  if (!raw) return null;
  const phone = normalizePhone(raw);
  if (!phone) throw invalidPhone(raw);
  const owner = await prisma.user.findUnique({ where: { phone } });
  if (owner && owner.id !== exceptUserId) throw new HttpError(409, "PHONE_TAKEN", "This number is already used by another BrokerBuddy account");
  return phone;
}

// ---------- sign in with a mobile number (SMS code) ----------

/** Whether the app should offer mobile-number sign-in. */
authRouter.get("/otp", (_req, res) => {
  res.json({ enabled: getOtpSender() != null, digits: OTP_RULES.digits, resendAfterSec: OTP_RULES.resendAfterSeconds });
});

authRouter.post("/otp/request", async (req, res) => {
  const { phone } = otpRequestSchema.parse(req.body);
  const r = await requestOtp({ phone, purpose: "LOGIN", ip: req.ip ?? null });
  // Same answer whether or not the number has an account.
  res.json({ ...r, message: "If this number belongs to a BrokerBuddy account, a code is on its way." });
});

authRouter.post("/otp/verify", async (req, res) => {
  const body = otpVerifySchema.parse(req.body);
  const challenge = await verifyOtp({ phone: body.phone, code: body.code, purpose: "LOGIN" });
  const user = await prisma.user.findUnique({ where: { phone: challenge.phone } });
  if (!user || !user.active) throw new HttpError(401, "INVALID_CREDENTIALS", "This account can't sign in");
  res.json({ token: signToken(user.id), user: publicUser(user) });
});

// ---------- add / change your own mobile number (proved by SMS code) ----------

authRouter.post("/phone/request", requireAuth, async (req, res) => {
  const me = currentUser(req);
  const { phone } = otpRequestSchema.parse(req.body);
  await userPhone(phone, me.id); // fail early if invalid or someone else's
  res.json(await requestOtp({ phone, purpose: "VERIFY_PHONE", userId: me.id, ip: req.ip ?? null }));
});

authRouter.post("/phone/verify", requireAuth, async (req, res) => {
  const me = currentUser(req);
  const body = otpVerifySchema.parse(req.body);
  const challenge = await verifyOtp({ phone: body.phone, code: body.code, purpose: "VERIFY_PHONE", userId: me.id });
  const phone = await userPhone(challenge.phone, me.id);
  const user = await prisma.user.update({ where: { id: me.id }, data: { phone } });
  res.json({ user: publicUser(user) });
});

export { publicUser };
