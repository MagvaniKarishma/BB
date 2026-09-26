// Development seed: one demo brokerage with sample Mumbai data.
// Login: demo@brokerbuddy.local / demo12345
import bcrypt from "bcryptjs";
import { PrismaClient } from "@prisma/client";

const prisma = new PrismaClient();

async function main() {
  const email = "demo@brokerbuddy.local";
  if (await prisma.user.findUnique({ where: { email } })) {
    console.log("Demo data already present");
    return;
  }
  const brokerage = await prisma.brokerage.create({ data: { name: "Demo Realty, Andheri" } });
  const owner = await prisma.user.create({
    data: { brokerageId: brokerage.id, name: "Demo Owner", email, role: "OWNER", passwordHash: await bcrypt.hash("demo12345", 10) },
  });
  const client = await prisma.client.create({
    data: {
      brokerageId: brokerage.id, name: "Rahul Sharma", primaryPhone: "+919820012345", leadSource: "WALK_IN",
      assignedToId: owner.id,
      phones: { create: { brokerageId: brokerage.id, e164: "+919820012345", label: "primary" } },
    },
  });
  await prisma.inquiry.create({
    data: {
      brokerageId: brokerage.id, clientId: client.id, transactionType: "RENT", category: "BHK_2",
      budgetMax: 70000n, locations: ["Andheri", "Jogeshwari"], furnishing: ["SEMI_FURNISHED", "FULLY_FURNISHED"],
      minParking: 1, mandatory: ["BUDGET", "LOCATION"],
      revisions: { create: { version: 1, source: "MANUAL", changedById: owner.id, changes: {}, snapshot: {} } },
    },
  });
  const listings = [
    { title: "2BHK near Lokhandwala", locality: "Andheri West", price: 65000n, furnishing: "SEMI_FURNISHED", parkingSpots: 1, floor: 7 },
    { title: "2BHK Chakala", locality: "Andheri East", price: 58000n, furnishing: "FULLY_FURNISHED", parkingSpots: 0, floor: 3 },
    { title: "2BHK Oshiwara", locality: "Jogeshwari West", price: 72000n, furnishing: "FULLY_FURNISHED", parkingSpots: 1, floor: 12 },
  ] as const;
  for (const l of listings) {
    await prisma.property.create({
      data: { ...l, brokerageId: brokerage.id, transactionType: "RENT", category: "BHK_2", possession: "READY_TO_MOVE", listedById: owner.id },
    });
  }
  await prisma.reminder.create({
    data: {
      brokerageId: brokerage.id, clientId: client.id, assignedToId: owner.id, createdById: owner.id,
      title: "Share Lokhandwala options with Rahul", dueAt: new Date(Date.now() + 3600_000),
    },
  });
  console.log("Seeded demo brokerage. Login: demo@brokerbuddy.local / demo12345");
}

main().finally(() => prisma.$disconnect());
