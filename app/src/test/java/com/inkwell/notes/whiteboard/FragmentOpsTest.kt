package com.inkwell.notes.whiteboard

import com.inkwell.notes.core.model.ConnectorItem
import com.inkwell.notes.core.model.ImageItem
import com.inkwell.notes.core.model.PageContent
import com.inkwell.notes.core.model.PenType
import com.inkwell.notes.core.model.ShapeItem
import com.inkwell.notes.core.model.ShapeKind
import com.inkwell.notes.core.model.StickyItem
import com.inkwell.notes.core.model.Stroke
import com.inkwell.notes.core.model.TextItem
import com.inkwell.notes.whiteboard.logic.BoardGeometry
import com.inkwell.notes.whiteboard.logic.Box
import com.inkwell.notes.whiteboard.logic.FragmentOps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FragmentOpsTest {
    private val eps = 1e-3f

    private val fragment = PageContent(
        strokes = listOf(Stroke("s1", PenType.BALLPOINT, 0, 2f, floatArrayOf(100f, 100f, 0.5f, 0f, 0f, 300f, 200f, 0.5f, 0f, 10f), 0L, z = 5)),
        shapes = listOf(ShapeItem("r1", ShapeKind.RECTANGLE, floatArrayOf(100f, 300f, 500f, 500f), 0, strokeWidth = 4f, z = 7)),
        texts = listOf(TextItem("t1", 120f, 520f, 200f, 40f, "Label", fontSizePt = 20f, z = 9)),
        images = listOf(ImageItem("i1", "asset.png", 400f, 100f, 100f, 100f, z = 3)),
        stickies = listOf(StickyItem("k1", 600f, 100f, z = 2, groupId = "g")),
        connectors = listOf(ConnectorItem("c1", fromItemId = "k1", toItemId = "t1", z = 10), ConnectorItem("c2", fromItemId = "zz", toX = 50f, toY = 50f)),
    )

    @Test fun boundsCoverEveryItemType() {
        val b = BoardGeometry.contentBounds(fragment)!!
        assertEquals(50f, b.left, eps) // unattached connector end
        assertEquals(50f, b.top, eps)
        assertEquals(780f, b.right, eps) // sticky 600 + 180
        assertEquals(560f, b.bottom, eps) // text 520 + 40
    }

    @Test fun fitIntoScalesDownAndCentres() {
        val target = Box(36f, 36f, 36f + 372f, 36f + 600f)
        val fitted = FragmentOps.fitInto(fragment, target)
        val b = BoardGeometry.contentBounds(fitted)!!
        assertTrue(b.width <= target.width + eps)
        assertEquals(target.top, b.top, 0.01f)
        assertEquals(target.centerX, b.centerX, 0.01f)
        // Everything shrank by the same factor, including sizes.
        val s = b.width / BoardGeometry.contentBounds(fragment)!!.width
        assertEquals(20f * s, fitted.texts.single().fontSizePt, 0.01f)
        assertEquals(4f * s, fitted.shapes.first { it.id == "r1" }.strokeWidth, 0.01f)
    }

    @Test fun boundsIncludeRotation() {
        val f = PageContent(images = listOf(ImageItem("i", "a.png", 0f, 0f, 400f, 100f, rotation = 90f)))
        val b = BoardGeometry.contentBounds(f)!!
        assertEquals(150f, b.left, 0.01f)
        assertEquals(-150f, b.top, 0.01f)
        assertEquals(250f, b.right, 0.01f)
        assertEquals(250f, b.bottom, 0.01f)
    }

    @Test fun fitIntoKeepsRotatedItemsOnThePage() {
        // A 400x100 image turned upright, sent to an A4 page inside 47.6pt margins.
        val f = PageContent(
            images = listOf(ImageItem("i", "a.png", 0f, 0f, 400f, 100f, rotation = 90f)),
            texts = listOf(TextItem("t", 500f, 0f, 300f, 40f, "x", rotation = 30f)),
        )
        val target = Box(47.6f, 47.6f, 595f - 47.6f, 842f - 47.6f)
        val fitted = FragmentOps.fitInto(f, target)
        val b = BoardGeometry.contentBounds(fitted)!!
        assertEquals(target.top, b.top, 0.01f)
        assertTrue(b.left >= target.left - eps && b.right <= target.right + eps)
        assertTrue(b.bottom <= target.bottom + eps)
        assertEquals(90f, fitted.images.single().rotation, eps)
    }

    @Test fun fitIntoTurnsStickiesIntoScalableItemsWhenShrinking() {
        val f = PageContent(
            stickies = listOf(StickyItem("k", 0f, 0f, width = 2000f, height = 180f, text = "Plan", color = 0xFFAABBCC.toInt(), z = 4)),
            connectors = listOf(ConnectorItem("c", fromItemId = "k", toX = 10f, toY = 300f)),
        )
        val fitted = FragmentOps.fitInto(f, Box(0f, 0f, 500f, 800f))
        val s = 500f / 2000f
        assertTrue(fitted.stickies.isEmpty())
        val box = fitted.shapes.single()
        assertEquals("k", box.id) // connectors stay attached
        assertEquals(0xFFAABBCC.toInt(), box.fillColor)
        assertEquals(6f * s, box.cornerRadius, eps)
        val text = fitted.texts.single()
        assertEquals("Plan", text.text)
        assertEquals(14f * s, text.fontSizePt, eps)
        assertEquals(10f * s, text.x - box.points[0], eps)
        assertEquals(1980f * s, text.width, 0.01f)
        assertEquals(4, text.z)
        assertTrue(text.groupId != null && text.groupId == box.groupId)
        // Nothing to shrink: stickies stay stickies.
        assertEquals(1, FragmentOps.fitInto(f, Box(0f, 0f, 5000f, 5000f)).stickies.size)
    }

    @Test fun fitIntoNeverUpscalesByDefault() {
        val small = PageContent(texts = listOf(TextItem("t", 0f, 0f, 10f, 10f, "x")))
        val fitted = FragmentOps.fitInto(small, Box(0f, 0f, 1000f, 1000f))
        assertEquals(10f, fitted.texts.single().width, eps)
        assertEquals(495f, fitted.texts.single().x, eps)
    }

    @Test fun translateMovesStrokePointsButNotPressure() {
        val moved = FragmentOps.translate(fragment, 10f, -20f)
        val p = moved.strokes.single().points
        assertEquals(110f, p[0], eps)
        assertEquals(80f, p[1], eps)
        assertEquals(0.5f, p[2], eps)
        assertEquals(10f, p[9], eps) // timestamp untouched
        assertEquals(100f, fragment.strokes.single().points[0], eps) // original not mutated
    }

    @Test fun freshIdsKeepConnectorLinksInsideTheFragment() {
        var n = 0
        val f = FragmentOps.withFreshIds(fragment) { "n${n++}" }
        val sticky = f.stickies.single()
        val text = f.texts.single()
        val c1 = f.connectors.first { it.toItemId != null }
        assertEquals(sticky.id, c1.fromItemId)
        assertEquals(text.id, c1.toItemId)
        assertNotEquals("k1", sticky.id)
        assertNotEquals("g", sticky.groupId)
        // A connector pointing outside the fragment becomes free-standing.
        assertEquals(null, f.connectors.first { it.toItemId == null }.fromItemId)
    }

    @Test fun compactZKeepsOrder() {
        val c = FragmentOps.compactZ(fragment)
        assertEquals(1, c.stickies.single().z) // z 2 -> rank 1 (0 is the free connector)
        assertTrue(c.images.single().z < c.strokes.single().z)
        assertTrue(c.texts.single().z < c.connectors.first().z)
        assertEquals(0, FragmentOps.minZ(c))
    }

    @Test fun boardTextReadsInRowOrder() {
        val f = PageContent(
            texts = listOf(TextItem("b", 300f, 10f, 10f, 10f, "second"), TextItem("c", 0f, 200f, 10f, 10f, "third")),
            stickies = listOf(StickyItem("a", 0f, 0f, text = "first")),
        )
        assertEquals("first\n\nsecond\n\nthird", FragmentOps.boardText(f))
        assertEquals(setOf("asset.png"), FragmentOps.assetIds(fragment))
    }

    @Test fun boardTextRowsAboveZeroAreNotMergedWithRowZero() {
        // Rows are 40pt everywhere: -30 belongs to the row above 0..40.
        val f = PageContent(
            texts = listOf(TextItem("a", 500f, -30f, 10f, 10f, "above")),
            stickies = listOf(StickyItem("b", 0f, 30f, text = "below")),
        )
        assertEquals("above\n\nbelow", FragmentOps.boardText(f))
    }
}
