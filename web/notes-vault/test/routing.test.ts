import { env } from "cloudflare:workers";
import { describe, expect, it } from "vitest";
import { BASE, LINK_ID, ORIGIN, bearer, call, token } from "./helpers";

async function expectPlain404(res: Response) {
  expect(res.status).toBe(404);
  expect(res.headers.get("Content-Type")).toMatch(/^text\/plain/);
  const text = await res.text();
  expect(text.trim()).toBe("Not Found");
  expect(text).not.toContain(LINK_ID);
  expect(res.headers.get("Set-Cookie")).toBeNull();
  // Security headers are on every response, 404s included.
  expect(res.headers.get("X-Content-Type-Options")).toBe("nosniff");
  expect(res.headers.get("Cache-Control")).toBe("no-store");
}

describe("routing outside the private link", () => {
  const outside = [
    "/",
    "/notesabc",
    "/notes/apix",
    "/notesapi/ping",
    "/notes/wrong-link-id",
    "/notes/wrong-link-id/",
    "/notes/wrong-link-id/api/ping",
    "/notes/wrong-link-id/api/session",
    `/notes/${LINK_ID}x`,
    `/notes/${LINK_ID}x/api/ping`,
    `/notes/${LINK_ID.slice(0, -1)}`,
    `/notes/${LINK_ID.toUpperCase()}`,
    `/x/notes/${LINK_ID}`,
    "/favicon.ico",
  ];
  for (const path of outside) {
    it(`GET ${path} is a plain 404`, async () => {
      await expectPlain404(await call(ORIGIN + path));
    });
  }

  it("a wrong link is a 404 even with a valid token and for POST", async () => {
    const t = await token();
    await expectPlain404(await call(`${ORIGIN}/notes/wrong-link-id/api/docs`, { headers: bearer(t) }));
    await expectPlain404(
      await call(`${ORIGIN}/notes/wrong-link-id/api/session`, {
        method: "POST",
        body: JSON.stringify({ password: env.NOTES_PASSWORD }),
      }),
    );
  });

  it("everything is a 404 when NOTES_LINK_ID is not configured", async () => {
    const e = { ...env, NOTES_LINK_ID: "" };
    await expectPlain404(await call(BASE, { env: e }));
    await expectPlain404(await call(`${BASE}/api/ping`, { env: e }));
  });

  it("unknown paths under the link are 404 too", async () => {
    await expectPlain404(await call(`${BASE}/elsewhere`));
    await expectPlain404(await call(`${BASE}/apix`));
  });

  it("unknown API routes under the link need auth, then 404 as JSON", async () => {
    expect((await call(`${BASE}/api/nope`)).status).toBe(401);
    const res = await call(`${BASE}/api/nope`, { headers: bearer(await token()) });
    expect(res.status).toBe(404);
    expect(await res.json()).toEqual({ error: "not_found" });
  });

  it("wrong methods get 405 with Allow", async () => {
    const res = await call(`${BASE}/api/session`);
    expect(res.status).toBe(405);
    expect(res.headers.get("Allow")).toBe("POST");
    const page = await call(BASE, { method: "POST" });
    expect(page.status).toBe(405);
  });
});

describe("ping", () => {
  it("returns the vault name with a token", async () => {
    const res = await call("/api/ping", { headers: bearer(await token()) });
    expect(res.status).toBe(200);
    expect(res.headers.get("Content-Type")).toMatch(/^application\/json/);
    expect(await res.json()).toEqual({ ok: true, name: "Your Pit Box · Notes" });
  });

  it("is 401 without a token", async () => {
    const res = await call("/api/ping");
    expect(res.status).toBe(401);
    expect(await res.json()).toEqual({ error: "unauthorized" });
  });
});

describe("the plain /notes entry", () => {
  for (const path of ["/notes", "/notes/"]) {
    it(`GET ${path} serves the locked page, without the link in it`, async () => {
      const res = await call(ORIGIN + path);
      expect(res.status).toBe(200);
      expect(res.headers.get("Content-Type")).toMatch(/^text\/html/);
      const html = await res.text();
      expect(html).toContain('id="login-view"');
      expect(html).not.toContain(LINK_ID);
    });
  }

  it("its API needs a session", async () => {
    expect((await call(`${ORIGIN}/notes/api/ping`)).status).toBe(401);
    expect((await call(`${ORIGIN}/notes/api/docs`)).status).toBe(401);
  });

  it("the password signs in there and the cookie works on both entries", async () => {
    const res = await call(`${ORIGIN}/notes/api/session`, {
      method: "POST",
      headers: { "Content-Type": "application/json", "CF-Connecting-IP": "10.200.0.1" },
      body: JSON.stringify({ password: env.NOTES_PASSWORD }),
    });
    expect(res.status).toBe(200);
    const setCookie = res.headers.get("Set-Cookie") ?? "";
    expect(setCookie).toContain("Path=/notes;");
    const cookie = setCookie.split(";")[0] as string;
    expect((await call(`${ORIGIN}/notes/api/docs`, { headers: { Cookie: cookie } })).status).toBe(200);
    expect((await call(`${BASE}/api/docs`, { headers: { Cookie: cookie } })).status).toBe(200);
    const page = await (await call(`${ORIGIN}/notes`, { headers: { Cookie: cookie } })).text();
    expect(page).toContain('data-signed-in="true"');
  });

  it("a wrong password is refused there too", async () => {
    const res = await call(`${ORIGIN}/notes/api/session`, {
      method: "POST",
      headers: { "Content-Type": "application/json", "CF-Connecting-IP": "10.200.0.2" },
      body: JSON.stringify({ password: "not-the-password" }),
    });
    expect(res.status).toBe(401);
  });

  it("a bearer token from the app works on the plain API", async () => {
    const t = await token();
    expect((await call(`${ORIGIN}/notes/api/docs`, { headers: bearer(t) })).status).toBe(200);
  });
});
