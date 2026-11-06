package com.inkwell.notes.ink.model

import com.inkwell.notes.core.model.ConnectorItem
import com.inkwell.notes.core.model.ImageItem
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
import com.inkwell.notes.ink.geometry.SpatialGrid
import com.inkwell.notes.ink.render.RenderOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.IdentityHashMap

/** Index, diff and item helpers on unusual content. */
class ContentModelEdgeCasesTest {

    @Test fun indexSkipsItemsWithoutGeometryAndStillHitsTheRest() {
        val empty = Stroke("e", PenType.BALLPOINT, 0, 2f, FloatArray(0), 0L)
        val s = TestInk.line("s", 10f, 10f, 90f, 10f)
        val c = PageContent(strokes = listOf(empty, s))
        val idx = ItemIndex.build(c, IdentityHashMap())
        assertEquals(2, idx.entries.size)
        assertTrue(idx.boundsOf(empty)!!.isEmpty)
        assertSame(s, idx.hitTest(50f, 10f, 2f))
        assertNull(idx.hitTest(50f, 40f, 2f))
    }

    @Test fun boundsCacheIsReusedAcrossVersionsButNotForConnectors() {
        val a = StickyItem("a", 0f, 0f, 50f, 50f)
        val b = StickyItem("b", 200f, 0f, 50f, 50f)
        val c = ConnectorItem("c", fromItemId = "a", toItemId = "b")
        val cache = IdentityHashMap<Any, Bounds>()
        val v1 = PageContent(stickies = listOf(a, b), connectors = listOf(c))
        val i1 = ItemIndex.build(v1, cache)
        val cb1 = i1.boundsOf(c)!!.copy()
        // Move b: the connector's bounds must follow even though the connector instance is unchanged.
        val b2 = b.copy(x = 400f)
        val v2 = PageContent(stickies = listOf(a, b2), connectors = listOf(c))
        val i2 = ItemIndex.build(v2, cache)
        val cb2 = i2.boundsOf(c)!!
        assertTrue(cb2.right > cb1.right + 100f)
        assertSame(i1.boundsOf(a), cache[a])
    }

    @Test fun hitTestPrefersTheTopmostItemByPaintOrder() {
        val under = TestInk.line("under", 0f, 50f, 100f, 50f, z = 5)
        val over = TestInk.line("over", 0f, 50f, 100f, 50f, z = 1)
        val hl = TestInk.line("hl", 0f, 50f, 100f, 50f, pen = PenType.HIGHLIGHTER, z = 9, width = 10f)
        // z wins over list order; highlighting always lies beneath the ink of its run, whatever its z,
        // so handwriting written over a highlight is what a tap picks.
        val c = PageContent(strokes = listOf(over, under, hl))
        val idx = ItemIndex.build(c, IdentityHashMap())
        assertSame(under, idx.hitTest(50f, 50f, 1f))
        assertSame(over, idx.hitTest(50f, 50f, 1f) { it !== under })
        assertSame(hl, idx.hitTest(50f, 50f, 1f) { it is Stroke && it.pen == PenType.HIGHLIGHTER })
        val order = RenderOrder.order(c)
        assertEquals(listOf("hl", "over", "under"), order.map { Items.id(it) })
    }

    @Test fun renderOrderPutsBoxesBeneathInkOfTheSameZ() {
        val img = ImageItem("i", "a", 0f, 0f, 10f, 10f, z = 3)
        val st = StickyItem("st", 0f, 0f, z = 3)
        val cn = ConnectorItem("cn", z = 3)
        val s = TestInk.line("s", 0f, 0f, 1f, 0f, z = 3)
        val sh = ShapeItem("sh", ShapeKind.LINE, floatArrayOf(0f, 0f, 1f, 1f), 0, z = 3)
        val t = TextItem("t", 0f, 0f, 1f, 1f, "x", z = 3)
        val higher = TestInk.line("h2", 0f, 0f, 1f, 0f, z = 2)
        val c = PageContent(strokes = listOf(s, higher), shapes = listOf(sh), texts = listOf(t), images = listOf(img), stickies = listOf(st), connectors = listOf(cn))
        val ids = RenderOrder.order(c).map { Items.id(it) }
        assertEquals(listOf("h2", "i", "st", "cn", "s", "sh", "t"), ids)
        assertEquals(RenderOrder.order(c).size, ContentOps.itemCount(c))
    }

    @Test fun negativeAndHugeZValuesOrderWithoutOverflow() {
        val lo = TestInk.line("lo", 0f, 0f, 1f, 0f, z = Int.MIN_VALUE)
        val hi = TestInk.line("hi", 0f, 0f, 1f, 0f, z = Int.MAX_VALUE)
        val img = ImageItem("img", "a", 0f, 0f, 1f, 1f, z = Int.MAX_VALUE)
        val c = PageContent(strokes = listOf(hi, lo), images = listOf(img))
        assertEquals(listOf("lo", "img", "hi"), RenderOrder.order(c).map { Items.id(it) })
        assertEquals(Int.MAX_VALUE, c.maxZ())
        assertEquals(Int.MIN_VALUE, ContentOps.minZ(c))
    }

    @Test fun diffAndApplyHandleReplaceRemoveAndAddInOneStep() {
        val a = TestInk.line("a", 0f, 0f, 1f, 0f); val b = TestInk.line("b", 0f, 0f, 1f, 0f); val c = TestInk.line("c", 0f, 0f, 1f, 0f)
        val t = TextItem("t", 0f, 0f, 1f, 1f, "x")
        val before = PageContent(strokes = listOf(a, b, c), texts = listOf(t))
        val b2 = b.copy(color = 1); val d = TestInk.line("d", 0f, 0f, 1f, 0f)
        val after = PageContent(strokes = listOf(b2, c, d), texts = emptyList())
        val diff = ContentOps.diff("p", before, after)
        assertEquals(setOf("a", "b", "t"), diff.removed.map { Items.id(it.item) }.toSet())
        assertEquals(setOf("b", "d"), diff.added.map { Items.id(it.item) }.toSet())
        val fwd = ContentOps.applyForward(before, diff)
        assertEquals(listOf("b", "c", "d"), fwd.strokes.map { it.id })
        assertSame(b2, fwd.strokes[0])
        assertTrue(fwd.texts.isEmpty())
        val back = ContentOps.applyBackward(fwd, diff)
        assertEquals(listOf("a", "b", "c"), back.strokes.map { it.id })
        assertSame(b, back.strokes[1])
        assertEquals(listOf(t), back.texts)
        // Replaying forward on content that already has the result is harmless.
        assertEquals(fwd.strokes.map { it.id }, ContentOps.applyForward(fwd, diff).strokes.map { it.id })
    }

    @Test fun removeAndReplaceReturnTheSameInstanceWhenNothingMatches() {
        val c = PageContent(strokes = listOf(TestInk.line("a", 0f, 0f, 1f, 0f)))
        assertSame(c, ContentOps.remove(c, setOf("zz")))
        assertSame(c, ContentOps.replace(c, mapOf("zz" to TestInk.line("zz", 0f, 0f, 1f, 0f))))
        assertSame(c, ContentOps.add(c, emptyList()))
        assertSame(c, ContentOps.mapItems(c, setOf("nope")) { it })
    }

    @Test fun addKeepsOneItemPerIdEvenWhenTheBatchRepeatsIt() {
        val a = TestInk.line("a", 0f, 0f, 1f, 0f)
        val a2 = a.copy(color = 7)
        val c = ContentOps.add(PageContent.EMPTY, listOf(a, a2))
        assertEquals(1, c.strokes.size)
        assertSame(a2, c.strokes[0])
    }

    @Test fun spatialGridFindsHugeAndTinyItemsAndIgnoresEmptyBounds() {
        val g = SpatialGrid()
        g.insert(0, Bounds(-1_000_000f, -1_000_000f, 1_000_000f, 1_000_000f))
        g.insert(1, Bounds(10f, 10f, 10f, 10f))
        g.insert(2, Bounds())
        g.insert(3, Bounds(500_000f, 500_000f, 500_001f, 500_001f))
        fun q(b: Bounds): Set<Int> { val out = HashSet<Int>(); g.query(b) { out += it }; return out }
        assertEquals(setOf(0, 1), q(Bounds(9f, 9f, 11f, 11f)))
        assertEquals(setOf(0), q(Bounds(-50f, -50f, -40f, -40f)))
        assertEquals(setOf(0, 3), q(Bounds(499_999f, 499_999f, 500_002f, 500_002f)))
        // A query spanning the whole canvas falls back to a scan and still returns everything with bounds.
        assertEquals(setOf(0, 1, 3), q(Bounds(-2_000_000f, -2_000_000f, 2_000_000f, 2_000_000f)))
        assertEquals(emptySet<Int>(), q(Bounds()))
        // Every inserted handle is remembered (an empty one simply never matches a query).
        assertEquals(4, g.size)
        assertTrue(g.bounds(2)!!.isEmpty)
    }

    @Test fun strokeBoundsIncludeTheWidestPossibleNib() {
        for (pen in PenType.entries) {
            val s = TestInk.stroke("s", 0f, 0f, 100f, 0f, pen = pen, width = 10f)
            val b = ItemGeometry.strokeBounds(s, Bounds())
            assertTrue("$pen", -b.top >= 10f * com.inkwell.notes.ink.render.PenModel.maxFactor(pen) / 2f)
        }
    }

    @Test fun connectorBoundsCoverArrowHeadsAndItemHitTestsUseLiveEnds() {
        val a = StickyItem("a", 0f, 0f, 50f, 50f)
        val b = StickyItem("b", 200f, 0f, 50f, 50f)
        val c = ConnectorItem("c", fromItemId = "a", toItemId = "b", width = 2f)
        val page = PageContent(stickies = listOf(a, b), connectors = listOf(c))
        val e = ItemGeometry.connectorEndpoints(c, page, FloatArray(4))
        assertEquals(50f, e[0], 0f); assertEquals(25f, e[1], 0f); assertEquals(200f, e[2], 0f); assertEquals(25f, e[3], 0f)
        assertTrue(ItemGeometry.hitPoint(c, 125f, 25f, 1f, page))
        assertTrue(!ItemGeometry.hitPoint(c, 125f, 40f, 1f, page))
        val bounds = ItemGeometry.bounds(c, Bounds(), page)
        assertTrue(bounds.contains(50f, 25f) && bounds.contains(200f, 25f))
        // A dangling attachment (item deleted) falls back to the stored coordinates.
        val orphan = ConnectorItem("o", fromItemId = "gone", fromX = 5f, fromY = 6f, toX = 7f, toY = 8f)
        val oe = ItemGeometry.connectorEndpoints(orphan, page, FloatArray(4))
        assertEquals(5f, oe[0], 0f); assertEquals(8f, oe[3], 0f)
        assertNotNull(ItemGeometry.bounds(orphan, Bounds(), page))
    }

    @Test fun stackFromKeepsRelativeOrderOfTypeGroupedItems() {
        // A pasted fragment: a stroke above an image, a second stroke sharing the image's z.
        val over = TestInk.line("over", 0f, 0f, 10f, 0f, z = 7)
        val tied = TestInk.line("tied", 0f, 5f, 10f, 5f, z = 3)
        val img = ImageItem("img", "a", 0f, 0f, 10f, 10f, z = 3)
        val out = ContentOps.stackFrom(ContentOps.allItems(PageContent(strokes = listOf(over, tied), images = listOf(img))), 20)
        val zs = out.associate { Items.id(it) to Items.z(it) }
        assertEquals(mapOf("over" to 21, "tied" to 20, "img" to 20), zs)
        assertEquals(listOf("over", "tied", "img"), out.map { Items.id(it) })
    }
}
