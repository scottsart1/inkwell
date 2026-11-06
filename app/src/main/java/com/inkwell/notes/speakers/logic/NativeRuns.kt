package com.inkwell.notes.speakers.logic

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Recordings whose analysis is inside native code (sherpa-onnx / onnxruntime). A native fault
 * kills the whole process with no Java exception to catch, and WorkManager then runs the job
 * again on every start: without this record one bad recording would crash the app in a loop.
 * The marker is written (fsynced) before the native pass and cleared after it, so a marker
 * left by another process means that run died inside native code. A marker of this process
 * belongs to a run that is still alive (or leaked one): it is no crash.
 */
@Serializable
data class NativeRuns(val entries: Map<String, Entry> = emptyMap()) {
    @Serializable
    data class Entry(
        /** A run of this recording entered native code and has not come back yet. */
        val inFlight: Boolean = false,
        /** Runs of this recording that died inside native code, in a row. */
        val crashes: Int = 0,
        /** The process (a token per process start) whose run set [inFlight]. */
        val owner: String? = null,
    )

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun encode(r: NativeRuns): String = json.encodeToString(serializer(), r)

        fun decode(text: String): NativeRuns = runCatching { json.decodeFromString(serializer(), text) }.getOrDefault(NativeRuns())
    }
}

/** Pure rules over [NativeRuns]. */
object NativeRunRules {
    /** After this many runs of one recording died inside native code, stop retrying it. */
    const val MAX_CRASHES = 2

    sealed interface Decision {
        val next: NativeRuns

        /** Enter native code; [next] marks the run in flight. */
        data class Proceed(override val next: NativeRuns) : Decision

        /** Do not: earlier runs of this recording kept killing the app. */
        data class Stop(override val next: NativeRuns) : Decision
    }

    /**
     * A run of [recordingId] in the process [owner] is about to enter native code. Only a mark
     * left by another process counts as a crash: that process died with the run inside.
     */
    fun start(state: NativeRuns, recordingId: String, owner: String): Decision {
        val e = state.entries[recordingId] ?: NativeRuns.Entry()
        val crashes = if (e.inFlight && e.owner != owner) e.crashes + 1 else e.crashes
        return if (crashes >= MAX_CRASHES) {
            Decision.Stop(state.with(recordingId, NativeRuns.Entry(inFlight = false, crashes = crashes)))
        } else {
            Decision.Proceed(state.with(recordingId, NativeRuns.Entry(inFlight = true, crashes = crashes, owner = owner)))
        }
    }

    /**
     * The run of process [owner] came back from native code: [completed] (every window analysed)
     * forgets the recording; otherwise (a Java exception, a stop) only the in-flight mark goes.
     * A mark set by another process is left alone: it records that process's death.
     */
    fun finish(state: NativeRuns, recordingId: String, owner: String, completed: Boolean): NativeRuns {
        val e = state.entries[recordingId] ?: return state
        if (e.inFlight && e.owner != owner) return state
        return if (completed) state.without(recordingId) else state.with(recordingId, e.copy(inFlight = false, owner = null))
    }

    /** The user asked to try again (or the recording is gone): start from a clean slate. */
    fun reset(state: NativeRuns, recordingId: String): NativeRuns = state.without(recordingId)

    private fun NativeRuns.with(id: String, e: NativeRuns.Entry) = copy(entries = entries + (id to e))

    private fun NativeRuns.without(id: String) = if (id in entries) copy(entries = entries - id) else this
}
