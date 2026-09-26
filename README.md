# BrokerBuddy

A real estate CRM for Mumbai brokers. It manages clients, remembers each client's property requirements, organizes leads, and matches clients with available properties.

| Part | Stack |
|---|---|
| `backend/` | Node.js 22, TypeScript, Express 5, Prisma 6, PostgreSQL 16, Zod, JWT |
| `android/app` | Kotlin, Jetpack Compose, Material 3, Retrofit, WorkManager |
| `android/core` | Pure-Kotlin module with the API models, INR (lakh/crore) formatting and phone normalization. It builds without the Android SDK. |

## Status

| Phase | Scope | Status |
|---|---|---|
| 1 | CRM, dashboards, requirements, inventory, matching, reminders, team | **Done** (backend fully tested; Android compiles, passes lint and builds in CI) |
| 2 | Voice notes (Hindi/Hinglish/Marathi/English) → AI requirement extraction | **Done** (backend tested; Android compiles, passes lint and builds in CI) |
| 3 | Caller screen for known clients (incoming-call detection) | **Done** (backend tested; Android compiles, passes lint and builds in CI; device testing pending — see `docs/CALLER_SCREEN.md`) |
| 4 | WhatsApp Business Cloud API; 99acres / Housing.com / Magicbricks lead recognition | **Done** (backend tested; Android compiles, passes lint and builds in CI; not tested against Meta's live API — see `docs/WHATSAPP.md`) |
| 5 | Security hardening, deployment, signed APK and download site | Signing config and CI are in place |
| — | AI Call Assistant: AI receptionist with the broker's recorded greeting, requirement capture into the CRM | **Backend built and tested** (typed conversations and simulated provider calls); app compiles, passes lint and builds in CI; needs a telephony number to take real calls — see `docs/AI_CALL_ASSISTANT.md` |
| — | Property photos (encrypted file storage) | Backend and app done (gallery, add/remove photos) |
| — | Sign in with an SMS code (MSG91) | Backend tested with a simulated MSG91; app sign-in and "Your mobile number" screens built. Needs your MSG91 account and DLT template — see `docs/OTP_LOGIN.md` |

## Phase 1: what works

**Clients**
- Name, phones, email, lead source, status, assignee and notes.
- **Duplicate prevention.** Every number is normalized to E.164 (`098200-12345`, `+91 98200 12345` and `919820012345` are all the same number). A unique database index on `(brokerage, phone)` backs this up, so a concurrent create can't slip through.
- The app checks for duplicates while the broker types and offers to open the existing profile.
- Alternate numbers are covered by the same duplicate check.

**Inquiries (requirements)**
- A client can have any number of separate inquiries, for example renting a 1 BHK now and buying a 3 BHK later.
- Fields: budget range, preferred localities, acceptable furnishing, minimum parking, floor range, possession status and date, and must-have flags.
- A field the client didn't mention stays `null`. It is shown as "Not specified" and never filled with a guess.

**Change history**
- Every edit appends an `InquiryRevision` with a field-level diff and a full snapshot.
- Writes use optimistic concurrency, so two brokers editing at once can't silently overwrite each other.

**Property inventory**
- Stored in a separate `Property` table: price or rent, deposit, locality, building, area, BHK, furnishing, parking, floor, possession and availability.

**Matching** (`backend/src/domain/matching.ts`)
- Always excluded: a different transaction type, a different category, or a listing that isn't available.
- **A failed must-have excludes the property.** Up to 10% over budget counts as "near" (half credit) only when budget isn't a must-have.
- If the listing lacks the data to check a must-have, the property is kept and flagged "verify with owner". It is neither passed nor dropped silently.
- Localities are normalized for Mumbai: `Andheri (W)` = `Andheri West`, and `Andheri` alone covers both East and West.
- Results are scored 0–100 with a per-field explanation, then ranked by score and price.
- Matching also runs in reverse: a property page lists the clients who fit it.

**Dashboards**
- Separate Rent and Buy boards with live counts of active inquiries (and distinct clients) for Studio, 1–4 BHK, 5+ BHK, Commercial and Other.
- The app refreshes every 30 seconds while the board is visible, and you can pull to refresh.
- Tapping a tile opens the matching inquiries.

**Follow-up reminders**
- Stored on the server and assigned to a team member.
- The app syncs every 15 minutes with WorkManager and arms alarms, which survives reboots.
- Overdue items are notified once.
- If notifications are blocked, a banner explains that follow-ups still appear in the list, with a button to the settings page.

**Team**
- Each brokerage is a separate tenant, and all data is isolated between brokerages.
- Roles are OWNER, ADMIN and AGENT. Owners and admins add members, and deactivating a member cuts off access immediately.
- Only owners and admins can delete clients or properties.

**Contact actions**
- Call opens the dialer; no call permission is needed.
- WhatsApp uses the official `wa.me` click-to-chat link and falls back to the browser. SMS is also available.

## Phase 2: voice notes

**Recording and review in the app**
- Tap the mic on a client (a new note) or on a requirement (an update).
- The app records mono AAC, up to 3 minutes, and uploads it with the chosen language: Auto, Hindi, Hinglish, Marathi or English.
- On the review screen the agent can:
  - read and correct the transcript (the speech-to-text original is kept);
  - see warnings to check;
  - choose which inquiry the note belongs to: a suggestion, any of the client's inquiries, or a new one;
  - edit a pre-filled requirement form. Each extracted field shows the exact words it came from (🎙 “60 se 70 hazaar”), and on an existing requirement, its previous value.
- Nothing is saved until the agent taps Save.

**Server pipeline** (`backend/src/voice/`): audio → speech-to-text → extraction → evidence check.
- **Speech-to-text:** `transcriber.ts` works with any Whisper-style `/audio/transcriptions` service (`STT_URL`). Hindi and Marathi send a language hint. Hinglish uses auto-detection plus a romanized prompt, so the transcript stays in Latin script.
- **Extraction:** `claudeExtractor.ts` uses Claude (`claude-opus-5`, structured JSON output) when an Anthropic key is configured. Otherwise, or if the Claude call fails, `rulesExtractor.ts` handles Hindi (Devanagari), Hinglish, Marathi and English. It understands:
  - lakh/crore/hazaar and number words (डेढ़, dhai, साढ़े तीन, चाळीस);
  - ranges ("60 se 70 hazaar") and max/min phrasing, including "35000 se zyada nahi";
  - 70+ Mumbai localities with East/West;
  - "Khar nahi chahiye" (excluded area), and deposit amounts, which are not treated as budget;
  - parking counts, floor ranges, and possession status and date.
- **Never invents:**
  - Every extracted value must quote the transcript word for word. `verifyDraft` drops any value whose quote isn't actually in the transcript; this is how Claude hallucinations are caught.
  - When something is ambiguous (two sizes, rent *and* buy, "high floor" with no number), the field is left empty and a warning is added.
  - A voice note fills only what was said. Existing values stay, and new locations are added to the old ones rather than replacing them.
- **Fallbacks:**
  - Microphone permission denied, no speech-to-text configured, or transcription failed → the recording is kept and the agent types or dictates the note.
  - Upload failed → the recording stays on the phone and can be re-sent.
  - AI unavailable → the built-in rules are used, with a warning.
- **History and association:**
  - Saving creates or updates the inquiry inside one database transaction.
  - It writes a `VOICE_NOTE` history entry linked to the voice note (`InquiryRevision.voiceNoteId`); the history tab shows the transcript.
  - A note can only be saved to an inquiry of its own client, can't be saved twice, and never crosses brokerages.

API: `POST /voice-notes` (multipart `audio`, `clientId`, `inquiryId?`, `language`, `durationMs`) · `POST /voice-notes/text` · `GET /voice-notes?clientId=` · `GET /voice-notes/:id` · `GET /voice-notes/:id/audio` · `PUT /voice-notes/:id/transcript` · `POST /voice-notes/:id/retry` · `POST /voice-notes/:id/apply` · `POST /voice-notes/:id/discard`

## Phase 3: caller screen

**Detecting calls:** Android's official `CallScreeningService` with the call-screening role, which the user grants (Android 10+).
- BrokerBuddy never blocks or delays calls.
- It does not use the restricted call-log permissions.

**What the agent sees:**
- A silent heads-up notification, always. It has an inline *Add note* reply, plus *Profile*, *Matches* or *Create client*.
- A Truecaller-style floating card, if "Display over other apps" is allowed.
- A full caller screen in the app.

**What the card shows:**
- the client, status and agent;
- **every open inquiry**, with budget, locations, must-haves and a live count of matching properties;
- the last conversation (note or voice note);
- pending follow-ups.

**Unknown numbers:** get *Create client*, with the number pre-filled.

**Speed and offline:** a cached phone → name directory shows the name instantly, and offline too.

**Setup:** Settings → Caller screen checks each permission and has a *Simulate incoming call* test.

**More detail:** Android restrictions, limitations (Android 8–9, only one screening app at a time, calls from saved contacts, VoIP calls, OEM battery managers) and the device test plan are in [`docs/CALLER_SCREEN.md`](docs/CALLER_SCREEN.md).

API: `GET /caller/lookup?phone=` · `GET /caller/directory` · `GET|POST /clients/:id/notes`

## Phase 4: WhatsApp and portal leads

**How messages arrive:** the official WhatsApp Cloud API delivers them by webhook.
- Each brokerage connects its own number and gets its own webhook URL.
- Every event is signature-checked, and Meta's retries are ignored.
- The access token and app secret are stored encrypted.

**Who a message is about:**
- Clients are identified by phone number.
- Unknown senders go to a **WhatsApp leads** inbox. *Create client* is duplicate-safe: an existing number links to that client instead.
- Portal leads forwarded by an agent are recognised as forwards, so the agent never becomes a client.

**Portal leads (99acres, Housing.com, Magicbricks):** the lead's name, phone, email and listing are recognised. **The advertised price is kept separate and masked before extraction**, so it never becomes the client's budget.

**Requirements:**
- Drafts are extracted with the Phase 2 extractor and reviewed before saving.
- Saving records a `WHATSAPP` revision linked to the message.
- The client's conversation history is kept, including WhatsApp voice messages (transcribed).

**Without the API:**
- share a message, or an exported chat, into the app from WhatsApp;
- paste text on the import screen;
- use *Open in WhatsApp* to send.

Credentials, Meta setup steps and limitations are in [`docs/WHATSAPP.md`](docs/WHATSAPP.md).

## Design

The UI follows the BrokerBuddy home-screen design:
- **Colours:** navy/blue brand, a light-blue page background, and white rounded cards with pastel category colours.
- **Font:** Poppins, bundled with its SIL Open Font License. It includes Devanagari and the ₹ sign.
- **Home screen:**
  - a greeting card with Mumbai skyline artwork;
  - stat tiles (clients, enquiries, properties, follow-ups) with new-this-week trends;
  - a Rent/Buy switch and category chips;
  - today's follow-ups, new leads (including portal and WhatsApp leads) and top property matches.
- **Navigation:** a bottom bar with a centre microphone for a quick voice note.

Every number on the home screen comes from real data (`GET /dashboard`); nothing is decorative placeholder data.
Property cards show a placeholder illustration until listing photos are supported.

`docs/design-preview-home.png` is an HTML approximation of the home screen with sample data, for design review only; it is not a screenshot of the app.

## Running locally

To try the app on a real phone, follow [`docs/TESTING_ON_A_PHONE.md`](docs/TESTING_ON_A_PHONE.md) (APK download, server setup, test checklist, what's still partial). 99acres / Housing.com leads: [`docs/PORTAL_LEADS.md`](docs/PORTAL_LEADS.md). To look around without a server, tap **Try the demo** on the sign-in screen (sample data, read-only).

### Backend
```bash
cd backend
cp .env.example .env          # set DATABASE_URL and a 32+ char JWT_SECRET
npm install
npx prisma migrate deploy
npm run db:seed               # optional demo data: demo@brokerbuddy.local / demo12345
npm run dev                   # http://localhost:4000, API under /api/v1
npm test                      # needs a Postgres test DB (TEST_DATABASE_URL)
```

### Android
Open `android/` in Android Studio, or run `./gradlew :app:assembleDebug` with the Android SDK installed.
- On the login screen, set the server address. `http://10.0.2.2:4000` reaches your computer from the emulator; on a phone, use your computer's LAN IP.
- Debug builds allow plain HTTP; release builds require HTTPS.
- `./gradlew :core:test` runs the pure-Kotlin tests without the Android SDK.

Release signing reads `android/keystore.properties` (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`) or the `BB_KEYSTORE_*` environment variables. This file is git-ignored.

### CI
`.github/workflows/ci.yml` runs:
- Backend typecheck and tests against Postgres.
- Android unit tests, lint and the debug APK build. The APK is uploaded as a build artifact.

## API overview (`/api/v1`)
`auth/register`, `auth/login`, `auth/me` ·
`clients` (list/search, create, get, patch, delete, `check-duplicate`, `lookup`, `:id/phones`, `:id/inquiries`) ·
`inquiries` (list by type/category, get, patch, `:id/history`, `:id/matches`) ·
`properties` (CRUD, `:id/matches`) · `dashboard` · `reminders` (list, create, patch, delete) · `team` (list, add, patch)

## Plans for the next phases
- **Phase 5 – production readiness.**
  - Rate limiting, stored tokens encrypted with the Android Keystore, audit logs, backups.
  - Docker deployment and HTTPS.
  - A release-signed APK on a download page, with version checks.
