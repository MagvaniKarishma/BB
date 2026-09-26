import type { GreetingLanguage } from "@prisma/client";

const DEVANAGARI = /[ऀ-ॿ]/;
// Words that mark Marathi rather than Hindi in Devanagari text.
const MARATHI_MARKERS = /(आहे|आहेत|पाहिजे|मला|तुम्ही|नाही|हवा|हवी|हवे|आणि|किंवा|माझं|माझे|कोणत्या|भाड्याने|घ्यायचा|नको)/;
// Romanised Hindi function words (Hinglish).
const HINGLISH_MARKERS =
  /\b(hai|hain|chahiye|chahie|mujhe|mujhko|nahi|nahin|haan|haa|ka|ki|ke|mein|aur|bhi|toh|pe|kitna|kya|kaunsa|wala|wali|dijiye|bata|batao|hoon|hu|raha|rahi|lena|dena|kiraye|khareed\w*|bas|theek|thik|ji|accha|acha|lakh|hazaar|hazar)\b/i;
const MARATHI_ROMAN = /\b(aahe|ahe|pahije|mala|nahi|hava|havi|kuthe|bhadyane|ghyaycha|ani)\b/i;

/**
 * Best guess of the language a caller is speaking, from what the speech recogniser
 * returned. Returns null when there's nothing to go on (numbers, "ok").
 */
export function detectLanguage(text: string): GreetingLanguage | null {
  const t = text.trim();
  if (!/[\p{L}]/u.test(t)) return null;
  if (DEVANAGARI.test(t)) return MARATHI_MARKERS.test(t) ? "MARATHI" : "HINDI";
  const words = t.split(/\s+/).filter((w) => /[a-z]/i.test(w));
  if (words.length === 0) return null;
  if (MARATHI_ROMAN.test(t) && /\b(aahe|ahe|pahije|mala|hava|havi)\b/i.test(t)) return "MARATHI";
  if (HINGLISH_MARKERS.test(t)) return "HINGLISH";
  // Very short replies ("yes", "Powai") say little about the language.
  return words.length >= 3 ? "ENGLISH" : null;
}

/** BCP-47 tags for speech recognition / TTS at the telephony provider. */
export const SPEECH_LOCALE: Record<GreetingLanguage, string> = {
  HINGLISH: "hi-IN",
  HINDI: "hi-IN",
  ENGLISH: "en-IN",
  MARATHI: "mr-IN",
};
