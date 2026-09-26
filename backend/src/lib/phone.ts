import { parsePhoneNumberFromString } from "libphonenumber-js";

/**
 * Normalises a phone number to E.164, assuming India (+91) when no country code
 * is given. Handles the common local formats brokers type or receive:
 * "98200 12345", "098200-12345", "+91 98200 12345", "919820012345".
 * Returns null when the input is not a valid number — callers must reject it
 * rather than guess.
 */
export function normalizePhone(input: string): string | null {
  const trimmed = input.trim();
  if (!trimmed) return null;
  let candidate = trimmed.replace(/[\s\-().]/g, "");
  if (candidate.startsWith("00")) candidate = `+${candidate.slice(2)}`;
  // Bare 12-digit numbers starting with 91 are Indian numbers without "+".
  if (/^91\d{10}$/.test(candidate)) candidate = `+${candidate}`;
  const parsed = parsePhoneNumberFromString(candidate, "IN");
  if (!parsed || !parsed.isValid()) return null;
  return parsed.number;
}
