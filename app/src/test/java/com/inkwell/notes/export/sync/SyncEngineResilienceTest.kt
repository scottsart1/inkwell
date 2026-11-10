package com.inkwell.notes.export.sync

import com.inkwell.notes.core.model.DestinationPrefs
import com.inkwell.notes.core.model.NotesExportFormat
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

/**
 * Lockout and credential behaviour against the vault double, and deletion behaviour: local deletes
 * never reach the copies in the folder, in Google Drive or in the vault.
 */
class SyncEngineResilienceTest {
    private val tmp: File = Files.createTempDirectory("sync-resilience").toFile()
    private val cloud = FakeDrive()
    private val vault = FakeVault()
    private val local = File(tmp, "local").apply { mkdirs() }
    private val d = doc(folderId = "f2")

    private val prefs = DestinationPrefs(
        notes = setOf(OutputTarget.DRIVE, OutputTarget.FOLDER, OutputTarget.VAULT),
        transcripts = setOf(OutputTarget.FOLDER, OutputTarget.DRIVE, OutputTarget.VAULT),
        notesFormat = NotesExportFormat.MARKDOWN,
        driveAccount = "me@example.com",
        folderUri = "content://com.android.externalstorage.documents/tree/primary%3ASync",
        vaultUrl = "https://yourpitbox.com/notes/Lk7xQ2pLmn0",
    )
    private val vaultPrefs = DestinationPrefs(
        notes = setOf(OutputTarget.VAULT),
        vaultUrl = "https://yourpitbox.com/notes/Lk7xQ2pLmn0",
        notesFormat = NotesExportFormat.MARKDOWN,
    )

    @After
    fun tearDown() {
        vault.close()
        tmp.deleteRecursively()
    }

    private fun harness(prefs: DestinationPrefs = this.prefs, creds: MemoryCredentials = MemoryCredentials()) =
        EngineHarness(tmp, prefs, cloud, folderRoot = local, vault = vault, credentials = creds).apply {
            source.docs[d.id] = d
            source.folders["f1"] = "Economics" to null
            source.folders["f2"] = "Term 1" to "f1"
            source.markdowns[d.id] = "# Financial Markets"
        }

    private fun loginAttempts() = vault.calls().count { it == "POST /api/session" }

    @Test
    fun `a wrong stored password is tried once, not on every run, so the app never locks the user out`() = runBlocking {
        val h = harness(vaultPrefs, creds = MemoryCredentials(pw = "wrong"))
        // Thirty minutes of note taking: an edit settles every 30 s and each one kicks a run.
        repeat(60) { i ->
            h.now += 30_000
            h.engine.enqueue(d.id, SyncKind.NOTES, h.prefs.notes)
            val report = h.engine.run()
            assertTrue("run $i still reports the wrong password", report.error!!.contains("password is wrong"))
            assertFalse(report.retryLater)
        }
        // The vault locks an address after 8 failures in 15 minutes; the app must stay well below that.
        assertTrue("login attempts in 30 min: ${loginAttempts()}", loginAttempts() <= 3)
        assertTrue(h.dao.all.all { it.status == SyncItemStatus.PENDING })
        assertEquals("the hold is remembered across runs", true, h.state.vaultCredentialsRefusedAt > 0)
    }

    @Test
    fun `fixing the password releases the hold at once`() = runBlocking {
        val creds = MemoryCredentials(pw = "wrong")
        val h = harness(vaultPrefs, creds = creds)
        h.engine.enqueue(d.id, SyncKind.NOTES, h.prefs.notes)
        h.engine.run()
        assertEquals(1, loginAttempts())

        creds.pw = "correct horse"
        h.state.vaultCredentialsRefusedAt = 0 // what SyncManagerImpl.onVaultCredentialsChanged does
        h.engine.run()
        assertEquals(2, loginAttempts())
        assertEquals(SyncItemStatus.DONE, h.dao.all.single().status)
        assertEquals(0L, h.state.vaultCredentialsRefusedAt)
    }

    @Test
    fun `the hold expires on its own after the lockout window`() = runBlocking {
        val h = harness(vaultPrefs, creds = MemoryCredentials(pw = "wrong"))
        h.engine.enqueue(d.id, SyncKind.NOTES, h.prefs.notes)
        h.engine.run()
        h.now += SyncEngine.CREDENTIALS_HOLD_MS - 1
        h.engine.run()
        assertEquals(1, loginAttempts())
        h.now += 2
        h.engine.run()
        assertEquals("one more attempt once the window passed", 2, loginAttempts())
    }

    @Test
    fun `a server lockout for the right password does not start a credentials hold`() = runBlocking {
        vault.lockedSeconds = 120
        val h = harness(vaultPrefs)
        h.engine.enqueue(d.id, SyncKind.NOTES, h.prefs.notes)
        val report = h.engine.run()
        assertEquals(h.now + 120_000, report.vaultLockedUntil)
        assertEquals(0L, h.state.vaultCredentialsRefusedAt)
        vault.lockedSeconds = null
        h.now += 121_000
        h.engine.run()
        assertEquals(SyncItemStatus.DONE, h.dao.all.single().status)
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
        assertTrue(d.id in vault.docIds)
        val vaultRequestsBefore = vault.requests.size

        // Trashed: queued work is skipped quietly, nothing is removed anywhere.
        h.source.docs[d.id] = d.copy(trashedAt = h.now)
        h.engine.enqueue(d.id, SyncKind.NOTES, prefs.notes)
        h.engine.enqueue(d.id, SyncKind.TRANSCRIPT, prefs.transcripts, "rec-1")
        val callsBefore = cloud.calls.size
        h.engine.run()
        assertEquals(listOf("notes.md", "transcript_Lecture 3.md"), dir.list()!!.sorted())
        assertEquals(inDrive, cloud.files())
        assertEquals("a trashed document is not touched in Drive", callsBefore, cloud.calls.size)
        assertEquals("a trashed document is not touched in the vault", vaultRequestsBefore, vault.requests.size)

        // Gone for good: likewise.
        h.source.docs.remove(d.id)
        h.engine.enqueue(d.id, SyncKind.NOTES, prefs.notes)
        h.engine.run()
        assertEquals(listOf("notes.md", "transcript_Lecture 3.md"), dir.list()!!.sorted())
        assertEquals(inDrive, cloud.files())
        assertTrue(vault.requests.none { it.method == "DELETE" })
        assertTrue(d.id in vault.docIds)
        assertTrue(h.dao.all.all { it.status == SyncItemStatus.DONE })
    }
}
