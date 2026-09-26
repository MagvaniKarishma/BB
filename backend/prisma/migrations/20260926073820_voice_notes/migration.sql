-- CreateEnum
CREATE TYPE "VoiceLanguage" AS ENUM ('AUTO', 'HINDI', 'HINGLISH', 'MARATHI', 'ENGLISH');

-- CreateEnum
CREATE TYPE "VoiceNoteStatus" AS ENUM ('NEEDS_TRANSCRIPT', 'READY', 'APPLIED', 'DISCARDED');

-- AlterTable
ALTER TABLE "InquiryRevision" ADD COLUMN     "voiceNoteId" TEXT;

-- CreateTable
CREATE TABLE "VoiceNote" (
    "id" TEXT NOT NULL,
    "brokerageId" TEXT NOT NULL,
    "clientId" TEXT NOT NULL,
    "inquiryId" TEXT,
    "createdById" TEXT,
    "language" "VoiceLanguage" NOT NULL DEFAULT 'AUTO',
    "status" "VoiceNoteStatus" NOT NULL,
    "audio" BYTEA,
    "audioMimeType" TEXT,
    "audioSize" INTEGER,
    "durationMs" INTEGER,
    "originalTranscript" TEXT,
    "transcript" TEXT,
    "transcriptSource" TEXT,
    "extraction" JSONB,
    "extractor" TEXT,
    "error" TEXT,
    "appliedVersion" INTEGER,
    "appliedAt" TIMESTAMP(3),
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "VoiceNote_pkey" PRIMARY KEY ("id")
);

-- CreateIndex
CREATE INDEX "VoiceNote_brokerageId_clientId_createdAt_idx" ON "VoiceNote"("brokerageId", "clientId", "createdAt");

-- AddForeignKey
ALTER TABLE "InquiryRevision" ADD CONSTRAINT "InquiryRevision_voiceNoteId_fkey" FOREIGN KEY ("voiceNoteId") REFERENCES "VoiceNote"("id") ON DELETE SET NULL ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "VoiceNote" ADD CONSTRAINT "VoiceNote_brokerageId_fkey" FOREIGN KEY ("brokerageId") REFERENCES "Brokerage"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "VoiceNote" ADD CONSTRAINT "VoiceNote_clientId_fkey" FOREIGN KEY ("clientId") REFERENCES "Client"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "VoiceNote" ADD CONSTRAINT "VoiceNote_inquiryId_fkey" FOREIGN KEY ("inquiryId") REFERENCES "Inquiry"("id") ON DELETE SET NULL ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "VoiceNote" ADD CONSTRAINT "VoiceNote_createdById_fkey" FOREIGN KEY ("createdById") REFERENCES "User"("id") ON DELETE SET NULL ON UPDATE CASCADE;
