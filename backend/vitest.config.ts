import { tmpdir } from "node:os";
import path from "node:path";
import { defineConfig } from "vitest/config";

export default defineConfig({
  test: {
    globalSetup: ["./test/globalSetup.ts"],
    fileParallelism: false,
    testTimeout: 20000,
    env: {
      DATABASE_URL: process.env.TEST_DATABASE_URL ?? "postgresql://bb:bb@localhost:5432/brokerbuddy_test",
      JWT_SECRET: "test-secret-test-secret-test-secret-1234",
      // Tests never reach real portals; listing lookups are faked per test.
      LISTING_LOOKUP: "off",
      STORAGE_DIR: path.join(tmpdir(), `bb-test-storage-${process.pid}`),
      DATA_ENCRYPTION_KEY: "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
    },
  },
});
