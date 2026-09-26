# AI Call Assistant

An AI receptionist that answers calls to your **business number**. It greets callers
with your recorded greeting, notes their property requirements, and saves them as a
lead with a callback follow-up.

It works on a cloud business number from a telephony provider, not on your personal
SIM. Android does not let an app answer ordinary calls with an AI. You can still forward
missed calls from your mobile to the business number (see *Forwarding your mobile* below).

## What works today

| Part | Status |
|---|---|
| Settings: on/off, Direct / AI Receptionist / Smart modes, hours, callbacks, hand-over, unclear-caller behaviour | ✅ Backend + app |
| Greetings in Hinglish, Hindi, English and Marathi. Editable scripts; record in the app, upload MP3/WAV, preview before saving, replace, delete, reset | ✅ Backend + app |
| Conversation: one question at a time, skips, callback and human requests, unclear answers, caller-language detection, never invents details | ✅ Tested with typed conversations |
| **Test your assistant**: type as a caller in the app; nothing is saved | ✅ |
| Saving to the CRM: existing client matched by phone (no duplicates), requirement with history, call note with summary and transcript, callback follow-up | ✅ Tested |
| Duplicate webhooks, dropped calls, missing "call ended" events | ✅ Tested |
| Twilio adapter (TwiML, speech input, signature checks) | ⚠️ Written from Twilio's documentation and unit-tested. **Not yet tried on a live account or an Indian number.** |
| Voice option B (natural TTS voice) | ⏳ Not built. The app shows it as unavailable |
| Voice option C (custom AI voice) | ⏳ Not built. BrokerBuddy does not clone voices. It will only be offered through a provider with explicit, verified consent |
| Instant push notification to the broker | ⏳ Follow-ups appear through the app's periodic reminder sync (up to ~15 min). Instant push needs Firebase Cloud Messaging |

## Call flow

```
Caller dials the business number
  → telephony provider → POST /telephony/twilio/voice
  → BrokerBuddy finds the account by the dialled number and checks its settings
      Direct mode / assistant off  → ring the team number (no AI, nothing stored)
      Smart mode, business hours   → ring the team for 20 s; if unanswered → AI
      AI Receptionist / after hours → AI
  → greeting: the broker's recording (if enabled and recorded) or the script, read by the AI voice
  → "Just so you know, I'm an AI assistant…" (always, after a recorded greeting)
  → questions: rent/buy → BHK → area → budget → name → anything else → callback number
      "talk to a person"  → transfer to the team number (if allowed and within hours), else callback
      "call me back"      → confirm number, end
      unclear ×N          → callback or transfer (setting)
  → call ends → POST /telephony/twilio/status → saved to the CRM once
```

The AI **always says it is an AI assistant**, even when the greeting is in the broker's
own voice. It never claims to be the broker.

## What gets saved

For each call that reached the AI, if the caller's number is known:

- **Client:**
  - Matched by phone number, through the same duplicate check as every other channel.
  - An existing client's name, source and details are never overwritten.
  - A new client gets source *AI Call Assistant* and status *New*.
  - With no name given, the client is labelled `Caller 98200 12345`; no name is guessed.
- **Requirement:**
  - Updates the client's matching active enquiry, or creates one when rent/buy and the property type were given.
  - Only stated fields are set, and each change is recorded in the history (source *AI call*).
- **Note** with the summary, the call status and duration, and the transcript (source *AI call*).
- **Follow-up** "Call back …" when the caller asked for a callback, or when the call dropped before the end. Due time and assignee come from the settings.
- **Call log:** Settings → AI Call Assistant → *Recent AI calls*.

Calls from hidden numbers are logged but create no client.

## Setting up a phone number (needs your decision and accounts)

Nothing has been bought or signed up for. You need to:

1. **Choose a telephony provider** with Indian business numbers that support:
   - inbound calls to a webhook;
   - speech recognition for Hindi (hi-IN), Marathi (mr-IN) and English (en-IN);
   - text-to-speech in those languages;
   - playing an audio file from a URL.

   Before paying, confirm with each provider what's available for your business in India.
   Indian virtual numbers need KYC and DoT-compliant paperwork, and capabilities vary by
   number type. BrokerBuddy includes a Twilio-format adapter. Exotel, Plivo and Knowlarity
   are common Indian options, but each would need its own adapter (the call-flow core is
   provider-independent: `backend/src/callAssistant/sessions.ts`).
2. **Deploy the backend on HTTPS** and set on the server:
   - `PUBLIC_BASE_URL`: the exact public address;
   - `TWILIO_AUTH_TOKEN`;
   - `DATA_ENCRYPTION_KEY`: encrypts stored greetings and photos;
   - `STORAGE_DRIVER` / `S3_*` for production file storage.
3. **Point the number at BrokerBuddy** (Twilio console → phone number):
   - *A call comes in* → `POST {PUBLIC_BASE_URL}/telephony/twilio/voice`
   - *Call status changes* → `POST {PUBLIC_BASE_URL}/telephony/twilio/status`
4. In the app, go to **Settings → AI Call Assistant**:
   - enter the business number and the team number;
   - pick the mode and record the greetings;
   - switch it on;
   - make a real test call.

### Forwarding your mobile

To send only missed calls from your own mobile to the business number, use your
operator's conditional call forwarding (set in the phone's Call settings → Call
forwarding: *when busy / unanswered / unreachable*). Your operator may charge for
forwarded calls. BrokerBuddy does not change forwarding settings and does not intercept calls.

## Audio

- The app records greetings as 16 kHz mono WAV, a format phone services play reliably.
- Uploads must be MP3 or WAV, 1–60 seconds. The format is checked from the file's content, not its name.
- M4A/AAC is rejected with a clear message rather than converted, because conversion would need ffmpeg on the server.
- Recordings are stored outside the database (local folder or S3-compatible bucket), encrypted with `DATA_ENCRYPTION_KEY`.
- The phone provider gets a signed link that expires after an hour.

## Privacy and disclosure

- The assistant tells every caller that it is an AI and that what they say is being noted for the agent.
- Call audio is **not recorded** by BrokerBuddy. The provider's speech recognition turns speech into text; only that text (the transcript) and the collected details are stored.
- If you enable call recording at your provider, you must add your own disclosure to the greeting.
- Callers' personal data is processed under your business's privacy obligations (for example India's DPDP Act 2023). Check the notice wording with your adviser.

## Known limitations

- The Twilio adapter is untested live, and speech-recognition quality for Hinglish and Marathi on phone audio is unknown until tried.
- The conversation is turn-based: the caller speaks after the assistant finishes (no barge-in).
- Requirement extraction uses the configured extractor. Claude (`ANTHROPIC_API_KEY`) or the built-in rules both work; the rules handle common Mumbai phrasing but not everything.
- Smart mode rings a single team number. Ringing several agents in turn is not built.
