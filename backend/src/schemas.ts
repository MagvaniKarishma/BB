import { z } from "zod";
import {
  Availability,
  ClientStatus,
  Furnishing,
  InquiryStatus,
  LeadSource,
  NoteSource,
  Possession,
  PropertyCategory,
  ReminderStatus,
  RequirementField,
  RequirementSource,
  Role,
  TransactionType,
} from "@prisma/client";

// Rupee amounts: whole numbers up to ₹10,000 crore.
const rupees = z.number().int().nonnegative().max(1e11);
const trimmed = (max: number) => z.string().trim().min(1).max(max);
const optionalText = (max: number) => z.string().trim().max(max).nullish();
const isoDate = z.coerce.date();

export const registerSchema = z.object({
  brokerageName: trimmed(120),
  name: trimmed(120),
  email: z.string().trim().toLowerCase().email(),
  password: z.string().min(8).max(200),
  phone: z.string().trim().max(30).optional(),
});

export const loginSchema = z.object({
  email: z.string().trim().toLowerCase().email(),
  password: z.string().min(1).max(200),
});

export const createMemberSchema = z.object({
  name: trimmed(120),
  email: z.string().trim().toLowerCase().email(),
  password: z.string().min(8).max(200),
  phone: z.string().trim().max(30).optional(),
  role: z.enum([Role.ADMIN, Role.AGENT]).default(Role.AGENT),
});

export const updateMemberSchema = z.object({
  role: z.enum([Role.ADMIN, Role.AGENT]).optional(),
  active: z.boolean().optional(),
});

export const createClientSchema = z.object({
  name: trimmed(120),
  phone: trimmed(30),
  altPhones: z.array(trimmed(30)).max(5).default([]),
  email: z.string().trim().toLowerCase().email().nullish(),
  leadSource: z.nativeEnum(LeadSource),
  status: z.nativeEnum(ClientStatus).default(ClientStatus.NEW),
  notes: optionalText(5000),
  assignedToId: z.string().nullish(),
});

export const updateClientSchema = z.object({
  name: trimmed(120).optional(),
  email: z.string().trim().toLowerCase().email().nullish(),
  leadSource: z.nativeEnum(LeadSource).optional(),
  status: z.nativeEnum(ClientStatus).optional(),
  notes: optionalText(5000),
  assignedToId: z.string().nullish(),
});

export const addPhoneSchema = z.object({
  phone: trimmed(30),
  label: optionalText(40),
});

export const addNoteSchema = z.object({
  body: trimmed(5000),
  source: z.nativeEnum(NoteSource).default(NoteSource.MANUAL),
});

export const listClientsSchema = z.object({
  q: z.string().trim().max(100).optional(),
  status: z.nativeEnum(ClientStatus).optional(),
  leadSource: z.nativeEnum(LeadSource).optional(),
  assignedToId: z.string().optional(),
  page: z.coerce.number().int().min(1).default(1),
  pageSize: z.coerce.number().int().min(1).max(100).default(30),
});

const requirementFields = {
  transactionType: z.nativeEnum(TransactionType),
  category: z.nativeEnum(PropertyCategory),
  status: z.nativeEnum(InquiryStatus),
  budgetMin: rupees.nullish(),
  budgetMax: rupees.nullish(),
  locations: z.array(trimmed(80)).max(20),
  furnishing: z.array(z.nativeEnum(Furnishing)).max(3),
  minParking: z.number().int().min(0).max(20).nullish(),
  floorMin: z.number().int().min(-5).max(200).nullish(),
  floorMax: z.number().int().min(-5).max(200).nullish(),
  possession: z.nativeEnum(Possession).nullish(),
  possessionBy: isoDate.nullish(),
  mandatory: z.array(z.nativeEnum(RequirementField)).max(6),
  notes: optionalText(5000),
};

type RangeCheckable = {
  budgetMin?: number | null;
  budgetMax?: number | null;
  floorMin?: number | null;
  floorMax?: number | null;
};
const checkRanges = (v: RangeCheckable, ctx: z.RefinementCtx) => {
  if (v.budgetMin != null && v.budgetMax != null && v.budgetMin > v.budgetMax) {
    ctx.addIssue({ code: z.ZodIssueCode.custom, path: ["budgetMin"], message: "budgetMin > budgetMax" });
  }
  if (v.floorMin != null && v.floorMax != null && v.floorMin > v.floorMax) {
    ctx.addIssue({ code: z.ZodIssueCode.custom, path: ["floorMin"], message: "floorMin > floorMax" });
  }
};

export const createInquirySchema = z
  .object({
    ...requirementFields,
    status: requirementFields.status.default(InquiryStatus.ACTIVE),
    locations: requirementFields.locations.default([]),
    furnishing: requirementFields.furnishing.default([]),
    mandatory: requirementFields.mandatory.default([]),
    source: z.nativeEnum(RequirementSource).default(RequirementSource.MANUAL),
  })
  .superRefine(checkRanges);

export const updateInquirySchema = z
  .object({
    transactionType: requirementFields.transactionType.optional(),
    category: requirementFields.category.optional(),
    status: requirementFields.status.optional(),
    budgetMin: requirementFields.budgetMin,
    budgetMax: requirementFields.budgetMax,
    locations: requirementFields.locations.optional(),
    furnishing: requirementFields.furnishing.optional(),
    minParking: requirementFields.minParking,
    floorMin: requirementFields.floorMin,
    floorMax: requirementFields.floorMax,
    possession: requirementFields.possession,
    possessionBy: requirementFields.possessionBy,
    mandatory: requirementFields.mandatory.optional(),
    notes: requirementFields.notes,
    source: z.nativeEnum(RequirementSource).default(RequirementSource.MANUAL),
  })
  .superRefine(checkRanges);

export const listInquiriesSchema = z.object({
  transactionType: z.nativeEnum(TransactionType).optional(),
  category: z.nativeEnum(PropertyCategory).optional(),
  status: z.nativeEnum(InquiryStatus).optional(),
});

const propertyFields = {
  title: trimmed(160),
  transactionType: z.nativeEnum(TransactionType),
  category: z.nativeEnum(PropertyCategory),
  price: rupees.positive(),
  deposit: rupees.nullish(),
  locality: trimmed(80),
  building: optionalText(120),
  address: optionalText(300),
  carpetAreaSqft: z.number().int().positive().max(1_000_000).nullish(),
  bathrooms: z.number().int().min(0).max(20).nullish(),
  furnishing: z.nativeEnum(Furnishing).nullish(),
  parkingSpots: z.number().int().min(0).max(50).nullish(),
  floor: z.number().int().min(-5).max(200).nullish(),
  totalFloors: z.number().int().min(0).max(200).nullish(),
  possession: z.nativeEnum(Possession).nullish(),
  possessionDate: isoDate.nullish(),
  availability: z.nativeEnum(Availability),
  ownerName: optionalText(120),
  ownerPhone: optionalText(30),
  notes: optionalText(5000),
};

export const createPropertySchema = z.object({
  ...propertyFields,
  availability: propertyFields.availability.default(Availability.AVAILABLE),
});

export const updatePropertySchema = z.object(propertyFields).partial();

export const listPropertiesSchema = z.object({
  transactionType: z.nativeEnum(TransactionType).optional(),
  category: z.nativeEnum(PropertyCategory).optional(),
  availability: z.nativeEnum(Availability).optional(),
  q: z.string().trim().max(100).optional(),
  page: z.coerce.number().int().min(1).default(1),
  pageSize: z.coerce.number().int().min(1).max(100).default(30),
});

export const createReminderSchema = z.object({
  title: trimmed(160),
  note: optionalText(2000),
  dueAt: isoDate,
  clientId: z.string().nullish(),
  inquiryId: z.string().nullish(),
  assignedToId: z.string().optional(),
});

export const updateReminderSchema = z.object({
  title: trimmed(160).optional(),
  note: optionalText(2000),
  dueAt: isoDate.optional(),
  status: z.nativeEnum(ReminderStatus).optional(),
  assignedToId: z.string().optional(),
});

export const listRemindersSchema = z.object({
  status: z.nativeEnum(ReminderStatus).optional(),
  scope: z.enum(["mine", "all"]).default("mine"),
  clientId: z.string().optional(),
  from: isoDate.optional(),
  to: isoDate.optional(),
});
