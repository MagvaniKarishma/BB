# A free test server (Render)

This puts a BrokerBuddy server online on **Render's free plan**, so the app on your phone works anywhere, on Wi-Fi or mobile data, with no computer. It is for **testing with test data**, not for real clients.

## Before you start

You need:
- a GitHub account with access to `MagvaniKarishma/BB`;
- about 10 minutes.

The free plan shouldn't ask for a card. **If Render asks for payment details or shows a price, stop.** It means a paid option was chosen or the free-plan terms changed.

## Steps

1. Open **https://dashboard.render.com** and choose **Sign up with GitHub** (or sign in).
2. Click **New +** → **Blueprint**.
3. Under **Connect a repository**, choose **MagvaniKarishma/BB**. If it isn't listed, click **Configure account** / **GitHub** and give Render access to that repository.
4. Set **Branch** to `claude/brokerbuddy-android-crm-57xhqa`. That branch has the `render.yaml` file that describes the server.
5. Give the Blueprint any name, e.g. `brokerbuddy-test`. Render shows two items, both on the **Free** plan:
   - the web service `brokerbuddy-test`;
   - the database `brokerbuddy-test-db`.
6. Click **Apply** / **Deploy Blueprint**. The first build takes about 5–10 minutes. Wait until the web service shows **Live**.
7. Open the web service. Its address is shown at the top, e.g. `https://brokerbuddy-test.onrender.com`. Check it by opening `<address>/health` in your phone's browser. It should show `{"ok":true}`.

Render creates the secret keys (`JWT_SECRET`, `DATA_ENCRYPTION_KEY`) itself. Nothing needs to be typed in or shared.

## Connect the app

1. Open BrokerBuddy. On the sign-in screen, tap **Server: …** under the buttons.
2. Enter the address from step 7, starting with `https://`, e.g. `https://brokerbuddy-test.onrender.com`.
3. Tap **New broker? Create a broker account** and create your account. The server starts empty.

## What works on the free server

- Everything that runs on the server alone:
  - clients, requirements, properties, matches;
  - follow-ups and callbacks;
  - Today's Work;
  - 99acres / Housing.com leads (WhatsApp share, CSV import, lead email);
  - voice commands (Ask);
  - AI Call Assistant settings and typed tests;
  - team members.
- **Voice notes:** recording works. With no speech-to-text service set up, each note is saved as *Needs transcript*. Type what you said and it fills in the requirement as usual.
- **Not available until set up:**
  - SMS sign-in (needs MSG91);
  - automatic speech-to-text;
  - WhatsApp Business inbox;
  - real AI phone calls.

## Free-plan limits to expect

- **Sleeping:**
  - After about 15 minutes with no use, the server sleeps. The next request wakes it in about a minute.
  - If the app says it can't reach the server, wait a minute and try again.
- **Uploaded files don't last:**
  - Property photos, voice recordings and greeting audio are stored on the server's disk, and the free plan wipes that disk whenever the server restarts or sleeps.
  - Everything else is kept in the database.
  - Keeping files needs S3-compatible storage (settings in `backend/.env.example`), which you'd set up and approve separately.
- **The free database expires:** Render's free database is deleted after a limited period (30 days when this was written, per Render's free-plan terms). Check Render's current terms.
- **Updates:** Render redeploys automatically when new code is pushed to the branch. The database keeps its data, and the migrations run on start.

## Removing it

In the Render dashboard, open the Blueprint → **Settings** → delete it. That removes the web service and the database.
