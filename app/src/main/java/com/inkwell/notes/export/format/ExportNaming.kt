package com.inkwell.notes.export.format

import com.inkwell.notes.export.ExportFormat
import java.text.Normalizer

/**
 * File names and MIME types of exported files. Names are derived from the document title
 * ("20261015_Financial Markets.pdf") and made safe for every target we write to: FAT/exFAT
 * SD cards, Drive, FileProvider shares and zip entries.
 */
object ExportNaming {
    /** Longest base name we produce, in UTF-16 units (most file systems allow 255 bytes). */
    const val MAX_BASE_LENGTH = 120

    /**
     * Longest base name in UTF-8 bytes. App storage (ext4/f2fs) and most documents providers
     * refuse names over 255 bytes, which 120 CJK characters (3 bytes each) already exceed;
     * this leaves room for a " (12)" suffix and the extension.
     */
    const val MAX_BASE_BYTES = 200

    private val FORBIDDEN = Regex("""[\\/:*?"<>|\u0000-\u001F\u007F]""")
    private val SPACES = Regex("""\s+""")
    private val RESERVED_WINDOWS = setOf(
        "CON", "PRN", "AUX", "NUL",
        "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
        "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9",
    )

    /**
     * A file-system-safe base name (no extension) for [title]: forbidden characters become
     * spaces, whitespace collapses, leading/trailing dots and spaces go, reserved device names
     * get a suffix, and the result is capped at [MAX_BASE_LENGTH] characters and
     * [MAX_BASE_BYTES] bytes without splitting a surrogate pair. Never empty: falls back to
     * [fallback].
     */
    fun sanitize(title: String, fallback: String = "Inkwell"): String {
        val normalized = Normalizer.normalize(title, Normalizer.Form.NFC)
        var s = normalized.replace(FORBIDDEN, " ").replace(SPACES, " ").trim().trim('.', ' ')
        if (s.isEmpty()) s = fallback
        if (s.substringBefore('.').uppercase() in RESERVED_WINDOWS) s = "${s}_"
        if (s.length > MAX_BASE_LENGTH) s = truncate(s, MAX_BASE_LENGTH)
        s = truncateBytes(s, MAX_BASE_BYTES)
        return s.ifEmpty { fallback }
    }

    /** [s] cut to at most [maxBytes] bytes of UTF-8 at a code point boundary, not ending in a dot or space. */
    fun truncateBytes(s: String, maxBytes: Int): String {
        var bytes = 0
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            val n = when {
                cp < 0x80 -> 1
                cp < 0x800 -> 2
                cp < 0x10000 -> 3
                else -> 4
            }
            if (bytes + n > maxBytes) return s.substring(0, i).trimEnd('.', ' ')
            bytes += n
            i += Character.charCount(cp)
        }
        return s
    }

    /** [s] cut to at most [maxLength] UTF-16 units without splitting a surrogate pair or ending in a dot or space. */
    fun truncate(s: String, maxLength: Int): String {
        if (s.length <= maxLength) return s
        var end = maxLength.coerceAtLeast(1)
        if (Character.isHighSurrogate(s[end - 1])) end--
        return s.substring(0, end).trimEnd('.', ' ')
    }

    /** True when an image export of [pageCount] pages is delivered as a zip of images. */
    fun isZipped(format: ExportFormat, pageCount: Int): Boolean =
        (format == ExportFormat.PNG || format == ExportFormat.JPEG) && pageCount > 1

    /** Extension of the delivered file (zip for multi-page images). */
    fun extension(format: ExportFormat, pageCount: Int): String =
        if (isZipped(format, pageCount)) "zip" else format.extension

    /** MIME type of the delivered file. */
    fun mime(format: ExportFormat, pageCount: Int): String =
        if (isZipped(format, pageCount)) "application/zip" else format.mime

    /**
     * MIME type to use when asking a documents provider to create the file. Providers append
     * the extension registered for a MIME type when the name's extension does not match it
     * ("Notes.inkwell" + application/zip would become "Notes.inkwell.zip"), so formats whose
     * extension is not registered for their MIME type are created as octet-stream.
     */
    fun safMime(format: ExportFormat, pageCount: Int): String = when {
        isZipped(format, pageCount) -> "application/zip"
        format == ExportFormat.INKWELL -> "application/octet-stream"
        format == ExportFormat.MARKDOWN -> "application/octet-stream"
        else -> format.mime
    }

    /** "<title>.<ext>" for one document. */
    fun fileName(title: String, format: ExportFormat, pageCount: Int): String =
        "${sanitize(title)}.${extension(format, pageCount)}"

    /** Entry name of page [index] (0-based) inside a zip of page images: page_001.png. */
    fun pageImageName(index: Int, format: ExportFormat): String =
        "page_%03d.%s".format(java.util.Locale.ROOT, index + 1, format.extension)

    /**
     * Picks a name not in [taken] by appending " (2)", " (3)", ... before the extension.
     * Comparison ignores case, as most targets (FAT, Drive web) do.
     */
    fun unique(name: String, taken: Collection<String>): String {
        val lower = taken.mapTo(HashSet()) { it.lowercase() }
        if (name.lowercase() !in lower) return name
        val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        val base = name.substring(0, dot)
        val ext = name.substring(dot)
        var n = 2
        while (true) {
            val candidate = "$base ($n)$ext"
            if (candidate.lowercase() !in lower) return candidate
            n++
        }
    }
}
