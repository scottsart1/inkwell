/** Bindings and secrets of the notes-vault Worker (see wrangler.toml and README.md). */
export interface Env {
  /** D1 database "notes-vault" (tables docs, transcripts, files; login_attempts is no longer used). */
  DB: D1Database;
  /** R2 bucket "notes-vault-files": file bodies at `<docId>/<name>`. */
  FILES: R2Bucket;
  /** Secret: the random path segment of the private link (`/notes/<NOTES_LINK_ID>`). */
  NOTES_LINK_ID?: string;
}
