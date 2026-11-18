import { HttpError, json } from "./http";

/**
 * The vault has no password: anyone who opens yourpitbox.com/notes can read, change and
 * delete what is in it. `POST api/session` stays only so app versions that still "sign in"
 * keep syncing: it accepts any body and hands back a token that nothing checks.
 */
export const OPEN_TOKEN = "open";
/** How far ahead the no-op session says it expires, so older apps rarely ask again. */
export const OPEN_SESSION_TTL_MS = 365 * 24 * 60 * 60 * 1000;
/** The cookie earlier versions set when someone signed in; cleared on sight. */
export const OLD_SESSION_COOKIE = "nv_session";

/** True when the request carries an Origin header equal to the request's own origin. */
export function sameOrigin(request: Request): boolean {
  const origin = request.headers.get("Origin");
  if (!origin) return false;
  return origin === new URL(request.url).origin;
}

/** A browser request sent from another site (the app sends no Origin at all). */
export function crossSite(request: Request): boolean {
  return request.headers.has("Origin") && !sameOrigin(request);
}

function clearedOldCookie(): string {
  return `${OLD_SESSION_COOKIE}=; Max-Age=0; Path=/notes; HttpOnly; Secure; SameSite=Strict`;
}

/** `POST api/session`: always succeeds (no password); the body, if any, is ignored. */
export function handleSession(request: Request, now: number = Date.now()): Response {
  if (crossSite(request)) throw new HttpError(403, "bad_origin");
  return json({ token: OPEN_TOKEN, expiresAt: now + OPEN_SESSION_TTL_MS }, 200, { "Set-Cookie": clearedOldCookie() });
}

/** `POST api/logout`: nothing to sign out of; clears a cookie left by an earlier version. */
export function handleLogout(request: Request): Response {
  if (crossSite(request)) throw new HttpError(403, "bad_origin");
  return json({ ok: true }, 200, { "Set-Cookie": clearedOldCookie() });
}
