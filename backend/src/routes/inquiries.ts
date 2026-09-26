import { Router } from "express";
import type { Inquiry, Prisma, RequirementSource } from "@prisma/client";
import type { z } from "zod";
import { prisma } from "../db.js";
import { type AuthUser, currentUser } from "../lib/auth.js";
import { badRequest, notFound } from "../lib/errors.js";
import { diffSnapshots, snapshotOf } from "../domain/requirementHistory.js";
import { evaluateMatch, rankMatches } from "../domain/matching.js";
import { createInquirySchema, listInquiriesSchema, updateInquirySchema } from "../schemas.js";
import { withPhotoIds } from "../services/photos.js";
import { matchCounts } from "../services/matching.js";

export const inquiriesRouter = Router();

const toBig = (v: number | null | undefined) => (v == null ? v : BigInt(v));

type CreateBody = z.infer<typeof createInquirySchema>;
type UpdatePatch = Omit<z.infer<typeof updateInquirySchema>, "source">;

/** Creates an inquiry and its version-1 revision inside the given transaction. */
export async function createInquiryTx(
  tx: Prisma.TransactionClient,
  me: AuthUser,
  clientId: string,
  body: CreateBody,
  voiceNoteId?: string,
): Promise<Inquiry> {
  const { source, ...fields } = body;
  const inquiry = await tx.inquiry.create({
    data: {
      ...fields,
      budgetMin: toBig(fields.budgetMin),
      budgetMax: toBig(fields.budgetMax),
      brokerageId: me.brokerageId,
      clientId,
      source,
    },
  });
  const snapshot = snapshotOf(inquiry);
  await tx.inquiryRevision.create({
    data: {
      inquiryId: inquiry.id,
      version: 1,
      changedById: me.id,
      source,
      voiceNoteId: voiceNoteId ?? null,
      changes: snapshot as Prisma.InputJsonObject,
      snapshot: snapshot as Prisma.InputJsonObject,
    },
  });
  return inquiry;
}

export function createInquiry(me: AuthUser, clientId: string, body: CreateBody): Promise<Inquiry> {
  return prisma.$transaction((tx) => createInquiryTx(tx, me, clientId, body));
}

/**
 * Applies a partial requirement update inside the given transaction. The previous
 * state is never overwritten silently: each change appends an InquiryRevision with a
 * field-level diff and the full resulting snapshot. Optimistic concurrency on `version`.
 */
export async function updateInquiryTx(
  tx: Prisma.TransactionClient,
  me: AuthUser,
  current: Inquiry,
  patch: UpdatePatch,
  source: RequirementSource,
  voiceNoteId?: string,
): Promise<{ inquiry: Inquiry; changed: boolean }> {
  const merged = {
    budgetMin: patch.budgetMin !== undefined ? patch.budgetMin : current.budgetMin == null ? null : Number(current.budgetMin),
    budgetMax: patch.budgetMax !== undefined ? patch.budgetMax : current.budgetMax == null ? null : Number(current.budgetMax),
    floorMin: patch.floorMin !== undefined ? patch.floorMin : current.floorMin,
    floorMax: patch.floorMax !== undefined ? patch.floorMax : current.floorMax,
  };
  if (merged.budgetMin != null && merged.budgetMax != null && merged.budgetMin > merged.budgetMax) {
    throw badRequest("budgetMin cannot exceed budgetMax");
  }
  if (merged.floorMin != null && merged.floorMax != null && merged.floorMin > merged.floorMax) {
    throw badRequest("floorMin cannot exceed floorMax");
  }

  const data: Prisma.InquiryUpdateInput = {
    ...patch,
    budgetMin: toBig(patch.budgetMin),
    budgetMax: toBig(patch.budgetMax),
  };
  const candidate = { ...current, ...stripUndefined(data) } as Inquiry;
  const changes = diffSnapshots(snapshotOf(current), snapshotOf(candidate));
  if (Object.keys(changes).length === 0) return { inquiry: current, changed: false };

  const version = current.version + 1;
  const { count } = await tx.inquiry.updateMany({
    where: { id: current.id, version: current.version },
    data: { ...(data as Prisma.InquiryUpdateManyMutationInput), version, source },
  });
  if (count === 0) throw badRequest("Inquiry was modified concurrently; reload and try again");
  const inquiry = await tx.inquiry.findUniqueOrThrow({ where: { id: current.id } });
  await tx.inquiryRevision.create({
    data: {
      inquiryId: inquiry.id,
      version,
      changedById: me.id,
      source,
      voiceNoteId: voiceNoteId ?? null,
      changes: changes as Prisma.InputJsonObject,
      snapshot: snapshotOf(inquiry) as Prisma.InputJsonObject,
    },
  });
  return { inquiry, changed: true };
}

async function loadInquiry(brokerageId: string, id: string) {
  const inquiry = await prisma.inquiry.findFirst({ where: { id, brokerageId } });
  if (!inquiry) throw notFound("Inquiry");
  return inquiry;
}

/** Drill-down list behind the dashboard tiles. */
inquiriesRouter.get("/", async (req, res) => {
  const me = currentUser(req);
  const q = listInquiriesSchema.parse(req.query);
  let inquiries = await prisma.inquiry.findMany({
    where: {
      brokerageId: me.brokerageId,
      transactionType: q.transactionType,
      category: q.category,
      status: q.status ?? "ACTIVE",
    },
    orderBy: { updatedAt: "desc" },
    include: { client: { select: { id: true, name: true, primaryPhone: true, status: true } } },
    take: 500,
  });
  if (q.q) {
    const needle = q.q.toLowerCase();
    inquiries = inquiries.filter(
      (i) => i.client.name.toLowerCase().includes(needle) || i.locations.some((l) => l.toLowerCase().includes(needle)),
    );
  }
  const counts = await matchCounts(me.brokerageId, inquiries.filter((i) => i.status === "ACTIVE"));
  res.json({ total: inquiries.length, inquiries: inquiries.map((i) => ({ ...i, matchCount: counts.get(i.id) ?? null })) });
});

inquiriesRouter.get("/:id", async (req, res) => {
  const me = currentUser(req);
  const inquiry = await prisma.inquiry.findFirst({
    where: { id: req.params.id, brokerageId: me.brokerageId },
    include: { client: { select: { id: true, name: true, primaryPhone: true, status: true } } },
  });
  if (!inquiry) throw notFound("Inquiry");
  res.json({ inquiry });
});

/** Updates a requirement (history is recorded by updateInquiryTx). */
inquiriesRouter.patch("/:id", async (req, res) => {
  const me = currentUser(req);
  const { source, ...patch } = updateInquirySchema.parse(req.body);
  const current = await loadInquiry(me.brokerageId, req.params.id);

  const result = await prisma.$transaction((tx) => updateInquiryTx(tx, me, current, patch, source));
  res.json(result);
});

function stripUndefined<T extends object>(o: T): Partial<T> {
  return Object.fromEntries(Object.entries(o).filter(([, v]) => v !== undefined)) as Partial<T>;
}

inquiriesRouter.get("/:id/history", async (req, res) => {
  const me = currentUser(req);
  const inquiry = await loadInquiry(me.brokerageId, req.params.id);
  const revisions = await prisma.inquiryRevision.findMany({
    where: { inquiryId: inquiry.id },
    orderBy: { version: "desc" },
    include: {
      changedBy: { select: { id: true, name: true } },
      voiceNote: { select: { id: true, transcript: true, language: true, createdAt: true } },
    },
  });
  res.json({ revisions });
});

/** Properties matching this requirement, best first, mandatory violations excluded. */
inquiriesRouter.get("/:id/matches", async (req, res) => {
  const me = currentUser(req);
  const inquiry = await loadInquiry(me.brokerageId, req.params.id);
  const budgetHardCap = inquiry.mandatory.includes("BUDGET") && inquiry.budgetMax != null;
  const candidates = await prisma.property.findMany({
    where: {
      brokerageId: me.brokerageId,
      transactionType: inquiry.transactionType,
      category: inquiry.category,
      availability: "AVAILABLE",
      ...(budgetHardCap ? { price: { lte: inquiry.budgetMax! } } : {}),
    },
    take: 2000,
  });
  const ranked = rankMatches(candidates, (p) => evaluateMatch(inquiry, p), (p) => p.price);
  const withPhotos = await withPhotoIds(ranked.map((r) => r.item));
  res.json({
    inquiryId: inquiry.id,
    matches: ranked.map(({ result }, i) => ({ property: withPhotos[i], ...result })),
  });
});
