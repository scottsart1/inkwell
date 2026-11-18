import { handleApi } from "./api";
import { crossSite, handleLogout, handleSession } from "./auth";
import { secretEquals } from "./crypto";
import type { Env } from "./env";
import { HttpError, errorResponse, json, newNonce, plainNotFound, withSecurityHeaders } from "./http";
import { renderPage } from "./page";

export type { Env } from "./env";

const SAFE_METHODS = new Set(["GET", "HEAD", "OPTIONS"]);

/**
 * Routing:
 *   /notes, /notes/                   web UI at the plain address
 *   /notes/api/...                    JSON API for that page
 *   /notes/<NOTES_LINK_ID>            the same web UI under the private link
 *   /notes/<NOTES_LINK_ID>/api/...    JSON API (docs/VAULT_API.md); what the app uses
 *   anything else                     plain 404
 * There is no password: both entries are open to anyone who opens them.
 */
async function route(request: Request, env: Env, nonce: string): Promise<Response> {
  const url = new URL(request.url);
  const linkId = env.NOTES_LINK_ID ?? "";
  if (!linkId) return plainNotFound();
  let rest: string;
  if (/^\/notes(\/|\/api(\/.*)?)?$/.test(url.pathname)) {
    rest = url.pathname.slice("/notes".length);
  } else {
    const match = /^\/notes\/([^/]+)(\/.*)?$/.exec(url.pathname);
    if (!match) return plainNotFound();
    if (!(await secretEquals(match[1] as string, linkId))) return plainNotFound();
    rest = match[2] ?? "";
  }
  const method = request.method.toUpperCase();

  if (rest === "" || rest === "/") {
    if (method !== "GET" && method !== "HEAD") {
      throw new HttpError(405, "method_not_allowed", undefined, { Allow: "GET, HEAD" });
    }
    return new Response(renderPage(nonce), {
      headers: { "Content-Type": "text/html; charset=utf-8" },
    });
  }

  if (rest !== "/api" && !rest.startsWith("/api/")) return plainNotFound();
  const parts = rest.slice("/api/".length).replace(/\/+$/, "").split("/");

  // Kept for app versions that still sign in; neither checks anything.
  if (parts.length === 1 && parts[0] === "session") {
    if (method !== "POST") throw new HttpError(405, "method_not_allowed", undefined, { Allow: "POST" });
    return handleSession(request);
  }
  if (parts.length === 1 && parts[0] === "logout") {
    if (method !== "POST") throw new HttpError(405, "method_not_allowed", undefined, { Allow: "POST" });
    return handleLogout(request);
  }

  // Another website open in the same browser cannot change the vault through it. (Anyone can
  // still change it directly: there is no password.)
  if (!SAFE_METHODS.has(method) && crossSite(request)) throw new HttpError(403, "bad_origin");
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
