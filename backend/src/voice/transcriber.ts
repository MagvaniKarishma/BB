import type { VoiceLanguage } from "@prisma/client";

export interface Transcriber {
  readonly name: string;
  transcribe(audio: Buffer, mimeType: string, language: VoiceLanguage): Promise<string>;
}

// ISO-639-1 hints. Hinglish is left to auto-detection, steered by a romanised prompt.
const LANGUAGE_CODE: Partial<Record<VoiceLanguage, string>> = { HINDI: "hi", MARATHI: "mr", ENGLISH: "en" };
const HINGLISH_PROMPT =
  "Rahul ji ko Andheri West mein 2 BHK chahiye rent pe, budget 60 hazaar tak, semi furnished, ek parking.";

const EXTENSION: Record<string, string> = {
  "audio/mp4": "m4a", "audio/m4a": "m4a", "audio/x-m4a": "m4a", "audio/aac": "aac", "audio/mpeg": "mp3",
  "audio/ogg": "ogg", "audio/webm": "webm", "audio/wav": "wav", "audio/x-wav": "wav", "audio/3gpp": "3gp",
  "audio/amr": "amr",
};

/**
 * Speech-to-text over the widely supported `POST /audio/transcriptions` multipart
 * API (Whisper-style). Works with any provider exposing that interface, including
 * self-hosted Whisper servers. Configured with STT_URL / STT_API_KEY / STT_MODEL.
 */
export class HttpTranscriber implements Transcriber {
  readonly name: string;

  constructor(
    private readonly url: string,
    private readonly apiKey: string | undefined,
    private readonly model: string,
    private readonly timeoutMs = 60_000,
  ) {
    this.name = `stt:${model}`;
  }

  async transcribe(audio: Buffer, mimeType: string, language: VoiceLanguage): Promise<string> {
    const form = new FormData();
    const ext = EXTENSION[mimeType] ?? "m4a";
    form.append("file", new Blob([new Uint8Array(audio)], { type: mimeType }), `note.${ext}`);
    form.append("model", this.model);
    form.append("response_format", "json");
    const code = LANGUAGE_CODE[language];
    if (code) form.append("language", code);
    if (language === "HINGLISH") form.append("prompt", HINGLISH_PROMPT);

    const res = await fetch(this.url, {
      method: "POST",
      headers: this.apiKey ? { Authorization: `Bearer ${this.apiKey}` } : {},
      body: form,
      signal: AbortSignal.timeout(this.timeoutMs),
    });
    if (!res.ok) {
      const detail = (await res.text()).slice(0, 200);
      throw new Error(`Transcription service returned ${res.status}: ${detail}`);
    }
    const body = (await res.json()) as { text?: unknown };
    if (typeof body.text !== "string") throw new Error("Transcription service returned no text");
    return body.text.trim();
  }
}
