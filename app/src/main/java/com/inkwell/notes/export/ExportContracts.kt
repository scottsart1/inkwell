package com.inkwell.notes.export

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import com.inkwell.notes.core.model.DocumentMeta
import kotlinx.coroutines.flow.StateFlow
import java.io.File

enum class ExportFormat(val extension: String, val mime: String) {
    PDF("pdf", "application/pdf"),
    PNG("png", "image/png"),
    JPEG("jpg", "image/jpeg"),
    TEXT("txt", "text/plain"),
    MARKDOWN("md", "text/markdown"),
    DOCX("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
    /** Native editable package (.inkwell zip: metadata, page content, assets, source PDF, audio). */
    INKWELL("inkwell", "application/zip"),
}

data class ExportOptions(
    val format: ExportFormat,
    /** null = all pages. */
    val pageIndices: List<Int>? = null,
    /** PDF: burn annotations into page content (true) or keep them as PDF annotations where supported (false). */
    val flattenAnnotations: Boolean = true,
    /** Include paper backgrounds / source PDF pages. */
    val includeBackground: Boolean = true,
    /** PDF: add an invisible text layer from recognised handwriting/OCR. */
    val searchableText: Boolean = true,
    val includeOutline: Boolean = true,
    /** Raster resolution for PNG/JPEG and flattened backgrounds. */
    val dpi: Int = 200,
    val jpegQuality: Int = 90,
    /** INKWELL: bundle audio recordings. */
    val includeAudio: Boolean = true,
    /** TEXT/MARKDOWN/DOCX: append recording transcripts. */
    val includeTranscripts: Boolean = true,
)

interface ExportService {
    /** Exports into the app's export cache and returns the file (a .zip for multi-page images). */
    suspend fun export(docId: String, options: ExportOptions): File
    /** Exports straight to a user-chosen document Uri. */
    suspend fun exportTo(docId: String, options: ExportOptions, target: Uri)
    /** Bulk export into a user-chosen folder tree Uri. Returns how many succeeded. */
    suspend fun exportMany(docIds: List<String>, options: ExportOptions, treeUri: Uri): Int
    /** Exports one recording's audio (all segments joined into a single .m4a when possible). */
    suspend fun exportRecording(recordingId: String): File
    fun shareIntent(file: File, mime: String): Intent
    /** Imports a native .inkwell package as a new document. */
    suspend fun importPackage(uri: Uri, folderId: String? = null): DocumentMeta
    /** Renders a page with all content (used by PNG export, AI vision, thumbnails). */
    suspend fun renderPage(docId: String, pageIndex: Int, widthPx: Int): Bitmap
    /** Markdown of the document: typed text, recognised handwriting, OCR text, transcripts. */
    suspend fun documentMarkdown(docId: String, includeTranscripts: Boolean = true): String
}

/** Generates library and page thumbnails. */
interface ThumbnailService {
    /** Re-renders the document's cover thumbnail (first page) after edits. */
    fun refresh(docId: String)
    suspend fun pageThumbnail(docId: String, pageId: String, widthPx: Int = 240): Bitmap?
}

data class SyncStatus(
    val running: Boolean = false,
    val pending: Int = 0,
    val lastSuccessAt: Long = 0,
    val lastError: String? = null,
)

/**
 * Copies outputs to the destinations the user chose for each output type (notes,
 * transcripts, audio): a SAF folder (Files, SD card), the user's Google Drive and/or the private
 * notes vault on yourpitbox.com, so they can be read on another computer. Local storage always
 * remains the source of truth (local-first).
 */
interface SyncManager {
    val status: StateFlow<SyncStatus>
    fun onDocumentChanged(docId: String)
    /**
     * The document's title or its place in the library changed (moved, or a folder above it was
     * renamed or moved): move the copies kept in sync destinations. Unlike [onDocumentChanged]
     * this also covers transcripts and audio, only renames/moves the document's Drive folder, and
     * sends the vault only the new metadata.
     */
    fun onDocumentMoved(docId: String) = onDocumentChanged(docId)
    fun onTranscriptReady(docId: String, recordingId: String)
    fun onRecordingFinished(docId: String, recordingId: String)
    fun onDocumentDeleted(docId: String)
    suspend fun syncNow(): SyncStatus
    /** Checks that Google Drive accepts uploads for the connected account; returns a human-readable result. */
    suspend fun testDrive(): Result<String>
    /** Checks URL + password against the notes vault; returns a human-readable result. */
    suspend fun testVault(url: String, password: String): Result<String>
    suspend fun testFolder(treeUri: Uri): Result<String>
}

data class BackupReport(
    val documents: Int,
    val bytes: Long,
    val file: Uri?,
    /** Documents left out because they could not be packaged (unreadable files). */
    val skipped: Int = 0,
)

/** Documents restored, and packages in the backup that could not be (damaged, from a newer app). */
data class RestoreReport(val documents: Int, val skipped: Int = 0)

/** Full-library backup (portable zip of native packages) — distinct from sync. */
interface BackupService {
    suspend fun backupTo(treeOrFileUri: Uri, includeAudio: Boolean = true): BackupReport
    suspend fun restoreFrom(uri: Uri): Int
    /** [restoreFrom] that also reports the documents it had to skip. */
    suspend fun restoreWithReport(uri: Uri): RestoreReport = RestoreReport(restoreFrom(uri))
    /** Schedules automatic backups into the chosen folder (null disables). */
    fun scheduleAutomatic(treeUri: Uri?, intervalDays: Int = 1)
}
