-- CreateEnum
CREATE TYPE "FloorBand" AS ENUM ('LOWER', 'MIDDLE', 'HIGHER');

-- CreateEnum
CREATE TYPE "PropertyType" AS ENUM ('APARTMENT', 'INDEPENDENT_HOUSE', 'VILLA', 'PENTHOUSE', 'BUILDER_FLOOR', 'COMMERCIAL', 'PLOT', 'OTHER');

-- AlterEnum
ALTER TYPE "RequirementField" ADD VALUE 'PROPERTY_TYPE';

-- AlterTable
ALTER TABLE "Inquiry" ADD COLUMN     "floorPreference" "FloorBand"[] DEFAULT ARRAY[]::"FloorBand"[],
ADD COLUMN     "propertyTypes" "PropertyType"[] DEFAULT ARRAY[]::"PropertyType"[];

-- AlterTable
ALTER TABLE "Property" ADD COLUMN     "amenities" TEXT[] DEFAULT ARRAY[]::TEXT[],
ADD COLUMN     "builtUpAreaSqft" INTEGER,
ADD COLUMN     "propertyType" "PropertyType";

