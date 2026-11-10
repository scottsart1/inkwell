import {
  base64UrlDecode,
  base64UrlEncode,
  constantTimeEqual,
  hmacKey,
  secretEquals,
  sha256,
  toHex,
  utf8,
} from "./crypto";
import type { Env } from "./env";
import { HttpError, badRequest, json, readJson } from "./http";

export const SESSION_COOKIE = "nv_session";
export const TOKEN_TTL_MS = 30 * 24 * 60 * 60 * 1000;
export const LOCKOUT_WINDOW_MS = 15 * 60 * 1000;
/** Failed logins allowed per IP within the window; the next attempt is refused with 429. */
export const MAX_FAILURES = 8;
const MAX_TOKEN_LENGTH = 512;

export interface Secrets {
  linkId: string;
  password: string;
  sessionSecret: string;
}

export function getSecrets(env: Env): Secrets | null {
  const linkId = env.NOTES_LINK_ID ?? "";
  const password = env.NOTES_PASSWORD ?? "";
  const sessionSecret = env.SESSION_SECRET ?? "";
  if (!linkId || !password || !sessionSecret) return null;
  return { linkId, password, sessionSecret };
}

/** First 8 bytes (hex) of SHA-256(password + secret): changes whenever the password does. */
export async function passwordVersion(secrets: Secrets): Promise<string> {
  const digest = await sha256(secrets.password + secrets.sessionSecret);
  return toHex(digest.slice(0, 8));
}

/** Token = base64url(JSON {exp, pv}) + "." + base64url(HMAC-SHA256(SESSION_SECRET, payload)). */
export async function issueToken(
  secrets: Secrets,
  now: number = Date.now(),
): Promise<{ token: string; expiresAt: number }> {
  const exp = now + TOKEN_TTL_MS;
  const payload = base64UrlEncode(utf8(JSON.stringify({ exp, pv: await passwordVersion(secrets) })));
  const key = await hmacKey(secrets.sessionSecret);
  const sig = new Uint8Array(await crypto.subtle.sign("HMAC", key, utf8(payload)));
  return { token: `${payload}.${base64UrlEncode(sig)}`, expiresAt: exp };
}

export async function verifyToken(token: string, secrets: Secrets, now: number = Date.now()): Promise<boolean> {
  if (!token || token.length > MAX_TOKEN_LENGTH) return false;
  const parts = token.split(".");
  if (parts.length !== 2) return false;
  const [payload, sigText] = parts as [string, string];
  const sig = base64UrlDecode(sigText);
  if (!sig || sig.length !== 32) return false;
  const key = await hmacKey(secrets.sessionSecret);
  // crypto.subtle.verify compares the MAC in constant time.
  if (!(await crypto.subtle.verify("HMAC", key, sig, utf8(payload)))) return false;
  const raw = base64UrlDecode(payload);
  if (!raw) return false;
  let claims: unknown;
  try {
    claims = JSON.parse(new TextDecoder().decode(raw));
  } catch {
    return false;
  }
  if (typeof claims !== "object" || claims === null) return false;
  const { exp, pv } = claims as { exp?: unknown; pv?: unknown };
  if (typeof exp !== "number" || !Number.isFinite(exp) || exp <= now) return false;
  if (typeof pv !== "string") return false;
  return constantTimeEqual(utf8(pv), utf8(await passwordVersion(secrets)));
}

export function getCookie(request: Request, name: string): string | null {
  const header = request.headers.get("Cookie");
  if (!header) return null;
  for (const part of header.split(";")) {
    const eq = part.indexOf("=");
    if (eq === -1) continue;
    if (part.slice(0, eq).trim() === name) return part.slice(eq + 1).trim();
  }
  return null;
}

function cookiePath(secrets: Secrets): string {
  return `/notes/${secrets.linkId}`;
}

export function sessionCookie(token: string, secrets: Secrets): string {
  return `${SESSION_COOKIE}=${token}; Max-Age=${TOKEN_TTL_MS / 1000}; Path=${cookiePath(secrets)}; HttpOnly; Secure; SameSite=Strict`;
}

export function clearedSessionCookie(secrets: Secrets): string {
  return `${SESSION_COOKIE}=; Max-Age=0; Path=${cookiePath(secrets)}; HttpOnly; Secure; SameSite=Strict`;
}

export type AuthVia = "bearer" | "cookie";

/**
 * Bearer token (app) takes precedence; a present but invalid Authorization header never
 * falls back to the cookie. Returns how the request authenticated, or null.
 */
export async function authenticate(request: Request, secrets: Secrets): Promise<AuthVia | null> {
  const authz = request.headers.get("Authorization");
  if (authz !== null) {
    const m = /^Bearer[ ]+(\S+)\s*$/i.exec(authz);
    return m && (await verifyToken(m[1] as string, secrets)) ? "bearer" : null;
  }
  const cookie = getCookie(request, SESSION_COOKIE);
  if (cookie && (await verifyToken(cookie, secrets))) return "cookie";
  return null;
}

/** True when the request carries an Origin header equal to the request's own origin. */
export function sameOrigin(request: Request): boolean {
  const origin = request.headers.get("Origin");
  if (!origin) return false;
  return origin === new URL(request.url).origin;
}

/**
 * Lockout bucket for an address: IPv4 addresses as-is, IPv6 addresses by their /64
 * prefix (one subscriber typically owns a whole /64).
 */
export function clientBucket(request: Request): string {
  const ip = (request.headers.get("CF-Connecting-IP") ?? "").trim().toLowerCase().slice(0, 100);
  if (!ip) return "unknown";
  if (!ip.includes(":")) return ip;
  const mapped = /^::ffff:(\d{1,3}(?:\.\d{1,3}){3})$/.exec(ip);
  if (mapped) return mapped[1] as string;
  const halves = (ip.split("%")[0] as string).split("::");
  if (halves.length > 2) return ip;
  const head = halves[0] ? halves[0].split(":") : [];
  const tail = halves.length === 2 && halves[1] ? halves[1].split(":") : [];
  const fill = halves.length === 2 ? 8 - head.length - tail.length : 0;
  if (fill < 0) return ip;
  const groups = [...head, ...new Array<string>(fill).fill("0"), ...tail];
  if (groups.length !== 8 || !groups.every((g) => /^[0-9a-f]{1,4}$/.test(g))) return ip;
  return `${groups
    .slice(0, 4)
    .map((g) => parseInt(g, 16).toString(16))
    .join(":")}::/64`;
}

interface AttemptRow {
  window_start: number;
  count: number;
}

/**
 * Counts this attempt atomically before the password is checked, so parallel guesses
 * cannot slip past the limit; a successful login deletes the row again.
 */
async function countAttempt(db: D1Database, bucket: string, now: number): Promise<AttemptRow> {
  const expired = now - LOCKOUT_WINDOW_MS;
  const [, upsert] = await db.batch<AttemptRow>([
    db.prepare("DELETE FROM login_attempts WHERE window_start <= ?1 AND ip <> ?2").bind(expired, bucket),
    db
      .prepare(
        `INSERT INTO login_attempts (ip, window_start, count) VALUES (?1, ?2, 1)
         ON CONFLICT(ip) DO UPDATE SET
           window_start = CASE WHEN login_attempts.window_start <= ?3 THEN ?2 ELSE login_attempts.window_start END,
           count = CASE WHEN login_attempts.window_start <= ?3 THEN 1 ELSE login_attempts.count + 1 END
         RETURNING window_start, count`,
      )
      .bind(bucket, now, expired),
  ]);
  const row = upsert?.results?.[0];
  if (!row) throw new Error("login_attempts upsert returned no row");
  return row;
}

export async function handleLogin(request: Request, env: Env, secrets: Secrets): Promise<Response> {
  // Defence in depth for the browser: a cross-site page cannot post the login form for us.
  if (request.headers.has("Origin") && !sameOrigin(request)) throw new HttpError(403, "bad_origin");
  const body = await readJson(request, 4096);
  const password = (body as { password?: unknown } | null)?.password;
  if (typeof password !== "string" || password.length === 0 || password.length > 1024) {
    throw badRequest("password must be a non-empty string");
  }
  const now = Date.now();
  const bucket = clientBucket(request);
  const attempt = await countAttempt(env.DB, bucket, now);
  if (attempt.count > MAX_FAILURES) {
    const retryAfterSeconds = Math.max(1, Math.ceil((attempt.window_start + LOCKOUT_WINDOW_MS - now) / 1000));
    throw new HttpError(429, "locked", undefined, { "Retry-After": String(retryAfterSeconds) }, { retryAfterSeconds });
  }
  if (!(await secretEquals(password, secrets.password))) {
    throw new HttpError(401, "invalid_password");
  }
  await env.DB.prepare("DELETE FROM login_attempts WHERE ip = ?1").bind(bucket).run();
  const { token, expiresAt } = await issueToken(secrets, now);
  return json({ token, expiresAt }, 200, { "Set-Cookie": sessionCookie(token, secrets) });
}

export function handleLogout(request: Request, secrets: Secrets): Response {
  if (request.headers.has("Origin") && !sameOrigin(request)) throw new HttpError(403, "bad_origin");
  return json({ ok: true }, 200, { "Set-Cookie": clearedSessionCookie(secrets) });
}
