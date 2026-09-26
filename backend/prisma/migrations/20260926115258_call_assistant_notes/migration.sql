-- AlterEnum
ALTER TYPE "NoteSource" ADD VALUE 'AI_CALL';

-- AlterTable
ALTER TABLE "CallSession" ADD COLUMN     "isTest" BOOLEAN NOT NULL DEFAULT false;
