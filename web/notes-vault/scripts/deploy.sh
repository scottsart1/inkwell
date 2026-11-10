#!/usr/bin/env bash
# Deploys the notes vault Worker (yourpitbox.com/notes) idempotently:
#   1. creates the D1 database "notes-vault" and the R2 bucket "notes-vault-files" if missing
#   2. fills in database_id in wrangler.toml if it still holds the placeholder
#   3. applies D1 migrations to the remote database
#   4. sets the Worker secrets NOTES_PASSWORD, NOTES_LINK_ID, SESSION_SECRET
#   5. runs `wrangler deploy`
#
# Environment:
#   CLOUDFLARE_API_TOKEN, CLOUDFLARE_ACCOUNT_ID  wrangler credentials (or a prior `wrangler login`)
#   NOTES_PASSWORD   required: the vault password (changing it signs every device out)
#   NOTES_LINK_ID    optional: the private link segment. If unset, an existing secret is kept;
#                    if the Worker has none yet, a random 32-character id is generated and the
#                    private URL is printed once (local runs only, never in CI logs).
#   SESSION_SECRET   optional: HMAC key for session tokens. If unset, an existing secret is
#                    kept; otherwise a random one is generated (never printed).
#
# Secret values are never echoed: they are piped to `wrangler secret put` on stdin.
set -euo pipefail

cd "$(dirname "$0")/.."

DB_NAME="notes-vault"
BUCKET="notes-vault-files"
WORKER="notes-vault"
PLACEHOLDER="REPLACE_WITH_D1_ID"
SITE="https://yourpitbox.com"
# WRANGLER_CMD lets you point the script at another wrangler (e.g. a global install).
read -r -a WRANGLER <<< "${WRANGLER_CMD:-npx --no-install wrangler}"

log() { printf '==> %s\n' "$*" >&2; }
die() { printf 'error: %s\n' "$*" >&2; exit 1; }

in_ci() { [[ "${GITHUB_ACTIONS:-}" == "true" || "${CI:-}" == "true" ]]; }

random_id() { openssl rand -base64 48 | tr -d '\n=' | tr '+/' '-_' | cut -c1-32; }
random_secret() { openssl rand -base64 48 | tr -d '\n'; }

# Prints the JSON value found in wrangler's stdout (it may print banners around it).
extract_json() {
  node -e '
    const s = require("fs").readFileSync(0, "utf8");
    const starts = [...s.matchAll(/^[ \t]*[\[{]/gm)].map((m) => m.index);
    const ends = [...s.matchAll(/[\]}][ \t]*$/gm)].map((m) => m.index + 1).reverse();
    for (const i of starts) for (const j of ends) {
      if (j <= i) continue;
      try { process.stdout.write(JSON.stringify(JSON.parse(s.slice(i, j)))); process.exit(0); } catch {}
    }
    process.exit(1);
  '
}

[[ -n "${NOTES_PASSWORD:-}" ]] || die "NOTES_PASSWORD must be set (the vault password)."
if (( ${#NOTES_PASSWORD} < 12 )); then
  log "warning: NOTES_PASSWORD is shorter than 12 characters; a long passphrase is strongly recommended."
fi
if [[ -n "${NOTES_LINK_ID:-}" && ! "${NOTES_LINK_ID}" =~ ^[A-Za-z0-9_-]{16,128}$ ]]; then
  die "NOTES_LINK_ID must be 16-128 characters of [A-Za-z0-9_-]."
fi
command -v openssl >/dev/null || die "openssl is required."
command -v node >/dev/null || die "node is required."

if [[ "${GITHUB_ACTIONS:-}" == "true" ]]; then
  # Belt and braces: GitHub masks repository secrets already; this also covers values
  # that reach the script another way. (Only GitHub understands this command.)
  for v in NOTES_PASSWORD NOTES_LINK_ID SESSION_SECRET; do
    if [[ -n "${!v:-}" ]]; then echo "::add-mask::${!v}"; fi
  done
fi

# Names (never values) of the Worker's current secrets: unset inputs keep what exists, so
# a deploy never silently changes the private link or signs devices out.
if SECRETS_OUT="$("${WRANGLER[@]}" secret list --name "$WORKER" --format json 2>&1)"; then
  EXISTING_SECRETS="$(printf '%s' "$SECRETS_OUT" | extract_json)" || die "could not parse \`wrangler secret list\` output."
elif grep -qiE "10007|does not exist|not found" <<<"$SECRETS_OUT"; then
  EXISTING_SECRETS='[]' # first deploy: the Worker does not exist yet
else
  printf '%s\n' "$SECRETS_OUT" >&2
  die "could not list the Worker's secrets (see above); refusing to guess whether a link already exists."
fi
has_secret() {
  node -e '
    const list = JSON.parse(process.argv[1]);
    process.exit(Array.isArray(list) && list.some((s) => s && s.name === process.argv[2]) ? 0 : 1);
  ' "$EXISTING_SECRETS" "$1"
}

# Decide about the link before changing anything, so a CI run without one fails cleanly.
if [[ -n "${NOTES_LINK_ID:-}" ]]; then
  LINK_ACTION="set"
elif has_secret NOTES_LINK_ID; then
  LINK_ACTION="keep"
elif in_ci; then
  die "NOTES_LINK_ID is not set and the Worker has none yet. Generate one locally (openssl rand -base64 48 | tr -d '\\n=' | tr '+/' '-_' | cut -c1-32), store it as the NOTES_LINK_ID repository secret and re-run."
else
  LINK_ACTION="generate"
fi

# ---------------------------------------------------------------------------------------
# 1. D1 database
d1_uuid() {
  "${WRANGLER[@]}" d1 list --json 2>/dev/null | extract_json | node -e '
    const list = JSON.parse(require("fs").readFileSync(0, "utf8"));
    const db = (Array.isArray(list) ? list : []).find((d) => d.name === process.argv[1]);
    if (db) process.stdout.write(db.uuid || db.id || "");
  ' "$DB_NAME"
}

log "Checking D1 database \"$DB_NAME\""
DB_ID="$(d1_uuid || true)"
if [[ -z "$DB_ID" ]]; then
  log "Creating D1 database \"$DB_NAME\""
  "${WRANGLER[@]}" d1 create "$DB_NAME" >&2
  DB_ID="$(d1_uuid)"
  [[ -n "$DB_ID" ]] || die "could not read the id of D1 database \"$DB_NAME\" after creating it."
else
  log "D1 database exists ($DB_ID)"
fi

# 2. database_id in wrangler.toml
if grep -q "$PLACEHOLDER" wrangler.toml; then
  log "Writing database_id into wrangler.toml"
  sed -i.bak "s/$PLACEHOLDER/$DB_ID/" wrangler.toml && rm -f wrangler.toml.bak
else
  CONFIGURED="$(sed -n 's/^[[:space:]]*database_id[[:space:]]*=[[:space:]]*"\([^"]*\)".*/\1/p' wrangler.toml | head -n1)"
  if [[ "$CONFIGURED" != "$DB_ID" ]]; then
    die "wrangler.toml has database_id \"$CONFIGURED\" but the account's \"$DB_NAME\" is \"$DB_ID\". Fix wrangler.toml (or set it to $PLACEHOLDER) and re-run."
  fi
fi

# ---------------------------------------------------------------------------------------
# R2 bucket
log "Checking R2 bucket \"$BUCKET\""
if "${WRANGLER[@]}" r2 bucket info "$BUCKET" >/dev/null 2>&1; then
  log "R2 bucket exists"
else
  log "Creating R2 bucket \"$BUCKET\""
  if ! out="$("${WRANGLER[@]}" r2 bucket create "$BUCKET" 2>&1)"; then
    if grep -qi "already exists" <<<"$out"; then
      log "R2 bucket already exists"
    else
      printf '%s\n' "$out" >&2
      die "could not create R2 bucket \"$BUCKET\"."
    fi
  fi
fi

# ---------------------------------------------------------------------------------------
# 3. Migrations (CI=true skips wrangler's confirmation prompt)
log "Applying D1 migrations (remote)"
CI=true "${WRANGLER[@]}" d1 migrations apply "$DB_NAME" --remote >&2

# ---------------------------------------------------------------------------------------
# 4. Secrets
put_secret() {
  local name="$1" value="$2"
  log "Setting secret $name"
  printf '%s' "$value" | "${WRANGLER[@]}" secret put "$name" --name "$WORKER" >/dev/null
}

put_secret NOTES_PASSWORD "$NOTES_PASSWORD"

PRINT_URL=""
case "$LINK_ACTION" in
  set) put_secret NOTES_LINK_ID "$NOTES_LINK_ID" ;;
  keep) log "Keeping the existing NOTES_LINK_ID secret" ;;
  generate)
    NOTES_LINK_ID="$(random_id)"
    put_secret NOTES_LINK_ID "$NOTES_LINK_ID"
    PRINT_URL="$SITE/notes/$NOTES_LINK_ID"
    ;;
esac

if [[ -n "${SESSION_SECRET:-}" ]]; then
  put_secret SESSION_SECRET "$SESSION_SECRET"
elif has_secret SESSION_SECRET; then
  log "Keeping the existing SESSION_SECRET secret"
else
  put_secret SESSION_SECRET "$(random_secret)"
fi

# ---------------------------------------------------------------------------------------
# 5. Deploy
log "Deploying the Worker"
"${WRANGLER[@]}" deploy >&2

if [[ -n "$PRINT_URL" ]]; then
  printf '\nYour private vault URL (shown only this once - save it in your password manager):\n  %s\n\n' "$PRINT_URL"
fi
log "Done."
