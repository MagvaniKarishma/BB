/**
 * Parses WhatsApp's "Export chat" text (without media) — the manual alternative when the
 * Business API isn't connected. Handles the Android format
 *   "26/09/2026, 10:15 am - Rahul Sharma: Hi"   (12 h or 24 h clock)
 * and the iPhone format
 *   "[26/09/26, 10:15:32 AM] Rahul Sharma: Hi"
 * Multi-line messages are joined; system lines ("Messages are end-to-end encrypted…")
 * and "<Media omitted>" placeholders are recognised. Dates are read as day/month (India).
 */

export interface ExportedMessage {
  sentAt: Date;
  sender: string;
  text: string | null;
  media: boolean;
}

const LINE = new RegExp(
  String.raw`^‎?\[?(\d{1,2})[/.-](\d{1,2})[/.-](\d{2,4}),?\s+(\d{1,2}):(\d{2})(?::(\d{2}))?[\s  ]*([ap]\.?\s?m\.?)?\]?\s*(?:[-–]\s*)?` +
    String.raw`([^:]{1,80}?):\s(.*)$`,
  "i",
);
const SYSTEM_START = new RegExp(String.raw`^‎?\[?\d{1,2}[/.-]\d{1,2}[/.-]\d{2,4},?\s+\d{1,2}:\d{2}`);
const MEDIA = /^‎?(<media omitted>|<attached: .*>|(image|video|audio|sticker|document|gif) omitted)$/i;

/** @param tzOffsetMinutes the phone's UTC offset when the chat was exported (India: +330). */
export function parseChatExport(text: string, tzOffsetMinutes = 330): ExportedMessage[] {
  const out: ExportedMessage[] = [];
  for (const rawLine of text.replace(/\r\n?/g, "\n").split("\n")) {
    const line = rawLine.replace(/^﻿/, "");
    const m = LINE.exec(line);
    if (m) {
      let [, d, mo, y, h, mi, s, ampm] = m;
      let hour = Number(h);
      if (ampm) {
        const pm = /^p/i.test(ampm);
        if (pm && hour < 12) hour += 12;
        if (!pm && hour === 12) hour = 0;
      }
      let year = Number(y);
      if (year < 100) year += 2000;
      const utc = Date.UTC(year, Number(mo) - 1, Number(d), hour, Number(mi), Number(s ?? 0)) - tzOffsetMinutes * 60_000;
      const body = m[9].replace(/‎/g, "").trim();
      const media = MEDIA.test(body);
      out.push({ sentAt: new Date(utc), sender: m[8].replace(/‎/g, "").trim(), text: media ? null : body, media });
      continue;
    }
    if (SYSTEM_START.test(line)) continue; // timestamped line without "Name:" = system message
    const last = out[out.length - 1];
    if (last && line.trim() && !last.media) last.text = `${last.text ?? ""}\n${line}`.trim();
  }
  return out;
}
