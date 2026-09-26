# Caller screen (Phase 3): Android constraints, design and test plan

## What Android allows (and what we don't do)

| Approach | Status on modern Android | Used? |
|---|---|---|
| **`CallScreeningService` + `ROLE_CALL_SCREENING`** (Android 10+) | Official API for third-party caller ID. The user grants the role in a system dialog. The system then binds the service for incoming calls and passes the number. | **Yes: primary detection** |
| `PHONE_STATE` broadcast + `READ_PHONE_STATE` | The incoming number is only included with `READ_CALL_LOG` (Android 9+). Google Play restricts `READ_CALL_LOG` to default dialer/SMS-type apps. | No |
| Reading the call log | Same Play restriction, and it only works after the call anyway. | No |
| Becoming the default dialer (`InCallService`) | Would mean replacing the phone app. Too invasive for a CRM. | No |
| Full-screen intent over the ringing screen | Android 14 limits `USE_FULL_SCREEN_INTENT` to calling/alarm apps. | No |
| Starting an activity from the background | Blocked by background-activity-launch rules (Android 10+). | No |
| **Heads-up notification** | Always allowed with notification permission (Android 13+ runtime). | **Yes: always shown** |
| **Overlay window (`SYSTEM_ALERT_WINDOW`)** | Allowed after the user enables "Display over other apps". Unavailable on Android Go devices, and some OEM builds restrict it. | **Yes: optional floating card** |

BrokerBuddy **never blocks, silences, rejects or records calls**. `BrokerCallScreeningService` answers
"allow" immediately, before any lookup, so a slow network can never delay the ringing.

## How it works

1. **Incoming call:**
   - Telecom binds `BrokerCallScreeningService`.
   - The service responds "allow", then hands the number to `CallerIdController`.
   - Withheld/private numbers (no handle) are ignored.
2. **Instant name:** `CallerDirectoryStore` holds a phone → name directory (`GET /caller/directory`, refreshed every 6 h, at login and after a client is created). If the number is there, a card with the name is shown straight away. This also works offline.
3. **Full card:** `GET /caller/lookup` (8 s timeout) returns:
   - the client, status, lead source and agent;
   - **every open inquiry**, with budget, locations, must-haves and a live count of matching properties;
   - the **last conversation** (latest note or voice-note transcript);
   - pending **follow-ups**, overdue first.
4. **Surfaces:**
   - **Heads-up notification** ("caller_id" channel, silent, high importance):
     - known caller: *Profile*, *Matches* (or *Requirements* when there are several inquiries), and an inline **Add note** reply that saves without opening the app;
     - unknown caller: *Create client*.
   - **Floating card** (only if "Display over other apps" is granted): the same content. It can be dragged, has a close button, auto-hides after 60 s, and never takes focus from the dialer.
   - **Caller screen** in the app (tap the notification or card): the full detail, with Call back, WhatsApp, Add note and Voice note.
5. **Unknown numbers:** *Create client* opens the new-client form with the number and "Phone call" lead source pre-filled. The usual duplicate check still applies.

## Setup the agent does (Settings → Caller screen)

Each item shows ✓/✗ and an **Allow** button that opens the system's own dialog or settings page:

- **Call screening access** (required)
- **Notifications** (required)
- **Floating caller card** (optional)
- **Calls from saved contacts** (optional; `READ_CONTACTS`, see limitations)

Toggles: turn the caller screen on/off, offer *Create client* for unknown numbers, and use the floating card.

**Simulate incoming call** runs the exact same lookup-and-display pipeline without a real call.

## Known limitations (honest list)

- **Android 8–9 are not supported.** There is no Play-compliant way to get the incoming number on them; the app says so in Settings.
- **Only one app can hold the call-screening role.** If the agent uses Truecaller or another screening app for spam protection, granting the role to BrokerBuddy takes it away from that app (the system dialog makes this choice). This is the biggest practical trade-off.
- **Calls from saved phone contacts:** AOSP documents that a call-screening app may not be consulted for numbers already in the user's contacts. Granting Contacts permission is expected to help on some versions, but this **must be verified per device/OS version**. Brokers often save clients as contacts, so this is the first thing to test.
- **Dual-SIM / work profiles / VoIP (WhatsApp) calls:** VoIP calls don't go through Telecom's screening, so WhatsApp calls are **not** identified. Dual-SIM should work (Telecom screens both SIMs) but is unverified.
- **Process lifetime:** the lookup runs after the screening service is unbound. If the OS kills the app process within those ~1–2 s (aggressive OEM battery managers: some Xiaomi/Oppo/Vivo/Realme builds), the card may not appear. Mitigations: the cached name shows first, and the notification persists once posted. Agents on such phones may need to exempt BrokerBuddy from battery optimisation (OEM setting; not requested automatically).
- **Overlay:** unavailable on Android Go, and hidden by some OEM call screens. The notification is always the fallback.
- **Lock screen:** the notification shows according to the user's lock-screen notification settings. Actions that open the app require unlocking.
- **Privacy:** the offline directory (names and numbers of the brokerage's clients) is stored in app-private storage and deleted on sign-out. Requirements are never cached.

## Device test plan

Not yet run: this development environment has no Android SDK, emulator or device. CI builds the APK.

| # | Scenario | Expected |
|---|---|---|
| 1 | Settings → Caller screen on Android 10, 12, 13, 14, 15 | Items show correct ✓/✗; each *Allow* opens the right system screen |
| 2 | Emulator: `adb emu gsm call 9820012345` with a matching client | Heads-up within ~1 s showing name + requirements; floating card if allowed |
| 3 | Same, number saved in phone contacts, with and without Contacts permission | Record whether the card appears (see limitation) |
| 4 | Unknown number | "Not in BrokerBuddy" + *Create client* → pre-filled form |
| 5 | Client with 3 inquiries (rent, buy, paused) | All shown; "Requirements" action opens profile |
| 6 | Airplane mode (Wi-Fi off, cellular call) | Cached name shown with "Offline" line |
| 7 | Inline *Add note* from notification | "Note saved"; note appears on caller screen and profile |
| 8 | Withheld number | Nothing shown; call unaffected |
| 9 | Role held by another app (e.g. Truecaller) | System dialog explains the switch; BrokerBuddy works only after the user chooses it |
| 10 | Xiaomi/Realme with default battery settings, app swiped away | Record reliability; document the OEM steps |
| 11 | Simulate incoming call button | Same result as a real call |
| 12 | Call rings while the phone is locked | Notification visible per lock-screen settings |
