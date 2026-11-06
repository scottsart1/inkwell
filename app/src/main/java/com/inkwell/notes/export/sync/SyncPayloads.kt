package com.inkwell.notes.export.sync

import com.inkwell.notes.core.model.Recording
import com.inkwell.notes.core.model.TranscriptSegment
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Text that sync writes: transcripts as Markdown (folder) and as HTML (a Google Doc in Drive). */
object SyncPayloads {
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
