package com.inkwell.notes.audio

import com.inkwell.notes.audio.logic.TranscriptAssembly
import com.inkwell.notes.audio.logic.TranscriptCheckpoint
import com.inkwell.notes.core.model.TranscriptSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The per-segment loop of TranscriptionWorker, replayed over pure functions: each file is
 * transcribed on its own (times relative to the file) and moved onto the recording timeline.
 */
class TranscriptionOffsetsTest {
    private val durations = listOf(120_000L, 119_950L, 120_010L)
    private val files = listOf("/a/seg_000.m4a", "/a/seg_001.m4a", "/a/seg_002.m4a")

    /** What the provider returns for a file: relative to that file's start. */
    private fun provider(index: Int): List<TranscriptSegment> = listOf(
        TranscriptSegment(0, 3_000, "segment $index first"),
        TranscriptSegment(3_000, 7_500, "segment $index second"),
        // Whisper occasionally runs a few ms past the file's measured length.
        TranscriptSegment(118_000, 120_300, "segment $index last"),
    )

    private fun run(checkpoint: TranscriptCheckpoint, present: Set<Int>, transcribe: (Int) -> List<TranscriptSegment>): TranscriptCheckpoint {
        var c = checkpoint
        for (i in c.done until files.size) {
            val duration = durations.getOrNull(i) ?: 0L
            val pieces = if (i !in present) emptyList() else TranscriptAssembly.shift(transcribe(i), TranscriptAssembly.offsetOf(durations, i), duration)
            c = c.copy(done = i + 1, segments = c.segments + pieces)
        }
        return c
    }

    @Test
    fun everySegmentIsShiftedByTheSumOfThePreviousMeasuredDurations() {
        val out = run(TranscriptCheckpoint(files), present = setOf(0, 1, 2)) { provider(it) }.segments.sortedBy { it.startMs }
        assertEquals(9, out.size)
        assertEquals(0L, out[0].startMs)
        assertEquals(120_000L, out[3].startMs)
        assertEquals(239_950L, out[6].startMs)
        // Clamped to its own file: never spills into the next segment's time.
        assertEquals(120_000L, out[2].endMs)
        assertEquals(239_950L, out[5].endMs)
        assertEquals(359_960L, out[8].endMs)
        assertEquals("segment 2 last", out[8].text)
    }

    @Test
    fun aMissingMiddleSegmentKeepsLaterTimestampsInPlace() {
        val out = run(TranscriptCheckpoint(files), present = setOf(0, 2)) { provider(it) }.segments.sortedBy { it.startMs }
        assertEquals(6, out.size)
        assertEquals(239_950L, out[3].startMs)
        assertTrue(out.none { it.text.contains("segment 1") })
    }

    @Test
    fun resumingFromACheckpointNeverRetranscribesFinishedSegments() {
        val calls = ArrayList<Int>()
        val failed = runCatching {
            run(TranscriptCheckpoint(files), present = setOf(0, 1, 2)) { i ->
                calls += i
                if (i == 1) throw IllegalStateException("network") else provider(i)
            }
        }
        assertTrue(failed.isFailure)
        assertEquals(listOf(0, 1), calls)
        calls.clear()
        // The worker had checkpointed segment 0 before the failure; a retry resumes from there.
        val saved = TranscriptCheckpoint(files, done = 1, segments = TranscriptAssembly.shift(provider(0), 0, durations[0]))
        assertTrue(saved.matches(files))
        val resumed = run(saved, present = setOf(0, 1, 2)) { i -> calls += i; provider(i) }
        assertEquals(listOf(1, 2), calls)
        assertEquals(3, resumed.done)
        assertEquals(9, resumed.segments.size)
        assertEquals(239_950L, resumed.segments.sortedBy { it.startMs }[6].startMs)
    }

    @Test
    fun aCheckpointForDifferentFilesOrAnImpossibleProgressIsDiscarded() {
        val stale = TranscriptCheckpoint(files.take(2), done = 2)
        assertFalse(stale.matches(files))
        val tooFar = TranscriptCheckpoint(files, done = 4)
        assertFalse(tooFar.matches(files))
        val negative = TranscriptCheckpoint(files, done = -1)
        assertFalse(negative.matches(files))
        assertTrue(TranscriptCheckpoint(files, done = 3).matches(files))
    }

    @Test
    fun aSliverLeftByStoppingRightAfterASegmentSwitchIsNotSent() {
        // Stopped 60 ms after the recorder moved to a new file: the API refuses audio under
        // 0.1 s, which failed the whole job (and every "Try again") at that segment.
        assertTrue(TranscriptAssembly.tooShortToTranscribe(60))
        assertTrue(TranscriptAssembly.tooShortToTranscribe(TranscriptAssembly.MIN_TRANSCRIBE_MS - 1))
        assertFalse(TranscriptAssembly.tooShortToTranscribe(TranscriptAssembly.MIN_TRANSCRIBE_MS))
        assertFalse(TranscriptAssembly.tooShortToTranscribe(120_000))
        // Unknown duration (older rows): still sent.
        assertFalse(TranscriptAssembly.tooShortToTranscribe(0))
    }
}
