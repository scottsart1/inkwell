package com.inkwell.notes.export.email

import com.inkwell.notes.core.model.TranscriptSegment
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * "Email to work": the text of the email the user sends to their own work address from the
 * tablet's mail app. Everything is in the body (readable even where attachments are stripped),
 * and the notes PDF and a Word file go along as attachments. Plain text throughout.
 */
object WorkEmail {
    /** Mail apps and Android's Binder both choke on huge extras; longer text continues in the Word file. */
    const val MAX_BODY_CHARS = 100_000
    const val CUT_NOTE = "\n\n[The rest is in the attached Word file.]\n"

    private val ADDRESS = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")

    /** The address as typed, trimmed; leading "mailto:" and surrounding <> dropped. */
    fun normalize(raw: String): String = raw.trim().removePrefix("mailto:").trim().removePrefix("<").removeSuffix(">").trim()

    fun isAddress(raw: String): Boolean = ADDRESS.matches(normalize(raw))

    /** A recording's transcript for the email. */
    data class Transcript(
        val title: String,
        val startedAt: Long,
        val durationMs: Long,
        val segments: List<TranscriptSegment>,
    )

    fun documentSubject(docTitle: String): String = docTitle.ifBlank { "Notes" }

    fun recordingSubject(recordingTitle: String, docTitle: String): String {
        val rec = recordingTitle.ifBlank { "Recording" }
        return if (docTitle.isBlank() || docTitle == rec) "Transcript: $rec" else "Transcript: $rec ($docTitle)"
    }

    /**
     * Body for a whole document: its title, the note text (typed, recognised handwriting, OCR;
     * plain text), then every transcript. [notesText] blank (pure drawings) points to the
     * attached PDF.
     */
    fun documentBody(
        docTitle: String,
        notesText: String,
        transcripts: List<Transcript>,
        hasPdf: Boolean,
        timeZone: TimeZone = TimeZone.getDefault(),
    ): String = fit(
        buildString {
            appendLine(docTitle.ifBlank { "Notes" })
            appendLine()
            appendLine("NOTES")
            val notes = notesText.trim()
            when {
                notes.isNotBlank() -> appendLine(notes)
                hasPdf -> appendLine("(Handwriting and drawings only: see the attached PDF.)")
                else -> appendLine("(No text.)")
            }
            for (t in transcripts) {
                appendLine()
                append(transcriptBlock(t, timeZone))
            }
        },
    )

    /** Body for one recording: its transcript only. */
    fun recordingBody(docTitle: String, transcript: Transcript, timeZone: TimeZone = TimeZone.getDefault()): String = fit(
        buildString {
            if (docTitle.isNotBlank()) {
                appendLine(docTitle)
                appendLine()
            }
            append(transcriptBlock(transcript, timeZone))
        },
    )

    /** "TRANSCRIPT: title", date and length, then one line per spoken block with time and speaker. */
    fun transcriptBlock(t: Transcript, timeZone: TimeZone = TimeZone.getDefault()): String = buildString {
        val date = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply { this.timeZone = timeZone }.format(Date(t.startedAt))
        appendLine("TRANSCRIPT: " + t.title.ifBlank { "Recording" })
        appendLine("$date · ${clock(t.durationMs)}")
        appendLine()
        var lastSpeaker: String? = null
        var wrote = false
        for (s in t.segments) {
            val text = s.text.trim()
            if (text.isEmpty()) continue
            append('[').append(clock(s.startMs)).append("] ")
            val speaker = s.speaker?.trim()?.takeIf { it.isNotEmpty() }
            if (speaker != null && speaker != lastSpeaker) append(speaker).append(": ")
            lastSpeaker = speaker ?: lastSpeaker
            appendLine(text.replace(Regex("\\s*\\n\\s*"), " "))
            wrote = true
        }
        if (!wrote) appendLine("(Nothing was transcribed.)")
    }

    /** [text] within [MAX_BODY_CHARS], cut at a line break with a note that the rest is attached. */
    fun fit(text: String, max: Int = MAX_BODY_CHARS): String {
        val trimmed = text.trimEnd() + "\n"
        if (trimmed.length <= max) return trimmed
        val room = (max - CUT_NOTE.length).coerceAtLeast(0)
        val cut = trimmed.lastIndexOf('\n', room).takeIf { it > room / 2 } ?: room
        // Never split a surrogate pair.
        val end = if (cut > 0 && Character.isHighSurrogate(trimmed[cut - 1])) cut - 1 else cut
        return trimmed.substring(0, end).trimEnd() + CUT_NOTE
    }

    /**
     * The note text of a plain-text export without its first line (the document title, which
     * the email already starts with).
     */
    fun withoutTitleLine(plainExport: String): String {
        val text = plainExport.replace("\r\n", "\n").trimStart('\uFEFF')
        val firstBreak = text.indexOf('\n')
        return if (firstBreak < 0) "" else text.substring(firstBreak + 1).trim()
    }

    /** m:ss or h:mm:ss. */
    fun clock(ms: Long): String {
        val total = ms.coerceAtLeast(0) / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%d:%02d", m, s)
    }
}
