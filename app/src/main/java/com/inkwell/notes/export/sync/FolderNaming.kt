package com.inkwell.notes.export.sync

import com.inkwell.notes.core.model.NotesExportFormat
import com.inkwell.notes.core.model.Recording

/**
 * Names inside the chosen folder:
 *
 * ```
 * Inkwell/<folder>/<sub-folder>/<document title>/notes.pdf
 *                                                notes.md
 *                                                transcript_<recording title>.md
 *                                                audio_<recording title>.m4a
 * ```
 *
 * Every segment is made safe for FAT/exFAT SD cards, Drive and desktop systems.
 */
object FolderNaming {
    const val ROOT = "Inkwell"
    const val NOTES_PDF = "notes.pdf"
    const val NOTES_MD = "notes.md"
    private const val MAX_SEGMENT = 100
    private val ILLEGAL = Regex("[\\\\/:*?\"<>|\\u0000-\\u001F\\u007F]")
    private val SPACES = Regex("\\s+")
    private val RESERVED = setOf(
        "CON", "PRN", "AUX", "NUL",
        "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
        "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9",
    )

    /** One path segment: illegal characters become "_", whitespace collapses, no leading/trailing dots or spaces. */
    fun segment(name: String, fallback: String = "Untitled", maxLength: Int = MAX_SEGMENT): String {
        var s = name.replace(SPACES, " ").replace(ILLEGAL, "_").trim().trim('.', ' ')
        if (s.length > maxLength) s = s.take(maxLength).trimEnd('.', ' ')
        if (s.isEmpty()) s = fallback
        if (s.substringBefore('.').uppercase() in RESERVED) s = "${s}_"
        return s
    }

    /**
     * Folder chain (outermost first) + document title below [ROOT]. Library titles are unique, but
     * two titles can sanitise to the same segment ("Q&A: Lecture" and "Q&A_ Lecture"); when
     * sanitising changed the title and [docId] is known, a short id suffix keeps the directories
     * apart so one document's copies never overwrite another's. [withId] adds the suffix anyway
     * (the plain name holds another document's copies).
     */
    fun documentDir(folderNames: List<String>, docTitle: String, docId: String? = null, withId: Boolean = false): List<String> =
        listOf(ROOT) + folderNames.map { segment(it, fallback = "Folder") } + documentSegment(docTitle, docId, withId)

    internal fun documentSegment(docTitle: String, docId: String?, withId: Boolean = false): String {
        val plain = segment(docTitle)
        val shortId = docId?.filter { it.isLetterOrDigit() }?.take(6).orEmpty()
        if (shortId.isEmpty() || (plain == docTitle && !withId)) return plain
        return segment(docTitle, maxLength = MAX_SEGMENT - shortId.length - 3) + " ($shortId)"
    }

    fun notesFiles(format: NotesExportFormat): List<String> = when (format) {
        NotesExportFormat.PDF -> listOf(NOTES_PDF)
        NotesExportFormat.MARKDOWN -> listOf(NOTES_MD)
        NotesExportFormat.PDF_AND_MARKDOWN -> listOf(NOTES_PDF, NOTES_MD)
    }

    fun transcriptFile(stem: String): String = "transcript_$stem.md"
    fun audioFile(stem: String): String = "audio_$stem.m4a"

    /**
     * File-name stem for [recording] among [all] recordings of the same document. Recordings with
     * the same title are told apart by their order of starting ("Lecture", "Lecture (2)").
     */
    fun recordingStem(recording: Recording, all: List<Recording>): String {
        val base = segment(recording.title, fallback = "Recording", maxLength = 80)
        val same = (all + recording).distinctBy { it.id }
            .filter { segment(it.title, fallback = "Recording", maxLength = 80).equals(base, ignoreCase = true) }
            .sortedWith(compareBy<Recording> { it.startedAt }.thenBy { it.id })
        val position = same.indexOfFirst { it.id == recording.id }
        return if (position <= 0) base else "$base (${position + 1})"
    }

    /** True for files this app writes into a document folder (used when moving a renamed document). */
    fun isOwnFile(name: String): Boolean =
        name == NOTES_PDF || name == NOTES_MD ||
            (name.startsWith("transcript_") && name.endsWith(".md")) ||
            (name.startsWith("audio_") && name.endsWith(".m4a"))
}
