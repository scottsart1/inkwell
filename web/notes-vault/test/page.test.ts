import { describe, expect, it } from "vitest";
import { BASE, LINK_ID, bearer, call, token } from "./helpers";

const EXPECTED_CSP_PARTS = [
  "default-src 'none'",
  "font-src https://fonts.gstatic.com",
  "img-src 'self' data:",
  "media-src 'self' blob:",
  "connect-src 'self'",
  "form-action 'self'",
  "base-uri 'none'",
  "frame-ancestors 'none'",
];

function expectSecurityHeaders(res: Response): string {
  const csp = res.headers.get("Content-Security-Policy") as string;
  expect(csp).toBeTruthy();
  for (const part of EXPECTED_CSP_PARTS) expect(csp).toContain(part);
  const nonce = /script-src 'nonce-([A-Za-z0-9+/=]+)'/.exec(csp)?.[1] as string;
  expect(nonce).toBeTruthy();
  expect(nonce.length).toBeGreaterThanOrEqual(16);
  expect(csp).toContain(`style-src 'self' 'nonce-${nonce}' https://fonts.googleapis.com`);
  expect(csp).not.toContain("unsafe-inline");
  expect(csp).not.toContain("unsafe-eval");
  expect(res.headers.get("X-Content-Type-Options")).toBe("nosniff");
  expect(res.headers.get("Referrer-Policy")).toBe("no-referrer");
  expect(res.headers.get("X-Robots-Tag")).toBe("noindex, nofollow");
  expect(res.headers.get("Cache-Control")).toBe("no-store");
  expect(res.headers.get("Permissions-Policy")).toMatch(/camera=\(\)/);
  return nonce;
}

describe("web UI", () => {
  for (const suffix of ["", "/"]) {
    it(`GET /notes/<link>${suffix} serves the notes page, with no sign-in, and a fresh nonce`, async () => {
      const res = await call(BASE + suffix);
      expect(res.status).toBe(200);
      expect(res.headers.get("Content-Type")).toBe("text/html; charset=utf-8");
      const nonce = expectSecurityHeaders(res);
      const html = await res.text();

      expect(html).toContain("<title>Notes</title>");
      expect(html).toContain("Your Pit Box · Notes");
      expect(html).toMatch(/<div id="app-view" class="app">/);
      expect(html).toMatch(/<input[^>]*id="search"/);
      expect(html).not.toMatch(/type="password"/);
      expect(html).not.toContain("login-view");
      expect(html).not.toContain("lock-btn");
      expect(html).not.toContain("Unlock");

      // Every script and style element carries this response's nonce; no external scripts.
      const scripts = html.match(/<script\b[^>]*>/g) ?? [];
      expect(scripts).toEqual([`<script nonce="${nonce}">`]);
      const styles = html.match(/<style\b[^>]*>/g) ?? [];
      expect(styles).toEqual([`<style nonce="${nonce}">`]);
      expect(html).not.toMatch(/<script[^>]+src=/);
      expect(html.match(/<\/script/gi)).toHaveLength(1);
      expect(html).not.toMatch(/\sstyle="/);
      expect(html).not.toMatch(/\son[a-z]+="/);
      expect(html).toContain('href="https://fonts.googleapis.com/css2?family=Figtree');

      // The link id is not embedded in the page (the script derives it from the URL).
      expect(html).not.toContain(LINK_ID);
      // The Markdown renderer is inlined into the script.
      expect(html).toContain("var renderMarkdown = (function");
      expect(html).not.toContain("__RENDER_MARKDOWN__");
    });
  }

  it("uses a different nonce on every response", async () => {
    const a = expectSecurityHeaders(await call(BASE));
    const b = expectSecurityHeaders(await call(BASE));
    expect(a).not.toBe(b);
  });

  it("uses the Inkwell palette and dark mode", async () => {
    const html = await (await call(BASE)).text();
    for (const color of ["#F5F4F0", "#FFFFFF", "#1A1916", "#57544C", "#2952CC", "#DDD9CF", "#141412", "#1D1C1A", "#EDEBE5", "#8FA8F0"]) {
      expect(html).toContain(color);
    }
    expect(html).toContain("@media (prefers-color-scheme: dark)");
    expect(html).toContain("@media (max-width: 760px)");
    expect(html).not.toMatch(/gradient/i);
  });

  it("security headers are on JSON responses too", async () => {
    expectSecurityHeaders(await call("/api/ping", { headers: bearer(await token()) }));
    expectSecurityHeaders(await call("/api/ping"));
  });

  it("HEAD returns headers only", async () => {
    const res = await call(BASE, { method: "HEAD" });
    expect(res.status).toBe(200);
    expect(res.body).toBeNull();
    expectSecurityHeaders(res);
  });
});
