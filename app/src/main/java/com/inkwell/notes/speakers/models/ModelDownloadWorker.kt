package com.inkwell.notes.speakers.models

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.inkwell.notes.R
import com.inkwell.notes.app.AppGraph
import com.inkwell.notes.speakers.SpeakerServiceImpl
import com.inkwell.notes.speakers.logic.ModelFiles
import com.inkwell.notes.speakers.logic.ModelSpec
import com.inkwell.notes.speakers.logic.RangeResume
import com.inkwell.notes.speakers.logic.SpeakerModelSpecs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Downloads the pinned speaker models once. Each file streams into "<name>.part" while its
 * size and SHA-256 are measured; only a file that matches both is renamed into place. A
 * stopped or failed download resumes with an HTTP Range request from the partial file.
 */
class ModelDownloadWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    private class DownloadException(val retryable: Boolean, val userMessage: String) : Exception(userMessage)

    override suspend fun doWork(): Result {
        val graph = AppGraph.get(applicationContext)
        val service = graph.speakers as? SpeakerServiceImpl
        val models = service?.models ?: SpeakerModels(applicationContext, graph.appScope)
        val dir = models.dir
        return try {
            withContext(Dispatchers.IO) {
                dir.mkdirs()
                val missing = SpeakerModelSpecs.ALL.sumOf { s -> if (ModelFiles.present(dir, listOf(s))) 0L else s.sizeBytes - ModelFiles.bytesOnDisk(dir, listOf(s)) }
                if (missing > 0 && dir.usableSpace in 0 until missing + SPACE_MARGIN) {
                    throw DownloadException(false, applicationContext.getString(R.string.speaker_engine_model_error_storage))
                }
                var before = 0L
                for (spec in SpeakerModelSpecs.ALL) {
                    if (!ModelFiles.present(dir, listOf(spec))) download(graph, dir, spec, before)
                    before += spec.sizeBytes
                }
            }
            models.changed()
            service?.onModelsReady()
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: DownloadException) {
            Log.w(TAG, "Model download failed (retryable=${e.retryable})")
            models.changed()
            if (e.retryable && runAttemptCount < MAX_ATTEMPTS) Result.retry() else fail(service, e.userMessage)
        } catch (e: IOException) {
            Log.w(TAG, "Model download interrupted: ${e.javaClass.simpleName}")
            models.changed()
            if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else fail(service, applicationContext.getString(R.string.speaker_engine_model_error_network))
        }
    }

    private fun fail(service: SpeakerServiceImpl?, message: String): Result {
        service?.onModelsFailed()
        return Result.failure(workDataOf(KEY_ERROR to message))
    }

    private suspend fun download(graph: AppGraph, dir: File, spec: ModelSpec, before: Long) {
        val part = ModelFiles.part(dir, spec)
        val target = ModelFiles.target(dir, spec)
        // Two passes at most: the second one starts from scratch when the server refused the range.
        for (pass in 0 until 2) {
            val partBytes = if (part.isFile) part.length() else 0L
            if (partBytes == spec.sizeBytes) {
                // Complete but never promoted (stopped right before the rename).
                if (ModelFiles.promote(part, target, spec) == ModelFiles.Outcome.Ok) return
                continue
            }
            val from = ModelFiles.resumeFrom(partBytes, spec)
            if (from == 0L) part.delete()
            val request = Request.Builder()
                .url(spec.url)
                .header("Accept-Encoding", "identity")
                .apply { if (from > 0) header("Range", "bytes=$from-") }
                .build()
            val call = graph.http.newCall(request)
            val done = coroutineScope {
                // Cancelling the job cancels the call, which unblocks a read stalled on a dead
                // connection (a completion handler would only run once the read had returned).
                val finished = AtomicBoolean(false)
                val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
                    try { awaitCancellation() } finally { if (!finished.get()) call.cancel() }
                }
                try {
                    call.execute().use { response ->
                        val action = RangeResume.onResponse(response.code, from, response.header("Content-Range"))
                        val (append, start) = when (action) {
                            RangeResume.Action.Append -> (from > 0) to from
                            RangeResume.Action.Overwrite -> false to 0L
                            RangeResume.Action.Restart -> {
                                part.delete()
                                return@use false
                            }
                            is RangeResume.Action.Fail -> throw DownloadException(
                                action.retryable,
                                if (action.retryable) applicationContext.getString(R.string.speaker_engine_model_error_network)
                                else applicationContext.getString(R.string.speaker_engine_model_error_server, response.code),
                            )
                        }
                        val body = response.body ?: throw IOException("Empty body")
                        val md = MessageDigest.getInstance("SHA-256")
                        if (append) ModelFiles.digestPrefix(part, start, md)
                        var written = start
                        var lastProgress = 0L
                        body.byteStream().use { input ->
                            FileOutputStream(part, append).use { out ->
                                val buf = ByteArray(BUFFER)
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val n = input.read(buf)
                                    if (n < 0) break
                                    written += n
                                    if (written > spec.sizeBytes) {
                                        // Not the file we pinned: never keep it.
                                        out.close()
                                        part.delete()
                                        throw DownloadException(true, applicationContext.getString(R.string.speaker_engine_model_error_verify))
                                    }
                                    md.update(buf, 0, n)
                                    out.write(buf, 0, n)
                                    val now = SystemClock.elapsedRealtime()
                                    if (now - lastProgress >= PROGRESS_MS) {
                                        lastProgress = now
                                        setProgress(workDataOf(KEY_DONE to before + written, KEY_TOTAL to SpeakerModelSpecs.TOTAL_BYTES))
                                    }
                                }
                                out.flush()
                                out.fd.sync()
                            }
                        }
                        if (written < spec.sizeBytes) throw IOException("Connection closed early")
                        when (ModelFiles.finish(part, target, spec, written, ModelFiles.hex(md.digest()))) {
                            ModelFiles.Outcome.Ok -> {
                                setProgress(workDataOf(KEY_DONE to before + spec.sizeBytes, KEY_TOTAL to SpeakerModelSpecs.TOTAL_BYTES))
                                true
                            }
                            else -> throw DownloadException(true, applicationContext.getString(R.string.speaker_engine_model_error_verify))
                        }
                    }
                } catch (e: IOException) {
                    // The call was cancelled because the job was: that is a stop, not a network error.
                    currentCoroutineContext().ensureActive()
                    throw e
                } finally {
                    finished.set(true)
                    watcher.cancel()
                }
            }
            if (done) return
        }
        throw DownloadException(true, applicationContext.getString(R.string.speaker_engine_model_error_network))
    }

    companion object {
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_ERROR = "error"
        private const val TAG = "SpeakerModelDownload"
        private const val MAX_ATTEMPTS = 8
        private const val BUFFER = 64 * 1024
        private const val PROGRESS_MS = 300L
        private const val SPACE_MARGIN = 16L * 1024 * 1024
    }
}
