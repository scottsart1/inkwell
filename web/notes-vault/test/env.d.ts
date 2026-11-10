import type { D1Migration } from "cloudflare:test";
import type { Env as VaultEnv } from "../src/env";

declare global {
  namespace Cloudflare {
    interface Env extends VaultEnv {
      TEST_MIGRATIONS: D1Migration[];
    }
  }
}

export {};
