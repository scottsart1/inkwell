package com.inkwell.notes.ocr.work

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.util.Log
import com.inkwell.notes.core.storage.FileStore
import com.inkwell.notes.ocr.text.OcrScaling
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** OCR of one image or scanned page, boxes normalised to 0..1 of the image. */
@Serializable
internal data class CachedImageOcr(
    val engine: String,
    val blocks: List<CachedBlock>,
    /** Mean confidence of the local OCR; null when unknown (or for AI results). */
    val confidence: Float? = null,
    /** A vision model already looked at this image and found no text: never ask (and pay) again. */
    val aiChecked: Boolean = false,
) {
    @Serializable
    data class CachedBlock(val text: String, val box: List<Float>, val confidence: Float? = null)

    val isAi: Boolean get() = engine.startsWith("ai:")

    /** Local OCR below [threshold] mean confidence that no vision model has looked at yet. */
    fun needsAiAssist(threshold: Float): Boolean {
        val mean = confidence ?: return false
        return !isAi && !aiChecked && mean < threshold
    }
}

/**
 * Recognised text of embedded images and scanned PDF pages, keyed by document, item and file
 * size, so an image placed on many pages, or a scanned page re-indexed after an annotation, is
 * read only once (and an AI transcription is never paid for twice). Kept in no-backup storage
 * and pruned when documents disappear.
 */
internal class ImageOcrCache(private val dir: File) {
    private val json = Json { ignoreUnknownKeys = true }

    private fun file(docId: String, key: String, size: Long) =
        File(dir, "${FileStore.safe(docId)}$SEP${FileStore.safe(key)}$SEP$size.json")

    suspend fun get(docId: String, key: String, size: Long): CachedImageOcr? = withContext(Dispatchers.IO) {
        val f = file(docId, key, size)
        if (!f.exists()) return@withContext null
        try {
            json.decodeFromString(CachedImageOcr.serializer(), f.readText())
        } catch (e: Exception) {
            f.delete()
            null
        }
    }

    suspend fun put(docId: String, key: String, size: Long, value: CachedImageOcr) = withContext(Dispatchers.IO) {
        try {
            dir.mkdirs()
            // Older results for the same item (previous file sizes) are obsolete.
            val prefix = "${FileStore.safe(docId)}$SEP${FileStore.safe(key)}$SEP"
            dir.listFiles()?.filter { it.name.startsWith(prefix) }?.forEach { it.delete() }
            val bytes = json.encodeToString(CachedImageOcr.serializer(), value).encodeToByteArray()
            FileStore.writeAtomically(file(docId, key, size), keepBackup = false) { it.write(bytes) }
        } catch (e: Exception) {
            Log.w(TAG, "Could not cache OCR: ${e.javaClass.simpleName}")
        }
    }

    /** Forgets a document's cached results (when it is deleted). */
    suspend fun removeDocument(docId: String) = withContext(Dispatchers.IO) {
        val prefix = "${FileStore.safe(docId)}$SEP"
        dir.listFiles()?.filter { it.name.startsWith(prefix) }?.forEach { it.delete() }
        Unit
    }

    /** Deletes results of documents not in [liveDocIds]. */
    suspend fun prune(liveDocIds: Set<String>) = withContext(Dispatchers.IO) {
        val live = liveDocIds.mapTo(HashSet()) { FileStore.safe(it) }
        dir.listFiles()?.forEach { f ->
            val doc = f.name.substringBefore(SEP, missingDelimiterValue = "")
            if (doc.isEmpty() || doc !in live) f.delete()
        }
        Unit
    }

    companion object {
        private const val TAG = "OcrCache"
        private const val SEP = "__"
        /** Images smaller than this (px, longest edge) carry no readable text. */
        const val MIN_EDGE = 48

        /** Decodes [file] subsampled for OCR; null when unreadable, tiny or out of memory. */
        fun decodeForOcr(file: File): Bitmap? = try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || maxOf(bounds.outWidth, bounds.outHeight) < MIN_EDGE) {
                null
            } else {
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = OcrScaling.sampleSize(bounds.outWidth, bounds.outHeight)
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                BitmapFactory.decodeFile(file.path, opts)?.let(::onWhite)
            }
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "Image too large to read")
            null
        } catch (e: Exception) {
            null
        }

        /**
         * [src] composited onto white when it has an alpha channel. The recogniser ignores alpha,
         * so the transparent background of a PNG (stored as transparent black) would read as
         * black and swallow dark text. [src] is recycled when a copy is returned.
         */
        private fun onWhite(src: Bitmap): Bitmap {
            if (!src.hasAlpha()) return src
            val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
            out.eraseColor(Color.WHITE)
            Canvas(out).drawBitmap(src, 0f, 0f, null)
            out.setHasAlpha(false)
            src.recycle()
            return out
        }
    }
}
