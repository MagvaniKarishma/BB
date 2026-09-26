import { Prisma, type Client } from "@prisma/client";
import type { z } from "zod";
import { prisma } from "../db.js";
import type { AuthUser } from "../lib/auth.js";
import { HttpError, badRequest } from "../lib/errors.js";
import { normalizePhone } from "../lib/phone.js";
import type { createClientSchema } from "../schemas.js";
import { assertMember } from "../routes/team.js";

/**
 * The single place clients are created. Every path — the app form, the caller screen,
 * WhatsApp and portal leads — goes through these checks, so duplicate profiles can't
 * be created by one channel that another would have caught.
 */

export const invalidPhone = (raw: string) =>
  new HttpError(400, "INVALID_PHONE", `"${raw}" is not a valid phone number`);

export const duplicateError = (client: { id: string; name: string; primaryPhone: string }, matchedOn: string) =>
  new HttpError(409, "DUPLICATE_CLIENT", `A client with this ${matchedOn} already exists: ${client.name}`, {
    existingClient: { id: client.id, name: client.name, primaryPhone: client.primaryPhone },
    matchedOn,
  });

/** Finds an existing client in the brokerage owning any of these numbers. */
export async function findByPhones(brokerageId: string, e164s: string[]): Promise<Client | null> {
  const hit = await prisma.clientPhone.findFirst({
    where: { brokerageId, e164: { in: e164s } },
    include: { client: true },
  });
  return hit?.client ?? null;
}

export type CreateClientInput = z.infer<typeof createClientSchema>;

/** Creates a client or throws DUPLICATE_CLIENT (409) naming the existing profile. */
export async function createClientChecked(me: AuthUser, body: CreateClientInput) {
  const e164s: string[] = [];
  for (const raw of [body.phone, ...body.altPhones]) {
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
    return await prisma.client.create({
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
          create: e164s.map((e164, i) => ({ brokerageId: me.brokerageId, e164, label: i === 0 ? "primary" : null })),
        },
      },
      include: { phones: true },
    });
  } catch (err) {
    // Concurrent create with the same number: the unique index is the final arbiter.
    if (err instanceof Prisma.PrismaClientKnownRequestError && err.code === "P2002") {
      const existing = await findByPhones(me.brokerageId, e164s);
      if (existing) throw duplicateError(existing, "phone number");
    }
    throw err;
  }
}

/** Adds a number to a client unless another client already owns it. */
export async function addPhoneChecked(brokerageId: string, clientId: string, raw: string, label: string | null) {
  const e164 = normalizePhone(raw);
  if (!e164) throw invalidPhone(raw);
  const owner = await findByPhones(brokerageId, [e164]);
  if (owner) {
    if (owner.id === clientId) throw badRequest("This number is already on this client");
    throw duplicateError(owner, "phone number");
  }
  return prisma.clientPhone.create({ data: { brokerageId, clientId, e164, label } });
}
