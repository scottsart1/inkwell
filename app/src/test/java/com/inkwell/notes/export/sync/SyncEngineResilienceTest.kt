package com.inkwell.notes.export.sync

import com.inkwell.notes.core.model.DestinationPrefs
import com.inkwell.notes.core.model.NotesExportFormat
import com.inkwell.notes.core.model.OutputTarget
import com.inkwell.notes.core.model.TranscriptSegment
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Deletion behaviour: local deletes never reach the copies in the folder or in Google Drive. */
class SyncEngineResilienceTest {
    private val tmp: File = Files.createTempDirectory("sync-resilience").toFile()
    private val cloud = FakeDrive()
    private val local = File(tmp, "local").apply { mkdirs() }
    private val d = doc(folderId = "f2")

    private val prefs = DestinationPrefs(
        notes = setOf(OutputTarget.DRIVE, OutputTarget.FOLDER),
        transcripts = setOf(OutputTarget.FOLDER, OutputTarget.DRIVE),
        notesFormat = NotesExportFormat.MARKDOWN,
        driveAccount = "me@example.com",
        folderUri = "content://com.android.externalstorage.documents/tree/primary%3ASync",
    )

    @After
    fun tearDown() {
        tmp.deleteRecursively()
    }

    private fun harness() = EngineHarness(tmp, prefs, cloud, folderRoot = local).apply {
        source.docs[d.id] = d
        source.folders["f1"] = "Economics" to null
        source.folders["f2"] = "Term 1" to "f1"
        source.markdowns[d.id] = "# Financial Markets"
    }

    @Test
    fun `trashing or deleting a document never deletes its copies`() = runBlocking {
        val h = harness()
        h.source.recordings["rec-1"] = recording(title = "Lecture 3")
        h.source.transcripts["rec-1"] = StoredTranscript(listOf(TranscriptSegment(0, 1000, "Hello", null)), "on-device")
        h.engine.enqueue(d.id, SyncKind.NOTES, prefs.notes)
        h.engine.enqueue(d.id, SyncKind.TRANSCRIPT, prefs.transcripts, "rec-1")
        h.engine.run()
        val dir = File(local, "Inkwell/Economics/Term 1/${d.title}")
        val inDrive = listOf(
            "Inkwell/Economics/Term 1/${d.title}/notes.md",
            "Inkwell/Economics/Term 1/${d.title}/transcript_Lecture 3",
        )
        assertEquals(listOf("notes.md", "transcript_Lecture 3.md"), dir.list()!!.sorted())
        assertEquals(inDrive, cloud.files())

        // Trashed: queued work is skipped quietly, nothing is removed anywhere.
        h.source.docs[d.id] = d.copy(trashedAt = h.now)
        h.engine.enqueue(d.id, SyncKind.NOTES, prefs.notes)
        h.engine.enqueue(d.id, SyncKind.TRANSCRIPT, prefs.transcripts, "rec-1")
        val callsBefore = cloud.calls.size
        h.engine.run()
        assertEquals(listOf("notes.md", "transcript_Lecture 3.md"), dir.list()!!.sorted())
        assertEquals(inDrive, cloud.files())
        assertEquals("a trashed document is not touched in Drive", callsBefore, cloud.calls.size)

        // Gone for good: likewise.
        h.source.docs.remove(d.id)
        h.engine.enqueue(d.id, SyncKind.NOTES, prefs.notes)
        h.engine.run()
        assertEquals(listOf("notes.md", "transcript_Lecture 3.md"), dir.list()!!.sorted())
        assertEquals(inDrive, cloud.files())
        assertTrue(h.dao.all.all { it.status == SyncItemStatus.DONE })
    }
}
