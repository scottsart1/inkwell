import { describe, expect, it } from "vitest";
import { renderMarkdown as md } from "../src/markdown";
import { BASE, ORIGIN, bearer, call, login, token, uid } from "./helpers";

describe("hardening", () => {
  it("the Markdown renderer never emits a tag, handler or scheme from its input", () => {
    const cases = [
      "[a](https://x.test/\u0000)",
      "[a](https://x.test/%0Ajavascript:alert(1))",
      "![x](https://x.test/a.png)",
      "[<img src=x onerror=alert(1)>](https://x.test)",
      "<a href=\"javascript:alert(1)\">x</a>",
      "| a | b |\n|---|---|\n| <script>x</script> | `<b>` |",
      "- [ ] <input onfocus=alert(1) autofocus>",
      "```\" onmouseover=\"alert(1)\nx\n```",
      "[a](https://x.test \"t\" onmouseover=alert(1))",
      "https://x.test/?a=1&b=<script>",
      "**[a](https://x.test)**",
      "> ![a](javascript:alert(1))",
      "[a](HTTPS://x.test/\"onclick=alert(1))",
      "\u0000\u0000",
      "[a](https://x.test/<b>)",
      "a\u00001\u0000b `code`",
    ];
    for (const c of cases) {
      const out = md(c);
      // Only real tags count: escaped text may legitimately contain these words.
      expect(out.replace(/<input type="checkbox" disabled( checked)?>/g, ""), c).not.toMatch(/<(script|img|input|iframe|svg|style)\b/i);
      expect(out, c).not.toMatch(/<[^>]*\son\w+=/i);
      expect(out, c).not.toMatch(/href="(?!https?:)/i);
      expect(out, c).not.toMatch(/href="javascript:/i);
      // No unescaped quote inside an attribute: every href is attr-escaped.
      for (const m of out.matchAll(/href="([^"]*)"/g)) expect(m[1], c).not.toMatch(/[<>"']/);
      expect(out, c).not.toContain("\u0000");
    }
    // Slot restoration never leaks user digits into other slots.
    expect(md("`a`0`b`")).toBe("<p><code>a</code>0<code>b</code></p>");
  });

  it("odd paths, mixed credentials and oversized logins are refused", async () => {
    const t = await token();
    // Encoded slash in a doc id, dot segments, upper-case method, trailing slashes.
    for (const p of ["/api/docs/a%2Fb", "/api/docs/..", "/api/docs/./x", "/api/docs/x/files/..%2F..%2Fetc", "/api/docs/x/transcripts/%00"]) {
      const r = await call(p, { method: "PUT", headers: { ...bearer(t), "Content-Type": "application/json" }, body: "{}" });
      expect([400, 404], p).toContain(r.status);
    }
    expect((await call("/api/ping/", { headers: bearer(t) })).status).toBe(200);
    expect((await call("/api//ping", { headers: bearer(t) })).status).toBe(404);
    // Bearer with cookie: a wrong bearer never falls back to the cookie even on GET.
    const { cookie } = await login();
    expect((await call("/api/ping", { headers: { Cookie: cookie, Authorization: "Basic abc" } })).status).toBe(401);
    // Cookie DELETE with same origin works; cookie DELETE of a file with foreign origin refused.
    const id = uid();
    await call(`/api/docs/${id}`, { method: "PUT", headers: { ...bearer(t), "Content-Type": "application/json" }, body: JSON.stringify({ title: "t", kind: "TYPED", createdAt: 1, modifiedAt: 1 }) });
    expect((await call(`/api/docs/${id}`, { method: "DELETE", headers: { Cookie: cookie, Origin: "https://evil.example" } })).status).toBe(403);
    expect((await call(`/api/docs/${id}`, { method: "DELETE", headers: { Cookie: cookie, Origin: ORIGIN } })).status).toBe(200);
    // Login with a huge body is refused, with charset content types accepted.
    const big = await call("/api/session", { method: "POST", headers: { "Content-Type": "application/json; charset=utf-8", "CF-Connecting-IP": "10.9.9.9" }, body: JSON.stringify({ password: "x".repeat(5000) }) });
    expect(big.status).toBe(413);
    // Query search with weird input.
    expect((await call(`/api/docs?q=${encodeURIComponent("%_\\'\"; DROP TABLE docs; --")}`, { headers: bearer(t) })).status).toBe(200);
    expect(BASE.length).toBeGreaterThan(0);
  });
});
