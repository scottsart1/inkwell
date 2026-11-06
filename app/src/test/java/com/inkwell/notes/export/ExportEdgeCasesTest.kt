package com.inkwell.notes.export

import com.inkwell.notes.core.model.OutlineEntry
import com.inkwell.notes.export.backup.BackupNaming
import com.inkwell.notes.export.backup.IdRemap
import com.inkwell.notes.export.backup.ZipSafety
import com.inkwell.notes.export.format.DocumentTextBuilder
import com.inkwell.notes.export.format.ExportBlock
import com.inkwell.notes.export.format.ExportDocument
import com.inkwell.notes.export.format.ExportSection
import com.inkwell.notes.export.format.InlineRun
import com.inkwell.notes.export.format.MarkdownWriter
import com.inkwell.notes.export.format.PageTextInput
import com.inkwell.notes.export.format.runsOf
import com.inkwell.notes.core.model.TextItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportEdgeCasesTest {

    @Test fun indentedParagraphsNeverBecomeCodeBlocks() {
        // Four leading spaces (or a tab) start an indented code block in CommonMark; a text box
        // whose first line happens to be indented must still export as a paragraph.
        val doc = ExportDocument("T", listOf(ExportSection(null, listOf(ExportBlock.Paragraph(runsOf("    indented start\n\tsecond line"))))))
        val md = MarkdownWriter.write(doc)
        val body = md.substringAfter("# T\n\n")
        assertFalse(body, body.startsWith("    "))
        assertFalse(body, body.contains("\n\t"))
        assertTrue(body, body.contains("indented start"))
    }

    @Test fun backupEntryNamesNeverSplitASurrogatePair() {
        val title = "x".repeat(79) + "😀" + " tail"
        val name = BackupNaming.entryName(title, "0b6f2c1e-8a4d-4c1b-9f7e-1d2c3b4a5f60")
        var i = 0
        while (i < name.length) {
            val c = name[i]
            if (Character.isHighSurrogate(c)) {
                assertTrue("lone high surrogate at $i in $name", i + 1 < name.length && Character.isLowSurrogate(name[i + 1]))
                i++
            } else {
                assertFalse("lone low surrogate at $i in $name", Character.isLowSurrogate(c))
            }
            i++
        }
        assertTrue(name.startsWith("documents/") && name.endsWith(".inkwell"))
    }

    @Test fun outlineChildrenOfADroppedEntryDoNotPointAtAMissingParent() {
        val remap = IdRemap.create(pageIds = listOf("p2"), recordingIds = emptyList(), outlineIds = listOf("a", "b"))
        val entries = listOf(
            OutlineEntry("a", "d", "Chapter", pageId = "p1"), // page p1 is not in the package
            OutlineEntry("b", "d", "Section", pageId = "p2", parentId = "a"),
        )
        val out = remap.outlineEntries(entries)
        assertEquals(1, out.size)
        val ids = out.map { it.id }.toSet()
        out.forEach { e -> assertTrue("parent ${e.parentId} must exist or be null", e.parentId == null || e.parentId in ids) }
    }

    @Test fun zipEntryPathsAreNormalised() {
        assertEquals("a/b", ZipSafety.safeEntryPath("./a//b"))
        assertNull(ZipSafety.safeEntryPath("a/../b"))
        assertNull(ZipSafety.safeEntryPath("..\\x"))
        assertNull(ZipSafety.safeEntryPath("C:\\x"))
        assertNull(ZipSafety.safeEntryPath("/abs"))
        assertNull(ZipSafety.safeEntryPath("a\u0000b"))
    }

    @Test fun blockStartsAreEscapedOnEveryLineOfAParagraph() {
        val doc = DocumentTextBuilder.build(
            "T", null,
            listOf(PageTextInput(0, texts = listOf(TextItem("t", 0f, 0f, 100f, 20f, "> quoted?\n+ plus\n= equals\n#tag ok\n1) one")))),
            emptyList(),
        )
        val md = MarkdownWriter.write(doc)
        assertTrue(md, md.contains("\\> quoted?"))
        assertTrue(md, md.contains("\\+ plus"))
        assertTrue(md, md.contains("\\= equals"))
        assertTrue(md, md.contains("\\#tag ok") || md.contains("#tag ok"))
        assertTrue(md, md.contains("1\\) one"))
    }

    @Test fun separatorLinesNeverTurnTextIntoHeadingsOrRules() {
        // "---" / "===" under a line is a setext heading underline; "---" alone a thematic break.
        val md = MarkdownWriter.inline(listOf(InlineRun("Agenda\n---\nTotals\n===\n-- --\n- - -\n-5 degrees\n=x")))
        assertEquals("Agenda\n\\---\nTotals\n\\===\n\\-- --\n\\- - -\n-5 degrees\n=x", md)
        val doc = DocumentTextBuilder.build(
            "T", null,
            listOf(PageTextInput(0, texts = listOf(TextItem("t", 0f, 0f, 100f, 20f, "Shopping\n---\nmilk")))),
            emptyList(),
        )
        val out = MarkdownWriter.write(doc)
        assertFalse(out, out.lines().any { it.trim() == "---" || it.trim() == "===" })
        assertTrue(out, out.contains("Shopping\\\n\\---\\\nmilk"))
    }

    @Test fun trailingHashesInHeadingsAreKept() {
        val doc = ExportDocument(
            "Issue #",
            listOf(ExportSection("Week #", listOf(ExportBlock.Heading(1, runsOf("Ticket ##")), ExportBlock.Heading(2, runsOf("C#"))))),
        )
        val md = MarkdownWriter.write(doc)
        assertTrue(md, md.startsWith("# Issue \\#\n"))
        assertTrue(md, md.contains("\n## Week \\#\n"))
        assertTrue(md, md.contains("\n## Ticket \\##\n"))
        assertTrue(md, md.contains("\n### C#\n"))
    }
}
