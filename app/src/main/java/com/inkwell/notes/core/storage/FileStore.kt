package com.inkwell.notes.core.storage

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * On-disk layout of the local-first library:
 *
 * files/docs/<docId>/pages/<pageId>.ink      ProtoBuf PageContent (+ .bak previous version)
 * files/docs/<docId>/assets/<assetId>        images and other embedded files
 * files/docs/<docId>/source.pdf              imported PDF (never modified)
 * files/docs/<docId>/typed.json              typed-document blocks
 * files/docs/<docId>/thumb.webp              library thumbnail
 * files/docs/<docId>/audio/<recId>/seg_NNN.m4a  crash-safe audio segments
 * files/docs/<docId>/audio/<recId>/waveform.bin amplitude envelope
 */
class FileStore(context: Context) {
    val root: File = File(context.filesDir, "docs").apply { mkdirs() }
    val cacheDir: File = context.cacheDir
    val exportsDir: File get() = File(cacheDir, "exports").apply { mkdirs() }

    fun docDir(docId: String): File = File(root, safe(docId)).apply { mkdirs() }
    fun pagesDir(docId: String): File = File(docDir(docId), "pages").apply { mkdirs() }
    fun pageFile(docId: String, pageId: String): File = File(pagesDir(docId), "${safe(pageId)}.ink")
    fun assetsDir(docId: String): File = File(docDir(docId), "assets").apply { mkdirs() }
    fun assetFile(docId: String, assetId: String): File = File(assetsDir(docId), safe(assetId))
    fun sourcePdf(docId: String): File = File(docDir(docId), "source.pdf")
    fun typedFile(docId: String): File = File(docDir(docId), "typed.json")
    fun thumbnailFile(docId: String): File = File(docDir(docId), "thumb.webp")
    fun pageThumbnailFile(docId: String, pageId: String): File = File(File(docDir(docId), "thumbs").apply { mkdirs() }, "${safe(pageId)}.webp")
    fun audioDir(docId: String, recordingId: String): File = File(File(docDir(docId), "audio"), safe(recordingId)).apply { mkdirs() }

    fun deleteDocument(docId: String) {
        File(root, safe(docId)).deleteRecursively()
    }

    fun sizeOf(file: File): Long = if (!file.exists()) 0 else if (file.isFile) file.length() else file.walkBottomUp().filter { it.isFile }.sumOf { it.length() }

    companion object {
        private val UNSAFE = Regex("[^A-Za-z0-9._-]")

        /**
         * A single path component that can never leave its parent directory: unsafe characters
         * become '_', and a name made only of dots ("." is the directory itself, ".." its parent)
         * is replaced by underscores. Deleting or writing "document .." must never touch filesDir.
         */
        fun safe(name: String): String {
            val s = name.replace(UNSAFE, "_")
            return if (s.isEmpty() || s.all { it == '.' }) "_".repeat(maxOf(1, s.length)) else s
        }

        /**
         * Crash-safe write: data goes to a temp file, is fsynced, the previous version
         * is kept as `.bak`, then the temp file is renamed over the target.
         */
        @Throws(IOException::class)
        fun writeAtomically(target: File, keepBackup: Boolean = true, write: (FileOutputStream) -> Unit) {
            target.parentFile?.mkdirs()
            val tmp = tempOf(target)
            FileOutputStream(tmp).use { out ->
                write(out)
                out.flush()
                out.fd.sync()
            }
            if (keepBackup && target.exists()) {
                val bak = File(target.parentFile, target.name + ".bak")
                bak.delete()
                if (!target.renameTo(bak)) target.copyTo(bak, overwrite = true)
            }
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
        }

        fun backupOf(target: File): File = File(target.parentFile, target.name + ".bak")

        /** The temp file [writeAtomically] writes before renaming it over [target]. */
        fun tempOf(target: File): File = File(target.parentFile, target.name + ".tmp")
    }
}
