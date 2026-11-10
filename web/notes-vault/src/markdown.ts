/**
 * A small, safe Markdown renderer for the vault's web UI.
 *
 * Safety model: every piece of source text is HTML-escaped before it reaches the output;
 * the renderer only ever emits a fixed set of tags with fixed attributes. The only
 * attribute values derived from input are link targets (accepted only when they parse as
 * absolute http/https URLs, then escaped) and the fenced-code language class (restricted
 * to [A-Za-z0-9_+-]). Raw HTML in the source is shown as text, never interpreted.
 *
 * Supported: ATX headings, paragraphs (single newlines become <br>), bullet / ordered /
 * task lists (nested by indentation), blockquotes, fenced code, inline code, bold,
 * italic, strikethrough, GFM tables, [links](https://...), bare http(s) URLs, rules.
 *
 * IMPORTANT: this function must stay self-contained (no references to anything outside
 * its own body) because the page embeds `renderMarkdown.toString()` into its inline
 * script, so the browser runs exactly the code the tests exercise.
 */
export function renderMarkdown(source: string): string {
  const MAX_DEPTH = 6;
  const SLOT = "\u0000";

  const esc = (s: string): string =>
    s
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;")
      .replace(/'/g, "&#39;");

  const safeHref = (raw: string): string | null => {
    if (!/^https?:\/\//i.test(raw) || /[\s<>"'`\\\u0000-\u001f\u007f]/.test(raw)) return null;
    try {
      const u = new URL(raw);
      if (u.protocol !== "http:" && u.protocol !== "https:") return null;
    } catch {
      return null;
    }
    return raw;
  };

  const anchor = (href: string, labelHtml: string): string =>
    '<a href="' + esc(href) + '" rel="noopener noreferrer" target="_blank">' + labelHtml + "</a>";

  const emphasis = (s: string): string =>
    s
      .replace(/\*\*(?=\S)([^\n]{0,800}?\S)\*\*/g, "<strong>$1</strong>")
      .replace(/(^|[^A-Za-z0-9_])__(?=\S)([^\n]{0,800}?\S)__(?![A-Za-z0-9_])/g, "$1<strong>$2</strong>")
      .replace(/\*(?=[^\s*])([^\n]{0,800}?[^\s*])\*/g, "<em>$1</em>")
      .replace(/(^|[^A-Za-z0-9_])_(?=[^\s_])([^\n]{0,800}?[^\s_])_(?![A-Za-z0-9_])/g, "$1<em>$2</em>")
      .replace(/~~(?=\S)([^\n]{0,800}?\S)~~/g, "<del>$1</del>");

  function inline(text: string): string {
    const slots: string[] = [];
    const hold = (html: string): string => SLOT + (slots.push(html) - 1) + SLOT;
    let s = text;
    // Code spans first: their content is literal.
    s = s.replace(/(`+)([^`\n]|[^`\n][^\n]*?[^`\n])\1(?!`)/g, (_m, _ticks: string, code: string) =>
      hold("<code>" + esc(code) + "</code>"),
    );
    // Backslash escapes.
    s = s.replace(/\\([\\`*_{}[\]()#+\-.!|~>])/g, (_m, ch: string) => hold(esc(ch)));
    // Explicit links (and images, which are shown as links: no remote images are loaded).
    s = s.replace(
      /!?\[([^[\]\n]{0,1000})\]\(\s*<?([^\s()<>]{1,2048})>?(?:\s+"[^"\n]{0,500}")?\s*\)/g,
      (m: string, label: string, url: string) => {
        const href = safeHref(url);
        if (!href) return m;
        return hold(anchor(href, emphasis(esc(label || url))));
      },
    );
    // Bare URLs.
    s = s.replace(/\bhttps?:\/\/[^\s<>"'`\u0000]+/gi, (m: string) => {
      let url = m;
      let tail = "";
      while (/[.,;:!?)\]]$/.test(url)) {
        if (url.endsWith(")") && (url.match(/\(/g) || []).length >= (url.match(/\)/g) || []).length) break;
        tail = url.slice(-1) + tail;
        url = url.slice(0, -1);
      }
      const href = safeHref(url);
      return href ? hold(anchor(href, esc(url))) + tail : m;
    });
    s = emphasis(esc(s));
    for (let guard = 0; guard < 8 && s.indexOf(SLOT) !== -1; guard++) {
      s = s.replace(/\u0000(\d+)\u0000/g, (_m, n: string) => slots[Number(n)] ?? "");
    }
    return s;
  }

  const isBlank = (line: string): boolean => /^\s*$/.test(line);
  const leading = (line: string): number => (/^ */.exec(line) as RegExpExecArray)[0].length;
  const listMarker = (line: string): RegExpExecArray | null =>
    /^( {0,40})([-*+]|\d{1,9}[.)])([ \t]+)(.*)$/.exec(line);
  const fenceOpen = (line: string): RegExpExecArray | null =>
    /^ {0,3}(`{3,}|~{3,})[ \t]*([A-Za-z0-9_+-]{0,30})[^`]*$/.exec(line);
  const isRule = (line: string): boolean => /^ {0,3}([-*_])(?:[ \t]*\1){2,}[ \t]*$/.test(line);

  function splitRow(line: string): string[] {
    let t = line.trim();
    if (t.startsWith("|")) t = t.slice(1);
    if (t.endsWith("|") && !t.endsWith("\\|")) t = t.slice(0, -1);
    const cells: string[] = [];
    let cur = "";
    for (let k = 0; k < t.length; k++) {
      const c = t[k];
      if (c === "\\" && t[k + 1] === "|") {
        cur += "\\|";
        k++;
      } else if (c === "|") {
        cells.push(cur.trim());
        cur = "";
      } else {
        cur += c;
      }
    }
    cells.push(cur.trim());
    return cells;
  }

  const isDelimiterRow = (line: string): boolean =>
    line.indexOf("-") !== -1 &&
    /^[\s|:-]+$/.test(line) &&
    splitRow(line).every((c) => /^:?-+:?$/.test(c));

  function table(header: string[], delimiter: string[], rows: string[][]): string {
    const cols = header.length;
    const cls = delimiter.map((d) => {
      const left = d.startsWith(":");
      const right = d.endsWith(":");
      return left && right ? ' class="al-c"' : right ? ' class="al-r"' : left ? ' class="al-l"' : "";
    });
    let html = '<div class="table-wrap"><table><thead><tr>';
    for (let c = 0; c < cols; c++) html += "<th" + (cls[c] ?? "") + ">" + inline(header[c] ?? "") + "</th>";
    html += "</tr></thead><tbody>";
    for (const row of rows) {
      html += "<tr>";
      for (let c = 0; c < cols; c++) html += "<td" + (cls[c] ?? "") + ">" + inline(row[c] ?? "") + "</td>";
      html += "</tr>";
    }
    return html + "</tbody></table></div>";
  }

  function list(lines: string[], start: number, depth: number, out: string[]): number {
    const first = listMarker(lines[start] as string) as RegExpExecArray;
    const indent = (first[1] as string).length;
    const ordered = /\d/.test(first[2] as string);
    const items: { lines: string[]; loose: boolean }[] = [];
    let contentOffset = 0;
    let i = start;
    let prevBlank = false;
    while (i < lines.length) {
      const line = lines[i] as string;
      const m = listMarker(line);
      if (m && (m[1] as string).length === indent && /\d/.test(m[2] as string) === ordered && !isRule(line)) {
        contentOffset = indent + (m[2] as string).length + Math.min((m[3] as string).length, 4);
        items.push({ lines: [m[4] as string], loose: false });
        prevBlank = false;
        i++;
        continue;
      }
      const item = items[items.length - 1] as { lines: string[]; loose: boolean };
      if (isBlank(line)) {
        const next = lines[i + 1];
        const nm = next === undefined ? null : listMarker(next);
        const continues =
          next !== undefined &&
          !isBlank(next) &&
          (leading(next) > indent || (nm !== null && (nm[1] as string).length === indent));
        if (!continues) break;
        item.lines.push("");
        if (leading(next as string) > indent && !(nm && (nm[1] as string).length === indent)) item.loose = true;
        prevBlank = true;
        i++;
        continue;
      }
      const lead = leading(line);
      if (lead > indent) {
        item.lines.push(line.slice(Math.min(lead, contentOffset)));
        prevBlank = false;
        i++;
        continue;
      }
      // Lazy continuation of the item's paragraph (no indentation, not another block).
      if (!prevBlank && !m && !fenceOpen(line) && !isRule(line) && !/^ {0,3}(#{1,6}[ \t]|>)/.test(line)) {
        item.lines.push(line.trim());
        i++;
        continue;
      }
      break;
    }
    const startNum = ordered ? parseInt(first[2] as string, 10) : 1;
    let html = ordered ? (startNum !== 1 ? '<ol start="' + startNum + '">' : "<ol>") : "<ul>";
    for (const item of items) {
      let liClass = "";
      let prefix = "";
      const task = /^\[([ xX])\][ \t]+/.exec(item.lines[0] as string);
      if (task) {
        liClass = ' class="task"';
        prefix = '<input type="checkbox" disabled' + (task[1] === " " ? "" : " checked") + "> ";
        item.lines[0] = (item.lines[0] as string).slice(task[0].length);
      }
      let body = blocks(item.lines, depth + 1);
      if (!item.loose) body = body.replace(/^<p>([\s\S]*?)<\/p>/, "$1");
      html += "<li" + liClass + ">" + prefix + body + "</li>";
    }
    out.push(html + (ordered ? "</ol>" : "</ul>"));
    return i;
  }

  function blocks(lines: string[], depth: number): string {
    if (depth > MAX_DEPTH) {
      return lines.filter((l) => !isBlank(l)).map((l) => "<p>" + inline(l.trim()) + "</p>").join("");
    }
    const out: string[] = [];
    let para: string[] = [];
    const flush = (): void => {
      if (para.length) out.push("<p>" + para.map((l) => inline(l.trim())).join("<br>") + "</p>");
      para = [];
    };
    let i = 0;
    while (i < lines.length) {
      const line = lines[i] as string;
      const fence = fenceOpen(line);
      if (fence) {
        flush();
        const marker = fence[1] as string;
        const lang = fence[2] as string;
        const body: string[] = [];
        i++;
        while (i < lines.length) {
          const t = (lines[i] as string).trim();
          if (t.length >= marker.length && t === (marker[0] as string).repeat(t.length)) break;
          body.push(lines[i] as string);
          i++;
        }
        i++;
        out.push(
          "<pre><code" + (lang ? ' class="language-' + esc(lang) + '"' : "") + ">" + esc(body.join("\n")) + "</code></pre>",
        );
        continue;
      }
      if (isBlank(line)) {
        flush();
        i++;
        continue;
      }
      const heading = /^ {0,3}(#{1,6})(?:[ \t]+(.*?))?(?:[ \t]+#+)?[ \t]*$/.exec(line);
      if (heading) {
        flush();
        const level = (heading[1] as string).length;
        const tag = "h" + Math.min(level + 2, 6);
        out.push("<" + tag + ' class="md-h' + level + '">' + inline(heading[2] ?? "") + "</" + tag + ">");
        i++;
        continue;
      }
      if (isRule(line)) {
        flush();
        out.push("<hr>");
        i++;
        continue;
      }
      if (/^ {0,3}>/.test(line)) {
        flush();
        const inner: string[] = [];
        while (i < lines.length && /^ {0,3}>/.test(lines[i] as string)) {
          inner.push((lines[i] as string).replace(/^ {0,3}> ?/, ""));
          i++;
        }
        out.push("<blockquote>" + blocks(inner, depth + 1) + "</blockquote>");
        continue;
      }
      const next = lines[i + 1];
      if (line.indexOf("|") !== -1 && next !== undefined && isDelimiterRow(next)) {
        const header = splitRow(line);
        const delimiter = splitRow(next);
        if (header.length === delimiter.length) {
          flush();
          i += 2;
          const rows: string[][] = [];
          while (i < lines.length && !isBlank(lines[i] as string) && (lines[i] as string).indexOf("|") !== -1) {
            rows.push(splitRow(lines[i] as string));
            i++;
          }
          out.push(table(header, delimiter, rows));
          continue;
        }
      }
      if (listMarker(line)) {
        flush();
        i = list(lines, i, depth, out);
        continue;
      }
      para.push(line);
      i++;
    }
    flush();
    return out.join("\n");
  }

  const text = String(source ?? "")
    .replace(/\u0000/g, "�")
    .replace(/\r\n?/g, "\n")
    .replace(/\t/g, "    ");
  return blocks(text.split("\n"), 0);
}
