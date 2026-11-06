package com.inkwell.notes.export.sync

import com.inkwell.notes.core.model.DestinationPrefs
import com.inkwell.notes.core.model.NotesExportFormat
import com.inkwell.notes.core.model.OutputTarget
import com.inkwell.notes.core.model.RecordingStatus
import com.inkwell.notes.core.model.TranscriptSegment
import com.inkwell.notes.core.model.TranscriptStatus
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

class SyncEngineTest {
    private val tmp: File = Files.createTempDirectory("sync-engine").toFile()
    private val cloud = FakeDrive()
    private val local = File(tmp, "local").apply { mkdirs() }
    private val d = doc(folderId = "f2")

    private val drivePrefs = DestinationPrefs(
        notes = setOf(OutputTarget.DRIVE),
        driveAccount = "me@example.com",
        notesFormat = NotesExportFormat.PDF_AND_MARKDOWN,
    )
    private val folderPrefs = DestinationPrefs(
        notes = setOf(OutputTarget.FOLDER),
        folderUri = "content://com.android.externalstorage.documents/tree/primary%3ASync",
        folderLabel = "Sync",
    )

    @After
    fun tearDown() {
        tmp.deleteRecursively()
    }

    private fun harness(prefs: DestinationPrefs, rename: Boolean = true) =
        EngineHarness(tmp, prefs, cloud, folderRoot = local, folderRename = rename).apply {
            source.docs[d.id] = d
            source.folders["f1"] = "Economics" to null
            source.folders["f2"] = "Term 1" to "f1"
            source.markdowns[d.id] = "# Financial Markets\n\nYield curve notes"
        }

    private fun docDir(title: String = d.title) = File(local, "Inkwell/Economics/Term 1/$title")
    private fun drivePath(file: String, title: String = d.title) = "Inkwell/Economics/Term 1/$title/$file"

    private fun transcribed(h: EngineHarness, id: String = "rec-1", title: String = "Lecture 3") {
        h.source.recordings[id] = recording(id = id, title = title)
        h.source.transcripts[id] = StoredTranscript(listOf(TranscriptSegment(0, 1000, "Hello class", "Prof. Lee")), "on-device")
    }

    // ------------------------------------------------------------------ Google Drive

    @Test
    fun `notes go to Drive as notes pdf and notes md in Inkwell, folders, title`() = runBlocking {
        val h = harness(drivePrefs)
        h.engine.enqueue(d.id, SyncKind.NOTES, h.prefs.notes)
        val report = h.engine.run()

        assertEquals(listOf(drivePath("notes.md"), drivePath("notes.pdf")), cloud.files())
        assertTrue(cloud.node(drivePath("notes.pdf"))!!.content!!.contentEquals(FakeSource.pdfBytes(d.id)))
        assertEquals("application/pdf", cloud.node(drivePath("notes.pdf"))!!.mimeType)
        assertEquals("# Financial Markets\n\nYield curve notes", cloud.node(drivePath("notes.md"))!!.content!!.decodeToString())
        assertEquals(listOf("me@example.com"), h.driveAccounts)
        assertEquals("1", cloud.node("Inkwell")!!.properties[DriveNames.PROP_ROOT])
        assertEquals(d.id, cloud.node("Inkwell/Economics/Term 1/${d.title}")!!.properties[DriveNames.PROP_DOC])

        assertEquals(1, report.succeeded)
        assertNull(report.error)
        assertEquals(SyncItemStatus.DONE, h.dao.all.single().status)
        assertEquals(h.now, h.source.synced[d.id])
        assertEquals(h.now, h.state.lastSuccessAt)
        assertEquals("temporary exports are cleaned up", 0, File(tmp, "exports").list()!!.size)
    }

    @Test
    fun `a second copy replaces the files in place, keeping their ids`() = runBlocking {
        val h = harness(drivePrefs)
        h.engine.enqueue(d.id, SyncKind.NOTES, h.prefs.notes)
        h.engine.run()
        val pdfId = cloud.node(drivePath("notes.pdf"))!!.id
        val folderCount = cloud.nodes.values.count { it.isFolder }

        h.source.markdowns[d.id] = "# v2"
        h.engine.enqueue(d.id, SyncKind.NOTES, h.prefs.notes)
        h.engine.run()
        assertEquals(listOf(drivePath("notes.md"), drivePath("notes.pdf")), cloud.files())
        assertEquals(pdfId, cloud.node(drivePath("notes.pdf"))!!.id)
        assertEquals(2, cloud.node(drivePath("notes.pdf"))!!.versions)
        assertEquals("# v2", cloud.node(drivePath("notes.md"))!!.content!!.decodeToString())
        assertEquals("no new folders", folderCount, cloud.nodes.values.count { it.isFolder })
    }

    @Test
    fun `PDF only and Markdown only formats write only their file`() = runBlocking {
        val pdfOnly = harness(drivePrefs.copy(notesFormat = NotesExportFormat.PDF))
        pdfOnly.engine.enqueue(d.id, SyncKind.NOTES, pdfOnly.prefs.notes)
        pdfOnly.engine.run()
        assertEquals(listOf(drivePath("notes.pdf")), cloud.files())

        val other = FakeDrive()
        val mdOnly = EngineHarness(tmp, drivePrefs.copy(notesFormat = NotesExportFormat.MARKDOWN), other).apply { source.docs[d.id] = d.copy(folderId = null) }
        mdOnly.engine.enqueue(d.id, SyncKind.NOTES, mdOnly.prefs.notes)
        mdOnly.engine.run()
        assertEquals(0, mdOnly.source.pdfExports)
        assertEquals(listOf("Inkwell/${d.title}/notes.md"), other.files())
    }

    @Test
    fun `transcripts become a Google Doc next to the notes`() = runBlocking {
        val h = harness(DestinationPrefs(transcripts = setOf(OutputTarget.DRIVE), driveAccount = "me@example.com"))
        transcribed(h)
        h.engine.enqueue(d.id, SyncKind.TRANSCRIPT, h.prefs.transcripts, "rec-1")
        h.engine.run()

        assertEquals(listOf(drivePath("transcript_Lecture 3")), cloud.files())
        val docNode = cloud.node(drivePath("transcript_Lecture 3"))!!
        assertEquals(DriveNames.MIME_GOOGLE_DOC, docNode.mimeType)
        assertEquals(DriveNames.MIME_HTML, docNode.uploadedAs)
        val html = docNode.content!!.decodeToString()
        assertTrue(html, html.contains("<h1>Lecture 3</h1>"))
        assertTrue(html, html.contains("<b>Prof. Lee:</b> Hello class"))
        assertEquals(DriveNames.transcriptKey("rec-1"), docNode.properties[DriveNames.PROP_FILE])
        assertTrue("notes text stays on the tablet", h.source.pdfExports == 0 && cloud.files().none { it.endsWith("notes.md") })
    }

    @Test
    fun `a transcript Drive can't convert is kept as Markdown`() = runBlocking {
        cloud.refuseConversion = true
        val h = harness(DestinationPrefs(transcripts = setOf(OutputTarget.DRIVE), driveAccount = "me@example.com"))
        transcribed(h)
        h.engine.enqueue(d.id, SyncKind.TRANSCRIPT, h.prefs.transcripts, "rec-1")
        val report = h.engine.run()
        assertEquals(1, report.succeeded)
        assertEquals(listOf(drivePath("transcript_Lecture 3.md")), cloud.files())
        assertTrue(cloud.node(drivePath("transcript_Lecture 3.md"))!!.content!!.decodeToString().contains("Hello class"))
    }

    @Test
    fun `a renamed recording renames its files instead of adding new ones`() = runBlocking {
        val h = harness(DestinationPrefs(transcripts = setOf(OutputTarget.DRIVE), audio = setOf(OutputTarget.DRIVE), driveAccount = "me@example.com", audioOnUnmeteredOnly = false))
        transcribed(h)
        h.engine.enqueue(d.id, SyncKind.TRANSCRIPT, h.prefs.transcripts, "rec-1")
        h.engine.enqueue(d.id, SyncKind.AUDIO, h.prefs.audio, "rec-1")
        h.engine.run()
        assertEquals(listOf(drivePath("audio_Lecture 3.m4a"), drivePath("transcript_Lecture 3")), cloud.files())
        assertTrue(cloud.node(drivePath("audio_Lecture 3.m4a"))!!.content!!.contentEquals(FakeSource.audioBytes("rec-1")))
        assertEquals("audio/mp4", cloud.node(drivePath("audio_Lecture 3.m4a"))!!.mimeType)

        h.source.recordings["rec-1"] = h.source.recordings.getValue("rec-1").copy(title = "Budget review")
        h.engine.enqueue(d.id, SyncKind.TRANSCRIPT, h.prefs.transcripts, "rec-1")
        h.engine.enqueue(d.id, SyncKind.AUDIO, h.prefs.audio, "rec-1")
        h.engine.run()
        assertEquals(listOf(drivePath("audio_Budget review.m4a"), drivePath("transcript_Budget review")), cloud.files())
    }

    @Test
    fun `a renamed or moved document moves its Drive folder along`() = runBlocking {
        val h = harness(drivePrefs)
        h.engine.enqueue(d.id, SyncKind.NOTES, h.prefs.notes)
        h.engine.run()
        val folderId = cloud.node("Inkwell/Economics/Term 1/${d.title}")!!.id

        h.source.docs[d.id] = d.copy(folderId = "f1", title = "20261015_Bond Pricing")
        h.engine.enqueue(d.id, SyncKind.NOTES, h.prefs.notes)
        h.engine.run()
        assertEquals(listOf("Inkwell/Economics/20261015_Bond Pricing/notes.md", "Inkwell/Economics/20261015_Bond Pricing/notes.pdf"), cloud.files())
        assertEquals(folderId, cloud.node("Inkwell/Economics/20261015_Bond Pricing")!!.id)
    }

    @Test
    fun `a document folder deleted in Drive is made again`() = runBlocking {
        val h = harness(drivePrefs)
        h.engine.enqueue(d.id, SyncKind.NOTES, h.prefs.notes)
        h.engine.run()
        cloud.node("Inkwell/Economics/Term 1/${d.title}")!!.trashed = true
        assertTrue(cloud.files().isEmpty())

        h.engine.enqueue(d.id, SyncKind.NOTES, h.prefs.notes)
        h.engine.run()
        assertEquals(listOf(drivePath("notes.md"), drivePath("notes.pdf")), cloud.files())
        assertEquals(SyncItemStatus.DONE, h.dao.all.last().status)
    }

    @Test
    fun `a whole Inkwell folder deleted in Drive is made again`() = runBlocking {
        val h = harness(drivePrefs)
        h.engine.enqueue(d.id, SyncKind.NOTES, h.prefs.notes)
        h.engine.run()
        cloud.node("Inkwell")!!.trashed = true

        h.engine.enqueue(d.id, SyncKind.NOTES, h.prefs.notes)
        h.engine.run()
        assertEquals(listOf(drivePath("notes.md"), drivePath("notes.pdf")), cloud.files())
        assertEquals(1, cloud.nodes.values.count { !it.trashed && it.properties[DriveNames.PROP_ROOT] == "1" })
    }

    @Test
    fun `forgotten ids are found again by app property, never duplicated`() = runBlocking {
        val h = harness(drivePrefs)
        h.engine.enqueue(d.id, SyncKind.NOTES, h.prefs.notes)
        h.engine.run()
        val before = cloud.nodes.size
        h.state.clearDrive()

        h.engine.enqueue(d.id, SyncKind.NOTES, h.prefs.notes)
        h.engine.run()
        assertEquals("same folders and files, updated", before, cloud.nodes.size)
        assertEquals(listOf(drivePath("notes.md"), drivePath("notes.pdf")), cloud.files())
    }

    @Test
    fun `audio waits for an unmetered network when asked`() = runBlocking {
        val h = harness(DestinationPrefs(audio = setOf(OutputTarget.DRIVE), driveAccount = "me@example.com", audioOnUnmeteredOnly = true))
        h.source.recordings["rec-1"] = recording()
        h.network.unmetered = false
        h.engine.enqueue(d.id, SyncKind.AUDIO, setOf(OutputTarget.DRIVE), "rec-1")

        val first = h.engine.run()
        assertTrue(cloud.calls.isEmpty())
        assertEquals(1, first.deferred)
        assertEquals(SyncItemStatus.PENDING, h.dao.all.single().status)
        assertEquals(SyncNeeds(unmetered = true), syncNeeds(h.dao.pendingRows(), h.prefs))

        h.network.unmetered = true
        h.engine.run()
        assertEquals(listOf(drivePath("audio_Lecture 3.m4a")), cloud.files())
        assertEquals(SyncItemStatus.DONE, h.dao.all.single().status)
    }

    @Test
    fun `audio on metered networks goes when the user allows it`() = runBlocking {
        val h = harness(DestinationPrefs(audio = setOf(OutputTarget.DRIVE), driveAccount = "me@example.com", audioOnUnmeteredOnly = false))
        h.source.recordings["rec-1"] = recording()
        h.network.unmetered = false
        h.engine.enqueue(d.id, SyncKind.AUDIO, setOf(OutputTarget.DRIVE), "rec-1")
        h.engine.run()
        assertEquals(listOf(drivePath("audio_Lecture 3.m4a")), cloud.files())
    }

    @Test
    fun `a recording still in progress is left for later`() = runBlocking {
        val h = harness(DestinationPrefs(audio = setOf(OutputTarget.DRIVE), driveAccount = "me@example.com", audioOnUnmeteredOnly = false))
        h.source.recordings["rec-1"] = recording(status = RecordingStatus.RECORDING)
        h.engine.enqueue(d.id, SyncKind.AUDIO, setOf(OutputTarget.DRIVE), "rec-1")
        val report = h.engine.run()
        assertEquals(1, report.deferred)
        assertEquals(SyncItemStatus.PENDING, h.dao.all.single().status)
        assertEquals(0, h.source.audioExports)
    }

    @Test
    fun `offline Drive waits while the folder copy goes ahead`() = runBlocking {
        val h = harness(drivePrefs.copy(notes = setOf(OutputTarget.DRIVE, OutputTarget.FOLDER), folderUri = folderPrefs.folderUri))
        h.network.online = false
        h.engine.enqueue(d.id, SyncKind.NOTES, h.prefs.notes)
        val report = h.engine.run()

        assertTrue(cloud.calls.isEmpty())
        assertTrue(File(docDir(), "notes.pdf").isFile)
        assertEquals(1, report.succeeded)
        assertEquals(1, report.deferred)
        val driveRow = h.dao.all.single { it.target == OutputTarget.DRIVE.name }
        assertEquals(SyncItemStatus.PENDING, driveRow.status)
        assertEquals("waiting for a network is not a failed attempt", 0, driveRow.attempts)
    }

    @Test
    fun `without a connected account Drive items wait and say how to connect`() = runBlocking {
        val h = harness(drivePrefs.copy(driveAccount = null))
        h.engine.enqueue(d.id, SyncKind.NOTES, h.prefs.notes)
        val report = h.engine.run()
        assertTrue(cloud.calls.isEmpty())
        assertTrue(h.driveAccounts.isEmpty())
        assertEquals(SyncItemStatus.PENDING, h.dao.all.single().status)
        assertTrue(report.error!!.contains("Connect Google Drive"))
        assertFalse(report.retryLater)
    }

    @Test
    fun `sign-in or setup problems stop Drive work for the run after one try`() = runBlocking {
        val h = harness(drivePrefs)
        val other = doc(id = "11111111-2222-3333-4444-555555555555", title = "Other")
        h.source.docs[other.id] = other
        h.engine.enqueue(d.id, SyncKind.NOTES, h.prefs.notes)
        h.engine.enqueue(other.id, SyncKind.NOTES, h.prefs.notes)
        cloud.failNext += DriveException.NeedsSignIn()
        val report = h.engine.run()

        assertEquals("one call, then Drive work stops for this run", 1, cloud.calls.size)
        assertTrue(h.dao.all.all { it.status == SyncItemStatus.PENDING && it.attempts == 0 })
        assertTrue(report.error!!.contains("sign in again"))
        assertFalse(report.retryLater)
        assertEquals(report.error, h.state.lastError)

        cloud.failNext += DriveException.NotSetUp()
        assertTrue(h.engine.run().error!!.contains("isn't set up"))

        h.engine.run()
        assertTrue("fixed: everything goes", h.dao.all.all { it.status == SyncItemStatus.DONE })
        assertNull(h.state.lastError)
    }

    @Test
    fun `rate limits slow down without counting as failures`() = runBlocking {
        val h = harness(drivePrefs)
        h.engine.enqueue(d.id, SyncKind.NOTES, h.prefs.notes)
        cloud.failNext += DriveException.RateLimited()
        val report = h.engine.run()
        assertTrue(report.slowDown)
        assertTrue(report.retryLater)
        assertEquals(SyncItemStatus.PENDING, h.dao.all.single().status)
        assertEquals(0, h.dao.all.single().attempts)
    }

    @Test
    fun `server errors retry with a limit and rejected uploads fail at once`() = runBlocking {
        val h = harness(drivePrefs.copy(notesFormat = NotesExportFormat.MARKDOWN))
        h.engine.enqueue(d.id, SyncKind.NOTES, h.prefs.notes)
        repeat(SyncEngine.MAX_ATTEMPTS - 1) { i ->
            if (i > 0) h.now += SyncEngine.retryDelayMs(i)
            cloud.failNext += DriveException.Server(503)
            val report = h.engine.run()
            assertTrue(report.retryLater)
            val row = h.dao.all.single()
            assertEquals(SyncItemStatus.PENDING, row.status)
            assertEquals(i + 1, row.attempts)
            assertTrue(row.lastError!!.contains("HTTP 503"))
        }
        h.now += SyncEngine.retryDelayMs(SyncEngine.MAX_ATTEMPTS - 1)
        cloud.failNext += DriveException.Server(503)
        h.engine.run()
        assertEquals(SyncItemStatus.FAILED, h.dao.all.single().status)

        val h2 = harness(drivePrefs)
        h2.engine.enqueue(d.id, SyncKind.NOTES, h2.prefs.notes)
        cloud.failNext += DriveException.Rejected(400, "invalid")
        val report = h2.engine.run()
        assertFalse(report.retryLater)
        assertEquals(1, report.failed)
        assertEquals(SyncItemStatus.FAILED, h2.dao.all.single().status)
    }

    @Test
    fun `runs kicked during an item's retry backoff neither send it nor use up its attempts`() = runBlocking {
        val h = harness(drivePrefs.copy(notesFormat = NotesExportFormat.MARKDOWN))
        h.engine.enqueue(d.id, SyncKind.NOTES, h.prefs.notes)
        cloud.failNext += DriveException.Server(503)
        h.engine.run()
        assertEquals(1, h.dao.all.single().attempts)
        val sent = cloud.calls.size

        // Edits settling, recordings and settings changes kick runs again and again during the outage.
        repeat(20) {
            h.now += 1_000
            val report = h.engine.run()
            assertTrue("the run comes back for the waiting item", report.retryLater)
            assertEquals(1, report.deferred)
            assertTrue("the outage stays shown while the item waits", h.state.lastError!!.contains("HTTP 503"))
        }
        assertEquals(1, h.dao.all.single().attempts)
        assertEquals(SyncItemStatus.PENDING, h.dao.all.single().status)
        assertEquals("nothing is sent while the item waits", sent, cloud.calls.size)

        // "Sync now" does not wait: the user asked.
        cloud.failNext += DriveException.Server(503)
        h.engine.run(ignoreBackoff = true)
        assertEquals(2, h.dao.all.single().attempts)
        h.now += SyncEngine.retryDelayMs(2) - 1
        h.engine.run()
        assertEquals("the longer second backoff is waited out too", 2, h.dao.all.single().attempts)

        h.now += 1
        val report = h.engine.run()
        assertNull(report.error, report.error)
        assertNull("the copy went through: the error is cleared", h.state.lastError)
        assertEquals(SyncItemStatus.DONE, h.dao.all.single().status)
    }

    @Test
    fun `retry backoff doubles per attempt and is capped`() {
        assertEquals(30_000L, SyncEngine.retryDelayMs(1))
        assertEquals(60_000L, SyncEngine.retryDelayMs(2))
        assertEquals(30_000L shl 6, SyncEngine.retryDelayMs(SyncEngine.MAX_ATTEMPTS - 1))
        assertEquals(3_600_000L, SyncEngine.retryDelayMs(50))
        assertEquals(30_000L, SyncEngine.retryDelayMs(0))
    }

    @Test
    fun `export failures retry, unimplemented exports fail`() = runBlocking {
        val h = harness(folderPrefs)
        h.source.failExport = IOException("render failed")
        h.engine.enqueue(d.id, SyncKind.NOTES, h.prefs.notes)
        val r1 = h.engine.run()
        assertTrue(r1.retryLater)
        assertEquals(1, h.dao.all.single().attempts)
        assertTrue(r1.error!!.contains(d.title))

        h.source.failExport = NotImplementedError()
        h.now += SyncEngine.retryDelayMs(1)
        val r2 = h.engine.run()
        assertEquals(1, r2.failed)
        assertEquals(SyncItemStatus.FAILED, h.dao.all.single().status)
    }

    @Test
    fun `items for switched-off destinations and missing documents are dropped quietly`() = runBlocking {
        val h = harness(drivePrefs)
        h.engine.enqueue(d.id, SyncKind.NOTES, setOf(OutputTarget.DRIVE))
        h.engine.enqueue("gone", SyncKind.NOTES, setOf(OutputTarget.DRIVE))
        h.prefs = h.prefs.copy(notes = emptySet())
        h.engine.run()
        assertTrue(cloud.calls.isEmpty())
        assertTrue(h.dao.all.all { it.status == SyncItemStatus.DONE })

        val h2 = harness(drivePrefs)
        h2.source.docs[d.id] = d.copy(trashedAt = 5)
        h2.engine.enqueue(d.id, SyncKind.NOTES, h2.prefs.notes)
        h2.engine.enqueue("gone", SyncKind.NOTES, h2.prefs.notes)
        h2.engine.run()
        assertTrue(cloud.calls.isEmpty())
        assertTrue(h2.dao.all.all { it.status == SyncItemStatus.DONE })
        assertTrue(h2.source.synced.isEmpty())
    }

    @Test
    fun `rows of the removed notes vault are ignored`() = runBlocking {
        val h = harness(drivePrefs)
        h.dao.enqueue(
            com.inkwell.notes.core.storage.db.SyncQueueEntity(
                documentId = d.id, kind = "NOTES", target = "VAULT", refId = null, enqueuedAt = h.now, attempts = 0, lastError = null, status = "PENDING",
            ),
        )
        h.engine.run()
        assertTrue(cloud.calls.isEmpty())
        assertEquals(SyncItemStatus.DONE, h.dao.all.single().status)
    }

    // ------------------------------------------------------------------ folder

    @Test
    fun `notes go to Inkwell, folder path, title in the chosen folder`() = runBlocking {
        val h = harness(folderPrefs)
        h.engine.enqueue(d.id, SyncKind.NOTES, h.prefs.notes)
        h.engine.run()
        assertEquals(listOf("notes.md", "notes.pdf"), docDir().list()!!.sorted())
        assertTrue(File(docDir(), "notes.pdf").readBytes().contentEquals(FakeSource.pdfBytes(d.id)))
        assertEquals("# Financial Markets\n\nYield curve notes", File(docDir(), "notes.md").readText())

        h.source.markdowns[d.id] = "# v2"
        h.engine.enqueue(d.id, SyncKind.NOTES, h.prefs.notes)
        h.engine.run()
        assertEquals("updates overwrite in place", listOf("notes.md", "notes.pdf"), docDir().list()!!.sorted())
        assertEquals("# v2", File(docDir(), "notes.md").readText())
        assertTrue(cloud.calls.isEmpty())
    }

    @Test
    fun `transcripts and audio land next to the notes`() = runBlocking {
        val h = harness(DestinationPrefs(transcripts = setOf(OutputTarget.FOLDER), audio = setOf(OutputTarget.FOLDER), folderUri = folderPrefs.folderUri))
        h.source.recordings["rec-1"] = recording(title = "Lecture 3")
        h.source.transcripts["rec-1"] = StoredTranscript(listOf(TranscriptSegment(0, 1000, "Hello class", null)), "on-device")
        h.engine.enqueue(d.id, SyncKind.TRANSCRIPT, h.prefs.transcripts, "rec-1")
        h.engine.enqueue(d.id, SyncKind.AUDIO, h.prefs.audio, "rec-1")
        h.engine.run()
        assertEquals(listOf("audio_Lecture 3.m4a", "transcript_Lecture 3.md"), docDir().list()!!.sorted())
        assertTrue(File(docDir(), "transcript_Lecture 3.md").readText().contains("Hello class"))
        assertTrue(File(docDir(), "audio_Lecture 3.m4a").readBytes().contentEquals(FakeSource.audioBytes("rec-1")))
    }

    @Test
    fun `both destinations share one PDF export`() = runBlocking {
        val h = harness(drivePrefs.copy(notes = setOf(OutputTarget.DRIVE, OutputTarget.FOLDER), folderUri = folderPrefs.folderUri))
        h.engine.enqueue(d.id, SyncKind.NOTES, h.prefs.notes)
        h.engine.run()
        assertEquals(1, h.source.pdfExports)
        assertTrue(File(docDir(), "notes.pdf").isFile)
        assertEquals(listOf(drivePath("notes.md"), drivePath("notes.pdf")), cloud.files())
    }

    @Test
    fun `a renamed document renames its folder copy`() = runBlocking {
        val h = harness(folderPrefs)
        h.engine.enqueue(d.id, SyncKind.NOTES, h.prefs.notes)
        h.engine.run()
        File(docDir(), "my own file.txt").writeText("keep me")

        h.source.docs[d.id] = d.copy(title = "20261015_Bond Pricing")
        h.engine.enqueue(d.id, SyncKind.NOTES, h.prefs.notes)
        h.engine.run()
        assertFalse(docDir().exists())
        assertEquals(listOf("my own file.txt", "notes.md", "notes.pdf"), docDir("20261015_Bond Pricing").list()!!.sorted())
    }

    @Test
    fun `without rename support a moved document is rewritten and old copies removed`() = runBlocking {
        val prefs = folderPrefs.copy(transcripts = setOf(OutputTarget.FOLDER))
        val h = harness(prefs, rename = false)
        h.source.recordings["rec-1"] = recording(title = "Lecture 3", transcriptStatus = TranscriptStatus.DONE)
        h.source.transcripts["rec-1"] = StoredTranscript(listOf(TranscriptSegment(0, 1000, "Hi", null)), "on-device")
        h.engine.enqueue(d.id, SyncKind.NOTES, prefs.notes)
        h.engine.enqueue(d.id, SyncKind.TRANSCRIPT, prefs.transcripts, "rec-1")
        h.engine.run()
        assertEquals(listOf("notes.md", "notes.pdf", "transcript_Lecture 3.md"), docDir().list()!!.sorted())

        h.source.docs[d.id] = d.copy(folderId = "f1", title = "Moved")
        h.engine.enqueue(d.id, SyncKind.NOTES, prefs.notes)
        h.engine.run()
        val moved = File(local, "Inkwell/Economics/Moved")
        assertEquals(listOf("notes.md", "notes.pdf", "transcript_Lecture 3.md"), moved.list()!!.sorted())
        assertFalse("old folder removed once empty", docDir().exists())
        assertTrue(h.dao.pendingRows().isEmpty())
    }

    @Test
    fun `a revoked folder clears the destination and fails its items`() = runBlocking {
        val h = harness(folderPrefs.copy(transcripts = setOf(OutputTarget.FOLDER)))
        h.folderAccess = FolderAccess { throw FolderRevokedException() }
        h.engine.enqueue(d.id, SyncKind.NOTES, setOf(OutputTarget.FOLDER))
        h.engine.enqueue(d.id, SyncKind.TRANSCRIPT, setOf(OutputTarget.FOLDER), "rec-x")
        h.source.recordings["rec-x"] = recording(id = "rec-x")
        h.source.transcripts["rec-x"] = StoredTranscript(listOf(TranscriptSegment(0, 1, "x", null)), "s")
        val report = h.engine.run()

        assertEquals(1, h.revoked)
        assertNull(h.prefs.folderUri)
        assertFalse(OutputTarget.FOLDER in h.prefs.notes)
        assertTrue(h.dao.all.all { it.status == SyncItemStatus.FAILED || it.status == SyncItemStatus.DONE })
        assertTrue(report.error!!.contains("Choose the folder again"))
    }

    @Test
    fun `an unavailable folder is retried later and keeps the destination`() = runBlocking {
        val h = harness(folderPrefs)
        h.folderAccess = FolderAccess { throw FolderUnavailableException() }
        h.engine.enqueue(d.id, SyncKind.NOTES, h.prefs.notes)
        val report = h.engine.run()
        assertTrue(report.retryLater)
        assertEquals(0, h.revoked)
        assertNotNull(h.prefs.folderUri)
        assertEquals(SyncItemStatus.PENDING, h.dao.all.single().status)
    }

    // ------------------------------------------------------------------ queue

    @Test
    fun `enqueue keeps one open item per document, kind, target and recording`() = runBlocking {
        val h = harness(drivePrefs)
        h.engine.enqueue(d.id, SyncKind.NOTES, setOf(OutputTarget.DRIVE, OutputTarget.FOLDER))
        h.now += 1000
        h.engine.enqueue(d.id, SyncKind.NOTES, setOf(OutputTarget.DRIVE))
        h.engine.enqueue(d.id, SyncKind.TRANSCRIPT, setOf(OutputTarget.DRIVE), "rec-1")
        h.engine.enqueue(d.id, SyncKind.TRANSCRIPT, setOf(OutputTarget.DRIVE), "rec-2")
        h.engine.enqueue(d.id, SyncKind.TRANSCRIPT, setOf(OutputTarget.DRIVE), "rec-1")

        val pending = h.dao.pendingRows()
        assertEquals(4, pending.size)
        assertEquals(1, pending.count { it.kind == "NOTES" && it.target == "DRIVE" })
        assertEquals(h.now, pending.single { it.kind == "NOTES" && it.target == "DRIVE" }.enqueuedAt)
        assertEquals(1, pending.count { it.kind == "NOTES" && it.target == "FOLDER" })
        assertEquals(setOf("rec-1", "rec-2"), pending.filter { it.kind == "TRANSCRIPT" }.map { it.refId }.toSet())
        assertEquals(1, pending.count { it.refId == "rec-1" })
    }

    @Test
    fun `a new item supersedes a failed one`() = runBlocking {
        val h = harness(drivePrefs)
        h.engine.enqueue(d.id, SyncKind.NOTES, setOf(OutputTarget.DRIVE))
        h.dao.failPendingForTarget("DRIVE", "boom")
        h.engine.enqueue(d.id, SyncKind.NOTES, setOf(OutputTarget.DRIVE))
        assertEquals(1, h.dao.all.size)
        assertEquals(SyncItemStatus.PENDING, h.dao.all.single().status)
    }

    @Test
    fun `changed documents are those edited after their last copy`() = runBlocking {
        val h = harness(folderPrefs)
        h.source.docs.clear()
        val never = doc(id = "a", lastSyncedAt = 0)
        val edited = doc(id = "b", modifiedAt = 10_000, lastSyncedAt = 5_000)
        val clean = doc(id = "c", modifiedAt = 10_000, lastSyncedAt = 9_000)
        val trashed = doc(id = "d", lastSyncedAt = 0).copy(trashedAt = 1)
        listOf(never, edited, clean, trashed).forEach { h.source.docs[it.id] = it }
        h.engine.enqueueChangedDocuments()
        assertEquals(setOf("a", "b"), h.dao.pendingRows().map { it.documentId }.toSet())
    }

    @Test
    fun `everything means notes, finished recordings and done transcripts`() = runBlocking {
        val prefs = DestinationPrefs(
            notes = setOf(OutputTarget.FOLDER),
            transcripts = setOf(OutputTarget.DRIVE),
            audio = setOf(OutputTarget.FOLDER, OutputTarget.DRIVE),
            folderUri = "x", driveAccount = "me@example.com",
        )
        val h = harness(prefs)
        h.source.recordings["r1"] = recording(id = "r1")
        h.source.recordings["r2"] = recording(id = "r2", status = RecordingStatus.RECORDING, transcriptStatus = TranscriptStatus.NONE)
        h.engine.enqueueEverything()
        val rows = h.dao.pendingRows().map { "${it.kind}:${it.target}:${it.refId}" }.toSet()
        assertEquals(setOf("NOTES:FOLDER:null", "TRANSCRIPT:DRIVE:r1", "AUDIO:FOLDER:r1", "AUDIO:DRIVE:r1"), rows)

        h.dao.rows.value = emptyList()
        h.engine.enqueueEverything(onlyKind = SyncKind.AUDIO, onlyTarget = OutputTarget.DRIVE)
        assertEquals(listOf("AUDIO:DRIVE:r1"), h.dao.pendingRows().map { "${it.kind}:${it.target}:${it.refId}" })
    }

    @Test
    fun `scoped runs only take their own items and keep other errors`() = runBlocking {
        val prefs = drivePrefs.copy(
            notes = setOf(OutputTarget.FOLDER, OutputTarget.DRIVE), audio = setOf(OutputTarget.DRIVE),
            folderUri = folderPrefs.folderUri, audioOnUnmeteredOnly = true,
        )
        val h = harness(prefs)
        h.source.recordings["rec-1"] = recording()
        h.engine.enqueue(d.id, SyncKind.NOTES, prefs.notes)
        h.engine.enqueue(d.id, SyncKind.AUDIO, prefs.audio, "rec-1")
        h.state.lastError = "Google Drive needs you to sign in again."

        h.engine.run(SyncScope.FOLDER)
        assertTrue(File(docDir(), "notes.pdf").isFile)
        assertTrue(cloud.calls.isEmpty())
        assertEquals("a folder-only run can't clear a Drive problem", "Google Drive needs you to sign in again.", h.state.lastError)

        h.engine.run(SyncScope.DRIVE_AUDIO)
        assertEquals(listOf(drivePath("audio_Lecture 3.m4a")), cloud.files())
        assertEquals(1, h.dao.pendingRows().size)
        assertEquals("DRIVE", h.dao.pendingRows().single().target)

        h.engine.run(SyncScope.ALL)
        assertTrue(h.dao.pendingRows().isEmpty())
        assertNull(h.state.lastError)
    }

    @Test
    fun `background needs follow targets and the Wi-Fi rule`() {
        fun row(kind: SyncKind, target: OutputTarget) = com.inkwell.notes.core.storage.db.SyncQueueEntity(
            documentId = "d", kind = kind.name, target = target.name, refId = null, enqueuedAt = 0, attempts = 0, lastError = null, status = "PENDING",
        )
        val wifiOnly = DestinationPrefs(audioOnUnmeteredOnly = true)
        assertEquals(SyncNeeds(local = true), syncNeeds(listOf(row(SyncKind.NOTES, OutputTarget.FOLDER)), wifiOnly))
        assertEquals(SyncNeeds(network = true), syncNeeds(listOf(row(SyncKind.NOTES, OutputTarget.DRIVE)), wifiOnly))
        assertEquals(SyncNeeds(unmetered = true), syncNeeds(listOf(row(SyncKind.AUDIO, OutputTarget.DRIVE)), wifiOnly))
        assertEquals(SyncNeeds(network = true), syncNeeds(listOf(row(SyncKind.AUDIO, OutputTarget.DRIVE)), wifiOnly.copy(audioOnUnmeteredOnly = false)))
        assertFalse(syncNeeds(emptyList(), wifiOnly).any)
    }
}
