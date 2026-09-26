import { createApp } from "./app.js";
import { config } from "./config.js";
import { prisma } from "./db.js";

// Fail fast on missing secrets rather than at first login.
void config.jwtSecret;

const server = createApp().listen(config.port, () => {
  console.log(`BrokerBuddy API listening on :${config.port}`);
});

const shutdown = () => {
  server.close(() => void prisma.$disconnect().then(() => process.exit(0)));
};
process.on("SIGINT", shutdown);
process.on("SIGTERM", shutdown);
