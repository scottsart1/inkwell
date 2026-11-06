package com.inkwell.notes.audio.logic

import com.inkwell.notes.core.model.TranscriptSegment
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Progress of a transcription that runs segment by segment, saved after each one so a
 * stopped or retried job continues where it left off instead of paying for audio twice.
 */
@Serializable
data class TranscriptCheckpoint(
    /** The segment files this checkpoint belongs to; a different list invalidates it. */
    val files: List<String>,
    /** Number of leading segments already transcribed. */
    val done: Int = 0,
    val segments: List<TranscriptSegment> = emptyList(),
    val language: String? = null,
    val source: String? = null,
) {
    fun matches(currentFiles: List<String>): Boolean = files == currentFiles && done in 0..files.size

    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun encode(c: TranscriptCheckpoint): String = json.encodeToString(serializer(), c)

        fun decode(text: String): TranscriptCheckpoint? = runCatching { json.decodeFromString(serializer(), text) }.getOrNull()
    }
}

object TranscriptAssembly {
    /** Below this a segment holds no words; the transcription API refuses audio under 0.1 s. */
    const val MIN_TRANSCRIBE_MS = 250L

    /**
     * True for a segment too short to send: the sliver left when recording stopped (or paused)
     * just after a segment switch. Sending it fails the request, and with it the whole job on
     * every retry. 0 means the duration is unknown: such segments are still sent.
     */
    fun tooShortToTranscribe(durationMs: Long): Boolean = durationMs in 1 until MIN_TRANSCRIBE_MS

    /** Start of segment [index] on the recording's timeline. */
    fun offsetOf(durations: List<Long>, index: Int): Long = durations.take(index.coerceAtLeast(0)).sumOf { it.coerceAtLeast(0) }

    /**
     * Moves segment-relative transcript pieces onto the recording timeline and keeps them
     * inside the segment ([durationMs] <= 0 means unknown, no upper clamp). Blank pieces are dropped.
     */
    fun shift(pieces: List<TranscriptSegment>, offsetMs: Long, durationMs: Long): List<TranscriptSegment> =
        pieces.filter { it.text.isNotBlank() }.map { p ->
            val start = p.startMs.coerceAtLeast(0)
            val end = p.endMs.coerceAtLeast(start)
            val (s, e) = if (durationMs > 0) start.coerceAtMost(durationMs) to end.coerceAtMost(durationMs) else start to end
            p.copy(startMs = offsetMs + s, endMs = offsetMs + e, text = p.text.trim())
        }

    /**
     * Start times made strictly increasing: a block that starts at or before the previous one
     * moves to 1 ms after it. Blocks are addressed by their start on the timeline (moving one to
     * another speaker, seeking), so two blocks must never share one. Order and text are kept.
     */
    fun distinctStarts(segments: List<TranscriptSegment>): List<TranscriptSegment> {
        var prev = Long.MIN_VALUE
        return segments.sortedBy { it.startMs }.map { s ->
            val start = if (prev != Long.MIN_VALUE && s.startMs <= prev) prev + 1 else s.startMs
            prev = start
            if (start == s.startMs) s else s.copy(startMs = start, endMs = maxOf(s.endMs, start))
        }
    }

    /** Fraction done for progress display. */
    fun progress(done: Int, total: Int): Float = if (total <= 0) 0f else (done.toFloat() / total).coerceIn(0f, 1f)
}
