-- CreateEnum
CREATE TYPE "CallMode" AS ENUM ('DIRECT', 'AI_RECEPTIONIST', 'SMART_ASSISTANT');

-- CreateEnum
CREATE TYPE "AssistantVoice" AS ENUM ('RECORDED_STANDARD', 'RECORDED_NATURAL', 'CUSTOM_AI_VOICE');

-- CreateEnum
CREATE TYPE "GreetingLanguage" AS ENUM ('HINGLISH', 'HINDI', 'ENGLISH', 'MARATHI');

-- CreateEnum
CREATE TYPE "UnclearBehavior" AS ENUM ('TAKE_CALLBACK', 'TRANSFER');

-- CreateEnum
CREATE TYPE "HumanTransfer" AS ENUM ('ON_REQUEST', 'NEVER');

-- CreateEnum
CREATE TYPE "CallSessionStatus" AS ENUM ('IN_PROGRESS', 'COMPLETED', 'INTERRUPTED', 'TRANSFERRED', 'FAILED');

-- CreateEnum
CREATE TYPE "CallTurnRole" AS ENUM ('CALLER', 'ASSISTANT');

-- AlterEnum
ALTER TYPE "LeadSource" ADD VALUE 'AI_CALL_ASSISTANT';

-- AlterEnum
ALTER TYPE "RequirementSource" ADD VALUE 'AI_CALL';

-- CreateTable
CREATE TABLE "CallAssistantSettings" (
    "brokerageId" TEXT NOT NULL,
    "enabled" BOOLEAN NOT NULL DEFAULT false,
    "mode" "CallMode" NOT NULL DEFAULT 'DIRECT',
    "voice" "AssistantVoice" NOT NULL DEFAULT 'RECORDED_STANDARD',
    "customGreetingEnabled" BOOLEAN NOT NULL DEFAULT false,
    "defaultLanguage" "GreetingLanguage" NOT NULL DEFAULT 'HINGLISH',
    "businessHours" JSONB NOT NULL,
    "callbackReminder" BOOLEAN NOT NULL DEFAULT true,
    "callbackDelayMinutes" INTEGER NOT NULL DEFAULT 15,
    "callbackAssigneeId" TEXT,
    "unclearBehavior" "UnclearBehavior" NOT NULL DEFAULT 'TAKE_CALLBACK',
    "maxUnclearRetries" INTEGER NOT NULL DEFAULT 2,
    "humanTransfer" "HumanTransfer" NOT NULL DEFAULT 'ON_REQUEST',
    "transferNumber" TEXT,
    "businessNumbers" TEXT[],
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "CallAssistantSettings_pkey" PRIMARY KEY ("brokerageId")
);

-- CreateTable
CREATE TABLE "CallGreeting" (
    "id" TEXT NOT NULL,
    "brokerageId" TEXT NOT NULL,
    "language" "GreetingLanguage" NOT NULL,
    "script" TEXT NOT NULL,
    "storageKey" TEXT,
    "mimeType" TEXT,
    "size" INTEGER,
    "durationMs" INTEGER,
    "updatedById" TEXT,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "CallGreeting_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "CallSession" (
    "id" TEXT NOT NULL,
    "brokerageId" TEXT NOT NULL,
    "provider" TEXT NOT NULL,
    "providerCallId" TEXT NOT NULL,
    "fromNumber" TEXT,
    "toNumber" TEXT,
    "status" "CallSessionStatus" NOT NULL DEFAULT 'IN_PROGRESS',
    "language" "GreetingLanguage",
    "state" JSONB NOT NULL,
    "seenEvents" TEXT[],
    "callbackRequested" BOOLEAN NOT NULL DEFAULT false,
    "humanRequested" BOOLEAN NOT NULL DEFAULT false,
    "summary" TEXT,
    "clientId" TEXT,
    "inquiryId" TEXT,
    "savedAt" TIMESTAMP(3),
    "startedAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "endedAt" TIMESTAMP(3),
    "durationSec" INTEGER,

    CONSTRAINT "CallSession_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "CallTurn" (
    "id" TEXT NOT NULL,
    "sessionId" TEXT NOT NULL,
    "seq" INTEGER NOT NULL,
    "role" "CallTurnRole" NOT NULL,
    "text" TEXT NOT NULL,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "CallTurn_pkey" PRIMARY KEY ("id")
);

-- CreateIndex
CREATE UNIQUE INDEX "CallGreeting_storageKey_key" ON "CallGreeting"("storageKey");

-- CreateIndex
CREATE UNIQUE INDEX "CallGreeting_brokerageId_language_key" ON "CallGreeting"("brokerageId", "language");

-- CreateIndex
CREATE INDEX "CallSession_brokerageId_startedAt_idx" ON "CallSession"("brokerageId", "startedAt");

-- CreateIndex
CREATE UNIQUE INDEX "CallSession_provider_providerCallId_key" ON "CallSession"("provider", "providerCallId");

-- CreateIndex
CREATE UNIQUE INDEX "CallTurn_sessionId_seq_key" ON "CallTurn"("sessionId", "seq");

-- AddForeignKey
ALTER TABLE "CallAssistantSettings" ADD CONSTRAINT "CallAssistantSettings_brokerageId_fkey" FOREIGN KEY ("brokerageId") REFERENCES "Brokerage"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "CallGreeting" ADD CONSTRAINT "CallGreeting_brokerageId_fkey" FOREIGN KEY ("brokerageId") REFERENCES "Brokerage"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "CallSession" ADD CONSTRAINT "CallSession_brokerageId_fkey" FOREIGN KEY ("brokerageId") REFERENCES "Brokerage"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "CallTurn" ADD CONSTRAINT "CallTurn_sessionId_fkey" FOREIGN KEY ("sessionId") REFERENCES "CallSession"("id") ON DELETE CASCADE ON UPDATE CASCADE;
