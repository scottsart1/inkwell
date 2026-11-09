package com.inkwell.notes.export.email

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import com.inkwell.notes.app.AppGraph
import com.inkwell.notes.core.model.TranscriptSegment
import com.inkwell.notes.export.ExportFormat
import com.inkwell.notes.export.ExportOptions
import com.inkwell.notes.export.format.DocumentTextBuilder
import com.inkwell.notes.export.format.DocxImage
import com.inkwell.notes.export.format.DocxImageSource
import com.inkwell.notes.export.format.DocxWriter
import com.inkwell.notes.export.format.ExportNaming
import com.inkwell.notes.export.format.TranscriptInput
import kotlinx.coroutines.CancellationException
import java.io.File
import java.util.UUID

/**
 * Builds the "Email to work" intent: addressed to the user's own work address, subject and body
 * filled in, PDF and Word attachments shared read-only through the app's FileProvider. Opens a
 * mail app; nothing is sent until the user taps Send there. Runs on a background thread.
 */
internal class WorkEmailSender(private val graph: AppGraph) {
    private val context: Context get() = graph.context

    /** The recording has no transcript yet: there is nothing to email. */
    class NoTranscript : Exception()

    /** The whole document: note text and every transcript in the body; notes PDF and a Word file attached. */
    suspend fun forDocument(docId: String, to: String): Intent {
        val doc = graph.library.getDocument(docId) ?: throw IllegalStateException("Document not found")
        val pdf = attempt("PDF") { graph.export.export(docId, ExportOptions(ExportFormat.PDF)) }
        val docx = attempt("Word") { graph.export.export(docId, ExportOptions(ExportFormat.DOCX, includeTranscripts = true)) }
        val notes = attempt("text") { plainNotes(docId) }.orEmpty()
        val transcripts = graph.db.recordings().forDocument(docId).map { it.toModel() }
            .sortedBy { it.startedAt }
            .mapNotNull { rec ->
                val segments = segments(rec.id)
                if (segments.isEmpty()) null else WorkEmail.Transcript(rec.title, rec.startedAt, rec.durationMs, segments)
            }
        val body = WorkEmail.documentBody(doc.title, notes, transcripts, hasPdf = pdf != null)
        return intent(
            to = to,
            subject = WorkEmail.documentSubject(doc.title),
            body = body,
            files = listOfNotNull(pdf?.let { it to MIME_PDF }, docx?.let { it to MIME_DOCX }),
        )
    }

    /** One recording: its transcript in the body and as a Word file. */
    suspend fun forRecording(recordingId: String, to: String): Intent {
        val rec = graph.db.recordings().get(recordingId)?.toModel() ?: throw IllegalStateException("Recording not found")
        val segments = segments(rec.id)
        if (segments.isEmpty()) throw NoTranscript()
        val docTitle = graph.library.getDocument(rec.documentId)?.title.orEmpty()
        val transcript = WorkEmail.Transcript(rec.title, rec.startedAt, rec.durationMs, segments)
        val docx = attempt("Word") { transcriptDocx(rec.title.ifBlank { "Recording" }, docTitle, segments) }
        return intent(
            to = to,
            subject = WorkEmail.recordingSubject(rec.title, docTitle),
            body = WorkEmail.recordingBody(docTitle, transcript),
            files = listOfNotNull(docx?.let { it to MIME_DOCX }),
        )
    }

    /** Typed text, recognised handwriting and OCR as plain text (the TEXT export), title line dropped. */
    private suspend fun plainNotes(docId: String): String {
        val file = graph.export.export(docId, ExportOptions(ExportFormat.TEXT, includeTranscripts = false))
        return try {
            WorkEmail.withoutTitleLine(file.readText(Charsets.UTF_8))
        } finally {
            file.parentFile?.takeIf { it != graph.files.exportsDir }?.deleteRecursively() ?: file.delete()
        }
    }

    private suspend fun segments(recordingId: String): List<TranscriptSegment> =
        graph.db.transcripts().forRecording(recordingId).map { it.toModel() }.filter { it.text.isNotBlank() }

    /** A Word file of one transcript (speakers and times), named after the recording. */
    private suspend fun transcriptDocx(recordingTitle: String, docTitle: String, segments: List<TranscriptSegment>): File {
        val title = if (docTitle.isBlank()) recordingTitle else "$recordingTitle ($docTitle)"
        val doc = DocumentTextBuilder.build(title, typed = null, pages = emptyList(), transcripts = listOf(TranscriptInput(recordingTitle, segments)))
        val dir = File(graph.files.exportsDir, "email-" + UUID.randomUUID()).apply { mkdirs() }
        val file = File(dir, ExportNaming.sanitize("Transcript - $recordingTitle") + ".docx")
        val noImages = object : DocxImageSource {
            override suspend fun asset(assetId: String): DocxImage? = null
            override suspend fun page(pageIndex: Int): DocxImage? = null
        }
        file.outputStream().use { DocxWriter(noImages).write(doc, it) }
        return file
    }

    private fun intent(to: String, subject: String, body: String, files: List<Pair<File, String>>): Intent {
        val uris = files.map { (file, _) -> FileProvider.getUriForFile(context, context.packageName + ".files", file) }
        val mimes = files.map { it.second }.distinct()
        return Intent(if (uris.size > 1) Intent.ACTION_SEND_MULTIPLE else Intent.ACTION_SEND).apply {
            putExtra(Intent.EXTRA_EMAIL, arrayOf(to))
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
            when (uris.size) {
                0 -> Unit
                1 -> putExtra(Intent.EXTRA_STREAM, uris[0])
                else -> putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            }
            type = when {
                uris.isEmpty() -> "text/plain"
                mimes.size == 1 -> mimes[0]
                else -> "*/*"
            }
            if (uris.isNotEmpty()) {
                clipData = ClipData.newUri(context.contentResolver, files[0].first.name, uris[0]).apply {
                    uris.drop(1).forEach { addItem(ClipData.Item(it)) }
                }
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            // Only mail apps: a mailto: selector keeps chat and drive apps out of the list.
            selector = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))
        }
    }

    /** One part of the email failing (an export) leaves it out instead of stopping the email. */
    private suspend fun <T> attempt(what: String, block: suspend () -> T): T? = try {
        block()
    } catch (c: CancellationException) {
        throw c
    } catch (t: Throwable) {
        Log.w(TAG, "Work email: no $what (${t.javaClass.simpleName})")
        null
    }

    private companion object {
        const val TAG = "WorkEmail"
        const val MIME_PDF = "application/pdf"
        const val MIME_DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    }
}
