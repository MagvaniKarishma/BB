import type { TransactionType } from "@prisma/client";
import type { Evidence } from "../voice/draft.js";

/**
 * Opens the listing link in a portal enquiry to learn whether the listing is for rent or
 * sale — portal messages often don't say ("your 1 BHK Apartment listed at Housing.com").
 *
 * Only the page ADDRESS and its title/meta tags are read, never the page body (portal
 * pages show rent and sale links everywhere). Only portal domains and Housing.com's
 * short-link service are ever requested, over HTTPS, one link per enquiry, with a normal
 * identifying user agent — if a portal blocks the request we report it and the agent
 * confirms with the client (or opens the link on their phone). Nothing is guessed.
 */

export interface ListingPage {
  /** OK: page read; BLOCKED: portal refused (bot protection); FAILED: network/other. */
  status: "OK" | "BLOCKED" | "FAILED";
  /** Where the link finally led. */
  url?: string;
  title?: string;
  transactionType?: Evidence<TransactionType>;
  reason?: string;
}

export interface FetchedPage {
  status: number;
  location?: string | null;
  body?: string;
}
export type PageFetcher = (url: string) => Promise<FetchedPage>;
export type ListingLookup = (url: string) => Promise<ListingPage>;

const PORTAL_HOSTS = ["99acres.com", "housing.com", "magicbricks.com"];
// Branch.io short links (Housing.com shares listings as dzfki.app.link/…): followed, never read as the listing.
const SHORT_LINK_HOSTS = ["app.link"];
const MAX_HOPS = 6;
const MAX_BODY = 1_500_000;
const TIMEOUT_MS = 8_000;
const USER_AGENT = "Mozilla/5.0 (compatible; BrokerBuddy/1.0; listing check for an enquiry sent to the agent)";

const hostIn = (host: string, list: string[]) => list.some((d) => host === d || host.endsWith(`.${d}`));

export function allowedUrl(raw: string): URL | null {
  try {
    const u = new URL(raw);
    if (u.protocol !== "https:" || u.username || u.password || (u.port && u.port !== "443")) return null;
    const host = u.hostname.toLowerCase();
    return hostIn(host, PORTAL_HOSTS) || hostIn(host, SHORT_LINK_HOSTS) ? u : null;
  } catch {
    return null;
  }
}
const isPortal = (u: URL) => hostIn(u.hostname.toLowerCase(), PORTAL_HOSTS);

const RENT_RE = /\bfor[\s_-]+rent\b|(?:^|\/)rent(?:\/|$)|\bon[\s_-]+rent\b|\brental\b|\bto[\s_-]+let\b/i;
const SALE_RE = /\bfor[\s_-]+sale\b|(?:^|\/)(?:buy|sale|resale)(?:\/|$)|\bresale\b|\bfor[\s_-]+buy\b/i;

/**
 * Rent/sale from one piece of text, only when it points one way. For a URL path the
 * evidence is the listing's slug ("585-sqft-1-bhk-apartment-on-rent-in-mulund-west-mumbai").
 */
export function classify(text: string, isPath = false): Evidence<TransactionType> | undefined {
  const rent = RENT_RE.exec(text);
  const sale = SALE_RE.exec(text);
  const value: TransactionType | undefined = rent && !sale ? "RENT" : sale && !rent ? "BUY" : undefined;
  if (!value) return undefined;
  if (!isPath) return { value, evidence: text.trim().slice(0, 200) };
  const re = value === "RENT" ? RENT_RE : SALE_RE;
  const segments = text.split("/").filter((seg) => re.test(`/${seg}/`));
  const evidence = segments.sort((a, b) => b.length - a.length)[0] ?? (rent ?? sale)![0];
  return { value, evidence: evidence.replace(/^\d{5,}-/, "") };
}

/**
 * Branch short links answer phones with an Android intent:// URL whose web address is in
 * S.browser_fallback_url; other clients get a normal redirect.
 */
export function intentFallback(location: string): string | undefined {
  if (!/^intent:/i.test(location)) return undefined;
  const m = /[#;]S\.browser_fallback_url=([^;]+)/.exec(location);
  if (!m) return undefined;
  try {
    return decodeURIComponent(m[1]);
  } catch {
    return undefined;
  }
}

const decodeEntities = (s: string) =>
  s.replace(/&amp;/g, "&").replace(/&quot;/g, '"').replace(/&#39;|&#x27;/g, "'").replace(/&lt;/g, "<").replace(/&gt;/g, ">").trim();

function meta(html: string, key: string): string | undefined {
  const re = new RegExp(
    String.raw`<meta[^>]+(?:property|name)\s*=\s*["']${key}["'][^>]*content\s*=\s*["']([^"']*)["']` +
      String.raw`|<meta[^>]+content\s*=\s*["']([^"']*)["'][^>]*(?:property|name)\s*=\s*["']${key}["']`,
    "i",
  );
  const m = re.exec(html);
  const v = m?.[1] ?? m?.[2];
  return v ? decodeEntities(v) : undefined;
}

export function readPage(html: string) {
  const head = html.slice(0, 300_000);
  const title = /<title[^>]*>([^<]{1,300})<\/title>/i.exec(head)?.[1];
  const canonical = /<link[^>]+rel\s*=\s*["']canonical["'][^>]*href\s*=\s*["']([^"']+)["']/i.exec(head)?.[1];
  // Short-link landing pages send the browser on with JavaScript
  // (window.top.location = validateProtocol("https://housing.com/rent/…")) or a link; take
  // the first portal page address in it.
  const next = /https?:\/\/(?:[\w-]+\.)*(?:99acres\.com|housing\.com|magicbricks\.com)\/[^"'\s<>\\]+/i.exec(head)?.[0];
  return {
    title: title ? decodeEntities(title) : meta(head, "og:title"),
    ogTitle: meta(head, "og:title"),
    ogUrl: meta(head, "og:url"),
    canonical: canonical ? decodeEntities(canonical) : undefined,
    next: next ? decodeEntities(next).replace(/\\\//g, "/") : undefined,
  };
}

/** Rent/sale from the listing's address first, then its canonical/og URL, then its title. */
function fromPage(url: URL, page?: ReturnType<typeof readPage>): Evidence<TransactionType> | undefined {
  const path = (u: string | undefined) => {
    if (!u) return undefined;
    try {
      return decodeURIComponent(new URL(u, url).pathname);
    } catch {
      return undefined;
    }
  };
  for (const p of [path(url.href), path(page?.canonical), path(page?.ogUrl)]) {
    const t = p ? classify(p, true) : undefined;
    if (t) return t;
  }
  for (const title of [page?.ogTitle, page?.title]) {
    const t = title ? classify(title) : undefined;
    if (t) return t;
  }
  return undefined;
}

export const fetchPage: PageFetcher = async (url) => {
  const res = await fetch(url, {
    redirect: "manual",
    signal: AbortSignal.timeout(TIMEOUT_MS),
    headers: { "User-Agent": USER_AGENT, Accept: "text/html,application/xhtml+xml", "Accept-Language": "en-IN,en" },
  });
  const location = res.headers.get("location");
  let body: string | undefined;
  if (res.status === 200 && (res.headers.get("content-type") ?? "").includes("html")) {
    const reader = res.body?.getReader();
    const chunks: Uint8Array[] = [];
    let size = 0;
    while (reader && size < MAX_BODY) {
      const { done, value } = await reader.read();
      if (done) break;
      chunks.push(value);
      size += value.length;
    }
    await reader?.cancel().catch(() => undefined);
    body = new TextDecoder().decode(Buffer.concat(chunks));
  } else {
    await res.body?.cancel().catch(() => undefined);
  }
  return { status: res.status, location, body };
};

export async function lookupListing(link: string, fetcher: PageFetcher = fetchPage): Promise<ListingPage> {
  const start = allowedUrl(link);
  if (!start) return { status: "FAILED", reason: "not a portal link" };
  let url: URL = start;
  try {
    for (let hop = 0; hop < MAX_HOPS; hop++) {
      // The address alone often says it (housing.com/rent/…, …-for-sale-in-…).
      if (isPortal(url)) {
        const byUrl = fromPage(url);
        if (byUrl) return { status: "OK", url: url.href, transactionType: byUrl };
      }
      const res = await fetcher(url.href);
      if (res.status >= 300 && res.status < 400 && res.location) {
        const target = intentFallback(res.location) ?? new URL(res.location, url).href;
        const next = allowedUrl(target);
        if (!next) return { status: "FAILED", url: url.href, reason: "the link leads outside the portal" };
        url = next;
        continue;
      }
      // Bot protection answers with odd codes: Housing.com 406, 99acres 417.
      if ([401, 403, 406, 417, 429].includes(res.status)) {
        return { status: "BLOCKED", url: url.href, reason: `the portal refused the request (HTTP ${res.status})` };
      }
      if (res.status !== 200 || !res.body) {
        return { status: "FAILED", url: url.href, reason: `HTTP ${res.status}` };
      }
      const page = readPage(res.body);
      if (!isPortal(url)) {
        // A short-link landing page: follow its redirect target, if it's a portal page.
        const next: URL | null = page.next ? allowedUrl(new URL(page.next, url).href) : null;
        if (!next || next.href === url.href) return { status: "FAILED", url: url.href, reason: "the short link didn't lead to a listing" };
        url = next;
        continue;
      }
      return { status: "OK", url: url.href, title: page.title, transactionType: fromPage(url, page) };
    }
    return { status: "FAILED", url: url.href, reason: "too many redirects" };
  } catch (err) {
    const reason = err instanceof Error ? (err.name === "TimeoutError" ? "timed out" : err.message) : String(err);
    return { status: "FAILED", url: url.href, reason };
  }
}

// Wiring: on by default; LISTING_LOOKUP=off disables network access (tests, offline installs).
let lookup: ListingLookup | null | undefined;
export function getListingLookup(): ListingLookup | null {
  if (lookup === undefined) lookup = process.env.LISTING_LOOKUP === "off" ? null : (u) => lookupListing(u);
  return lookup;
}
/** For tests and alternative wiring. */
export function setListingLookup(l: ListingLookup | null | undefined) {
  lookup = l;
}
