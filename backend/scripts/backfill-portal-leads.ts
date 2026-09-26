/**
 * One-time backfill after upgrading: 99acres / Housing.com enquiries received over WhatsApp
 * BEFORE portal leads existed are added as portal leads, so they appear on the portal screens.
 *
 * - Uses what was already read from each message (no links opened, nothing re-analysed).
 * - Links a lead to a client only when the message is already linked, or the phone number
 *   belongs to an existing client. It never creates clients.
 * - Safe to run more than once (each message maps to one lead).
 *
 *   npx tsx scripts/backfill-portal-leads.ts           # dry run: counts only
 *   npx tsx scripts/backfill-portal-leads.ts --apply   # write
 */
import type { PropertyCategory } from "@prisma/client";
import { prisma } from "../src/db.js";
import { brokerageActor, isPortal, recordLead } from "../src/services/portalLeads.js";
import type { Extraction } from "../src/voice/draft.js";
import type { PortalLead } from "../src/whatsapp/portalLeads.js";

export async function backfillPortalLeads(apply: boolean) {
  const messages = await prisma.whatsAppMessage.findMany({
    where: { direction: "INBOUND", portal: { in: ["ACRES_99", "HOUSING_COM"] }, processedAt: { not: null } },
    orderBy: { sentAt: "asc" },
  });
  let added = 0, existing = 0;
  for (const m of messages) {
    const already = await prisma.portalLead.findFirst({ where: { brokerageId: m.brokerageId, dedupeKey: `wa:${m.id}` } });
    if (already) { existing++; continue; }
    if (!apply) { added++; continue; }
    const p = m.portalLead as unknown as PortalLead | null;
    if (!p || !isPortal(m.portal)) continue;
    const draft = (m.extraction as unknown as Extraction | null)?.draft;
    const facts = p.listingFacts ?? {};
    const out = await recordLead(await brokerageActor(m.brokerageId), {
      portal: m.portal,
      channel: "WHATSAPP",
      sourceRef: m.id,
      dedupeKey: `wa:${m.id}`,
      enquiredAt: m.sentAt,
      name: m.leadName,
      phone: m.leadPhone,
      email: p.leadEmail,
      message: p.leadMessage ?? m.text,
      budgetMin: draft?.budgetMin?.value ?? null,
      budgetMax: draft?.budgetMax?.value ?? null,
      listing: {
        externalId: p.listingRef,
        url: p.listingPage?.url ?? p.listingUrl,
        title: p.listingTitle,
        transactionType: facts.transactionType ?? draft?.transactionType?.value ?? null,
        // Older messages predate listingFacts: fall back to what the analysis read from the message text.
        category: (facts.category as PropertyCategory | undefined) ?? draft?.category?.value ?? null,
        locality: facts.locality ?? p.listingLocality?.value ?? draft?.locations?.[0]?.value,
        price: p.listingPrice?.value ?? null,
      },
      clientId: m.clientId,
      createClient: false,
      raw: { text: m.text, backfilled: true },
    });
    if (out.created) added++; else existing++;
  }
  return { messages: messages.length, added, existing };
}

const isMain = process.argv[1]?.endsWith("backfill-portal-leads.ts");
if (isMain) {
  const apply = process.argv.includes("--apply");
  backfillPortalLeads(apply)
    .then((r) => console.log(`${apply ? "Backfilled" : "Dry run —"} ${r.added} lead(s) to add, ${r.existing} already present, from ${r.messages} portal message(s).`))
    .finally(() => prisma.$disconnect());
}
