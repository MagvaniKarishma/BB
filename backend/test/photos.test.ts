import { mkdtemp, readFile, readdir } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import request from "supertest";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { EncryptingStore, LocalDiskStore, assertKey, getBlobStore, newKey, setBlobStore } from "../src/storage/blobStore.js";
import { app, registerBroker, resetDb } from "./helpers.js";

// Smallest valid files of each type (magic bytes are what's checked).
const JPEG = Buffer.concat([Buffer.from([0xff, 0xd8, 0xff, 0xe0]), Buffer.alloc(200, 7)]);
const PNG = Buffer.concat([Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]), Buffer.alloc(100, 1)]);

let dir: string;
beforeEach(async () => {
  await resetDb();
  dir = await mkdtemp(path.join(tmpdir(), "bb-photos-"));
  setBlobStore(new EncryptingStore(new LocalDiskStore(dir)));
});
afterEach(() => setBlobStore(undefined));

async function withProperty() {
  const b = await registerBroker();
  const property = (await b.api.post("/properties", {
    title: "1 BHK Veena Nagar", transactionType: "RENT", category: "BHK_1", price: 27000, locality: "Mulund West", bathrooms: 1,
  })).body.property;
  const upload = (buf: Buffer, name = "p.jpg", token = b.token) =>
    request(app).post(`/api/v1/properties/${property.id}/photos`).set("Authorization", `Bearer ${token}`).attach("photo", buf, name);
  return { ...b, property, upload };
}

async function allFiles(root: string): Promise<string[]> {
  const out: string[] = [];
  for (const e of await readdir(root, { withFileTypes: true, recursive: true })) if (e.isFile()) out.push(path.join(e.parentPath, e.name));
  return out;
}

describe("property photos", () => {
  it("stores the file encrypted outside the database and serves it back only to the brokerage", async () => {
    const { api, property, upload } = await withProperty();
    expect(property).toMatchObject({ bathrooms: 1, photoIds: [] });

    const up = await upload(JPEG);
    expect(up.status).toBe(201);
    expect(up.body.property.photoIds).toEqual([up.body.photoId]);

    const [file] = await allFiles(dir);
    const onDisk = await readFile(file);
    expect(onDisk.subarray(0, 4).toString()).toBe("BBE1"); // encrypted at rest
    expect(onDisk.includes(JPEG.subarray(4, 40))).toBe(false);

    const got = await api.get(`/properties/${property.id}/photos/${up.body.photoId}`).buffer(true);
    expect(got.status).toBe(200);
    expect(got.headers["content-type"]).toBe("image/jpeg");
    expect(Buffer.compare(got.body, JPEG)).toBe(0);

    // Another brokerage can't read it, even with the right ids.
    const other = await registerBroker("Other Realty");
    expect((await other.api.get(`/properties/${property.id}/photos/${up.body.photoId}`)).status).toBe(404);
    expect((await upload(PNG, "x.png", other.token)).status).toBe(404);

    expect((await api.get("/properties")).body.properties[0].photoIds).toEqual([up.body.photoId]);
  });

  it("rejects files that aren't really images, whatever their name says", async () => {
    const { upload } = await withProperty();
    const res = await upload(Buffer.from("<script>alert(1)</script>"), "evil.jpg");
    expect(res.status).toBe(415);
    expect(await allFiles(dir)).toEqual([]);
  });

  it("deletes the file with the photo and with the property", async () => {
    const { api, property, upload } = await withProperty();
    const a = (await upload(JPEG)).body.photoId;
    await upload(PNG, "b.png");
    expect(await allFiles(dir)).toHaveLength(2);

    expect((await api.delete(`/properties/${property.id}/photos/${a}`)).status).toBe(204);
    expect(await allFiles(dir)).toHaveLength(1);
    expect((await api.get(`/properties/${property.id}`)).body.property.photoIds).toHaveLength(1);

    expect((await api.delete(`/properties/${property.id}`)).status).toBe(204);
    expect(await allFiles(dir)).toEqual([]);
  });
});

describe("blob storage", () => {
  it("only accepts server-generated keys inside its directory", async () => {
    for (const bad of ["../etc/passwd", "a/../../b", "/abs", "a//b", "a\\b", ""]) expect(() => assertKey(bad)).toThrow();
    const key = newKey("brk_1", "greetings", ".m4a");
    expect(key).toMatch(/^brk_1\/greetings\/[0-9a-f]{32}\.m4a$/);
    const store = getBlobStore();
    await store.put(key, Buffer.from("hello"));
    expect((await store.get(key))?.toString()).toBe("hello");
    await store.delete(key);
    expect(await store.get(key)).toBeNull();
  });
});
