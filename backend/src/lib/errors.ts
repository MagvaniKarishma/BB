import type { ErrorRequestHandler } from "express";
import { Prisma } from "@prisma/client";
import { ZodError } from "zod";

export class HttpError extends Error {
  constructor(
    public status: number,
    public code: string,
    message: string,
    public details?: unknown,
  ) {
    super(message);
  }
}

export const notFound = (what: string) => new HttpError(404, "NOT_FOUND", `${what} not found`);
export const forbidden = (message = "Not allowed") => new HttpError(403, "FORBIDDEN", message);
export const badRequest = (message: string, details?: unknown) =>
  new HttpError(400, "BAD_REQUEST", message, details);

export const errorHandler: ErrorRequestHandler = (err, _req, res, _next) => {
  if (err instanceof HttpError) {
    res.status(err.status).json({ error: { code: err.code, message: err.message, details: err.details } });
    return;
  }
  if (err instanceof ZodError) {
    res.status(400).json({
      error: { code: "VALIDATION_ERROR", message: "Invalid request", details: err.flatten() },
    });
    return;
  }
  if (err instanceof Prisma.PrismaClientKnownRequestError && err.code === "P2002") {
    res.status(409).json({ error: { code: "CONFLICT", message: "Record already exists" } });
    return;
  }
  if (err?.name === "MulterError") {
    const tooLarge = err.code === "LIMIT_FILE_SIZE";
    res.status(tooLarge ? 413 : 400).json({
      error: { code: tooLarge ? "AUDIO_TOO_LARGE" : "BAD_UPLOAD", message: tooLarge ? "Recording is too large" : err.message },
    });
    return;
  }
  if (err?.type === "entity.parse.failed") {
    res.status(400).json({ error: { code: "BAD_JSON", message: "Malformed JSON body" } });
    return;
  }
  console.error(describeForLog(err));
  res.status(500).json({ error: { code: "INTERNAL", message: "Internal server error" } });
};

/**
 * What an unexpected error looks like in the server log. Database errors carry the query's
 * values (client names, phone numbers, messages), so only their kind and code are logged.
 */
export function describeForLog(err: unknown): string {
  if (err instanceof Prisma.PrismaClientKnownRequestError) return `Database error ${err.code} (${err.meta?.modelName ?? "query"})`;
  if (err instanceof Prisma.PrismaClientValidationError) return "Database validation error (details withheld: may contain client data)";
  if (err instanceof Error) return err.stack ?? `${err.name}: ${err.message}`;
  return String(err);
}
