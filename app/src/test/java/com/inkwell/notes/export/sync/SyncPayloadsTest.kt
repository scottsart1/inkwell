package com.inkwell.notes.export.sync

import com.inkwell.notes.core.model.TranscriptSegment
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class SyncPayloadsTest {
    @Test
    fun `transcript markdown is readable and timestamped`() {
        val rec = recording(title = "Lecture 3")
        val md = SyncPayloads.transcriptMarkdown(
            rec,
            listOf(
                TranscriptSegment(0, 4_000, "Good morning.", "Prof. Lee"),
                TranscriptSegment(4_000, 9_000, "Yield curves today.", "Prof. Lee"),
                TranscriptSegment(3_725_000, 3_730_000, "Questions?", null),
            ),
            "20261015_Financial Markets",
            TimeZone.getTimeZone("UTC"),
        )
        assertTrue(md.startsWith("# Lecture 3\n\n20261015_Financial Markets · 2025-10-15 01:00 · 1:05\n"))
        assertTrue(md.contains("**[0:00]** **Prof. Lee:** Good morning."))
        assertTrue("speaker named once per run", md.contains("**[0:04]** Yield curves today."))
        assertTrue(md.contains("**[1:02:05]** Questions?"))
    }

    @Test
    fun `transcript HTML for a Google Doc is escaped, timestamped and names each speaker once per run`() {
        val rec = recording(title = "Q&A <draft>")
        val html = SyncPayloads.transcriptHtml(
            rec,
            listOf(
                TranscriptSegment(0, 4_000, "Rates < 5% & falling.", "Prof. \"Lee\""),
                TranscriptSegment(4_000, 9_000, "Line one\nline two", "Prof. \"Lee\""),
                TranscriptSegment(9_000, 9_500, "   ", "Sam"),
                TranscriptSegment(3_725_000, 3_730_000, "Questions?", null),
            ),
            "20261015_Financial Markets",
            TimeZone.getTimeZone("UTC"),
        )
        assertTrue(html, html.contains("<title>Q&amp;A &lt;draft&gt;</title>"))
        assertTrue(html, html.contains("<h1>Q&amp;A &lt;draft&gt;</h1>"))
        assertTrue(html, html.contains("<p><i>20261015_Financial Markets · 2025-10-15 01:00 · 1:05</i></p>"))
        assertTrue(html, html.contains("<p><b>[0:00]</b> <b>Prof. &quot;Lee&quot;:</b> Rates &lt; 5% &amp; falling.</p>"))
        assertTrue(html, html.contains("<p><b>[0:04]</b> Line one<br>line two</p>"))
        assertTrue(html, html.contains("<p><b>[1:02:05]</b> Questions?</p>"))
        assertFalse("blank segments are skipped", html.contains("Sam"))
        assertFalse("nothing unescaped", html.contains("<draft>"))
    }

    @Test
    fun `clock formats minutes and hours`() {
        assertTrue(SyncPayloads.clock(0) == "0:00")
        assertTrue(SyncPayloads.clock(65_000) == "1:05")
        assertTrue(SyncPayloads.clock(3_725_000) == "1:02:05")
        assertTrue(SyncPayloads.clock(-5) == "0:00")
    }
}
