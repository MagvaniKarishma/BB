# Testing BrokerBuddy on an Android phone

This guide covers installing the debug APK on a real phone and testing it against a
BrokerBuddy server on your own computer, using test data only. Nothing here touches
production.

**What has and hasn't been verified before this guide:**

- **Checked:**
  - The app compiles, passes Android lint and its unit tests in CI.
  - Every request the app sends and every response it reads was exercised against the real backend (the contract test: 34 request bodies, 68 responses).
  - The backend was built, started and signed into with the demo account.
- **Not checked:** the app has **not been run on a phone or emulator yet**. Your test is the first time the screens run on a device, so expect some rough edges and please note anything odd.

## Quickest look: the demo (no server)

On the sign-in screen, tap **Try the demo (no server needed)**. The app opens with a sample brokerage: 11 clients, 12 listings, follow-ups, WhatsApp leads and voice notes. These are real answers from the BrokerBuddy server, saved into the app and served on the phone.
- **What works:** every screen, lists, tabs, search, client and property details, matches and requirement history. Dates move forward so today's follow-ups stay "today".
- **What doesn't:** nothing you add or change is saved; the app says so. Photos, recordings, SMS, WhatsApp sending, AI calls and caller ID are off. Follow-up notifications don't fire.
- **Leaving:** tap **Exit demo** on the yellow bar to return to sign-in.

To test saving data, use your own server (below).

To refresh the sample data after server changes, run `backend/scripts/demo-snapshot.ts` against an empty scratch database. The script's header has the command.

## 1. Start the server on your computer

You need:
- Node.js 22 and PostgreSQL 16 (both free);
- the phone and the computer on the **same Wi-Fi**.

```bash
git clone https://github.com/MagvaniKarishma/BB.git
cd BB && git checkout claude/brokerbuddy-android-crm-57xhqa
cd backend
cp .env.example .env
```

Edit `.env`:
- `DATABASE_URL` → your local Postgres, e.g. `postgresql://postgres:yourpassword@localhost:5432/brokerbuddy` (create an empty database called `brokerbuddy` first).
- `JWT_SECRET` → any random text of 32 or more characters.
- `DATA_ENCRYPTION_KEY` → 64 hex characters. Generate one with `node -e "console.log(require('crypto').randomBytes(32).toString('hex'))"`.
- Leave the rest empty for a first test.

Then:

```bash
npm install
npx prisma migrate deploy      # creates the tables
npm run db:seed                # demo data — login demo@brokerbuddy.local / demo12345
npm run dev                    # server on port 4000
```

Check it works: open `http://localhost:4000/health` in your computer's browser. It should show `{"ok":true}`.

Find your computer's Wi-Fi address:
- **Windows:** run `ipconfig` and look for "IPv4 Address", e.g. `192.168.1.23`.
- **macOS:** run `ipconfig getifaddr en0`.
- **Linux:** run `hostname -I`.

If the phone can't connect later, allow Node.js / port **4000** through the computer's firewall (Windows asks the first time; choose *Private networks*).

## 2. Download the APK

1. Open the latest green run of the **CI** workflow on the branch `claude/brokerbuddy-android-crm-57xhqa`: <https://github.com/MagvaniKarishma/BB/actions/workflows/ci.yml?query=branch%3Aclaude%2Fbrokerbuddy-android-crm-57xhqa>
2. Scroll to **Artifacts** and download **brokerbuddy-debug-apk**. You must be signed in to GitHub.
3. It downloads as a `.zip`. Unzip it to get `app-debug.apk` (about 19 MB). Artifacts are kept for 90 days.

## 3. Install it on the phone

The app needs Android 8.0 or newer. The caller screen needs Android 10 or newer.

1. Copy `app-debug.apk` to the phone by USB, Google Drive, email to yourself, or WhatsApp "Message yourself".
2. Open it on the phone. Android will ask to **allow installing unknown apps** for the app you opened it from (Files, Drive, Chrome…). Allow it, then tap **Install**.
3. If Play Protect warns that the app is unknown, choose **Install anyway**. This is expected for a debug build that isn't from the Play Store.

## 4. Connect the app to your server

1. Open BrokerBuddy. After the navy splash screen you'll see the sign-in screen.
2. Tap the small **Server: 10.0.2.2:4000** link under the buttons. That address only works in the Android emulator.
3. Change it to `http://<your computer's IP>:4000`, e.g. `http://192.168.1.23:4000`.
4. Choose the **Email** tab if tabs are shown and sign in with `demo@brokerbuddy.local` / `demo12345`. You can also create your own account with **New broker? Create a broker account**.

The Mobile number tab only appears once MSG91 is configured (see `docs/OTP_LOGIN.md`).

**"Can't reach the server"** means the phone got no answer at that address. Check:
- the server is running on the computer (`npm run dev` shows *BrokerBuddy API listening on :4000*);
- the address is the computer's Wi-Fi address, not `10.0.2.2`, and starts with `http://`, e.g. `http://192.168.1.23:4000`;
- the phone is on the same Wi-Fi as the computer, not mobile data;
- in the phone's browser, `http://192.168.1.23:4000/health` shows `{"ok":true}`. If it doesn't, the computer's firewall is blocking port 4000, or the Wi-Fi network (e.g. guest or office Wi-Fi) blocks devices from talking to each other.

## 5. Test checklist

Tick each item; note the phone model and Android version with any problem.

### Basics
- [ ] Splash appears, then sign-in. No white flash on start.
- [ ] Sign in with email works; wrong password shows an error.
- [ ] Rotate the phone on a few screens: nothing resets or crashes.
- [ ] Kill the app and reopen: you stay signed in.
- [ ] ☰ menu → Sign out returns to sign-in.

### Home (dashboard)
- [ ] Greeting with your first name and today's date.
- [ ] Clients / Enquiries / Properties / Follow-ups tiles show numbers matching the lists. Each tile opens its list.
- [ ] Rent/Buy switch changes the category counts; tapping a category opens its requirements.
- [ ] Today's follow-ups, new leads and top matches show seeded data. Mark done / Snooze work.

### Clients
- [ ] Clients list: tabs All / New / Active / Follow Up / Lost with counts; search by name and by phone.
- [ ] Call and WhatsApp buttons open the dialer / WhatsApp with the right number.
- [ ] **+** → add a client. Adding one with the **same phone number** again is refused and points to the existing client.
- [ ] Client profile: Overview / Requirements / Activity tabs; Add Note; Add Follow Up; Share Properties opens matches.

### Requirements and matching
- [ ] + Add Requirement: Rent/Buy starts empty; saving without it shows an error; budget accepts "65k", "75 L", "1.2 Cr".
- [ ] Edit a requirement; its History shows what changed.
- [ ] Requirements screen (☰ → Requirements): Rent/Buy, search, "N Matches" opens matches.
- [ ] Matches: % badge; All / Best / To verify tabs; "Send to client" opens WhatsApp with the listing text.

### Properties
- [ ] Add a property (incl. bathrooms); it appears in the Properties list.
- [ ] Detail → **Photos → Add**: pick 2–3 photos from the gallery, including a portrait camera photo. They upload, show the right way up, and swipe in the gallery.
- [ ] Long-press a photo → remove.
- [ ] Share sends the listing text.
- [ ] Mark as Rented/Sold → confirm → it disappears from "Available" and from matches. Marking it Available again brings it back.

### Follow ups
- [ ] Today / Upcoming / Overdue tabs and counts are right.
- [ ] Create a follow-up due in 2–3 minutes; allow notifications when asked. A notification arrives at the due time.
- [ ] Snooze and Mark done from the ⋮ menu.

### Voice notes (microphone button in the middle of the bottom bar)
- [ ] Pick a client → the microphone permission prompt appears → record in Hindi / Hinglish.
- [ ] Rings move with your voice; timer counts; stop works.
- [ ] Without `STT_URL` on the server the note is saved as **Needs transcript**; type what you said, then it fills the requirement form.
- [ ] Review screen: ▶ plays the recording back; **Save to Client** creates or updates the requirement.
- [ ] "Type the note instead" works (this was broken until the latest fix).

### WhatsApp (manual — no Meta account needed)
- [ ] In WhatsApp, long-press a message (e.g. a Housing.com enquiry) → Share → BrokerBuddy. It opens the import screen and recognises the portal, area and BHK.
- [ ] Create a client from it; importing the same enquiry again links to that client (no duplicate).

### Caller screen (Android 10+)
- [ ] Settings → Caller screen → allow BrokerBuddy as caller ID & spam app.
- [ ] Have someone call from a number saved as a client: a notification (and the navy card, if "Display over other apps" is allowed) shows their name and requirement.
- [ ] Unknown number → "Create client" works. The phone's own Answer/Decline buttons still work.

### AI Call Assistant (settings only — no real calls without a phone provider)
- [ ] Settings → AI Call Assistant: record a greeting, ▶ preview, save, re-record, delete.
- [ ] Test your assistant: type "Mujhe Powai mein 2 BHK rent pe chahiye" and get follow-up questions.

### Team and account
- [ ] Settings: add an agent without a phone number (this was broken until the latest fix). Deactivate and reactivate them.
- [ ] Settings → Your mobile number shows "SMS sign-in isn't set up" until MSG91 is configured.

## 6. What is partial, placeholder or needs outside services

| Area | Status |
|---|---|
| Sign in with SMS code | Built; needs your MSG91 account + DLT template (`docs/OTP_LOGIN.md`). Never sent a real SMS yet. |
| Voice note transcription | Needs a speech-to-text service (`STT_URL`, e.g. OpenAI Whisper). Without it, recordings are kept and you type the transcript. |
| AI requirement extraction | Uses Claude if `ANTHROPIC_API_KEY` is set; otherwise built-in Hindi/Hinglish/Marathi/English rules (work offline, less flexible). |
| WhatsApp Business inbox | Needs a Meta WhatsApp Business account and a public HTTPS server (`docs/WHATSAPP.md`). Manual share/import works without it. Sending from the app (API) not tested live. |
| AI Call Assistant real calls | Needs a telephony provider and number (`docs/AI_CALL_ASSISTANT.md`). Settings, greetings and typed tests work. |
| Portal listing link check (rent/sale) | Server needs internet access; 99acres sometimes blocks automated requests (then the agent confirms). |
| Photos of people | None — avatars show initials. |
| Property without photos | Shows stock skyline artwork. |
| Home search icon | Opens the Clients list (search there); there's no search across everything. |
| Home badge | Shows today's date (the design's weather badge isn't built). |
| Test your assistant | Uses a fixed dummy caller number (+91 98000 00000). |
| Property "Share" | Shares text only, not photos. |
| Follow-up notifications | Scheduled on the phone; new follow-ups created elsewhere reach the phone on the next sync (up to ~15 minutes). |
| Release (Play Store) build | Needs your signing key; release builds only allow HTTPS servers. |
