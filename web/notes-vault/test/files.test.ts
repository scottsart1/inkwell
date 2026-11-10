import { env } from "cloudflare:workers";
import { describe, expect, it } from "vitest";
import { parseRange } from "../src/api";
import { ORIGIN, bearer, call, docBody, getJson, login, putJson, token, uid } from "./helpers";

async function upload(path: string, data: Uint8Array, headers: Record<string, string> = {}) {
  return call(path, {
    method: "PUT",
    headers: { ...bearer(await token()), "Content-Length": String(data.byteLength), ...headers },
    body: data,
  });
}

const bytes = (n: number) => {
  const out = new Uint8Array(n);
  for (let i = 0; i < n; i++) out[i] = (i * 31 + 7) & 0xff;
  return out;
};

describe("files", () => {
  it("uploads to R2 at <docId>/<name>, preserving Content-Type, and downloads the same bytes", async () => {
    const id = uid();
    await putJson(`/api/docs/${id}`, docBody());
    const data = bytes(70_000);
    const res = await upload(`/api/docs/${id}/files/rec_abc-1.m4a`, data, { "Content-Type": "audio/mp4" });
    expect(res.status).toBe(200);
    expect(await res.json()).toEqual({ ok: true });

    const obj = await env.FILES.get(`${id}/rec_abc-1.m4a`);
    expect(obj?.httpMetadata?.contentType).toBe("audio/mp4");
    expect(new Uint8Array(await (obj as R2ObjectBody).arrayBuffer())).toEqual(data);

    const dl = await call(`/api/docs/${id}/files/rec_abc-1.m4a`, { headers: bearer(await token()) });
    expect(dl.status).toBe(200);
    expect(dl.headers.get("Content-Type")).toBe("audio/mp4");
    expect(dl.headers.get("Content-Length")).toBe(String(data.byteLength));
    expect(dl.headers.get("Accept-Ranges")).toBe("bytes");
    expect(dl.headers.get("Content-Disposition")).toBe('attachment; filename="rec_abc-1.m4a"');
    expect(dl.headers.get("X-Content-Type-Options")).toBe("nosniff");
    expect(new Uint8Array(await dl.arrayBuffer())).toEqual(data);

    const { body } = await getJson(`/api/docs/${id}`);
    expect(body.fileCount).toBe(1);
    expect(body.files).toHaveLength(1);
    expect(body.files[0]).toMatchObject({ name: "rec_abc-1.m4a", contentType: "audio/mp4", size: 70_000 });
    expect(typeof body.files[0].uploadedAt).toBe("number");
  });

  it("re-uploading replaces the file (idempotent PUT)", async () => {
    const id = uid();
    const path = `/api/docs/${id}/files/notes.md`;
    await upload(path, new TextEncoder().encode("v1"), { "Content-Type": "text/markdown" });
    await upload(path, new TextEncoder().encode("version 2"), { "Content-Type": "text/markdown; charset=utf-8" });
    const dl = await call(path, { headers: bearer(await token()) });
    expect(await dl.text()).toBe("version 2");
    expect(dl.headers.get("Content-Type")).toBe("text/markdown; charset=utf-8");
    const { body } = await getJson(`/api/docs/${id}`);
    expect(body.files).toEqual([expect.objectContaining({ name: "notes.md", size: 9 })]);
  });

  it("defaults the Content-Type and accepts empty files", async () => {
    const id = uid();
    expect((await upload(`/api/docs/${id}/files/empty.bin`, new Uint8Array(0))).status).toBe(200);
    const dl = await call(`/api/docs/${id}/files/empty.bin`, { headers: bearer(await token()) });
    expect(dl.status).toBe(200);
    expect(dl.headers.get("Content-Type")).toBe("application/octet-stream");
    expect((await dl.arrayBuffer()).byteLength).toBe(0);
  });

  it("streams a body without a known length when Content-Length is given", async () => {
    const id = uid();
    const data = bytes(10_000);
    const stream = new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(data.slice(0, 4000));
        controller.enqueue(data.slice(4000));
        controller.close();
      },
    });
    const res = await call(`/api/docs/${id}/files/notes.pdf`, {
      method: "PUT",
      headers: { ...bearer(await token()), "Content-Type": "application/pdf", "Content-Length": "10000" },
      body: stream,
    });
    expect(res.status).toBe(200);
    const obj = await env.FILES.get(`${id}/notes.pdf`);
    expect(new Uint8Array(await (obj as R2ObjectBody).arrayBuffer())).toEqual(data);
  });

  it("validates names, sizes and lengths", async () => {
    const id = uid();
    const t = bearer(await token());
    const bad = ["bad%20name", "a%2Fb", "%2E%2E", `${"n".repeat(121)}`, "na*me", "...", "caf%C3%A9.txt"];
    for (const name of bad) {
      const res = await call(`/api/docs/${id}/files/${name}`, {
        method: "PUT",
        headers: { ...t, "Content-Length": "1" },
        body: "x",
      });
      expect(res.status, name).toBe(400);
    }
    expect((await upload(`/api/docs/${id}/files/${"n".repeat(120)}`, new Uint8Array([1]))).status).toBe(200);

    const tooBig = await call(`/api/docs/${id}/files/big.m4a`, {
      method: "PUT",
      headers: { ...t, "Content-Length": String(95 * 1024 * 1024 + 1) },
      body: "x",
    });
    expect(tooBig.status).toBe(413);

    const noLength = await call(`/api/docs/${id}/files/a.bin`, {
      method: "PUT",
      headers: t,
      body: new ReadableStream({ start: (c) => (c.enqueue(new Uint8Array([1])), c.close()) }),
    });
    expect(noLength.status).toBe(411);

    // (workerd logs "fixed-length pipe ended prematurely" for this one; that is expected.)
    const lying = await call(`/api/docs/${id}/files/a.bin`, {
      method: "PUT",
      headers: { ...t, "Content-Length": "10" },
      body: new ReadableStream({ start: (c) => (c.enqueue(new Uint8Array([1, 2, 3])), c.close()) }),
    });
    expect(lying.status).toBe(400);
    expect(await env.FILES.head(`${id}/a.bin`)).toBeNull();

    const badType = await upload(`/api/docs/${id}/files/a.bin`, new Uint8Array([1]), { "Content-Type": "not a type" });
    expect(badType.status).toBe(400);
  });

  it("serves byte ranges for audio seeking", async () => {
    const id = uid();
    const data = bytes(1000);
    await upload(`/api/docs/${id}/files/rec_r.m4a`, data, { "Content-Type": "audio/mp4" });
    const t = bearer(await token());
    const part = await call(`/api/docs/${id}/files/rec_r.m4a`, { headers: { ...t, Range: "bytes=100-199" } });
    expect(part.status).toBe(206);
    expect(part.headers.get("Content-Range")).toBe("bytes 100-199/1000");
    expect(part.headers.get("Content-Length")).toBe("100");
    expect(new Uint8Array(await part.arrayBuffer())).toEqual(data.slice(100, 200));

    const tail = await call(`/api/docs/${id}/files/rec_r.m4a`, { headers: { ...t, Range: "bytes=-10" } });
    expect(tail.status).toBe(206);
    expect(new Uint8Array(await tail.arrayBuffer())).toEqual(data.slice(990));

    const bad = await call(`/api/docs/${id}/files/rec_r.m4a`, { headers: { ...t, Range: "bytes=5000-" } });
    expect(bad.status).toBe(416);
    expect(bad.headers.get("Content-Range")).toBe("bytes */1000");

    const head = await call(`/api/docs/${id}/files/rec_r.m4a`, { method: "HEAD", headers: t });
    expect(head.status).toBe(200);
    expect(head.headers.get("Content-Length")).toBe("1000");
    expect(head.body).toBeNull();

    expect(parseRange("bytes=0-", 10)).toEqual({ offset: 0, length: 10 });
    expect(parseRange("bytes=2-100", 10)).toEqual({ offset: 2, length: 8 });
    expect(parseRange("bytes=5-2", 10)).toBeNull();
    expect(parseRange("bytes=10-20", 10)).toBe("unsatisfiable");
    expect(parseRange("bytes=-0", 10)).toBe("unsatisfiable");
    expect(parseRange("bytes=-20", 10)).toEqual({ offset: 0, length: 10 });
    expect(parseRange("bytes=0-1,4-5", 10)).toBeNull();
    expect(parseRange("items=0-1", 10)).toBeNull();
  });

  it("download by cookie works for the web UI (GET needs no Origin)", async () => {
    const id = uid();
    await upload(`/api/docs/${id}/files/notes.md`, new TextEncoder().encode("# x"), { "Content-Type": "text/markdown" });
    const { cookie } = await login();
    const dl = await call(`/api/docs/${id}/files/notes.md`, { headers: { Cookie: cookie } });
    expect(dl.status).toBe(200);
    expect(await dl.text()).toBe("# x");
  });

  it("missing files are 404 and need auth", async () => {
    const id = uid();
    expect((await call(`/api/docs/${id}/files/none.pdf`)).status).toBe(401);
    const res = await call(`/api/docs/${id}/files/none.pdf`, { headers: bearer(await token()) });
    expect(res.status).toBe(404);
    expect(await res.json()).toEqual({ error: "not_found" });
  });

  it("DELETE removes the object and its row", async () => {
    const id = uid();
    await putJson(`/api/docs/${id}`, docBody());
    await upload(`/api/docs/${id}/files/notes.pdf`, bytes(10), { "Content-Type": "application/pdf" });
    await upload(`/api/docs/${id}/files/keep.pdf`, bytes(10), { "Content-Type": "application/pdf" });
    const res = await call(`/api/docs/${id}/files/notes.pdf`, { method: "DELETE", headers: bearer(await token()) });
    expect(res.status).toBe(200);
    expect(await env.FILES.head(`${id}/notes.pdf`)).toBeNull();
    expect(await env.FILES.head(`${id}/keep.pdf`)).not.toBeNull();
    const { body } = await getJson(`/api/docs/${id}`);
    expect(body.files.map((f: { name: string }) => f.name)).toEqual(["keep.pdf"]);
    expect((await call(`/api/docs/${id}/files/notes.pdf`, { headers: bearer(await token()) })).status).toBe(404);
  });

  it("cookie uploads need the same Origin", async () => {
    const id = uid();
    const { cookie } = await login();
    const data = new Uint8Array([1, 2, 3]);
    const without = await call(`/api/docs/${id}/files/x.bin`, {
      method: "PUT",
      headers: { Cookie: cookie, "Content-Length": "3" },
      body: data,
    });
    expect(without.status).toBe(403);
    const withOrigin = await call(`/api/docs/${id}/files/x.bin`, {
      method: "PUT",
      headers: { Cookie: cookie, Origin: ORIGIN, "Content-Length": "3" },
      body: data,
    });
    expect(withOrigin.status).toBe(200);
  });
});
