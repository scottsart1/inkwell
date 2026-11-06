package com.inkwell.notes.speakers.work

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.inkwell.notes.R
import com.inkwell.notes.app.AppGraph
import com.inkwell.notes.audio.recorder.AudioFiles
import com.inkwell.notes.core.model.RecordingStatus
import com.inkwell.notes.core.model.SpeakerEngine
import com.inkwell.notes.core.storage.FileStore
import com.inkwell.notes.speakers.SpeakerAudio
import com.inkwell.notes.speakers.SpeakerAudioImpl
import com.inkwell.notes.speakers.SpeakerServiceImpl
import com.inkwell.notes.speakers.engine.SherpaVoiceEngine
import com.inkwell.notes.speakers.logic.AnalysisWindow
import com.inkwell.notes.speakers.logic.AudioPlan
import com.inkwell.notes.speakers.logic.DiarizationAssembler
import com.inkwell.notes.speakers.logic.DiarizationCheckpoint
import com.inkwell.notes.speakers.logic.FloatBuffer
import com.inkwell.notes.speakers.logic.Pcm
import com.inkwell.notes.speakers.logic.SpeakerModelSpecs
import com.inkwell.notes.speakers.logic.VoiceVector
import com.inkwell.notes.speakers.logic.Voiceprint
import com.inkwell.notes.speakers.logic.WindowAnalyzer
import com.inkwell.notes.speakers.voices.valueOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Works out who spoke when in one recording, on the device (engine DEVICE), after it was
 * recorded. The timeline is cut into windows of up to five minutes of consecutive segments;
 * each window is decoded, diarized and embedded on a background-priority thread, then
 * checkpointed (turns in speakers.partial.json next to the audio, window embeddings only in
 * the encrypted voice store), so a stopped job resumes at the next window. Finally the
 * windows' voices are linked into the recording's voices and handed to
 * [SpeakerServiceImpl.saveDiarization], which recognises known people and labels the
 * transcript. When the models are missing it queues their download and ends; it is queued
 * again when they arrive.
 */
class DiarizationWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val docId = inputData.getString(Diarizations.KEY_DOC) ?: return Result.failure()
        val recordingId = inputData.getString(Diarizations.KEY_RECORDING) ?: return Result.failure()
        val graph = AppGraph.get(applicationContext)
        val service = graph.speakers as? SpeakerServiceImpl ?: return Result.failure()
        val prefs = graph.settings.current.speakers
        if (prefs.engine != SpeakerEngine.DEVICE) return failure(R.string.speaker_engine_error_off)

        val entity = graph.db.recordings().get(recordingId) ?: return failure(R.string.speaker_engine_error_no_audio)
        if (entity.status == RecordingStatus.RECORDING || entity.status == RecordingStatus.PAUSED) return Result.retry()
        val rec = entity.toModel()

        if (!service.models.present() || !service.models.verifyOnce()) {
            if (service.models.present()) {
                // Present but unreadable (I/O error): downloading again would not help.
                return retryOrFail()
            }
            service.awaitModels(docId, recordingId)
            // Arrived meanwhile: run again shortly instead of waiting for a download that is done.
            if (service.models.present()) return Result.retry()
            return Result.success(workDataOf(Diarizations.KEY_WAITING to true))
        }

        val files = withContext(Dispatchers.IO) { AudioFiles.segments(graph.files, rec) }
        val available = withContext(Dispatchers.IO) { files.map { it.isFile && it.length() > 0 } }
        val windows = AudioPlan.windows(rec.segmentDurationsMs, available)
        if (windows.isEmpty()) return failure(R.string.speaker_engine_error_no_audio)

        val paths = files.map { it.absolutePath }
        val checkpointFile = checkpointFile(graph.files, docId, recordingId)
        // Window embeddings are checkpointed even when voices are not remembered: they are
        // temporary (encrypted, deleted when the job ends), and without them a recording that
        // takes longer than one job run to analyse would start over forever.
        val resumed = loadCheckpoint(service, checkpointFile, recordingId, paths, rec.segmentDurationsMs, windows.size)
        var checkpoint = resumed?.first
            ?: DiarizationCheckpoint(modelId = SpeakerModelSpecs.EMBEDDING_MODEL_ID, files = paths, durations = rec.segmentDurationsMs)
        val embeddings = HashMap<String, FloatArray>(resumed?.second.orEmpty())
        if (resumed == null) withContext(Dispatchers.IO) { service.store.deleteWork(recordingId) }

        val guard = service.nativeRuns
        var analysed = false

        val audio = graph.speakerAudio as? SpeakerAudioImpl
        // One native pass at a time in this process (see NativeRunGuard.pass); released in the finally.
        guard.pass.lock()
        val dispatcher = SherpaVoiceEngine.newDispatcher()
        var engine: SherpaVoiceEngine? = null
        try {
            // A native fault kills the process with nothing to catch, and the job would run (and
            // crash) again on every start: after repeated deaths inside native code, stop here.
            // Inside the try: a cancellation after the mark is written still reaches finish().
            if (!withContext(Dispatchers.IO) { guard.start(recordingId) }) {
                Log.e(TAG, "Identification kept stopping the app on this recording; not retried")
                runCatching { discard(service, checkpointFile, recordingId) }
                return failure(R.string.speaker_engine_error_crashed)
            }
            engine = withContext(dispatcher) {
                SherpaVoiceEngine(service.models.file(SpeakerModelSpecs.SEGMENTATION), service.models.file(SpeakerModelSpecs.EMBEDDING))
            }
            report(windows, checkpoint.windowsDone, 0f, force = true)
            for (w in windows.drop(checkpoint.windowsDone)) {
                val samples = decodeWindow(graph, audio, files, w)
                val e = engine
                val result = withContext(dispatcher) {
                    WindowAnalyzer.analyze(
                        windowIndex = w.index,
                        globalStartMs = w.globalStartMs,
                        samples = samples,
                        engine = e,
                        progress = { f -> report(windows, w.index, f, force = false) },
                        checkCancelled = { if (isStopped) throw CancellationException("stopped") },
                    )
                }
                embeddings.putAll(result.embeddings)
                checkpoint = checkpoint.copy(
                    windowsDone = w.index + 1,
                    turns = checkpoint.turns + result.turns,
                    voices = checkpoint.voices + result.voices,
                    failures = 0,
                )
                saveCheckpoint(service, checkpointFile, recordingId, checkpoint, embeddings)
                report(windows, w.index + 1, 0f, force = true)
            }
            analysed = true
        } catch (e: CancellationException) {
            // Cancelled by the user (or replaced): nothing resumes this run, so its temporary
            // voice data must not stay behind. A system stop keeps the checkpoint to resume from.
            if (stopReason == WorkInfo.STOP_REASON_CANCELLED_BY_APP) {
                withContext(NonCancellable) {
                    // Replaced ("Try again"): the newer run of this recording now owns the checkpoint
                    // and window embeddings, and cleans them up itself.
                    val newerRun = runCatching {
                        WorkManager.getInstance(applicationContext)
                            .getWorkInfosForUniqueWork(Diarizations.uniqueName(recordingId)).get()
                            .any { it.id != id && !it.state.isFinished }
                    }.getOrDefault(false)
                    if (!newerRun) runCatching { discard(service, checkpointFile, recordingId) }
                }
            }
            throw e
        } catch (e: LinkageError) {
            // The native library could not load (unsupported CPU or a broken install).
            Log.e(TAG, "Speaker models unavailable", e)
            return failure(R.string.speaker_engine_error_unsupported)
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "Out of memory while identifying speakers")
            return failedRun(service, checkpointFile, recordingId, checkpoint, embeddings)
        } catch (e: Exception) {
            Log.e(TAG, "Identification failed", e)
            return failedRun(service, checkpointFile, recordingId, checkpoint, embeddings)
        } finally {
            try {
                // One non-cancellable block: returning into the cancelled job between the steps
                // (a dispatcher switch) would throw there and skip the rest.
                withContext(NonCancellable) {
                    withContext(dispatcher) { engine?.close() }
                    dispatcher.close()
                    // Back from native code (whatever the outcome): this run did not crash the app.
                    withContext(Dispatchers.IO) { guard.finish(recordingId, completed = analysed) }
                }
            } finally {
                guard.pass.unlock()
            }
        }

        val final = checkpoint
        val result = withContext(Dispatchers.Default) {
            DiarizationAssembler.assemble(
                recordingId = recordingId,
                engineId = SpeakerModelSpecs.ENGINE_ID,
                turns = final.turns,
                voices = final.voices,
                embeddings = embeddings,
                totalMs = rec.durationMs.takeIf { it > 0 } ?: rec.segmentDurationsMs.sum(),
                expectedSpeakers = graph.settings.current.speakers.expectedSpeakers.coerceAtLeast(0),
            )
        }
        return try {
            service.saveDiarization(docId, result)
            discard(service, checkpointFile, recordingId)
            runCatching { service.notifyIfNamesNeeded(docId, recordingId) }.onFailure { Log.w(TAG, "Naming notification failed", it) }
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Saving the speakers failed", e)
            failedRun(service, checkpointFile, recordingId, final, embeddings)
        }
    }

    /** Decodes one window's pieces into contiguous 16 kHz audio aligned with the timeline. */
    private suspend fun decodeWindow(graph: AppGraph, audio: SpeakerAudioImpl?, files: List<File>, w: AnalysisWindow): FloatArray {
        val size = w.pieces.sumOf { Pcm.samplesFor(it.toMs, SpeakerAudio.SAMPLE_RATE) - Pcm.samplesFor(it.fromMs, SpeakerAudio.SAMPLE_RATE) }
        val out = FloatBuffer(size.coerceAtLeast(16))
        for (p in w.pieces) {
            val expected = Pcm.samplesFor(p.toMs, SpeakerAudio.SAMPLE_RATE) - Pcm.samplesFor(p.fromMs, SpeakerAudio.SAMPLE_RATE)
            val file = files.getOrNull(p.segment)
            val pcm = when {
                file == null -> null
                audio != null -> audio.decodeOrNull(file, p.fromMs, p.toMs)
                else -> runCatching { graph.speakerAudio.decode(file, p.fromMs, p.toMs) }.getOrNull()
            }
            out.add(Pcm.fitTo(pcm ?: FloatArray(0), expected))
        }
        // Sized exactly, so this hands over the buffer instead of copying ~38 MB.
        return out.finish()
    }

    @Volatile private var lastReport = 0L

    /** Progress in segments (pieces) done; throttled, callable from the engine thread. */
    private fun report(windows: List<AnalysisWindow>, windowsDone: Int, fraction: Float, force: Boolean) {
        // A stopped run still finishing its native pass must not overwrite its replacement's progress.
        if (isStopped) return
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastReport < PROGRESS_MS) return
        lastReport = now
        val (done, total) = DiarizationAssembler.progress(windows, windowsDone, fraction)
        setProgressAsync(workDataOf(Diarizations.KEY_DONE to done, Diarizations.KEY_TOTAL to total))
    }

    /** A checkpoint that matches this recording, with its window embeddings; null to start over. */
    private suspend fun loadCheckpoint(
        service: SpeakerServiceImpl,
        file: File,
        recordingId: String,
        paths: List<String>,
        durations: List<Long>,
        windows: Int,
    ): Pair<DiarizationCheckpoint, Map<String, FloatArray>>? = withContext(Dispatchers.IO) {
        val c = runCatching { if (file.isFile) DiarizationCheckpoint.decode(file.readText()) else null }.getOrNull()
            ?.takeIf { it.matches(SpeakerModelSpecs.EMBEDDING_MODEL_ID, paths, durations, windows) }
            ?: return@withContext null
        val stored = service.store.readWork(recordingId).valueOrNull()
        val embeddings = stored?.vectors?.associate { it.tag to it.vector }.orEmpty()
        if (c.consistentWith(embeddings.keys)) c to embeddings else null
    }

    private suspend fun saveCheckpoint(
        service: SpeakerServiceImpl,
        file: File,
        recordingId: String,
        c: DiarizationCheckpoint,
        embeddings: Map<String, FloatArray>,
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            // Embeddings first: a checkpoint never claims embeddings that were not stored.
            val dim = embeddings.values.firstOrNull()?.size
            if (dim != null) {
                val weights = c.voices.associate { it.id to it.speechMs.toFloat() }
                service.store.writeWork(
                    recordingId,
                    Voiceprint(SpeakerModelSpecs.EMBEDDING_MODEL_ID, dim, embeddings.map { (id, v) -> VoiceVector(id, weights[id] ?: 0f, v) }),
                )
            }
            FileStore.writeAtomically(file, keepBackup = false) { it.write(DiarizationCheckpoint.encode(c).toByteArray()) }
            true
        } catch (e: Exception) {
            Log.w(TAG, "Could not save identification progress: ${e.javaClass.simpleName}")
            false
        }
    }

    private fun failure(res: Int) = Result.failure(workDataOf(Diarizations.KEY_ERROR to applicationContext.getString(res)))

    /** Retries a few times. */
    private fun retryOrFail(): Result =
        if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else failure(R.string.speaker_engine_error_failed)

    /**
     * Analysis or saving failed with [c] as the progress so far: retries a few times in a row,
     * counted in the checkpoint (system stops and waits also raise runAttemptCount, and must
     * not use up the budget of a long recording). On giving up deletes the checkpoint and the
     * window embeddings: temporary voice data must not linger.
     */
    private suspend fun failedRun(
        service: SpeakerServiceImpl,
        file: File,
        recordingId: String,
        c: DiarizationCheckpoint,
        embeddings: Map<String, FloatArray>,
    ): Result {
        val failures = c.failures + 1
        if (failures < MAX_ATTEMPTS) {
            // When the count cannot be saved, fall back on the run count so this still ends.
            if (saveCheckpoint(service, file, recordingId, c.copy(failures = failures), embeddings) || runAttemptCount < MAX_ATTEMPTS) {
                return Result.retry()
            }
        }
        runCatching { discard(service, file, recordingId) }
        return failure(R.string.speaker_engine_error_failed)
    }

    /** Deletes the checkpoint and the window embeddings of [recordingId]. */
    private suspend fun discard(service: SpeakerServiceImpl, checkpointFile: File, recordingId: String) = withContext(Dispatchers.IO) {
        checkpointFile.delete()
        service.store.deleteWork(recordingId)
    }

    internal companion object {
        private const val TAG = "Diarization"
        private const val CHECKPOINT = "speakers.partial.json"
        private const val MAX_ATTEMPTS = 3
        private const val PROGRESS_MS = 500L

        /** The checkpoint of [recordingId]'s identification (turns only; no voice data). */
        fun checkpointFile(files: FileStore, docId: String, recordingId: String): File =
            File(files.audioDir(docId, recordingId), CHECKPOINT)
    }
}
