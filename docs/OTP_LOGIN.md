# Sign in with an SMS code (MSG91)

Agents can sign in with their mobile number and a 6-digit code sent by SMS through
[MSG91](https://msg91.com). Email and password sign-in keeps working.

## How it works

1. The app sends the number to `POST /api/v1/auth/otp/request`.
2. The server creates a random 6-digit code and stores only its HMAC. It asks MSG91 to deliver the code using your DLT-approved template.
   - The code is BrokerBuddy's own; MSG91's verify API isn't used.
3. The agent types the code. `POST /api/v1/auth/otp/verify` checks it and returns the same session token as a password sign-in.

Rules:

| Rule | Value |
|---|---|
| Code length | 6 digits |
| Valid for | 5 minutes |
| Wrong tries per code | 5; then the code stops working |
| Codes per number | 1 every 30 seconds, 5 per hour |
| Codes per network address | 20 per hour |
| New code | cancels the previous one |
| Unknown or disabled number | same reply as a real one, and no SMS is sent (nobody can probe which numbers have accounts) |

Which number signs in: each user's mobile number, which must be unique across BrokerBuddy.
- Owners can give it at registration.
- Owners and admins set it when adding a team member.
- Anyone signed in can add or change their own number in **Settings → Your mobile number**. The new number is saved only after a code sent to it is entered.

## Setting up MSG91 (needs you)

Nothing has been bought or configured. You need to:

1. **Create an MSG91 account** and complete its KYC.
2. **Register an OTP template on DLT.** Indian regulations require every business SMS to use a registered sender ID (header) and template. Register them through your DLT operator portal (Jio, Vodafone Idea, Airtel, BSNL…), then add them in MSG91. The template must contain MSG91's `##OTP##` variable, for example:

   > `##OTP## is your BrokerBuddy sign-in code. It expires in 5 minutes. Do not share it with anyone. -BRKBDY`

   Match the exact wording and header to what DLT approved, or operators will block the SMS.
3. In MSG91, copy your **Auth key** and the **template ID** of that OTP template.
4. Set on the server:
   - `MSG91_AUTH_KEY` and `MSG91_OTP_TEMPLATE_ID` (sign-in by SMS switches on automatically);
   - `TRUST_PROXY=1` if the server runs behind a load balancer or reverse proxy, so the rate limits see each caller's real address.
5. Restart the backend. The app's sign-in screen shows **Mobile number** when `GET /api/v1/auth/otp` reports `enabled: true`.
6. **Test with your own number first.** This integration was built from MSG91's published v5 OTP API and tested against a simulated MSG91. It hasn't sent a real SMS yet, because MSG91 can't be reached from the development environment.

For local development without SMS, set `OTP_PROVIDER=console`. Codes are then printed to the server log. The server refuses this setting when `NODE_ENV=production`.

## Costs

MSG91 charges per SMS, and DLT registration may have fees. Rate limits cap how many codes one number or network can trigger, but a busy team still sends one SMS per sign-in, so check MSG91's current pricing.
