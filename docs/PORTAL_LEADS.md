# 99acres and Housing.com leads

BrokerBuddy keeps enquiries from **99acres** and **Housing.com** per listing:
- **Home → Today's Work** opens **99acres Leads** or **Housing.com Leads**.
- Pick a listing to see who enquired, then call, WhatsApp, add a note, schedule a follow-up or update the lead's status.

The two portals are kept apart. A flat advertised on both is two listings, and each lead belongs to one portal.

## What counts as a lead

Only an **identifiable enquiry** that reached BrokerBuddy counts: a person, a phone number or email, and a time.
- Listing views, clicks and impressions are **not** leads. They never appear, and nothing is estimated from them.
- Fields the source didn't give stay empty. BrokerBuddy doesn't guess a listing's title, price or photo, or a client's name.

## How leads get in

Neither portal offers a public lead API. BrokerBuddy does not sign in to your portal account or read it. The supported sources are:

| Source | How | Status |
|---|---|---|
| **WhatsApp** | Share a portal lead message or "Chat on WhatsApp" enquiry to BrokerBuddy, or receive it through a connected WhatsApp Business number. | Works (tested with real Housing.com and 99acres messages) |
| **Lead export (CSV)** | Download your leads from the portal (Excel → *Save as CSV*), then use *Lead sources → Import CSV* on that portal's screen. | Works with the columns below. **Not yet tried with a real export file**, so send one if a column isn't recognised. |
| **Lead email** | *Lead sources → Paste lead email*: paste the whole email the portal sent. | Works when the email has the usual "Name / Mobile / Property" details |
| **Inbound link** | An owner creates a link (*Lead sources → Create inbound link*). A portal CRM integration, email-parsing tool or Zapier-style tool posts leads to it. | Built and tested with test requests; **no portal is connected to it yet** |

### Connection status on each portal screen

- **Import Required**: no automatic source is set up. Leads come from WhatsApp, CSV or email.
- **Not Connected**: an inbound link exists, but no lead has arrived through it yet.
- **Connected**: at least one lead actually arrived through the inbound link. The screen never shows Connected before that.

### CSV columns recognised

Header names aren't case-sensitive. The first matching name wins.

- **Required:**
  - an enquiry date: *Enquiry Date, Lead Date, Date, Received On, Created At…*;
  - a phone number or email: *Mobile, Phone, Contact Number, Email…*
- **Optional:**
  - *Lead ID, Name, Message / Query / Remarks*
  - *Property ID / Listing ID, Property URL, Property Title / Property / Project, Locality / Location*
  - *Price / Rent, Budget, Carpet Area, Photo URL*

Dates like `26/09/2026 10:15 AM`, `26-09-2026` and `26 Sep 2026, 6:05 pm` are read as India time, day first.

Handling of particular rows:
- A row without a readable date, or without both phone and email, is skipped and listed with its reason.
- Importing the same file again adds nothing. Rows with a Lead ID are matched on it; other rows on the person, listing, message and time.

### Inbound link format

`POST <server>/api/v1/portal-inbound/<key>` with JSON: either one lead, or `{ "leads": [ … ] }` with up to 500.

```json
{
  "leadId": "H-100",
  "enquiredAt": "2026-09-26T10:00:00+05:30",
  "name": "Kiran",
  "phone": "9820077777",
  "email": null,
  "message": "Is it available?",
  "budgetMax": 55000,
  "listing": { "id": "HX1", "url": "https://housing.com/…", "title": "2 BHK for rent", "locality": "Chembur", "price": 50000, "photoUrl": "https://…" }
}
```

- `enquiredAt` and a `phone` or `email` are required.
- The portal comes from the key; each portal has its own link.
- Creating a new link turns off the old one.
- The key is shown once. Only its hash is stored.

## After upgrading an existing server

Portal enquiries shared over WhatsApp **before** this version aren't on the portal screens until you run, once, in `backend/`:

```bash
npm run backfill:portal-leads            # dry run: shows how many would be added
npm run backfill:portal-leads -- --apply # adds them
```

It uses what was already read from each message and links a lead only to a client already linked or with the same phone number. It never creates clients, and running it again adds nothing.

## Clients and duplicates

- A lead is linked to a client by **phone number**, or by **email** when exactly one client has it. **Never by name**: two "Rahul Sharma"s with different numbers stay two clients.
- A new client is created only when the lead has a phone number no client has. Its source is set to the portal.
- A lead with only an email stays unlinked until you link the client. Notes and follow-ups need a linked client.
- Each enquiry keeps its own record with its original time and message, even from the same person about the same listing. The same enquiry delivered twice is stored once.
- The same enquiry can arrive by two routes, e.g. the WhatsApp notification and later the CSV export. It is recognised when it's the same portal and the same phone or email, the listing matches (or one source didn't name it), and the times are within 10 minutes. The second copy only fills in missing details.
- Two different portal lead IDs are never merged. A shared WhatsApp message's time is when it was sent or shared, which can differ from the portal's time by more than 10 minutes; those copies are then kept as two leads.

## Requirement from the enquired listing

Someone who enquired about "1 BHK Apartment for Rent, Mulund West" is looking for that. When the lead is linked to a client, BrokerBuddy adds that requirement:
- **Filled in:** rent/buy, property type and area, as the listing states them.
- **Price:**
  - The listing's price goes into the notes as *the listing's price, not a budget the client stated*.
  - The budget field is filled only from the client's own words (a CSV Budget column, or their message).
- **Source:** the requirement's source is *Portal lead*, and its history starts there.
- **Skipped when:**
  - the listing doesn't say rent/sale and the property type, since nothing is guessed. The enquiry still shows on the client's Overview.
  - the client already has an active requirement of the same kind. Existing requirements are never changed.
- **After upgrading:** the backfill command below also adds these requirements for enquiries already saved.

## Listings and your own properties

- A portal listing is recognised by its portal listing ID, else its link, else (only when neither is known) by rent/sale, type, area and price together.
- It is linked to one of your own properties only when exactly one fits:
  - the portal listing ID is written in your property's title or notes, or
  - the rent/sale, type, area and price are the same.
- If two properties fit, it stays unlinked.
- The photo shown is the portal's image link when the source gave one, else your own property's first photo, else the stock artwork.

## Lead status

**New → Contacted → Follow-up → Converted / Not interested / Closed.**
- Changing a lead from New also moves a client still marked *New* to *Contacted*. Nothing else on the client changes.
- Scheduling a follow-up from a New lead marks it *Follow-up*.

## Voice and typed commands

On Home, tap **Today's Work → Ask** and speak (English, हिंदी or मराठी via the phone's speech recognition) or type:

- "Show today's 99acres leads" · "Show Housing.com leads from yesterday" · "99acres ki pichle 7 din ki leads dikhao" · "आजचे 99acres लीड दाखवा"
- "Show everyone interested in the Andheri property" · "Andheri wali property mein kaun interested hai"
- "Mark Rahul as contacted" · "Priya ko not interested mark karo"
- "Set a follow-up with Rahul tomorrow at 5 pm" · "Amit ke saath kal follow up"

Limits:
- A client is chosen only when exactly one saved name is in the command. Otherwise the choices are shown and nothing changes.
- Names are matched as saved: a name saved in English letters isn't found from Devanagari speech, and the other way round.
- Speech recognition is Google's on the phone. Nothing is recorded by BrokerBuddy.
