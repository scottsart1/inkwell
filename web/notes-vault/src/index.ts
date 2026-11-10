import { handleApi } from "./api";
import { authenticate, getSecrets, handleLogin, handleLogout, sameOrigin, SESSION_COOKIE, getCookie, verifyToken } from "./auth";
import { secretEquals } from "./crypto";
import type { Env } from "./env";
import { HttpError, errorResponse, json, newNonce, plainNotFound, withSecurityHeaders } from "./http";
import { renderPage } from "./page";

export type { Env } from "./env";

const SAFE_METHODS = new Set(["GET", "HEAD", "OPTIONS"]);

/**
 * Routing:
 *   /notes/<NOTES_LINK_ID>            web UI (also with a trailing slash)
 *   /notes/<NOTES_LINK_ID>/api/...    JSON API (docs/VAULT_API.md)
 *   anything else                     plain 404 (the vault is invisible without the link)
 */
async function route(request: Request, env: Env, nonce: string): Promise<Response> {
  const url = new URL(request.url);
  const linkId = env.NOTES_LINK_ID ?? "";
  const match = /^\/notes\/([^/]+)(\/.*)?$/.exec(url.pathname);
  if (!linkId || !match) return plainNotFound();
  if (!(await secretEquals(match[1] as string, linkId))) return plainNotFound();

  const rest = match[2] ?? "";
  const method = request.method.toUpperCase();

  if (rest === "" || rest === "/") {
    if (method !== "GET" && method !== "HEAD") {
      throw new HttpError(405, "method_not_allowed", undefined, { Allow: "GET, HEAD" });
    }
    const secrets = getSecrets(env);
    const cookie = getCookie(request, SESSION_COOKIE);
    const signedIn = !!(secrets && cookie && (await verifyToken(cookie, secrets)));
    return new Response(renderPage(nonce, signedIn), {
      headers: { "Content-Type": "text/html; charset=utf-8" },
    });
  }

  if (rest !== "/api" && !rest.startsWith("/api/")) return plainNotFound();
  const secrets = getSecrets(env);
  if (!secrets) throw new HttpError(503, "not_configured");
  const parts = rest.slice("/api/".length).replace(/\/+$/, "").split("/");

  if (parts.length === 1 && parts[0] === "session") {
    if (method !== "POST") throw new HttpError(405, "method_not_allowed", undefined, { Allow: "POST" });
    return handleLogin(request, env, secrets);
  }
  if (parts.length === 1 && parts[0] === "logout") {
    if (method !== "POST") throw new HttpError(405, "method_not_allowed", undefined, { Allow: "POST" });
    return handleLogout(request, secrets);
  }

  const via = await authenticate(request, secrets);
  if (!via) throw new HttpError(401, "unauthorized");
  // CSRF: browsers attach the cookie automatically, so state changes made with it must
  // come from our own origin. Bearer tokens are never sent implicitly and are exempt.
  if (via === "cookie" && !SAFE_METHODS.has(method) && !sameOrigin(request)) {
    throw new HttpError(403, "bad_origin");
  }
  return handleApi(request, env, parts, url);
}

export async function handle(request: Request, env: Env): Promise<Response> {
  const nonce = newNonce();
  let response: Response;
  try {
    response = await route(request, env, nonce);
  } catch (err) {
    if (err instanceof HttpError) {
      response = errorResponse(err);
    } else {
      // Never log the URL (it contains the link id) or any header/body content.
      console.error("notes-vault: internal error:", err instanceof Error ? err.name : typeof err);
      response = json({ error: "internal_error" }, 500);
    }
  }
  if (request.method.toUpperCase() === "HEAD" && response.body) {
    await response.body.cancel().catch(() => {});
    response = new Response(null, { status: response.status, headers: response.headers });
  }
  return withSecurityHeaders(response, nonce);
}

export default {
  fetch: (request, env) => handle(request, env),
} satisfies ExportedHandler<Env>;
