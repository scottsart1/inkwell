/** Bindings and secrets of the notes-vault Worker (see wrangler.toml and README.md). */
export interface Env {
  /** D1 database "notes-vault" (tables docs, transcripts, files, login_attempts). */
  DB: D1Database;
  /** R2 bucket "notes-vault-files": file bodies at `<docId>/<name>`. */
  FILES: R2Bucket;
  /** Secret: the random path segment of the private link (`/notes/<NOTES_LINK_ID>`). */
  NOTES_LINK_ID?: string;
  /** Secret: the vault password. Changing it invalidates every issued token. */
  NOTES_PASSWORD?: string;
  /** Secret: HMAC key for session tokens. */
  SESSION_SECRET?: string;
}
