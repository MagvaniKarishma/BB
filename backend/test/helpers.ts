import request from "supertest";
import { createApp } from "../src/app.js";
import { prisma } from "../src/db.js";

export const app = createApp();

export async function resetDb() {
  await prisma.$executeRawUnsafe(
    `TRUNCATE "WhatsAppMessage","WhatsAppContact","WhatsAppAccount","ClientNote","VoiceNote","Reminder","InquiryRevision","Inquiry","ClientPhone","Client","OtpChallenge","PropertyPhoto","Property","CallTurn","CallSession","CallGreeting","CallAssistantSettings","PortalLead","PortalListing","PortalIntegration","User","Brokerage" CASCADE`,
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
    put: (url: string, body: object) => request(app).put(`/api/v1${url}`).set(h).send(body),
    delete: (url: string) => request(app).delete(`/api/v1${url}`).set(h),
  };
}

/** Payload shaped like Meta's documented "messages" webhook. */
export function metaPayload(phoneNumberId: string, messages: object[], contacts: object[] = [], statuses: object[] = []) {
  return {
    object: "whatsapp_business_account",
    entry: [
      {
        id: "102290129340398",
        changes: [
          {
            field: "messages",
            value: {
              messaging_product: "whatsapp",
              metadata: { display_phone_number: "919820000000", phone_number_id: phoneNumberId },
              contacts,
              messages,
              statuses,
            },
          },
        ],
      },
    ],
  };
}


// Real Housing.com enquiries received by agents (shared by the product owner).
export const REAL_HOUSING_1 =
  "Hi, I came across your 1 BHK Apartment listed at Housing.com for ₹ 27,000 in Mulund West, Mumbai https://dzfki.app.link/XTXUr0PoI6b. I am interested in the property. Please let me know if it is available. Thank you!";
export const REAL_99ACRES_1 = `Hi,
I am interested in 1 BHK Flat in Veena Nagar. Wanted to discuss with you about the same.

Regards,
karishma

https://www.99acres.com/I94007278`;
export const REAL_HOUSING_2 =
  "Hi, I came across your 1 BHK Apartment listed at Housing.com for ₹ 33,000 in Mulund West, Mumbai https://dzfki.app.link/WswPAG2oI6b. I am interested in the property. Please let me know if it is available. Thank you!";

