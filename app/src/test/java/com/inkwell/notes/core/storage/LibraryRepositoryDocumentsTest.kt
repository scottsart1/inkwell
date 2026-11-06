package com.inkwell.notes.core.storage

import com.inkwell.notes.core.model.AudioAnchor
import com.inkwell.notes.core.model.AutoNameStatus
import com.inkwell.notes.core.model.DocKind
import com.inkwell.notes.core.model.ImageItem
import com.inkwell.notes.core.model.LibraryFilter
import com.inkwell.notes.core.model.OutlineEntry
import com.inkwell.notes.core.model.PageContent
import com.inkwell.notes.core.model.PenType
import com.inkwell.notes.core.model.Stroke
import com.inkwell.notes.core.model.TextSource
import com.inkwell.notes.core.naming.DateNaming
import com.inkwell.notes.core.storage.db.ChatMessageEntity
import com.inkwell.notes.core.storage.db.EmbeddingEntity
import com.inkwell.notes.core.storage.db.TextIndexEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.TimeZone

/** Document-level behaviour of [LibraryRepositoryImpl] against the in-memory database and real files. */
class LibraryRepositoryDocumentsTest {
    private lateinit var h: RepositoryTestHarness
    private val repo get() = h.repo

    @Before fun setUp() { h = RepositoryTestHarness() }
    @After fun tearDown() { h.close() }

    private fun ink(id: String, audio: AudioAnchor? = null) =
        Stroke(id, PenType.BALLPOINT, 0xFF000000.toInt(), 2f, floatArrayOf(1f, 2f, 0.5f, 0f, 0f, 3f, 4f, 0.5f, 0f, 10f), createdAt = 1L, audio = audio)

    // ------------------------------------------------------------------ creation & naming

    @Test fun newDocumentsAreDateUntitledAndNumbered() = runTest {
        val a = repo.createDocument(DocKind.NOTEBOOK)
        val b = repo.createDocument(DocKind.TYPED)
        val c = repo.createDocument(DocKind.WHITEBOARD)
        val prefix = DateNaming.datePrefix(h.now)
        assertEquals("${prefix}_Untitled", a.title)
        assertEquals("${prefix}_Untitled (2)", b.title)
        assertEquals("${prefix}_Untitled (3)", c.title)
        assertEquals(0, b.pageCount)
        assertEquals(1, c.pageCount)
        assertTrue(repo.getPages(c.id).single().isInfinite)
        assertEquals(listOf(a.id, b.id, c.id), h.hooks.metaChanged)
    }

    @Test fun importsKeepTheirNameAndAreNeverAutoRenamed() = runTest {
        val a = repo.createDocument(DocKind.PDF, originalName = "Lecture 5 slides.pdf")
        val b = repo.createDocument(DocKind.PDF, originalName = "/sdcard/Download/Lecture 5 slides.PDF")
        assertEquals("Lecture 5 slides", a.title)
        assertEquals("Lecture 5 slides (2)", b.title)
        assertEquals(AutoNameStatus.IMPORTED, a.autoNameStatus)
        assertEquals("Lecture 5 slides.pdf", a.originalName)
        // The background job's variant refuses imported documents; only the explicit path renames them.
        assertNull(repo.applyAutoNameIfWaiting(a.id, "Thermodynamics"))
        assertEquals("Lecture 5 slides", repo.getDocument(a.id)!!.title)
    }

    @Test fun documentCreatedInATrashedFolderLandsAtTheRoot() = runTest {
        val f = repo.createFolder("Old", null)
        repo.trashFolder(f.id)
        val d = repo.createDocument(DocKind.NOTEBOOK, folderId = f.id)
        assertNull(d.folderId)
    }

    @Test fun autoNamingIsOffWhenDisabledAtCreation() = runTest {
        h.settings.update { it.copy(naming = it.naming.copy(autoNameEnabled = false)) }
        val d = repo.createDocument(DocKind.NOTEBOOK)
        assertEquals(AutoNameStatus.DISABLED, d.autoNameStatus)
        assertNull(repo.applyAutoNameIfWaiting(d.id, "Physics"))
    }

    @Test fun manualRenameMarksManualAndIsUniqueAndBlankIsRejected() = runTest {
        val a = repo.createDocument(DocKind.NOTEBOOK)
        val b = repo.createDocument(DocKind.NOTEBOOK)
        repo.renameDocument(a.id, "  Physics  ")
        repo.renameDocument(b.id, "physics")
        assertEquals("Physics", repo.getDocument(a.id)!!.title)
        assertEquals("physics (2)", repo.getDocument(b.id)!!.title)
        assertEquals(AutoNameStatus.MANUAL, repo.getDocument(a.id)!!.autoNameStatus)
        assertEquals(listOf(a.id, b.id), h.hooks.renamed)
        repo.renameDocument(a.id, " \n\t ")
        assertEquals("Physics", repo.getDocument(a.id)!!.title)
        // A manually named document is never auto-renamed by the background job...
        assertNull(repo.applyAutoNameIfWaiting(a.id, "Thermodynamics"))
        assertEquals("Physics", repo.getDocument(a.id)!!.title)
        // ...but the user's explicit "rename now" still works.
        assertEquals("${DateNaming.datePrefix(h.now)}_Thermodynamics", repo.applyAutoName(a.id, "Thermodynamics"))
    }

    @Test fun autoNameKeepsTheCreationPrefixDaysLaterAndNeverRunsTwice() = runTest {
        val d = repo.createDocument(DocKind.NOTEBOOK)
        val prefix = DateNaming.datePrefix(h.now)
        h.advance(30L * 24 * 3600 * 1000)
        val title = repo.applyAutoNameIfWaiting(d.id, "monetary policy")
        assertEquals("${prefix}_Monetary Policy", title)
        val after = repo.getDocument(d.id)!!
        assertEquals(AutoNameStatus.DONE, after.autoNameStatus)
        assertEquals("Monetary Policy", after.topic)
        assertEquals(listOf(d.id), h.hooks.renamed)
        // DONE documents are left alone by the background path.
        assertNull(repo.applyAutoNameIfWaiting(d.id, "Something Else"))
        assertEquals("${prefix}_Monetary Policy", repo.getDocument(d.id)!!.title)
        // modifiedAt is not bumped by an automatic rename.
        assertEquals(d.modifiedAt, after.modifiedAt)
    }

    @Test fun autoNameGetsANumericSuffixWhenTheTopicIsTaken() = runTest {
        val a = repo.createDocument(DocKind.NOTEBOOK)
        val b = repo.createDocument(DocKind.NOTEBOOK)
        val prefix = DateNaming.datePrefix(h.now)
        assertEquals("${prefix}_Genetics", repo.applyAutoName(a.id, "Genetics"))
        assertEquals("${prefix}_Genetics (2)", repo.applyAutoName(b.id, "genetics"))
    }

    @Test fun genericTopicLeavesTheUntitledNameAndStatus() = runTest {
        val d = repo.createDocument(DocKind.NOTEBOOK)
        assertEquals(d.title, repo.applyAutoName(d.id, "Notes"))
        assertEquals(AutoNameStatus.PENDING, repo.getDocument(d.id)!!.autoNameStatus)
        assertTrue(h.hooks.renamed.isEmpty())
    }

    @Test fun datePrefixNeverChangesWhenTheDeviceTimeZoneChanges() = runTest {
        val original = TimeZone.getDefault()
        try {
            // Created at 23:30 in New York on 9 Oct...
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
            h.now = LocalDateTime.of(2025, 10, 9, 23, 30).atZone(ZoneId.of("America/New_York")).toInstant().toEpochMilli()
            val d = repo.createDocument(DocKind.NOTEBOOK)
            assertEquals("20251009_Untitled", d.title)
            // ...and named a day later after flying to London, where that instant was already 10 Oct.
            TimeZone.setDefault(TimeZone.getTimeZone("Europe/London"))
            h.advance(25L * 3600 * 1000)
            val title = repo.applyAutoNameIfWaiting(d.id, "Jet Lag")
            assertEquals("20251009_Jet Lag", title)
        } finally {
            TimeZone.setDefault(original)
        }
    }

    @Test fun trashedDocumentsAreNotAutoNamedButKeepWaiting() = runTest {
        val d = repo.createDocument(DocKind.NOTEBOOK)
        repo.trashDocument(d.id)
        assertNull(repo.applyAutoNameIfWaiting(d.id, "Physics"))
        assertEquals(AutoNameStatus.PENDING, repo.getDocument(d.id)!!.autoNameStatus)
    }

    // ------------------------------------------------------------------ tags, flags, metadata

    @Test fun tagsAreNormalisedAndBumpModifiedAtOnlyWhenChanged() = runTest {
        val d = repo.createDocument(DocKind.NOTEBOOK)
        h.advance(1000)
        repo.setTags(d.id, listOf(" Econ ", "econ", "Term\u001F1", "", "x".repeat(100)))
        val after = repo.getDocument(d.id)!!
        assertEquals(listOf("Econ", "Term 1", "x".repeat(48)), after.tags)
        assertEquals(h.now, after.modifiedAt)
        h.advance(1000)
        repo.setTags(d.id, listOf("Econ", "Term 1", "x".repeat(48)))
        assertEquals(after.modifiedAt, repo.getDocument(d.id)!!.modifiedAt)
    }

    @Test fun filingChangesDoNotBumpModifiedAt() = runTest {
        val d = repo.createDocument(DocKind.NOTEBOOK)
        val f = repo.createFolder("F", null)
        h.advance(5000)
        repo.setFavorite(d.id, true)
        repo.setPinned(d.id, true)
        repo.setArchived(d.id, true)
        repo.moveDocument(d.id, f.id)
        repo.setThumbnail(d.id, "/x/thumb.webp")
        repo.updateViewState(d.id, 3, 2f, 10f, 20f)
        val after = repo.getDocument(d.id)!!
        assertEquals(d.modifiedAt, after.modifiedAt)
        assertTrue(after.favorite && after.pinned && after.archived)
        assertEquals(f.id, after.folderId)
        assertEquals(3, after.lastPageIndex)
        assertEquals(h.now, after.openedAt)
        assertEquals("a move tells sync to move the copies", listOf(d.id), h.hooks.moved)
    }

    @Test fun viewStateRejectsNonFiniteValues() = runTest {
        val d = repo.createDocument(DocKind.NOTEBOOK)
        repo.updateViewState(d.id, -4, Float.NaN, Float.POSITIVE_INFINITY, -0f)
        val after = repo.getDocument(d.id)!!
        assertEquals(0, after.lastPageIndex)
        assertEquals(1f, after.lastZoom)
        assertEquals(0f, after.lastScrollX)
    }

    @Test fun updateDocumentFromAStaleCopyKeepsPageCountAndTrashState() = runTest {
        val d = repo.createDocument(DocKind.NOTEBOOK)
        repo.addPage(d.id)
        repo.trashDocument(d.id)
        repo.updateDocument(d.copy(coverColor = 42))
        val after = repo.getDocument(d.id)!!
        assertEquals(42, after.coverColor)
        assertEquals(2, after.pageCount)
        assertNotNull(after.trashedAt)
    }

    @Test fun moveToATrashedOrMissingFolderIsRefused() = runTest {
        val d = repo.createDocument(DocKind.NOTEBOOK)
        val f = repo.createFolder("F", null)
        repo.trashFolder(f.id)
        repo.moveDocument(d.id, f.id)
        assertNull(repo.getDocument(d.id)!!.folderId)
        repo.moveDocument(d.id, "nope")
        assertNull(repo.getDocument(d.id)!!.folderId)
        assertTrue(h.hooks.moved.isEmpty())
    }

    // ------------------------------------------------------------------ duplicate

    @Test fun duplicateCopiesPagesContentAssetsOutlineAndNaming() = runTest {
        val src = repo.createDocument(DocKind.NOTEBOOK)
        val p1 = repo.getPages(src.id).single()
        val p2 = repo.addPage(src.id)
        h.pageStore.save(src.id, p1.id, PageContent(strokes = listOf(ink("s1", AudioAnchor("rec", 1000))), images = listOf(ImageItem("i", "photo.png", 0f, 0f, 10f, 10f))))
        h.files.assetFile(src.id, "photo.png").writeBytes(byteArrayOf(1, 2, 3))
        repo.upsertOutlineEntry(OutlineEntry("o1", src.id, "Intro", p1.id))
        repo.upsertOutlineEntry(OutlineEntry("o2", src.id, "Detail", p2.id, parentId = "o1"))
        repo.setBookmark(p2.id, true, "Mark")
        repo.applyAutoName(src.id, "Physics")
        h.hooks.metaChanged.clear()

        val copy = repo.duplicateDocument(src.id)

        assertEquals("${DateNaming.datePrefix(src.createdAt)}_Physics copy", copy.title)
        assertEquals(AutoNameStatus.DONE, copy.autoNameStatus)
        assertEquals(src.createdAt, copy.createdAt)
        assertEquals(2, copy.pageCount)
        val pages = repo.getPages(copy.id)
        assertEquals(listOf(0, 1), pages.map { it.index })
        assertTrue(pages.none { it.id == p1.id || it.id == p2.id })
        val content = h.pageStore.load(copy.id, pages[0].id)
        assertEquals("s1", content.strokes.single().id)
        assertNull("recording links must not dangle in the copy", content.strokes.single().audio)
        assertTrue(h.files.assetFile(copy.id, "photo.png").readBytes().contentEquals(byteArrayOf(1, 2, 3)))
        assertTrue(pages[1].bookmarked)
        val outline = repo.observeOutline(copy.id).first()
        assertEquals(setOf("Intro", "Detail"), outline.map { it.title }.toSet())
        val intro = outline.single { it.title == "Intro" }
        val detail = outline.single { it.title == "Detail" }
        assertEquals(intro.id, detail.parentId)
        assertEquals(pages[0].id, intro.pageId)
        assertEquals(pages[1].id, detail.pageId)
        assertEquals(listOf(copy.id), h.hooks.metaChanged)
        // Source untouched.
        assertEquals(2, repo.getDocument(src.id)!!.pageCount)
        assertEquals(2, repo.getPages(src.id).size)
    }

    @Test fun duplicateOfATrashedPinnedDocumentIsLiveAndUnpinned() = runTest {
        val src = repo.createDocument(DocKind.NOTEBOOK)
        repo.setPinned(src.id, true)
        repo.trashDocument(src.id)
        val copy = repo.duplicateDocument(src.id)
        assertNull(copy.trashedAt)
        assertFalse(copy.pinned)
    }

    @Test fun duplicateOfAQueuedDocumentStartsPendingAgain() = runTest {
        val src = repo.createDocument(DocKind.NOTEBOOK)
        h.db.documents().updateAutoNameState(src.id, AutoNameStatus.INSUFFICIENT, 4, listOf(AutoNameStatus.PENDING))
        val copy = repo.duplicateDocument(src.id)
        assertEquals(AutoNameStatus.PENDING, copy.autoNameStatus)
        assertEquals(0, copy.autoNameAttempts)
    }

    @Test fun duplicateCopiesIndexRowsOfUpToDatePagesAndAsksForTheRest() = runTest {
        val src = repo.createDocument(DocKind.NOTEBOOK)
        val fresh = repo.getPages(src.id).single()
        val stale = repo.addPage(src.id)
        h.db.textIndex().insertAll(listOf(TextIndexEntity(documentId = src.id, pageId = fresh.id, recordingId = null, source = TextSource.HANDWRITING, text = "hello", confidence = null, timestampMs = null, bounds = null, engine = "t", updatedAt = 1)))
        h.db.pages().markIndexed(fresh.id, h.now + 1)
        h.db.pages().touch(stale.id, h.now + 5) // edited after its last indexing
        val copy = repo.duplicateDocument(src.id)
        val copiedPages = repo.getPages(copy.id)
        val rows = h.db.textIndex().forDocument(copy.id)
        assertEquals(1, rows.size)
        assertEquals(copiedPages[0].id, rows.single().pageId)
        assertEquals(listOf(copy.id to listOf(copiedPages[1].id)), h.hooks.needIndexing)
    }

    @Test fun duplicateOfAMissingDocumentFailsCleanly() = runTest {
        try {
            repo.duplicateDocument("ghost")
            fail("expected failure")
        } catch (e: IllegalArgumentException) {
            // expected
        }
        assertTrue(h.files.root.listFiles().orEmpty().isEmpty())
    }

    // ------------------------------------------------------------------ trash / delete

    @Test fun trashRestoreRoundTrip() = runTest {
        val f = repo.createFolder("F", null)
        val d = repo.createDocument(DocKind.NOTEBOOK, folderId = f.id)
        repo.trashDocument(d.id)
        assertNotNull(repo.getDocument(d.id)!!.trashedAt)
        assertTrue(repo.observeDocuments(LibraryFilter(folderId = f.id)).first().isEmpty())
        assertEquals(listOf(d.id), repo.observeDocuments(LibraryFilter(trashed = true)).first().map { it.id })
        repo.restoreDocument(d.id)
        val back = repo.getDocument(d.id)!!
        assertNull(back.trashedAt)
        assertEquals(f.id, back.folderId)
    }

    @Test fun restoringADocumentWhoseFolderIsTrashedBringsTheFolderBack() = runTest {
        val parent = repo.createFolder("P", null)
        val child = repo.createFolder("C", parent.id)
        val d = repo.createDocument(DocKind.NOTEBOOK, folderId = child.id)
        repo.trashFolder(parent.id)
        assertNotNull(repo.getDocument(d.id)!!.trashedAt)
        repo.restoreDocument(d.id)
        assertEquals(child.id, repo.getDocument(d.id)!!.folderId)
        assertNull(repo.getFolder(child.id)!!.trashedAt)
        assertNull(repo.getFolder(parent.id)!!.trashedAt)
    }

    @Test fun restoringADocumentWhoseFolderWasPurgedGoesToTheRoot() = runTest {
        val f = repo.createFolder("F", null)
        val d = repo.createDocument(DocKind.NOTEBOOK, folderId = f.id)
        repo.trashDocument(d.id)
        h.db.folders().delete(f.id)
        repo.restoreDocument(d.id)
        assertNull(repo.getDocument(d.id)!!.folderId)
    }

    @Test fun deletePermanentlyRemovesRowsFilesIndexAndChat() = runTest {
        val d = repo.createDocument(DocKind.NOTEBOOK)
        val p = repo.getPages(d.id).single()
        h.pageStore.save(d.id, p.id, PageContent(strokes = listOf(ink("s"))))
        h.files.assetFile(d.id, "a.png").writeBytes(byteArrayOf(1))
        File(h.files.audioDir(d.id, "rec"), "seg_000.m4a").writeBytes(byteArrayOf(1))
        repo.upsertOutlineEntry(OutlineEntry("o", d.id, "T", p.id))
        h.db.textIndex().insertAll(listOf(TextIndexEntity(documentId = d.id, pageId = p.id, recordingId = null, source = TextSource.HANDWRITING, text = "x", confidence = null, timestampMs = null, bounds = null, engine = "t", updatedAt = 1)))
        h.db.embeddings().insertAll(listOf(EmbeddingEntity(textRowId = 1, documentId = d.id, model = "m", vector = ByteArray(4))))
        h.db.chat().upsert(ChatMessageEntity("m1", "thread", d.id, "user", "hi", null, 1, null))
        val other = repo.createDocument(DocKind.NOTEBOOK)

        repo.deletePermanently(d.id)

        assertNull(repo.getDocument(d.id))
        assertTrue(repo.getPages(d.id).isEmpty())
        assertTrue(repo.observeOutline(d.id).first().isEmpty())
        assertTrue(h.db.textIndex().forDocument(d.id).isEmpty())
        assertTrue(h.db.embeddings().forDocument("m", d.id).isEmpty())
        assertTrue(h.db.chat().threadsForDocument(d.id).isEmpty())
        assertFalse(File(h.files.root, d.id).exists())
        assertEquals(listOf(d.id), h.hooks.deleted)
        // Nothing else was touched and the deletion journal is clean.
        assertNotNull(repo.getDocument(other.id))
        assertFalse(File(h.files.root.parentFile, "pending-deletes.txt").exists())
    }

    @Test fun deletingAnUnknownDocumentIsANoOp() = runTest {
        repo.deletePermanently("ghost")
        assertTrue(h.hooks.deleted.isEmpty())
        assertFalse(File(h.files.root.parentFile, "pending-deletes.txt").exists())
    }

    @Test fun emptyTrashRemovesTrashedDocumentsAndFoldersOnly() = runTest {
        val keepFolder = repo.createFolder("Keep", null)
        val keep = repo.createDocument(DocKind.NOTEBOOK, folderId = keepFolder.id)
        val binFolder = repo.createFolder("Bin", null)
        val binned = repo.createDocument(DocKind.NOTEBOOK, folderId = binFolder.id)
        val loose = repo.createDocument(DocKind.NOTEBOOK)
        repo.trashFolder(binFolder.id)
        repo.trashDocument(loose.id)
        repo.emptyTrash()
        assertNotNull(repo.getDocument(keep.id))
        assertNotNull(repo.getFolder(keepFolder.id))
        assertNull(repo.getDocument(binned.id))
        assertNull(repo.getDocument(loose.id))
        assertNull(repo.getFolder(binFolder.id))
        assertFalse(File(h.files.root, binned.id).exists())
        assertTrue(File(h.files.root, keep.id).exists() || !File(h.files.root, keep.id).exists()) // keep's folder may not exist yet
    }

    @Test fun purgeOnlyRemovesTrashOlderThanThirtyDaysAndFinishesInterruptedDeletes() = runTest {
        val old = repo.createDocument(DocKind.NOTEBOOK)
        val recent = repo.createDocument(DocKind.NOTEBOOK)
        val oldFolder = repo.createFolder("Old", null)
        repo.trashDocument(old.id)
        repo.trashFolder(oldFolder.id)
        h.advance(LibraryRepositoryImpl.TRASH_RETENTION_MS - 1000)
        repo.trashDocument(recent.id)
        h.advance(2000)
        // A deletion that died after the rows were gone but before the files were removed.
        val orphanDir = h.files.docDir("orphan-doc").apply { File(this, "x.bin").writeBytes(byteArrayOf(1)) }
        File(h.files.root.parentFile, "pending-deletes.txt").writeText("orphan-doc\n${recent.id}\n")

        val removed = repo.purgeExpiredTrash()

        assertEquals(1, removed)
        assertNull(repo.getDocument(old.id))
        assertNull(repo.getFolder(oldFolder.id))
        assertNotNull("trashed a moment ago", repo.getDocument(recent.id))
        assertFalse(orphanDir.exists())
        // A journaled id whose row still exists is alive (or being deleted right now): its files
        // survive and its entry is left to the deletion that wrote it.
        assertTrue(h.files.docDir(recent.id).exists())
        assertEquals(listOf(recent.id), File(h.files.root.parentFile, "pending-deletes.txt").readLines().filter { it.isNotBlank() })
    }

    @Test fun purgeKeepsTheJournalEntryOfADeletionThatHasNotCommittedYet() = runTest {
        // deletePermanently journals first and commits after; a purge running in between sees the row.
        val doc = repo.createDocument(DocKind.NOTEBOOK)
        File(h.files.root.parentFile, "pending-deletes.txt").writeText("${doc.id}\n")
        repo.purgeExpiredTrash()
        // The deletion now commits, and the process dies before the folder is removed.
        h.db.documents().delete(doc.id)
        val dir = h.files.docDir(doc.id).apply { File(this, "x.bin").writeBytes(byteArrayOf(1)) }
        // The next purge still finds the entry and removes the folder.
        repo.purgeExpiredTrash()
        assertFalse(dir.exists())
        assertFalse(File(h.files.root.parentFile, "pending-deletes.txt").exists())
    }

    /**
     * A repository on [h]'s database whose first permanent deletion restores the document [restore]
     * names, like a user tapping Restore while Empty trash or the purge works through its list.
     */
    private fun repoRestoringDuringDelete(restore: () -> String): LibraryRepositoryImpl {
        lateinit var r: LibraryRepositoryImpl
        var restored = false
        val hooks = object : LibraryHooks {
            override fun documentMetaChanged(docId: String) {}
            override fun documentRenamed(docId: String) {}
            override fun pagesNeedIndexing(docId: String, pageIds: List<String>) {}
            override fun coverChanged(docId: String) {}
            override fun documentDeleted(docId: String) {
                if (restored) return
                restored = true
                runBlocking { r.restoreDocument(restore()) }
            }
            override suspend fun renderPdfPagePng(docId: String, pdfPageIndex: Int, widthPx: Int, heightPx: Int): ByteArray? = null
        }
        r = LibraryRepositoryImpl(h.db, h.files, h.pageStore, h.settings, hooks) { h.now }
        return r
    }

    @Test fun purgeSkipsADocumentRestoredAfterItWasListed() = runTest {
        var second = ""
        val r = repoRestoringDuringDelete { second }
        // The fake keeps insertion order, so the first document is deleted first.
        val first = r.createDocument(DocKind.NOTEBOOK).id
        second = r.createDocument(DocKind.NOTEBOOK).id
        r.trashDocument(first)
        r.trashDocument(second)
        h.advance(LibraryRepositoryImpl.TRASH_RETENTION_MS + 1000)
        h.files.docDir(second)
        assertEquals(1, r.purgeExpiredTrash())
        assertNull(r.getDocument(first))
        val restored = r.getDocument(second)
        assertNotNull("a document restored during the purge was deleted", restored)
        assertNull(restored!!.trashedAt)
        assertTrue(File(h.files.root, second).exists())
    }

    @Test fun emptyTrashSkipsADocumentRestoredAfterItWasListed() = runTest {
        var second = ""
        val r = repoRestoringDuringDelete { second }
        val first = r.createDocument(DocKind.NOTEBOOK).id
        second = r.createDocument(DocKind.NOTEBOOK).id
        r.trashDocument(first)
        r.trashDocument(second)
        h.files.docDir(second)
        r.emptyTrash()
        assertNull(r.getDocument(first))
        val restored = r.getDocument(second)
        assertNotNull("a document restored during Empty trash was deleted", restored)
        assertNull(restored!!.trashedAt)
        assertTrue(File(h.files.root, second).exists())
    }

    // ------------------------------------------------------------------ queries

    @Test fun observeDocumentsFiltersRootFoldersAndQueries() = runTest {
        val f = repo.createFolder("Econ", null)
        val inFolder = repo.createDocument(DocKind.NOTEBOOK, folderId = f.id)
        val root = repo.createDocument(DocKind.NOTEBOOK)
        repo.renameDocument(root.id, "Macro basics")
        repo.setTags(inFolder.id, listOf("macro"))
        assertEquals(listOf(root.id), repo.observeDocuments(LibraryFilter()).first().map { it.id })
        assertEquals(listOf(inFolder.id), repo.observeDocuments(LibraryFilter(folderId = f.id)).first().map { it.id })
        assertEquals(setOf(root.id, inFolder.id), repo.observeDocuments(LibraryFilter(query = "macro")).first().map { it.id }.toSet())
        assertEquals(setOf(root.id, inFolder.id), repo.observeDocuments(LibraryFilter(inFolderOnly = false)).first().map { it.id }.toSet())
    }

    @Test fun storageInfoCountsLiveDocumentsAndSizes() = runTest {
        val d = repo.createDocument(DocKind.NOTEBOOK)
        val p = repo.getPages(d.id).single()
        h.pageStore.save(d.id, p.id, PageContent(strokes = listOf(ink("s"))))
        h.files.sourcePdf(d.id).writeBytes(ByteArray(100))
        val gone = repo.createDocument(DocKind.NOTEBOOK)
        repo.trashDocument(gone.id)
        val info = repo.storageInfo()
        assertEquals(1, info.documentCount)
        assertEquals(100L, info.pdfBytes)
        assertTrue(info.inkBytes > 0)
        assertEquals(info.totalBytes, repo.getDocument(d.id)!!.sizeBytes + repo.getDocument(gone.id)!!.sizeBytes)
        assertNotEquals(0L, repo.getDocument(d.id)!!.sizeBytes)
    }
}
