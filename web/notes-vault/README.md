# Notes vault (yourpitbox.com/notes)

A vault for the notes, meeting/lecture transcripts and audio that the Inkwell Android app
syncs. It is a Cloudflare Worker (TypeScript, no framework) with a D1 database and an R2
bucket, mounted on `yourpitbox.com/notes*` and `www.yourpitbox.com/notes*`.

- **No password.** Anyone who opens `https://yourpitbox.com/notes` can read, download, change
  and delete everything in it. (Earlier versions had a password; the owner removed it.)
- The older private link `/notes/<LINK_ID>` serves the same page and API. Every other path
  returns a plain `404 Not Found`.
- **Protocol:** [`docs/VAULT_API.md`](../../docs/VAULT_API.md) (the contract the app's
  sync client is written against).

```
web/notes-vault/
  src/index.ts        routing: link check (constant time), page, API, security headers
  src/auth.ts         the no-op session/logout calls kept for older apps, origin check
  src/api.ts          docs / transcripts / files endpoints (D1 + R2)
  src/validate.ts     input validation and limits
  src/markdown.ts     the small safe Markdown renderer used by the page
  src/page.ts         the single HTML page (inline CSS/JS with a per-response CSP nonce)
  migrations/         D1 migrations (wrangler d1 migrations apply)
  schema.sql          the full current schema (same as the migrations)
  scripts/deploy.sh   idempotent setup + deploy
  test/               Vitest tests running inside workerd (D1/R2 simulators)
```

## Bindings and secrets

| Name | Kind | Value |
|---|---|---|
| `DB` | D1 binding | database `notes-vault` (id in `wrangler.toml`) |
| `FILES` | R2 binding | bucket `notes-vault-files` (bodies at `<docId>/<name>`) |
| `NOTES_LINK_ID` | Worker secret | the private path segment, e.g. 32 random URL-safe characters |

`NOTES_LINK_ID` is not stored in this repository. If it is missing the Worker answers 404 to
everything. The `NOTES_PASSWORD` and `SESSION_SECRET` secrets of earlier versions are no longer
used; the deploy script removes them.

## One-time setup

The D1 database `notes-vault` and the R2 bucket `notes-vault-files` already exist on the
Cloudflare account and the database id is in `wrangler.toml`. The deploy script still
checks for them (and creates them if they are ever missing) and applies the D1 migrations.

### 1. Cloudflare API token

Cloudflare dashboard → My Profile → API Tokens → Create Token → Custom token:

| Scope | Permission | Access |
|---|---|---|
| Account | Workers Scripts | Edit |
| Zone (`yourpitbox.com` only) | Workers Routes | Edit |
| Account | D1 | Edit |
| Account | Workers R2 Storage | Edit |
| Zone (`yourpitbox.com` only) | Zone | Read (lets wrangler resolve `zone_name` to the zone id) |

Account Resources: the account that owns `yourpitbox.com`. Zone Resources: Include →
Specific zone → `yourpitbox.com`. Copy the token; you also need the **Account ID**
(dashboard → Workers & Pages → right-hand side, or the URL of the account home).

### 2. Generate the link id (first deploy only)

```bash
openssl rand -base64 48 | tr -d '\n=' | tr '+/' '-_' | cut -c1-32   # NOTES_LINK_ID
```

### 3a. Deploy from GitHub Actions (recommended)

Repository → Settings → Secrets and variables → Actions → New repository secret, add:

`CLOUDFLARE_API_TOKEN`, `CLOUDFLARE_ACCOUNT_ID`, `NOTES_LINK_ID`. (The `NOTES_PASSWORD` and
`SESSION_SECRET` repository secrets are no longer read and can be deleted.)

Then Actions → **Deploy notes vault** → Run workflow. From then on every push to `main`
that touches `web/notes-vault/**` runs typecheck, tests and the deploy. Without
`CLOUDFLARE_API_TOKEN` the deploy job is skipped. In CI the script never generates a link
id: if `NOTES_LINK_ID` is not set and the Worker has none yet, it stops with an error.

### 3b. Or deploy from your computer

```bash
cd web/notes-vault
npm ci
export CLOUDFLARE_API_TOKEN=<token> CLOUDFLARE_ACCOUNT_ID=<account-id>   # or: npx wrangler login
export NOTES_LINK_ID=<link-id>         # optional; see below
npm run deploy                          # = bash scripts/deploy.sh
```

`scripts/deploy.sh` is idempotent:

1. lists D1 databases and creates `notes-vault` if missing; if `wrangler.toml` still has
   `database_id = "REPLACE_WITH_D1_ID"` it writes the real id in (and it refuses to deploy
   if the file names a different database than the account has);
2. creates the R2 bucket `notes-vault-files` if missing;
3. runs `wrangler d1 migrations apply notes-vault --remote`;
4. sets `NOTES_LINK_ID` with `printf "%s" "$X" | npx wrangler secret put NAME`. If it is unset
   the existing value is kept; when the Worker has no link yet (first local run) a random
   32-character id is generated and the link is printed once;
5. runs `npx wrangler deploy` (routes `yourpitbox.com/notes*` and `www.yourpitbox.com/notes*`
   on the `yourpitbox.com` zone), then deletes the old `NOTES_PASSWORD` and `SESSION_SECRET`
   secrets if they are still there (a failure there is only a warning).

## Using the vault

Open `https://yourpitbox.com/notes` (or `https://yourpitbox.com/notes/<LINK_ID>`; `www.` works
too). There is nothing to sign in to. The page lists synced documents (newest first) with search across titles, recognised
text and transcripts; selecting one shows the rendered notes, transcripts with clickable
`[mm:ss]` timestamps that seek the recording, and downloadable files.

For testing before DNS/routes are live, the same paths work on the Worker's
`https://notes-vault.<your-subdomain>.workers.dev/notes/<LINK_ID>`.

### Changing the link

Set a new `NOTES_LINK_ID` the same way. The old link turns into a plain 404 at once;
update your bookmark. (The plain `/notes` address stays open regardless.)

### Bringing a password back

The vault had a password until this change (session tokens, lockout, sign-in page). It is in
the git history: revert the commit "Notes vault: remove the password" and redeploy with
`NOTES_PASSWORD` set, then enter the password in the app.

## How the app connects

In Inkwell: **Settings › Storage, sync & backup › Notes vault**. Enter the address
(`https://yourpitbox.com/notes`) and use *Test connection*. The app still calls
`POST {url}/api/session` (the vault answers any request with a token nothing checks) and sends
that token along. Opening the settings screen removes a password an earlier version saved.
Choose per output (notes, transcripts, audio) whether it goes to the vault.

Implementation details the client can rely on (all within `docs/VAULT_API.md`):

- File uploads (`PUT .../files/{name}`) stream straight into R2 and need a
  `Content-Length` header (`411 {"error":"length_required"}` otherwise; OkHttp sends it
  for file and byte-array bodies). `Content-Type` is stored and returned on download.
  Downloads support `Range` requests (206) so the browser can seek audio.
- A transcript or file uploaded before its document creates a placeholder document row
  (kind `NOTEBOOK`, the transcript title or the doc id as title) that the next
  `PUT /api/docs/{docId}` replaces.
- `PUT /api/docs/{docId}` replaces the whole record: optional fields that are omitted
  reset to their defaults (`folderPath` null, `pageCount` 0, `tags` [], `markdown` "").
- All PUTs and DELETEs are idempotent; DELETE of something missing returns `{"ok":true}`.
- Extra (not in the contract, harmless to ignore): `DELETE /api/docs/{docId}/transcripts/{recordingId}`.
- Errors are JSON `{"error": code}` (sometimes with `detail`): 400 `invalid_request` /
  `invalid_json`, 403 `bad_origin`, 404 `not_found`, 405 `method_not_allowed`, 411
  `length_required`, 413 `too_large`.

## Security notes

- **There is no password or sign-in.** Anyone who finds the address has full read and write
  access, including deleting documents and files.
- The link id is compared in constant time (both sides SHA-256 hashed, then a byte loop
  without early exit).
- Cross-site changes: PUT/DELETE/POST requests whose `Origin` is another site get 403, so a
  web page open in the same browser cannot change the vault. This does not stop anyone from
  calling the API directly.
- Every response carries a strict CSP (per-response nonce, no inline handlers, no external
  scripts), `X-Content-Type-Options: nosniff`, `Referrer-Policy: no-referrer`,
  `X-Robots-Tag: noindex, nofollow` (asks search engines not to list it; not all honour it),
  `Cache-Control: no-store`.
- Workers Logs / observability are disabled in `wrangler.toml`; the code never logs URLs,
  headers or bodies.
- Uploaded files are served as attachments with `nosniff` and the CSP, so an uploaded
  HTML file cannot run script on this origin.

## Limits

- `markdown` ≤ 2 MB (UTF-8). D1 rows are capped at 2,000,000 bytes, so markdown above
  1.5 MB is kept whole in R2 (`_markdown/<docId>.md`) and only its first 1.5 MB is searched.
- Files ≤ 95 MB (Cloudflare's request body limit is 100 MB on Free/Pro plans).
- Transcripts ≤ about 1.9 MB of segment JSON + text per recording.
- On the Workers Free plan (10 ms CPU per request) very large documents may hit the CPU
  limit; the paid plan has ample headroom.

## Development

```bash
cd web/notes-vault
npm install
npx tsc --noEmit
npx vitest run          # tests run inside workerd with local D1/R2 and fake secrets

# local server with a fake link id:
npx wrangler d1 migrations apply notes-vault --local
npx wrangler dev --var NOTES_LINK_ID:dev-link
# then open http://localhost:8787/notes/dev-link
```

Schema changes: add `migrations/000N_name.sql` and update `schema.sql` to match.
