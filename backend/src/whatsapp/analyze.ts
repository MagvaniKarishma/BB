import type { VoiceLanguage } from "@prisma/client";
import type { Evidence, Extraction, RequirementDraft } from "../voice/draft.js";
import { extractWithRules } from "../voice/rulesExtractor.js";
import { extractRequirement } from "../voice/service.js";
import { getListingLookup } from "./listingLookup.js";
import { PORTAL_LABEL, type PortalLead, parsePortalLead } from "./portalLeads.js";

export interface MessageAnalysis {
  portalLead: PortalLead | null;
  extraction: Extraction;
}

const inr = (n: number) => `₹${n.toLocaleString("en-IN")}`;
const hasFields = (d: RequirementDraft) => Object.keys(d).length > 0;

/**
 * Reads a WhatsApp message (or shared text) for requirements.
 *
 * Portal leads are split into two sources:
 *  - the LISTING the lead enquired about → only its transaction type, property type and
 *    locality are proposed (flagged as coming from the listing); its advertised price is
 *    masked out and reported as `advertisedPrices`, never as a budget;
 *  - the LEAD'S OWN WORDS ("Message: …") → extracted normally; these win on conflicts.
 * Other messages go through the configured extractor (Claude or rules), which also keeps
 * asking prices ("price 65k", "listed at 1.2 cr") out of the budget.
 */
export async function analyzeMessage(text: string, language: VoiceLanguage = "AUTO"): Promise<MessageAnalysis> {
  const normalized = text.normalize("NFC");
  const portalLead = parsePortalLead(normalized);
  if (!portalLead) {
    return { portalLead: null, extraction: await extractRequirement(normalized, language) };
  }

  const warnings: string[] = [];
  const label = PORTAL_LABEL[portalLead.portal];

  // Listing attributes: everything except prices and the lead's quoted message.
  const mask = [...portalLead.priceSpans, ...(portalLead.leadMessageSpan ? [portalLead.leadMessageSpan] : [])];
  const listing = extractWithRules(normalized, { mask }).draft;
  const fromListing: RequirementDraft = {};
  if (listing.transactionType) fromListing.transactionType = listing.transactionType;
  else {
    const sale = /\bfor\s+sale\b|\bresale\b|\/buy\//i.exec(normalized);
    if (sale) fromListing.transactionType = { value: "BUY", evidence: sale[0] };
  }
  if (listing.category) fromListing.category = listing.category;
  if (listing.locations) fromListing.locations = listing.locations;
  // Sub-localities ("Veena Nagar") aren't in the lexicon; keep the area exactly as written.
  else if (portalLead.listingLocality) fromListing.locations = [portalLead.listingLocality];
  // Not in the message → open the listing link and read it from the listing page.
  const lookup = getListingLookup();
  if (!fromListing.transactionType && portalLead.listingUrl && lookup) {
    portalLead.listingPage = await lookup(portalLead.listingUrl);
    const found = portalLead.listingPage.transactionType;
    if (found) {
      fromListing.transactionType = found;
      warnings.push(
        `${found.value === "RENT" ? "For rent" : "For sale"} — read from the ${label} listing page ("${found.evidence}"), not from the message; confirm with the client`,
      );
    }
  }
  if (!fromListing.transactionType) {
    const p = portalLead.listingPrice?.value;
    // A hint only — never filled in: the agent confirms rent or buy with the client.
    const hint = p != null && p < 2_00_000 ? ` (${inr(p)} looks like a monthly rent)` : "";
    const page = portalLead.listingPage;
    const tried = page ? (page.status === "OK" ? "; the listing page doesn't say either" : `; couldn't read the listing page (${page.reason})`) : "";
    warnings.push(`The ${label} message doesn't say whether it's for rent or sale${hint}${tried} — confirm with the client`);
  }
  if (hasFields(fromListing)) {
    warnings.push(`Property type/area taken from the ${label} listing they enquired about — confirm it is what the client wants`);
  }

  // The lead's own words.
  let own: Extraction | null = null;
  if (portalLead.leadMessage) own = await extractRequirement(portalLead.leadMessage, language);

  const draft: RequirementDraft = { ...fromListing, ...(own?.draft ?? {}) };
  const advertisedPrices: Evidence<number>[] = [...(own?.advertisedPrices ?? [])];
  if (portalLead.listingPrice) {
    advertisedPrices.unshift(portalLead.listingPrice);
    warnings.push(
      `Advertised price ${inr(portalLead.listingPrice.value)} of the ${label} listing kept separate — it is not the client's budget`,
    );
  }
  if (!draft.budgetMax && !draft.budgetMin) warnings.push("The client's budget isn't stated — ask them");

  return {
    portalLead,
    extraction: {
      draft,
      advertisedPrices,
      warnings: [...warnings, ...(own?.warnings ?? [])],
      extractor: own?.extractor ?? "rules",
    },
  };
}
