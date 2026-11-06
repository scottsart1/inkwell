package com.inkwell.notes.audio

import com.inkwell.notes.core.model.AudioAnchor
import com.inkwell.notes.core.model.Recording
import kotlinx.coroutines.flow.StateFlow

/** Supplies the recording timeline position for items created while recording. */
fun interface AudioClock {
    /** The anchor for "now", or null when nothing is recording for [docId]. */
    fun anchorFor(docId: String): AudioAnchor?
}

sealed interface RecordingState {
    data object Idle : RecordingState
    data class Active(
        val docId: String,
        val recordingId: String,
        val startedAt: Long,
        /** Elapsed recorded time, excluding pauses. */
        val elapsedMs: Long,
        val paused: Boolean,
        /** Current input level 0..1 for the activity indicator. */
        val level: Float,
    ) : RecordingState
    /**
     * [at] tells failures apart: a repeated failure with the same message is a new state, so a
     * StateFlow emits it again and the error shows again.
     */
    data class Error(val message: String, val at: Long = System.nanoTime()) : RecordingState
}

/**
 * Records audio for a document through a microphone foreground service, so recording
 * continues across page changes, the AI panel, and the app going to the background.
 * Audio is written in crash-safe segments.
 */
interface RecordingController {
    val state: StateFlow<RecordingState>
    val clock: AudioClock

    /** Starts recording into [docId]. Caller must hold RECORD_AUDIO permission. */
    fun start(docId: String, title: String? = null)
    fun pause()
    fun resume()
    fun stop()

    /** Recovers recordings left RECORDING/PAUSED by a crash (finalises finished segments). */
    suspend fun recoverInterrupted()
}

data class PlaybackState(
    val recording: Recording? = null,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val playing: Boolean = false,
    val speed: Float = 1f,
    /** User-visible reason the last playback attempt failed, or null. */
    val error: String? = null,
)

/** Plays a recording's segments as one seamless timeline. */
interface PlaybackController {
    val state: StateFlow<PlaybackState>
    fun load(recording: Recording)
    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
    fun setSpeed(speed: Float)
    fun release()
}

// Implementations: RecordingControllerImpl.kt, PlaybackControllerImpl.kt, RecordingService.kt.
