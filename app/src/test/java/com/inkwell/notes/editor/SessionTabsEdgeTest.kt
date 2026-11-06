package com.inkwell.notes.editor

import com.inkwell.notes.editor.logic.SessionTabs
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The editor opens a document's tab when it first sees the document and afterwards only
 * follows renames, so a document stream that keeps emitting while a closed tab's editor
 * fades out (its pause writes the view state) can never bring the tab back.
 */
class SessionTabsEdgeTest {

    @Test
    fun `a rename after the tab was closed does not reopen it`() {
        val tabs = SessionTabs()
        tabs.open("a", "Alpha")
        tabs.open("x", "Notes")
        assertEquals("a", tabs.close("x"))
        // The closed editor's document stream emits again during its exit transition.
        tabs.rename("x", "Notes")
        tabs.rename("x", "Notes renamed")
        assertEquals(listOf("a"), tabs.tabs.value.map { it.docId })
    }

    @Test
    fun `a rename of an open tab keeps its place`() {
        val tabs = SessionTabs()
        tabs.open("a", "Alpha")
        tabs.open("b", "Beta")
        tabs.rename("a", "Alpha 2")
        assertEquals(listOf("a" to "Alpha 2", "b" to "Beta"), tabs.tabs.value.map { it.docId to it.title })
    }
}
