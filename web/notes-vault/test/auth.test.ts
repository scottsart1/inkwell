import { describe, expect, it } from "vitest";
import { OPEN_SESSION_TTL_MS, OPEN_TOKEN } from "../src/auth";
import { BASE, LINK_ID, ORIGIN, bearer, call, docBody, sessionRequest, uid } from "./helpers";

describe("no password", () => {
  it("every API call works without any credentials, on both entries", async () => {
    expect((await call("/api/ping")).status).toBe(200);
    expect((await call("/api/docs")).status).toBe(200);
    expect((await call(`${ORIGIN}/notes/api/ping`)).status).toBe(200);
    const id = uid();
    const put = await call(`/api/docs/${id}`, { method: "PUT", body: JSON.stringify(docBody()) });
    expect(put.status).toBe(200);
    const got = await call(`${ORIGIN}/notes/api/docs/${id}`);
    expect(got.status).toBe(200);
    expect(((await got.json()) as { title: string }).title).toBe(docBody().title);
    expect((await call(`/api/docs/${id}`, { method: "DELETE" })).status).toBe(200);
  });

  it("any Authorization header or old session cookie is ignored", async () => {
    for (const headers of [bearer("garbage"), bearer("old.token-from-an-earlier-version"), { Authorization: "Basic abc" }, { Cookie: "nv_session=old" }]) {
      expect((await call("/api/ping", { headers })).status).toBe(200);
    }
  });
});

describe("api/session, kept for app versions that still sign in", () => {
  it("accepts any password, or none, and returns a token with a far expiry", async () => {
    const before = Date.now();
    for (const res of [
      await sessionRequest("whatever was saved in the app"),
      await sessionRequest(""),
      await call("/api/session", { method: "POST" }),
      await call(`${ORIGIN}/notes/api/session`, { method: "POST", body: "not json" }),
    ]) {
      expect(res.status).toBe(200);
      const body = (await res.json()) as { token: string; expiresAt: number };
      expect(body.token).toBe(OPEN_TOKEN);
      expect(body.expiresAt).toBeGreaterThanOrEqual(before + OPEN_SESSION_TTL_MS);
      // Clears the cookie an earlier version may have left.
      const cookie = res.headers.get("Set-Cookie") as string;
      expect(cookie).toMatch(/^nv_session=;/);
      expect(cookie).toContain("Max-Age=0");
      expect(cookie).toContain("Path=/notes;");
    }
  });

  it("a cross-site post is refused", async () => {
    const res = await call("/api/session", { method: "POST", headers: { Origin: "https://evil.example" }, body: "{}" });
    expect(res.status).toBe(403);
  });

  it("logout answers ok and clears the old cookie", async () => {
    const res = await call("/api/logout", { method: "POST", headers: { Origin: ORIGIN } });
    expect(res.status).toBe(200);
    expect(await res.json()).toEqual({ ok: true });
    expect(res.headers.get("Set-Cookie")).toMatch(/^nv_session=;.*Max-Age=0/);
  });
});

describe("changes from another website open in the same browser", () => {
  it("PUT and DELETE with a foreign Origin are refused", async () => {
    const id = uid();
    for (const origin of ["https://evil.example", "http://vault.example.test", "null", `${ORIGIN}.evil.example`]) {
      const res = await call(`/api/docs/${id}`, { method: "PUT", headers: { Origin: origin }, body: JSON.stringify(docBody()) });
      expect(res.status, origin).toBe(403);
      expect(await res.json()).toEqual({ error: "bad_origin" });
    }
    expect((await call(`/api/docs/${id}`, { method: "DELETE", headers: { Origin: "https://evil.example" } })).status).toBe(403);
  });

  it("the page's own origin and the app (no Origin) can change things", async () => {
    expect((await call(`/api/docs/${uid()}`, { method: "PUT", headers: { Origin: ORIGIN }, body: JSON.stringify(docBody()) })).status).toBe(200);
    expect((await call(`/api/docs/${uid()}`, { method: "PUT", body: JSON.stringify(docBody()) })).status).toBe(200);
  });

  it("the base URL used in tests is under the link", () => {
    expect(BASE.endsWith(`/notes/${LINK_ID}`)).toBe(true);
  });
});
