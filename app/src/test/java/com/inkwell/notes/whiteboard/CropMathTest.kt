package com.inkwell.notes.whiteboard

import com.inkwell.notes.whiteboard.logic.Box
import com.inkwell.notes.whiteboard.logic.CropHandle
import com.inkwell.notes.whiteboard.logic.CropMath
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class CropMathTest {
    private fun assertValid(r: Box) {
        assertTrue("$r", r.left >= 0f && r.top >= 0f && r.right <= 1f && r.bottom <= 1f)
        assertTrue("$r", r.left <= r.right && r.top <= r.bottom)
    }

    @Test
    fun smallestBoxMovedToTheLeftEdgeCanStillBeGrabbedByItsLeftHandle() {
        var r = CropMath.initial()
        r = CropMath.drag(r, CropHandle.RIGHT, -1f, 0f) // narrowest
        r = CropMath.drag(r, CropHandle.MOVE, -1f, 0f) // against the left edge
        for (h in listOf(CropHandle.LEFT, CropHandle.TOP_LEFT, CropHandle.BOTTOM_LEFT)) {
            for (dx in listOf(0f, -0.1f, 0.1f)) assertValid(CropMath.drag(r, h, dx, 0f))
        }
    }

    @Test
    fun smallestBoxMovedToTheTopEdgeCanStillBeGrabbedByItsTopHandle() {
        var r = CropMath.initial()
        r = CropMath.drag(r, CropHandle.BOTTOM, 0f, -1f)
        r = CropMath.drag(r, CropHandle.MOVE, 0f, -1f)
        for (h in listOf(CropHandle.TOP, CropHandle.TOP_LEFT, CropHandle.TOP_RIGHT)) {
            for (dy in listOf(0f, -0.1f, 0.1f)) assertValid(CropMath.drag(r, h, 0f, dy))
        }
    }

    @Test
    fun randomDragsNeverThrowAndStayInside() {
        val rnd = Random(42)
        val handles = CropHandle.entries
        repeat(2_000) {
            var r = CropMath.initial()
            repeat(30) {
                val big = rnd.nextBoolean()
                val dx = (rnd.nextFloat() - 0.5f) * (if (big) 3f else 0.2f)
                val dy = (rnd.nextFloat() - 0.5f) * (if (big) 3f else 0.2f)
                r = CropMath.drag(r, handles[rnd.nextInt(handles.size)], dx, dy)
                assertValid(r)
            }
        }
    }
}
