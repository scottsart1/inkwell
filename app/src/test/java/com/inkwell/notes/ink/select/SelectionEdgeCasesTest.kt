package com.inkwell.notes.ink.select

import com.inkwell.notes.core.model.ConnectorItem
import com.inkwell.notes.core.model.ImageItem
import com.inkwell.notes.core.model.InkPoints
import com.inkwell.notes.core.model.LassoFilter
import com.inkwell.notes.core.model.PageContent
import com.inkwell.notes.core.model.PenType
import com.inkwell.notes.core.model.ShapeItem
import com.inkwell.notes.core.model.ShapeKind
import com.inkwell.notes.core.model.StickyItem
import com.inkwell.notes.core.model.Stroke
import com.inkwell.notes.core.model.TextItem
import com.inkwell.notes.ink.TestInk
import com.inkwell.notes.ink.geometry.Bounds
import com.inkwell.notes.ink.geometry.ItemGeometry
import com.inkwell.notes.ink.model.ContentOps
import com.inkwell.notes.ink.model.ItemIndex
import com.inkwell.notes.ink.model.Items
import com.inkwell.notes.ink.tools.LassoSelector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.IdentityHashMap
import kotlin.math.PI
import kotlin.math.abs

/** Transform baking, clipboard and lasso behaviour on edge cases. */
class SelectionEdgeCasesTest {
    private fun xy(s: Stroke, i: Int) = s.points[i * InkPoints.STRIDE] to s.points[i * InkPoints.STRIDE + 1]

    private fun select(c: PageContent, poly: FloatArray, filter: LassoFilter = LassoFilter()): Set<String> =
        LassoSelector.select(c, ItemIndex.build(c, IdentityHashMap()), poly, filter)

    // ------------------------------------------------------------------ Baking

    @Test fun rotationKeepsStrokeWidthExactly() {
        val s = TestInk.stroke("s", 0f, 0f, 10f, 0f, width = 0.1f)
        val out = TransformBaker.bake(s, Affine.rotate(0.3f, 5f, 0f)) as Stroke
        assertEquals(0.1f, out.width, 1e-6f)
        val big = TestInk.stroke("b", 0f, 0f, 10f, 0f, width = 150f)
        assertEquals(150f, (TransformBaker.bake(big, Affine.rotate(1f, 5f, 0f)) as Stroke).width, 1e-4f)
        // Scaling clamps into the model's width range.
        assertEquals(200f, (TransformBaker.bake(big, Affine.scale(3f, 3f, 0f, 0f)) as Stroke).width, 0f)
    }

    @Test fun rotatedBoxUnderUniformCornerScaleKeepsRotationAndScalesSize() {
        val t = TextItem("t", 10f, 10f, 100f, 20f, "x", rotation = 30f, fontSizePt = 12f)
        val out = TransformBaker.bake(t, Affine.scale(2f, 2f, 0f, 0f)) as TextItem
        assertEquals(30f, out.rotation, 1e-3f)
        assertEquals(200f, out.width, 1e-3f); assertEquals(40f, out.height, 1e-3f)
        assertEquals(24f, out.fontSizePt, 1e-3f)
        // Centre maps through the transform.
        assertEquals(2f * 60f, out.x + out.width / 2f, 1e-3f); assertEquals(2f * 20f, out.y + out.height / 2f, 1e-3f)
    }

    @Test fun fullTurnNormalisesBackToZero() {
        val img = ImageItem("i", "a", 0f, 0f, 80f, 40f, rotation = 350f)
        val out = TransformBaker.bake(img, Affine.rotate((10 * PI / 180).toFloat(), 40f, 20f)) as ImageItem
        assertEquals(0f, out.rotation, 1e-2f)
        assertEquals(180f, TransformBaker.normalizeDegrees(-180f), 0f)
        assertEquals(180f, TransformBaker.normalizeDegrees(540f), 0f)
        assertEquals(-90f, TransformBaker.normalizeDegrees(270f), 0f)
    }

    @Test fun shapeStrokeWidthAndCornerRadiusScale() {
        val rr = ShapeItem("r", ShapeKind.ROUNDED_RECTANGLE, floatArrayOf(100f, 50f, 0f, 0f), 0, strokeWidth = 2f, cornerRadius = 8f)
        val out = TransformBaker.bake(rr, Affine.scale(0.5f, 0.5f, 0f, 0f)) as ShapeItem
        assertEquals(1f, out.strokeWidth, 1e-4f)
        assertEquals(4f, out.cornerRadius, 1e-4f)
        // Reversed corners are normalised to left/top first.
        assertTrue(out.points[0] <= out.points[2] && out.points[1] <= out.points[3])
        assertEquals(0f, out.points[0], 1e-4f); assertEquals(50f, out.points[2], 1e-4f)
    }

    @Test fun boxMinimumSizeStopsCollapse() {
        val st = StickyItem("s", 0f, 0f, 100f, 100f)
        val out = TransformBaker.bake(st, Affine.scale(0f, 0f, 0f, 0f)) as StickyItem
        assertTrue(out.width > 0f && out.height > 0f)
        val box = TransformBaker.box(0f, 0f, 10f, 10f, 45f, Affine.scale(1e-9f, 1e-9f, 0f, 0f))
        assertTrue(box[2] > 0f && box[3] > 0f)
        assertEquals(45f, box[4], 1e-3f)
    }

    // ------------------------------------------------------------------ Clipboard

    @Test fun copyKeepsRelativePositionsAndInternalAttachments() {
        val a = StickyItem("a", 100f, 100f, 50f, 50f)
        val b = TextItem("b", 300f, 100f, 60f, 30f, "x")
        val s = TestInk.stroke("s", 120f, 200f, 320f, 200f, width = 4f)
        val c = ConnectorItem("c", fromItemId = "a", toItemId = "b")
        val page = PageContent(stickies = listOf(a), texts = listOf(b), strokes = listOf(s), connectors = listOf(c))
        val frag = SelectionClipboard.copy(page, setOf("a", "b", "s", "c"))!!
        assertEquals("a", frag.connectors[0].fromItemId); assertEquals("b", frag.connectors[0].toItemId)
        // Relative offsets are untouched.
        val fa = frag.stickies[0]; val fb = frag.texts[0]; val fs = frag.strokes[0]
        assertEquals(200f, fb.x - fa.x, 1e-3f); assertEquals(0f, fb.y - fa.y, 1e-3f)
        assertEquals(20f, xy(fs, 0).first - fa.x, 1e-3f); assertEquals(100f, xy(fs, 0).second - fa.y, 1e-3f)
        val bounds = SelectionClipboard.boundsOf(frag)!!
        assertEquals(0f, bounds.left, 1e-3f); assertEquals(0f, bounds.top, 1e-3f)
        // Width padding is included so pasting never lands ink outside the fragment box.
        assertTrue(bounds.right >= xy(fs, 1).first + 2f)
    }

    @Test fun copyOfOnlyAnAttachedConnectorFreezesBothEnds() {
        val a = StickyItem("a", 100f, 100f, 50f, 50f)
        val b = StickyItem("b", 300f, 100f, 50f, 50f)
        val c = ConnectorItem("c", fromItemId = "a", toItemId = "b")
        val page = PageContent(stickies = listOf(a, b), connectors = listOf(c))
        val frag = SelectionClipboard.copy(page, setOf("c"))!!
        val fc = frag.connectors.single()
        assertEquals(null, fc.fromItemId); assertEquals(null, fc.toItemId)
        // From a's right edge midpoint to b's left edge midpoint: 150 points apart, same height.
        assertEquals(150f, fc.toX - fc.fromX, 1e-3f)
        assertEquals(0f, fc.toY - fc.fromY, 1e-3f)
        val bounds = SelectionClipboard.boundsOf(frag)!!
        assertEquals(0f, bounds.left, 1e-3f); assertEquals(0f, bounds.top, 1e-3f)
    }

    @Test fun removingAnAttachedItemFreezesTheArrowEndWhereTheItemWas() {
        // Stored ends are stale creation-time points; the boxes have moved since.
        val a = TextItem("a", 100f, 100f, 60f, 30f, "x")
        val b = StickyItem("b", 300f, 100f, 50f, 30f)
        val c = ConnectorItem("c", fromItemId = "a", toItemId = "b", fromX = -900f, fromY = -900f, toX = -800f, toY = -800f)
        val other = ConnectorItem("o", fromItemId = "b", toItemId = "missing", fromX = 1f, fromY = 2f, toX = 3f, toY = 4f)
        val page = PageContent(texts = listOf(a), stickies = listOf(b), connectors = listOf(c, other))
        val out = SelectionClipboard.detachRemoved(page, ContentOps.remove(page, setOf("a")))
        val fc = out.connectors.single { it.id == "c" }
        assertEquals(null, fc.fromItemId)
        // a's right edge midpoint (facing b), not the stale (-900, -900).
        assertEquals(160f, fc.fromX, 1e-3f); assertEquals(115f, fc.fromY, 1e-3f)
        assertEquals("b", fc.toItemId)
        // Untouched by this edit: still refers to b and keeps its own dangling end as it was.
        assertTrue(out.connectors.single { it.id == "o" } === other)
        // Nothing removed (or no connectors): the very same content comes back.
        assertTrue(SelectionClipboard.detachRemoved(page, page) === page)
        val moved = ContentOps.replace(page, mapOf("a" to a.copy(x = 120f)))
        assertTrue(SelectionClipboard.detachRemoved(page, moved) === moved)
    }

    @Test fun remapAfterPasteKeepsAttachmentsAndDetachesUnknowns() {
        val c = ConnectorItem("c", fromItemId = "a", toItemId = "zz", fromX = 1f, fromY = 2f, toX = 3f, toY = 4f)
        val out = Items.remapConnector(c, mapOf("a" to "a2", "c" to "c2")) as ConnectorItem
        assertEquals("a2", out.fromItemId)
        assertEquals(null, out.toItemId)
        assertEquals(3f, out.toX, 0f)
    }

    // ------------------------------------------------------------------ Lasso

    @Test fun rectangleLassoDrawnInAnyDirectionSelectsTheSame() {
        val s = TestInk.line("s", 20f, 50f, 80f, 50f)
        val c = PageContent(strokes = listOf(s))
        for (poly in listOf(
            LassoSelector.rectPolygon(0f, 0f, 100f, 100f), LassoSelector.rectPolygon(100f, 100f, 0f, 0f),
            LassoSelector.rectPolygon(100f, 0f, 0f, 100f), LassoSelector.rectPolygon(0f, 100f, 100f, 0f),
        )) assertEquals(setOf("s"), select(c, poly))
        // A degenerate (zero area) rectangle selects nothing.
        assertEquals(emptySet<String>(), select(c, LassoSelector.rectPolygon(0f, 0f, 0f, 100f)))
        assertEquals(emptySet<String>(), select(c, floatArrayOf(0f, 0f, 100f, 100f)))
    }

    @Test fun rotatedRectangleShapeIsTestedAlongItsRotatedOutline() {
        // A long thin rectangle rotated 90 degrees stands upright; a lasso around its unrotated box misses it.
        val tall = ShapeItem("r", ShapeKind.RECTANGLE, floatArrayOf(0f, 40f, 200f, 60f), 0, rotation = 90f)
        val c = PageContent(shapes = listOf(tall))
        assertEquals(emptySet<String>(), select(c, LassoSelector.rectPolygon(-10f, 30f, 210f, 70f)))
        assertEquals(setOf("r"), select(c, LassoSelector.rectPolygon(80f, -60f, 120f, 160f)))
    }

    @Test fun strokeMadeOfOneDotIsSelectedByItsPosition() {
        val dot = TestInk.stroke("d", 50f, 50f, width = 6f)
        val c = PageContent(strokes = listOf(dot))
        assertEquals(setOf("d"), select(c, LassoSelector.rectPolygon(40f, 40f, 60f, 60f)))
        assertEquals(emptySet<String>(), select(c, LassoSelector.rectPolygon(60f, 60f, 90f, 90f)))
    }

    @Test fun lassoPolygonWithRepeatedPointsStillWorks() {
        val poly = floatArrayOf(0f, 0f, 0f, 0f, 100f, 0f, 100f, 0f, 100f, 100f, 100f, 100f, 0f, 100f, 0f, 100f, 0f, 0f)
        val c = PageContent(strokes = listOf(TestInk.line("s", 20f, 50f, 80f, 50f)))
        assertEquals(setOf("s"), select(c, poly))
    }

    @Test fun selfIntersectingLassoUsesEvenOdd() {
        // A bow tie pinched at (50, 50): the left and right lobes count as inside, the pinch does not.
        val poly = floatArrayOf(0f, 0f, 100f, 100f, 100f, 0f, 0f, 100f)
        val c = PageContent(strokes = listOf(TestInk.line("left", 10f, 40f, 10f, 60f), TestInk.line("across", 20f, 10f, 80f, 10f)))
        val sel = select(c, poly)
        assertTrue("left" in sel)
        assertTrue("across" !in sel)
    }

    @Test fun connectorAttachedToItemsIsSelectedByItsLiveEndpoints() {
        val a = StickyItem("a", 0f, 0f, 50f, 50f)
        val b = StickyItem("b", 150f, 0f, 50f, 50f)
        // Stored fallback coordinates are far away: the live endpoints must be used.
        val c = ConnectorItem("c", fromItemId = "a", toItemId = "b", fromX = 900f, fromY = 900f, toX = 950f, toY = 950f)
        val page = PageContent(stickies = listOf(a, b), connectors = listOf(c))
        val sel = select(page, LassoSelector.rectPolygon(40f, -10f, 160f, 60f), LassoFilter(text = false))
        assertEquals(setOf("c"), sel)
    }

    @Test fun groupExpansionCoversEveryItemType() {
        val s = TestInk.line("s", 10f, 10f, 20f, 10f).copy(groupId = "g")
        val sh = ShapeItem("sh", ShapeKind.LINE, floatArrayOf(500f, 500f, 600f, 500f), 0, groupId = "g")
        val t = TextItem("t", 700f, 700f, 10f, 10f, "x", groupId = "g")
        val i = ImageItem("i", "a", 800f, 800f, 10f, 10f, groupId = "g")
        val st = StickyItem("st", 900f, 900f, groupId = "g")
        val c = PageContent(strokes = listOf(s), shapes = listOf(sh), texts = listOf(t), images = listOf(i), stickies = listOf(st))
        assertEquals(setOf("s", "sh", "t", "i", "st"), LassoSelector.expandGroups(c, setOf("i")))
    }

    // ------------------------------------------------------------------ Geometry

    @Test fun selectionBoundsOfRotatedTextUseTheRotatedCorners() {
        val t = TextItem("t", 0f, 0f, 100f, 20f, "x", rotation = 90f)
        val b = ItemGeometry.bounds(t, Bounds())
        assertEquals(40f, b.left, 1e-3f); assertEquals(60f, b.right, 1e-3f)
        assertEquals(-40f, b.top, 1e-3f); assertEquals(60f, b.bottom, 1e-3f)
    }

    @Test fun contentOpsReplaceKeepsOrderAndFragmentKeepsPageOrder() {
        val a = TestInk.line("a", 0f, 0f, 1f, 0f); val b = TestInk.line("b", 0f, 0f, 1f, 0f); val c = TestInk.line("c", 0f, 0f, 1f, 0f)
        val page = PageContent(strokes = listOf(a, b, c))
        val moved = TransformBaker.bake(b, Affine.translate(5f, 5f))
        val out = ContentOps.replace(page, mapOf("b" to moved, "zz" to a))
        assertEquals(listOf("a", "b", "c"), out.strokes.map { it.id })
        assertEquals(5f, xy(out.strokes[1], 0).first, 0f)
        val frag = ContentOps.fragment(page, setOf("c", "a"))
        assertEquals(listOf("a", "c"), frag.strokes.map { it.id })
    }

    @Test fun recolorKeepsHighlighterTranslucencyAndOpaqueInkOpaque() {
        val hl = TestInk.stroke("h", 0f, 0f, 10f, 0f, pen = PenType.HIGHLIGHTER, color = 0x66FFE000)
        val out = Items.recolor(hl, 0xFF00FF00.toInt()) as Stroke
        assertEquals(0x66, out.color ushr 24)
        assertEquals(0x00FF00, out.color and 0xFFFFFF)
        val ink = TestInk.stroke("i", 0f, 0f, 10f, 0f, color = 0xFF000000.toInt())
        assertEquals(0xFF0000FF.toInt(), (Items.recolor(ink, 0xFF0000FF.toInt()) as Stroke).color)
    }

    private fun near(a: Float, b: Float) = abs(a - b) < 1e-3f
}
