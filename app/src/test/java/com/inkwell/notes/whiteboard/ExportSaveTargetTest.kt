package com.inkwell.notes.whiteboard

import com.inkwell.notes.whiteboard.logic.ExportMath
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The lost-render fallback must only delete the empty placeholder the save picker created. */
class ExportSaveTargetTest {
    @Test
    fun emptyPlaceholderMayBeDeleted() {
        assertTrue(ExportMath.mayDeleteUnwrittenTarget(0L))
    }

    @Test
    fun existingFileChosenForOverwriteIsKept() {
        assertFalse(ExportMath.mayDeleteUnwrittenTarget(1L))
        assertFalse(ExportMath.mayDeleteUnwrittenTarget(48_213L))
    }

    @Test
    fun unknownSizeIsKept() {
        assertFalse(ExportMath.mayDeleteUnwrittenTarget(null))
    }

    @Test
    fun nonsenseSizeIsKept() {
        assertFalse(ExportMath.mayDeleteUnwrittenTarget(-1L))
    }
}
