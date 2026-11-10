package com.inkwell.notes.export.sync

import com.inkwell.notes.core.model.DocumentMeta
import com.inkwell.notes.core.model.Recording
import com.inkwell.notes.core.model.TranscriptSegment
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Text that sync writes: transcripts as Markdown (folder) and as HTML (a Google Doc in Drive), and
 * request bodies for the notes vault within its limits (docs/VAULT_API.md).
 */
object SyncPayloads {
    private const val MAX_TITLE = 500
    private const val MAX_FOLDER_PATH = 1000
    private const val MAX_TAGS = 200
    private const val MAX_TAG = 200
    private const val MAX_SOURCE = 200
    private const val MAX_SPEAKER = 200
    private const val MAX_SEGMENT_TEXT = 100_000
    const val TRUNCATION_NOTE = "\n\n_(Text shortened to fit the vault's 2 MB limit.)_\n"

    /**
     * Metadata + text of a document for the vault. [markdown] null makes a metadata-only upsert
     * (used when only transcripts or audio go to the vault, so the vault still shows the right title).
     */
    fun docUpsert(doc: DocumentMeta, folderPath: String, markdown: String?): VaultDocUpsert = VaultDocUpsert(
        title = doc.title.ifBlank { "Untitled" }.take(MAX_TITLE),
        kind = doc.kind.name,
        createdAt = doc.createdAt.coerceAtLeast(0),
        modifiedAt = doc.modifiedAt.coerceAtLeast(0),
        folderPath = folderPath.trim().takeIf { it.isNotEmpty() }?.take(MAX_FOLDER_PATH),
        pageCount = doc.pageCount.coerceAtLeast(0),
        tags = doc.tags.filter { it.isNotBlank() }.take(MAX_TAGS).map { it.take(MAX_TAG) },
        markdown = markdown?.let { fitUtf8(it, VaultNames.MAX_MARKDOWN_BYTES) },
    )

    /** A transcript for the vault: speaker names (never voice labels), times and text. */
    fun transcriptUpsert(recording: Recording, segments: List<TranscriptSegment>, source: String): VaultTranscriptUpsert =
        VaultTranscriptUpsert(
            title = recording.title.ifBlank { "Recording" }.take(MAX_TITLE),
            startedAt = recording.startedAt.coerceAtLeast(0),
            durationMs = recording.durationMs.coerceAtLeast(0),
            source = source.ifBlank { "unknown" }.take(MAX_SOURCE),
            segments = segments.map {
                VaultSegment(
                    startMs = it.startMs.coerceAtLeast(0),
                    endMs = it.endMs.coerceAtLeast(it.startMs.coerceAtLeast(0)),
                    text = it.text.take(MAX_SEGMENT_TEXT),
                    speaker = it.speaker?.takeIf { s -> s.isNotBlank() }?.take(MAX_SPEAKER),
                )
            },
        )

    /** [text] cut to at most [maxBytes] UTF-8 bytes (never inside a character), with a note when cut. */
    fun fitUtf8(text: String, maxBytes: Int): String {
        val bytes = text.encodeToByteArray()
        if (bytes.size <= maxBytes) return text
        val note = TRUNCATION_NOTE.encodeToByteArray()
        var end = (maxBytes - note.size).coerceAtLeast(0)
        // Back up over UTF-8 continuation bytes so a multi-byte character is never split.
        while (end > 0 && (bytes[end].toInt() and 0xC0) == 0x80) end--
        return bytes.decodeToString(0, end) + TRUNCATION_NOTE
    }

    /** Readable Markdown of a transcript for the folder destination. */
    fun transcriptMarkdown(recording: Recording, segments: List<TranscriptSegment>, docTitle: String, timeZone: TimeZone = TimeZone.getDefault()): String {
        val date = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply { this.timeZone = timeZone }.format(Date(recording.startedAt))
        return buildString {
            append("# ").append(recording.title.ifBlank { "Recording" }).append("\n\n")
            append(docTitle).append(" · ").append(date).append(" · ").append(clock(recording.durationMs)).append("\n\n")
            var lastSpeaker: String? = null
            for (s in segments) {
                val text = s.text.trim()
                if (text.isEmpty()) continue
                append("**[").append(clock(s.startMs)).append("]** ")
                val speaker = s.speaker?.takeIf { it.isNotBlank() }
                if (speaker != null && speaker != lastSpeaker) append("**").append(speaker).append(":** ")
                lastSpeaker = speaker ?: lastSpeaker
                append(text).append("\n\n")
            }
        }.trimEnd() + "\n"
    }

    /**
     * The transcript as a small HTML page. Drive turns it into a Google Doc, so the transcript
     * reads, searches and prints like any document on another computer (Docs, Word, a browser).
     */
    fun transcriptHtml(recording: Recording, segments: List<TranscriptSegment>, docTitle: String, timeZone: TimeZone = TimeZone.getDefault()): String {
        val date = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply { this.timeZone = timeZone }.format(Date(recording.startedAt))
        val title = recording.title.ifBlank { "Recording" }
        return buildString {
            append("<!DOCTYPE html><html><head><meta charset=\"utf-8\"><title>").append(html(title)).append("</title></head><body>\n")
            append("<h1>").append(html(title)).append("</h1>\n")
            append("<p><i>").append(html(docTitle)).append(" · ").append(date).append(" · ").append(clock(recording.durationMs)).append("</i></p>\n")
            var lastSpeaker: String? = null
            for (s in segments) {
                val text = s.text.trim()
                if (text.isEmpty()) continue
                append("<p><b>[").append(clock(s.startMs)).append("]</b> ")
                val speaker = s.speaker?.takeIf { it.isNotBlank() }
                if (speaker != null && speaker != lastSpeaker) append("<b>").append(html(speaker)).append(":</b> ")
                lastSpeaker = speaker ?: lastSpeaker
                append(html(text).replace("\n", "<br>")).append("</p>\n")
            }
            append("</body></html>\n")
        }
    }

    private fun html(text: String): String = buildString(text.length + 16) {
        for (ch in text) when (ch) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&#39;")
            else -> if (ch < ' ' && ch != '\n' && ch != '\t') append(' ') else append(ch)
        }
    }

    /** m:ss or h:mm:ss. */
    fun clock(ms: Long): String {
        val total = (ms.coerceAtLeast(0) / 1000)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%d:%02d", m, s)
    }
}

/** Serialises the cached vault session for SecretStore, bound to one vault link. */
object VaultSessionCodec {
    @kotlinx.serialization.Serializable
    private data class Stored(val token: String, val expiresAt: Long, val vault: String)

    fun encode(session: VaultSession, endpointFingerprint: String): String =
        VaultJson.encodeToString(Stored.serializer(), Stored(session.token, session.expiresAt, endpointFingerprint))

    /** The session when [stored] is valid JSON for the same vault; null otherwise. */
    fun decode(stored: String?, endpointFingerprint: String): VaultSession? {
        if (stored.isNullOrEmpty()) return null
        val s = runCatching { VaultJson.decodeFromString(Stored.serializer(), stored) }.getOrNull() ?: return null
        if (s.vault != endpointFingerprint || s.token.isEmpty()) return null
        return VaultSession(s.token, s.expiresAt)
    }
}
