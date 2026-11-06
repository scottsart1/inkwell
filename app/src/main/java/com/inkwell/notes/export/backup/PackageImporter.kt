package com.inkwell.notes.export.backup

import android.util.Log
import androidx.room.withTransaction
import com.inkwell.notes.app.AppGraph
import com.inkwell.notes.core.model.DocumentMeta
import com.inkwell.notes.core.model.PageContent
import com.inkwell.notes.core.model.RecognizedBlock
import com.inkwell.notes.core.model.TextSource
import com.inkwell.notes.core.naming.DateNaming
import com.inkwell.notes.core.naming.TitleRules
import com.inkwell.notes.core.storage.PageContentStore
import com.inkwell.notes.core.storage.db.DocumentEntity
import com.inkwell.notes.core.storage.db.OutlineEntity
import com.inkwell.notes.core.storage.db.PageEntity
import com.inkwell.notes.core.storage.db.RecordingEntity
import com.inkwell.notes.export.format.TextRow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.zip.ZipInputStream

/**
 * Imports a native .inkwell package as a new document: fresh ids for the document, pages,
 * recordings and outline (audio anchors follow), title (numbered when taken) and creation time kept. The package
 * is unpacked into a staging folder first and validated, so a damaged file never leaves a
 * half-imported document behind.
 */
internal class PackageImporter(private val graph: AppGraph) {

    suspend fun import(input: InputStream, folderId: String?): DocumentMeta = withContext(Dispatchers.IO) {
        val staging = File(graph.files.cacheDir, "import/" + UUID.randomUUID()).apply { mkdirs() }
        try {
            val budget = (graph.files.root.usableSpace - RESERVED_BYTES).coerceAtMost(MAX_PACKAGE_BYTES)
            if (budget <= 0) throw PackageException(PackageException.Reason.NO_SPACE)
            // When free space sets the limit, running into it means the device is full, not a bad package:
            // a restore stops there instead of quietly skipping every larger document.
            val overBudget = if (budget < MAX_PACKAGE_BYTES) PackageException.Reason.NO_SPACE else PackageException.Reason.TOO_LARGE
            val names = try {
                ZipSafety.extract(ZipInputStream(input), staging, budget, overBytes = overBudget)
            } catch (e: PackageException) {
                throw e
            } catch (e: java.util.zip.ZipException) {
                throw PackageException(PackageException.Reason.NOT_A_PACKAGE, e)
            }
            if (PackageManifest.MANIFEST !in names) throw PackageException(PackageException.Reason.NOT_A_PACKAGE)
            val manifest = PackageManifest.decode(File(staging, PackageManifest.MANIFEST).readText())
            install(manifest, staging, folderId)
        } finally {
            staging.deleteRecursively()
        }
    }

    private suspend fun install(m: PackageManifest, staging: File, requestedFolder: String?): DocumentMeta {
        val keptRecordings = m.recordings.filter { it.audioIncluded || it.transcript.isNotEmpty() }
        val remap = IdRemap.create(
            pageIds = m.pages.map { it.meta.id },
            recordingIds = keptRecordings.map { it.recording.id },
            outlineIds = m.outline.map { it.id },
        )
        val docId = remap.documentId
        val files = graph.files
        val now = System.currentTimeMillis()
        var committed = false
        try {
            // ---- Files
            val orderedPages = m.pages.sortedBy { it.meta.index }
            val pageMetas = orderedPages.mapIndexedNotNull { i, p -> remap.pageMeta(p.meta, i)?.let { p to it } }
            for ((p, meta) in pageMetas) {
                val bytes = ZipSafety.resolveInside(staging, p.content)?.takeIf { it.isFile }?.readBytes()
                val content = PageContentStore.decodeOrNull(bytes) ?: PageContent.EMPTY
                graph.pageStore.save(docId, meta.id, remap.content(content))
            }
            for (asset in m.assets) {
                val src = ZipSafety.resolveInside(staging, PackageManifest.assetPath(asset)) ?: continue
                if (src.isFile) move(src, files.assetFile(docId, asset))
            }
            m.sourcePdf?.let { ZipSafety.resolveInside(staging, it) }?.takeIf { it.isFile }?.let { move(it, files.sourcePdf(docId)) }
            m.typed?.let { ZipSafety.resolveInside(staging, it) }?.takeIf { it.isFile }?.let { move(it, files.typedFile(docId)) }
            val thumbnail = m.thumbnail?.let { ZipSafety.resolveInside(staging, it) }?.takeIf { it.isFile }?.let {
                File(files.docDir(docId), "thumb_$now.webp").also { dst -> move(it, dst) }
            }
            val recordings = keptRecordings.map { pr ->
                val newId = remap.recording(pr.recording.id)!!
                val dir = files.audioDir(docId, newId)
                val segs = ArrayList<String>()
                val durations = ArrayList<Long>()
                if (pr.audioIncluded) {
                    pr.recording.segmentFiles.forEachIndexed { i, path ->
                        val src = ZipSafety.resolveInside(staging, path)?.takeIf { it.isFile } ?: return@forEachIndexed
                        val dst = File(dir, File(path).name)
                        move(src, dst)
                        segs += dst.absolutePath
                        durations += pr.recording.segmentDurationsMs.getOrElse(i) { 0L }
                    }
                }
                val wave = pr.recording.waveformPath?.let { ZipSafety.resolveInside(staging, it) }?.takeIf { it.isFile }?.let {
                    File(dir, "waveform.bin").also { dst -> move(it, dst) }
                }
                pr to pr.recording.copy(
                    id = newId, documentId = docId, segmentFiles = segs, segmentDurationsMs = durations,
                    waveformPath = wave?.absolutePath,
                )
            }

            // ---- Rows (one transaction)
            val folder = requestedFolder?.let { id -> graph.db.folders().get(id)?.takeIf { it.trashedAt == null }?.id }
            val textByPage = m.text.filter { it.pageId != null }.groupBy { it.pageId!! }
            val meta = m.document.copy(
                id = docId,
                folderId = folder,
                trashedAt = null,
                pageCount = pageMetas.size,
                lastPageIndex = m.document.lastPageIndex.coerceIn(0, (pageMetas.size - 1).coerceAtLeast(0)),
                openedAt = 0,
                thumbnailPath = thumbnail?.absolutePath,
                sizeBytes = files.sizeOf(files.docDir(docId)),
                lastSyncedAt = 0,
                modifiedAt = maxOf(m.document.modifiedAt, m.document.createdAt),
            )
            val saved = graph.db.withTransaction {
                // Titles are unique in the library (synced folders are named after them), so a copy
                // restored beside its original becomes "Title (2)" like any other duplicate.
                val unique = meta.copy(title = DateNaming.unique(TitleRules.sanitize(meta.title) ?: DateNaming.UNTITLED, graph.db.documents().titlesExcept(docId)))
                graph.db.documents().insert(DocumentEntity.from(unique))
                graph.db.pages().upsertAll(pageMetas.map { (p, pm) ->
                    val textRestored = p.textCurrent && textByPage[p.meta.id].orEmpty().isNotEmpty()
                    PageEntity.from(pm, indexedAt = if (textRestored) maxOf(now, pm.modifiedAt) else 0L)
                })
                remap.outlineEntries(m.outline).forEach { graph.db.outline().upsert(OutlineEntity.from(it)) }
                for ((pr, rec) in recordings) {
                    graph.db.recordings().upsert(RecordingEntity.from(rec))
                    if (pr.transcript.isNotEmpty()) {
                        graph.db.transcripts().insertAll(PackagedTranscripts.entities(rec.id, pr.transcript, pr.transcriptSource))
                    }
                }
                unique
            }
            committed = true

            // ---- Search and follow-up work (never fails the import)
            withContext(NonCancellable) {
                restoreText(docId, textByPage, remap)
                for ((pr, rec) in recordings) {
                    if (pr.transcript.isNotEmpty()) quietly("transcript index") {
                        graph.search.replaceTranscript(docId, rec.id, pr.transcript, pr.transcriptSource ?: "import")
                    }
                }
                quietly("index") { graph.indexing.documentImported(docId) }
                quietly("meta") { graph.indexing.documentMetaChanged(docId) }
                if (thumbnail == null) quietly("thumbnail") { graph.thumbnails.refresh(docId) }
                quietly("sync") { graph.sync.onDocumentChanged(docId) }
            }
            return saved
        } catch (t: Throwable) {
            if (!committed) withContext(NonCancellable) { files.deleteDocument(docId) }
            throw t
        }
    }

    private suspend fun restoreText(docId: String, textByPage: Map<String, List<PackagedText>>, remap: IdRemap) {
        for ((oldPage, rows) in textByPage) {
            val page = remap.page(oldPage) ?: continue
            rows.groupBy { it.source }.forEach { (source, group) ->
                if (source == TextSource.TRANSCRIPT) return@forEach
                val blocks = group.map { RecognizedBlock(it.text, TextRow.parseBounds(it.bounds) ?: FloatArray(0), it.confidence) }
                quietly("text index") { graph.search.replacePageText(docId, page, source, blocks, group.first().engine.ifBlank { "import" }) }
            }
        }
    }

    private inline fun quietly(what: String, block: () -> Unit) {
        try {
            block()
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            Log.w(TAG, "Import follow-up '$what' failed (${t.javaClass.simpleName})")
        }
    }

    private fun move(src: File, dst: File) {
        dst.parentFile?.mkdirs()
        if (dst.exists()) dst.delete()
        if (!src.renameTo(dst)) {
            src.copyTo(dst, overwrite = true)
            src.delete()
        }
    }

    companion object {
        private const val TAG = "PackageImport"
        /** Free space kept for the rest of the app while unpacking. */
        private const val RESERVED_BYTES = 64L * 1024 * 1024
        private const val MAX_PACKAGE_BYTES = 16L * 1024 * 1024 * 1024
    }
}
