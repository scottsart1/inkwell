# Notes vault API (yourpitbox.com/notes)

The notes vault is a Cloudflare Worker ([`web/notes-vault`](../web/notes-vault/README.md),
deployed with `npm run deploy` there) mounted on `yourpitbox.com/notes/*`. The Android app's `SyncManager` pushes notes,
transcripts and audio to it when the user picks "Notes vault" as a destination in Settings ›
Storage, sync & backup, next to a folder and Google Drive. The settings take only the link.

**The vault has no password.** Anyone who opens `https://yourpitbox.com/notes` can read,
download, change and delete everything in it. Earlier versions had one; the owner removed it
(app version 1.2.7).

## Addressing

```
BASE = https://yourpitbox.com/notes            (the plain address)
BASE = https://yourpitbox.com/notes/<LINK_ID>  (the older "special link"; same page and API)
```

`LINK_ID` is a Worker secret (`NOTES_LINK_ID`), not stored in this repository; without it the
Worker answers 404 to everything. Any path other than these two entries gets a plain 404.

The Worker never redirects, and the app does not follow redirects. The one exception is a site
moving between `www.host` and `host`. The zone sends `www.yourpitbox.com/*` to the apex with a
301. When a permanent redirect (301 or 308) keeps the scheme, port and path and only adds or
drops a leading `www.`, the app follows it once and saves the new link (`VaultRedirect`). It
shows any other redirect as an error.

## Sessions (kept for compatibility)

`POST {BASE}/api/session`, any body (older apps send `{"password": "..."}`)

- `200 {"token": "open", "expiresAt": <epoch ms, a year ahead>}`, always. It also clears the
  `nv_session` cookie earlier versions set.
- `403 {"error": "bad_origin"}` when posted from another website.

Nothing checks the token: every other `/api/*` call works with or without
`Authorization: Bearer <token>`. Older vaults answered `401 invalid_password`,
`401 unauthorized` and `429 locked`; the app still understands those.

`POST {BASE}/api/logout` answers `{"ok":true}` and clears the old cookie.

State-changing requests (PUT, DELETE, POST) that carry an `Origin` header for another site get
`403 {"error":"bad_origin"}`, so another website open in the same browser cannot change the
vault through it. Requests without `Origin` (the app, scripts) and from the page itself work.

## Endpoints

| Method | Path | Body / result |
|---|---|---|
| GET | `/api/ping` | `{"ok":true,"name":"Your Pit Box · Notes"}` |
| GET | `/api/docs` | `{"docs":[DocSummary…]}` newest first; `?q=` filters by title/markdown/transcript text |
| GET | `/api/docs/{docId}` | `DocDetail` |
| PUT | `/api/docs/{docId}` | `DocUpsert` JSON → `{"ok":true}` (creates or replaces metadata + markdown) |
| DELETE | `/api/docs/{docId}` | removes the doc, its transcripts and files |
| PUT | `/api/docs/{docId}/transcripts/{recordingId}` | `TranscriptUpsert` JSON |
| PUT | `/api/docs/{docId}/files/{name}` | raw bytes, `Content-Type` set; ≤ 95 MB; `name` matches `[A-Za-z0-9._-]{1,120}` |
| GET | `/api/docs/{docId}/files/{name}` | the bytes (download) |
| DELETE | `/api/docs/{docId}/files/{name}` | |

```ts
type DocUpsert = {
  title: string;           // "20261015_Financial Markets"
  kind: "NOTEBOOK" | "PDF" | "WHITEBOARD" | "TYPED";
  createdAt: number;       // epoch ms (original creation)
  modifiedAt: number;
  folderPath?: string;     // "Economics / Term 1"
  pageCount?: number;
  tags?: string[];
  markdown?: string;       // recognised handwriting + typed + OCR text, ≤ 2 MB
};
type TranscriptUpsert = {
  title: string;
  startedAt: number;
  durationMs: number;
  source: string;          // "openai:whisper-1"
  segments: { startMs: number; endMs: number; text: string; speaker?: string | null }[];
};
type DocSummary = { id: string; title: string; kind: string; createdAt: number; modifiedAt: number;
                    folderPath: string | null; pageCount: number; transcriptCount: number; fileCount: number };
type DocDetail = DocSummary & { tags: string[]; markdown: string;
  transcripts: (TranscriptUpsert & { recordingId: string })[];
  files: { name: string; contentType: string; size: number; uploadedAt: number }[] };
```

`docId` is the app's document id (`[A-Za-z0-9-]{1,64}`). Uploads are idempotent PUTs, so the
app can retry safely. Files the app uploads by convention: `notes.pdf`, `notes.md`,
`rec_<recordingId>.m4a`.

## Storage

- Cloudflare D1 database `notes-vault`: tables `docs`, `transcripts`, `files` (and `login_attempts`,
  left from the password era and no longer used).
- Cloudflare R2 bucket `notes-vault-files`: file bodies at `<docId>/<name>`.
