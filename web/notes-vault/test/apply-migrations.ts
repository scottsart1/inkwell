import { applyD1Migrations } from "cloudflare:test";
import { env } from "cloudflare:workers";

// Setup files run before each test file; applyD1Migrations() only applies migrations that
// have not been applied yet, so this is idempotent.
await applyD1Migrations(env.DB, env.TEST_MIGRATIONS);
