package com.inkwell.notes.audio

import com.inkwell.notes.audio.recorder.RecordingRuntime
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Recording errors reach the recorder overlay through [RecordingRuntime]. Its state keeps equal
 * values, so the same failure twice in a row (Record tapped again with storage still full) is
 * only visible as a new report; without one the second tap showed nothing at all.
 */
class RecordingErrorReportTest {
    @After
    fun tearDown() {
        RecordingRuntime.mutableState.value = RecordingState.Idle
    }

    @Test
    fun theSameErrorTwiceIsTwoReportsAlthoughTheStateDoesNotChange() {
        RecordingRuntime.reportError("Not enough free storage to record.")
        val shown = RecordingRuntime.state.value
        val first = RecordingRuntime.errorReport.value

        RecordingRuntime.reportError("Not enough free storage to record.")

        // The state flow keeps the earlier, equal value: nothing for the overlay to react to...
        assertSame(shown, RecordingRuntime.state.value)
        // ...except the report count.
        assertEquals(first + 1, RecordingRuntime.errorReport.value)
    }

    @Test
    fun everyErrorIsPublishedAndCounted() {
        val before = RecordingRuntime.errorReport.value
        RecordingRuntime.reportError("Couldn't start recording.")
        assertEquals(RecordingState.Error("Couldn't start recording."), RecordingRuntime.state.value)
        RecordingRuntime.mutableState.value = RecordingState.Idle
        RecordingRuntime.reportError("The microphone is in use by another app.")
        assertEquals(RecordingState.Error("The microphone is in use by another app."), RecordingRuntime.state.value)
        assertEquals(before + 2, RecordingRuntime.errorReport.value)
    }
}
