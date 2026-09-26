import { afterEach, beforeEach, describe, expect, it } from "vitest";
import request from "supertest";
import type Anthropic from "@anthropic-ai/sdk";
import { app, registerBroker, resetDb } from "./helpers.js";
import { setVoiceServices, rulesExtractor, type RequirementExtractor } from "../src/voice/service.js";
import type { Transcriber } from "../src/voice/transcriber.js";
import { ClaudeExtractor, CLAUDE_MODEL } from "../src/voice/claudeExtractor.js";

const HINGLISH =
  "Rahul ji ko Andheri West ya Jogeshwari mein 2 BHK chahiye rent pe, budget 60 se 70 hazaar tak, semi furnished chalega, ek car parking zaroori hai.";
const HINDI = "क्लाइंट को पवई में 3 बीएचके खरीदना है, बजट डेढ़ करोड़ से दो करोड़ तक, रेडी टू मूव चाहिए।";
const FAKE_AUDIO = Buffer.from("fake-aac-bytes");

const fakeTranscriber = (text: string | Error): Transcriber & { calls: string[] } => {
  const calls: string[] = [];
  return {
    name: "stt:fake",
    calls,
    async transcribe(_audio, mime, language) {
      calls.push(`${mime}:${language}`);
      if (text instanceof Error) throw text;
      return text;
    },
  };
};

beforeEach(async () => {
  await resetDb();
  setVoiceServices({ transcriber: null, extractor: rulesExtractor });
});
afterEach(() => setVoiceServices(null));

async function setup() {
  const broker = await registerBroker();
  const client = (await broker.api.post("/clients", { name: "Rahul", phone: "9820012345", leadSource: "WALK_IN" })).body.client;
  const upload = (fields: Record<string, string>, mime = "audio/mp4") => {
    let r = request(app).post("/api/v1/voice-notes").set("Authorization", `Bearer ${broker.token}`);
    for (const [k, v] of Object.entries(fields)) r = r.field(k, v);
    return r.attach("audio", FAKE_AUDIO, { filename: "note.m4a", contentType: mime });
  };
  return { ...broker, client, upload };
}

describe("voice notes: recording → transcript → draft → review → save", () => {
  it("transcribes a Hinglish recording and extracts a draft with evidence", async () => {
    const stt = fakeTranscriber(HINGLISH);
    setVoiceServices({ transcriber: stt, extractor: rulesExtractor });
    const { upload, client, api } = await setup();

    const res = await upload({ clientId: client.id, language: "HINGLISH", durationMs: "14000" });
    expect(res.status).toBe(201);
    expect(stt.calls).toEqual(["audio/mp4:HINGLISH"]);
    const note = res.body.voiceNote;
    expect(note.status).toBe("READY");
    expect(note.transcript).toBe(HINGLISH);
    expect(note.originalTranscript).toBe(HINGLISH);
    expect(note.transcriptSource).toBe("stt:fake");
    expect(note.hasAudio).toBe(true);
    expect(note.audio).toBeUndefined(); // bytes never sent in JSON
    const d = note.extraction.draft;
    expect(d.transactionType).toEqual({ value: "RENT", evidence: "rent" });
    expect(d.category.value).toBe("BHK_2");
    expect([d.budgetMin.value, d.budgetMax.value]).toEqual([60000, 70000]);
    expect(d.locations.map((l: { value: string }) => l.value)).toEqual(["Andheri West", "Jogeshwari"]);
    expect(d.possession).toBeUndefined(); // not said → not invented

    const audio = await api.get(`/voice-notes/${note.id}/audio`);
    expect(audio.status).toBe(200);
    expect(audio.headers["content-type"]).toContain("audio/mp4");
    expect(Buffer.from(audio.body).equals(FAKE_AUDIO)).toBe(true);
  });

  it("saves the agent-corrected draft as a new inquiry with VOICE_NOTE history", async () => {
    const { api, client } = await setup();
    const created = await api.post("/voice-notes/text", { clientId: client.id, language: "HINDI", transcript: HINDI });
    expect(created.status).toBe(201);
    const draft = created.body.voiceNote.extraction.draft;
    expect(draft.category.value).toBe("BHK_3");
    expect(draft.budgetMax.value).toBe(20_000_000);
    expect(created.body.suggestedInquiryId).toBeNull(); // client has no inquiries yet

    // Agent reviews: corrects max budget, marks location mandatory.
    const applied = await api.post(`/voice-notes/${created.body.voiceNote.id}/apply`, {
      requirement: {
        transactionType: "BUY",
        category: "BHK_3",
        budgetMin: 15_000_000,
        budgetMax: 22_500_000,
        locations: ["Powai"],
        possession: "READY_TO_MOVE",
        mandatory: ["LOCATION"],
      },
    });
    expect(applied.status).toBe(200);
    expect(applied.body.inquiry).toMatchObject({ source: "VOICE_NOTE", budgetMax: 22_500_000, clientId: client.id, version: 1 });
    expect(applied.body.voiceNote).toMatchObject({ status: "APPLIED", inquiryId: applied.body.inquiry.id, appliedVersion: 1 });

    const history = await api.get(`/inquiries/${applied.body.inquiry.id}/history`);
    expect(history.body.revisions[0]).toMatchObject({ source: "VOICE_NOTE", voiceNote: { id: created.body.voiceNote.id } });

    const again = await api.post(`/voice-notes/${created.body.voiceNote.id}/apply`, { requirement: { transactionType: "BUY", category: "BHK_3" } });
    expect(again.status).toBe(409);
  });

  it("updates an existing inquiry, keeping unspecified fields and recording a diff", async () => {
    const { api, client } = await setup();
    const inquiry = (
      await api.post(`/clients/${client.id}/inquiries`, {
        transactionType: "RENT", category: "BHK_2", budgetMax: 55000, locations: ["Andheri"], minParking: 1, mandatory: ["BUDGET"],
      })
    ).body.inquiry;
    const note = (
      await api.post("/voice-notes/text", {
        clientId: client.id,
        inquiryId: inquiry.id,
        transcript: "Rahul ne bola budget ab 65 hazaar tak badha sakte hain, aur Jogeshwari bhi chalega",
      })
    ).body;
    expect(note.suggestedInquiryId).toBe(inquiry.id);
    expect(note.voiceNote.extraction.draft.budgetMax.value).toBe(65000);

    const applied = await api.post(`/voice-notes/${note.voiceNote.id}/apply`, {
      inquiryId: inquiry.id,
      requirement: { budgetMax: 65000, locations: ["Andheri", "Jogeshwari"] },
    });
    expect(applied.status).toBe(200);
    expect(applied.body.inquiry).toMatchObject({ version: 2, budgetMax: 65000, minParking: 1, source: "VOICE_NOTE" });
    const [latest, first] = (await api.get(`/inquiries/${inquiry.id}/history`)).body.revisions;
    expect(latest.changes).toEqual({
      budgetMax: { from: 55000, to: 65000 },
      locations: { from: ["Andheri"], to: ["Andheri", "Jogeshwari"] },
    });
    expect(latest.voiceNote.id).toBe(note.voiceNote.id);
    expect(first.source).toBe("MANUAL");
  });

  it("falls back to a typed transcript when speech-to-text is not configured", async () => {
    const { upload, client, api } = await setup();
    const res = await upload({ clientId: client.id });
    expect(res.status).toBe(201);
    expect(res.body.voiceNote.status).toBe("NEEDS_TRANSCRIPT");
    expect(res.body.voiceNote.error).toMatch(/not configured/);
    expect(res.body.voiceNote.extraction).toBeNull();

    const cannotApply = await api.post(`/voice-notes/${res.body.voiceNote.id}/apply`, { requirement: {} });
    expect(cannotApply.status).toBe(400);

    const typed = await api.put(`/voice-notes/${res.body.voiceNote.id}/transcript`, { transcript: "Malad West mein 1 BHK kiraye pe, 30 hazaar tak" });
    expect(typed.body.voiceNote).toMatchObject({ status: "READY", transcriptSource: "manual", originalTranscript: "Malad West mein 1 BHK kiraye pe, 30 hazaar tak" });
    expect(typed.body.voiceNote.extraction.draft.budgetMax.value).toBe(30000);
  });

  it("keeps the recording when transcription fails and can retry", async () => {
    setVoiceServices({ transcriber: fakeTranscriber(new Error("upstream 503")), extractor: rulesExtractor });
    const { upload, client, api } = await setup();
    const res = await upload({ clientId: client.id, language: "HINDI" });
    expect(res.body.voiceNote).toMatchObject({ status: "NEEDS_TRANSCRIPT", hasAudio: true });
    expect(res.body.voiceNote.error).toMatch(/upstream 503/);

    setVoiceServices({ transcriber: fakeTranscriber(HINDI), extractor: rulesExtractor });
    const retried = await api.post(`/voice-notes/${res.body.voiceNote.id}/retry`);
    expect(retried.body.voiceNote).toMatchObject({ status: "READY", originalTranscript: HINDI });
  });

  it("an agent's transcript correction is kept separately from the original", async () => {
    setVoiceServices({ transcriber: fakeTranscriber("Andheri mein 2 BHK, 50 hazaar tak"), extractor: rulesExtractor });
    const { upload, client, api } = await setup();
    const id = (await upload({ clientId: client.id })).body.voiceNote.id;
    const fixed = await api.put(`/voice-notes/${id}/transcript`, { transcript: "Andheri mein 2 BHK, 60 hazaar tak" });
    expect(fixed.body.voiceNote.originalTranscript).toBe("Andheri mein 2 BHK, 50 hazaar tak");
    expect(fixed.body.voiceNote.transcript).toBe("Andheri mein 2 BHK, 60 hazaar tak");
    expect(fixed.body.voiceNote.extraction.draft.budgetMax.value).toBe(60000);
  });
});

describe("voice notes: association with the right client and inquiry", () => {
  it("rejects an inquiry that belongs to another client, on record and on save", async () => {
    const { api, client, upload } = await setup();
    const other = (await api.post("/clients", { name: "Priya", phone: "9867000000", leadSource: "REFERRAL" })).body.client;
    const othersInquiry = (await api.post(`/clients/${other.id}/inquiries`, { transactionType: "RENT", category: "BHK_1" })).body.inquiry;

    expect((await upload({ clientId: client.id, inquiryId: othersInquiry.id })).status).toBe(400);
    const note = (await api.post("/voice-notes/text", { clientId: client.id, transcript: "1 BHK rent" })).body.voiceNote;
    const res = await api.post(`/voice-notes/${note.id}/apply`, { inquiryId: othersInquiry.id, requirement: { budgetMax: 1 } });
    expect(res.status).toBe(400);
    expect((await api.get(`/inquiries/${othersInquiry.id}`)).body.inquiry.version).toBe(1);
  });

  it("isolates brokerages", async () => {
    const a = await setup();
    const b = await registerBroker("Other");
    expect((await b.api.post("/voice-notes/text", { clientId: a.client.id, transcript: "2 BHK" })).status).toBe(404);
    const note = (await a.api.post("/voice-notes/text", { clientId: a.client.id, transcript: "2 BHK" })).body.voiceNote;
    expect((await b.api.get(`/voice-notes/${note.id}`)).status).toBe(404);
    expect((await b.api.post(`/voice-notes/${note.id}/apply`, { requirement: {} })).status).toBe(404);
  });

  it("suggests the matching inquiry and warns when the chosen one doesn't fit", async () => {
    const { api, client } = await setup();
    const rent = (await api.post(`/clients/${client.id}/inquiries`, { transactionType: "RENT", category: "BHK_2" })).body.inquiry;
    await api.post(`/clients/${client.id}/inquiries`, { transactionType: "BUY", category: "BHK_3" });

    const suggested = (await api.post("/voice-notes/text", { clientId: client.id, transcript: "2 BHK kiraye pe, Powai" })).body;
    expect(suggested.suggestedInquiryId).toBe(rent.id);
    expect(suggested.inquiries).toHaveLength(2);

    const mismatched = (
      await api.post("/voice-notes/text", { clientId: client.id, inquiryId: rent.id, transcript: "3 BHK kharidna hai" })
    ).body;
    expect(mismatched.targetWarnings.join(" ")).toMatch(/BUY.*RENT/);
  });

  it("validates uploads", async () => {
    const { upload, client, api } = await setup();
    expect((await upload({ clientId: client.id }, "image/png")).status).toBe(415);
    expect((await api.post("/voice-notes", {})).status).toBe(400);
    expect((await upload({ clientId: "nope" })).status).toBe(404);
  });

  it("lists a client's notes and supports discarding", async () => {
    const { api, client } = await setup();
    const note = (await api.post("/voice-notes/text", { clientId: client.id, transcript: "2 BHK" })).body.voiceNote;
    expect((await api.get(`/voice-notes?clientId=${client.id}`)).body.voiceNotes).toHaveLength(1);
    expect((await api.post(`/voice-notes/${note.id}/discard`)).body.voiceNote.status).toBe("DISCARDED");
    expect((await api.post(`/voice-notes/${note.id}/apply`, { requirement: {} })).status).toBe(409);
  });
});

describe("AI extraction", () => {
  const fakeClaude = (output: unknown, stop_reason = "end_turn") => {
    const calls: Record<string, unknown>[] = [];
    const client = {
      beta: { messages: { parse: async (params: Record<string, unknown>) => { calls.push(params); return { stop_reason, parsed_output: output }; } } },
    } as unknown as Anthropic;
    return { client, calls };
  };
  const empty = {
    transactionType: null, category: null, budgetMinRupees: null, budgetMaxRupees: null, locations: [], furnishing: null,
    minParking: null, floorMin: null, floorMax: null, possession: null, possessionBy: null, warnings: [],
  };

  it("sends the right request and drops hallucinated or out-of-range values", async () => {
    const { client, calls } = fakeClaude({
      ...empty,
      transactionType: { value: "RENT", evidence: "kiraye pe" },
      category: { value: "BHK_2", evidence: "do bhk" },
      budgetMaxRupees: { value: 65000, evidence: "65 hazaar tak" },
      locations: [{ value: "Andheri West", evidence: "Andheri West" }, { value: "Juhu", evidence: "Juhu" }],
      minParking: { value: 500, evidence: "parking" },
      possessionBy: { value: "soon", evidence: "65 hazaar tak" },
      warnings: ["Read 65 hazaar as maximum"],
    });
    const result = await new ClaudeExtractor(client).extract("Andheri West mein do bhk kiraye pe, 65 hazaar tak, parking", "HINGLISH");
    expect(calls[0]).toMatchObject({ model: CLAUDE_MODEL, thinking: { type: "adaptive" }, fallbacks: "default" });
    expect(JSON.stringify(calls[0].messages)).toContain("Hinglish");
    expect(result.draft).toEqual({
      transactionType: { value: "RENT", evidence: "kiraye pe" },
      category: { value: "BHK_2", evidence: "do bhk" },
      budgetMax: { value: 65000, evidence: "65 hazaar tak" },
      locations: [{ value: "Andheri West", evidence: "Andheri West" }],
    });
    const w = result.warnings.join(" | ");
    expect(w).toMatch(/Juhu/);
    expect(w).toMatch(/parking 500/);
    expect(w).toMatch(/possession date/);
  });

  it("treats refusals as failures and the pipeline falls back to rules", async () => {
    const { client } = fakeClaude(null, "refusal");
    await expect(new ClaudeExtractor(client).extract("2 BHK", "AUTO")).rejects.toThrow(/declined/);

    const failing: RequirementExtractor = { name: "claude:test", extract: async () => { throw new Error("overloaded"); } };
    setVoiceServices({ transcriber: null, extractor: failing });
    const { api, client: c } = await setup();
    const res = await api.post("/voice-notes/text", { clientId: c.id, transcript: HINGLISH });
    expect(res.status).toBe(201);
    expect(res.body.voiceNote.extractor).toBe("rules");
    expect(res.body.voiceNote.extraction.warnings[0]).toMatch(/AI extraction was unavailable \(overloaded\)/);
    expect(res.body.voiceNote.extraction.draft.category.value).toBe("BHK_2");
  });
});
