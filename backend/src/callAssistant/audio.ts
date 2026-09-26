/**
 * Greeting audio checks. Telephony providers play MP3 and WAV reliably; AAC/M4A (what
 * most phones record) often isn't accepted, so the app records WAV and uploads must be
 * MP3 or WAV. The real format is read from the file's bytes, not its name.
 */
export type GreetingFormat = "audio/wav" | "audio/mpeg";

export const MAX_GREETING_MS = 60_000;
export const MAX_GREETING_BYTES = 6 * 1024 * 1024;

export function sniffAudio(b: Buffer): GreetingFormat | null {
  if (b.length >= 12 && b.toString("ascii", 0, 4) === "RIFF" && b.toString("ascii", 8, 12) === "WAVE") return "audio/wav";
  if (b.length >= 3 && b.toString("ascii", 0, 3) === "ID3") return "audio/mpeg";
  if (b.length >= 2 && b[0] === 0xff && (b[1] & 0xe0) === 0xe0) return "audio/mpeg"; // MPEG frame sync
  return null;
}

export interface WavInfo {
  channels: number;
  sampleRate: number;
  bitsPerSample: number;
  durationMs: number;
}

/** Reads a PCM WAV header (walking the chunks, as "fmt " and "data" can be anywhere). */
export function wavInfo(b: Buffer): WavInfo | null {
  if (sniffAudio(b) !== "audio/wav") return null;
  let off = 12;
  let fmt: { channels: number; sampleRate: number; byteRate: number; bits: number } | null = null;
  while (off + 8 <= b.length) {
    const id = b.toString("ascii", off, off + 4);
    const size = b.readUInt32LE(off + 4);
    const body = off + 8;
    if (id === "fmt " && body + 16 <= b.length) {
      fmt = { channels: b.readUInt16LE(body + 2), sampleRate: b.readUInt32LE(body + 4), byteRate: b.readUInt32LE(body + 8), bits: b.readUInt16LE(body + 14) };
    } else if (id === "data" && fmt && fmt.byteRate > 0) {
      const dataBytes = Math.min(size, b.length - body);
      return { channels: fmt.channels, sampleRate: fmt.sampleRate, bitsPerSample: fmt.bits, durationMs: Math.round((dataBytes / fmt.byteRate) * 1000) };
    }
    off = body + size + (size % 2);
  }
  return null;
}
