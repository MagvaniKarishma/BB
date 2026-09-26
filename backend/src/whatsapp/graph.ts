/**
 * Minimal WhatsApp Cloud API (Meta Graph API) client: send text/template messages and
 * download media. Base URL and version are configurable (WHATSAPP_GRAPH_URL,
 * WHATSAPP_GRAPH_VERSION) — set the version to a currently supported one from Meta's docs.
 */
export class GraphApiError extends Error {
  constructor(
    public status: number,
    message: string,
    public code?: number,
  ) {
    super(message);
  }
}

export class GraphClient {
  private readonly base: string;

  constructor(
    private readonly accessToken: string,
    private readonly phoneNumberId: string,
    baseUrl = process.env.WHATSAPP_GRAPH_URL ?? "https://graph.facebook.com",
    version = process.env.WHATSAPP_GRAPH_VERSION ?? "v23.0",
  ) {
    this.base = `${baseUrl.replace(/\/$/, "")}/${version}`;
  }

  private async request(path: string, init: RequestInit = {}): Promise<Record<string, unknown>> {
    const res = await fetch(`${this.base}/${path}`, {
      ...init,
      headers: { Authorization: `Bearer ${this.accessToken}`, "Content-Type": "application/json", ...(init.headers ?? {}) },
      signal: AbortSignal.timeout(20_000),
    });
    const body = (await res.json().catch(() => ({}))) as Record<string, unknown>;
    if (!res.ok) {
      const err = (body.error ?? {}) as { message?: string; code?: number };
      throw new GraphApiError(res.status, err.message ?? `WhatsApp API returned ${res.status}`, err.code);
    }
    return body;
  }

  private static wamid(body: Record<string, unknown>): string {
    const id = (body.messages as { id?: string }[] | undefined)?.[0]?.id;
    if (!id) throw new GraphApiError(502, "WhatsApp API did not return a message id");
    return id;
  }

  /** Free-form text — only allowed within 24 h of the customer's last message. */
  async sendText(toE164: string, body: string): Promise<string> {
    const res = await this.request(`${this.phoneNumberId}/messages`, {
      method: "POST",
      body: JSON.stringify({
        messaging_product: "whatsapp",
        recipient_type: "individual",
        to: toE164.replace(/\D/g, ""),
        type: "text",
        text: { preview_url: false, body },
      }),
    });
    return GraphClient.wamid(res);
  }

  /** Pre-approved template — required to start a conversation outside the 24 h window. */
  async sendTemplate(toE164: string, name: string, languageCode: string, bodyParams: string[]): Promise<string> {
    const res = await this.request(`${this.phoneNumberId}/messages`, {
      method: "POST",
      body: JSON.stringify({
        messaging_product: "whatsapp",
        to: toE164.replace(/\D/g, ""),
        type: "template",
        template: {
          name,
          language: { code: languageCode },
          components: bodyParams.length
            ? [{ type: "body", parameters: bodyParams.map((text) => ({ type: "text", text })) }]
            : [],
        },
      }),
    });
    return GraphClient.wamid(res);
  }

  /** Two steps: resolve the media URL, then download it with the same token. */
  async downloadMedia(mediaId: string, maxBytes = 16 * 1024 * 1024): Promise<{ bytes: Buffer; mimeType: string }> {
    const meta = await this.request(mediaId);
    const url = typeof meta.url === "string" ? meta.url : null;
    if (!url) throw new GraphApiError(502, "Media URL missing");
    const res = await fetch(url, { headers: { Authorization: `Bearer ${this.accessToken}` }, signal: AbortSignal.timeout(30_000) });
    if (!res.ok) throw new GraphApiError(res.status, `Media download failed (${res.status})`);
    const bytes = Buffer.from(await res.arrayBuffer());
    if (bytes.length > maxBytes) throw new GraphApiError(413, "Media too large");
    return { bytes, mimeType: (typeof meta.mime_type === "string" ? meta.mime_type : res.headers.get("content-type")) ?? "application/octet-stream" };
  }
}
