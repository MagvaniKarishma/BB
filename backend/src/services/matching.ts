import type { Inquiry } from "@prisma/client";
import { prisma } from "../db.js";
import { evaluateMatch } from "../domain/matching.js";

/** Number of available properties satisfying each inquiry's must-haves. */
export async function matchCounts(brokerageId: string, inquiries: Inquiry[]): Promise<Map<string, number>> {
  const counts = new Map<string, number>();
  if (inquiries.length === 0) return counts;
  const properties = await prisma.property.findMany({
    where: {
      brokerageId,
      availability: "AVAILABLE",
      OR: inquiries.map((i) => ({ transactionType: i.transactionType, category: i.category })),
    },
    take: 5000,
  });
  for (const i of inquiries) {
    counts.set(i.id, properties.filter((p) => evaluateMatch(i, p).eligible).length);
  }
  return counts;
}

