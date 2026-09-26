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
| 1 | CRM, dashboards, requirements, inventory, matching, reminders, team | **Done** (backend fully tested; Android app compiled in CI) |
| 2 | Voice notes (Hindi/Hinglish/Marathi/English) → AI requirement extraction | Not started |
| 3 | Caller screen for known clients (incoming-call detection) | Backend lookup endpoint ready (`GET /clients/lookup`) |
| 4 | WhatsApp Business Cloud API; 99acres / Housing.com / Magicbricks lead parsing | Not started (the app has a `wa.me` click-to-chat fallback) |
| 5 | Security hardening, deployment, signed APK and download site | Signing config and CI are in place |

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

## Running locally

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
- **Phase 2 – voice notes.**
  - Record in the app, upload the audio, then transcribe it with a multilingual speech-to-text service.
  - An LLM extracts a *draft* requirement using a strict JSON schema: only fields the speaker actually said, each with its source quote.
  - The broker reviews and confirms the draft. It is saved as a revision with `source = VOICE_NOTE`.
  - If AI is unavailable: keep the audio and transcript, and let the broker fill the form manually.
- **Phase 3 – caller screen.**
  - Use Android's `CallScreeningService`, with the user granting the call-screening role, plus an overlay or notification for known numbers, filled from `GET /clients/lookup`.
  - No call-log scraping, and no restricted permissions beyond what the role grants.
  - If the role is refused: a "recent unknown callers" prompt and quick search.
- **Phase 4 – WhatsApp and portal leads.**
  - The official WhatsApp Business Cloud API: webhook, approved message templates, and the 24-hour session window respected.
  - Parse portal lead emails and exports (99acres, Housing.com, Magicbricks) into leads. Leads go through the same duplicate check and are never auto-merged without broker confirmation.
  - If the API isn't set up: `wa.me` links.
- **Phase 5 – production readiness.**
  - Rate limiting, stored tokens encrypted with the Android Keystore, audit logs, backups.
  - Docker deployment and HTTPS.
  - A release-signed APK on a download page, with version checks.
