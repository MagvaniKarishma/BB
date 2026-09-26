-- CreateEnum
CREATE TYPE "MessageDirection" AS ENUM ('INBOUND', 'OUTBOUND');

-- CreateEnum
CREATE TYPE "MessageChannel" AS ENUM ('API', 'MANUAL');

-- CreateEnum
CREATE TYPE "MessageStatus" AS ENUM ('RECEIVED', 'SENT', 'DELIVERED', 'READ', 'FAILED');

-- CreateEnum
CREATE TYPE "DraftReview" AS ENUM ('NONE', 'PENDING', 'APPLIED', 'DISMISSED');

-- AlterTable
ALTER TABLE "InquiryRevision" ADD COLUMN     "whatsappMessageId" TEXT;

-- CreateTable
CREATE TABLE "WhatsAppAccount" (
    "id" TEXT NOT NULL,
    "brokerageId" TEXT NOT NULL,
    "phoneNumberId" TEXT NOT NULL,
    "wabaId" TEXT,
    "displayPhone" TEXT,
    "accessTokenEnc" TEXT NOT NULL,
    "appSecretEnc" TEXT NOT NULL,
    "webhookKey" TEXT NOT NULL,
    "verifyToken" TEXT NOT NULL,
    "verifiedAt" TIMESTAMP(3),
    "lastEventAt" TIMESTAMP(3),
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "WhatsAppAccount_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "WhatsAppContact" (
    "id" TEXT NOT NULL,
    "brokerageId" TEXT NOT NULL,
    "accountId" TEXT,
    "waId" TEXT NOT NULL,
    "profileName" TEXT,
    "clientId" TEXT,
    "lastInboundAt" TIMESTAMP(3),
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "WhatsAppContact_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "WhatsAppMessage" (
    "id" TEXT NOT NULL,
    "brokerageId" TEXT NOT NULL,
    "accountId" TEXT,
    "contactId" TEXT,
    "clientId" TEXT,
    "inquiryId" TEXT,
    "channel" "MessageChannel" NOT NULL,
    "direction" "MessageDirection" NOT NULL,
    "externalId" TEXT NOT NULL,
    "type" TEXT NOT NULL,
    "text" TEXT,
    "mediaId" TEXT,
    "mediaMimeType" TEXT,
    "senderName" TEXT,
    "sentAt" TIMESTAMP(3) NOT NULL,
    "status" "MessageStatus" NOT NULL,
    "errorReason" TEXT,
    "leadPhone" TEXT,
    "leadName" TEXT,
    "portal" TEXT,
    "portalLead" JSONB,
    "extraction" JSONB,
    "review" "DraftReview" NOT NULL DEFAULT 'NONE',
    "processedAt" TIMESTAMP(3),
    "processError" TEXT,
    "raw" JSONB,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "WhatsAppMessage_pkey" PRIMARY KEY ("id")
);

-- CreateIndex
CREATE UNIQUE INDEX "WhatsAppAccount_phoneNumberId_key" ON "WhatsAppAccount"("phoneNumberId");

-- CreateIndex
CREATE UNIQUE INDEX "WhatsAppAccount_webhookKey_key" ON "WhatsAppAccount"("webhookKey");

-- CreateIndex
CREATE UNIQUE INDEX "WhatsAppContact_brokerageId_waId_key" ON "WhatsAppContact"("brokerageId", "waId");

-- CreateIndex
CREATE INDEX "WhatsAppMessage_brokerageId_clientId_sentAt_idx" ON "WhatsAppMessage"("brokerageId", "clientId", "sentAt");

-- CreateIndex
CREATE INDEX "WhatsAppMessage_brokerageId_review_idx" ON "WhatsAppMessage"("brokerageId", "review");

-- CreateIndex
CREATE INDEX "WhatsAppMessage_processedAt_idx" ON "WhatsAppMessage"("processedAt");

-- CreateIndex
CREATE UNIQUE INDEX "WhatsAppMessage_brokerageId_externalId_key" ON "WhatsAppMessage"("brokerageId", "externalId");

-- AddForeignKey
ALTER TABLE "InquiryRevision" ADD CONSTRAINT "InquiryRevision_whatsappMessageId_fkey" FOREIGN KEY ("whatsappMessageId") REFERENCES "WhatsAppMessage"("id") ON DELETE SET NULL ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "WhatsAppAccount" ADD CONSTRAINT "WhatsAppAccount_brokerageId_fkey" FOREIGN KEY ("brokerageId") REFERENCES "Brokerage"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "WhatsAppContact" ADD CONSTRAINT "WhatsAppContact_brokerageId_fkey" FOREIGN KEY ("brokerageId") REFERENCES "Brokerage"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "WhatsAppContact" ADD CONSTRAINT "WhatsAppContact_accountId_fkey" FOREIGN KEY ("accountId") REFERENCES "WhatsAppAccount"("id") ON DELETE SET NULL ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "WhatsAppContact" ADD CONSTRAINT "WhatsAppContact_clientId_fkey" FOREIGN KEY ("clientId") REFERENCES "Client"("id") ON DELETE SET NULL ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "WhatsAppMessage" ADD CONSTRAINT "WhatsAppMessage_brokerageId_fkey" FOREIGN KEY ("brokerageId") REFERENCES "Brokerage"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "WhatsAppMessage" ADD CONSTRAINT "WhatsAppMessage_accountId_fkey" FOREIGN KEY ("accountId") REFERENCES "WhatsAppAccount"("id") ON DELETE SET NULL ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "WhatsAppMessage" ADD CONSTRAINT "WhatsAppMessage_contactId_fkey" FOREIGN KEY ("contactId") REFERENCES "WhatsAppContact"("id") ON DELETE SET NULL ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "WhatsAppMessage" ADD CONSTRAINT "WhatsAppMessage_clientId_fkey" FOREIGN KEY ("clientId") REFERENCES "Client"("id") ON DELETE SET NULL ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "WhatsAppMessage" ADD CONSTRAINT "WhatsAppMessage_inquiryId_fkey" FOREIGN KEY ("inquiryId") REFERENCES "Inquiry"("id") ON DELETE SET NULL ON UPDATE CASCADE;
