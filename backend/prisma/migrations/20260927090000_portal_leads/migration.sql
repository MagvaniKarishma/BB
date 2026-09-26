-- CreateEnum
CREATE TYPE "ReminderKind" AS ENUM ('FOLLOW_UP', 'CALLBACK');

-- CreateEnum
CREATE TYPE "PortalListingStatus" AS ENUM ('ACTIVE', 'INACTIVE');

-- CreateEnum
CREATE TYPE "PortalLeadStatus" AS ENUM ('NEW', 'CONTACTED', 'FOLLOW_UP', 'CONVERTED', 'NOT_INTERESTED', 'CLOSED');

-- CreateEnum
CREATE TYPE "PortalLeadChannel" AS ENUM ('WHATSAPP', 'CSV', 'EMAIL', 'WEBHOOK');

-- AlterTable
ALTER TABLE "Reminder" ADD COLUMN     "kind" "ReminderKind" NOT NULL DEFAULT 'FOLLOW_UP';

-- CreateTable
CREATE TABLE "PortalListing" (
    "id" TEXT NOT NULL,
    "brokerageId" TEXT NOT NULL,
    "portal" "LeadSource" NOT NULL,
    "externalId" TEXT,
    "url" TEXT,
    "title" TEXT,
    "transactionType" "TransactionType",
    "category" "PropertyCategory",
    "locality" TEXT,
    "price" BIGINT,
    "carpetAreaSqft" INTEGER,
    "photoUrl" TEXT,
    "propertyId" TEXT,
    "propertyMatch" TEXT,
    "fingerprint" TEXT,
    "status" "PortalListingStatus" NOT NULL DEFAULT 'ACTIVE',
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "PortalListing_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "PortalLead" (
    "id" TEXT NOT NULL,
    "brokerageId" TEXT NOT NULL,
    "portal" "LeadSource" NOT NULL,
    "listingId" TEXT,
    "clientId" TEXT,
    "externalLeadId" TEXT,
    "enquiredAt" TIMESTAMP(3) NOT NULL,
    "name" TEXT,
    "phone" TEXT,
    "email" TEXT,
    "message" TEXT,
    "budgetMin" BIGINT,
    "budgetMax" BIGINT,
    "requirement" TEXT,
    "status" "PortalLeadStatus" NOT NULL DEFAULT 'NEW',
    "channel" "PortalLeadChannel" NOT NULL,
    "sourceRef" TEXT,
    "raw" JSONB,
    "dedupeKey" TEXT NOT NULL,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "PortalLead_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "PortalIntegration" (
    "id" TEXT NOT NULL,
    "brokerageId" TEXT NOT NULL,
    "portal" "LeadSource" NOT NULL,
    "keyHash" TEXT NOT NULL,
    "keyPrefix" TEXT NOT NULL,
    "lastReceivedAt" TIMESTAMP(3),
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "PortalIntegration_pkey" PRIMARY KEY ("id")
);

-- CreateIndex
CREATE INDEX "PortalListing_brokerageId_portal_idx" ON "PortalListing"("brokerageId", "portal");

-- CreateIndex
CREATE UNIQUE INDEX "PortalListing_brokerageId_portal_externalId_key" ON "PortalListing"("brokerageId", "portal", "externalId");

-- CreateIndex
CREATE UNIQUE INDEX "PortalListing_brokerageId_portal_url_key" ON "PortalListing"("brokerageId", "portal", "url");

-- CreateIndex
CREATE UNIQUE INDEX "PortalListing_brokerageId_portal_fingerprint_key" ON "PortalListing"("brokerageId", "portal", "fingerprint");

-- CreateIndex
CREATE INDEX "PortalLead_brokerageId_portal_enquiredAt_idx" ON "PortalLead"("brokerageId", "portal", "enquiredAt");

-- CreateIndex
CREATE INDEX "PortalLead_listingId_enquiredAt_idx" ON "PortalLead"("listingId", "enquiredAt");

-- CreateIndex
CREATE INDEX "PortalLead_clientId_idx" ON "PortalLead"("clientId");

-- CreateIndex
CREATE UNIQUE INDEX "PortalLead_brokerageId_portal_dedupeKey_key" ON "PortalLead"("brokerageId", "portal", "dedupeKey");

-- CreateIndex
CREATE UNIQUE INDEX "PortalIntegration_keyHash_key" ON "PortalIntegration"("keyHash");

-- CreateIndex
CREATE UNIQUE INDEX "PortalIntegration_brokerageId_portal_key" ON "PortalIntegration"("brokerageId", "portal");

-- AddForeignKey
ALTER TABLE "PortalListing" ADD CONSTRAINT "PortalListing_brokerageId_fkey" FOREIGN KEY ("brokerageId") REFERENCES "Brokerage"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "PortalListing" ADD CONSTRAINT "PortalListing_propertyId_fkey" FOREIGN KEY ("propertyId") REFERENCES "Property"("id") ON DELETE SET NULL ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "PortalLead" ADD CONSTRAINT "PortalLead_brokerageId_fkey" FOREIGN KEY ("brokerageId") REFERENCES "Brokerage"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "PortalLead" ADD CONSTRAINT "PortalLead_listingId_fkey" FOREIGN KEY ("listingId") REFERENCES "PortalListing"("id") ON DELETE SET NULL ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "PortalLead" ADD CONSTRAINT "PortalLead_clientId_fkey" FOREIGN KEY ("clientId") REFERENCES "Client"("id") ON DELETE SET NULL ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "PortalIntegration" ADD CONSTRAINT "PortalIntegration_brokerageId_fkey" FOREIGN KEY ("brokerageId") REFERENCES "Brokerage"("id") ON DELETE CASCADE ON UPDATE CASCADE;


-- Callbacks promised by the AI call assistant (created before reminders had a kind).
UPDATE "Reminder" SET "kind" = 'CALLBACK' WHERE "title" LIKE 'Call back %';
