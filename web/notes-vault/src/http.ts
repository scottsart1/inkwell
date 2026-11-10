import { randomBase64 } from "./crypto";

/** Thrown by handlers to produce a JSON error response. */
export class HttpError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    readonly detail?: string,
    readonly headers?: Record<string, string>,
    readonly extra?: Record<string, unknown>,
  ) {
    super(code);
  }
}

export const badRequest = (detail: string) => new HttpError(400, "invalid_request", detail);

export function newNonce(): string {
  return randomBase64(16);
}

export function contentSecurityPolicy(nonce: string): string {
  return [
    "default-src 'none'",
    `script-src 'nonce-${nonce}'`,
    `style-src 'self' 'nonce-${nonce}' https://fonts.googleapis.com`,
    "font-src https://fonts.gstatic.com",
    "img-src 'self' data:",
    "media-src 'self' blob:",
    "connect-src 'self'",
    "form-action 'self'",
    "base-uri 'none'",
    "frame-ancestors 'none'",
  ].join("; ");
}

/** Applies the security headers every response carries (HTML, JSON, files and 404s). */
export function withSecurityHeaders(response: Response, nonce: string): Response {
  const headers = new Headers(response.headers);
  headers.set("Content-Security-Policy", contentSecurityPolicy(nonce));
  headers.set("X-Content-Type-Options", "nosniff");
  headers.set("Referrer-Policy", "no-referrer");
  headers.set("X-Robots-Tag", "noindex, nofollow");
  headers.set("Cache-Control", "no-store");
  headers.set(
    "Permissions-Policy",
    "camera=(), microphone=(), geolocation=(), payment=(), usb=()",
  );
  headers.set("X-Frame-Options", "DENY");
  headers.set("Cross-Origin-Opener-Policy", "same-origin");
  headers.set("Cross-Origin-Resource-Policy", "same-origin");
  return new Response(response.body, { status: response.status, statusText: response.statusText, headers });
}

export function json(body: unknown, status = 200, headers?: HeadersInit): Response {
  const h = new Headers(headers);
  h.set("Content-Type", "application/json; charset=utf-8");
  return new Response(JSON.stringify(body), { status, headers: h });
}

export function errorResponse(err: HttpError): Response {
  const body: Record<string, unknown> = { error: err.code, ...err.extra };
  if (err.detail) body.detail = err.detail;
  return json(body, err.status, err.headers);
}

/** The generic 404 for everything outside the private link: short, plain, no hints. */
export function plainNotFound(): Response {
  return new Response("Not Found\n", {
    status: 404,
    headers: { "Content-Type": "text/plain; charset=utf-8" },
  });
}

/**
 * Reads a request body as text, refusing more than `limit` bytes (checked against
 * Content-Length up front and while streaming, so a missing/lying header cannot bypass it).
 */
export async function readTextLimited(request: Request, limit: number): Promise<string> {
  const declared = request.headers.get("Content-Length");
  if (declared !== null && Number(declared) > limit) throw new HttpError(413, "too_large");
  if (!request.body) return "";
  const reader = request.body.getReader();
  const chunks: Uint8Array[] = [];
  let total = 0;
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    total += value.byteLength;
    if (total > limit) {
      await reader.cancel().catch(() => {});
      throw new HttpError(413, "too_large");
    }
    chunks.push(value);
  }
  const all = new Uint8Array(total);
  let offset = 0;
  for (const c of chunks) {
    all.set(c, offset);
    offset += c.byteLength;
  }
  try {
    return new TextDecoder("utf-8", { fatal: true, ignoreBOM: false }).decode(all);
  } catch {
    throw badRequest("body is not valid UTF-8");
  }
}

export async function readJson(request: Request, limit: number): Promise<unknown> {
  const text = await readTextLimited(request, limit);
  try {
    return JSON.parse(text);
  } catch {
    throw new HttpError(400, "invalid_json");
  }
}
