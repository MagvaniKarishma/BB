import { describe, expect, it } from "vitest";
import { createHmac } from "node:crypto";
import { parseWebhook } from "../src/whatsapp/webhook.js";
import { REAL_99ACRES_1, REAL_HOUSING_1, REAL_HOUSING_2, metaPayload } from "./helpers.js";
import { parseChatExport } from "../src/whatsapp/chatExport.js";
import { detectPortal, parsePortalLead } from "../src/whatsapp/portalLeads.js";
import { analyzeMessage } from "../src/whatsapp/analyze.js";
import { decrypt, encrypt, validMetaSignature } from "../src/lib/crypto.js";

describe("webhook parsing", () => {
  it("parses text, interactive, media captions, audio, location and statuses", () => {
    const payload = metaPayload(
      "106540352242922",
      [
        { from: "919820012345", id: "wamid.A", timestamp: "1790000000", type: "text", text: { body: "2 BHK chahiye Andheri mein" } },
        { from: "919820012345", id: "wamid.B", timestamp: "1790000001", type: "interactive", interactive: { type: "button_reply", button_reply: { id: "yes", title: "Yes, schedule visit" } } },
        { from: "919820012345", id: "wamid.C", timestamp: "1790000002", type: "image", image: { id: "MEDIA1", mime_type: "image/jpeg", caption: "Is flat ka price kya hai?" } },
        { from: "919820012345", id: "wamid.D", timestamp: "1790000003", type: "audio", audio: { id: "MEDIA2", mime_type: "audio/ogg; codecs=opus" } },
        { from: "919820012345", id: "wamid.E", timestamp: "1790000004", type: "location", location: { latitude: 19.13, longitude: 72.83, name: "Lokhandwala" } },
        { from: "919820012345", id: "wamid.F", timestamp: "1790000005", type: "reaction", reaction: { message_id: "wamid.X", emoji: "👍" } },
      ],
      [{ profile: { name: "Rahul" }, wa_id: "919820012345" }],
      [{ id: "wamid.OUT", status: "failed", timestamp: "1790000006", recipient_id: "919820012345", errors: [{ code: 131047, title: "Re-engagement message" }] }],
    );
    const [change] = parseWebhook(payload);
    expect(change.phoneNumberId).toBe("106540352242922");
    const m = change.messages;
    expect(m.map((x) => x.text)).toEqual([
      "2 BHK chahiye Andheri mein",
      "Yes, schedule visit",
      "Is flat ka price kya hai?",
      null,
      "📍 Lokhandwala (19.13, 72.83)",
      null,
    ]);
    expect(m[0]).toMatchObject({ profileName: "Rahul", type: "text", timestamp: new Date(1790000000 * 1000) });
    expect(m[3]).toMatchObject({ type: "audio", mediaId: "MEDIA2", mimeType: "audio/ogg; codecs=opus" });
    expect(m[5].type).toBe("reaction");
    expect(change.statuses).toEqual([
      { id: "wamid.OUT", status: "failed", timestamp: new Date(1790000006 * 1000), recipient: "919820012345", error: "Re-engagement message" },
    ]);
  });

  it("ignores other objects/fields and malformed entries", () => {
    expect(parseWebhook({ object: "page", entry: [] })).toEqual([]);
    expect(parseWebhook(null)).toEqual([]);
    const other = { object: "whatsapp_business_account", entry: [{ changes: [{ field: "account_update", value: {} }] }] };
    expect(parseWebhook(other)).toEqual([]);
    const bad = metaPayload("1", [{ id: "x" }, { from: "91", type: "text" }]);
    expect(parseWebhook(bad)[0].messages).toEqual([]);
  });
});

describe("security helpers", () => {
  it("validates X-Hub-Signature-256", () => {
    const body = Buffer.from('{"a":1}');
    const sig = "sha256=" + createHmac("sha256", "app-secret").update(body).digest("hex");
    expect(validMetaSignature(body, sig, "app-secret")).toBe(true);
    expect(validMetaSignature(body, sig, "other-secret")).toBe(false);
    expect(validMetaSignature(Buffer.from('{"a":2}'), sig, "app-secret")).toBe(false);
    expect(validMetaSignature(body, undefined, "app-secret")).toBe(false);
    expect(validMetaSignature(body, "sha256=abc", "app-secret")).toBe(false);
  });

  it("encrypts secrets at rest (AES-256-GCM, tamper-evident)", () => {
    const enc = encrypt("EAAG-access-token");
    expect(enc).not.toContain("EAAG");
    expect(decrypt(enc)).toBe("EAAG-access-token");
    expect(encrypt("x")).not.toBe(encrypt("x"));
    // Flip one bit of the ciphertext ("v1.<iv>.<tag>.<data>"): always a real change.
    const [v, iv, tag, data] = enc.split(".");
    const bytes = Buffer.from(data, "base64");
    bytes[0] ^= 0x01;
    expect(() => decrypt([v, iv, tag, bytes.toString("base64")].join("."))).toThrow();
  });
});

describe("chat export (manual alternative)", () => {
  it("parses Android 12 h exports with multi-line messages, media and system lines", () => {
    const text = [
      "26/09/2026, 10:15 am - Messages and calls are end-to-end encrypted. No one outside of this chat can read them.",
      "26/09/2026, 10:15 am - Rahul Sharma: Namaste! 2 BHK chahiye Andheri West mein",
      "budget 70 hazaar tak",
      "26/09/2026, 10:17 am - Priya (BrokerBuddy): Ji, I'll share options",
      "26/09/2026, 1:05 pm - Rahul Sharma: <Media omitted>",
      "27/09/2026, 12:30 am - Rahul Sharma: Semi furnished chalega",
    ].join("\n");
    const msgs = parseChatExport(text);
    expect(msgs).toHaveLength(4);
    expect(msgs[0]).toMatchObject({ sender: "Rahul Sharma", text: "Namaste! 2 BHK chahiye Andheri West mein\nbudget 70 hazaar tak", media: false });
    expect(msgs[0].sentAt.toISOString()).toBe("2026-09-26T04:45:00.000Z"); // 10:15 IST
    expect(msgs[2]).toMatchObject({ media: true, text: null });
    expect(msgs[2].sentAt.toISOString()).toBe("2026-09-26T07:35:00.000Z");
    expect(msgs[3].sentAt.toISOString()).toBe("2026-09-26T19:00:00.000Z"); // 12:30 am
  });

  it("parses iPhone and 24 h formats (incl. narrow no-break spaces)", () => {
    const text = [
      "[26/09/26, 10:15:32 AM] Rahul Sharma: Hi",
      "‎[26/09/26, 10:16:00 AM] Rahul Sharma: ‎image omitted",
      "26/09/26, 18:45 - Rahul: Kal visit karte hain",
    ].join("\n");
    const msgs = parseChatExport(text);
    expect(msgs.map((m) => [m.sender, m.text, m.media])).toEqual([
      ["Rahul Sharma", "Hi", false],
      ["Rahul Sharma", null, true],
      ["Rahul", "Kal visit karte hain", false],
    ]);
    expect(msgs[2].sentAt.toISOString()).toBe("2026-09-26T13:15:00.000Z");
  });
});

const NOTIFICATION_99 =
  "Dear Customer, You have received a response on your property ID A12345678 (2 BHK Apartment for Rent in Andheri West, Mumbai, ₹65,000). " +
  "Name: Amit Verma, Mobile: +91-9876543210, Email: amit.v@example.com. - 99acres";
const HOUSING =
  "New lead on Housing.com! Priya Nair (+91 98670 00000) is interested in your listing '3 BHK Flat in Powai for Sale' priced at ₹2.4 Cr. " +
  "View: https://housing.com/in/buy/resale/page/12345678-3-bhk-powai";
const MAGICBRICKS =
  "Magicbricks: Buyer Enquiry | Name: Rohit Kulkarni | Contact: 9820098200 | Property: 1 BHK, Thane West | Price: Rs. 45 Lac | " +
  "Message: Is this still available? Looking to buy within 50 lakh, need parking.";
const CLICK_TO_CHAT =
  "Hi, I am interested in your property on 99acres: 2 BHK in Andheri West for ₹65,000/month. https://www.99acres.com/2-bhk-apartment-for-rent-in-andheri-west-mumbai-r12345678";

describe("portal lead recognition", () => {
  it("detects the portal", () => {
    expect(detectPortal(NOTIFICATION_99)).toBe("ACRES_99");
    expect(detectPortal(HOUSING)).toBe("HOUSING_COM");
    expect(detectPortal(MAGICBRICKS)).toBe("MAGICBRICKS");
    expect(detectPortal("Hi, 2 BHK chahiye")).toBeNull();
    expect(parsePortalLead("Kya aapke paas Housing society mein flat hai?")).toBeNull(); // "housing" alone isn't the portal
  });

  it("extracts lead identity, listing reference and the advertised price", () => {
    expect(parsePortalLead(NOTIFICATION_99)).toMatchObject({
      portal: "ACRES_99", leadName: "Amit Verma", leadPhone: "+919876543210", leadEmail: "amit.v@example.com",
      listingRef: "A12345678", listingPrice: { value: 65000 },
    });
    expect(parsePortalLead(HOUSING)).toMatchObject({
      portal: "HOUSING_COM", leadName: "Priya Nair", leadPhone: "+919867000000", listingPrice: { value: 24_000_000 },
    });
    expect(parsePortalLead(MAGICBRICKS)).toMatchObject({
      portal: "MAGICBRICKS", leadName: "Rohit Kulkarni", leadPhone: "+919820098200", listingPrice: { value: 4_500_000 },
      leadMessage: "Is this still available? Looking to buy within 50 lakh, need parking.",
    });
    const c2c = parsePortalLead(CLICK_TO_CHAT)!;
    expect(c2c).toMatchObject({ portal: "ACRES_99", listingRef: "r12345678", listingPrice: { value: 65000 } });
    expect(c2c.leadPhone).toBeUndefined(); // the sender is the lead
  });
});

describe("message analysis: advertised prices stay out of client budgets", () => {
  it("portal notification: listing price is not a budget; listing attributes are proposed with a warning", async () => {
    const { portalLead, extraction } = await analyzeMessage(NOTIFICATION_99);
    expect(portalLead?.portal).toBe("ACRES_99");
    expect(extraction.draft.budgetMax).toBeUndefined();
    expect(extraction.draft.budgetMin).toBeUndefined();
    expect(extraction.advertisedPrices).toEqual([{ value: 65000, evidence: "₹65,000" }]);
    expect(extraction.draft).toMatchObject({
      transactionType: { value: "RENT" }, category: { value: "BHK_2" }, locations: [{ value: "Andheri West" }],
    });
    const w = extraction.warnings.join(" | ");
    expect(w).toMatch(/Advertised price ₹65,000 of the 99acres listing kept separate/);
    expect(w).toMatch(/budget isn't stated/);
  });

  it("the lead's own words set the budget, never the listing price", async () => {
    const { extraction } = await analyzeMessage(MAGICBRICKS);
    expect(extraction.draft.budgetMax?.value).toBe(5_000_000); // "within 50 lakh" — their words
    expect(extraction.advertisedPrices?.[0].value).toBe(4_500_000); // listing price
    expect(extraction.draft).toMatchObject({
      transactionType: { value: "BUY" }, category: { value: "BHK_1" }, locations: [{ value: "Thane West" }], minParking: { value: 1 },
    });
  });

  it("housing.com sale listing in crores", async () => {
    const { extraction } = await analyzeMessage(HOUSING);
    expect(extraction.draft.budgetMax).toBeUndefined();
    expect(extraction.draft).toMatchObject({ transactionType: { value: "BUY" }, category: { value: "BHK_3" }, locations: [{ value: "Powai" }] });
  });

  it("click-to-chat enquiry quoting a monthly rent", async () => {
    const { extraction } = await analyzeMessage(CLICK_TO_CHAT);
    expect(extraction.draft.budgetMax).toBeUndefined();
    expect(extraction.advertisedPrices?.[0]).toEqual({ value: 65000, evidence: "₹65,000/month" });
  });

  it("ordinary messages: asking prices are separated, budgets are kept", async () => {
    const asking = (await analyzeMessage("Lokhandwala wala 2 BHK ka price 85 hazaar hai kya? Mera budget 70 hazaar tak hai")).extraction;
    expect(asking.draft.budgetMax?.value).toBe(70000);
    expect(asking.advertisedPrices).toEqual([{ value: 85000, evidence: "85 hazaar" }]);

    const hindi = (await analyzeMessage("मुझे बोरीवली वेस्ट में 3 बीएचके खरीदना है, बजट सवा दो करोड़ तक, रेडी टू मूव")).extraction;
    expect(hindi.draft).toMatchObject({
      transactionType: { value: "BUY" }, category: { value: "BHK_3" }, budgetMax: { value: 22_500_000 },
      locations: [{ value: "Borivali West" }], possession: { value: "READY_TO_MOVE" },
    });

    const listed = (await analyzeMessage("That Powai flat listed at 1.8 cr is too much. Can you find 2 BHK under 1.5 cr?")).extraction;
    expect(listed.draft.budgetMax?.value).toBe(15_000_000);
    expect(listed.advertisedPrices?.[0].value).toBe(18_000_000);

    const smalltalk = (await analyzeMessage("Ok thanks, kal baat karte hain 🙏")).extraction;
    expect(smalltalk.draft).toEqual({});
  });
});

describe("real Housing.com enquiries", () => {
  it.each([
    [REAL_HOUSING_1, 27000, "https://dzfki.app.link/XTXUr0PoI6b"],
    [REAL_HOUSING_2, 33000, "https://dzfki.app.link/WswPAG2oI6b"],
  ])("recognises the lead, keeps ₹%s as the listing price and invents nothing", async (text, price, url) => {
    const lead = parsePortalLead(text)!;
    expect(lead).toMatchObject({ portal: "HOUSING_COM", listingUrl: url, listingPrice: { value: price } });
    expect(lead.leadName).toBeUndefined(); // no name in the message → taken from the WhatsApp profile instead
    expect(lead.leadPhone).toBeUndefined(); // the sender is the lead

    const { extraction } = await analyzeMessage(text);
    expect(extraction.draft).toEqual({
      category: { value: "BHK_1", evidence: "1 BHK" },
      locations: [{ value: "Mulund West", evidence: "Mulund West" }],
    });
    // Not stated → not guessed; the agent gets a hint instead.
    expect(extraction.draft.transactionType).toBeUndefined();
    expect(extraction.draft.budgetMax).toBeUndefined();
    const w = extraction.warnings.join(" | ");
    expect(w).toMatch(/doesn't say whether it's for rent or sale \(₹\d{2},000 looks like a monthly rent\)/);
    expect(w).toMatch(/not the client's budget/);
  });
});

describe("real 99acres enquiry", () => {
  it("reads the area and sign-off as written, with the listing ID and no invented price", async () => {
    const lead = parsePortalLead(REAL_99ACRES_1)!;
    expect(lead).toMatchObject({
      portal: "ACRES_99",
      listingUrl: "https://www.99acres.com/I94007278",
      listingRef: "I94007278",
      listingLocality: { value: "Veena Nagar" },
      signedName: "Karishma",
    });
    expect(lead.leadName).toBeUndefined(); // the sender's own name, not a third party
    expect(lead.listingPrice).toBeUndefined();

    const { extraction } = await analyzeMessage(REAL_99ACRES_1);
    expect(extraction.draft).toEqual({
      category: { value: "BHK_1", evidence: "1 BHK" },
      locations: [{ value: "Veena Nagar", evidence: "Veena Nagar" }],
    });
    expect(extraction.advertisedPrices).toEqual([]);
    const w = extraction.warnings.join(" | ");
    expect(w).toMatch(/doesn't say whether it's for rent or sale — confirm/);
    expect(w).toMatch(/budget isn't stated/);
  });

  it("doesn't take a sign-off or area from unrelated text", () => {
    expect(parsePortalLead("Saw your listing on 99acres. Thanks")?.signedName).toBeUndefined();
    expect(parsePortalLead("Saw your 2 BHK on 99acres in Mumbai.")?.listingLocality).toBeUndefined();
  });
});
