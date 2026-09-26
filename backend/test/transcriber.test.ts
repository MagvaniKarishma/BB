import { afterAll, beforeAll, describe, expect, it } from "vitest";
import http from "node:http";
import type { AddressInfo } from "node:net";
import { HttpTranscriber } from "../src/voice/transcriber.js";

let server: http.Server;
let url: string;
let last: { auth?: string; body: string; contentType?: string } = { body: "" };
let reply: { status: number; body: unknown } = { status: 200, body: { text: " नमस्ते " } };

beforeAll(async () => {
  server = http.createServer((req, res) => {
    const chunks: Buffer[] = [];
    req.on("data", (c) => chunks.push(c));
    req.on("end", () => {
      last = { auth: req.headers.authorization, body: Buffer.concat(chunks).toString("latin1"), contentType: req.headers["content-type"] };
      res.writeHead(reply.status, { "content-type": "application/json" });
      res.end(JSON.stringify(reply.body));
    });
  });
  await new Promise<void>((r) => server.listen(0, r));
  url = `http://127.0.0.1:${(server.address() as AddressInfo).port}/v1/audio/transcriptions`;
});
afterAll(() => server.close());

describe("HttpTranscriber", () => {
  it("posts multipart audio with model, language and auth", async () => {
    const t = new HttpTranscriber(url, "secret", "whisper-1");
    expect(await t.transcribe(Buffer.from("AUDIO"), "audio/mp4", "HINDI")).toBe("नमस्ते");
    expect(last.auth).toBe("Bearer secret");
    expect(last.contentType).toMatch(/^multipart\/form-data/);
    expect(last.body).toContain('filename="note.m4a"');
    expect(last.body).toContain("AUDIO");
    expect(last.body).toMatch(/name="model"\r\n\r\nwhisper-1/);
    expect(last.body).toMatch(/name="language"\r\n\r\nhi/);
  });

  it("uses auto-detect plus a romanised prompt for Hinglish", async () => {
    await new HttpTranscriber(url, undefined, "m").transcribe(Buffer.from("A"), "audio/mp4", "HINGLISH");
    expect(last.body).not.toContain('name="language"');
    expect(last.body).toContain('name="prompt"');
    expect(last.auth).toBeUndefined();
  });

  it("surfaces service errors", async () => {
    reply = { status: 503, body: { error: "busy" } };
    await expect(new HttpTranscriber(url, "k", "m").transcribe(Buffer.from("A"), "audio/mp4", "AUTO")).rejects.toThrow(/503/);
    reply = { status: 200, body: {} };
    await expect(new HttpTranscriber(url, "k", "m").transcribe(Buffer.from("A"), "audio/mp4", "AUTO")).rejects.toThrow(/no text/);
  });
});
