import { env } from "cloudflare:workers";
import { describe, expect, it } from "vitest";
import {
  LOCKOUT_WINDOW_MS,
  MAX_FAILURES,
  TOKEN_TTL_MS,
  clientBucket,
  getSecrets,
  issueToken,
  passwordVersion,
  verifyToken,
  type Secrets,
} from "../src/auth";
import { base64UrlEncode, hmacKey, utf8 } from "../src/crypto";
import { BASE, LINK_ID, ORIGIN, PASSWORD, bearer, call, login, loginRequest, uid, uniqueIp } from "./helpers";

const secrets = getSecrets(env) as Secrets;

describe("login", () => {
  it("returns a token, expiry and a strict session cookie", async () => {
    const before = Date.now();
    const res = await loginRequest(PASSWORD, uniqueIp());
    expect(res.status).toBe(200);
    const body = (await res.json()) as { token: string; expiresAt: number };
    expect(typeof body.token).toBe("string");
    expect(body.token).toMatch(/^[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$/);
    expect(body.expiresAt).toBeGreaterThanOrEqual(before + TOKEN_TTL_MS);
    expect(body.expiresAt).toBeLessThanOrEqual(Date.now() + TOKEN_TTL_MS);

    const cookie = res.headers.get("Set-Cookie") as string;
    expect(cookie).toContain(`nv_session=${body.token}`);
    expect(cookie).toContain("HttpOnly");
    expect(cookie).toContain("Secure");
    expect(cookie).toContain("SameSite=Strict");
    expect(cookie).toContain(`Path=/notes/${LINK_ID}`);
    expect(cookie).toContain("Max-Age=2592000");
  });

  it("token payload is {exp, pv} with pv = first 8 bytes of SHA-256(password + secret)", async () => {
    const { token } = await login();
    const payload = JSON.parse(atob((token.split(".")[0] as string).replace(/-/g, "+").replace(/_/g, "/")));
    expect(Object.keys(payload).sort()).toEqual(["exp", "pv"]);
    const digest = new Uint8Array(await crypto.subtle.digest("SHA-256", utf8(PASSWORD + env.SESSION_SECRET)));
    const hex = [...digest.slice(0, 8)].map((b) => b.toString(16).padStart(2, "0")).join("");
    expect(payload.pv).toBe(hex);
    expect(await passwordVersion(secrets)).toBe(hex);
  });

  it("rejects a wrong password with 401 invalid_password", async () => {
    const res = await loginRequest("not the password", uniqueIp());
    expect(res.status).toBe(401);
    expect(await res.json()).toEqual({ error: "invalid_password" });
    expect(res.headers.get("Set-Cookie")).toBeNull();
  });

  it("rejects a malformed body with 400", async () => {
    const ip = uniqueIp();
    for (const body of ["{", "[]", JSON.stringify({}), JSON.stringify({ password: 42 }), JSON.stringify({ password: "" })]) {
      const res = await call("/api/session", { method: "POST", headers: { "CF-Connecting-IP": ip }, body });
      expect(res.status).toBe(400);
    }
  });

  it("refuses a cross-site login post", async () => {
    const res = await call("/api/session", {
      method: "POST",
      headers: { Origin: "https://evil.example", "CF-Connecting-IP": uniqueIp() },
      body: JSON.stringify({ password: PASSWORD }),
    });
    expect(res.status).toBe(403);
  });

  it("is not configured without the password/secret", async () => {
    const res = await loginRequest(PASSWORD, uniqueIp(), { ...env, NOTES_PASSWORD: "" });
    expect(res.status).toBe(503);
  });
});

describe("lockout", () => {
  it(`locks an IP after ${MAX_FAILURES} failures within 15 minutes, even for the right password`, async () => {
    const ip = uniqueIp();
    for (let i = 0; i < MAX_FAILURES; i++) {
      expect((await loginRequest(`wrong-${i}`, ip)).status).toBe(401);
    }
    const locked = await loginRequest(PASSWORD, ip);
    expect(locked.status).toBe(429);
    const body = (await locked.json()) as { error: string; retryAfterSeconds: number };
    expect(body.error).toBe("locked");
    expect(body.retryAfterSeconds).toBeGreaterThan(0);
    expect(body.retryAfterSeconds).toBeLessThanOrEqual(LOCKOUT_WINDOW_MS / 1000);
    expect(locked.headers.get("Retry-After")).toBe(String(body.retryAfterSeconds));

    // Other addresses are unaffected.
    expect((await loginRequest(PASSWORD, uniqueIp())).status).toBe(200);
  });

  it("a successful login resets the counter", async () => {
    const ip = uniqueIp();
    for (let i = 0; i < MAX_FAILURES - 1; i++) expect((await loginRequest("wrong", ip)).status).toBe(401);
    expect((await loginRequest(PASSWORD, ip)).status).toBe(200);
    for (let i = 0; i < MAX_FAILURES; i++) expect((await loginRequest("wrong", ip)).status).toBe(401);
    expect((await loginRequest("wrong", ip)).status).toBe(429);
  });

  it("the lock ends when the 15-minute window has passed", async () => {
    const ip = uniqueIp();
    for (let i = 0; i < MAX_FAILURES; i++) await loginRequest("wrong", ip);
    expect((await loginRequest(PASSWORD, ip)).status).toBe(429);
    await env.DB.prepare("UPDATE login_attempts SET window_start = ?1 WHERE ip = ?2")
      .bind(Date.now() - LOCKOUT_WINDOW_MS - 1000, ip)
      .run();
    expect((await loginRequest(PASSWORD, ip)).status).toBe(200);
    const row = await env.DB.prepare("SELECT count FROM login_attempts WHERE ip = ?1").bind(ip).first();
    expect(row).toBeNull();
  });

  it("parallel guesses cannot exceed the limit", async () => {
    const ip = uniqueIp();
    const results = await Promise.all(Array.from({ length: 20 }, () => loginRequest("wrong", ip)));
    const statuses = results.map((r) => r.status);
    expect(statuses.filter((s) => s === 401).length).toBe(MAX_FAILURES);
    expect(statuses.filter((s) => s === 429).length).toBe(20 - MAX_FAILURES);
  });

  it("groups IPv6 addresses by /64", () => {
    const req = (ip: string) => new Request("https://x.test/", { headers: { "CF-Connecting-IP": ip } });
    expect(clientBucket(req("2001:db8:1:2:aaaa::1"))).toBe("2001:db8:1:2::/64");
    expect(clientBucket(req("2001:0db8:0001:0002:ffff:0:0:9"))).toBe("2001:db8:1:2::/64");
    expect(clientBucket(req("::ffff:192.0.2.7"))).toBe("192.0.2.7");
    expect(clientBucket(req("192.0.2.7"))).toBe("192.0.2.7");
    expect(clientBucket(new Request("https://x.test/"))).toBe("unknown");
  });
});

describe("tokens", () => {
  it("bearer token and cookie both authenticate", async () => {
    const { token, cookie } = await login();
    expect((await call("/api/docs", { headers: bearer(token) })).status).toBe(200);
    expect((await call("/api/docs", { headers: { Cookie: `other=1; ${cookie}` } })).status).toBe(200);
    expect((await call("/api/docs", { headers: { authorization: `bearer ${token}` } })).status).toBe(200);
  });

  it("rejects missing, garbage and tampered tokens", async () => {
    const { token } = await login();
    const [payload, sig] = token.split(".") as [string, string];
    const forgedPayload = base64UrlEncode(utf8(JSON.stringify({ exp: Date.now() + 1e12, pv: "0000000000000000" })));
    const bad = [
      "",
      "garbage",
      `${payload}.`,
      `${payload}.${sig}x`,
      `${forgedPayload}.${sig}`,
      `${payload}.${sig}.${sig}`,
      `${payload}.${sig.slice(0, -2)}AA`,
    ];
    for (const t of bad) {
      const res = await call("/api/docs", { headers: bearer(t) });
      expect(res.status, t).toBe(401);
      expect(await res.json()).toEqual({ error: "unauthorized" });
    }
    expect((await call("/api/docs", { headers: { Cookie: "nv_session=garbage" } })).status).toBe(401);
  });

  it("an invalid Authorization header does not fall back to a valid cookie", async () => {
    const { cookie } = await login();
    const res = await call("/api/docs", { headers: { Cookie: cookie, Authorization: "Bearer nope" } });
    expect(res.status).toBe(401);
  });

  it("expired tokens are rejected", async () => {
    const old = await issueToken(secrets, Date.now() - TOKEN_TTL_MS - 1000);
    expect(await verifyToken(old.token, secrets)).toBe(false);
    expect((await call("/api/ping", { headers: bearer(old.token) })).status).toBe(401);
    expect((await call("/api/ping", { headers: { Cookie: `nv_session=${old.token}` } })).status).toBe(401);
    const fresh = await issueToken(secrets, Date.now() - TOKEN_TTL_MS + 60_000);
    expect((await call("/api/ping", { headers: bearer(fresh.token) })).status).toBe(200);
  });

  it("a correctly signed token with a past exp is rejected", async () => {
    const payload = base64UrlEncode(
      utf8(JSON.stringify({ exp: Date.now() - 1, pv: await passwordVersion(secrets) })),
    );
    const sig = new Uint8Array(
      await crypto.subtle.sign("HMAC", await hmacKey(env.SESSION_SECRET as string), utf8(payload)),
    );
    expect((await call("/api/ping", { headers: bearer(`${payload}.${base64UrlEncode(sig)}`) })).status).toBe(401);
  });

  it("changing the password invalidates every existing token", async () => {
    const { token, cookie } = await login();
    const changed = { ...env, NOTES_PASSWORD: "a-new-test-password" };
    expect((await call("/api/ping", { headers: bearer(token), env: changed })).status).toBe(401);
    expect((await call("/api/ping", { headers: { Cookie: cookie }, env: changed })).status).toBe(401);
    const fresh = await login(changed);
    expect((await call("/api/ping", { headers: bearer(fresh.token), env: changed })).status).toBe(200);
    // And the old password no longer logs in.
    expect((await loginRequest(PASSWORD, uniqueIp(), changed)).status).toBe(401);
  });

  it("changing SESSION_SECRET invalidates tokens too", async () => {
    const { token } = await login();
    const rotated = { ...env, SESSION_SECRET: "another-test-session-secret" };
    expect((await call("/api/ping", { headers: bearer(token), env: rotated })).status).toBe(401);
  });

  it("logout clears the cookie", async () => {
    const res = await call("/api/logout", { method: "POST", headers: { Origin: ORIGIN } });
    expect(res.status).toBe(200);
    expect(await res.json()).toEqual({ ok: true });
    const cookie = res.headers.get("Set-Cookie") as string;
    expect(cookie).toMatch(/^nv_session=;/);
    expect(cookie).toContain("Max-Age=0");
    expect(cookie).toContain(`Path=/notes/${LINK_ID}`);
    expect(cookie).toContain("HttpOnly");
  });
});

describe("CSRF origin check for cookie-authenticated changes", () => {
  const doc = {
    title: "20261016_CSRF",
    kind: "TYPED",
    createdAt: 1,
    modifiedAt: 2,
  };

  it("cookie PUT without Origin is refused", async () => {
    const { cookie } = await login();
    const res = await call(`/api/docs/${uid()}`, { method: "PUT", headers: { Cookie: cookie }, body: JSON.stringify(doc) });
    expect(res.status).toBe(403);
    expect(await res.json()).toEqual({ error: "bad_origin" });
  });

  it("cookie PUT/DELETE with a foreign Origin is refused", async () => {
    const { cookie } = await login();
    const id = uid();
    for (const origin of ["https://evil.example", "http://vault.example.test", "null", `${ORIGIN}.evil.example`]) {
      const res = await call(`/api/docs/${id}`, {
        method: "PUT",
        headers: { Cookie: cookie, Origin: origin },
        body: JSON.stringify(doc),
      });
      expect(res.status, origin).toBe(403);
    }
    const del = await call(`/api/docs/${id}`, { method: "DELETE", headers: { Cookie: cookie, Origin: "https://evil.example" } });
    expect(del.status).toBe(403);
  });

  it("cookie PUT with the matching Origin works", async () => {
    const { cookie } = await login();
    const res = await call(`/api/docs/${uid()}`, {
      method: "PUT",
      headers: { Cookie: cookie, Origin: ORIGIN },
      body: JSON.stringify(doc),
    });
    expect(res.status).toBe(200);
  });

  it("cookie GET needs no Origin", async () => {
    const { cookie } = await login();
    expect((await call("/api/docs", { headers: { Cookie: cookie } })).status).toBe(200);
  });

  it("bearer requests are exempt", async () => {
    const { token } = await login();
    const res = await call(`/api/docs/${uid()}`, { method: "PUT", headers: bearer(token), body: JSON.stringify(doc) });
    expect(res.status).toBe(200);
    const foreign = await call(`/api/docs/${uid()}`, {
      method: "PUT",
      headers: { ...bearer(token), Origin: "https://evil.example" },
      body: JSON.stringify(doc),
    });
    expect(foreign.status).toBe(200);
  });

  it("the base URL used in tests is under the link", () => {
    expect(BASE.endsWith(`/notes/${LINK_ID}`)).toBe(true);
  });
});
