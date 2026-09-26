-- CreateEnum
CREATE TYPE "Role" AS ENUM ('OWNER', 'ADMIN', 'AGENT');

-- CreateEnum
CREATE TYPE "LeadSource" AS ENUM ('WALK_IN', 'REFERRAL', 'PHONE_CALL', 'WHATSAPP', 'ACRES_99', 'HOUSING_COM', 'MAGICBRICKS', 'WEBSITE', 'SOCIAL_MEDIA', 'OTHER');

-- CreateEnum
CREATE TYPE "ClientStatus" AS ENUM ('NEW', 'CONTACTED', 'SITE_VISIT', 'NEGOTIATION', 'CLOSED_WON', 'CLOSED_LOST', 'ON_HOLD');

-- CreateEnum
CREATE TYPE "TransactionType" AS ENUM ('RENT', 'BUY');

-- CreateEnum
CREATE TYPE "PropertyCategory" AS ENUM ('STUDIO', 'BHK_1', 'BHK_2', 'BHK_3', 'BHK_4', 'BHK_5_PLUS', 'COMMERCIAL', 'OTHER');

-- CreateEnum
CREATE TYPE "Furnishing" AS ENUM ('UNFURNISHED', 'SEMI_FURNISHED', 'FULLY_FURNISHED');

-- CreateEnum
CREATE TYPE "Possession" AS ENUM ('READY_TO_MOVE', 'UNDER_CONSTRUCTION');

-- CreateEnum
CREATE TYPE "InquiryStatus" AS ENUM ('ACTIVE', 'PAUSED', 'FULFILLED', 'DROPPED');

-- CreateEnum
CREATE TYPE "RequirementField" AS ENUM ('BUDGET', 'LOCATION', 'FURNISHING', 'PARKING', 'FLOOR', 'POSSESSION');

-- CreateEnum
CREATE TYPE "RequirementSource" AS ENUM ('MANUAL', 'VOICE_NOTE', 'PORTAL_LEAD', 'WHATSAPP');

-- CreateEnum
CREATE TYPE "Availability" AS ENUM ('AVAILABLE', 'ON_HOLD', 'RENTED', 'SOLD', 'WITHDRAWN');

-- CreateEnum
CREATE TYPE "ReminderStatus" AS ENUM ('PENDING', 'DONE', 'CANCELLED');

-- CreateTable
CREATE TABLE "Brokerage" (
    "id" TEXT NOT NULL,
    "name" TEXT NOT NULL,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "Brokerage_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "User" (
    "id" TEXT NOT NULL,
    "brokerageId" TEXT NOT NULL,
    "name" TEXT NOT NULL,
    "email" TEXT NOT NULL,
    "phone" TEXT,
    "passwordHash" TEXT NOT NULL,
    "role" "Role" NOT NULL DEFAULT 'AGENT',
    "active" BOOLEAN NOT NULL DEFAULT true,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "User_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "Client" (
    "id" TEXT NOT NULL,
    "brokerageId" TEXT NOT NULL,
    "name" TEXT NOT NULL,
    "primaryPhone" TEXT NOT NULL,
    "email" TEXT,
    "leadSource" "LeadSource" NOT NULL,
    "status" "ClientStatus" NOT NULL DEFAULT 'NEW',
    "notes" TEXT,
    "assignedToId" TEXT,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "Client_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "ClientPhone" (
    "id" TEXT NOT NULL,
    "brokerageId" TEXT NOT NULL,
    "clientId" TEXT NOT NULL,
    "e164" TEXT NOT NULL,
    "label" TEXT,

    CONSTRAINT "ClientPhone_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "Inquiry" (
    "id" TEXT NOT NULL,
    "brokerageId" TEXT NOT NULL,
    "clientId" TEXT NOT NULL,
    "transactionType" "TransactionType" NOT NULL,
    "category" "PropertyCategory" NOT NULL,
    "status" "InquiryStatus" NOT NULL DEFAULT 'ACTIVE',
    "budgetMin" BIGINT,
    "budgetMax" BIGINT,
    "locations" TEXT[],
    "furnishing" "Furnishing"[],
    "minParking" INTEGER,
    "floorMin" INTEGER,
    "floorMax" INTEGER,
    "possession" "Possession",
    "possessionBy" TIMESTAMP(3),
    "mandatory" "RequirementField"[],
    "notes" TEXT,
    "source" "RequirementSource" NOT NULL DEFAULT 'MANUAL',
    "version" INTEGER NOT NULL DEFAULT 1,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "Inquiry_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "InquiryRevision" (
    "id" TEXT NOT NULL,
    "inquiryId" TEXT NOT NULL,
    "version" INTEGER NOT NULL,
    "changedById" TEXT,
    "source" "RequirementSource" NOT NULL,
    "changes" JSONB NOT NULL,
    "snapshot" JSONB NOT NULL,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "InquiryRevision_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "Property" (
    "id" TEXT NOT NULL,
    "brokerageId" TEXT NOT NULL,
    "title" TEXT NOT NULL,
    "transactionType" "TransactionType" NOT NULL,
    "category" "PropertyCategory" NOT NULL,
    "price" BIGINT NOT NULL,
    "deposit" BIGINT,
    "locality" TEXT NOT NULL,
    "building" TEXT,
    "address" TEXT,
    "carpetAreaSqft" INTEGER,
    "furnishing" "Furnishing",
    "parkingSpots" INTEGER,
    "floor" INTEGER,
    "totalFloors" INTEGER,
    "possession" "Possession",
    "possessionDate" TIMESTAMP(3),
    "availability" "Availability" NOT NULL DEFAULT 'AVAILABLE',
    "ownerName" TEXT,
    "ownerPhone" TEXT,
    "notes" TEXT,
    "listedById" TEXT,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "Property_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "Reminder" (
    "id" TEXT NOT NULL,
    "brokerageId" TEXT NOT NULL,
    "clientId" TEXT,
    "inquiryId" TEXT,
    "assignedToId" TEXT NOT NULL,
    "createdById" TEXT,
    "dueAt" TIMESTAMP(3) NOT NULL,
    "title" TEXT NOT NULL,
    "note" TEXT,
    "status" "ReminderStatus" NOT NULL DEFAULT 'PENDING',
    "completedAt" TIMESTAMP(3),
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "Reminder_pkey" PRIMARY KEY ("id")
);

-- CreateIndex
CREATE UNIQUE INDEX "User_email_key" ON "User"("email");

-- CreateIndex
CREATE INDEX "User_brokerageId_idx" ON "User"("brokerageId");

-- CreateIndex
CREATE INDEX "Client_brokerageId_status_idx" ON "Client"("brokerageId", "status");

-- CreateIndex
CREATE INDEX "Client_brokerageId_name_idx" ON "Client"("brokerageId", "name");

-- CreateIndex
CREATE INDEX "ClientPhone_clientId_idx" ON "ClientPhone"("clientId");

-- CreateIndex
CREATE UNIQUE INDEX "ClientPhone_brokerageId_e164_key" ON "ClientPhone"("brokerageId", "e164");

-- CreateIndex
CREATE INDEX "Inquiry_brokerageId_transactionType_category_status_idx" ON "Inquiry"("brokerageId", "transactionType", "category", "status");

-- CreateIndex
CREATE INDEX "Inquiry_clientId_idx" ON "Inquiry"("clientId");

-- CreateIndex
CREATE UNIQUE INDEX "InquiryRevision_inquiryId_version_key" ON "InquiryRevision"("inquiryId", "version");

-- CreateIndex
CREATE INDEX "Property_brokerageId_transactionType_category_availability_idx" ON "Property"("brokerageId", "transactionType", "category", "availability");

-- CreateIndex
CREATE INDEX "Reminder_brokerageId_assignedToId_status_dueAt_idx" ON "Reminder"("brokerageId", "assignedToId", "status", "dueAt");

-- AddForeignKey
ALTER TABLE "User" ADD CONSTRAINT "User_brokerageId_fkey" FOREIGN KEY ("brokerageId") REFERENCES "Brokerage"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "Client" ADD CONSTRAINT "Client_brokerageId_fkey" FOREIGN KEY ("brokerageId") REFERENCES "Brokerage"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "Client" ADD CONSTRAINT "Client_assignedToId_fkey" FOREIGN KEY ("assignedToId") REFERENCES "User"("id") ON DELETE SET NULL ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "ClientPhone" ADD CONSTRAINT "ClientPhone_brokerageId_fkey" FOREIGN KEY ("brokerageId") REFERENCES "Brokerage"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "ClientPhone" ADD CONSTRAINT "ClientPhone_clientId_fkey" FOREIGN KEY ("clientId") REFERENCES "Client"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "Inquiry" ADD CONSTRAINT "Inquiry_brokerageId_fkey" FOREIGN KEY ("brokerageId") REFERENCES "Brokerage"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "Inquiry" ADD CONSTRAINT "Inquiry_clientId_fkey" FOREIGN KEY ("clientId") REFERENCES "Client"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "InquiryRevision" ADD CONSTRAINT "InquiryRevision_inquiryId_fkey" FOREIGN KEY ("inquiryId") REFERENCES "Inquiry"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "InquiryRevision" ADD CONSTRAINT "InquiryRevision_changedById_fkey" FOREIGN KEY ("changedById") REFERENCES "User"("id") ON DELETE SET NULL ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "Property" ADD CONSTRAINT "Property_brokerageId_fkey" FOREIGN KEY ("brokerageId") REFERENCES "Brokerage"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "Property" ADD CONSTRAINT "Property_listedById_fkey" FOREIGN KEY ("listedById") REFERENCES "User"("id") ON DELETE SET NULL ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "Reminder" ADD CONSTRAINT "Reminder_brokerageId_fkey" FOREIGN KEY ("brokerageId") REFERENCES "Brokerage"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "Reminder" ADD CONSTRAINT "Reminder_clientId_fkey" FOREIGN KEY ("clientId") REFERENCES "Client"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "Reminder" ADD CONSTRAINT "Reminder_inquiryId_fkey" FOREIGN KEY ("inquiryId") REFERENCES "Inquiry"("id") ON DELETE SET NULL ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "Reminder" ADD CONSTRAINT "Reminder_assignedToId_fkey" FOREIGN KEY ("assignedToId") REFERENCES "User"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "Reminder" ADD CONSTRAINT "Reminder_createdById_fkey" FOREIGN KEY ("createdById") REFERENCES "User"("id") ON DELETE SET NULL ON UPDATE CASCADE;
