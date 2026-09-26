import type { NextFunction, Request, Response } from "express";
import jwt from "jsonwebtoken";
import type { Role } from "@prisma/client";
import { config } from "../config.js";
import { prisma } from "../db.js";
import { HttpError, forbidden } from "./errors.js";

export interface AuthUser {
  id: string;
  brokerageId: string;
  role: Role;
  name: string;
}

declare global {
  // eslint-disable-next-line @typescript-eslint/no-namespace
  namespace Express {
    interface Request {
      user?: AuthUser;
    }
  }
}

export function signToken(userId: string): string {
  return jwt.sign({ sub: userId }, config.jwtSecret, {
    expiresIn: config.jwtTtl as jwt.SignOptions["expiresIn"],
  });
}

const unauthorized = () => new HttpError(401, "UNAUTHORIZED", "Authentication required");

export async function requireAuth(req: Request, _res: Response, next: NextFunction) {
  const header = req.header("authorization");
  if (!header?.startsWith("Bearer ")) return next(unauthorized());
  let userId: string;
  try {
    const payload = jwt.verify(header.slice(7), config.jwtSecret);
    if (typeof payload === "string" || typeof payload.sub !== "string") return next(unauthorized());
    userId = payload.sub;
  } catch {
    return next(unauthorized());
  }
  // Re-read the user so deactivated members and role changes take effect immediately.
  const user = await prisma.user.findUnique({ where: { id: userId } });
  if (!user || !user.active) return next(unauthorized());
  req.user = { id: user.id, brokerageId: user.brokerageId, role: user.role, name: user.name };
  next();
}

export function requireRole(...roles: Role[]) {
  return (req: Request, _res: Response, next: NextFunction) => {
    if (!req.user || !roles.includes(req.user.role)) return next(forbidden());
    next();
  };
}

/** Narrowing helper for handlers mounted behind requireAuth. */
export function currentUser(req: Request): AuthUser {
  if (!req.user) throw unauthorized();
  return req.user;
}
