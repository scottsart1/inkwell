package com.inkwell.notes.audio.transcription

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.inkwell.notes.R
import com.inkwell.notes.ai.provider.DiarizeReferences
import com.inkwell.notes.app.AppGraph
import com.inkwell.notes.audio.logic.TranscriptAssembly
import com.inkwell.notes.audio.logic.TranscriptCheckpoint
import com.inkwell.notes.audio.recorder.AudioFiles
import com.inkwell.notes.core.model.AiException
import com.inkwell.notes.core.model.AiTask
import com.inkwell.notes.core.model.RecordingStatus
import com.inkwell.notes.core.model.SpeakerEngine
import com.inkwell.notes.core.model.TranscriptStatus
import com.inkwell.notes.core.storage.FileStore
import com.inkwell.notes.core.storage.db.TranscriptSegmentEntity
import com.inkwell.notes.speakers.SpeakerServiceImpl
import com.inkwell.notes.speakers.cloud.CloudLabelMapper
import com.inkwell.notes.speakers.cloud.CloudReferenceSource
import com.inkwell.notes.speakers.cloud.CloudSpeakerState
import com.inkwell.notes.speakers.cloud.CloudTurns
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Transcribes one recording with the user's TRANSCRIPTION model, one audio segment per
 * request. Progress is checkpointed after every segment (transcript.partial.json next to
 * the audio), so a retry after a network failure or a stopped job resumes instead of
 * uploading audio again. On success the transcript replaces any previous one, is indexed
 * for search and handed to sync.
 *
 * When speakers are set to be labelled by OpenAI, each segment goes to
 * gpt-4o-transcribe-diarize instead, with short reference clips of voices already heard in
 * this recording and of known people, so a voice keeps one label ("S1") across segments; the
 * speakers found are handed to the speaker service. Otherwise the speaker service labels the
 * stored transcript itself (on-device identification).
 */
class TranscriptionWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val docId = inputData.getString(Transcriptions.KEY_DOC) ?: return Result.failure()
        val recordingId = inputData.getString(Transcriptions.KEY_RECORDING) ?: return Result.failure()
        val graph = AppGraph.get(applicationContext)
        val recordings = graph.db.recordings()

        val entity = recordings.get(recordingId) ?: return Result.failure(failureData(R.string.audio_no_audio))
        if (entity.status == RecordingStatus.RECORDING || entity.status == RecordingStatus.PAUSED) {
            // Still being recorded (or awaiting recovery): try again later.
            return Result.retry()
        }
        val engine = graph.settings.current.speakers.engine
        val canDiarize = engine == SpeakerEngine.OPENAI && runCatching { graph.ai.canDiarize() }.getOrDefault(false)
        if (!canDiarize && !graph.ai.isAvailable(AiTask.TRANSCRIPTION)) {
            recordings.setTranscriptStatus(recordingId, TranscriptStatus.FAILED.name)
            return Result.failure(failureData(R.string.audio_transcribe_unavailable))
        }
        val rec = entity.toModel()
        val files = withContext(Dispatchers.IO) { AudioFiles.segments(graph.files, rec) }
        val durations = rec.segmentDurationsMs
        if (files.isEmpty() || files.none { it.isFile && it.length() > 0 }) {
            recordings.setTranscriptStatus(recordingId, TranscriptStatus.FAILED.name)
            return Result.failure(failureData(R.string.audio_transcribe_no_audio))
        }

        recordings.setTranscriptStatus(recordingId, TranscriptStatus.RUNNING.name)
        val checkpointFile = File(graph.files.audioDir(docId, recordingId), AudioFiles.TRANSCRIPT_CHECKPOINT)
        val paths = files.map { it.absolutePath }
        val saved = withContext(Dispatchers.IO) { readCheckpoint(checkpointFile) }?.takeIf { it.first.matches(paths) }
        val plan = SpeakerJobPlan.plan(engine, canDiarize, saved?.first?.done ?: 0, saved?.second?.diarized == true)
        var checkpoint = saved?.first?.takeIf { plan.resume } ?: TranscriptCheckpoint(paths)
        var speakers = saved?.second?.takeIf { plan.resume && !plan.stripLabels } ?: CloudSpeakerState()
        if (plan.stripLabels) checkpoint = JobCheckpoint.withoutSpeakers(checkpoint)
        speakers = speakers.copy(diarized = plan.cloud)
        var note: String? = if (plan.cloudUnavailable) applicationContext.getString(R.string.speakers_cloud_unavailable) else null
        val references = if (plan.cloud) cloudReferences(graph, docId, recordingId) else null

        try {
            setProgress(workDataOf(Transcriptions.KEY_DONE to checkpoint.done, Transcriptions.KEY_TOTAL to files.size))
            for (i in checkpoint.done until files.size) {
                val file = files[i]
                val duration = durations.getOrNull(i) ?: 0L
                val offset = TranscriptAssembly.offsetOf(durations, i)
                val pieces = when {
                    !file.isFile || file.length() == 0L -> emptyList()
                    references != null -> {
                        val refs = references.referencesFor(speakers.voices)
                        val t = graph.ai.transcribeDiarized(recordingId, file, duration, offset, refs.references, checkpoint.language)
                            ?: throw AiException(applicationContext.getString(R.string.audio_transcribe_unavailable))
                        if (checkpoint.source == null) checkpoint = checkpoint.copy(source = t.source)
                        if (checkpoint.language == null) checkpoint = checkpoint.copy(language = t.language)
                        // Already on the recording timeline; labels are local to this file until mapped.
                        val blocks = t.segments.filter { it.text.isNotBlank() }.map { it.copy(text = it.text.trim(), speakerLabel = null) }
                        val assigned = CloudLabelMapper.assign(speakers.voices, refs, blocks, ::unnamed)
                        speakers = speakers.copy(voices = assigned.voices)
                        assigned.segments
                    }
                    else -> {
                        val t = graph.ai.transcribe(recordingId, listOf(file), listOf(duration), checkpoint.language)
                            ?: throw AiException(applicationContext.getString(R.string.audio_transcribe_unavailable))
                        if (checkpoint.source == null) checkpoint = checkpoint.copy(source = t.source)
                        if (checkpoint.language == null) checkpoint = checkpoint.copy(language = t.language)
                        TranscriptAssembly.shift(t.segments, offset, duration)
                    }
                }
                checkpoint = checkpoint.copy(done = i + 1, segments = checkpoint.segments + pieces)
                if (plan.cloud) speakers = speakers.copy(voices = CloudTurns.refresh(speakers.voices, checkpoint.segments))
                val snapshot = checkpoint
                val speakerSnapshot = speakers
                if (recordings.get(recordingId) == null) {
                    // Deleted meanwhile (with its document): leave nothing of it on disk.
                    withContext(Dispatchers.IO) { checkpointFile.delete() }
                    return Result.failure(failureData(R.string.audio_no_audio))
                }
                withContext(Dispatchers.IO) { writeCheckpoint(checkpointFile, snapshot, speakerSnapshot) }
                setProgress(workDataOf(Transcriptions.KEY_DONE to i + 1, Transcriptions.KEY_TOTAL to files.size))
            }

            val source = checkpoint.source ?: "openai"
            val segments = TranscriptAssembly.distinctStarts(checkpoint.segments)
            graph.db.transcripts().replace(
                recordingId,
                segments.map {
                    TranscriptSegmentEntity(
                        recordingId = recordingId, startMs = it.startMs, endMs = it.endMs, text = it.text,
                        speaker = it.speaker, source = source, speakerLabel = it.speakerLabel,
                    )
                },
            )
            recordings.setTranscriptStatus(recordingId, TranscriptStatus.DONE.name)
            if (plan.cloud) {
                val total = durations.sumOf { it.coerceAtLeast(0) }.takeIf { it > 0 } ?: rec.durationMs
                val result = CloudTurns.result(recordingId, segments, speakers.voices, total)
                if (hook("Saving speakers") { graph.speakers.saveDiarization(docId, result) }) {
                    // Same "voices to name" notice the device engine posts (it applies the app-lock,
                    // foreground and permission rules itself).
                    hook("Naming notification") { (graph.speakers as? SpeakerServiceImpl)?.notifyIfNamesNeeded(docId, recordingId) }
                } else {
                    note = applicationContext.getString(R.string.speakers_cloud_not_saved)
                }
            } else {
                hook("Speaker labelling") { graph.speakers.onTranscriptReady(docId, recordingId) }
            }
            // The speaker service may have renamed blocks (recognised people): index what is stored.
            val stored = hookValue { graph.db.transcripts().forRecording(recordingId).map { it.toModel() } }
                ?.takeIf { it.isNotEmpty() } ?: segments
            hook("Indexing the transcript") { graph.search.replaceTranscript(docId, recordingId, stored, source) }
            hook("Sync hook") { graph.sync.onTranscriptReady(docId, recordingId) }
            withContext(Dispatchers.IO) { checkpointFile.delete() }
            return Result.success(note?.let { workDataOf(Transcriptions.KEY_SPEAKER_NOTE to it) } ?: Data.EMPTY)
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                // Stopped by the system: it will run again. Cancelled by the user: the UI already cleared it.
                recordings.setTranscriptStatusIf(recordingId, listOf(TranscriptStatus.RUNNING.name), TranscriptStatus.QUEUED.name)
            }
            throw e
        } catch (e: AiException) {
            Log.w(TAG, "Transcription failed (retryable=${e.retryable})")
            return retryOrFail(graph, recordingId, e.retryable, e.message)
        } catch (e: IOException) {
            Log.w(TAG, "Transcription I/O failure", e)
            return retryOrFail(graph, recordingId, retryable = true, message = null)
        } catch (e: Exception) {
            Log.e(TAG, "Transcription crashed", e)
            // E.g. the transcript no longer fits a recording deleted meanwhile: drop its progress too.
            if (hookValue { recordings.get(recordingId) == null } == true) withContext(Dispatchers.IO) { checkpointFile.delete() }
            return retryOrFail(graph, recordingId, retryable = false, message = null)
        }
    }

    /**
     * References for one job: known people from the speaker service (loaded once) and clips of
     * this recording (kept in memory only, never written or logged).
     */
    private fun cloudReferences(graph: AppGraph, docId: String, recordingId: String) = CloudReferenceSource(
        loadPeople = { graph.speakers.referencesFor(docId, recordingId, DiarizeReferences.MAX_REFERENCES) },
        cutClip = { start, end -> graph.speakerAudio.clip(recordingId, start, end) },
        accept = { DiarizeReferences.fitClip(it) != null },
    )

    private fun unnamed(index: Int): String = applicationContext.getString(R.string.speakers_cloud_unnamed, index)

    /** Runs a follow-up step that must not fail the transcript; false when it failed. */
    private suspend fun hook(what: String, block: suspend () -> Unit): Boolean = try {
        block()
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "$what failed: ${e.javaClass.simpleName}")
        false
    }

    private suspend fun <T> hookValue(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private suspend fun retryOrFail(graph: AppGraph, recordingId: String, retryable: Boolean, message: String?): Result {
        if (retryable && runAttemptCount < MAX_ATTEMPTS) {
            graph.db.recordings().setTranscriptStatus(recordingId, TranscriptStatus.QUEUED.name)
            return Result.retry()
        }
        graph.db.recordings().setTranscriptStatus(recordingId, TranscriptStatus.FAILED.name)
        val text = message?.takeIf { it.isNotBlank() } ?: applicationContext.getString(R.string.audio_transcribe_failed)
        return Result.failure(workDataOf(Transcriptions.KEY_ERROR to text))
    }

    private fun failureData(res: Int) = workDataOf(Transcriptions.KEY_ERROR to applicationContext.getString(res))

    private fun readCheckpoint(file: File): Pair<TranscriptCheckpoint, CloudSpeakerState>? =
        if (!file.isFile) null else runCatching { JobCheckpoint.decode(file.readText()) }.getOrNull()

    private fun writeCheckpoint(file: File, c: TranscriptCheckpoint, speakers: CloudSpeakerState) {
        // Never recreate the recording's folder once it was deleted (writeAtomically would).
        if (file.parentFile?.isDirectory != true) return
        runCatching {
            FileStore.writeAtomically(file, keepBackup = false) { it.write(JobCheckpoint.encode(c, speakers).toByteArray()) }
        }.onFailure { Log.w(TAG, "Could not save transcription progress", it) }
    }

    private companion object {
        const val TAG = "Transcription"
        const val MAX_ATTEMPTS = 4
    }
}
