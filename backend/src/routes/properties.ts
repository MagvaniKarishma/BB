import { Router } from "express";
import multer from "multer";
import type { Prisma } from "@prisma/client";
import { prisma } from "../db.js";
import { currentUser, requireRole } from "../lib/auth.js";
import { HttpError, badRequest, notFound } from "../lib/errors.js";
import { sniffImage, withPhotoId, withPhotoIds } from "../services/photos.js";
import { getBlobStore, newKey } from "../storage/blobStore.js";
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
  res.json({ total, page: q.page, pageSize: q.pageSize, properties: await withPhotoIds(properties) });
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
  res.status(201).json({ property: await withPhotoId(property) });
});

async function loadProperty(brokerageId: string, id: string) {
  const property = await prisma.property.findFirst({ where: { id, brokerageId } });
  if (!property) throw notFound("Property");
  return property;
}

propertiesRouter.get("/:id", async (req, res) => {
  const me = currentUser(req);
  res.json({ property: await withPhotoId(await loadProperty(me.brokerageId, String(req.params.id))) });
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
  res.json({ property: await withPhotoId(property) });
});

propertiesRouter.delete("/:id", requireRole("OWNER", "ADMIN"), async (req, res) => {
  const me = currentUser(req);
  const existing = await loadProperty(me.brokerageId, String(req.params.id));
  const photos = await prisma.propertyPhoto.findMany({ where: { propertyId: existing.id }, select: { storageKey: true } });
  await prisma.property.delete({ where: { id: existing.id } });
  await Promise.all(photos.map((p) => getBlobStore().delete(p.storageKey).catch(() => undefined)));
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

// ---------- photos ----------

export const MAX_PHOTOS = 12;
const upload = multer({
  storage: multer.memoryStorage(),
  limits: { fileSize: 5 * 1024 * 1024, files: 1 },
});

propertiesRouter.post("/:id/photos", upload.single("photo"), async (req, res) => {
  const me = currentUser(req);
  const property = await loadProperty(me.brokerageId, String(req.params.id));
  if (!req.file) throw badRequest("Attach the photo as the 'photo' field");
  const mimeType = sniffImage(req.file.buffer);
  if (!mimeType) throw new HttpError(415, "UNSUPPORTED_IMAGE", "Photos must be JPEG, PNG or WebP images");
  const count = await prisma.propertyPhoto.count({ where: { propertyId: property.id } });
  if (count >= MAX_PHOTOS) throw badRequest(`A property can have at most ${MAX_PHOTOS} photos`);
  // File first, then the row: a failed upload leaves no row pointing at nothing.
  const storageKey = newKey(me.brokerageId, "photos");
  await getBlobStore().put(storageKey, req.file.buffer);
  const photo = await prisma.propertyPhoto
    .create({
      data: { brokerageId: me.brokerageId, propertyId: property.id, mimeType, size: req.file.size, storageKey, position: count },
      select: { id: true },
    })
    .catch(async (err) => {
      await getBlobStore().delete(storageKey).catch(() => undefined);
      throw err;
    });
  await prisma.property.update({ where: { id: property.id }, data: { updatedAt: new Date() } });
  res.status(201).json({ photoId: photo.id, property: await withPhotoId(property) });
});

propertiesRouter.get("/:id/photos/:photoId", async (req, res) => {
  const me = currentUser(req);
  const photo = await prisma.propertyPhoto.findFirst({
    where: { id: String(req.params.photoId), propertyId: String(req.params.id), brokerageId: me.brokerageId },
  });
  if (!photo) throw notFound("Photo");
  const bytes = await getBlobStore().get(photo.storageKey);
  if (!bytes) throw notFound("Photo");
  res.setHeader("Content-Type", photo.mimeType);
  // Photos never change once uploaded (a new one gets a new id).
  res.setHeader("Cache-Control", "private, max-age=604800, immutable");
  res.send(bytes);
});

propertiesRouter.delete("/:id/photos/:photoId", async (req, res) => {
  const me = currentUser(req);
  const photo = await prisma.propertyPhoto.findFirst({
    where: { id: String(req.params.photoId), propertyId: String(req.params.id), brokerageId: me.brokerageId },
  });
  if (!photo) throw notFound("Photo");
  await prisma.propertyPhoto.delete({ where: { id: photo.id } });
  // Row first, then the file: a failed file delete leaves an orphan file, never a broken photo.
  await getBlobStore().delete(photo.storageKey).catch(() => undefined);
  res.status(204).end();
});
