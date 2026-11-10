-- Notes vault schema (D1 / SQLite). Applied with:
--   npx wrangler d1 migrations apply notes-vault --remote
-- Keep schema.sql in sync with the sum of all migrations.

-- One row per synced document. markdown holds the recognised/typed/OCR text; when it is
-- larger than fits a D1 row (markdown_external = 1) the full text lives in R2 at
-- _markdown/<id>.md and this column keeps the leading part for search.
CREATE TABLE IF NOT EXISTS docs (
  id                TEXT    NOT NULL PRIMARY KEY,
  title             TEXT    NOT NULL,
  kind              TEXT    NOT NULL,
  created_at        INTEGER NOT NULL,
  modified_at       INTEGER NOT NULL,
  folder_path       TEXT,
  page_count        INTEGER NOT NULL DEFAULT 0,
  tags              TEXT    NOT NULL DEFAULT '[]',
  markdown          TEXT    NOT NULL DEFAULT '',
  markdown_external INTEGER NOT NULL DEFAULT 0,
  updated_at        INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS docs_modified_at_idx ON docs (modified_at DESC, created_at DESC);

-- Meeting / lecture transcripts, one per recording of a document. segments is the JSON
-- array from the app; text is the segments' text joined for search.
CREATE TABLE IF NOT EXISTS transcripts (
  doc_id       TEXT    NOT NULL,
  recording_id TEXT    NOT NULL,
  title        TEXT    NOT NULL,
  started_at   INTEGER NOT NULL,
  duration_ms  INTEGER NOT NULL,
  source       TEXT    NOT NULL,
  segments     TEXT    NOT NULL DEFAULT '[]',
  text         TEXT    NOT NULL DEFAULT '',
  updated_at   INTEGER NOT NULL,
  PRIMARY KEY (doc_id, recording_id)
);
CREATE INDEX IF NOT EXISTS transcripts_doc_started_idx ON transcripts (doc_id, started_at);

-- Uploaded files; bodies live in R2 at <doc_id>/<name>.
CREATE TABLE IF NOT EXISTS files (
  doc_id       TEXT    NOT NULL,
  name         TEXT    NOT NULL,
  content_type TEXT    NOT NULL,
  size         INTEGER NOT NULL,
  uploaded_at  INTEGER NOT NULL,
  PRIMARY KEY (doc_id, name)
);
CREATE INDEX IF NOT EXISTS files_uploaded_idx ON files (doc_id, uploaded_at);

-- Failed-login counter per client address (IPv6 by /64), rolling 15-minute window.
CREATE TABLE IF NOT EXISTS login_attempts (
  ip           TEXT    NOT NULL PRIMARY KEY,
  window_start INTEGER NOT NULL,
  count        INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS login_attempts_window_idx ON login_attempts (window_start);
