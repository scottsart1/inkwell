package com.inkwell.notes.audio.ui.dock

import com.inkwell.notes.audio.logic.DockMode

/**
 * Where the dock was for the current recording, kept for the process so leaving and
 * re-entering the editor (or opening another document) doesn't reset it.
 */
internal object DockMemory {
    private var recordingId: String? = null
    var mode: DockMode? = null
        private set
    /** Vertical centre as a fraction of the container height. */
    var centerFraction: Float = 0.5f
        private set
    /** The last error report (RecordingRuntime.errorReport) the user dismissed or saw time out. */
    var dismissedError: Any? = null
    /** Notification permission was already requested in this process. */
    var askedForNotifications: Boolean = false

    /** Returns the remembered mode for [id], or null for a recording the dock hasn't seen. */
    fun modeFor(id: String): DockMode? = if (id == recordingId) mode else null

    fun remember(id: String, mode: DockMode, centerFraction: Float) {
        if (id != recordingId) this.centerFraction = 0.5f
        recordingId = id
        this.mode = mode
        if (!centerFraction.isNaN()) this.centerFraction = centerFraction.coerceIn(0f, 1f)
    }
}
