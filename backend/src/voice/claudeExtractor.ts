import Anthropic from "@anthropic-ai/sdk";
import { betaZodOutputFormat } from "@anthropic-ai/sdk/helpers/beta/zod";
import { z } from "zod/v4";
import type { Evidence, Extraction, RequirementDraft } from "./draft.js";
import { verifyDraft } from "./draft.js";

export const CLAUDE_MODEL = "claude-opus-5";

const withEvidence = <T extends z.ZodType>(value: T) =>
  z.object({ value, evidence: z.string() }).nullable();

/** Output schema. Every value must carry a verbatim quote from the transcript. */
const ExtractionSchema = z.object({
  transactionType: withEvidence(z.enum(["RENT", "BUY"])),
  category: withEvidence(
    z.enum(["STUDIO", "BHK_1", "BHK_2", "BHK_3", "BHK_4", "BHK_5_PLUS", "COMMERCIAL", "OTHER"]),
  ),
  budgetMinRupees: withEvidence(z.number()),
  budgetMaxRupees: withEvidence(z.number()),
  locations: z.array(z.object({ value: z.string(), evidence: z.string() })),
  furnishing: withEvidence(z.array(z.enum(["UNFURNISHED", "SEMI_FURNISHED", "FULLY_FURNISHED"]))),
  minParking: withEvidence(z.number().int()),
  floorPreference: withEvidence(z.array(z.enum(["LOWER", "MIDDLE", "HIGHER"]))),
  possession: withEvidence(z.enum(["READY_TO_MOVE", "UNDER_CONSTRUCTION"])),
  possessionBy: withEvidence(z.string()),
  warnings: z.array(z.string()),
});
type ClaudeOutput = z.infer<typeof ExtractionSchema>;

const SYSTEM_PROMPT = `You extract a Mumbai real-estate client's property requirement from a broker's voice-note transcript.
Transcripts may be in Hindi (Devanagari or romanised "Hinglish"), Marathi, English, or a mix.

Rules — the broker relies on these, so follow them exactly:
- Extract only what the speaker actually states. If something is not said, return null (or an empty list). Never infer, assume or fill in typical values.
- For every value, "evidence" must be copied verbatim from the transcript (the shortest phrase that states it, same script and spelling). Values without verbatim evidence are discarded.
- Money: convert to whole rupees. hazaar/हज़ार/हजार/k = 1,000; lakh/लाख/L = 1,00,000; crore/करोड़/कोटी/cr = 1,00,00,000. dedh/डेढ़/दीड = 1.5, dhai/ढाई/अडीच = 2.5, saadhe X = X + 0.5. For rent, the budget is monthly rent.
- "X se Y", "X to Y" is a range (min X, max Y). "X tak", "up to X", "X se zyada nahi" is a maximum. "kam se kam X", "X se upar" is a minimum. A lone budget amount is the maximum — add a warning saying you read it as the maximum.
- Ignore amounts for deposit, maintenance, brokerage, salary; mention them in warnings.
- The asking price or advertised rent of a specific property ("price 65k", "listed at 1.2 cr", "₹65,000/month" in a listing) is NOT the client's budget. Leave it out of the budget and mention it in warnings.
- Category: 1 RK or studio = STUDIO; N BHK/bedroom = BHK_N (5 or more = BHK_5_PLUS); shop/office/dukaan/godown = COMMERCIAL. If more than one size is mentioned as acceptable ("2 ya 3 BHK"), return null and warn.
- transactionType: rent/kiraya/किराया/भाड्याने = RENT; buy/kharidna/खरीदना/विकत = BUY. If both, return null and warn.
- locations: one entry per wanted locality, canonical English spelling (e.g. "Andheri West", "Powai", "Lower Parel"). Keep East/West if said. Skip places mentioned as not wanted (e.g. "Khar nahi chahiye") and warn.
- furnishing: list the acceptable types. A bare "furnished" means FULLY_FURNISHED — warn that you interpreted it.
- minParking: number of parking spots needed; "parking chahiye" = 1. If parking is explicitly not needed, return null.
- floorPreference: only LOWER, MIDDLE or HIGHER floors of the building. "high floor"/"upar wala floor"/"ऊंची मंजिल" = HIGHER; "low floor"/"neeche wala floor"/"ground floor" = LOWER; "middle floor"/"beech ka floor" = MIDDLE. For an exact floor number ("5th floor ke upar", "10th floor tak") return null and add a warning quoting it — never convert a floor number into a band.
- possessionBy: YYYY-MM-DD, last day of the stated month/year, only when a possession deadline is stated.
- Do not decide which requirements are mandatory. Put anything the broker should double-check in warnings (short, in English).`;

/** The minimal slice of the SDK client we use (lets tests substitute a fake). */
export type ClaudeClient = Pick<Anthropic, "beta">;

const LANGUAGE_HINT: Record<string, string> = {
  AUTO: "unknown",
  HINDI: "Hindi",
  HINGLISH: "Hinglish (Hindi written in Latin script, mixed with English)",
  MARATHI: "Marathi",
  ENGLISH: "English (Indian)",
};

export class ClaudeExtractor {
  readonly name = `claude:${CLAUDE_MODEL}`;

  constructor(private readonly client: ClaudeClient = new Anthropic()) {}

  async extract(transcript: string, language: string): Promise<Extraction> {
    const response = await this.client.beta.messages.parse({
      model: CLAUDE_MODEL,
      max_tokens: 16000,
      thinking: { type: "adaptive" },
      // Server-side refusal fallback: re-run on Anthropic's recommended model if declined.
      betas: ["server-side-fallback-2026-07-01"],
      fallbacks: "default",
      system: SYSTEM_PROMPT,
      messages: [
        {
          role: "user",
          content: `Language of the note: ${LANGUAGE_HINT[language] ?? "unknown"}\n\n<transcript>\n${transcript}\n</transcript>`,
        },
      ],
      output_config: { format: betaZodOutputFormat(ExtractionSchema) },
    });
    if (response.stop_reason === "refusal") throw new Error("The AI model declined to process this note");
    if (response.stop_reason === "max_tokens") throw new Error("The AI response was cut off");
    const parsed = response.parsed_output;
    if (!parsed) throw new Error("The AI response did not match the expected format");

    const { draft, warnings } = toDraft(parsed);
    const verified = verifyDraft(draft, transcript);
    return {
      draft: verified.draft,
      warnings: [...parsed.warnings, ...warnings, ...verified.warnings],
      extractor: this.name,
    };
  }
}

/** Maps model output to a draft, discarding out-of-range values instead of clamping them. */
export function toDraft(o: ClaudeOutput): { draft: RequirementDraft; warnings: string[] } {
  const draft: RequirementDraft = {};
  const warnings: string[] = [];
  const intIn = (e: Evidence<number> | null, lo: number, hi: number, label: string) => {
    if (!e) return undefined;
    if (!Number.isFinite(e.value) || e.value < lo || e.value > hi) {
      warnings.push(`Discarded ${label} ${e.value}: outside the allowed range`);
      return undefined;
    }
    return { value: Math.round(e.value), evidence: e.evidence };
  };
  if (o.transactionType) draft.transactionType = o.transactionType;
  if (o.category) draft.category = o.category;
  const min = intIn(o.budgetMinRupees, 1, 1e11, "minimum budget");
  const max = intIn(o.budgetMaxRupees, 1, 1e11, "maximum budget");
  if (min) draft.budgetMin = min;
  if (max) draft.budgetMax = max;
  const locations = o.locations.filter((l) => l.value.trim() && l.evidence.trim());
  if (locations.length) draft.locations = locations;
  if (o.furnishing && o.furnishing.value.length) {
    draft.furnishing = { value: [...new Set(o.furnishing.value)], evidence: o.furnishing.evidence };
  }
  const parking = intIn(o.minParking, 1, 20, "parking");
  if (parking) draft.minParking = parking;
  if (o.floorPreference && o.floorPreference.value.length) {
    draft.floorPreference = { value: [...new Set(o.floorPreference.value)], evidence: o.floorPreference.evidence };
  }
  if (o.possession) draft.possession = o.possession;
  if (o.possessionBy) {
    if (/^\d{4}-\d{2}-\d{2}$/.test(o.possessionBy.value) && !Number.isNaN(Date.parse(o.possessionBy.value))) {
      draft.possessionBy = o.possessionBy;
    } else {
      warnings.push(`Discarded possession date "${o.possessionBy.value}": not a valid date`);
    }
  }
  return { draft, warnings };
}
