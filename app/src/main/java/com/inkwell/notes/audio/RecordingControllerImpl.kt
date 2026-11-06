package com.inkwell.notes.audio

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.inkwell.notes.R
import com.inkwell.notes.app.AppGraph
import com.inkwell.notes.audio.recorder.RecordingEngine
import com.inkwell.notes.audio.recorder.RecordingRuntime
import kotlinx.coroutines.flow.StateFlow

/**
 * Records through [RecordingService] (microphone foreground service) and the process-wide
 * [RecordingEngine]. [state] is the same process-level flow the service and the dock read,
 * so it always reflects what the recorder is doing. Safe to call from any thread.
 */
class RecordingControllerImpl(private val graph: AppGraph) : RecordingController {
    private val engine: RecordingEngine get() = RecordingRuntime.engine(graph)

    override val state: StateFlow<RecordingState> = RecordingRuntime.state

    override val clock: AudioClock = AudioClock { docId -> RecordingRuntime.anchorFor(docId) }

    override fun start(docId: String, title: String?) {
        val context = graph.context
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            RecordingRuntime.reportError(context.getString(R.string.audio_error_permission))
            return
        }
        val current = state.value
        if (current is RecordingState.Active && current.docId == docId) {
            if (current.paused) resume()
            return
        }
        engine // created before the service asks for it
        try {
            // The service enters the foreground at once; a recording of another document is finished first.
            ContextCompat.startForegroundService(context, RecordingService.startIntent(context, docId, title))
        } catch (e: Exception) {
            Log.w(TAG, "Could not start the recording service", e)
            RecordingRuntime.reportError(context.getString(R.string.audio_error_background))
        }
    }

    override fun pause() = engine.pause()

    override fun resume() = engine.resume()

    override fun stop() = engine.stop()

    override suspend fun recoverInterrupted() = engine.recover()

    private companion object {
        const val TAG = "RecordingController"
    }
}
