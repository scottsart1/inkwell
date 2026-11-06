package com.inkwell.notes.editor

import com.inkwell.notes.editor.logic.PendingCapture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PendingCaptureTest {
    @Test fun `a capture with a page point survives a round trip`() {
        val c = PendingCapture("/data/cache/camera/a|b.jpg", 3, 120.5f, 88.25f)
        assertEquals(c, PendingCapture.decode(c.encode()))
    }

    @Test fun `a capture for the viewport centre keeps its missing placement`() {
        val c = PendingCapture("/data/cache/camera/x.jpg", null, null, null)
        assertEquals(c, PendingCapture.decode(c.encode()))
    }

    @Test fun `anything else is not a capture`() {
        assertNull(PendingCapture.decode(""))
        assertNull(PendingCapture.decode("1|2|3"))
        assertNull(PendingCapture.decode("1|2|3|"))
    }
}
