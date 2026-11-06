package com.inkwell.notes.speakers

import com.inkwell.notes.speakers.logic.NativeRunRules
import com.inkwell.notes.speakers.logic.NativeRunRules.Decision
import com.inkwell.notes.speakers.logic.NativeRuns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A recording whose analysis keeps killing the app must stop being retried. */
class NativeRunRulesTest {
    private fun proceed(d: Decision): NativeRuns {
        assertTrue("expected to proceed: $d", d is Decision.Proceed)
        return d.next
    }

    @Test
    fun aRunThatComesBackLeavesNoTrace() {
        val started = proceed(NativeRunRules.start(NativeRuns(), "r1", "p1"))
        assertEquals(true, started.entries["r1"]?.inFlight)
        assertEquals(NativeRuns(), NativeRunRules.finish(started, "r1", "p1", completed = true))
    }

    @Test
    fun twoDeathsInsideNativeCodeStopTheRecording() {
        var s = proceed(NativeRunRules.start(NativeRuns(), "r1", "p1"))
        // The process died: nothing called finish. The next process's start counts one crash.
        s = proceed(NativeRunRules.start(s, "r1", "p2"))
        assertEquals(1, s.entries["r1"]?.crashes)
        val stop = NativeRunRules.start(s, "r1", "p3")
        assertTrue(stop is Decision.Stop)
        assertEquals(false, stop.next.entries["r1"]?.inFlight)
        // Stays stopped on later starts until the user tries again.
        assertTrue(NativeRunRules.start(stop.next, "r1", "p4") is Decision.Stop)
        assertTrue(NativeRunRules.start(NativeRunRules.reset(stop.next, "r1"), "r1", "p4") is Decision.Proceed)
    }

    @Test
    fun aJavaFailureIsNotACrash() {
        var s = proceed(NativeRunRules.start(NativeRuns(), "r1", "p1"))
        s = NativeRunRules.finish(s, "r1", "p1", completed = false)
        repeat(5) {
            s = proceed(NativeRunRules.start(s, "r1", "p1"))
            s = NativeRunRules.finish(s, "r1", "p1", completed = false)
        }
        assertEquals(0, s.entries["r1"]?.crashes)
    }

    @Test
    fun aLiveRunOfThisProcessIsNotACrash() {
        // A stopped run is still inside native code when its replacement starts in the same process.
        var s = proceed(NativeRunRules.start(NativeRuns(), "r1", "p1"))
        repeat(3) { s = proceed(NativeRunRules.start(s, "r1", "p1")) }
        assertEquals(0, s.entries["r1"]?.crashes)
        assertEquals(true, s.entries["r1"]?.inFlight)
    }

    @Test
    fun anotherProcessesMarkIsNotClearedByFinish() {
        // The process died inside native code; a later run gave up before entering it.
        val dead = proceed(NativeRunRules.start(NativeRuns(), "r1", "p1"))
        assertEquals(dead, NativeRunRules.finish(dead, "r1", "p2", completed = false))
        assertEquals(dead, NativeRunRules.finish(dead, "r1", "p2", completed = true))
        assertEquals(1, NativeRunRules.start(dead, "r1", "p2").next.entries["r1"]?.crashes)
    }

    @Test
    fun recordingsAreIndependentAndTheRecordSurvivesEncoding() {
        var s = proceed(NativeRunRules.start(NativeRuns(), "a", "p1"))
        s = proceed(NativeRunRules.start(s, "a", "p2"))
        s = proceed(NativeRunRules.start(s, "b", "p2"))
        val back = NativeRuns.decode(NativeRuns.encode(s))
        assertEquals(s, back)
        assertEquals(1, back.entries["a"]?.crashes)
        assertEquals(0, back.entries["b"]?.crashes)
        assertEquals("p2", back.entries["a"]?.owner)
        assertEquals(NativeRuns(), NativeRuns.decode("{not json"))
    }

    @Test
    fun aMarkWrittenBeforeOwnersWereRecordedCountsAsACrash() {
        val old = NativeRuns.decode("""{"entries":{"r1":{"inFlight":true,"crashes":0}}}""")
        assertEquals(1, NativeRunRules.start(old, "r1", "p1").next.entries["r1"]?.crashes)
    }
}
