/**
 * Parses WhatsApp Cloud API webhook payloads (field "messages") into plain records.
 * Payload shape: { object: "whatsapp_business_account", entry: [{ id, changes: [{ field,
 * value: { metadata: { phone_number_id, display_phone_number }, contacts, messages, statuses } }] }] }
 * Unknown/unsupported message types are kept (type only) so nothing is silently lost.
 */

export interface InboundMessage {
  id: string;
  from: string;
  profileName: string | null;
  timestamp: Date;
  type: string;
  /** Human-readable text for the CRM (body, caption, button title, location…); null if none. */
  text: string | null;
  mediaId: string | null;
  mimeType: string | null;
  replyToId: string | null;
  raw: unknown;
}

export interface StatusUpdate {
  id: string;
  status: "sent" | "delivered" | "read" | "failed";
  timestamp: Date;
  recipient: string | null;
  error: string | null;
}

export interface WebhookChange {
  phoneNumberId: string;
  displayPhone: string | null;
  messages: InboundMessage[];
  statuses: StatusUpdate[];
}

type Json = Record<string, unknown>;
const obj = (v: unknown): Json => (v && typeof v === "object" && !Array.isArray(v) ? (v as Json) : {});
const arr = (v: unknown): unknown[] => (Array.isArray(v) ? v : []);
const str = (v: unknown): string | null => (typeof v === "string" && v.length > 0 ? v : null);
const time = (v: unknown) => {
  const n = Number(v);
  return Number.isFinite(n) && n > 0 ? new Date(n * 1000) : new Date();
};

function messageText(m: Json): { text: string | null; mediaId: string | null; mimeType: string | null } {
  const type = str(m.type) ?? "unknown";
  const part = obj(m[type]);
  switch (type) {
    case "text":
      return { text: str(obj(m.text).body), mediaId: null, mimeType: null };
    case "button":
      return { text: str(obj(m.button).text), mediaId: null, mimeType: null };
    case "interactive": {
      const i = obj(m.interactive);
      const reply = obj(i.button_reply ?? i.list_reply);
      const text = [str(reply.title), str(reply.description)].filter(Boolean).join(" — ");
      return { text: text || null, mediaId: null, mimeType: null };
    }
    case "image":
    case "video":
    case "document":
    case "audio":
    case "sticker":
      return { text: str(part.caption), mediaId: str(part.id), mimeType: str(part.mime_type) };
    case "location": {
      const l = obj(m.location);
      const text = ["📍", str(l.name), str(l.address), l.latitude != null ? `(${l.latitude}, ${l.longitude})` : null]
        .filter(Boolean)
        .join(" ");
      return { text, mediaId: null, mimeType: null };
    }
    case "contacts": {
      const people = arr(m.contacts).map((c) => {
        const cc = obj(c);
        const phone = str(obj(arr(cc.phones)[0]).phone);
        return [str(obj(cc.name).formatted_name), phone].filter(Boolean).join(" ");
      });
      return { text: people.length ? `Shared contact: ${people.join("; ")}` : null, mediaId: null, mimeType: null };
    }
    default:
      return { text: null, mediaId: null, mimeType: null };
  }
}

export function parseWebhook(body: unknown): WebhookChange[] {
  const root = obj(body);
  if (root.object !== "whatsapp_business_account") return [];
  const changes: WebhookChange[] = [];
  for (const entry of arr(root.entry)) {
    for (const change of arr(obj(entry).changes)) {
      const c = obj(change);
      if (c.field !== "messages") continue;
      const value = obj(c.value);
      const meta = obj(value.metadata);
      const phoneNumberId = str(meta.phone_number_id);
      if (!phoneNumberId) continue;
      const names = new Map<string, string>();
      for (const contact of arr(value.contacts)) {
        const cc = obj(contact);
        const waId = str(cc.wa_id);
        const name = str(obj(cc.profile).name);
        if (waId && name) names.set(waId, name);
      }
      const messages: InboundMessage[] = [];
      for (const raw of arr(value.messages)) {
        const m = obj(raw);
        const id = str(m.id);
        const from = str(m.from);
        if (!id || !from) continue;
        messages.push({
          id,
          from,
          profileName: names.get(from) ?? null,
          timestamp: time(m.timestamp),
          type: str(m.type) ?? "unknown",
          ...messageText(m),
          replyToId: str(obj(m.context).id),
          raw,
        });
      }
      const statuses: StatusUpdate[] = [];
      for (const raw of arr(value.statuses)) {
        const s = obj(raw);
        const id = str(s.id);
        const status = str(s.status);
        if (!id || !status || !["sent", "delivered", "read", "failed"].includes(status)) continue;
        const err = obj(arr(s.errors)[0]);
        statuses.push({
          id,
          status: status as StatusUpdate["status"],
          timestamp: time(s.timestamp),
          recipient: str(s.recipient_id),
          error: str(err.title) ?? str(err.message) ?? (err.code != null ? `Error ${err.code}` : null),
        });
      }
      changes.push({ phoneNumberId, displayPhone: str(meta.display_phone_number), messages, statuses });
    }
  }
  return changes;
}
