import { describe, expect, it } from "vitest";
import { type CallState, type EngineContext, newState, opening, respond, summarize } from "../src/callAssistant/engine.js";
import { detectLanguage } from "../src/callAssistant/language.js";
import { DEFAULT_SCRIPTS } from "../src/callAssistant/prompts.js";
import { spokenPhone, statedName } from "../src/callAssistant/intents.js";
import { extractWithRules } from "../src/voice/rulesExtractor.js";

const CALLER = "+919820012345";
const ctx = (over: Partial<EngineContext> = {}): EngineContext => ({
  callerNumber: CALLER,
  canTransfer: false,
  unclearBehavior: "TAKE_CALLBACK",
  maxUnclearRetries: 2,
  extract: async (t) => extractWithRules(t),
  ...over,
});

/** Plays a conversation; returns every assistant reply and the final state. */
async function talk(lines: string[], c = ctx(), start: CallState = newState("HINGLISH")) {
  let state = start;
  const replies: { say: string[]; action: string }[] = [];
  for (const line of lines) {
    const r = await respond(state, line, c);
    replies.push({ say: r.say, action: r.action });
    state = r.state;
  }
  return { replies, state, last: replies[replies.length - 1] };
}

describe("AI call assistant conversation", () => {
  it("opens as an AI after the broker's recorded greeting", () => {
    const r = opening(newState("HINGLISH"), { recorded: true, script: DEFAULT_SCRIPTS.HINGLISH });
    expect(r.say[0]).toMatch(/main ek AI assistant hoon/);
    expect(r.action).toBe("LISTEN");
    // A spoken (TTS) script that already says "AI assistant" isn't repeated.
    expect(opening(newState("HINGLISH"), { recorded: false, script: DEFAULT_SCRIPTS.HINGLISH }).say).toHaveLength(1);
    expect(opening(newState("ENGLISH"), { recorded: false, script: "Hi, thanks for calling Sharma Realty." }).say[0]).toMatch(/I'm an AI assistant/);
  });

  it("collects a Hinglish requirement one question at a time without re-asking what was said", async () => {
    const { replies, state, last } = await talk([
      "Mujhe Andheri West mein 2 BHK rent pe chahiye",
      "50 hazaar tak",
      "Mera naam Rahul Sharma hai",
      "Semi furnished chahiye aur ek parking",
      "haan",
    ]);
    // The first answer covered type, BHK and area → next question is the budget.
    expect(replies[0].say).toEqual(["Mahine ka rent budget kitna hai?"]);
    expect(replies[1].say).toEqual(["Aapka naam kya hai?"]);
    expect(replies[2].say[0]).toMatch(/Aur kuch zaroori hai/);
    expect(replies[3].say[0]).toMatch(/isi number pe call karein, jo 2 3 4 5 pe khatam/);
    expect(last).toEqual({ say: ["Dhanyavaad! Maine aapki requirement note kar li hai, agent aapko jaldi call karenge."], action: "HANGUP" });
    expect(state.draft).toMatchObject({
      transactionType: { value: "RENT" }, category: { value: "BHK_2" }, locations: [{ value: "Andheri West" }],
      budgetMax: { value: 50000 }, furnishing: { value: ["SEMI_FURNISHED"] }, minParking: { value: 1 },
    });
    expect(state.name?.value).toBe("Rahul Sharma");
    expect(state.callbackRequested).toBe(true);
    expect(summarize(state, CALLER)).toBe(
      "AI call with Rahul Sharma: Rent · 2 BHK · Andheri West · up to ₹50,000 · semi-furnished · 1 parking. " +
        "Also said: \"Semi furnished chahiye aur ek parking\". Callback requested on +919820012345.",
    );
  });

  it("speaks Hindi to a Hindi caller and Marathi to a Marathi caller", async () => {
    const hi = await talk(["मुझे ठाणे में दो बीएचके ख़रीदना है"], ctx(), newState("HINGLISH"));
    expect(hi.state.language).toBe("HINDI");
    expect(hi.last.say).toEqual(["आपका कुल बजट कितना है?"]);

    const mr = await talk(["मला पवई मध्ये भाड्याने फ्लॅट हवा आहे"], ctx(), newState("HINGLISH"));
    expect(mr.state.language).toBe("MARATHI");
    expect(mr.last.say).toEqual(["किती BHK हवा आहे? उदाहरणार्थ 1 BHK, 2 BHK किंवा स्टुडिओ."]);

    const en = await talk(["I want to buy a 3 bhk flat in Thane"], ctx(), newState("HINGLISH"));
    expect(en.state.language).toBe("ENGLISH");
    expect(en.last.say).toEqual(["What's your total budget?"]);
  });

  it("lets callers skip questions and never fills in what they didn't say", async () => {
    const { state, replies } = await talk(["2 BHK rent pe", "pata nahi", "budget abhi nahi bata sakta", "skip", "bas", "haan"]);
    expect(replies[1].say).toEqual(["Koi baat nahi.", "Mahine ka rent budget kitna hai?"]);
    expect(state.draft.locations).toBeUndefined();
    expect(state.draft.budgetMax).toBeUndefined();
    expect(state.name).toBeUndefined();
    expect(state.skipped).toEqual(expect.arrayContaining(["locations", "budget", "name"]));
    expect(state.done).toBe(true);
  });

  it("keeps areas that aren't in the locality list exactly as the caller said them", async () => {
    const { state } = await talk(["1 BHK rent", "Veena Nagar"]);
    expect(state.draft.locations).toEqual([{ value: "Veena Nagar", evidence: "Veena Nagar" }]);
  });

  it("hands over to a person on request only when someone can take the call", async () => {
    const yes = await talk(["mujhe agent se baat karni hai"], ctx({ canTransfer: true }));
    expect(yes.last).toEqual({ say: ["Main aapko agent se connect kar rahi hoon, kripya line pe rahiye."], action: "TRANSFER" });
    expect(yes.state.humanRequested).toBe(true);

    const no = await talk(["can I talk to a real person"], ctx({ canTransfer: false }), newState("ENGLISH"));
    expect(no.last.say).toEqual([
      "I can't connect you to the agent right now, but they'll call you back soon.",
      "Should the agent call you back on this number, ending in 2 3 4 5?",
    ]);
    expect(no.state.callbackRequested).toBe(true);
  });

  it("takes a callback request straight away and asks for a number when caller ID is hidden", async () => {
    const { replies, state } = await talk(
      ["abhi time nahi hai, baad mein call karna", "nau aath do double zero ek do teen chaar paanch"],
      ctx({ callerNumber: null }),
    );
    expect(replies[0].say).toEqual(["Zaroor, agent aapko call back karenge.", "Kis number pe call karein? Agar call nahi chahiye toh 'nahi' boliye."]);
    expect(state.callbackNumber?.value).toBe("+919820012345");
    expect(replies[1].action).toBe("HANGUP");
  });

  it("asks again on unclear answers, then gives up gracefully with a callback", async () => {
    const { replies, state } = await talk(["hmm", "", "achha achha"]);
    expect(replies[0].say).toEqual(["Maaf kijiye, main samajh nahi paayi.", "Boliye, aapko kaisi property chahiye?"]);
    expect(replies[1].action).toBe("LISTEN");
    expect(replies[2]).toEqual({ say: ["Maaf kijiye, line saaf nahi hai. Agent aapko jaldi call back karenge. Dhanyavaad!"], action: "HANGUP" });
    expect(state.callbackRequested).toBe(true);

    const transfer = await talk(["hmm", "hmm", "hmm"], ctx({ unclearBehavior: "TRANSFER", canTransfer: true }));
    expect(transfer.last.action).toBe("TRANSFER");
  });

  it("lets the caller correct an answer", async () => {
    const { state } = await talk(["2 BHK rent Powai", "nahi nahi, 3 BHK chahiye"]);
    expect(state.draft.category?.value).toBe("BHK_3");
  });
});

describe("caller speech helpers", () => {
  it("detects language, names and spoken numbers", () => {
    expect(detectLanguage("mujhe 2 bhk chahiye")).toBe("HINGLISH");
    expect(detectLanguage("मला फ्लॅट हवा आहे")).toBe("MARATHI");
    expect(detectLanguage("मुझे फ़्लैट चाहिए")).toBe("HINDI");
    expect(detectLanguage("ok")).toBeNull();
    expect(statedName("mera naam Priya hai")).toBe("Priya");
    expect(statedName("I am looking for a flat")).toBeNull();
    expect(statedName("माझं नाव अमित आहे")).toBe("अमित");
    expect(spokenPhone("98200 12345")).toBe("+919820012345");
    expect(spokenPhone("nine eight two zero zero one two three four five")).toBe("+919820012345");
    expect(spokenPhone("no")).toBeNull();
    expect(spokenPhone("2 BHK chahiye")).toBeNull();
  });
});
