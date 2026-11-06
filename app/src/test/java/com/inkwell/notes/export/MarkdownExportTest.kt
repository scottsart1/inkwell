package com.inkwell.notes.export

import com.inkwell.notes.core.model.BlockType
import com.inkwell.notes.core.model.StickyItem
import com.inkwell.notes.core.model.TextItem
import com.inkwell.notes.core.model.TextSource
import com.inkwell.notes.core.model.TextSpan
import com.inkwell.notes.core.model.TranscriptSegment
import com.inkwell.notes.core.model.TypedBlock
import com.inkwell.notes.core.model.TypedDocument
import com.inkwell.notes.export.format.DocumentTextBuilder
import com.inkwell.notes.export.format.ExportBlock
import com.inkwell.notes.export.format.InlineRun
import com.inkwell.notes.export.format.MarkdownWriter
import com.inkwell.notes.export.format.PageTextAssembler
import com.inkwell.notes.export.format.PageTextInput
import com.inkwell.notes.export.format.PlainTextWriter
import com.inkwell.notes.export.format.TextRow
import com.inkwell.notes.export.format.TranscriptInput
import com.inkwell.notes.export.format.TypedBlocks
import com.inkwell.notes.export.format.formatTimestamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownExportTest {

    private val typed = TypedDocument(
        blocks = listOf(
            TypedBlock("h1", BlockType.HEADING, "Overview", level = 1),
            TypedBlock("p1", BlockType.PARAGRAPH, "Prices rise when demand grows", spans = listOf(TextSpan(0, 6, bold = true), TextSpan(12, 18, italic = true))),
            TypedBlock("p2", BlockType.PARAGRAPH, "See docs or run ls -la", spans = listOf(TextSpan(4, 8, link = "https://example.com/docs"), TextSpan(16, 22, code = true))),
            TypedBlock("h2", BlockType.HEADING, "Steps", level = 2),
            TypedBlock("b1", BlockType.BULLET, "Collect data"),
            TypedBlock("b2", BlockType.BULLET, "From surveys", level = 1),
            TypedBlock("n1", BlockType.NUMBERED, "Clean"),
            TypedBlock("n2", BlockType.NUMBERED, "Model"),
            TypedBlock("p3", BlockType.PARAGRAPH, "Then:"),
            TypedBlock("n3", BlockType.NUMBERED, "Publish"),
            TypedBlock("c1", BlockType.CHECKLIST, "Draft", checked = true),
            TypedBlock("c2", BlockType.CHECKLIST, "Review"),
            TypedBlock("q", BlockType.QUOTE, "Markets clear"),
            TypedBlock("code", BlockType.CODE, "print(\"```\")", language = "python"),
            TypedBlock("t", BlockType.TABLE, rows = listOf(listOf("Term", "Meaning"), listOf("P|L", "Profit"), listOf("ROI"))),
            TypedBlock("img", BlockType.IMAGE, "Demand curve", assetId = "curve.png"),
            TypedBlock("d", BlockType.DIVIDER),
            TypedBlock("e", BlockType.PARAGRAPH, "  "),
        ),
    )

    @Test fun typedDocumentRendersAsGfm() {
        val doc = DocumentTextBuilder.build("20261015_Economics", typed, emptyList(), emptyList())
        val md = MarkdownWriter.write(doc)
        val expected = """
            # 20261015\_Economics

            ## Overview

            **Prices** rise *when d*emand grows

            See [docs](https://example.com/docs) or run `ls -la`

            ### Steps

            - Collect data
                - From surveys
            1. Clean
            2. Model

            Then:

            1. Publish
            - [x] Draft
            - [ ] Review

            > Markets clear

            ````python
            print("```")
            ````

            | Term | Meaning |
            | --- | --- |
            | P\|L | Profit |
            | ROI |  |

            *\[Image: Demand curve\]*

            ---
        """.trimIndent() + "\n"
        assertEquals(expected, md)
    }

    @Test fun plainTextDropsTheSyntax() {
        val doc = DocumentTextBuilder.build("Economics", typed, emptyList(), emptyList())
        val txt = PlainTextWriter.write(doc)
        assertTrue(txt.startsWith("Economics\n\nOverview\n\nPrices rise when demand grows\n"))
        assertTrue(txt.contains("• Collect data\n  • From surveys\n1. Clean\n2. Model\n"))
        assertTrue(txt.contains("[x] Draft\n[ ] Review\n"))
        assertTrue(txt.contains("    Markets clear\n"))
        assertTrue(txt.contains("Term\tMeaning\nP|L\tProfit\n"))
        assertTrue(txt.contains("[Image: Demand curve]"))
        assertTrue(!txt.contains("**") && !txt.contains("##") && !txt.contains("- [x]"))
    }

    @Test fun transcriptsBecomeTimestampedSections() {
        val doc = DocumentTextBuilder.build(
            title = "Lecture notes",
            typed = null,
            pages = listOf(PageTextInput(pageIndex = 0, texts = listOf(TextItem("t", 10f, 10f, 100f, 20f, "Typed box")))),
            transcripts = listOf(
                TranscriptInput("Week 3", listOf(TranscriptSegment(65_000, 70_000, "Second"), TranscriptSegment(5_000, 9_000, "First *star*", "Ana"))),
                TranscriptInput("Empty", listOf(TranscriptSegment(0, 1, "  "))),
                TranscriptInput("", listOf(TranscriptSegment(3_661_000, 3_662_000, "Late"))),
            ),
        )
        val md = MarkdownWriter.write(doc)
        assertTrue(md, md.contains("## Page 1\n\nTyped box\n"))
        assertTrue(md, md.contains("## Transcript — Week 3\n\n[00:05] **Ana:** First \\*star\\*\n\n[01:05] Second\n"))
        assertTrue(md, !md.contains("Empty"))
        assertTrue(md, md.contains("## Transcript — Recording\n\n[1:01:01] Late\n"))
        val txt = PlainTextWriter.write(doc)
        assertTrue(txt, txt.contains("Transcript — Week 3\n\n[00:05] Ana: First *star*\n[01:05] Second\n"))
    }

    @Test fun timestampsUseMinutesOrHours() {
        assertEquals("00:00", formatTimestamp(-5))
        assertEquals("09:59", formatTimestamp(599_999))
        assertEquals("1:00:00", formatTimestamp(3_600_000))
    }

    @Test fun lineStartsAndSpecialCharactersAreEscaped() {
        val md = MarkdownWriter.inline(listOf(InlineRun("# not a heading\n1. not a list\n- nor this <b>")))
        assertEquals("\\# not a heading\n1\\. not a list\n\\- nor this \\<b\\>", md)
        assertEquals("**bold** text", MarkdownWriter.inline(listOf(InlineRun("bold ", bold = true), InlineRun("text"))))
        assertEquals("``a`b``", MarkdownWriter.inline(listOf(InlineRun("a`b", code = true))))
        assertEquals("`` `tick ``", MarkdownWriter.inline(listOf(InlineRun("`tick", code = true))))
    }

    @Test fun pageTextIsInReadingOrder() {
        val input = PageTextInput(
            pageIndex = 0,
            texts = listOf(TextItem("b", 300f, 100f, 50f, 20f, "right"), TextItem("a", 20f, 104f, 50f, 20f, "left")),
            stickies = listOf(StickyItem("s", 10f, 400f, text = "sticky")),
            rows = listOf(
                TextRow("p", TextSource.HANDWRITING, "ink top", floatArrayOf(10f, 20f, 100f, 40f)),
                TextRow("p", TextSource.HANDWRITING, "ink unplaced", null),
                TextRow("p", TextSource.OCR, "ocr dup", null),
                TextRow("p", TextSource.PDF_TEXT, "pdf text", null),
            ),
            pdfText = "fallback not used",
        )
        val texts = PageTextAssembler.blocks(input).map { (it as ExportBlock.Paragraph).runs.single().text }
        assertEquals(listOf("pdf text", "ink top", "left", "right", "sticky", "ink unplaced"), texts)

        val onlyFallback = PageTextAssembler.blocks(PageTextInput(0, pdfText = "  embedded  "))
        assertEquals("embedded", (onlyFallback.single() as ExportBlock.Paragraph).runs.single().text)
    }

    @Test fun spansSplitAndMergeRuns() {
        val runs = TypedBlocks.fromSpans("abcdef", listOf(TextSpan(0, 4, bold = true), TextSpan(2, 6, italic = true), TextSpan(9, 12, strike = true)))
        assertEquals(listOf(InlineRun("ab", bold = true), InlineRun("cd", bold = true, italic = true), InlineRun("ef", italic = true)), runs)
        assertEquals(listOf(InlineRun("plain")), TypedBlocks.fromSpans("plain", emptyList()))
    }

    @Test fun boundsParsing() {
        assertEquals(listOf(1f, 2f, 3f, 4f), TextRow.parseBounds("1, 2,3 ,4")?.toList())
        assertNull(TextRow.parseBounds("1,2,3"))
        assertNull(TextRow.parseBounds("5,2,3,4")) // right < left
        assertNull(TextRow.parseBounds("a,b,c,d"))
        assertNull(TextRow.parseBounds(null))
    }
}
