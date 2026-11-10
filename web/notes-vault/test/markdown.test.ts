import { describe, expect, it } from "vitest";
import { renderMarkdown as md } from "../src/markdown";

describe("renderMarkdown safety", () => {
  it("escapes raw HTML everywhere", () => {
    const out = md('<script>alert(1)</script>\n\n<img src=x onerror="alert(1)">\n\n# <b>hi</b>\n\n- <i>x</i>');
    expect(out).not.toMatch(/<script|<img|<b>|<i>/);
    expect(out).toContain("&lt;script&gt;alert(1)&lt;/script&gt;");
    expect(out).toContain("&lt;img src=x onerror=&quot;alert(1)&quot;&gt;");
    expect(out).toContain("&lt;b&gt;hi&lt;/b&gt;");
  });

  it("only links http and https URLs, with rel=noopener noreferrer", () => {
    expect(md("[ok](https://example.com/a?b=1&c=2)")).toBe(
      '<p><a href="https://example.com/a?b=1&amp;c=2" rel="noopener noreferrer" target="_blank">ok</a></p>',
    );
    expect(md("[ok](http://example.com)")).toContain('href="http://example.com"');
    for (const evil of [
      "javascript:alert(1)",
      "JaVaScRiPt:alert(1)",
      "data:text/html,<script>alert(1)</script>",
      "vbscript:x",
      "//evil.example",
      "/relative",
      "javascript&#58;alert(1)",
    ]) {
      const out = md(`[click](${evil})`);
      expect(out, evil).not.toContain("<a ");
      expect(out).not.toMatch(/href="(?!https?:)/);
    }
    expect(md('[x](https://ex.com/"><script>)')).not.toContain("<script");
    // A quote can never break out of the href attribute.
    const quoted = md('[click](https://ex.com/"onmouseover="alert(1))');
    expect(quoted).not.toMatch(/"\s*onmouseover=/);
    expect(quoted).toContain("&quot;onmouseover=&quot;");
  });

  it("autolinks bare URLs but not other schemes", () => {
    expect(md("see https://example.com/path.")).toBe(
      '<p>see <a href="https://example.com/path" rel="noopener noreferrer" target="_blank">https://example.com/path</a>.</p>',
    );
    expect(md("javascript:alert(1)")).toBe("<p>javascript:alert(1)</p>");
    expect(md('"https://ex.com/a"')).toContain('href="https://ex.com/a"');
    expect(md('"https://ex.com/a"')).not.toContain("&quot;\"");
  });

  it("code is literal", () => {
    expect(md("`<b>**x**</b>`")).toBe("<p><code>&lt;b&gt;**x**&lt;/b&gt;</code></p>");
    expect(md("```html\n<script>alert(1)</script>\n**not bold**\n```")).toBe(
      '<pre><code class="language-html">&lt;script&gt;alert(1)&lt;/script&gt;\n**not bold**</code></pre>',
    );
    expect(md('```" onclick="x\ncode\n```')).not.toContain("onclick=\"");
  });

  it("neutralises NUL placeholders in the input", () => {
    expect(md("a\u00000\u0000b")).not.toContain("\u0000");
  });

  it("survives pathological nesting and long input", () => {
    expect(() => md(">".repeat(5000) + " deep")).not.toThrow();
    expect(() => md("- ".repeat(3000) + "x")).not.toThrow();
    const started = Date.now();
    md("**".repeat(20000));
    md("[".repeat(20000) + "](".repeat(2000));
    md("_a ".repeat(20000));
    expect(Date.now() - started).toBeLessThan(5000);
  });
});

describe("renderMarkdown features", () => {
  it("headings (shifted below the page and document headings)", () => {
    expect(md("# Title")).toBe('<h3 class="md-h1">Title</h3>');
    expect(md("## Sub ##")).toBe('<h4 class="md-h2">Sub</h4>');
    expect(md("###### Six")).toBe('<h6 class="md-h6">Six</h6>');
    expect(md("#hashtag")).toBe("<p>#hashtag</p>");
  });

  it("paragraphs keep line breaks", () => {
    expect(md("line one\nline two\n\nnext")).toBe("<p>line one<br>line two</p>\n<p>next</p>");
  });

  it("bold, italic, strikethrough", () => {
    expect(md("**bold** and *it* and __b2__ and _i2_ and ~~gone~~")).toBe(
      "<p><strong>bold</strong> and <em>it</em> and <strong>b2</strong> and <em>i2</em> and <del>gone</del></p>",
    );
    expect(md("snake_case_name stays")).toBe("<p>snake_case_name stays</p>");
    expect(md("2 * 3 * 4")).toBe("<p>2 * 3 * 4</p>");
    expect(md("\\*literal\\*")).toBe("<p>*literal*</p>");
  });

  it("lists, nested lists, ordered lists and tasks", () => {
    expect(md("- a\n- b\n  - c\n- d")).toBe("<ul><li>a</li><li>b\n<ul><li>c</li></ul></li><li>d</li></ul>");
    expect(md("1. one\n2. two")).toBe("<ol><li>one</li><li>two</li></ol>");
    expect(md("3. three\n4. four")).toBe('<ol start="3"><li>three</li><li>four</li></ol>');
    expect(md("- [ ] todo\n- [x] done")).toBe(
      '<ul><li class="task"><input type="checkbox" disabled> todo</li><li class="task"><input type="checkbox" disabled checked> done</li></ul>',
    );
    expect(md("* **bold item**")).toBe("<ul><li><strong>bold item</strong></li></ul>");
    expect(md("- a\nlazy continuation")).toBe("<ul><li>a<br>lazy continuation</li></ul>");
  });

  it("blockquotes and rules", () => {
    expect(md("> quoted *text*\n> more")).toBe("<blockquote><p>quoted <em>text</em><br>more</p></blockquote>");
    expect(md("a\n\n---\n\nb")).toBe("<p>a</p>\n<hr>\n<p>b</p>");
  });

  it("tables with alignment", () => {
    expect(md("| Term | Rate |\n|:-----|-----:|\n| 2y | **4.1%** |\n| 10y | 3.9% |")).toBe(
      '<div class="table-wrap"><table><thead><tr><th class="al-l">Term</th><th class="al-r">Rate</th></tr></thead>' +
        '<tbody><tr><td class="al-l">2y</td><td class="al-r"><strong>4.1%</strong></td></tr>' +
        '<tr><td class="al-l">10y</td><td class="al-r">3.9%</td></tr></tbody></table></div>',
    );
    expect(md("a | b\n--- | ---\n1 | 2")).toContain("<table>");
    expect(md("| a \\| b |\n|---|\n| c |")).toContain("<th>a | b</th>");
  });

  it("inline code inside links and emphasis inside link labels", () => {
    expect(md("[**`code`** label](https://ex.com)")).toBe(
      '<p><a href="https://ex.com" rel="noopener noreferrer" target="_blank"><strong><code>code</code></strong> label</a></p>',
    );
  });

  it("images render as links only (no remote image loads)", () => {
    const out = md("![chart](https://ex.com/c.png)");
    expect(out).not.toContain("<img");
    expect(out).toContain('<a href="https://ex.com/c.png"');
  });

  it("works on CRLF input and empty input", () => {
    expect(md("a\r\nb")).toBe("<p>a<br>b</p>");
    expect(md("")).toBe("");
  });
});
