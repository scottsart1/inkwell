package com.inkwell.notes.export.email

import com.inkwell.notes.core.model.TranscriptSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class WorkEmailTest {
    private val utc = TimeZone.getTimeZone("UTC")

    // 2026-03-04 09:05 UTC
    private val started = 1_772_615_100_000L

    private fun seg(startMs: Long, text: String, speaker: String? = null) = TranscriptSegment(startMs, startMs + 1000, text, speaker)

    @Test
    fun addressesAreValidatedAfterNormalizing() {
        assertTrue(WorkEmail.isAddress("name@company.com"))
        assertTrue(WorkEmail.isAddress("  first.last+notes@mail.company.co.uk "))
        assertTrue(WorkEmail.isAddress("mailto:name@company.com"))
        assertTrue(WorkEmail.isAddress("<name@company.com>"))
        assertEquals("name@company.com", WorkEmail.normalize(" mailto: <name@company.com> "))
        assertFalse(WorkEmail.isAddress(""))
        assertFalse(WorkEmail.isAddress("name"))
        assertFalse(WorkEmail.isAddress("name@company"))
        assertFalse(WorkEmail.isAddress("name @company.com"))
        assertFalse(WorkEmail.isAddress("a@b@company.com"))
    }

    @Test
    fun subjects() {
        assertEquals("20260304_Budget", WorkEmail.documentSubject("20260304_Budget"))
        assertEquals("Notes", WorkEmail.documentSubject(" "))
        assertEquals("Transcript: Standup (Team notes)", WorkEmail.recordingSubject("Standup", "Team notes"))
        assertEquals("Transcript: Standup", WorkEmail.recordingSubject("Standup", ""))
        assertEquals("Transcript: Standup", WorkEmail.recordingSubject("Standup", "Standup"))
        assertEquals("Transcript: Recording", WorkEmail.recordingSubject("", ""))
    }

    @Test
    fun transcriptBlockNamesEachSpeakerOncePerRun() {
        val t = WorkEmail.Transcript(
            "Standup",
            started,
            65 * 60_000L + 3_000,
            listOf(
                seg(0, "Morning all.", "Priya"),
                seg(4_000, "Two things today.", "Priya"),
                seg(61_000, "Sounds good.\nLet's go.", "Sam"),
                seg(62_000, "   "),
                seg(3_700_000, "Wrap up.", null),
            ),
        )
        val block = WorkEmail.transcriptBlock(t, utc)
        assertEquals(
            """
            TRANSCRIPT: Standup
            2026-03-04 09:05 · 1:05:03

            [0:00] Priya: Morning all.
            [0:04] Two things today.
            [1:01] Sam: Sounds good. Let's go.
            [1:01:40] Wrap up.

            """.trimIndent(),
            block,
        )
    }

    @Test
    fun emptyTranscriptSaysSo() {
        val block = WorkEmail.transcriptBlock(WorkEmail.Transcript("", started, 0, emptyList()), utc)
        assertTrue(block.startsWith("TRANSCRIPT: Recording\n"))
        assertTrue(block.contains("(Nothing was transcribed.)"))
    }

    @Test
    fun documentBodyHasTitleNotesThenTranscripts() {
        val body = WorkEmail.documentBody(
            docTitle = "Team notes",
            notesText = "Page 1\n\nShip on Friday.\n",
            transcripts = listOf(WorkEmail.Transcript("Standup", started, 30_000, listOf(seg(0, "Hello.", "Priya")))),
            hasPdf = true,
            timeZone = utc,
        )
        assertEquals(
            """
            Team notes

            NOTES
            Page 1

            Ship on Friday.

            TRANSCRIPT: Standup
            2026-03-04 09:05 · 0:30

            [0:00] Priya: Hello.

            """.trimIndent(),
            body,
        )
    }

    @Test
    fun drawingsOnlyPointToThePdf() {
        val withPdf = WorkEmail.documentBody("Sketch", "  \n", emptyList(), hasPdf = true, timeZone = utc)
        assertTrue(withPdf.contains("(Handwriting and drawings only: see the attached PDF.)"))
        val withoutPdf = WorkEmail.documentBody("", "", emptyList(), hasPdf = false, timeZone = utc)
        assertTrue(withoutPdf.startsWith("Notes\n"))
        assertTrue(withoutPdf.contains("(No text.)"))
    }

    @Test
    fun recordingBodyStartsWithTheNotebook() {
        val t = WorkEmail.Transcript("Standup", started, 1_000, listOf(seg(0, "Hi.")))
        assertTrue(WorkEmail.recordingBody("Team notes", t, utc).startsWith("Team notes\n\nTRANSCRIPT: Standup\n"))
        assertTrue(WorkEmail.recordingBody("", t, utc).startsWith("TRANSCRIPT: Standup\n"))
    }

    @Test
    fun longBodiesAreCutAtALineWithANote() {
        val lines = (1..50).joinToString("\n") { "Line $it of the meeting" }
        val fitted = WorkEmail.fit(lines, max = 200)
        assertTrue(fitted.length <= 200)
        assertTrue(fitted.endsWith(WorkEmail.CUT_NOTE))
        val kept = fitted.removeSuffix(WorkEmail.CUT_NOTE)
        assertTrue(kept.lines().all { it.startsWith("Line ") && it.endsWith("of the meeting") })
        assertTrue(lines.startsWith(kept))
    }

    @Test
    fun shortBodiesAreKeptWhole() {
        assertEquals("Hello\n", WorkEmail.fit("Hello\n\n\n"))
    }

    @Test
    fun cutNeverSplitsASurrogatePair() {
        val text = "😀".repeat(200) // one long line of emoji, no breaks
        val fitted = WorkEmail.fit(text, max = 101)
        val kept = fitted.removeSuffix(WorkEmail.CUT_NOTE)
        assertFalse(Character.isHighSurrogate(kept.last()))
        assertTrue(fitted.length <= 101)
    }

    @Test
    fun plainExportLosesOnlyItsTitleLine() {
        assertEquals("Page 1\n\nShip it.", WorkEmail.withoutTitleLine("Team notes\n\nPage 1\n\nShip it.\n"))
        assertEquals("Body", WorkEmail.withoutTitleLine("﻿Title\r\nBody\r\n"))
        assertEquals("", WorkEmail.withoutTitleLine("Only a title\n"))
        assertEquals("", WorkEmail.withoutTitleLine("Only a title"))
    }

    @Test
    fun clock() {
        assertEquals("0:00", WorkEmail.clock(-5))
        assertEquals("0:59", WorkEmail.clock(59_999))
        assertEquals("12:05", WorkEmail.clock(725_000))
        assertEquals("2:00:01", WorkEmail.clock(7_201_000))
    }
}
