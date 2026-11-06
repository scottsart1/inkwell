package com.inkwell.notes.export.format

import android.content.Context
import android.graphics.Bitmap
import com.inkwell.notes.R
import com.inkwell.notes.core.model.DocKind
import com.inkwell.notes.core.model.DocumentMeta
import com.inkwell.notes.export.ExportFormat
import com.inkwell.notes.export.ExportOptions
import com.inkwell.notes.export.backup.NonClosingOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.coroutines.coroutineContext

/**
 * PNG/JPEG export: each page rendered at the chosen resolution (width = page width / 72 ×
 * dpi). One page is written as a single image; several pages as a zip of page_001.png ...
 */
internal class ImageExporter(private val context: Context, private val source: DocSource, private val raster: PageRaster) {

    /** Number of images [export] will produce. */
    suspend fun imageCount(doc: DocumentMeta, options: ExportOptions): Int =
        if (doc.kind == DocKind.TYPED) PageRange.resolve(options.pageIndices, typedLayout(doc).pageCount).size
        else PageRange.resolve(options.pageIndices, source.pages(doc.id).size).size

    suspend fun export(doc: DocumentMeta, options: ExportOptions, out: OutputStream, progress: (Int, Int) -> Unit) {
        val jpeg = options.format == ExportFormat.JPEG
        val renderers: List<Pair<Int, suspend () -> Bitmap>> = if (doc.kind == DocKind.TYPED) {
            val layout = typedLayout(doc)
            PageRange.resolve(options.pageIndices, layout.pageCount).map { i ->
                i to suspend { withContext(Dispatchers.Default) { layout.renderBitmap(i, RasterSizing.widthForDpi(layout.pageWidth, options.dpi)) } }
            }
        } else {
            val pages = source.pages(doc.id)
            PageRange.resolve(options.pageIndices, pages.size).map { i ->
                val page = pages[i]
                i to suspend {
                    val content = source.content(doc.id, page)
                    val (w, _) = raster.displaySize(page, content)
                    raster.render(doc.id, page, content, RasterSizing.widthForDpi(w, options.dpi), drawPaper = options.includeBackground, opaque = jpeg)
                }
            }
        }
        if (renderers.isEmpty()) throw ExportErrors.of(context, R.string.export_err_no_pages)

        if (renderers.size == 1) {
            val bmp = renderers[0].second()
            try {
                withContext(Dispatchers.IO) { compress(bmp, jpeg, options.jpegQuality, out) }
            } finally {
                bmp.recycle()
            }
            progress(1, 1)
            return
        }
        withContext(Dispatchers.IO) {
            // Closing finishes the zip and frees its Deflater; the caller's stream is only flushed.
            ZipOutputStream(NonClosingOutputStream(out)).use { zip ->
                renderers.forEachIndexed { n, (index, render) ->
                    coroutineContext.ensureActive()
                    val bmp = render()
                    val bytes = try {
                        ByteArrayOutputStream(bmp.byteCount / 6).also { compress(bmp, jpeg, options.jpegQuality, it) }.toByteArray()
                    } finally {
                        bmp.recycle()
                    }
                    // Images are already compressed: store them.
                    val entry = ZipEntry(ExportNaming.pageImageName(index, options.format)).apply {
                        method = ZipEntry.STORED
                        size = bytes.size.toLong()
                        compressedSize = bytes.size.toLong()
                        crc = CRC32().also { it.update(bytes) }.value
                    }
                    zip.putNextEntry(entry)
                    zip.write(bytes)
                    zip.closeEntry()
                    progress(n + 1, renderers.size)
                }
            }
        }
    }

    private suspend fun typedLayout(doc: DocumentMeta): TypedLayout {
        val typed = source.typed(doc.id)
        return withContext(Dispatchers.Default) { TypedLayout(context, doc.title, TypedBlocks.convert(typed), { source.assetFile(doc.id, it) }) }
    }

    companion object {
        fun compress(bmp: Bitmap, jpeg: Boolean, quality: Int, out: OutputStream) {
            val ok = if (jpeg) bmp.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(30, 100), out)
            else bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            if (!ok) throw java.io.IOException("Image encoding failed")
        }
    }
}
