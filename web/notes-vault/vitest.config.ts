import path from "node:path";
import { cloudflareTest, readD1Migrations } from "@cloudflare/vitest-pool-workers";
import { defineConfig } from "vitest/config";

// Tests run inside the Workers runtime (workerd) with local D1 and R2 simulators bound as
// DB and FILES from wrangler.toml. The secrets below are obviously fake test values.
export default defineConfig(async () => {
  const migrations = await readD1Migrations(path.join(import.meta.dirname, "migrations"));
  return {
    plugins: [
      cloudflareTest({
        main: "./src/index.ts",
        wrangler: { configPath: "./wrangler.toml" },
        miniflare: {
          bindings: {
            NOTES_LINK_ID: "test-link-id-0000-not-a-real-secret",
            NOTES_PASSWORD: "test-password-not-real",
            SESSION_SECRET: "test-session-secret-not-real-0123456789",
            TEST_MIGRATIONS: migrations,
          },
        },
      }),
    ],
    test: {
      setupFiles: ["./test/apply-migrations.ts"],
    },
  };
});
