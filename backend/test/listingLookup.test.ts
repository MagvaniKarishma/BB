import { afterEach, describe, expect, it } from "vitest";
import { analyzeMessage } from "../src/whatsapp/analyze.js";
import { type FetchedPage, allowedUrl, classify, lookupListing, setListingLookup } from "../src/whatsapp/listingLookup.js";
import { REAL_99ACRES_1, REAL_HOUSING_1 } from "./helpers.js";

// Responses recorded from the real links (GitHub-hosted runner, Sept 2026).
const HOUSING_PAGE =
  "https://housing.com/rent/20835341-585-sqft-1-bhk-apartment-on-rent-in-mulund-west-mumbai?utm_source=sharebutton_crf&utm_medium=app&utm_campaign=rent-20835341";
const BRANCH_INTENT =
  "intent://open?link_click_id=1632331404932457911#Intent;scheme=housing;package=com.locon.housing;S.browser_fallback_url=" +
  encodeURIComponent(HOUSING_PAGE) + ";S.market_referrer=link_click_id%3D1;B.branch_intent=true;end";

function fakeWeb(routes: Record<string, FetchedPage>) {
  const requested: string[] = [];
  const fetcher = async (url: string) => {
    requested.push(url);
    const r = routes[url];
    if (!r) throw new Error(`unexpected request ${url}`);
    return r;
  };
  return { fetcher, requested };
}

describe("listing lookup", () => {
  it("reads 'for rent' from the Housing.com address a short link leads to, without opening the page", async () => {
    const web = fakeWeb({ "https://dzfki.app.link/XTXUr0PoI6b": { status: 307, location: HOUSING_PAGE } });
    const page = await lookupListing("https://dzfki.app.link/XTXUr0PoI6b", web.fetcher);
    expect(page).toMatchObject({
      status: "OK",
      url: HOUSING_PAGE,
      transactionType: { value: "RENT", evidence: "585-sqft-1-bhk-apartment-on-rent-in-mulund-west-mumbai" },
    });
    expect(web.requested).toEqual(["https://dzfki.app.link/XTXUr0PoI6b"]); // the bot-protected page itself isn't requested
  });

  it("follows the Android intent:// answer short links give phones", async () => {
    const web = fakeWeb({ "https://dzfki.app.link/X": { status: 307, location: BRANCH_INTENT } });
    expect((await lookupListing("https://dzfki.app.link/X", web.fetcher)).transactionType?.value).toBe("RENT");
  });

  it("reports 99acres' bot protection instead of guessing", async () => {
    const web = fakeWeb({ "https://www.99acres.com/I94007278": { status: 417 } });
    expect(await lookupListing("https://www.99acres.com/I94007278", web.fetcher)).toMatchObject({
      status: "BLOCKED",
      reason: "the portal refused the request (HTTP 417)",
    });
  });

  it("reads a page's canonical address or title when the link address doesn't say", async () => {
    const web = fakeWeb({
      "https://www.99acres.com/I1": { status: 301, location: "/spid-I1" },
      "https://www.99acres.com/spid-I1": {
        status: 200,
        body: '<html><head><title>2 BHK Flat for Sale in Veena Nagar, Mulund West | 99acres</title></head><body>Rent Buy for rent</body></html>',
      },
    });
    const page = await lookupListing("https://www.99acres.com/I1", web.fetcher);
    expect(page.transactionType).toEqual({ value: "BUY", evidence: "2 BHK Flat for Sale in Veena Nagar, Mulund West | 99acres" });
  });

  it("never leaves the portals and never guesses from mixed signals", async () => {
    expect(allowedUrl("http://housing.com/rent/1")).toBeNull(); // https only
    expect(allowedUrl("https://evil.example/99acres.com")).toBeNull();
    expect(allowedUrl("https://housing.com.evil.example/rent")).toBeNull();
    expect(allowedUrl("https://169.254.169.254/latest")).toBeNull();
    const web = fakeWeb({ "https://dzfki.app.link/Y": { status: 302, location: "https://tracker.example/x" } });
    expect(await lookupListing("https://dzfki.app.link/Y", web.fetcher)).toMatchObject({ status: "FAILED" });
    expect(classify("/rent-or-buy/flats", true)).toBeUndefined();
    expect(classify("Flats for rent and for sale in Mumbai")).toBeUndefined();
  });
});

describe("rent/sale from the listing link in real enquiries", () => {
  afterEach(() => setListingLookup(undefined));

  it("fills rent from the Housing.com link, with the address as evidence and a warning to confirm", async () => {
    setListingLookup((url) => lookupListing(url, fakeWeb({ [url]: { status: 307, location: HOUSING_PAGE } }).fetcher));
    const { portalLead, extraction } = await analyzeMessage(REAL_HOUSING_1);
    expect(extraction.draft.transactionType).toEqual({ value: "RENT", evidence: "585-sqft-1-bhk-apartment-on-rent-in-mulund-west-mumbai" });
    expect(portalLead?.listingPage?.url).toBe(HOUSING_PAGE);
    const w = extraction.warnings.join(" | ");
    expect(w).toMatch(/For rent — read from the Housing\.com listing page/);
    expect(w).not.toMatch(/doesn't say whether/);
    expect(extraction.draft.budgetMax).toBeUndefined(); // ₹27,000 is still the listing's rent, not a budget
  });

  it("leaves rent/sale empty and says why when 99acres blocks the lookup", async () => {
    setListingLookup((url) => lookupListing(url, fakeWeb({ [url]: { status: 417 } }).fetcher));
    const { extraction } = await analyzeMessage(REAL_99ACRES_1);
    expect(extraction.draft.transactionType).toBeUndefined();
    expect(extraction.warnings.join(" | ")).toMatch(
      /doesn't say whether it's for rent or sale; couldn't read the listing page \(the portal refused the request \(HTTP 417\)\) — confirm/,
    );
  });
});
