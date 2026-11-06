package com.inkwell.notes.export.sync

import com.inkwell.notes.core.model.DestinationPrefs
import com.inkwell.notes.core.model.OutputTarget
import com.inkwell.notes.core.model.TranscriptSegment
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** A document moved in the library (or below a renamed folder): [SyncEngine.enqueueMoved]. */
class SyncEngineMoveTest {
    private val tmp: File = Files.createTempDirectory("sync-move").toFile()
    private val cloud = FakeDrive()
    private val drive = File(tmp, "local").apply { mkdirs() }
    private val d = doc(folderId = "f2")
    private val folderUri = "content://com.android.externalstorage.documents/tree/primary%3ASync"
    private val account = "me@example.com"

    @After
    fun tearDown() {
        tmp.deleteRecursively()
    }

    private fun harness(prefs: DestinationPrefs) =
        EngineHarness(tmp, prefs, cloud, folderRoot = drive).apply {
            source.docs[d.id] = d
            source.folders["f1"] = "Economics" to null
            source.folders["f2"] = "Term 1" to "f1"
            source.markdowns[d.id] = "# Financial Markets\n\nYield curve notes"
            source.recordings["rec-1"] = recording(title = "Lecture 3")
            source.transcripts["rec-1"] = StoredTranscript(listOf(TranscriptSegment(0, 1000, "Hello class", null)), "on-device")
        }

    private val oldDir get() = File(drive, "Inkwell/Economics/Term 1/${d.title}")
    private val newDir get() = File(drive, "Inkwell/Economics/${d.title}")

    private fun EngineHarness.move() {
        source.docs[d.id] = d.copy(folderId = "f1")
    }

    // ------------------------------------------------------------------ folder

    @Test
    fun `a move takes transcripts and audio along when notes do not go to the folder`() = runBlocking {
        val prefs = DestinationPrefs(transcripts = setOf(OutputTarget.FOLDER), audio = setOf(OutputTarget.FOLDER), folderUri = folderUri)
        val h = harness(prefs)
        h.engine.enqueue(d.id, SyncKind.TRANSCRIPT, prefs.transcripts, "rec-1")
        h.engine.enqueue(d.id, SyncKind.AUDIO, prefs.audio, "rec-1")
        h.engine.run()
        assertEquals(listOf("audio_Lecture 3.m4a", "transcript_Lecture 3.md"), oldDir.list()!!.sorted())

        h.move()
        assertEquals(1, h.engine.enqueueMoved(d.id, prefs))
        h.engine.run()

        assertEquals(listOf("audio_Lecture 3.m4a", "transcript_Lecture 3.md"), newDir.list()!!.sorted())
        assertFalse("the old copies are gone", oldDir.exists())
        assertTrue(h.dao.pendingRows().isEmpty())
        assertEquals(0, h.source.pdfExports)
    }

    @Test
    fun `a move with only audio in the folder moves the audio`() = runBlocking {
        val prefs = DestinationPrefs(audio = setOf(OutputTarget.FOLDER), folderUri = folderUri)
        val h = harness(prefs)
        h.engine.enqueue(d.id, SyncKind.AUDIO, prefs.audio, "rec-1")
        h.engine.run()

        h.move()
        h.engine.enqueueMoved(d.id, prefs)
        assertEquals(listOf(SyncKind.AUDIO.name to "rec-1"), h.dao.pendingRows().map { it.kind to it.refId })
        h.engine.run()
        assertEquals(listOf("audio_Lecture 3.m4a"), newDir.list()!!.sorted())
        assertFalse(oldDir.exists())
    }

    @Test
    fun `a move skips a first transcript that has no text and moves the copies with one that does`() = runBlocking {
        val prefs = DestinationPrefs(transcripts = setOf(OutputTarget.FOLDER), audio = setOf(OutputTarget.FOLDER), folderUri = folderUri)
        val h = harness(prefs)
        // The first recording was silent: its transcription finished with no segments.
        val lecture = h.source.recordings.getValue("rec-1")
        h.source.recordings.clear()
        h.source.recordings["rec-0"] = recording(id = "rec-0", title = "Silence", startedAt = lecture.startedAt - 100_000)
        h.source.recordings["rec-1"] = lecture
        h.source.transcripts["rec-0"] = StoredTranscript(emptyList(), "on-device")
        h.engine.enqueue(d.id, SyncKind.TRANSCRIPT, prefs.transcripts, "rec-1")
        h.engine.enqueue(d.id, SyncKind.AUDIO, prefs.audio, "rec-0")
        h.engine.enqueue(d.id, SyncKind.AUDIO, prefs.audio, "rec-1")
        h.engine.run()
        val copies = listOf("audio_Lecture 3.m4a", "audio_Silence.m4a", "transcript_Lecture 3.md")
        assertEquals(copies, oldDir.list()!!.sorted())

        h.move()
        assertEquals(1, h.engine.enqueueMoved(d.id, prefs))
        assertEquals(listOf(SyncKind.TRANSCRIPT.name to "rec-1"), h.dao.pendingRows().map { it.kind to it.refId })
        h.engine.run()

        assertEquals(copies, newDir.list()!!.sorted())
        assertFalse("the old copies are gone", oldDir.exists())
        assertTrue(h.dao.pendingRows().isEmpty())
    }

    @Test
    fun `a move copies the notes again when they go to the folder`() = runBlocking {
        val prefs = DestinationPrefs(notes = setOf(OutputTarget.FOLDER), transcripts = setOf(OutputTarget.FOLDER), folderUri = folderUri)
        val h = harness(prefs)
        h.engine.enqueue(d.id, SyncKind.NOTES, prefs.notes)
        h.engine.enqueue(d.id, SyncKind.TRANSCRIPT, prefs.transcripts, "rec-1")
        h.engine.run()

        h.move()
        h.engine.enqueueMoved(d.id, prefs)
        assertEquals(listOf(SyncKind.NOTES.name to OutputTarget.FOLDER.name), h.dao.pendingRows().map { it.kind to it.target })
        h.engine.run()
        assertEquals(listOf("notes.md", "notes.pdf", "transcript_Lecture 3.md"), newDir.list()!!.sorted())
        assertFalse(oldDir.exists())
    }

    @Test
    fun `nothing is queued for a folder that holds no copies of the document`() = runBlocking {
        val prefs = DestinationPrefs(notes = setOf(OutputTarget.FOLDER), transcripts = setOf(OutputTarget.FOLDER), folderUri = folderUri)
        val h = harness(prefs)
        h.move()
        assertEquals(0, h.engine.enqueueMoved(d.id, prefs))
        assertTrue(h.dao.all.isEmpty())
    }

    // ------------------------------------------------------------------ Google Drive

    @Test
    fun `a move moves the Drive folder without uploading anything again`() = runBlocking {
        val prefs = DestinationPrefs(notes = setOf(OutputTarget.DRIVE), transcripts = setOf(OutputTarget.DRIVE), driveAccount = account)
        val h = harness(prefs)
        h.engine.enqueue(d.id, SyncKind.NOTES, prefs.notes)
        h.engine.enqueue(d.id, SyncKind.TRANSCRIPT, prefs.transcripts, "rec-1")
        h.engine.run()
        val folderId = cloud.node("Inkwell/Economics/Term 1/${d.title}")!!.id
        val exports = h.source.pdfExports
        val uploads = cloud.calls.count { it.startsWith("upload:") }

        h.move()
        assertEquals(1, h.engine.enqueueMoved(d.id, prefs))
        val report = h.engine.run()

        assertEquals(
            listOf("Inkwell/Economics/${d.title}/notes.md", "Inkwell/Economics/${d.title}/notes.pdf", "Inkwell/Economics/${d.title}/transcript_Lecture 3"),
            cloud.files(),
        )
        assertEquals(folderId, cloud.node("Inkwell/Economics/${d.title}")!!.id)
        assertEquals("nothing exported again", exports, h.source.pdfExports)
        assertEquals("nothing uploaded again", uploads, cloud.calls.count { it.startsWith("upload:") })
        assertEquals(1, report.succeeded)
        assertTrue(h.dao.pendingRows().isEmpty())
    }

    @Test
    fun `a move never creates a document Drive does not have`() = runBlocking {
        val prefs = DestinationPrefs(notes = setOf(OutputTarget.DRIVE), driveAccount = account)
        val h = harness(prefs)
        h.move()
        assertEquals("nothing in Drive yet: nothing to move", 0, h.engine.enqueueMoved(d.id, prefs))
        h.state.setDriveDocFolder(d.id, "gone")
        h.engine.enqueueMoved(d.id, prefs)
        h.state.setDriveDocFolder(d.id, null)
        h.engine.run()
        assertTrue(cloud.writes().isEmpty())
        assertTrue(h.dao.pendingRows().isEmpty())
    }

    @Test
    fun `with both destinations only the folder gets the notes again`() = runBlocking {
        val prefs = DestinationPrefs(notes = setOf(OutputTarget.DRIVE, OutputTarget.FOLDER), driveAccount = account, folderUri = folderUri)
        val h = harness(prefs)
        h.state.setFolderLocation(folderUri, d.id, listOf("Inkwell", "Economics", "Term 1", d.title))
        h.state.setDriveDocFolder(d.id, "drive-folder")
        h.move()
        h.engine.enqueueMoved(d.id, prefs)
        assertEquals(
            setOf(
                Triple(SyncKind.NOTES.name, OutputTarget.FOLDER.name, null),
                Triple(SyncKind.NOTES.name, OutputTarget.DRIVE.name, SyncEngine.DRIVE_MOVE_REF),
            ),
            h.dao.pendingRows().map { Triple(it.kind, it.target, it.refId) }.toSet(),
        )
    }

    @Test
    fun `a Drive move is dropped once nothing goes to Drive`() = runBlocking {
        val prefs = DestinationPrefs(notes = setOf(OutputTarget.DRIVE), transcripts = setOf(OutputTarget.DRIVE), driveAccount = account)
        val h = harness(prefs)
        h.state.setDriveDocFolder(d.id, "drive-folder")
        assertEquals(1, h.engine.enqueueMoved(d.id, prefs))
        h.prefs = prefs.copy(notes = emptySet(), transcripts = emptySet())
        h.engine.run()
        assertTrue(cloud.calls.isEmpty())
        assertTrue(h.dao.pendingRows().isEmpty())
    }

    @Test
    fun `Drive moves go to Drive while anything does`() {
        val none = DestinationPrefs()
        assertEquals(emptySet<OutputTarget>(), none.driveMoveTargets())
        assertEquals(setOf(OutputTarget.DRIVE), none.copy(notes = setOf(OutputTarget.DRIVE)).driveMoveTargets())
        assertEquals(setOf(OutputTarget.DRIVE), none.copy(audio = setOf(OutputTarget.DRIVE)).driveMoveTargets())
        assertEquals(emptySet<OutputTarget>(), none.copy(notes = setOf(OutputTarget.FOLDER)).driveMoveTargets())
        assertEquals(
            setOf(OutputTarget.DRIVE),
            SyncEngine.targetsFor(SyncKind.NOTES, SyncEngine.DRIVE_MOVE_REF, none.copy(transcripts = setOf(OutputTarget.DRIVE))),
        )
        assertEquals(emptySet<OutputTarget>(), SyncEngine.targetsFor(SyncKind.NOTES, null, none.copy(transcripts = setOf(OutputTarget.DRIVE))))
    }
}
