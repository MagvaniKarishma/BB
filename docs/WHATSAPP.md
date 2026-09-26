# WhatsApp integration (Phase 4)

BrokerBuddy receives WhatsApp messages through **Meta's official WhatsApp Business Platform (Cloud API)**.
When that isn't set up, agents can **share messages or exported chats into the app** instead.
Nothing uses unofficial WhatsApp Web automation or scraping.

## What it does

- **Receives messages sent to the brokerage's WhatsApp Business number** through webhooks. Each event is signature-checked (`X-Hub-Signature-256`), and Meta's retries are ignored by message id.
- **Supported message types:**
  - text, buttons and list replies;
  - image/video/document captions;
  - location and shared contacts;
  - **voice messages**: transcribed when speech-to-text is configured (`STT_URL`, see Phase 2).
  - Other types (stickers, reactions) are stored but not analysed.
- **Identifies the client by phone number** (normalized to E.164).
  - A **portal lead notification forwarded by a team member** (their number is on their user profile) is recognised as a forward: the lead in the text becomes the person, never the agent.
- **Recognises 99acres, Housing.com and Magicbricks leads:** portal, lead name, phone and email, listing ID/URL, the lead's own quoted message, and the **advertised price**.
- **Keeps advertised prices out of budgets:**
  - The listing price is stored as `advertisedPrices` / `portalLead.listingPrice`, and its text is masked before budget extraction.
  - In ordinary messages, amounts after "price / asking / listed at / कीमत / किंमत…" are treated the same way.
  - Only the client's own words ("within 50 lakh", "70 hazaar tak") become a budget.
- **Extracts a requirement draft** with the Phase 2 extractor (Claude when configured, otherwise the Hindi/Hinglish/Marathi/English rules).
  - Property type and area from a portal listing are proposed with a warning to confirm.
  - Drafts are **never applied automatically**. The agent reviews them and saves to a new or existing inquiry, which records a `WHATSAPP` revision linked to the message (`InquiryRevision.whatsappMessageId`).
- **Stores conversation history** (inbound, outbound and imported) per client, and links each message to an inquiry when that is unambiguous. The agent can re-link it.
- **Prevents duplicate client profiles:**
  - Unknown senders appear in the **WhatsApp leads** inbox instead of being auto-created.
  - *Create client* goes through the same duplicate check as every other path (`services/clients.ts`). If the number or email already belongs to a client, the message is **linked to that client** instead.
  - *Link to existing* refuses numbers owned by a different client.
- **Sends replies** from the conversation screen:
  - free text within Meta's 24-hour customer-service window;
  - approved templates outside it (`POST /whatsapp/send-template`);
  - otherwise the app opens the WhatsApp app with the text ready (click-to-chat).
  - Delivery receipts update the message status. The status only moves forward: sent → delivered → read, or failed.

## Manual alternatives (no API needed)

| How | Steps | Result |
|---|---|---|
| Share one message | In WhatsApp: long-press message → Share → BrokerBuddy (or copy and paste into *WhatsApp leads → Paste / import*) | Portal lead recognition, requirement draft, create/link client. Re-sharing the same text doesn't duplicate it |
| Import a whole chat | In WhatsApp: chat → ⋮ → More → Export chat → **Without media** → BrokerBuddy, then choose the client and which participant is them | Full history (Android and iPhone export formats), idempotent re-import. Any line can be analysed with *Look for requirements* |
| Send a message | Conversation → *Open in WhatsApp* | Opens `wa.me/<number>?text=…` in WhatsApp / WhatsApp Business |

## Credentials and setup

### What you need

| Item | Where it comes from | Where it goes |
|---|---|---|
| Meta Business portfolio (verified recommended) | business.facebook.com | — |
| Meta app of type *Business* with the **WhatsApp** product | developers.facebook.com → My Apps → Create app | — |
| **Phone number ID** | App → WhatsApp → API Setup | App: Settings → WhatsApp Business API |
| **WhatsApp Business Account ID** (optional) | App → WhatsApp → API Setup | same |
| **Permanent access token** (system user) | Business settings → Users → System users → Add (Admin) → Assign assets (the app + the WhatsApp account, full control) → *Generate token* with `whatsapp_business_messaging` and `whatsapp_business_management` | same (stored encrypted) |
| **App secret** | App → Settings → Basic → App secret | same (stored encrypted; used to verify webhook signatures) |
| **DATA_ENCRYPTION_KEY** | `openssl rand -hex 32` | server `.env` (required before connecting) |
| Public **HTTPS** URL for the API | Your deployment (valid TLS certificate; Meta rejects self-signed) | — |
| `WHATSAPP_GRAPH_VERSION` | A currently supported Graph API version from Meta's changelog (default `v23.0`; update as Meta deprecates versions) | server `.env` |

The temporary 24-hour token on the API Setup page works for trying things out, but expires. Use a system-user token in production.

### Steps

1. **Phone number:** in *WhatsApp → API Setup → Add phone number*, register a number and verify it by SMS or call.
   - A number already active in the WhatsApp or WhatsApp Business *app* must be moved or deleted there first. Meta's "coexistence" option (Business app and API on one number) exists in some markets; check availability for India.
   - The display name must be approved by Meta.
2. **Server:** set `DATA_ENCRYPTION_KEY` (and optionally `WHATSAPP_GRAPH_VERSION`), then restart.
3. **Connect in BrokerBuddy:** as owner/admin, open *Settings → WhatsApp Business API → Connect* and enter the phone number ID, access token and app secret.
   - Or use `PUT /api/v1/whatsapp/account`.
   - BrokerBuddy then shows a **Callback URL** (`https://<server>/webhooks/whatsapp/<random key>`) and a **Verify token**.
4. **Webhook in Meta:** *WhatsApp → Configuration → Webhook → Edit*. Paste the callback URL and verify token, then **Verify and save**; BrokerBuddy answers the handshake. Then **subscribe to the `messages` field**.
5. **Subscribe the app to the WhatsApp Business Account** if messages don't arrive (the dashboard usually does this):
   `curl -X POST "https://graph.facebook.com/<version>/<WABA_ID>/subscribed_apps" -H "Authorization: Bearer <token>"`
6. **Templates** (for messaging clients who haven't written in the last 24 h): create and get them approved in *WhatsApp Manager → Message templates*. Send them with `POST /whatsapp/send-template`.
7. **Billing:** add a payment method in WhatsApp Manager. Meta charges for template messages under its current pricing model; check Meta's pricing page for India.
8. **Team phones:** add each agent's phone number to their BrokerBuddy profile. Portal leads they forward to the business number are then treated as forwards.

## Limitations

- **Only the connected business number is covered.** Chats on agents' personal WhatsApp can't be read by any official API; use share/export for those. **Group chats** aren't supported by the Cloud API.
- **Portal formats:** the recogniser was built from typical notification and click-to-chat texts, not verified against current live 99acres, Housing.com and Magicbricks messages; formats also change. Unrecognised fields are left empty, never guessed. Collect real samples and add them to `test/whatsappLogic.test.ts`. Portal **emails** and portal CRM exports are not ingested yet.
- **Media:** images, video and documents are stored as references (caption only). Voice messages are transcribed only when speech-to-text is configured.
- **Processing** runs inside the API process, right after the webhook is acknowledged, with a 60-second sweep for anything missed. At high volume, move it to a job queue.
- **Not tested against Meta's live servers:** this environment cannot reach `graph.facebook.com`. Tests use payloads in Meta's documented format and a local stand-in for the Graph API.

## API summary (`/api/v1/whatsapp`)

- **Account** (owner/admin): `GET|PUT|DELETE /account`
- **Inbox and history:**
  - `GET /inbox?filter=attention|needs_client|needs_review|all`
  - `GET /clients/:clientId/messages`
  - `GET /messages/:id`
- **Linking:**
  - `POST /messages/:id/create-client` (duplicate-safe)
  - `POST /messages/:id/link-client {clientId, addNumber}`
  - `PATCH /messages/:id {inquiryId}`
- **Requirement drafts:**
  - `POST /messages/:id/extract`
  - `POST /messages/:id/apply {inquiryId?, requirement}`
  - `POST /messages/:id/dismiss`
- **Manual import:**
  - `POST /import {text, clientId?}`
  - `POST /import-chat {clientId, clientSenderName, exportText}`
- **Sending:**
  - `POST /send {clientId, text}` (within 24 h)
  - `POST /send-template {clientId, template, language, params}`
- **Public webhook:** `GET|POST /webhooks/whatsapp/:webhookKey`
