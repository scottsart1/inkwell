package com.inkwell.notes.export.backup

import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.inkwell.notes.BuildConfig
import com.inkwell.notes.R
import com.inkwell.notes.app.AppGraph
import com.inkwell.notes.core.model.AppSettings
import com.inkwell.notes.core.model.Folder
import com.inkwell.notes.core.settings.SettingsRepository
import com.inkwell.notes.core.storage.db.FolderEntity
import com.inkwell.notes.export.BackupReport
import com.inkwell.notes.export.RestoreReport
import com.inkwell.notes.export.format.ExportErrors
import com.inkwell.notes.export.format.SafStreams
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.coroutines.coroutineContext

/**
 * Whole-library backup: one zip holding library.json (folders, where each document lives,
 * preferences without secrets) followed by one .inkwell package per document. Packages are
 * built one at a time in a temp file and streamed into the zip, so memory use stays flat
 * and a document that fails to package never corrupts the backup.
 */
internal class LibraryBackup(private val graph: AppGraph) {
    private val context get() = graph.context
    private val writer = PackageWriter(graph)
    private val importer = PackageImporter(graph)

    // ------------------------------------------------------------------ Backup

    suspend fun backupTo(target: Uri, includeAudio: Boolean): BackupReport = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        var written: Uri? = null
        var ok = false
        try {
            val resolved = resolveTarget(target, now)
            val fileUri = resolved.uri
            written = fileUri
            val out = SafStreams.openForWrite(context, fileUri) ?: throw ExportErrors.of(context, R.string.export_err_backup_target)
            val counter = CountingOutputStream(BufferedOutputStream(out, 256 * 1024))
            var count = 0
            var skipped = 0
            // Closing the zip frees its Deflater; closing the counter closes the file even when that fails.
            counter.use { stream -> ZipOutputStream(stream).use { zip ->
                val docs = graph.db.documents().getAll().map { it.toModel() }.filter { it.trashedAt == null }.sortedBy { it.createdAt }
                val folders = graph.db.folders().getAllIncludingTrashed().map { it.toModel() }.filter { it.trashedAt == null }
                val paths = BackupNaming.folderPaths(folders)
                val entries = docs.map { BackupEntry(it.id, it.title, BackupNaming.entryName(it.title, it.id), it.folderId?.let { f -> paths[f] }.orEmpty()) }
                val manifest = LibraryManifest(
                    createdAt = now,
                    appVersion = BuildConfig.VERSION_NAME,
                    folders = folders.map { f -> BackupFolder(f.id, f.name, f.parentId, f.color, f.icon, f.sortOrder, paths[f.id].orEmpty()) },
                    documents = entries,
                    settings = settingsJson(graph.settings.current),
                )
                zip.setLevel(Deflater.DEFAULT_COMPRESSION)
                zip.putNextEntry(ZipEntry(LibraryManifest.FILE))
                zip.write(LibraryManifest.serializer().let { s -> PackageManifest.json.encodeToString(s, manifest) }.toByteArray(Charsets.UTF_8))
                zip.closeEntry()

                val tmpDir = File(graph.files.cacheDir, "backup-tmp").apply { mkdirs() }
                // Packages left behind by a backup that was killed part-way through.
                tmpDir.listFiles()?.filter { it.isFile && now - it.lastModified() > STALE_TEMP_MS }?.forEach { it.delete() }
                for (entry in entries) {
                    coroutineContext.ensureActive()
                    val tmp = File(tmpDir, UUID.randomUUID().toString() + ".inkwell")
                    try {
                        // A document that cannot be packaged is skipped; failing to write the backup itself is fatal.
                        val packaged = try {
                            tmp.outputStream().buffered().use { writer.write(entry.documentId, it, includeAudio) }
                            true
                        } catch (c: CancellationException) {
                            throw c
                        } catch (e: Exception) {
                            if (ExportErrors.isNoSpace(e)) throw e
                            Log.w(TAG, "Skipped a document in the backup (${e.javaClass.simpleName})")
                            skipped++
                            false
                        }
                        if (packaged) {
                            // Packages are zips already: copy them in without recompressing.
                            zip.setLevel(Deflater.NO_COMPRESSION)
                            zip.putNextEntry(ZipEntry(entry.entry))
                            tmp.inputStream().use { it.copyTo(zip, 256 * 1024) }
                            zip.closeEntry()
                            count++
                        }
                    } finally {
                        tmp.delete()
                    }
                }
            } }
            // Only a complete backup gets a name that restore and pruning recognise. A refused
            // rename never costs the finished backup: it is copied into a file with that name.
            val finished = resolved.finalName?.let { name ->
                rename(fileUri, name) ?: copyToFinal(target, fileUri, name)
            } ?: fileUri
            written = finished
            ok = true
            BackupReport(count, counter.count, finished, skipped)
        } catch (t: Throwable) {
            throw if (t is CancellationException) t else ExportErrors.wrap(context, t)
        } finally {
            // Never leave a partial backup behind that could later be mistaken for a good one.
            val partial = written
            if (!ok && partial != null) withContext(NonCancellable) { runCatching { DocumentsContract.deleteDocument(context.contentResolver, partial) } }
        }
    }

    /** Where the backup is written, and the name it gets once complete (null: written in place). */
    private class Target(val uri: Uri, val finalName: String?)

    /**
     * A folder tree gets a new "Inkwell-backup-partial-YYYYMMDD-HHmm.zip" that is renamed to
     * "Inkwell-backup-YYYYMMDD-HHmm.zip" when complete, so a backup cut short by the process
     * being killed is never taken for a good one. Providers that cannot rename get the final
     * name directly; a document Uri is written directly.
     */
    private fun resolveTarget(target: Uri, now: Long): Target {
        if (!isTree(target)) return Target(target, null)
        val tree = DocumentFile.fromTreeUri(context, target)
        if (tree == null || !tree.canWrite()) throw ExportErrors.of(context, R.string.export_err_folder)
        val finalName = BackupNaming.fileName(now)
        val partial = tree.createFile("application/zip", BackupNaming.partialFileName(now))
            ?: throw ExportErrors.of(context, R.string.export_err_backup_target)
        if (supportsRename(partial.uri)) return Target(partial.uri, finalName)
        runCatching { partial.delete() }
        val file = tree.createFile("application/zip", finalName)
            ?: throw ExportErrors.of(context, R.string.export_err_backup_target)
        return Target(file.uri, null)
    }

    /**
     * Fallback when the provider refused to rename the finished [partial] backup in [tree]:
     * copies it into a new file named [name] and deletes the partial one. Throws (leaving no
     * half-written copy behind) when the copy cannot be made; the caller then removes [partial].
     */
    private fun copyToFinal(tree: Uri, partial: Uri, name: String): Uri {
        val dir = DocumentFile.fromTreeUri(context, tree)
        val copy = dir?.createFile("application/zip", name) ?: throw ExportErrors.of(context, R.string.export_err_backup_target)
        try {
            val out = SafStreams.openForWrite(context, copy.uri) ?: throw ExportErrors.of(context, R.string.export_err_backup_target)
            out.use { o ->
                val input = context.contentResolver.openInputStream(partial) ?: throw ExportErrors.of(context, R.string.export_err_backup_target)
                input.use { it.copyTo(o, 256 * 1024) }
            }
        } catch (t: Throwable) {
            runCatching { copy.delete() }
            throw t
        }
        // Left behind only if this fails too; pruning removes stale partial files.
        runCatching { DocumentsContract.deleteDocument(context.contentResolver, partial) }
        return copy.uri
    }

    private fun supportsRename(uri: Uri): Boolean = try {
        context.contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_FLAGS), null, null, null)?.use { c ->
            c.moveToFirst() && (c.getInt(0) and DocumentsContract.Document.FLAG_SUPPORTS_RENAME) != 0
        } ?: false
    } catch (e: Exception) {
        false
    }

    /** The renamed document's Uri (it may change), or null when the provider refused. */
    private fun rename(uri: Uri, name: String): Uri? = try {
        DocumentsContract.renameDocument(context.contentResolver, uri, name)
    } catch (e: Exception) {
        Log.w(TAG, "Backup could not be renamed (${e.javaClass.simpleName})")
        null
    }

    private fun isTree(uri: Uri): Boolean = try {
        DocumentsContract.isTreeUri(uri) && !DocumentsContract.isDocumentUri(context, uri)
    } catch (e: Exception) {
        false
    }

    private fun settingsJson(s: AppSettings) =
        SettingsRepository.json.encodeToJsonElement(AppSettings.serializer(), SettingsSanitizer.forBackup(s)).jsonObject

    // ------------------------------------------------------------------ Restore

    suspend fun restoreFrom(uri: Uri): RestoreReport = withContext(Dispatchers.IO) {
        try {
            if (firstEntry(uri) == PackageManifest.MANIFEST) {
                // Not a backup but a single package: import it as one document.
                open(uri).use { importer.import(it, null) }
                return@withContext RestoreReport(1)
            }
            open(uri).use { restore(it) }
        } catch (t: Throwable) {
            throw when {
                t is CancellationException -> t
                t is PackageException && t.reason == PackageException.Reason.NOT_A_PACKAGE ->
                    ExportErrors.of(context, R.string.export_err_backup_invalid)
                else -> ExportErrors.wrap(context, t)
            }
        }
    }

    private fun open(uri: Uri): InputStream =
        (context.contentResolver.openInputStream(uri) ?: throw PackageException(PackageException.Reason.UNREADABLE)).buffered(256 * 1024)

    private fun firstEntry(uri: Uri): String? = try {
        open(uri).use { ZipInputStream(it).nextEntry?.name?.let(ZipSafety::safeEntryPath) }
    } catch (e: java.util.zip.ZipException) {
        throw PackageException(PackageException.Reason.NOT_A_PACKAGE, e)
    }

    private suspend fun restore(input: InputStream): RestoreReport {
        val libraryWasEmpty = graph.db.documents().getAll().isEmpty()
        val zip = ZipInputStream(input)
        var library: LibraryManifest? = null
        var restored = 0
        var skipped = 0
        var sawContent = false
        val folderCache = HashMap<List<String>, String?>()
        while (true) {
            coroutineContext.ensureActive()
            val entry = try {
                zip.nextEntry
            } catch (e: java.util.zip.ZipException) {
                if (!sawContent) throw PackageException(PackageException.Reason.NOT_A_PACKAGE, e) else throw PackageException(PackageException.Reason.CORRUPT, e)
            } ?: break
            if (entry.isDirectory) continue
            val name = ZipSafety.safeEntryPath(entry.name) ?: continue
            when {
                name == LibraryManifest.FILE -> {
                    sawContent = true
                    library = runCatching {
                        PackageManifest.json.decodeFromString(LibraryManifest.serializer(), zip.readBytes().decodeToString())
                    }.getOrNull()?.takeIf { it.format == LibraryManifest.FORMAT_ID }
                    // Recreate the whole folder tree, including folders without documents.
                    library?.folders?.sortedBy { it.path.size }?.forEach { f ->
                        if (f.path.isNotEmpty() && f.path !in folderCache) folderCache[f.path] = ensureFolderPath(f.path, library)
                    }
                }
                name.endsWith(".inkwell", ignoreCase = true) -> {
                    sawContent = true
                    val path = library?.documents?.firstOrNull { it.entry == name }?.folderPath.orEmpty()
                    val folderId = if (path in folderCache) folderCache[path] else ensureFolderPath(path, library).also { folderCache[path] = it }
                    try {
                        importer.import(NonClosingInputStream(zip), folderId)
                        restored++
                    } catch (c: CancellationException) {
                        throw c
                    } catch (e: Exception) {
                        if (e is PackageException && e.reason == PackageException.Reason.NO_SPACE) throw e
                        if (ExportErrors.isNoSpace(e)) throw e
                        Log.w(TAG, "Skipped a document while restoring (${e.javaClass.simpleName})")
                        skipped++
                    }
                }
            }
        }
        if (!sawContent) throw PackageException(PackageException.Reason.NOT_A_PACKAGE)
        if (libraryWasEmpty) library?.settings?.let { applySettings(it) }
        return RestoreReport(restored, skipped)
    }

    /** Finds the folder at [path] (matching names case-insensitively) or creates the missing part. */
    private suspend fun ensureFolderPath(path: List<String>, library: LibraryManifest?): String? {
        if (path.isEmpty()) return null
        var parentId: String? = null
        val prefix = ArrayList<String>()
        for (name in path) {
            prefix += name
            val existing = graph.db.folders().children(parentId).firstOrNull { it.name.equals(name, ignoreCase = true) && it.trashedAt == null }
            parentId = existing?.id ?: createFolder(name, parentId, library?.folders?.firstOrNull { it.path == prefix })
                ?: return parentId
        }
        return parentId
    }

    private suspend fun createFolder(name: String, parentId: String?, style: BackupFolder?): String? {
        return try {
            graph.library.createFolder(name, parentId, style?.color ?: 0, style?.icon ?: "folder").id
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            // The repository could not create it; write the row directly so documents keep their place.
            try {
                val now = System.currentTimeMillis()
                val f = Folder(UUID.randomUUID().toString(), name, parentId, style?.color ?: 0, style?.icon ?: "folder", now, now, style?.sortOrder ?: 0)
                graph.db.folders().upsert(FolderEntity.from(f))
                f.id
            } catch (e: Exception) {
                Log.w(TAG, "Folder could not be recreated (${e.javaClass.simpleName})")
                null
            }
        }
    }

    private suspend fun applySettings(json: kotlinx.serialization.json.JsonObject) {
        try {
            val restored = SettingsRepository.json.decodeFromJsonElement(AppSettings.serializer(), json)
            graph.settings.update { current -> SettingsSanitizer.merge(current, restored) }
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            Log.w(TAG, "Backup preferences not applied (${e.javaClass.simpleName})")
        }
    }

    companion object {
        private const val TAG = "LibraryBackup"
        /** A running backup touches or reads its temp package within minutes; older ones are leftovers. */
        private const val STALE_TEMP_MS = 6L * 60 * 60 * 1000
    }
}
