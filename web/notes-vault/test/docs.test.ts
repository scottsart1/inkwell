import { env } from "cloudflare:workers";
import { describe, expect, it } from "vitest";
import { MARKDOWN_INLINE_MAX_BYTES, markdownKey } from "../src/api";
import { bearer, call, docBody, getJson, putJson, token, transcriptBody, uid } from "./helpers";

interface Summary {
  id: string;
  title: string;
  kind: string;
  createdAt: number;
  modifiedAt: number;
  folderPath: string | null;
  pageCount: number;
  transcriptCount: number;
  fileCount: number;
}

async function putFile(path: string, bytes: Uint8Array | string, type = "application/octet-stream") {
  const data = typeof bytes === "string" ? new TextEncoder().encode(bytes) : bytes;
  return call(path, {
    method: "PUT",
    headers: { ...bearer(await token()), "Content-Type": type, "Content-Length": String(data.byteLength) },
    body: data,
  });
}

describe("doc upsert and detail", () => {
  it("creates a doc and returns the full DocDetail", async () => {
    const id = uid();
    const res = await putJson(`/api/docs/${id}`, docBody());
    expect(res.status).toBe(200);
    expect(await res.json()).toEqual({ ok: true });

    const { status, body } = await getJson(`/api/docs/${id}`);
    expect(status).toBe(200);
    expect(body).toEqual({
      id,
      title: "20261015_Financial Markets",
      kind: "NOTEBOOK",
      createdAt: 1_760_500_000_000,
      modifiedAt: 1_760_510_000_000,
      folderPath: "Economics / Term 1",
      pageCount: 3,
      transcriptCount: 0,
      fileCount: 0,
      tags: ["economics", "lecture"],
      markdown: "# Financial Markets\n\nBond yields and **duration**.",
      transcripts: [],
      files: [],
    });
  });

  it("PUT replaces metadata and markdown; omitted optional fields reset to defaults", async () => {
    const id = uid();
    await putJson(`/api/docs/${id}`, docBody());
    const res = await putJson(`/api/docs/${id}`, {
      title: "20261015_Financial Markets (revised)",
      kind: "PDF",
      createdAt: 1_760_500_000_000,
      modifiedAt: 1_760_520_000_000,
    });
    expect(res.status).toBe(200);
    const { body } = await getJson(`/api/docs/${id}`);
    expect(body).toMatchObject({
      title: "20261015_Financial Markets (revised)",
      kind: "PDF",
      modifiedAt: 1_760_520_000_000,
      folderPath: null,
      pageCount: 0,
      tags: [],
      markdown: "",
    });
  });

  it("GET of an unknown doc is 404", async () => {
    const { status, body } = await getJson(`/api/docs/${uid()}`);
    expect(status).toBe(404);
    expect(body).toEqual({ error: "not_found" });
  });

  it("validates ids and bodies", async () => {
    const cases: [string, unknown, number][] = [
      ["/api/docs/bad_id", docBody(), 400],
      [`/api/docs/${"a".repeat(65)}`, docBody(), 400],
      ["/api/docs/bad%20id", docBody(), 400],
      [`/api/docs/${uid()}`, docBody({ kind: "SPREADSHEET" }), 400],
      [`/api/docs/${uid()}`, docBody({ title: 7 }), 400],
      [`/api/docs/${uid()}`, docBody({ title: undefined }), 400],
      [`/api/docs/${uid()}`, docBody({ createdAt: "yesterday" }), 400],
      [`/api/docs/${uid()}`, docBody({ modifiedAt: -1 }), 400],
      [`/api/docs/${uid()}`, docBody({ tags: "economics" }), 400],
      [`/api/docs/${uid()}`, docBody({ tags: [1] }), 400],
      [`/api/docs/${uid()}`, docBody({ pageCount: "3" }), 400],
      [`/api/docs/${uid()}`, docBody({ markdown: 5 }), 400],
      [`/api/docs/${uid()}`, [docBody()], 400],
    ];
    for (const [path, body, status] of cases) {
      const res = await putJson(path, body);
      expect(res.status, `${path} ${JSON.stringify(body).slice(0, 60)}`).toBe(status);
      expect(((await res.json()) as { error: string }).error).toMatch(/invalid_request|invalid_json/);
    }
    const broken = await call(`/api/docs/${uid()}`, { method: "PUT", headers: bearer(await token()), body: "{nope" });
    expect(broken.status).toBe(400);
    expect(await broken.json()).toEqual({ error: "invalid_json" });
  });

  it("accepts a 64-character doc id", async () => {
    const id = "A-".repeat(32);
    expect((await putJson(`/api/docs/${id}`, docBody())).status).toBe(200);
    expect((await getJson(`/api/docs/${id}`)).status).toBe(200);
  });

  it("refuses markdown over 2 MB with 413", async () => {
    const res = await putJson(`/api/docs/${uid()}`, docBody({ markdown: "x".repeat(2 * 1024 * 1024 + 1) }));
    expect(res.status).toBe(413);
    expect(((await res.json()) as { error: string }).error).toBe("too_large");
    // Multi-byte characters count in bytes.
    const wide = await putJson(`/api/docs/${uid()}`, docBody({ markdown: "é".repeat(1024 * 1024 + 1) }));
    expect(wide.status).toBe(413);
  });

  it("stores markdown that does not fit a D1 row in R2 and returns it whole", async () => {
    const id = uid();
    const big = `# Big\n\n${"Lorem ipsum dolor sit amet. ".repeat(60_000)}needle-at-the-end`;
    expect(new TextEncoder().encode(big).byteLength).toBeGreaterThan(MARKDOWN_INLINE_MAX_BYTES);
    expect((await putJson(`/api/docs/${id}`, docBody({ markdown: big }))).status).toBe(200);
    const { body } = await getJson(`/api/docs/${id}`);
    expect(body.markdown).toBe(big);
    expect(await env.FILES.head(markdownKey(id))).not.toBeNull();
    const row = await env.DB.prepare("SELECT length(markdown) AS n, markdown_external FROM docs WHERE id = ?1")
      .bind(id)
      .first<{ n: number; markdown_external: number }>();
    expect(row?.markdown_external).toBe(1);
    expect(row?.n).toBeLessThanOrEqual(MARKDOWN_INLINE_MAX_BYTES);

    // Shrinking it moves it back inline and removes the R2 copy.
    expect((await putJson(`/api/docs/${id}`, docBody({ markdown: "small now" }))).status).toBe(200);
    expect((await getJson(`/api/docs/${id}`)).body.markdown).toBe("small now");
    expect(await env.FILES.head(markdownKey(id))).toBeNull();
  });
});

describe("list and search", () => {
  it("lists docs newest first with transcript and file counts", async () => {
    const tag = uid("order");
    const older = uid();
    const newer = uid();
    await putJson(`/api/docs/${older}`, docBody({ title: `${tag} older`, modifiedAt: 4_000_000_000_000 }));
    await putJson(`/api/docs/${newer}`, docBody({ title: `${tag} newer`, modifiedAt: 4_000_000_000_001 }));
    await putJson(`/api/docs/${newer}/transcripts/rec-1`, transcriptBody());
    await putJson(`/api/docs/${newer}/transcripts/rec-2`, transcriptBody());
    await putFile(`/api/docs/${newer}/files/notes.md`, "# hi", "text/markdown");

    const { status, body } = await getJson<{ docs: Summary[] }>("/api/docs");
    expect(status).toBe(200);
    const mine = body.docs.filter((d) => d.title.startsWith(tag));
    expect(mine.map((d) => d.id)).toEqual([newer, older]);
    expect(mine[0]).toEqual({
      id: newer,
      title: `${tag} newer`,
      kind: "NOTEBOOK",
      createdAt: 1_760_500_000_000,
      modifiedAt: 4_000_000_000_001,
      folderPath: "Economics / Term 1",
      pageCount: 3,
      transcriptCount: 2,
      fileCount: 1,
    });
    expect(mine[1]?.transcriptCount).toBe(0);
    // Summaries do not carry the heavy fields.
    expect(Object.keys(mine[0] as object)).not.toContain("markdown");
    // Ordering is newest first across the whole list.
    const times = body.docs.map((d) => d.modifiedAt);
    expect([...times].sort((a, b) => b - a)).toEqual(times);
  });

  it("?q= searches title, markdown and transcript text (case-insensitive)", async () => {
    const word = `zq${crypto.randomUUID().slice(0, 8)}`;
    const byTitle = uid();
    const byMarkdown = uid();
    const byTranscript = uid();
    const unrelated = uid();
    await putJson(`/api/docs/${byTitle}`, docBody({ title: `20261015_${word.toUpperCase()} Markets` }));
    await putJson(`/api/docs/${byMarkdown}`, docBody({ markdown: `Notes about ${word} and bonds` }));
    await putJson(`/api/docs/${byTranscript}`, docBody({ markdown: "nothing here" }));
    await putJson(
      `/api/docs/${byTranscript}/transcripts/r1`,
      transcriptBody({ segments: [{ startMs: 0, endMs: 1, text: `the lecturer said ${word}` }] }),
    );
    await putJson(`/api/docs/${unrelated}`, docBody({ markdown: "no match" }));

    const { body } = await getJson<{ docs: Summary[] }>(`/api/docs?q=${encodeURIComponent(word)}`);
    expect(body.docs.map((d) => d.id).sort()).toEqual([byTitle, byMarkdown, byTranscript].sort());
  });

  it("LIKE wildcards in q are literal", async () => {
    const word = `pct${crypto.randomUUID().slice(0, 8)}`;
    const id = uid();
    await putJson(`/api/docs/${id}`, docBody({ markdown: `${word} grew 50% this year` }));
    expect((await getJson<{ docs: Summary[] }>(`/api/docs?q=${encodeURIComponent(`${word} grew 50%`)}`)).body.docs.map((d) => d.id)).toEqual([id]);
    expect((await getJson<{ docs: Summary[] }>(`/api/docs?q=${encodeURIComponent(`${word}_grew`)}`)).body.docs).toEqual([]);
    expect((await getJson<{ docs: Summary[] }>(`/api/docs?q=${encodeURIComponent(`${word}%`)}`)).body.docs).toEqual([]);
  });
});

describe("transcripts", () => {
  it("upserts a transcript and returns it in the detail", async () => {
    const id = uid();
    await putJson(`/api/docs/${id}`, docBody());
    const res = await putJson(`/api/docs/${id}/transcripts/rec-123`, transcriptBody());
    expect(res.status).toBe(200);
    expect(await res.json()).toEqual({ ok: true });

    const { body } = await getJson(`/api/docs/${id}`);
    expect(body.transcriptCount).toBe(1);
    expect(body.transcripts).toEqual([
      {
        recordingId: "rec-123",
        title: "Lecture recording",
        startedAt: 1_760_500_100_000,
        durationMs: 125_000,
        source: "openai:whisper-1",
        segments: [
          { startMs: 0, endMs: 4_000, text: "Welcome to the lecture on yield curves.", speaker: "Lecturer" },
          { startMs: 65_000, endMs: 70_000, text: "Inverted curves often precede recessions.", speaker: null },
        ],
      },
    ]);

    // Same recording id replaces it.
    await putJson(`/api/docs/${id}/transcripts/rec-123`, transcriptBody({ title: "Renamed", segments: [] }));
    const again = await getJson(`/api/docs/${id}`);
    expect(again.body.transcripts).toHaveLength(1);
    expect(again.body.transcripts[0]).toMatchObject({ title: "Renamed", segments: [] });
  });

  it("creates a placeholder doc when the transcript arrives first; PUT doc replaces it", async () => {
    const id = uid();
    await putJson(`/api/docs/${id}/transcripts/r1`, transcriptBody());
    const placeholder = await getJson(`/api/docs/${id}`);
    expect(placeholder.status).toBe(200);
    expect(placeholder.body).toMatchObject({ id, title: "Lecture recording", transcriptCount: 1 });
    await putJson(`/api/docs/${id}`, docBody());
    const real = await getJson(`/api/docs/${id}`);
    expect(real.body).toMatchObject({ title: "20261015_Financial Markets", transcriptCount: 1 });
  });

  it("validates transcripts", async () => {
    const id = uid();
    const bad: unknown[] = [
      transcriptBody({ segments: "nope" }),
      transcriptBody({ segments: [{ startMs: 0, endMs: 1 }] }),
      transcriptBody({ segments: [{ startMs: -5, endMs: 1, text: "x" }] }),
      transcriptBody({ segments: [{ startMs: 0, endMs: 1, text: "x", speaker: 3 }] }),
      transcriptBody({ durationMs: null }),
      transcriptBody({ source: undefined }),
    ];
    for (const b of bad) expect((await putJson(`/api/docs/${id}/transcripts/r1`, b)).status).toBe(400);
    expect((await putJson(`/api/docs/${id}/transcripts/bad id`, transcriptBody())).status).toBe(400);
    expect((await putJson(`/api/docs/${id}/transcripts/..`, transcriptBody())).status).not.toBe(200);
  });

  it("DELETE removes a single transcript", async () => {
    const id = uid();
    await putJson(`/api/docs/${id}/transcripts/r1`, transcriptBody());
    await putJson(`/api/docs/${id}/transcripts/r2`, transcriptBody());
    const res = await call(`/api/docs/${id}/transcripts/r1`, { method: "DELETE", headers: bearer(await token()) });
    expect(res.status).toBe(200);
    const { body } = await getJson(`/api/docs/${id}`);
    expect(body.transcripts.map((t: { recordingId: string }) => t.recordingId)).toEqual(["r2"]);
  });
});

describe("doc delete", () => {
  it("removes the doc, its transcripts, file rows and R2 objects", async () => {
    const id = uid();
    const neighbour = `${id}0`;
    await putJson(`/api/docs/${id}`, docBody());
    await putJson(`/api/docs/${id}/transcripts/r1`, transcriptBody());
    await putFile(`/api/docs/${id}/files/notes.pdf`, "%PDF-1.7 test", "application/pdf");
    await putFile(`/api/docs/${id}/files/rec_r1.m4a`, new Uint8Array([0, 1, 2, 3]), "audio/mp4");
    await putFile(`/api/docs/${neighbour}/files/notes.pdf`, "other doc", "application/pdf");
    expect(await env.FILES.head(`${id}/notes.pdf`)).not.toBeNull();

    const res = await call(`/api/docs/${id}`, { method: "DELETE", headers: bearer(await token()) });
    expect(res.status).toBe(200);
    expect(await res.json()).toEqual({ ok: true });

    expect((await getJson(`/api/docs/${id}`)).status).toBe(404);
    const counts = await env.DB.prepare(
      `SELECT (SELECT COUNT(*) FROM docs WHERE id = ?1) AS d,
              (SELECT COUNT(*) FROM transcripts WHERE doc_id = ?1) AS t,
              (SELECT COUNT(*) FROM files WHERE doc_id = ?1) AS f`,
    )
      .bind(id)
      .first();
    expect(counts).toEqual({ d: 0, t: 0, f: 0 });
    expect((await env.FILES.list({ prefix: `${id}/` })).objects).toEqual([]);
    // A doc whose id merely starts with the deleted id is untouched.
    expect(await env.FILES.head(`${neighbour}/notes.pdf`)).not.toBeNull();

    // Deleting again is harmless (idempotent for retries).
    expect((await call(`/api/docs/${id}`, { method: "DELETE", headers: bearer(await token()) })).status).toBe(200);
  });
});
