package com.inkwell.notes.ink.tools

import com.inkwell.notes.core.model.AudioAnchor
import com.inkwell.notes.core.model.ConnectorItem
import com.inkwell.notes.core.model.EraserFilter
import com.inkwell.notes.core.model.EraserMode
import com.inkwell.notes.core.model.ImageItem
import com.inkwell.notes.core.model.PageContent
import com.inkwell.notes.core.model.PenType
import com.inkwell.notes.core.model.TextItem
import com.inkwell.notes.ink.TestInk
import com.inkwell.notes.ink.geometry.Bounds
import com.inkwell.notes.ink.geometry.ItemGeometry
import com.inkwell.notes.ink.model.ItemIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.IdentityHashMap

class EraserEngineTest {
    private var next = 0
    private val engine = EraserEngine { "piece-${next++}" }

    private fun erase(content: PageContent, mode: EraserMode, filter: EraserFilter, x: Float, y: Float, r: Float = 3f): PageContent {
        val index = ItemIndex.build(content, IdentityHashMap())
        return engine.apply(content, index, mode, filter, x, y, x, y, r, Bounds())
    }

    private val ink = TestInk.line("ink", 0f, 10f, 100f, 10f)
    private val hl = TestInk.line("hl", 0f, 10f, 100f, 10f, pen = PenType.HIGHLIGHTER, width = 12f)
    private val box = TestInk.rect("box", 0f, 0f, 100f, 20f)
    private val page = PageContent(strokes = listOf(hl, ink), shapes = listOf(box))

    @Test fun filtersDecideWhatCanBeErased() {
        assertTrue(EraserRules.allows(EraserFilter.ALL, ink))
        assertTrue(EraserRules.allows(EraserFilter.ALL, hl))
        assertTrue(EraserRules.allows(EraserFilter.ALL, box))
        assertTrue(EraserRules.allows(EraserFilter.INK_ONLY, ink))
        assertFalse(EraserRules.allows(EraserFilter.INK_ONLY, hl))
        assertFalse(EraserRules.allows(EraserFilter.INK_ONLY, box))
        assertTrue(EraserRules.allows(EraserFilter.HIGHLIGHTER_ONLY, hl))
        assertFalse(EraserRules.allows(EraserFilter.HIGHLIGHTER_ONLY, ink))
        assertTrue(EraserRules.allows(EraserFilter.SHAPES_ONLY, box))
        assertFalse(EraserRules.allows(EraserFilter.SHAPES_ONLY, ink))
        // Text, images and stickies are never erased.
        val text = TextItem("t", 0f, 0f, 10f, 10f, "x")
        val img = ImageItem("i", "a", 0f, 0f, 10f, 10f)
        for (f in EraserFilter.entries) { assertFalse(EraserRules.allows(f, text)); assertFalse(EraserRules.allows(f, img)) }
    }

    @Test fun strokeModeRemovesWholeItemsRespectingFilter() {
        val all = erase(page, EraserMode.STROKE, EraserFilter.ALL, 50f, 10f)
        // Both strokes go; the hollow box is not touched at its centre.
        assertTrue(all.strokes.isEmpty())
        assertEquals(1, all.shapes.size)
        assertTrue(erase(all, EraserMode.STROKE, EraserFilter.ALL, 50f, 20f).isEmpty)
        val inkOnly = erase(page, EraserMode.STROKE, EraserFilter.INK_ONLY, 50f, 10f)
        assertEquals(listOf("hl"), inkOnly.strokes.map { it.id })
        assertEquals(1, inkOnly.shapes.size)
        val hlOnly = erase(page, EraserMode.STROKE, EraserFilter.HIGHLIGHTER_ONLY, 50f, 10f)
        assertEquals(listOf("ink"), hlOnly.strokes.map { it.id })
        val shapes = erase(page, EraserMode.STROKE, EraserFilter.SHAPES_ONLY, 50f, 0f)
        assertEquals(2, shapes.strokes.size)
        assertTrue(shapes.shapes.isEmpty())
    }

    @Test fun missLeavesContentUntouched() {
        assertSame(page, erase(page, EraserMode.STROKE, EraserFilter.ALL, 50f, 200f))
    }

    @Test fun precisionSplitsAndKeepsAnchorsAndOrder() {
        val anchored = ink.copy(audio = AudioAnchor("rec", 1234))
        val content = PageContent(strokes = listOf(hl, anchored, TestInk.line("other", 0f, 80f, 100f, 80f)))
        val out = erase(content, EraserMode.PRECISION, EraserFilter.INK_ONLY, 50f, 10f)
        // Highlighter untouched, ink split in two at the original position, other stroke kept.
        assertEquals(listOf("hl", "piece-0", "piece-1", "other"), out.strokes.map { it.id })
        val left = out.strokes[1]; val right = out.strokes[2]
        assertEquals(AudioAnchor("rec", 1234), left.audio)
        assertEquals(AudioAnchor("rec", 1234), right.audio)
        assertEquals(anchored.createdAt, left.createdAt)
        assertTrue(TestInk.xs(left).last() < 50f)
        assertTrue(TestInk.xs(right).first() > 50f)
    }

    @Test fun highlighterOnlyPrecisionRemovesHighlightingButNotHandwriting() {
        val out = erase(page, EraserMode.PRECISION, EraserFilter.HIGHLIGHTER_ONLY, 50f, 10f)
        assertTrue(out.strokes.any { it.id == "ink" })
        assertEquals(2, out.strokes.count { it.pen == PenType.HIGHLIGHTER })
        assertEquals(1, out.shapes.size)
    }

    @Test fun segmentModeCutsBetweenCrossings() {
        val h = TestInk.line("h", 0f, 50f, 100f, 50f, samples = 23)
        val v1 = TestInk.line("v1", 30f, 0f, 30f, 100f, samples = 17)
        val v2 = TestInk.line("v2", 70f, 0f, 70f, 100f, samples = 17)
        val out = erase(PageContent(strokes = listOf(h, v1, v2)), EraserMode.SEGMENT, EraserFilter.ALL, 50f, 50f, r = 2f)
        val pieces = out.strokes.filter { it.id.startsWith("piece") }
        assertEquals(2, pieces.size)
        assertTrue(out.strokes.any { it.id == "v1" } && out.strokes.any { it.id == "v2" })
        assertEquals(30f, TestInk.xs(pieces[0]).last(), 0.01f)
        assertEquals(70f, TestInk.xs(pieces[1]).first(), 0.01f)
    }

    @Test fun wordModeRemovesTheTouchedWordOnly() {
        // Two words of three letters each on one line, 20pt tall.
        fun letter(id: String, x: Float, t: Long) = TestInk.stroke(id, x, 0f, x + 8f, 20f, x + 16f, 0f, createdAt = t)
        val word1 = listOf(letter("a", 0f, 0), letter("b", 20f, 400), letter("c", 40f, 800))
        val word2 = listOf(letter("d", 100f, 5000), letter("e", 120f, 5400))
        val out = erase(PageContent(strokes = word1 + word2), EraserMode.WORD, EraserFilter.ALL, 24f, 10f, r = 2f)
        assertEquals(listOf("d", "e"), out.strokes.map { it.id })
    }

    @Test fun erasingAnAttachedItemFreesTheConnectorEndWhereItWasAndDirtiesTheWholeLine() {
        val a = TestInk.rect("a", 0f, 0f, 20f, 20f)
        val b = TestInk.rect("b", 100f, 0f, 120f, 20f)
        // Stale free-end coordinates (the item was moved after connecting).
        val c = ConnectorItem("c", fromItemId = "a", toItemId = "b", fromX = 500f, fromY = 500f)
        val content = PageContent(shapes = listOf(a, b), connectors = listOf(c))
        val live = ItemGeometry.connectorEndpoints(c, content, FloatArray(4))
        val changed = Bounds()
        val out = engine.apply(content, ItemIndex.build(content, IdentityHashMap()), EraserMode.STROKE, EraserFilter.ALL, 0f, 10f, 0f, 10f, 3f, changed)
        assertEquals(listOf("b"), out.shapes.map { it.id })
        val freed = out.connectors.single()
        assertNull(freed.fromItemId)
        assertEquals("b", freed.toItemId)
        assertEquals(live[0], freed.fromX, 0.01f)
        assertEquals(live[1], freed.fromY, 0.01f)
        // The whole connector is re-rendered, not just the erased box.
        assertTrue(changed.right >= live[2])
    }

    @Test fun splittingAnAttachedStrokeFreesTheConnectorEnd() {
        val s = TestInk.line("s", 0f, 10f, 100f, 10f)
        val b = TestInk.rect("b", 200f, 0f, 220f, 20f)
        val content = PageContent(strokes = listOf(s), shapes = listOf(b), connectors = listOf(ConnectorItem("c", fromItemId = "s", toItemId = "b")))
        val out = erase(content, EraserMode.PRECISION, EraserFilter.INK_ONLY, 50f, 10f)
        assertTrue(out.strokes.none { it.id == "s" })
        val freed = out.connectors.single()
        assertNull(freed.fromItemId)
        assertEquals("b", freed.toItemId)
        // Unrelated erasing leaves connectors alone.
        val other = PageContent(strokes = listOf(TestInk.line("o", 0f, 80f, 100f, 80f)), connectors = content.connectors)
        assertSame(other.connectors.single(), erase(other, EraserMode.STROKE, EraserFilter.ALL, 50f, 80f).connectors.single())
    }
}
