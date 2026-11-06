package com.inkwell.notes.audio.recorder

import android.content.Context
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.inkwell.notes.R
import com.inkwell.notes.app.AppGraph
import com.inkwell.notes.audio.RecordingState
import com.inkwell.notes.audio.logic.AudioText
import com.inkwell.notes.audio.logic.RecoveryPlanner
import com.inkwell.notes.audio.logic.Waveform
import com.inkwell.notes.audio.recorder.RecordingSession.Companion.withSegments
import com.inkwell.notes.audio.transcription.Transcriptions
import com.inkwell.notes.core.model.AiTask
import com.inkwell.notes.core.model.RecordingStatus
import com.inkwell.notes.core.model.TranscriptStatus
import com.inkwell.notes.core.storage.db.RecordingEntity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Process-wide recording engine. All commands (start, pause, resume, stop, segment
 * persistence, recorder failures, crash recovery) run one at a time, in order, on a single
 * consumer loop, so switching documents, stopping during a rollover or recovering at launch
 * can never interleave. MediaRecorder lives on its own audio-priority thread; database and
 * file work runs on Dispatchers.IO; state is published through [RecordingRuntime.state].
 *
 * The microphone foreground service ([com.inkwell.notes.audio.RecordingService]) only holds
 * foreground status and the notification; it starts recordings here once it is in the
 * foreground and stops itself when [isIdle].
 */
internal class RecordingEngine(private val graph: AppGraph) {
    fun interface ServiceHooks {
        /** Nothing is recording or about to start; the service may leave the foreground. */
        fun onIdle()
    }

    private sealed interface Command {
        data class Start(val docId: String, val title: String?) : Command
        data class Refused(val message: String) : Command
        data object Pause : Command
        data object Resume : Command
        data class Stop(val error: String? = null, val target: RecordingSession? = null) : Command
        data class SegmentDone(val session: RecordingSession, val index: Int, val file: File) : Command
        data class Failed(val session: RecordingSession, val failure: SegmentRecorder.Failure) : Command
        data class Recover(val done: CompletableDeferred<Unit>) : Command
    }

    private val context: Context = graph.context
    private val state = RecordingRuntime.mutableState
    private val commands = Channel<Command>(Channel.UNLIMITED)
    private val pendingStarts = AtomicInteger(0)

    private val thread = HandlerThread("InkwellRecorder", Process.THREAD_PRIORITY_AUDIO).apply { start() }
    private val recorderDispatcher = Handler(thread.looper).asCoroutineDispatcher("InkwellRecorder")

    @Volatile private var session: RecordingSession? = null
    @Volatile private var hooks: ServiceHooks? = null

    private val wakeLock: PowerManager.WakeLock? by lazy {
        runCatching {
            context.getSystemService(PowerManager::class.java)
                ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Inkwell:recording")
                ?.apply { setReferenceCounted(false) }
        }.getOrNull()
    }

    init {
        graph.appScope.launch(Dispatchers.IO) {
            for (c in commands) {
                try {
                    handle(c)
                } catch (t: Throwable) {
                    Log.e(TAG, "Recording command ${c::class.simpleName} failed", t)
                    if (c is Command.Recover) c.done.complete(Unit)
                }
            }
        }
    }

    /** True when nothing is recording and no start is queued. */
    val isIdle: Boolean get() = session == null && pendingStarts.get() == 0

    val hasSession: Boolean get() = session != null

    fun attach(h: ServiceHooks) { hooks = h }

    fun detach(h: ServiceHooks) { if (hooks === h) hooks = null }

    /** Called by the service once it is in the foreground. */
    fun submitStart(docId: String, title: String?) {
        pendingStarts.incrementAndGet()
        commands.trySend(Command.Start(docId, title))
    }

    /** The service could not enter the foreground; report [message] instead of recording. */
    fun refuseStart(message: String) {
        commands.trySend(Command.Refused(message))
    }

    fun pause() { commands.trySend(Command.Pause) }

    fun resume() { commands.trySend(Command.Resume) }

    fun stop() { commands.trySend(Command.Stop()) }

    suspend fun recover() {
        val done = CompletableDeferred<Unit>()
        commands.send(Command.Recover(done))
        done.await()
    }

    // ------------------------------------------------------------------ command handling

    private suspend fun handle(c: Command) {
        try {
            when (c) {
                is Command.Start -> try {
                    start(c.docId, c.title)
                } finally {
                    pendingStarts.decrementAndGet()
                }
                is Command.Refused -> if (session == null) state.value = RecordingState.Error(c.message)
                Command.Pause -> session?.let { pause(it) }
                Command.Resume -> session?.let { resume(it) }
                is Command.Stop -> {
                    val s = session
                    if (s != null && (c.target == null || c.target === s)) finish(s, c.error)
                }
                is Command.SegmentDone -> if (c.session === session && !c.session.finishing) persistSegment(c.session, c.index, c.file)
                is Command.Failed -> if (c.session === session) finish(c.session, context.getString(failureMessage(c.failure)))
                is Command.Recover -> try {
                    recoverAll()
                } finally {
                    c.done.complete(Unit)
                }
            }
        } finally {
            // Every command can leave the engine idle: a stop or failure, but also a segment save
            // that found the document deleted, a resume that lost the microphone, or a stale
            // notification action with nothing recording. The service must learn about all of
            // them, or it stays in the foreground with a "Recording" notification for nothing.
            notifyIfIdle()
        }
    }

    private fun notifyIfIdle() {
        if (isIdle) hooks?.onIdle()
    }

    private fun failureMessage(f: SegmentRecorder.Failure): Int = when (f) {
        SegmentRecorder.Failure.MIC_LOST -> R.string.audio_error_mic_lost
        SegmentRecorder.Failure.SERVER_DIED -> R.string.audio_error_server_died
        SegmentRecorder.Failure.MIC_TAKEN -> R.string.audio_error_mic_taken
        SegmentRecorder.Failure.STORAGE_FULL -> R.string.audio_error_storage
    }

    private suspend fun start(docId: String, title: String?) {
        val current = session
        if (current != null) {
            if (current.docId == docId) {
                if (current.paused) resume(current)
                return
            }
            // One recording at a time: finish the other document's recording first.
            finish(current, error = null)
        }

        val doc = runCatching { graph.library.getDocument(docId) }.getOrNull()
        if (doc == null) {
            state.value = RecordingState.Error(context.getString(R.string.audio_error_doc_missing))
            return
        }
        if (runCatching { graph.files.root.usableSpace }.getOrDefault(Long.MAX_VALUE) < MIN_FREE_TO_START) {
            state.value = RecordingState.Error(context.getString(R.string.audio_error_storage))
            return
        }
        val now = System.currentTimeMillis()
        val recordingId = UUID.randomUUID().toString()
        val dir = graph.files.audioDir(docId, recordingId)
        val prefs = graph.settings.current.recording
        val config = SegmentRecorder.Config(
            sampleRate = prefs.sampleRate,
            bitrate = prefs.bitrate,
            segmentMs = prefs.segmentSeconds.coerceIn(MIN_SEGMENT_SECONDS, MAX_SEGMENT_SECONDS) * 1000L,
        )
        val n = runCatching { graph.db.recordings().countForDocument(docId) }.getOrDefault(0) + 1
        val name = title?.trim()?.takeIf { it.isNotEmpty() } ?: AudioText.defaultTitle(n, now)
        val entity = RecordingEntity(
            id = recordingId, documentId = docId, title = name, segmentFiles = "", segmentDurations = "",
            durationMs = 0, startedAt = now, endedAt = null, status = RecordingStatus.RECORDING,
            transcriptStatus = TranscriptStatus.NONE, waveformPath = AudioFiles.waveformFile(dir).absolutePath,
        )
        try {
            graph.db.recordings().upsert(entity)
        } catch (t: Throwable) {
            Log.e(TAG, "Could not create the recording row", t)
            dir.deleteRecursively()
            state.value = RecordingState.Error(context.getString(R.string.audio_error_start))
            return
        }

        val s = RecordingSession(docId, recordingId, now, dir, config, SystemClock::elapsedRealtime)
        s.docTitle = doc.title
        val started = runCatching {
            withContext(recorderDispatcher) {
                val rec = SegmentRecorder(context, config, { AudioFiles.segmentFile(dir, it) }, listenerFor(s))
                rec.start(0)
                s.recorder = rec
                s.begin()
                watchSilencing(s)
            }
        }
        if (started.isFailure) {
            Log.w(TAG, "Recorder failed to start", started.exceptionOrNull())
            runCatching { graph.db.recordings().delete(recordingId) }
            dir.deleteRecursively()
            state.value = RecordingState.Error(context.getString(R.string.audio_error_start))
            return
        }
        session = s
        RecordingRuntime.session = s
        acquireWakeLock()
        s.publish(state)
        s.pollJob = graph.appScope.launch(recorderDispatcher) { poll(s) }
    }

    private fun listenerFor(s: RecordingSession) = object : SegmentRecorder.Listener {
        override fun onSegmentFinished(index: Int, file: File) {
            // Recorder thread: book the boundary now so the timeline stays exact, persist on the loop.
            val ledgerIndex = s.roll(file)
            commands.trySend(Command.SegmentDone(s, ledgerIndex, file))
        }

        override fun onRecorderError(failure: SegmentRecorder.Failure) {
            commands.trySend(Command.Failed(s, failure))
        }
    }

    /** Recorder thread: level meter, waveform envelope and the long-segment safety net. */
    private suspend fun poll(s: RecordingSession) {
        while (currentCoroutineContext().isActive && !s.finishing) {
            delay(POLL_MS)
            if (s.paused || s.finishing) continue
            val rec = s.recorder ?: continue
            s.sample(rec.maxAmplitude())
            if (rec.isRunning && s.currentSegmentMs() > s.config.segmentMs * FORCE_ROLL_FACTOR) {
                rec.forceRoll()
            }
            checkHealth(s)
            if (session === s) s.publish(state)
        }
    }

    /** Stops with an error when storage runs low or another app has taken the microphone. */
    private fun checkHealth(s: RecordingSession) {
        if (s.failureReported) return
        s.polls++
        if (s.polls % STORAGE_CHECK_POLLS == 0L) {
            val free = runCatching { s.dir.usableSpace }.getOrDefault(Long.MAX_VALUE)
            if (free < MIN_FREE_WHILE_RECORDING) {
                s.failureReported = true
                commands.trySend(Command.Failed(s, SegmentRecorder.Failure.STORAGE_FULL))
                return
            }
        }
        val since = s.silencedSince
        if (since > 0 && SystemClock.elapsedRealtime() - since >= SILENCED_LIMIT_MS) {
            s.failureReported = true
            commands.trySend(Command.Failed(s, SegmentRecorder.Failure.MIC_TAKEN))
        }
    }

    /**
     * Since Android 10 a recorder whose microphone is taken by a higher-priority app (a call,
     * the assistant) is silenced instead of failing. Recorder thread.
     */
    private fun watchSilencing(s: RecordingSession) {
        val am = context.getSystemService(AudioManager::class.java) ?: return
        val callback = object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
                if (s.finishing) return
                // [configs] covers every capture on the device: only our own recorder's silencing counts.
                val silenced = s.recorder?.isSilenced() == true
                s.silencedSince = if (silenced) (s.silencedSince.takeIf { it > 0 } ?: SystemClock.elapsedRealtime()) else 0L
            }
        }
        runCatching { am.registerAudioRecordingCallback(callback, Handler(thread.looper)) }
            .onSuccess { s.recordingCallback = callback }
    }

    private fun unwatchSilencing(s: RecordingSession) {
        val cb = s.recordingCallback ?: return
        s.recordingCallback = null
        runCatching { context.getSystemService(AudioManager::class.java)?.unregisterAudioRecordingCallback(cb) }
    }

    private suspend fun pause(s: RecordingSession) {
        if (s.paused || s.finishing) return
        val ok = runCatching { withContext(recorderDispatcher) { s.recorder?.pause() } }
        if (ok.isFailure) {
            Log.w(TAG, "Pause failed", ok.exceptionOrNull())
            return
        }
        s.markPaused()
        s.publish(state)
        releaseWakeLock()
        runCatching { graph.db.recordings().mutate(s.recordingId) { it.copy(status = RecordingStatus.PAUSED) } }
        runCatching { AudioFiles.writeWaveform(s.waveformFile, s.waveformBytes()) }
    }

    private suspend fun resume(s: RecordingSession) {
        if (!s.paused || s.finishing) return
        val ok = runCatching { withContext(recorderDispatcher) { s.recorder?.resume() } }
        if (ok.isFailure) {
            Log.w(TAG, "Resume failed", ok.exceptionOrNull())
            finish(s, context.getString(R.string.audio_error_mic_lost))
            return
        }
        s.markResumed()
        acquireWakeLock()
        s.publish(state)
        runCatching { graph.db.recordings().mutate(s.recordingId) { it.copy(status = RecordingStatus.RECORDING) } }
    }

    /** A finished segment: measure it, save the envelope, and record it so a crash keeps it. */
    private suspend fun persistSegment(s: RecordingSession, index: Int, file: File) {
        AudioFiles.durationMs(file)?.let { s.correct(index, it) }
        runCatching { AudioFiles.writeWaveform(s.waveformFile, s.waveformBytes()) }
            .onFailure { Log.w(TAG, "Waveform save failed", it) }
        val segments = s.segments()
        val updated = try {
            graph.db.recordings().mutate(s.recordingId) { it.withSegments(segments) }
        } catch (t: Throwable) {
            Log.e(TAG, "Saving segment $index failed", t)
            return
        }
        if (updated == null) {
            // The row (or its document) was deleted while recording.
            finish(s, context.getString(R.string.audio_error_doc_missing))
        }
    }

    /**
     * Stops [s], keeps every readable segment, writes the envelope and completes the row.
     * State goes Idle (or Error with [error]) as soon as the microphone is released.
     */
    private suspend fun finish(s: RecordingSession, error: String?) {
        if (s.finishing) return
        s.finishing = true
        runCatching { s.pollJob?.cancelAndJoin() }
        val stopped = runCatching {
            withContext(recorderDispatcher) {
                unwatchSilencing(s)
                val r = s.recorder
                s.recorder = null
                r?.stop()
            }
        }.getOrNull()
        s.stopClock()
        session = null
        RecordingRuntime.session = null
        releaseWakeLock()
        state.value = if (error != null) RecordingState.Error(error) else RecordingState.Idle

        // The segment being written when we stopped.
        val last = stopped?.file
        if (last != null && s.segments().none { it.path == last.absolutePath }) {
            val ms = AudioFiles.durationMs(last)
            if (ms != null) s.correct(s.roll(last), ms) else last.delete()
        }
        // Segments whose measurement failed at rollover get another try.
        for ((i, seg) in s.segments().withIndex()) {
            if (!seg.measured) AudioFiles.durationMs(File(seg.path))?.let { s.correct(i, it) }
        }

        val segments = s.segments().filter { File(it.path).let { f -> f.isFile && f.length() > 0 } }
        if (segments.isEmpty()) {
            runCatching { graph.db.recordings().delete(s.recordingId) }
            s.dir.deleteRecursively()
            return
        }
        val total = segments.sumOf { it.durationMs }
        runCatching { AudioFiles.writeWaveform(s.waveformFile, Waveform.trim(s.waveformBytes(), total)) }
            .onFailure { Log.w(TAG, "Waveform save failed", it) }
        val endedAt = System.currentTimeMillis()
        val updated = try {
            graph.db.recordings().mutate(s.recordingId) {
                it.withSegments(segments).copy(status = RecordingStatus.COMPLETE, endedAt = endedAt)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Completing the recording failed", t)
            if (error == null) state.value = RecordingState.Error(context.getString(R.string.audio_error_save))
            return
        }
        if (updated == null) {
            s.dir.deleteRecursively()
            return
        }
        afterFinished(s.docId, s.recordingId)
    }

    private suspend fun afterFinished(docId: String, recordingId: String) {
        runCatching { graph.sync.onRecordingFinished(docId, recordingId) }
            .onFailure { Log.w(TAG, "Sync hook failed", it) }
        // Who spoke when: queued in the background (on-device engine), never while recording.
        runCatching { graph.speakers.onRecordingFinished(docId, recordingId) }
            .onFailure { Log.w(TAG, "Speaker hook failed", it) }
        val auto = graph.settings.current.recording.autoTranscribe
        if (auto && runCatching { graph.ai.isAvailable(AiTask.TRANSCRIPTION) }.getOrDefault(false)) {
            runCatching { Transcriptions.enqueue(graph, docId, recordingId, replace = false) }
                .onFailure { Log.w(TAG, "Could not queue transcription", it) }
        }
    }

    // ------------------------------------------------------------------ crash recovery

    private suspend fun recoverAll() {
        val activeId = session?.recordingId
        val unfinished = runCatching { graph.db.recordings().unfinished() }.getOrDefault(emptyList())
        for (e in unfinished) {
            if (e.id == activeId) continue
            runCatching { recoverOne(e) }.onFailure { Log.e(TAG, "Recovering a recording failed", it) }
        }
    }

    private suspend fun recoverOne(e: RecordingEntity) {
        val rec = e.toModel()
        val dir = graph.files.audioDir(e.documentId, e.id)
        val persisted = HashMap<String, Long>()
        rec.segmentFiles.forEachIndexed { i, p ->
            val f = AudioFiles.resolve(graph.files, e.documentId, e.id, p)
            persisted[f.absolutePath] = rec.segmentDurationsMs.getOrNull(i) ?: 0L
        }
        val onDisk = dir.listFiles()?.filter { RecoveryPlanner.indexOf(it.name) != null }.orEmpty()
        val candidates = (persisted.keys.map { File(it) } + onDisk).distinctBy { it.absolutePath }
        val probes = candidates.mapIndexed { i, f ->
            RecoveryPlanner.Probe(
                path = f.absolutePath,
                index = RecoveryPlanner.indexOf(f.name) ?: i,
                sizeBytes = if (f.isFile) f.length() else 0L,
                measuredMs = AudioFiles.durationMs(f),
                persistedMs = persisted[f.absolutePath],
            )
        }
        val plan = RecoveryPlanner.plan(probes)
        for (p in plan.dropped) {
            val f = File(p)
            if (f.parentFile?.absolutePath == dir.absolutePath) f.delete()
        }
        if (plan.kept.isEmpty()) {
            graph.db.recordings().delete(e.id)
            dir.deleteRecursively()
            return
        }
        val waveFile = AudioFiles.waveformFile(dir)
        AudioFiles.readWaveform(waveFile)?.let { bytes ->
            runCatching { AudioFiles.writeWaveform(waveFile, Waveform.trim(bytes, plan.totalMs)) }
        }
        val endedAt = plan.kept.maxOf { File(it.path).lastModified() }.takeIf { it > 0 } ?: (e.startedAt + plan.totalMs)
        val updated = graph.db.recordings().mutate(e.id) {
            it.copy(
                title = AudioText.recoveredTitle(it.title),
                segmentFiles = plan.paths.joinToString("\n"),
                segmentDurations = plan.durations.joinToString(","),
                durationMs = plan.totalMs,
                endedAt = endedAt,
                status = RecordingStatus.INTERRUPTED,
                waveformPath = waveFile.absolutePath,
            )
        }
        // Same hooks as a normal stop: back it up and, when the user asked for it, transcribe it.
        if (updated != null) afterFinished(e.documentId, e.id)
    }

    // ------------------------------------------------------------------ wake lock

    private fun acquireWakeLock() {
        runCatching { wakeLock?.acquire(WAKE_LOCK_TIMEOUT_MS) }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
    }

    companion object {
        private const val TAG = "RecordingEngine"
        const val POLL_MS = 50L
        private const val MIN_SEGMENT_SECONDS = 15
        private const val MAX_SEGMENT_SECONDS = 1800
        /** A segment this many times longer than intended is closed by restarting the recorder. */
        private const val FORCE_ROLL_FACTOR = 2.5
        /** Free space needed to start (about two hours of 96 kbps audio, plus room for autosave). */
        private const val MIN_FREE_TO_START = 96L * 1024 * 1024
        /** Below this, recording stops so ink autosave always has room. */
        private const val MIN_FREE_WHILE_RECORDING = 32L * 1024 * 1024
        /** Check free space every 100 polls (5 s). */
        private const val STORAGE_CHECK_POLLS = 100L
        /** Silenced this long by another app's capture = microphone taken. */
        private const val SILENCED_LIMIT_MS = 2_000L
        /** Re-acquired on every resume; bounds the damage if a release is ever missed. */
        private const val WAKE_LOCK_TIMEOUT_MS = 6L * 60 * 60 * 1000
    }
}
