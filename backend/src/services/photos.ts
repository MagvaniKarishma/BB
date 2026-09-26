import { prisma } from "../db.js";

/** Adds `photoIds` (in display order) to properties, with one query for the whole list. */
export async function withPhotoIds<T extends { id: string }>(properties: T[]): Promise<(T & { photoIds: string[] })[]> {
  if (properties.length === 0) return [];
  const photos = await prisma.propertyPhoto.findMany({
    where: { propertyId: { in: properties.map((p) => p.id) } },
    select: { id: true, propertyId: true },
    orderBy: [{ position: "asc" }, { createdAt: "asc" }],
  });
  const byProperty = new Map<string, string[]>();
  for (const ph of photos) byProperty.set(ph.propertyId, [...(byProperty.get(ph.propertyId) ?? []), ph.id]);
  return properties.map((p) => ({ ...p, photoIds: byProperty.get(p.id) ?? [] }));
}

export const withPhotoId = async <T extends { id: string }>(p: T) => (await withPhotoIds([p]))[0];

/** The file's real type from its first bytes — the declared MIME type is not trusted. */
export function sniffImage(bytes: Buffer): "image/jpeg" | "image/png" | "image/webp" | null {
  if (bytes.length >= 3 && bytes[0] === 0xff && bytes[1] === 0xd8 && bytes[2] === 0xff) return "image/jpeg";
  if (bytes.length >= 8 && bytes.subarray(0, 8).equals(Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]))) return "image/png";
  if (bytes.length >= 12 && bytes.toString("ascii", 0, 4) === "RIFF" && bytes.toString("ascii", 8, 12) === "WEBP") return "image/webp";
  return null;
}
