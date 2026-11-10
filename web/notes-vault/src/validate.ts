import { utf8 } from "./crypto";
import { HttpError, badRequest } from "./http";

export const DOC_ID_RE = /^[A-Za-z0-9-]{1,64}$/;
export const FILE_NAME_RE = /^[A-Za-z0-9._-]{1,120}$/;
/** Recording ids end up in file names (`rec_<recordingId>.m4a`), so the same alphabet. */
export const RECORDING_ID_RE = /^[A-Za-z0-9._-]{1,100}$/;
export const DOC_KINDS = ["NOTEBOOK", "PDF", "WHITEBOARD", "TYPED"] as const;
export type DocKind = (typeof DOC_KINDS)[number];

export const MAX_MARKDOWN_BYTES = 2 * 1024 * 1024;
export const MAX_FILE_BYTES = 95 * 1024 * 1024;
/** D1 rows are limited to 2,000,000 bytes; keep transcripts comfortably below that. */
export const MAX_TRANSCRIPT_ROW_BYTES = 1_900_000;
export const MAX_DOC_BODY_BYTES = 16 * 1024 * 1024;
export const MAX_TRANSCRIPT_BODY_BYTES = 8 * 1024 * 1024;
const MAX_TIMESTAMP = 8.64e15;

export interface DocUpsert {
  title: string;
  kind: DocKind;
  createdAt: number;
  modifiedAt: number;
  folderPath: string | null;
  pageCount: number;
  tags: string[];
  markdown: string;
}

export interface Segment {
  startMs: number;
  endMs: number;
  text: string;
  speaker: string | null;
}

export interface TranscriptUpsert {
  title: string;
  startedAt: number;
  durationMs: number;
  source: string;
  segments: Segment[];
}

const notDots = (s: string) => !/^\.+$/.test(s);

export function validDocId(id: string): boolean {
  return DOC_ID_RE.test(id);
}

export function validFileName(name: string): boolean {
  return FILE_NAME_RE.test(name) && notDots(name);
}

export function validRecordingId(id: string): boolean {
  return RECORDING_ID_RE.test(id) && notDots(id);
}

function obj(value: unknown, what: string): Record<string, unknown> {
  if (typeof value !== "object" || value === null || Array.isArray(value)) throw badRequest(`${what} must be an object`);
  return value as Record<string, unknown>;
}

/** Strips NUL characters, which SQLite text functions treat as terminators. */
const clean = (s: string) => (s.includes("\u0000") ? s.replace(/\u0000/g, "") : s);

function str(o: Record<string, unknown>, key: string, max: number, opts: { optional?: boolean } = {}): string | undefined {
  const v = o[key];
  if (v === undefined || v === null) {
    if (opts.optional) return undefined;
    throw badRequest(`${key} is required`);
  }
  if (typeof v !== "string") throw badRequest(`${key} must be a string`);
  if (v.length > max) throw badRequest(`${key} is longer than ${max} characters`);
  return clean(v);
}

function num(o: Record<string, unknown>, key: string, opts: { optional?: boolean; max?: number } = {}): number | undefined {
  const v = o[key];
  if (v === undefined || v === null) {
    if (opts.optional) return undefined;
    throw badRequest(`${key} is required`);
  }
  const max = opts.max ?? MAX_TIMESTAMP;
  if (typeof v !== "number" || !Number.isFinite(v) || v < 0 || v > max) {
    throw badRequest(`${key} must be a number between 0 and ${max}`);
  }
  return Math.round(v);
}

export function parseDocUpsert(body: unknown): DocUpsert {
  const o = obj(body, "body");
  const title = str(o, "title", 500) as string;
  const kind = o.kind;
  if (typeof kind !== "string" || !(DOC_KINDS as readonly string[]).includes(kind)) {
    throw badRequest(`kind must be one of ${DOC_KINDS.join(", ")}`);
  }
  const createdAt = num(o, "createdAt") as number;
  const modifiedAt = num(o, "modifiedAt") as number;
  const folderPath = str(o, "folderPath", 1000, { optional: true }) ?? null;
  const pageCount = num(o, "pageCount", { optional: true, max: 1_000_000 }) ?? 0;
  let tags: string[] = [];
  if (o.tags !== undefined && o.tags !== null) {
    if (!Array.isArray(o.tags) || o.tags.length > 200) throw badRequest("tags must be an array of at most 200 strings");
    tags = o.tags.map((t) => {
      if (typeof t !== "string" || t.length > 200) throw badRequest("each tag must be a string of at most 200 characters");
      return clean(t);
    });
  }
  // Length is enforced in bytes below (413), the request body limit bounds the string.
  const markdown = str(o, "markdown", Number.MAX_SAFE_INTEGER, { optional: true }) ?? "";
  if (utf8(markdown).byteLength > MAX_MARKDOWN_BYTES) {
    throw new HttpError(413, "too_large", "markdown exceeds 2 MB");
  }
  return { title, kind: kind as DocKind, createdAt, modifiedAt, folderPath, pageCount, tags, markdown };
}

export function parseTranscriptUpsert(body: unknown): TranscriptUpsert {
  const o = obj(body, "body");
  const title = str(o, "title", 500) as string;
  const startedAt = num(o, "startedAt") as number;
  const durationMs = num(o, "durationMs") as number;
  const source = str(o, "source", 200) as string;
  if (!Array.isArray(o.segments)) throw badRequest("segments must be an array");
  if (o.segments.length > 200_000) throw badRequest("too many segments");
  const segments = o.segments.map((raw, i): Segment => {
    const s = obj(raw, `segments[${i}]`);
    return {
      startMs: num(s, "startMs") as number,
      endMs: num(s, "endMs") as number,
      text: str(s, "text", 100_000) as string,
      speaker: str(s, "speaker", 200, { optional: true }) ?? null,
    };
  });
  return { title, startedAt, durationMs, source, segments };
}

/** Plain text of a transcript, stored alongside the segments for search. */
export function transcriptText(t: TranscriptUpsert): string {
  return t.segments.map((s) => s.text).join("\n");
}

const CONTENT_TYPE_RE = /^[A-Za-z0-9][A-Za-z0-9!#$&^_.+-]{0,126}\/[A-Za-z0-9][A-Za-z0-9!#$&^_.+-]{0,126}(?:\s*;[\x20-\x7e]{0,200})?$/;

export function parseContentType(header: string | null): string {
  if (header === null || header.trim() === "") return "application/octet-stream";
  const value = header.trim();
  if (!CONTENT_TYPE_RE.test(value)) throw badRequest("Content-Type is not a valid media type");
  return value;
}

/** Escapes LIKE wildcards so a search for "50%" means the literal text. */
export function likePattern(q: string): string {
  return `%${q.replace(/[\\%_]/g, (c) => `\\${c}`)}%`;
}
