import { createCipheriv, createDecipheriv, createHmac, randomBytes, timingSafeEqual } from "node:crypto";

/**
 * AES-256-GCM for secrets stored in the database (WhatsApp access tokens, app secrets).
 * DATA_ENCRYPTION_KEY: 32 bytes as base64 or hex. Never logged, never returned by the API.
 */
function key(): Buffer {
  const raw = process.env.DATA_ENCRYPTION_KEY;
  if (!raw) throw new Error("DATA_ENCRYPTION_KEY is not configured");
  const buf = /^[0-9a-f]{64}$/i.test(raw) ? Buffer.from(raw, "hex") : Buffer.from(raw, "base64");
  if (buf.length !== 32) throw new Error("DATA_ENCRYPTION_KEY must be 32 bytes (base64 or hex)");
  return buf;
}

export const encryptionConfigured = () => {
  try {
    key();
    return true;
  } catch {
    return false;
  }
};

export function encrypt(plaintext: string): string {
  const iv = randomBytes(12);
  const cipher = createCipheriv("aes-256-gcm", key(), iv);
  const data = Buffer.concat([cipher.update(plaintext, "utf8"), cipher.final()]);
  return ["v1", iv.toString("base64"), cipher.getAuthTag().toString("base64"), data.toString("base64")].join(".");
}

export function decrypt(payload: string): string {
  const [version, iv, tag, data] = payload.split(".");
  if (version !== "v1" || !iv || !tag || !data) throw new Error("Unrecognised encrypted value");
  const decipher = createDecipheriv("aes-256-gcm", key(), Buffer.from(iv, "base64"));
  decipher.setAuthTag(Buffer.from(tag, "base64"));
  return Buffer.concat([decipher.update(Buffer.from(data, "base64")), decipher.final()]).toString("utf8");
}

const BLOB_MAGIC = Buffer.from("BBE1");

/** AES-256-GCM for stored files: "BBE1" | iv(12) | tag(16) | ciphertext. */
export function encryptBytes(plain: Buffer): Buffer {
  const iv = randomBytes(12);
  const cipher = createCipheriv("aes-256-gcm", key(), iv);
  const data = Buffer.concat([cipher.update(plain), cipher.final()]);
  return Buffer.concat([BLOB_MAGIC, iv, cipher.getAuthTag(), data]);
}

export const isEncryptedBytes = (b: Buffer) => b.length >= 32 && b.subarray(0, 4).equals(BLOB_MAGIC);

export function decryptBytes(stored: Buffer): Buffer {
  if (!isEncryptedBytes(stored)) throw new Error("Unrecognised encrypted file");
  const decipher = createDecipheriv("aes-256-gcm", key(), stored.subarray(4, 16));
  decipher.setAuthTag(stored.subarray(16, 32));
  return Buffer.concat([decipher.update(stored.subarray(32)), decipher.final()]);
}

export const randomToken = (bytes = 24) => randomBytes(bytes).toString("base64url");

/** Constant-time check of Meta's X-Hub-Signature-256 header ("sha256=<hex>") over the raw body. */
export function validMetaSignature(rawBody: Buffer, header: string | undefined, appSecret: string): boolean {
  if (!header?.startsWith("sha256=")) return false;
  const expected = createHmac("sha256", appSecret).update(rawBody).digest();
  const given = Buffer.from(header.slice(7), "hex");
  return given.length === expected.length && timingSafeEqual(given, expected);
}
