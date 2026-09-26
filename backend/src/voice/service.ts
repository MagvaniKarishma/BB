import type { VoiceLanguage } from "@prisma/client";
import type { Extraction } from "./draft.js";
import { ClaudeExtractor } from "./claudeExtractor.js";
import { extractWithRules } from "./rulesExtractor.js";
import { HttpTranscriber, type Transcriber } from "./transcriber.js";

export interface RequirementExtractor {
  readonly name: string;
  extract(transcript: string, language: VoiceLanguage): Promise<Extraction>;
}

export const rulesExtractor: RequirementExtractor = {
  name: "rules",
  extract: async (transcript) => extractWithRules(transcript),
};

export interface VoiceServices {
  /** null when no speech-to-text service is configured. */
  transcriber: Transcriber | null;
  extractor: RequirementExtractor;
}

export function servicesFromEnv(env: NodeJS.ProcessEnv = process.env): VoiceServices {
  const transcriber = env.STT_URL
    ? new HttpTranscriber(env.STT_URL, env.STT_API_KEY, env.STT_MODEL ?? "whisper-1")
    : null;
  const useClaude =
    env.EXTRACTOR !== "rules" && Boolean(env.ANTHROPIC_API_KEY || env.ANTHROPIC_AUTH_TOKEN);
  return { transcriber, extractor: useClaude ? new ClaudeExtractor() : rulesExtractor };
}

let services: VoiceServices | null = null;
export const getVoiceServices = (): VoiceServices => (services ??= servicesFromEnv());
/** For tests and alternative wiring. */
export const setVoiceServices = (s: VoiceServices | null) => {
  services = s;
};

/**
 * Extracts a draft requirement. If the AI extractor fails, falls back to the built-in
 * rules so the agent still gets a draft to review, with a warning explaining why.
 */
export async function extractRequirement(transcript: string, language: VoiceLanguage): Promise<Extraction> {
  const { extractor } = getVoiceServices();
  try {
    return await extractor.extract(transcript, language);
  } catch (err) {
    if (extractor === rulesExtractor) throw err;
    const result = extractWithRules(transcript);
    const reason = err instanceof Error ? err.message : String(err);
    console.warn(`AI extraction failed, using rules: ${reason}`);
    return {
      ...result,
      warnings: [`AI extraction was unavailable (${reason.slice(0, 120)}); used built-in rules — please review carefully`, ...result.warnings],
    };
  }
}
