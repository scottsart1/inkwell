package com.inkwell.notes.export.sync

import androidx.work.ExistingWorkPolicy
import androidx.work.WorkInfo
import com.inkwell.notes.core.model.DestinationPrefs
import com.inkwell.notes.core.model.NotesExportFormat
import com.inkwell.notes.core.model.OutputTarget
import com.inkwell.notes.core.model.TranscriptSegment
import com.inkwell.notes.core.storage.db.SyncQueueEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Settings changing while a run is under way, folder ownership, queue paging and scheduling. */
class SyncEngineChangesDuringRunTest {
    private val tmp: File = Files.createTempDirectory("sync-changes").toFile()
    private val cloud = FakeDrive()
    private val drive = File(tmp, "local").apply { mkdirs() }
    private val d = doc(folderId = "f2")

    private val folderPrefs = DestinationPrefs(
        notes = setOf(OutputTarget.FOLDER),
        folderUri = "content://com.android.externalstorage.documents/tree/primary%3ASync",
        folderLabel = "Sync",
    )

    @After
    fun tearDown() {
        tmp.deleteRecursively()
    }

    private fun harness(prefs: DestinationPrefs, endpoint: VaultEndpoint? = null) =
        EngineHarness(tmp, prefs, cloud, folderRoot = drive, endpoint = endpoint).apply {
            source.docs[d.id] = d
            source.folders["f1"] = "Economics" to null
            source.folders["f2"] = "Term 1" to "f1"
        }

    private fun docDir(title: String = d.title) = File(drive, "Inkwell/Economics/Term 1/$title")

    // ------------------------------------------------------------------ settings changed mid-run

    @Test
    fun `a destination switched on during a run gets its items copied, not dropped`() = runBlocking {
        val h = harness(folderPrefs)
        h.source.recordings["rec-1"] = recording(title = "Lecture 3")
        h.source.transcripts["rec-1"] = StoredTranscript(listOf(TranscriptSegment(0, 1000, "Hello class", null)), "on-device")
        h.engine.enqueue(d.id, SyncKind.NOTES, setOf(OutputTarget.FOLDER))
        h.engine.enqueue(d.id, SyncKind.TRANSCRIPT, setOf(OutputTarget.FOLDER), "rec-1")
        // The user switches transcripts to the folder while the notes are being copied.
        h.prefs = h.prefs.copy(transcripts = emptySet())
        h.source.onExport = { h.prefs = h.prefs.copy(transcripts = setOf(OutputTarget.FOLDER)) }

        h.engine.run()
        assertTrue(File(docDir(), "transcript_Lecture 3.md").readText().contains("Hello class"))
        assertTrue(h.dao.all.all { it.status == SyncItemStatus.DONE })
    }

    @Test
    fun `losing the old folder after another was chosen keeps the new folder and its items`() = runBlocking {
        val h = harness(folderPrefs)
        val other = doc(id = "11111111-2222-3333-4444-555555555555", title = "Other", folderId = "f2")
        h.source.docs[other.id] = other
        val newUri = "content://com.android.externalstorage.documents/tree/primary%3ANew"
        h.folderAccess = FolderAccess { uri ->
            if (uri == folderPrefs.folderUri) {
                // Another folder was picked and the old one's permission released during this run.
                h.prefs = h.prefs.copy(folderUri = newUri, folderLabel = "New")
                throw FolderRevokedException()
            }
            JavaFileNode(drive)
        }
        h.engine.enqueue(d.id, SyncKind.NOTES, h.prefs.notes)
        h.engine.enqueue(other.id, SyncKind.NOTES, h.prefs.notes)
        val report = h.engine.run()

        assertEquals(0, h.revoked)
        assertEquals(newUri, h.prefs.folderUri)
        assertTrue(OutputTarget.FOLDER in h.prefs.notes)
        assertTrue(h.dao.all.none { it.status == SyncItemStatus.FAILED })
        assertEquals(SyncItemStatus.PENDING, h.dao.all.single { it.documentId == d.id }.status)
        assertNull(report.error)
        assertTrue("the next item goes to the new folder", File(docDir("Other"), "notes.pdf").isFile)
    }

    // ------------------------------------------------------------------ folder ownership

    @Test
    fun `a new document with a deleted document's title gets its own directory`() = runBlocking {
        val h = harness(folderPrefs)
        h.engine.enqueue(d.id, SyncKind.NOTES, h.prefs.notes)
        h.engine.run()
        // Deleted for good: its folder copy is the last one and stays, and so does its location.
        h.source.docs.remove(d.id)
        val again = doc(id = "7c1d9e0f-1111-2222-3333-444455556666", title = d.title, folderId = "f2")
        h.source.docs[again.id] = again
        h.engine.enqueue(again.id, SyncKind.NOTES, h.prefs.notes)
        h.engine.run()

        assertTrue(File(docDir(), "notes.pdf").readBytes().contentEquals(FakeSource.pdfBytes(d.id)))
        assertTrue(File(docDir("${d.title} (7c1d9e)"), "notes.pdf").readBytes().contentEquals(FakeSource.pdfBytes(again.id)))
    }

    @Test
    fun `a renamed document takes its own directory along, not the one now using its old title`() = runBlocking {
        val h = harness(folderPrefs)
        h.engine.enqueue(d.id, SyncKind.NOTES, h.prefs.notes)
        h.engine.run()
        // d is renamed and another document takes its old title; the other one is copied first.
        h.source.docs[d.id] = d.copy(title = "Bio 101")
        val taker = doc(id = "0a000000-1111-2222-3333-444455556666", title = d.title, folderId = "f2")
        h.source.docs[taker.id] = taker
        h.engine.enqueue(taker.id, SyncKind.NOTES, h.prefs.notes)
        h.engine.enqueue(d.id, SyncKind.NOTES, h.prefs.notes)
        h.engine.run()

        assertTrue(File(docDir("Bio 101"), "notes.pdf").readBytes().contentEquals(FakeSource.pdfBytes(d.id)))
        assertTrue(File(docDir("${d.title} (0a0000)"), "notes.pdf").readBytes().contentEquals(FakeSource.pdfBytes(taker.id)))
        assertFalse(docDir().exists())
    }

    @Test
    fun `a directory whose copies were removed by hand is free for the next document`() = runBlocking {
        val h = harness(folderPrefs)
        h.engine.enqueue(d.id, SyncKind.NOTES, h.prefs.notes)
        h.engine.run()
        h.source.docs.remove(d.id)
        docDir().deleteRecursively()
        val again = doc(id = "7c1d9e0f-1111-2222-3333-444455556666", title = d.title, folderId = "f2")
        h.source.docs[again.id] = again
        h.engine.enqueue(again.id, SyncKind.NOTES, h.prefs.notes)
        h.engine.run()

        assertTrue(File(docDir(), "notes.pdf").readBytes().contentEquals(FakeSource.pdfBytes(again.id)))
        assertNull(h.state.folderLocation(folderPrefs.folderUri!!, d.id))
    }

    @Test
    fun `a deleted document's last copies stay protected after using another folder and coming back`() = runBlocking {
        val otherDrive = File(tmp, "other").apply { mkdirs() }
        val otherUri = "content://com.android.externalstorage.documents/tree/primary%3AOther"
        val h = harness(folderPrefs)
        h.folderAccess = FolderAccess { uri -> JavaFileNode(if (uri == otherUri) otherDrive else drive) }
        h.engine.enqueue(d.id, SyncKind.NOTES, h.prefs.notes)
        h.engine.run()
        // d is deleted: its copy in the first folder is now the only one.
        h.source.docs.remove(d.id)

        // Another folder for a while (or the first one's permission was lost), where a new
        // document with the same title is copied.
        h.prefs = folderPrefs.copy(folderUri = otherUri)
        val elsewhere = doc(id = "5e5e5e5e-1111-2222-3333-444455556666", title = d.title, folderId = "f2")
        h.source.docs[elsewhere.id] = elsewhere
        h.engine.enqueue(elsewhere.id, SyncKind.NOTES, h.prefs.notes)
        h.engine.run()
        assertTrue(File(otherDrive, "Inkwell/Economics/Term 1/${d.title}/notes.pdf").readBytes().contentEquals(FakeSource.pdfBytes(elsewhere.id)))
        h.source.docs.remove(elsewhere.id)

        // The first folder is chosen again and another new document takes the title.
        h.prefs = folderPrefs
        val again = doc(id = "7c1d9e0f-1111-2222-3333-444455556666", title = d.title, folderId = "f2")
        h.source.docs[again.id] = again
        h.engine.enqueue(again.id, SyncKind.NOTES, h.prefs.notes)
        h.engine.run()

        assertTrue("the deleted document's copy is untouched", File(docDir(), "notes.pdf").readBytes().contentEquals(FakeSource.pdfBytes(d.id)))
        assertTrue(File(docDir("${d.title} (7c1d9e)"), "notes.pdf").readBytes().contentEquals(FakeSource.pdfBytes(again.id)))
        assertEquals(listOf("Inkwell", "Economics", "Term 1", d.title), h.state.folderLocation(folderPrefs.folderUri!!, d.id))
    }

    // ------------------------------------------------------------------ queue

    @Test
    fun `items waiting at the head of a long queue do not hide the ones behind them`() = runBlocking {
        val prefs = folderPrefs.copy(notes = setOf(OutputTarget.FOLDER, OutputTarget.DRIVE), driveAccount = "me@example.com")
        val h = harness(prefs)
        h.network.online = false
        // More offline Drive items than one page of the queue, all older than the folder copy.
        // Recent times, so the run's cleanup of old finished rows leaves the folder row alone.
        val start = h.now - 20_000
        h.dao.rows.value = (1..5_001).map { i ->
            SyncQueueEntity(
                rowId = i.toLong(), documentId = "doc-$i", kind = SyncKind.NOTES.name, target = OutputTarget.DRIVE.name,
                refId = null, enqueuedAt = start + i, attempts = 0, lastError = null, status = SyncItemStatus.PENDING,
            )
        }
        h.dao.rows.value = h.dao.rows.value + SyncQueueEntity(
            rowId = 10_000, documentId = d.id, kind = SyncKind.NOTES.name, target = OutputTarget.FOLDER.name,
            refId = null, enqueuedAt = start + 10_000, attempts = 0, lastError = null, status = SyncItemStatus.PENDING,
        )
        h.engine.run()

        assertTrue(File(docDir(), "notes.pdf").isFile)
        assertEquals(SyncItemStatus.DONE, h.dao.all.single { it.target == OutputTarget.FOLDER.name }.status)
        assertEquals(5_001, h.dao.pendingRows().size)
        assertEquals("one row per kind and target tells what to schedule", 1, h.dao.pendingKindsAndTargets().size)
    }

    @Test
    fun `unreachable Google Drive does not use up the item's attempts`() = runBlocking {
        val h = harness(DestinationPrefs(notes = setOf(OutputTarget.DRIVE), driveAccount = "me@example.com", notesFormat = NotesExportFormat.MARKDOWN))
        h.engine.enqueue(d.id, SyncKind.NOTES, h.prefs.notes)
        repeat(3) {
            cloud.failNext += DriveException.Unreachable(java.io.IOException("offline"))
            val report = h.engine.run()
            assertTrue(report.retryLater)
        }
        val row = h.dao.all.single()
        assertEquals(SyncItemStatus.PENDING, row.status)
        assertEquals(0, row.attempts)
    }

    @Test
    fun `an unreachable vault does not use up the item's attempts`() = runBlocking {
        val vault = FakeVault()
        val endpoint = vault.endpoint
        vault.close()
        val h = harness(DestinationPrefs(notes = setOf(OutputTarget.VAULT), vaultUrl = "https://v/notes/x", notesFormat = NotesExportFormat.MARKDOWN), endpoint)
        h.engine.enqueue(d.id, SyncKind.NOTES, h.prefs.notes)
        repeat(3) {
            val report = h.engine.run()
            assertTrue(report.retryLater)
        }
        val row = h.dao.all.single()
        assertEquals(SyncItemStatus.PENDING, row.status)
        assertEquals(0, row.attempts)
    }

    @Test
    fun `the vault switched on during a run gets its items copied, not dropped`() = runBlocking {
        val vault = FakeVault()
        try {
            val h = harness(folderPrefs, vault.endpoint)
            h.engine.enqueue(d.id, SyncKind.NOTES, setOf(OutputTarget.FOLDER))
            h.engine.enqueue(d.id, SyncKind.NOTES, setOf(OutputTarget.VAULT))
            // The vault is switched on for notes while the folder copy is being made.
            h.source.onExport = {
                h.prefs = h.prefs.copy(notes = setOf(OutputTarget.FOLDER, OutputTarget.VAULT), vaultUrl = "https://v/notes/x")
            }
            h.engine.run()
            assertTrue(h.dao.all.all { it.status == SyncItemStatus.DONE })
            assertTrue(vault.calls().contains("PUT /api/docs/${d.id}"))
        } finally {
            vault.close()
        }
    }

    // ------------------------------------------------------------------ scheduling

    @Test
    fun `new items replace a waiting run and queue one run behind a running one`() {
        assertEquals(ExistingWorkPolicy.REPLACE, SyncScheduler.policyFor(emptyList()))
        assertEquals(ExistingWorkPolicy.REPLACE, SyncScheduler.policyFor(listOf(WorkInfo.State.SUCCEEDED)))
        assertEquals("a run in retry backoff goes now", ExistingWorkPolicy.REPLACE, SyncScheduler.policyFor(listOf(WorkInfo.State.ENQUEUED)))
        assertEquals(ExistingWorkPolicy.APPEND_OR_REPLACE, SyncScheduler.policyFor(listOf(WorkInfo.State.RUNNING)))
        assertNull("one follow-up is enough", SyncScheduler.policyFor(listOf(WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED)))
        assertEquals(
            "a follow-up stuck behind a run in backoff is replaced",
            ExistingWorkPolicy.REPLACE, SyncScheduler.policyFor(listOf(WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED)),
        )
    }

    // ------------------------------------------------------------------ vault password

    @Test
    fun `the saved password only goes to the vault it was entered for`() {
        val a = VaultUrl.endpointOrNull("https://yourpitbox.com/notes/Lk7xQ2pLmn0")!!
        val b = VaultUrl.endpointOrNull("https://yourpitbox.co/notes/Lk7xQ2pLmn0")!!
        assertTrue(VaultPassword.fits(a.fingerprint, a, savedLink = b.baseUrl))
        assertFalse("a new or mistyped link never receives it", VaultPassword.fits(a.fingerprint, b, savedLink = b.baseUrl))
        assertTrue("not tied yet: the saved link", VaultPassword.fits(null, a, savedLink = a.baseUrl))
        assertFalse(VaultPassword.fits(null, b, savedLink = a.baseUrl))
        assertFalse(VaultPassword.fits(null, a, savedLink = ""))
        val www = VaultUrl.endpointOrNull("https://www.yourpitbox.com/notes/Lk7xQ2pLmn0")!!
        assertTrue("the same vault on the www host of its site", VaultPassword.fits(a.fingerprint, www, savedLink = ""))
        assertTrue(VaultPassword.fits(null, a, savedLink = www.baseUrl))
        assertFalse(VaultPassword.fits(www.fingerprint, VaultUrl.endpointOrNull("https://www.yourpitbox.com/notes/other")!!, savedLink = ""))
    }
}
