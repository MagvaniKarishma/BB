import { createApp } from "./app.js";
import { describeForLog } from "./lib/errors.js";
import { config } from "./config.js";
import { prisma } from "./db.js";
import { processPending } from "./whatsapp/processor.js";
import { sweepCalls } from "./callAssistant/sessions.js";

// Fail fast on missing secrets rather than at first login.
void config.jwtSecret;

const server = createApp().listen(config.port, () => {
  console.log(`BrokerBuddy API listening on :${config.port}`);
});

// Retry WhatsApp messages whose processing didn't finish (e.g. after a restart).
const sweeper = setInterval(() => {
  processPending().catch((err) => console.error("WhatsApp sweep failed", describeForLog(err)));
  // AI calls whose "ended" webhook never came, and CRM saves to retry.
  sweepCalls().catch((err) => console.error("AI call sweep failed", describeForLog(err)));
}, 60_000);

const shutdown = () => {
  clearInterval(sweeper);
  server.close(() => void prisma.$disconnect().then(() => process.exit(0)));
};
process.on("SIGINT", shutdown);
process.on("SIGTERM", shutdown);
