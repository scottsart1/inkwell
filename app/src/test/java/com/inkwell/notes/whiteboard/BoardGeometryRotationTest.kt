package com.inkwell.notes.whiteboard

import com.inkwell.notes.core.model.ImageItem
import com.inkwell.notes.core.model.PageContent
import com.inkwell.notes.core.model.ShapeItem
import com.inkwell.notes.core.model.ShapeKind
import com.inkwell.notes.core.model.TextItem
import com.inkwell.notes.whiteboard.logic.BoardGeometry
import com.inkwell.notes.whiteboard.logic.Box
import com.inkwell.notes.whiteboard.logic.FragmentOps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rotated items (turned with the lasso) take up their rotated box: the minimap, the overview
 * and "send to notebook" must see the area they are drawn in, not their unrotated box.
 */
class BoardGeometryRotationTest {
    private val eps = 1e-3f

    @Test fun rotatedTextCoversItsUprightBox() {
        val b = BoardGeometry.contentBounds(PageContent(texts = listOf(TextItem("t", 100f, 100f, 200f, 40f, "Label", rotation = 90f))))!!
        assertEquals(180f, b.left, eps)
        assertEquals(20f, b.top, eps)
        assertEquals(220f, b.right, eps)
        assertEquals(220f, b.bottom, eps)
    }

    @Test fun rotatedImageCoversItsCorners() {
        val b = BoardGeometry.contentBounds(PageContent(images = listOf(ImageItem("i", "a.png", 0f, 0f, 100f, 100f, rotation = 45f))))!!
        val half = 50f * kotlin.math.sqrt(2f)
        assertEquals(50f - half, b.left, eps)
        assertEquals(50f - half, b.top, eps)
        assertEquals(50f + half, b.right, eps)
        assertEquals(50f + half, b.bottom, eps)
    }

    @Test fun onlyBoxShapesAreRotated() {
        val ellipse = ShapeItem("e", ShapeKind.ELLIPSE, floatArrayOf(0f, 0f, 200f, 100f), 0, strokeWidth = 2f, rotation = 90f)
        val e = BoardGeometry.contentBounds(PageContent(shapes = listOf(ellipse)))!!
        assertEquals(49f, e.left, eps)
        assertEquals(-51f, e.top, eps)
        assertEquals(151f, e.right, eps)
        assertEquals(151f, e.bottom, eps)
        // Lines store their real end points; a rotation value does not move them.
        val line = ShapeItem("l", ShapeKind.LINE, floatArrayOf(0f, 0f, 100f, 0f), 0, strokeWidth = 2f, rotation = 30f)
        val l = BoardGeometry.contentBounds(PageContent(shapes = listOf(line)))!!
        assertEquals(-1f, l.left, eps)
        assertEquals(-1f, l.top, eps)
        assertEquals(101f, l.right, eps)
        assertEquals(1f, l.bottom, eps)
    }

    @Test fun sendingARotatedLabelKeepsItOnThePage() {
        // A vertical label (a 400 x 40 box turned 90 degrees) sent to an A4 page with 48 pt margins.
        val label = PageContent(texts = listOf(TextItem("t", 0f, 0f, 400f, 40f, "Vertical", rotation = 90f)))
        val target = Box(48f, 48f, 595f - 48f, 842f - 48f)
        val fitted = FragmentOps.fitInto(label, target)
        val drawn = BoardGeometry.contentBounds(fitted)!!
        // It used to be placed by its unrotated box, so its top 132 pt ended above the page.
        assertEquals(target.top, drawn.top, 0.01f)
        assertEquals(target.centerX, drawn.centerX, 0.01f)
        assertTrue(drawn.left >= target.left - eps && drawn.right <= target.right + eps)
        assertTrue(drawn.bottom <= target.bottom + eps)
        assertEquals(400f, drawn.height, 0.01f) // fits, so it keeps its size
    }
}
