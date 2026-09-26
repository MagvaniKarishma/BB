import { Router } from "express";
import { z } from "zod";
import { runCommand } from "../assistant/commands.js";
import { currentUser } from "../lib/auth.js";

export const assistantRouter = Router();

/** A spoken (already transcribed on the phone) or typed command. */
assistantRouter.post("/command", async (req, res) => {
  const me = currentUser(req);
  const body = z.object({
    text: z.string().trim().min(2).max(500),
    tz: z.number().int().min(-720).max(840).default(330),
  }).parse(req.body);
  res.json(await runCommand(me, body.text, body.tz));
});
