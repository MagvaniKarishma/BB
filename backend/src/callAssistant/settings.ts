import type { CallAssistantSettings, CallGreeting, GreetingLanguage } from "@prisma/client";
import { z } from "zod";
import { prisma } from "../db.js";
import { DEFAULT_SCRIPTS } from "./prompts.js";

export const businessHoursSchema = z.object({
  /** ISO weekdays, 1 = Monday … 7 = Sunday. */
  days: z.array(z.number().int().min(1).max(7)).max(7),
  start: z.string().regex(/^([01]\d|2[0-3]):[0-5]\d$/),
  end: z.string().regex(/^([01]\d|2[0-3]):[0-5]\d$/),
  timeZone: z.string().default("Asia/Kolkata"),
});
export type BusinessHours = z.infer<typeof businessHoursSchema>;

export const DEFAULT_HOURS: BusinessHours = { days: [1, 2, 3, 4, 5, 6], start: "09:30", end: "20:00", timeZone: "Asia/Kolkata" };

/** Settings as stored, or the defaults (assistant off, calls go straight to the team). */
export async function loadSettings(brokerageId: string): Promise<CallAssistantSettings> {
  const s = await prisma.callAssistantSettings.findUnique({ where: { brokerageId } });
  return (
    s ?? {
      brokerageId, enabled: false, mode: "DIRECT", voice: "RECORDED_STANDARD", customGreetingEnabled: false,
      defaultLanguage: "HINGLISH", businessHours: DEFAULT_HOURS, callbackReminder: true, callbackDelayMinutes: 15,
      callbackAssigneeId: null, unclearBehavior: "TAKE_CALLBACK", maxUnclearRetries: 2, humanTransfer: "ON_REQUEST",
      transferNumber: null, businessNumbers: [], updatedAt: new Date(0),
    }
  );
}

/** Is `at` inside the configured business hours (in the configured time zone)? */
export function withinHours(hoursJson: unknown, at = new Date()): boolean {
  const parsed = businessHoursSchema.safeParse(hoursJson);
  const h = parsed.success ? parsed.data : DEFAULT_HOURS;
  const parts = new Intl.DateTimeFormat("en-GB", {
    timeZone: h.timeZone, weekday: "short", hour: "2-digit", minute: "2-digit", hourCycle: "h23",
  }).formatToParts(at);
  const get = (t: string) => parts.find((p) => p.type === t)?.value ?? "";
  const day = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"].indexOf(get("weekday")) + 1;
  const minutes = Number(get("hour")) * 60 + Number(get("minute"));
  const toMin = (s: string) => Number(s.slice(0, 2)) * 60 + Number(s.slice(3));
  const [start, end] = [toMin(h.start), toMin(h.end)];
  if (!h.days.includes(day)) return false;
  return start <= end ? minutes >= start && minutes < end : minutes >= start || minutes < end; // overnight hours
}

export const LANGUAGES: GreetingLanguage[] = ["HINGLISH", "HINDI", "ENGLISH", "MARATHI"];

/** Every language, with the saved greeting or the default script. */
export function greetingsView(saved: CallGreeting[]) {
  return LANGUAGES.map((language) => {
    const g = saved.find((x) => x.language === language);
    return {
      language,
      script: g?.script ?? DEFAULT_SCRIPTS[language],
      isDefaultScript: !g || g.script === DEFAULT_SCRIPTS[language],
      hasAudio: g?.storageKey != null,
      mimeType: g?.mimeType ?? null,
      size: g?.size ?? null,
      durationMs: g?.durationMs ?? null,
      updatedAt: g?.updatedAt ?? null,
    };
  });
}
