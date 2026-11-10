import { env } from "cloudflare:workers";
import type { Env } from "../src/env";
import { handle } from "../src/index";

export const LINK_ID = env.NOTES_LINK_ID as string;
export const PASSWORD = env.NOTES_PASSWORD as string;
export const ORIGIN = "https://vault.example.test";
export const BASE = `${ORIGIN}/notes/${LINK_ID}`;

export interface CallInit extends RequestInit {
  env?: Env;
}

/** Calls the Worker's fetch handler directly. `path` is relative to the private base URL. */
export function call(path: string, init: CallInit = {}): Promise<Response> {
  const { env: e = env, ...rest } = init;
  const url = /^https?:\/\//.test(path) ? path : BASE + path;
  return handle(new Request(url, rest), e);
}

export function uniqueIp(): string {
  const b = crypto.getRandomValues(new Uint8Array(3));
  return `10.${b[0]}.${b[1]}.${b[2]}`;
}

export function uid(prefix = "doc"): string {
  return `${prefix}-${crypto.randomUUID()}`;
}

export function loginRequest(password: string, ip: string, e: Env = env): Promise<Response> {
  return call("/api/session", {
    method: "POST",
    headers: { "Content-Type": "application/json", "CF-Connecting-IP": ip },
    body: JSON.stringify({ password }),
    env: e,
  });
}

export async function login(e: Env = env): Promise<{ token: string; cookie: string; expiresAt: number }> {
  const res = await loginRequest(e.NOTES_PASSWORD as string, uniqueIp(), e);
  if (res.status !== 200) throw new Error(`login failed: ${res.status}`);
  const data = (await res.json()) as { token: string; expiresAt: number };
  return { token: data.token, expiresAt: data.expiresAt, cookie: `nv_session=${data.token}` };
}

export const bearer = (token: string): Record<string, string> => ({ Authorization: `Bearer ${token}` });

let cachedToken: string | null = null;
/** A valid bearer token for the default test secrets (cached per test file). */
export async function token(): Promise<string> {
  if (!cachedToken) cachedToken = (await login()).token;
  return cachedToken;
}

export async function putJson(path: string, body: unknown, headers: Record<string, string> = {}): Promise<Response> {
  return call(path, {
    method: "PUT",
    headers: { "Content-Type": "application/json", ...bearer(await token()), ...headers },
    body: JSON.stringify(body),
  });
}

export async function getJson<T = any>(path: string): Promise<{ status: number; body: T }> {
  const res = await call(path, { headers: bearer(await token()) });
  return { status: res.status, body: (await res.json()) as T };
}

export function docBody(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    title: "20261015_Financial Markets",
    kind: "NOTEBOOK",
    createdAt: 1_760_500_000_000,
    modifiedAt: 1_760_510_000_000,
    folderPath: "Economics / Term 1",
    pageCount: 3,
    tags: ["economics", "lecture"],
    markdown: "# Financial Markets\n\nBond yields and **duration**.",
    ...overrides,
  };
}

export function transcriptBody(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    title: "Lecture recording",
    startedAt: 1_760_500_100_000,
    durationMs: 125_000,
    source: "openai:whisper-1",
    segments: [
      { startMs: 0, endMs: 4_000, text: "Welcome to the lecture on yield curves.", speaker: "Lecturer" },
      { startMs: 65_000, endMs: 70_000, text: "Inverted curves often precede recessions.", speaker: null },
    ],
    ...overrides,
  };
}
