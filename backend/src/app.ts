import express from "express";
import helmet from "helmet";
import cors from "cors";
import { requireAuth } from "./lib/auth.js";
import { errorHandler, notFound } from "./lib/errors.js";
import { authRouter } from "./routes/auth.js";
import { teamRouter } from "./routes/team.js";
import { clientsRouter } from "./routes/clients.js";
import { inquiriesRouter } from "./routes/inquiries.js";
import { propertiesRouter } from "./routes/properties.js";
import { dashboardRouter } from "./routes/dashboard.js";
import { remindersRouter } from "./routes/reminders.js";

export function createApp() {
  const app = express();
  app.disable("x-powered-by");
  // Rupee amounts are BigInt in the database; serialise them as JSON numbers
  // (safe: every allowed amount is far below Number.MAX_SAFE_INTEGER).
  app.set("json replacer", (_key: string, value: unknown) =>
    typeof value === "bigint" ? Number(value) : value,
  );
  app.use(helmet());
  app.use(cors());
  app.use(express.json({ limit: "1mb" }));

  app.get("/health", (_req, res) => {
    res.json({ ok: true });
  });

  const api = express.Router();
  api.use("/auth", authRouter);
  api.use(requireAuth);
  api.use("/team", teamRouter);
  api.use("/clients", clientsRouter);
  api.use("/inquiries", inquiriesRouter);
  api.use("/properties", propertiesRouter);
  api.use("/dashboard", dashboardRouter);
  api.use("/reminders", remindersRouter);
  app.use("/api/v1", api);

  app.use((_req, _res, next) => next(notFound("Route")));
  app.use(errorHandler);
  return app;
}
