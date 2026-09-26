/**
 * Sends one-time codes by SMS. The provider only delivers the message; the code is
 * generated and checked by BrokerBuddy (src/auth/otp.ts).
 */
export interface OtpSender {
  readonly name: string;
  /** e164: "+919820012345". Throws on failure. */
  sendOtp(e164: string, code: string, expiryMinutes: number): Promise<void>;
}

export type Fetch = typeof fetch;

/**
 * MSG91 OTP API (v5): POST https://control.msg91.com/api/v5/otp with the auth key header,
 * the DLT-approved template and our own code (`otp`). MSG91's verify endpoint is not used.
 *
 * Needs MSG91_AUTH_KEY and MSG91_OTP_TEMPLATE_ID (a template registered on DLT whose text
 * contains the ##OTP## variable). See docs/OTP_LOGIN.md.
 */
export class Msg91Sender implements OtpSender {
  readonly name = "msg91";

  constructor(
    private readonly authKey: string,
    private readonly templateId: string,
    private readonly fetchImpl: Fetch = fetch,
    private readonly baseUrl = process.env.MSG91_BASE_URL ?? "https://control.msg91.com",
  ) {}

  async sendOtp(e164: string, code: string, expiryMinutes: number) {
    const url = new URL("/api/v5/otp", this.baseUrl);
    url.searchParams.set("template_id", this.templateId);
    url.searchParams.set("mobile", e164.replace(/^\+/, "")); // country code + number, no "+"
    url.searchParams.set("otp", code);
    url.searchParams.set("otp_expiry", String(expiryMinutes));
    const res = await this.fetchImpl(url, {
      method: "POST",
      headers: { authkey: this.authKey, "content-type": "application/json", accept: "application/json" },
      body: JSON.stringify({}),
      signal: AbortSignal.timeout(10_000),
    });
    const body = (await res.json().catch(() => null)) as { type?: string; message?: string } | null;
    if (!res.ok || body?.type !== "success") {
      // Never include the code in errors or logs.
      throw new Error(`MSG91 refused the OTP request (HTTP ${res.status}${body?.message ? `: ${body.message}` : ""})`);
    }
  }
}

/** Development only: prints the code to the server log instead of sending an SMS. */
export class ConsoleSender implements OtpSender {
  readonly name = "console";
  async sendOtp(e164: string, code: string) {
    console.warn(`[OTP_PROVIDER=console] code for ${e164}: ${code} (not sent — development only)`);
  }
}

let sender: OtpSender | null | undefined;

/** The configured sender, or null when OTP login isn't set up on this server. */
export function getOtpSender(): OtpSender | null {
  if (sender !== undefined) return sender;
  const provider = process.env.OTP_PROVIDER ?? (process.env.MSG91_AUTH_KEY ? "msg91" : "");
  if (provider === "msg91" && process.env.MSG91_AUTH_KEY && process.env.MSG91_OTP_TEMPLATE_ID) {
    sender = new Msg91Sender(process.env.MSG91_AUTH_KEY, process.env.MSG91_OTP_TEMPLATE_ID);
  } else if (provider === "console" && process.env.NODE_ENV !== "production") {
    sender = new ConsoleSender();
  } else {
    sender = null;
  }
  return sender;
}

/** For tests and alternative wiring. */
export const setOtpSender = (s: OtpSender | null | undefined) => {
  sender = s;
};
