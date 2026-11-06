package com.inkwell.notes.export.backup

import java.io.File
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipInputStream

/** Defensive zip handling for packages and backups that come from outside the app. */
object ZipSafety {
    const val MAX_ENTRIES = 200_000

    /**
     * Normalised relative path of a zip entry, or null when it is unsafe (absolute, drive
     * letter, parent references, empty) — guards against "zip slip" path traversal.
     */
    fun safeEntryPath(name: String): String? {
        val unified = name.replace('\\', '/')
        if (unified.startsWith("/") || Regex("^[A-Za-z]:").containsMatchIn(unified)) return null
        val parts = ArrayList<String>()
        for (seg in unified.split('/')) {
            when (seg) {
                "", "." -> Unit
                ".." -> return null
                else -> {
                    if (seg.any { it == '\u0000' }) return null
                    parts += seg
                }
            }
        }
        if (parts.isEmpty()) return null
        return parts.joinToString("/")
    }

    /** [path] resolved under [dir], or null when it would land outside it. */
    fun resolveInside(dir: File, path: String): File? {
        val safe = safeEntryPath(path) ?: return null
        val target = File(dir, safe)
        val base = dir.canonicalFile
        val canon = target.canonicalFile
        return if (canon.path.startsWith(base.path + File.separator)) target else null
    }

    /**
     * Extracts [zip] into [dir] (which must exist and be empty), refusing unsafe names, more
     * than [maxEntries] entries or more than [maxBytes] in total ([overBytes] says why, e.g.
     * NO_SPACE when [maxBytes] is the free space). Returns the relative paths written.
     * Directories are created as needed.
     */
    fun extract(
        zip: ZipInputStream,
        dir: File,
        maxBytes: Long,
        maxEntries: Int = MAX_ENTRIES,
        overBytes: PackageException.Reason = PackageException.Reason.TOO_LARGE,
    ): List<String> {
        val written = ArrayList<String>()
        var total = 0L
        val buf = ByteArray(64 * 1024)
        while (true) {
            val entry = zip.nextEntry ?: break
            if (written.size >= maxEntries) throw PackageException(PackageException.Reason.TOO_LARGE)
            if (entry.isDirectory) continue
            val path = safeEntryPath(entry.name) ?: throw PackageException(PackageException.Reason.CORRUPT)
            val target = resolveInside(dir, path) ?: throw PackageException(PackageException.Reason.CORRUPT)
            target.parentFile?.mkdirs()
            target.outputStream().buffered().use { out ->
                while (true) {
                    val n = zip.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > maxBytes) throw PackageException(overBytes)
                    out.write(buf, 0, n)
                }
            }
            written += path
        }
        return written
    }
}

/** Lets a nested reader/writer use a stream without closing the outer zip. */
class NonClosingInputStream(input: InputStream) : FilterInputStream(input) {
    override fun close() = Unit
}

class NonClosingOutputStream(output: OutputStream) : FilterOutputStream(output) {
    override fun write(b: ByteArray, off: Int, len: Int) {
        out.write(b, off, len)
    }

    override fun close() {
        flush()
    }
}

/** Counts bytes written through it. */
class CountingOutputStream(output: OutputStream) : FilterOutputStream(output) {
    var count = 0L
        private set

    override fun write(b: Int) {
        out.write(b)
        count++
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        out.write(b, off, len)
        count += len
    }
}

/** A package or backup could not be read; [reason] maps to a user-facing message. */
class PackageException(val reason: Reason, cause: Throwable? = null) : IOException(reason.name, cause) {
    enum class Reason { NOT_A_PACKAGE, CORRUPT, NEWER_VERSION, TOO_LARGE, NO_SPACE, UNREADABLE }
}
