package com.inkwell.notes.speakers

import com.inkwell.notes.speakers.logic.AudioPlan
import com.inkwell.notes.speakers.logic.DiarizationCheckpoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A checkpoint counts windows of one plan; resuming it under another plan would skip or repeat audio. */
class CheckpointPlanTest {
    private val files = listOf("/a/seg_000.m4a", "/a/seg_001.m4a", "/a/seg_002.m4a")
    private val durations = listOf(120_000L, 120_000L, 120_000L)

    @Test
    fun aCheckpointFromTheTenMinutePlanStartsOver() {
        // What the previous build wrote: version 1, no windowMs.
        val old = DiarizationCheckpoint.decode(
            """{"version":1,"modelId":"wespeaker_resnet34_lm","files":${files.joinToString(",", "[", "]") { "\"$it\"" }},"durations":[120000,120000,120000],"windowsDone":1}""",
        )
        assertNotNull(old)
        assertFalse(old!!.matches("wespeaker_resnet34_lm", files, durations, windows = 2))
    }

    @Test
    fun aCheckpointOfAnotherWindowLengthStartsOver() {
        val c = DiarizationCheckpoint(modelId = "m", files = files, durations = durations, windowsDone = 1, windowMs = 10L * 60 * 1000)
        assertFalse(c.matches("m", files, durations, windows = 2))
        val back = DiarizationCheckpoint.decode(DiarizationCheckpoint.encode(c))!!
        assertFalse(back.matches("m", files, durations, windows = 2))
    }

    @Test
    fun aCheckpointOfTheCurrentPlanResumes() {
        val c = DiarizationCheckpoint(modelId = "m", files = files, durations = durations, windowsDone = 1)
        val back = DiarizationCheckpoint.decode(DiarizationCheckpoint.encode(c))!!
        assertTrue(back.matches("m", files, durations, windows = AudioPlan.windows(durations, List(3) { true }).size))
    }

    @Test
    fun theFailureCountTravelsWithTheCheckpoint() {
        // Written before failures were counted: none so far, and it still resumes.
        val old = DiarizationCheckpoint.decode(
            DiarizationCheckpoint.encode(DiarizationCheckpoint(modelId = "m", files = files, durations = durations, windowsDone = 1))
                .replace(",\"failures\":0", ""),
        )!!
        assertEquals(0, old.failures)
        assertTrue(old.matches("m", files, durations, windows = 2))
        val failed = old.copy(failures = 2)
        val back = DiarizationCheckpoint.decode(DiarizationCheckpoint.encode(failed))!!
        assertEquals(2, back.failures)
        assertTrue(back.matches("m", files, durations, windows = 2))
    }
}
