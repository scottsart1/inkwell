package com.inkwell.notes.audio

import com.inkwell.notes.audio.recorder.RecordingRuntime
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Recording errors reach the recorder overlay through [RecordingRuntime]. The same failure twice
 * in a row (Record tapped again with storage still full) must show again: it is a new state value
 * (errors carry their time) and a new report.
 */
class RecordingErrorReportTest {
    @After
    fun tearDown() {
        RecordingRuntime.mutableState.value = RecordingState.Idle
    }

    @Test
    fun theSameErrorTwiceIsTwoReports() {
        RecordingRuntime.reportError("Not enough free storage to record.")
        val shown = RecordingRuntime.state.value
        val first = RecordingRuntime.errorReport.value
        Thread.sleep(2)

        RecordingRuntime.reportError("Not enough free storage to record.")

        assertNotEquals("a new value the overlay reacts to", shown, RecordingRuntime.state.value)
        assertEquals(first + 1, RecordingRuntime.errorReport.value)
    }

    @Test
    fun everyErrorIsPublishedAndCounted() {
        val before = RecordingRuntime.errorReport.value
        RecordingRuntime.reportError("Couldn't start recording.")
        assertEquals("Couldn't start recording.", (RecordingRuntime.state.value as RecordingState.Error).message)
        RecordingRuntime.mutableState.value = RecordingState.Idle
        RecordingRuntime.reportError("The microphone is in use by another app.")
        assertTrue(RecordingRuntime.state.value is RecordingState.Error)
        assertEquals("The microphone is in use by another app.", (RecordingRuntime.state.value as RecordingState.Error).message)
        assertEquals(before + 2, RecordingRuntime.errorReport.value)
    }
}
