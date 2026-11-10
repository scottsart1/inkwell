package com.inkwell.notes.export

import com.inkwell.notes.core.model.Recording
import com.inkwell.notes.core.model.RecordingStatus
import com.inkwell.notes.core.model.TranscriptSegment
import com.inkwell.notes.export.backup.PackageManifest
import com.inkwell.notes.export.backup.PackagedRecording
import com.inkwell.notes.export.backup.PackagedTranscripts
import com.inkwell.notes.export.format.DocumentTextBuilder
import com.inkwell.notes.export.format.DocxImage
import com.inkwell.notes.export.format.DocxImageSource
import com.inkwell.notes.export.format.DocxWriter
import com.inkwell.notes.export.format.TranscriptInput
import com.inkwell.notes.export.sync.SyncPayloads
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.TimeZone
import java.util.zip.ZipInputStream

/** Speaker names travel with transcripts everywhere a transcript goes; voiceprints never do. */
class SpeakerNamesExportTest {
    private val recording = Recording(
        id = "rec-1", documentId = "doc-1", title = "Planning", segmentFiles = listOf("audio/rec-1/seg_000.m4a"),
        segmentDurationsMs = listOf(60_000), durationMs = 60_000, startedAt = 0, endedAt = 60_000, status = RecordingStatus.COMPLETE,
    )
    private val segments = listOf(
        TranscriptSegment(1_000, 4_000, "Let's start.", "Priya", "S1"),
        TranscriptSegment(4_500, 8_000, "Agreed.", "Speaker 2", "S2"),
        TranscriptSegment(9_000, 12_000, "Next item.", "Priya", "S1"),
    )

    @Test fun packagesRoundTripSpeakerNamesAndVoiceLabels() {
        val packaged = PackagedRecording(recording, audioIncluded = false, transcript = segments, transcriptSource = "openai:gpt-4o-transcribe-diarize")
        val json = PackageManifest.json.encodeToString(PackagedRecording.serializer(), packaged)
        assertTrue(json.contains("\"speakerLabel\":\"S1\""))
        val back = PackageManifest.json.decodeFromString(PackagedRecording.serializer(), json)
        assertEquals(segments, back.transcript)

        val rows = PackagedTranscripts.entities("rec-new", back.transcript, back.transcriptSource)
        assertEquals(listOf("Priya", "Speaker 2", "Priya"), rows.map { it.speaker })
        assertEquals(listOf("S1", "S2", "S1"), rows.map { it.speakerLabel })
        assertTrue(rows.all { it.recordingId == "rec-new" && it.source == "openai:gpt-4o-transcribe-diarize" })
        assertEquals(segments, rows.map { it.toModel() })
    }

    @Test fun olderPackagesWithoutLabelsStillImport() {
        val old = """{"recording":${PackageManifest.json.encodeToString(Recording.serializer(), recording)},"audioIncluded":false,""" +
            """"transcript":[{"startMs":0,"endMs":900,"text":"Hello","speaker":"A"},{"startMs":900,"endMs":1000,"text":"x","speaker":" "}]}"""
        val back = PackageManifest.json.decodeFromString(PackagedRecording.serializer(), old)
        val rows = PackagedTranscripts.entities("r", back.transcript, back.transcriptSource)
        assertEquals("import", rows.first().source)
        assertEquals(listOf("A", null), rows.map { it.speaker })
        assertTrue(rows.all { it.speakerLabel == null })
    }

    @Test fun docxLabelsEachBlockWithItsSpeaker() = runTest {
        val doc = DocumentTextBuilder.build("Notes", typed = null, pages = emptyList(), transcripts = listOf(TranscriptInput("Planning", segments)))
        val images = object : DocxImageSource {
            override suspend fun asset(assetId: String): DocxImage? = null
            override suspend fun page(pageIndex: Int): DocxImage? = null
        }
        val out = ByteArrayOutputStream()
        DocxWriter(images).write(doc, out)
        var xml = ""
        ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (e.name == "word/document.xml") xml = zip.readBytes().decodeToString()
            }
        }
        assertEquals(2, Regex("Priya: </w:t>").findAll(xml).count())
        assertTrue(xml.contains("Speaker 2: </w:t>"))
        assertFalse(xml.contains("S1"))
    }

    @Test fun folderDriveAndVaultTranscriptsCarryNamesNotLabels() {
        val md = SyncPayloads.transcriptMarkdown(recording, segments, "Notes", TimeZone.getTimeZone("UTC"))
        assertTrue(md, md.contains("**[0:01]** **Priya:** Let's start."))
        assertTrue(md, md.contains("**[0:04]** **Speaker 2:** Agreed."))
        assertTrue(md, md.contains("**[0:09]** **Priya:** Next item."))
        val html = SyncPayloads.transcriptHtml(recording, segments, "Notes", TimeZone.getTimeZone("UTC"))
        assertTrue(html, html.contains("<b>Priya:</b> Let&#39;s start."))
        assertTrue(html, html.contains("<b>Speaker 2:</b> Agreed."))
        assertFalse("Google Drive never sees the voice labels", html.contains("S1"))
        val upsert = SyncPayloads.transcriptUpsert(recording, segments, "openai:gpt-4o-transcribe-diarize")
        assertEquals(listOf("Priya", "Speaker 2", "Priya"), upsert.segments.map { it.speaker })
        assertNull("the vault never sees the voice labels", upsert.segments.firstOrNull { it.speaker == "S1" })
    }
}
