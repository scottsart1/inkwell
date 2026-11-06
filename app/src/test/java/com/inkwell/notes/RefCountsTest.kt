package com.inkwell.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The app's own read grant for a shared file is shared by every import of that file: only the last one revokes it. */
class RefCountsTest {
    private val log = ArrayList<String>()
    private val refs = RefCounts<String>()

    private fun hold(key: String, ok: Boolean = true) = refs.acquire(key) { log += "grant $key"; ok }
    private fun drop(key: String) = refs.release(key) { log += "revoke $key" }

    @Test fun twoImportsOfTheSameFileShareOneGrant() {
        assertTrue(hold("a"))
        assertTrue(hold("a"))
        assertEquals(listOf("grant a"), log)
        drop("a")
        // The first import finished: the second still reads under the grant.
        assertEquals(listOf("grant a"), log)
        assertEquals(1, refs.count("a"))
        drop("a")
        assertEquals(listOf("grant a", "revoke a"), log)
        assertEquals(0, refs.count("a"))
    }

    @Test fun aFailedGrantHoldsNothing() {
        assertFalse(hold("a", ok = false))
        assertEquals(0, refs.count("a"))
        drop("a")
        assertEquals(listOf("grant a"), log)
        // The next import tries the grant again.
        assertTrue(hold("a"))
        assertEquals(listOf("grant a", "grant a"), log)
    }

    @Test fun keysAreCountedSeparately() {
        hold("a")
        hold("b")
        drop("a")
        assertEquals(listOf("grant a", "grant b", "revoke a"), log)
        assertEquals(1, refs.count("b"))
    }

    @Test fun aGrantAfterTheLastReleaseIsTakenAgain() {
        hold("a")
        drop("a")
        hold("a")
        assertEquals(listOf("grant a", "revoke a", "grant a"), log)
    }
}
