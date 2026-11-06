package com.inkwell.notes.export.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderCollisionTest {
    @Test
    fun `titles that sanitise to the same segment get distinct directories`() {
        val a = FolderNaming.documentDir(emptyList(), "Q&A: Lecture", "0b6f2c1e-8a4d")
        val b = FolderNaming.documentDir(emptyList(), "Q&A_ Lecture", "7c1d9e0f-1111")
        assertNotEquals(a, b)
        assertEquals("Q&A_ Lecture", b.last())
        assertEquals("Q&A_ Lecture (0b6f2c)", a.last())
    }

    @Test
    fun `clean titles keep their plain directory name`() {
        assertEquals(
            listOf("Inkwell", "Economics", "20261015_Financial Markets"),
            FolderNaming.documentDir(listOf("Economics"), "20261015_Financial Markets", "0b6f2c1e"),
        )
    }

    @Test
    fun `a truncated long title stays within the segment limit with its suffix`() {
        val seg = FolderNaming.documentDir(emptyList(), "x".repeat(150), "abcdef123").last()
        assertTrue(seg.endsWith(" (abcdef)"))
        assertTrue(seg.length <= 100)
    }
}
