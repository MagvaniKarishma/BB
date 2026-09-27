import type { Client, Inquiry, Prisma } from "@prisma/client";
import { prisma } from "../db.js";
import type { AuthUser } from "../lib/auth.js";
import { HttpError } from "../lib/errors.js";
import { normalizePhone } from "../lib/phone.js";
import { createInquiryTx, updateInquiryTx } from "../routes/inquiries.js";
import { createInquirySchema, updateInquirySchema } from "../schemas.js";
import { addPhoneChecked, createClientChecked, findByPhones } from "../services/clients.js";
import type { RequirementDraft } from "../voice/draft.js";
import { type CallState, summarize } from "./engine.js";
import { loadSettings } from "./settings.js";

/**
 * Saves a finished AI call to the CRM, exactly once per call:
 *  - the caller is matched by phone number to an existing client, or a new client is
 *    created through the same duplicate-safe path as every other channel;
 *  - existing client details are never overwritten; only empty fields are filled;
 *  - the requirement updates the client's matching active inquiry (with history) or
 *    becomes a new one, using only what the caller said;
 *  - a call note (summary + transcript) and, if asked, a callback follow-up are added.
 */

/** Label for a client whose name we don't know — descriptive, not a guessed name. */
const placeholderName = (e164: string) => `Caller ${e164.replace(/^\+91(\d{5})(\d{5})$/, "$1 $2")}`;
const isPlaceholder = (name: string) => /^Caller [\d +]+$/.test(name);

/** Only the fields the caller actually stated. */
export function draftToPatch(d: RequirementDraft) {
  const patch: Record<string, unknown> = {};
  if (d.transactionType) patch.transactionType = d.transactionType.value;
  if (d.category) patch.category = d.category.value;
  if (d.budgetMin) patch.budgetMin = d.budgetMin.value;
  if (d.budgetMax) patch.budgetMax = d.budgetMax.value;
  if (d.locations?.length) patch.locations = d.locations.map((l) => l.value.slice(0, 80));
  if (d.furnishing) patch.furnishing = d.furnishing.value;
  if (d.minParking) patch.minParking = d.minParking.value;
  if (d.floorPreference) patch.floorPreference = d.floorPreference.value;
  if (d.possession) patch.possession = d.possession.value;
  if (d.possessionBy) patch.possessionBy = d.possessionBy.value;
  return patch;
}

async function actor(brokerageId: string): Promise<AuthUser> {
  const owner = await prisma.user.findFirstOrThrow({ where: { brokerageId, role: "OWNER" }, orderBy: { createdAt: "asc" } });
  return { id: owner.id, brokerageId, role: owner.role, name: owner.name };
}

async function findOrCreateClient(me: AuthUser, phone: string, name: string | null, altPhone: string | null) {
  let client: Client | null = await findByPhones(me.brokerageId, [phone, ...(altPhone ? [altPhone] : [])]);
  let created = false;
  if (!client) {
    try {
      client = await createClientChecked(me, {
        name: name ?? placeholderName(phone), phone, altPhones: altPhone && altPhone !== phone ? [altPhone] : [],
        leadSource: "AI_CALL_ASSISTANT", status: "NEW",
      });
      created = true;
    } catch (err) {
      // Another request created them a moment ago: link to that client.
      if (!(err instanceof HttpError && err.code === "DUPLICATE_CLIENT")) throw err;
      client = await findByPhones(me.brokerageId, [phone]);
      if (!client) throw err;
    }
  }
  if (!created) {
    // Fill only what's missing: our own placeholder name, or a number they gave us.
    if (name && isPlaceholder(client.name)) client = await prisma.client.update({ where: { id: client.id }, data: { name } });
    for (const n of [phone, altPhone]) {
      if (!n) continue;
      await addPhoneChecked(me.brokerageId, client.id, n, "from AI call").catch(() => undefined); // already theirs, or someone else's
    }
  }
  return client;
}

async function saveRequirement(me: AuthUser, clientId: string, draft: RequirementDraft): Promise<Inquiry | null> {
  const patch = draftToPatch(draft);
  if (Object.keys(patch).length === 0) return null;
  return prisma.$transaction(async (tx) => {
    const existing =
      patch.transactionType && patch.category
        ? await tx.inquiry.findFirst({
            where: { clientId, status: "ACTIVE", transactionType: patch.transactionType as never, category: patch.category as never },
            orderBy: { updatedAt: "desc" },
          })
        : await tx.inquiry.findFirst({ where: { clientId, status: "ACTIVE" }, orderBy: { updatedAt: "desc" } });
    if (existing) {
      // Add areas rather than replace them.
      if (patch.locations) patch.locations = [...new Set([...existing.locations, ...(patch.locations as string[])])].slice(0, 20);
      // Same validation as the app's API; an out-of-range value drops the update, not the call.
      const parsed = updateInquirySchema.safeParse(patch);
      if (!parsed.success) return existing;
      const { source: _ignored, ...fields } = parsed.data;
      const { inquiry } = await updateInquiryTx(tx, me, existing, fields, "AI_CALL");
      return inquiry;
    }
    // A new requirement needs at least rent/buy and the property type.
    if (!patch.transactionType || !patch.category) return null;
    const parsed = createInquirySchema.safeParse({ ...patch, source: "AI_CALL" });
    return parsed.success ? createInquiryTx(tx, me, clientId, parsed.data) : null;
  });
}

/** Saves the call once; later calls (webhook retries, the sweeper) are no-ops. */
export async function saveCallToCrm(sessionId: string): Promise<void> {
  const claimed = await prisma.callSession.updateMany({
    where: { id: sessionId, savedAt: null, isTest: false },
    data: { savedAt: new Date() },
  });
  if (claimed.count === 0) return;
  const session = await prisma.callSession.findUniqueOrThrow({ where: { id: sessionId }, include: { turns: { orderBy: { seq: "asc" } } } });
  try {
    const state = (session.state as unknown as { engine: CallState }).engine;
    const caller = session.fromNumber ? normalizePhone(session.fromNumber) : null;
    const summary = summarize(state, caller);
    const me = await actor(session.brokerageId);
    const settings = await loadSettings(session.brokerageId);
    const phone = state.callbackNumber?.value ?? caller;

    let clientId: string | null = null;
    let inquiryId: string | null = null;
    if (phone) {
      const client = await findOrCreateClient(me, phone, state.name?.value ?? null, state.callbackNumber ? caller : null);
      clientId = client.id;
      inquiryId = (await saveRequirement(me, client.id, state.draft))?.id ?? null;
      const transcript = session.turns.map((t) => `${t.role === "CALLER" ? "Caller" : "AI"}: ${t.text}`).join("\n");
      const minutes = session.durationSec != null ? ` (${Math.max(1, Math.round(session.durationSec / 60))} min)` : "";
      await prisma.clientNote.create({
        data: {
          brokerageId: session.brokerageId, clientId, authorId: null, source: "AI_CALL",
          body: `${summary}\nStatus: ${session.status.toLowerCase()}${minutes}.\n\n${transcript}`.slice(0, 8000),
        },
      });
      // Follow up when asked to, and when the call dropped before the end (unless they declined a call).
      const interrupted = session.status === "INTERRUPTED" && state.callbackRequested !== false;
      if ((state.callbackRequested || interrupted) && settings.callbackReminder) {
        const assignee =
          settings.callbackAssigneeId &&
          (await prisma.user.findFirst({ where: { id: settings.callbackAssigneeId, brokerageId: session.brokerageId, active: true } }));
        await prisma.reminder.create({
          data: {
            brokerageId: session.brokerageId, clientId, inquiryId, assignedToId: assignee ? assignee.id : me.id,
            dueAt: new Date(Date.now() + settings.callbackDelayMinutes * 60_000),
            title: `Call back ${state.name?.value ?? phone} (${interrupted && !state.callbackRequested ? "AI call ended early" : "AI call"})`,
            note: summary.slice(0, 2000),
            kind: "CALLBACK",
          },
        });
      }
    }
    await prisma.callSession.update({ where: { id: sessionId }, data: { summary, clientId, inquiryId } });
  } catch (err) {
    // Release the claim so the sweeper can retry.
    await prisma.callSession.update({ where: { id: sessionId }, data: { savedAt: null } });
    throw err;
  }
}

export type SessionWithTurns = Prisma.CallSessionGetPayload<{ include: { turns: true } }>;
