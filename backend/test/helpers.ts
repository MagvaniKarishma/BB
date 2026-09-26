import request from "supertest";
import { createApp } from "../src/app.js";
import { prisma } from "../src/db.js";

export const app = createApp();

export async function resetDb() {
  await prisma.$executeRawUnsafe(
    `TRUNCATE "Reminder","InquiryRevision","Inquiry","ClientPhone","Client","Property","User","Brokerage" CASCADE`,
  );
}

let counter = 0;
/** Registers a fresh brokerage and returns an authenticated request helper. */
export async function registerBroker(brokerageName = "Test Realty") {
  counter += 1;
  const email = `owner${counter}-${Date.now()}@example.com`;
  const res = await request(app)
    .post("/api/v1/auth/register")
    .send({ brokerageName, name: "Owner", email, password: "password123" });
  if (res.status !== 201) throw new Error(`register failed: ${res.status} ${JSON.stringify(res.body)}`);
  return { token: res.body.token as string, user: res.body.user, api: authed(res.body.token) };
}

export function authed(token: string) {
  const h = { Authorization: `Bearer ${token}` };
  return {
    get: (url: string) => request(app).get(`/api/v1${url}`).set(h),
    post: (url: string, body?: object) => request(app).post(`/api/v1${url}`).set(h).send(body ?? {}),
    patch: (url: string, body: object) => request(app).patch(`/api/v1${url}`).set(h).send(body),
    delete: (url: string) => request(app).delete(`/api/v1${url}`).set(h),
  };
}
