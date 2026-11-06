package com.inkwell.notes.audio

import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Test

class RecordingStateTest {

    @Test fun aRepeatedFailureWithTheSameMessageIsANewState() {
        val first = RecordingState.Error("Not enough storage")
        val again = RecordingState.Error("Not enough storage")
        assertNotEquals(first, again)

        // A StateFlow skips equal values: the retry's failure must still reach the error toast.
        val state = MutableStateFlow<RecordingState>(first)
        state.value = again
        assertSame(again, state.value)
    }
}
