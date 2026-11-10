import { utf8 } from "./crypto";
import type { Env } from "./env";
import { HttpError, badRequest, json, readJson } from "./http";
import {
  MAX_DOC_BODY_BYTES,
  MAX_FILE_BYTES,
  MAX_TRANSCRIPT_BODY_BYTES,
  MAX_TRANSCRIPT_ROW_BYTES,
  type Segment,
  likePattern,
  parseContentType,
  parseDocUpsert,
  parseTranscriptUpsert,
  transcriptText,
  validDocId,
  validFileName,
  validRecordingId,
} from "./validate";

export const VAULT_NAME = "Your Pit Box · Notes";

/**
 * D1 rows are limited to 2,000,000 bytes. Markdown up to this size is stored inline;
 * larger markdown (the contract allows 2 MB) is kept whole in R2 under a key no file can
 * collide with (doc ids cannot contain "_"), while D1 keeps this many leading bytes for
 * search.
 */
export const MARKDOWN_INLINE_MAX_BYTES = 1_500_000;
export const markdownKey = (docId: string) => `_markdown/${docId}.md`;
export const fileKey = (docId: string, name: string) => `${docId}/${name}`;
/** Kind given to a doc row created implicitly by a transcript/file upload (replaced by PUT doc). */
const PLACEHOLDER_KIND = "NOTEBOOK";
const LIST_LIMIT = 2000;

const notFound = () => new HttpError(404, "not_found");

function allow(method: string, allowed: string[]): void {
  if (!allowed.includes(method)) {
    throw new HttpError(405, "method_not_allowed", undefined, { Allow: allowed.join(", ") });
  }
}

interface SummaryRow {
  id: string;
  title: string;
  kind: string;
  created_at: number;
  modified_at: number;
  folder_path: string | null;
  page_count: number;
  transcript_count: number;
  file_count: number;
}

interface DetailRow extends SummaryRow {
  tags: string;
  markdown: string;
  markdown_external: number;
}

interface TranscriptRow {
  recording_id: string;
  title: string;
  started_at: number;
  duration_ms: number;
  source: string;
  segments: string;
}

interface FileRow {
  name: string;
  content_type: string;
  size: number;
  uploaded_at: number;
}

const SUMMARY_COLUMNS = `d.id, d.title, d.kind, d.created_at, d.modified_at, d.folder_path, d.page_count,
  (SELECT COUNT(*) FROM transcripts t WHERE t.doc_id = d.id) AS transcript_count,
  (SELECT COUNT(*) FROM files f WHERE f.doc_id = d.id) AS file_count`;

function summary(row: SummaryRow) {
  return {
    id: row.id,
    title: row.title,
    kind: row.kind,
    createdAt: row.created_at,
    modifiedAt: row.modified_at,
    folderPath: row.folder_path,
    pageCount: row.page_count,
    transcriptCount: row.transcript_count,
    fileCount: row.file_count,
  };
}

function parseJsonColumn<T>(text: string, fallback: T): T {
  try {
    return JSON.parse(text) as T;
  } catch {
    return fallback;
  }
}

function utf8Prefix(text: string, maxBytes: number): string {
  const bytes = utf8(text);
  if (bytes.byteLength <= maxBytes) return text;
  let end = maxBytes;
  // Never cut inside a multi-byte sequence: back up while the first excluded byte is a continuation byte.
  while (end > 0 && ((bytes[end] as number) & 0xc0) === 0x80) end--;
  return new TextDecoder().decode(bytes.subarray(0, end));
}

function placeholderDoc(db: D1Database, docId: string, title: string, createdAt: number, now: number) {
  return db
    .prepare(
      `INSERT INTO docs (id, title, kind, created_at, modified_at, folder_path, page_count, tags, markdown, markdown_external, updated_at)
       VALUES (?1, ?2, ?3, ?4, ?4, NULL, 0, '[]', '', 0, ?5)
       ON CONFLICT(id) DO NOTHING`,
    )
    .bind(docId, title, PLACEHOLDER_KIND, createdAt, now);
}

// ---------------------------------------------------------------------------------------
// Docs

async function listDocs(env: Env, url: URL): Promise<Response> {
  const q = (url.searchParams.get("q") ?? "").trim().slice(0, 200);
  let stmt: D1PreparedStatement;
  if (q) {
    stmt = env.DB.prepare(
      `SELECT ${SUMMARY_COLUMNS} FROM docs d
       WHERE d.title LIKE ?1 ESCAPE '\\'
          OR d.markdown LIKE ?1 ESCAPE '\\'
          OR EXISTS (SELECT 1 FROM transcripts t WHERE t.doc_id = d.id AND (t.text LIKE ?1 ESCAPE '\\' OR t.title LIKE ?1 ESCAPE '\\'))
       ORDER BY d.modified_at DESC, d.created_at DESC, d.id ASC
       LIMIT ${LIST_LIMIT}`,
    ).bind(likePattern(q));
  } else {
    stmt = env.DB.prepare(
      `SELECT ${SUMMARY_COLUMNS} FROM docs d
       ORDER BY d.modified_at DESC, d.created_at DESC, d.id ASC
       LIMIT ${LIST_LIMIT}`,
    );
  }
  const { results } = await stmt.all<SummaryRow>();
  return json({ docs: results.map(summary) });
}

async function getDoc(env: Env, docId: string): Promise<Response> {
  const [docRes, trRes, fileRes] = await env.DB.batch<unknown>([
    env.DB.prepare(`SELECT ${SUMMARY_COLUMNS}, d.tags, d.markdown, d.markdown_external FROM docs d WHERE d.id = ?1`).bind(docId),
    env.DB.prepare(
      "SELECT recording_id, title, started_at, duration_ms, source, segments FROM transcripts WHERE doc_id = ?1 ORDER BY started_at, recording_id",
    ).bind(docId),
    env.DB.prepare("SELECT name, content_type, size, uploaded_at FROM files WHERE doc_id = ?1 ORDER BY name").bind(docId),
  ]);
  const row = docRes?.results[0] as DetailRow | undefined;
  if (!row) throw notFound();
  let markdown = row.markdown;
  if (row.markdown_external) {
    const obj = await env.FILES.get(markdownKey(docId));
    if (obj) markdown = await obj.text();
  }
  const transcripts = ((trRes?.results ?? []) as TranscriptRow[]).map((t) => ({
    recordingId: t.recording_id,
    title: t.title,
    startedAt: t.started_at,
    durationMs: t.duration_ms,
    source: t.source,
    segments: parseJsonColumn<Segment[]>(t.segments, []),
  }));
  const files = ((fileRes?.results ?? []) as FileRow[]).map((f) => ({
    name: f.name,
    contentType: f.content_type,
    size: f.size,
    uploadedAt: f.uploaded_at,
  }));
  return json({
    ...summary(row),
    transcriptCount: transcripts.length,
    fileCount: files.length,
    tags: parseJsonColumn<string[]>(row.tags, []),
    markdown,
    transcripts,
    files,
  });
}

async function putDoc(request: Request, env: Env, docId: string): Promise<Response> {
  const doc = parseDocUpsert(await readJson(request, MAX_DOC_BODY_BYTES));
  const now = Date.now();
  const external = utf8(doc.markdown).byteLength > MARKDOWN_INLINE_MAX_BYTES;
  const prev = await env.DB.prepare("SELECT markdown_external FROM docs WHERE id = ?1")
    .bind(docId)
    .first<{ markdown_external: number }>();
  if (external) {
    await env.FILES.put(markdownKey(docId), doc.markdown, {
      httpMetadata: { contentType: "text/markdown; charset=utf-8" },
    });
  }
  await env.DB.prepare(
    `INSERT INTO docs (id, title, kind, created_at, modified_at, folder_path, page_count, tags, markdown, markdown_external, updated_at)
     VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11)
     ON CONFLICT(id) DO UPDATE SET
       title = excluded.title, kind = excluded.kind, created_at = excluded.created_at,
       modified_at = excluded.modified_at, folder_path = excluded.folder_path,
       page_count = excluded.page_count, tags = excluded.tags, markdown = excluded.markdown,
       markdown_external = excluded.markdown_external, updated_at = excluded.updated_at`,
  )
    .bind(
      docId,
      doc.title,
      doc.kind,
      doc.createdAt,
      doc.modifiedAt,
      doc.folderPath,
      doc.pageCount,
      JSON.stringify(doc.tags),
      external ? utf8Prefix(doc.markdown, MARKDOWN_INLINE_MAX_BYTES) : doc.markdown,
      external ? 1 : 0,
      now,
    )
    .run();
  if (!external && prev?.markdown_external) await env.FILES.delete(markdownKey(docId));
  return json({ ok: true });
}

async function deleteR2Prefix(bucket: R2Bucket, prefix: string): Promise<void> {
  let cursor: string | undefined;
  for (;;) {
    const page = await bucket.list({ prefix, cursor, limit: 1000 });
    if (page.objects.length) await bucket.delete(page.objects.map((o) => o.key));
    if (!page.truncated) break;
    cursor = page.cursor;
  }
}

async function deleteDoc(env: Env, docId: string): Promise<Response> {
  await env.DB.batch([
    env.DB.prepare("DELETE FROM transcripts WHERE doc_id = ?1").bind(docId),
    env.DB.prepare("DELETE FROM files WHERE doc_id = ?1").bind(docId),
    env.DB.prepare("DELETE FROM docs WHERE id = ?1").bind(docId),
  ]);
  // R2 is cleaned by prefix (not from the rows above) so a retried DELETE also removes
  // objects whose rows are already gone.
  await deleteR2Prefix(env.FILES, `${docId}/`);
  await env.FILES.delete(markdownKey(docId));
  return json({ ok: true });
}

// ---------------------------------------------------------------------------------------
// Transcripts

async function putTranscript(request: Request, env: Env, docId: string, recordingId: string): Promise<Response> {
  const t = parseTranscriptUpsert(await readJson(request, MAX_TRANSCRIPT_BODY_BYTES));
  const segments = JSON.stringify(t.segments);
  const text = transcriptText(t);
  const rowBytes =
    utf8(segments).byteLength + utf8(text).byteLength + utf8(t.title).byteLength + utf8(t.source).byteLength;
  if (rowBytes > MAX_TRANSCRIPT_ROW_BYTES) throw new HttpError(413, "too_large", "transcript is too large");
  const now = Date.now();
  await env.DB.batch([
    placeholderDoc(env.DB, docId, t.title || docId, t.startedAt, now),
    env.DB.prepare(
      `INSERT INTO transcripts (doc_id, recording_id, title, started_at, duration_ms, source, segments, text, updated_at)
       VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9)
       ON CONFLICT(doc_id, recording_id) DO UPDATE SET
         title = excluded.title, started_at = excluded.started_at, duration_ms = excluded.duration_ms,
         source = excluded.source, segments = excluded.segments, text = excluded.text,
         updated_at = excluded.updated_at`,
    ).bind(docId, recordingId, t.title, t.startedAt, t.durationMs, t.source, segments, text, now),
  ]);
  return json({ ok: true });
}

async function deleteTranscript(env: Env, docId: string, recordingId: string): Promise<Response> {
  await env.DB.prepare("DELETE FROM transcripts WHERE doc_id = ?1 AND recording_id = ?2")
    .bind(docId, recordingId)
    .run();
  return json({ ok: true });
}

// ---------------------------------------------------------------------------------------
// Files

async function putFile(request: Request, env: Env, docId: string, name: string): Promise<Response> {
  const declared = request.headers.get("Content-Length");
  if (declared === null) throw new HttpError(411, "length_required");
  if (!/^\d{1,12}$/.test(declared.trim())) throw badRequest("invalid Content-Length");
  const length = Number(declared.trim());
  if (length > MAX_FILE_BYTES) throw new HttpError(413, "too_large", "files are limited to 95 MB");
  const contentType = parseContentType(request.headers.get("Content-Type"));
  const key = fileKey(docId, name);
  const httpMetadata = { contentType };
  if (length === 0 || !request.body) {
    if (length !== 0) throw badRequest("body length does not match Content-Length");
    await env.FILES.put(key, new Uint8Array(0), { httpMetadata });
  } else {
    // Stream straight into R2; FixedLengthStream gives R2 the known length it needs and
    // fails the upload if the body is shorter or longer than declared.
    const { readable, writable } = new FixedLengthStream(length);
    const [piped, stored] = await Promise.allSettled([
      request.body.pipeTo(writable),
      env.FILES.put(key, readable, { httpMetadata }),
    ]);
    if (piped.status === "rejected" || stored.status === "rejected") {
      throw badRequest("body length does not match Content-Length");
    }
  }
  const now = Date.now();
  await env.DB.batch([
    placeholderDoc(env.DB, docId, docId, now, now),
    env.DB.prepare(
      `INSERT INTO files (doc_id, name, content_type, size, uploaded_at) VALUES (?1, ?2, ?3, ?4, ?5)
       ON CONFLICT(doc_id, name) DO UPDATE SET
         content_type = excluded.content_type, size = excluded.size, uploaded_at = excluded.uploaded_at`,
    ).bind(docId, name, contentType, length, now),
  ]);
  return json({ ok: true });
}

type Range = { offset: number; length: number };

/** Parses a single `bytes=` range (RFC 9110). Malformed or multi-range headers are ignored. */
export function parseRange(header: string | null, size: number): Range | "unsatisfiable" | null {
  if (!header) return null;
  const m = /^bytes=(\d{0,15})-(\d{0,15})$/.exec(header.trim());
  if (!m || (m[1] === "" && m[2] === "")) return null;
  let start: number;
  let end: number;
  if (m[1] === "") {
    const suffix = Number(m[2]);
    if (suffix === 0) return "unsatisfiable";
    start = Math.max(0, size - suffix);
    end = size - 1;
  } else {
    start = Number(m[1]);
    if (m[2] !== "" && Number(m[2]) < start) return null;
    end = m[2] === "" ? size - 1 : Math.min(Number(m[2]), size - 1);
  }
  if (size === 0 || start >= size) return "unsatisfiable";
  return { offset: start, length: end - start + 1 };
}

async function getFile(request: Request, env: Env, docId: string, name: string, headOnly: boolean): Promise<Response> {
  const key = fileKey(docId, name);
  const rangeHeader = request.headers.get("Range");
  const meta = headOnly || rangeHeader ? await env.FILES.head(key) : null;
  if ((headOnly || rangeHeader) && !meta) throw notFound();
  let range: Range | "unsatisfiable" | null = null;
  if (meta && rangeHeader) range = parseRange(rangeHeader, meta.size);
  const headers = new Headers({
    "Accept-Ranges": "bytes",
    "Content-Disposition": `attachment; filename="${name}"`,
  });
  if (range === "unsatisfiable") {
    headers.set("Content-Range", `bytes */${(meta as R2Object).size}`);
    return json({ error: "range_not_satisfiable" }, 416, headers);
  }
  let obj: R2Object | R2ObjectBody | null = meta;
  let body: ReadableStream | null = null;
  if (!headOnly) {
    const got = await env.FILES.get(key, range ? { range } : undefined);
    if (!got) throw notFound();
    obj = got;
    body = got.body;
  }
  const o = obj as R2Object;
  headers.set("Content-Type", o.httpMetadata?.contentType ?? "application/octet-stream");
  headers.set("ETag", o.httpEtag);
  headers.set("Last-Modified", o.uploaded.toUTCString());
  if (range) {
    headers.set("Content-Range", `bytes ${range.offset}-${range.offset + range.length - 1}/${o.size}`);
    headers.set("Content-Length", String(range.length));
    return new Response(body, { status: 206, headers });
  }
  headers.set("Content-Length", String(o.size));
  return new Response(body, { status: 200, headers });
}

async function deleteFile(env: Env, docId: string, name: string): Promise<Response> {
  await env.FILES.delete(fileKey(docId, name));
  await env.DB.prepare("DELETE FROM files WHERE doc_id = ?1 AND name = ?2").bind(docId, name).run();
  return json({ ok: true });
}

// ---------------------------------------------------------------------------------------
// Router for {base}/api/* (authentication already done by the caller)

export async function handleApi(request: Request, env: Env, parts: string[], url: URL): Promise<Response> {
  const method = request.method.toUpperCase();
  const headOnly = method === "HEAD";
  const m = headOnly ? "GET" : method;
  const [resource, docId, sub, subId] = parts;

  if (parts.length === 1 && resource === "ping") {
    allow(m, ["GET"]);
    return json({ ok: true, name: VAULT_NAME });
  }
  if (resource !== "docs" || parts.length > 4 || parts.length === 3) throw notFound();
  if (parts.length === 1) {
    allow(m, ["GET"]);
    return listDocs(env, url);
  }
  if (!validDocId(docId as string)) throw badRequest("docId must match [A-Za-z0-9-]{1,64}");
  const id = docId as string;
  if (parts.length === 2) {
    allow(m, ["GET", "PUT", "DELETE"]);
    if (m === "GET") return getDoc(env, id);
    if (m === "PUT") return putDoc(request, env, id);
    return deleteDoc(env, id);
  }
  if (sub === "transcripts") {
    if (!validRecordingId(subId as string)) throw badRequest("recordingId must match [A-Za-z0-9._-]{1,100}");
    allow(m, ["PUT", "DELETE"]);
    if (m === "PUT") return putTranscript(request, env, id, subId as string);
    return deleteTranscript(env, id, subId as string);
  }
  if (sub === "files") {
    if (!validFileName(subId as string)) throw badRequest("name must match [A-Za-z0-9._-]{1,120}");
    allow(m, ["GET", "PUT", "DELETE"]);
    if (m === "GET") return getFile(request, env, id, subId as string, headOnly);
    if (m === "PUT") return putFile(request, env, id, subId as string);
    return deleteFile(env, id, subId as string);
  }
  throw notFound();
}
