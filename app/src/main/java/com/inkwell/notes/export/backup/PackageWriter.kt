package com.inkwell.notes.export.backup

import com.inkwell.notes.BuildConfig
import com.inkwell.notes.app.AppGraph
import com.inkwell.notes.core.model.Recording
import com.inkwell.notes.core.model.RecordingStatus
import com.inkwell.notes.core.model.TextSource
import com.inkwell.notes.core.storage.PageContentStore
import com.inkwell.notes.export.format.DocSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.coroutines.coroutineContext

/**
 * Writes a document as a native .inkwell package (see [PackageManifest]) to a stream,
 * reading one file at a time. The stream is left open.
 */
internal class PackageWriter(private val graph: AppGraph) {
    private val source = DocSource(graph)

    suspend fun write(docId: String, out: OutputStream, includeAudio: Boolean): Unit = withContext(Dispatchers.IO) {
        val doc = source.document(docId)
        val pages = source.pages(docId)
        val pageRows = graph.db.pages().forDocument(docId).associateBy { it.id }
        val outline = graph.db.outline().forDocument(docId).map { it.toModel() }
        val files = graph.files

        // Recordings: finished segments only (a segment being recorded is not playable yet).
        val recordings = graph.db.recordings().forDocument(docId).map { it.toModel() }
        val packagedRecordings = ArrayList<PackagedRecording>()
        val audioFiles = ArrayList<Pair<String, File>>()
        for (rec in recordings) {
            val live = rec.status == RecordingStatus.RECORDING || rec.status == RecordingStatus.PAUSED
            val finished = if (live) rec.segmentFiles.take(rec.segmentDurationsMs.size) else rec.segmentFiles
            val existing = finished.withIndex().filter { File(it.value).isFile }
            val transcriptRows = graph.db.transcripts().forRecording(rec.id)
            val transcript = transcriptRows.map { it.toModel() }
            val withAudio = includeAudio && existing.isNotEmpty()
            if (!withAudio && transcript.isEmpty()) continue
            val paths = ArrayList<String>()
            val durations = ArrayList<Long>()
            if (withAudio) {
                val used = HashSet<String>()
                for ((i, path) in existing) {
                    val f = File(path)
                    var name = f.name
                    if (!used.add(name)) name = "%03d_%s".format(java.util.Locale.ROOT, i, f.name).also { used += it }
                    val packagePath = PackageManifest.audioPath(rec.id, name)
                    paths += packagePath
                    durations += rec.segmentDurationsMs.getOrElse(i) { 0L }
                    audioFiles += packagePath to f
                }
            }
            val waveform = rec.waveformPath?.let { File(it) }?.takeIf { withAudio && it.isFile }
            val waveformPath = waveform?.let { PackageManifest.audioPath(rec.id, "waveform.bin").also { p -> audioFiles += p to it } }
            val packaged: Recording = rec.copy(
                segmentFiles = paths,
                segmentDurationsMs = durations,
                durationMs = if (withAudio && live) durations.sum() else rec.durationMs,
                status = if (live) RecordingStatus.INTERRUPTED else rec.status,
                endedAt = rec.endedAt ?: if (live) System.currentTimeMillis() else null,
                waveformPath = waveformPath,
            )
            packagedRecordings += PackagedRecording(packaged, withAudio, transcript, transcriptRows.firstOrNull()?.source)
        }

        val assetDir = files.assetsDir(docId)
        val assets = assetDir.listFiles()?.filter { it.isFile && !it.name.endsWith(".tmp") && !it.name.endsWith(".bak") }.orEmpty()
        val sourcePdf = files.sourcePdf(docId).takeIf { it.isFile }
        val typed = files.typedFile(docId).takeIf { it.isFile }
        val thumb = doc.thumbnailPath?.let { File(it) }?.takeIf { it.isFile }
        val text = graph.db.textIndex().forDocument(docId)
            .filter { it.pageId != null && it.source in PAGE_TEXT && it.text.isNotBlank() }
            .map { PackagedText(it.pageId, it.source, it.text, it.confidence, it.bounds, it.engine, it.timestampMs) }

        val manifest = PackageManifest(
            appVersion = BuildConfig.VERSION_NAME,
            exportedAt = System.currentTimeMillis(),
            document = doc.copy(thumbnailPath = null, lastSyncedAt = 0, sizeBytes = 0),
            pages = pages.map { p ->
                val row = pageRows[p.id]
                PackagedPage(p.copy(thumbnailPath = null), PackageManifest.pagePath(p.id), row != null && row.indexedAt >= row.modifiedAt && row.indexedAt > 0)
            },
            outline = outline,
            recordings = packagedRecordings,
            assets = assets.map { it.name },
            sourcePdf = sourcePdf?.let { PackageManifest.SOURCE_PDF },
            typed = typed?.let { PackageManifest.TYPED },
            thumbnail = thumb?.let { PackageManifest.THUMBNAIL },
            text = text,
        )

        // Closing finishes the zip and frees its Deflater; [out] is only flushed.
        ZipOutputStream(NonClosingOutputStream(out)).use { zip ->
            zip.setLevel(Deflater.DEFAULT_COMPRESSION)
            zip.putNextEntry(ZipEntry(PackageManifest.MANIFEST))
            zip.write(PackageManifest.encode(manifest).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            for (p in pages) {
                coroutineContext.ensureActive()
                val content = graph.pageStore.load(docId, p.id)
                zip.putNextEntry(ZipEntry(PackageManifest.pagePath(p.id)))
                zip.write(PageContentStore.encode(content))
                zip.closeEntry()
            }
            for (a in assets) copyFile(zip, PackageManifest.assetPath(a.name), a, compress = false)
            sourcePdf?.let { copyFile(zip, PackageManifest.SOURCE_PDF, it, compress = true) }
            typed?.let { copyFile(zip, PackageManifest.TYPED, it, compress = true) }
            for ((path, f) in audioFiles) {
                coroutineContext.ensureActive()
                copyFile(zip, path, f, compress = f.name.endsWith(".bin"))
            }
            thumb?.let { copyFile(zip, PackageManifest.THUMBNAIL, it, compress = false) }
        }
    }

    /** Media is stored with deflate level 0: already compressed, so only copied. */
    private fun copyFile(zip: ZipOutputStream, name: String, file: File, compress: Boolean) {
        zip.setLevel(if (compress) Deflater.DEFAULT_COMPRESSION else Deflater.NO_COMPRESSION)
        zip.putNextEntry(ZipEntry(name).apply { time = file.lastModified() })
        file.inputStream().use { it.copyTo(zip, 64 * 1024) }
        zip.closeEntry()
        zip.setLevel(Deflater.DEFAULT_COMPRESSION)
    }

    companion object {
        private val PAGE_TEXT = setOf(TextSource.HANDWRITING, TextSource.OCR, TextSource.PDF_TEXT, TextSource.TYPED)
    }
}
