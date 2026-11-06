package com.inkwell.notes.core.storage

import com.inkwell.notes.core.model.AudioAnchor
import com.inkwell.notes.core.model.DocKind
import com.inkwell.notes.core.model.ImageItem
import com.inkwell.notes.core.model.OutlineEntry
import com.inkwell.notes.core.model.PageContent
import com.inkwell.notes.core.model.PageSizePreset
import com.inkwell.notes.core.model.PageTemplate
import com.inkwell.notes.core.model.PaperSpec
import com.inkwell.notes.core.model.PaperStyle
import com.inkwell.notes.core.model.PenType
import com.inkwell.notes.core.model.Stroke
import com.inkwell.notes.core.model.TextSource
import com.inkwell.notes.core.storage.db.TextIndexEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class LibraryRepositoryPagesAndFoldersTest {
    private lateinit var h: RepositoryTestHarness
    private val repo get() = h.repo

    @Before fun setUp() { h = RepositoryTestHarness() }
    @After fun tearDown() { h.close() }

    private fun ink(id: String, audio: AudioAnchor? = null) =
        Stroke(id, PenType.BALLPOINT, 0xFF000000.toInt(), 2f, floatArrayOf(1f, 2f, 0.5f, 0f, 0f, 3f, 4f, 0.5f, 0f, 10f), createdAt = 1L, audio = audio)

    private suspend fun indices(docId: String) = repo.getPages(docId).map { it.index }
    private suspend fun ids(docId: String) = repo.getPages(docId).map { it.id }

    // ------------------------------------------------------------------ pages

    @Test fun addPageRenumbersAndKeepsPageCount() = runTest {
        val d = repo.createDocument(DocKind.NOTEBOOK)
        val first = repo.getPages(d.id).single()
        h.advance(10)
        val atEnd = repo.addPage(d.id)
        val atStart = repo.addPage(d.id, atIndex = 0)
        val beyond = repo.addPage(d.id, atIndex = 99)
        val negative = repo.addPage(d.id, atIndex = -3)
        assertEquals(listOf(negative.id, atStart.id, first.id, atEnd.id, beyond.id), ids(d.id))
        assertEquals(listOf(0, 1, 2, 3, 4), indices(d.id))
        assertEquals(1, atEnd.index)
        assertEquals(0, atStart.index)
        val doc = repo.getDocument(d.id)!!
        assertEquals(5, doc.pageCount)
        assertEquals(h.now, doc.modifiedAt)
        assertEquals(listOf(d.id, d.id), h.hooks.coverChanged) // the two inserts at index 0
    }

    @Test fun addedPageLooksLikeItsNeighbourIncludingRotation() = runTest {
        val d = repo.createDocument(DocKind.NOTEBOOK, template = PageTemplate(size = PageSizePreset.A5, paper = PaperSpec(PaperStyle.GRID)))
        val p = repo.getPages(d.id).single()
        repo.rotatePage(p.id, 90)
        val next = repo.addPage(d.id)
        assertEquals(PaperStyle.GRID, next.paper.style)
        assertEquals(0, next.rotation)
        // Shown landscape because the previous page is rotated: the new page is landscape itself.
        assertEquals(595f, next.widthPt)
        assertEquals(420f, next.heightPt)
        assertNull(next.pdfPageIndex)
    }

    @Test fun addPdfPagesClampsSizesAndNumbersPdfIndices() = runTest {
        val d = repo.createDocument(DocKind.PDF, originalName = "x.pdf")
        val pages = repo.addPdfPages(d.id, listOf(612f to 792f, Float.NaN to 10f, 1f to 100_000f), firstPdfIndex = 3)
        assertEquals(listOf(0, 1, 2), pages.map { it.index })
        assertEquals(listOf(3, 4, 5), pages.map { it.pdfPageIndex })
        assertEquals(595f to 842f, pages[1].widthPt to pages[1].heightPt)
        assertEquals(LibraryPages.MIN_PAGE_PT to LibraryPages.MAX_PAGE_PT, pages[2].widthPt to pages[2].heightPt)
        assertEquals(3, repo.getDocument(d.id)!!.pageCount)
        val more = repo.addPdfPages(d.id, listOf(100f to 100f), firstPdfIndex = 0, atIndex = 1)
        assertEquals(1, more.single().index)
        assertEquals(listOf(0, 1, 2, 3), indices(d.id))
        assertEquals(listOf(3, 0, 4, 5), repo.getPages(d.id).map { it.pdfPageIndex })
        assertTrue(repo.addPdfPages(d.id, emptyList()).isEmpty())
    }

    @Test fun deletePageRenumbersRemovesFilesOutlineAndIndex() = runTest {
        val d = repo.createDocument(DocKind.NOTEBOOK)
        val p0 = repo.getPages(d.id).single()
        val p1 = repo.addPage(d.id)
        val p2 = repo.addPage(d.id)
        h.pageStore.save(d.id, p1.id, PageContent(strokes = listOf(ink("s"))))
        h.pageStore.save(d.id, p1.id, PageContent(strokes = listOf(ink("s"), ink("t"))))
        h.files.pageThumbnailFile(d.id, p1.id).writeBytes(byteArrayOf(1))
        repo.upsertOutlineEntry(OutlineEntry("top", d.id, "Top", p0.id))
        repo.upsertOutlineEntry(OutlineEntry("mid", d.id, "Mid", p1.id, parentId = "top"))
        repo.upsertOutlineEntry(OutlineEntry("leaf", d.id, "Leaf", p2.id, parentId = "mid"))
        h.db.textIndex().insertAll(listOf(TextIndexEntity(documentId = d.id, pageId = p1.id, recordingId = null, source = TextSource.HANDWRITING, text = "x", confidence = null, timestampMs = null, bounds = null, engine = "t", updatedAt = 1)))

        repo.deletePage(p1.id)

        assertEquals(listOf(p0.id, p2.id), ids(d.id))
        assertEquals(listOf(0, 1), indices(d.id))
        assertEquals(2, repo.getDocument(d.id)!!.pageCount)
        assertFalse(h.files.pageFile(d.id, p1.id).exists())
        assertFalse(FileStore.backupOf(h.files.pageFile(d.id, p1.id)).exists())
        assertFalse(h.files.pageThumbnailFile(d.id, p1.id).exists())
        val outline = repo.observeOutline(d.id).first()
        assertEquals(setOf("top", "leaf"), outline.map { it.id }.toSet())
        assertEquals("top", outline.single { it.id == "leaf" }.parentId)
        assertTrue(h.db.textIndex().forDocument(d.id).isEmpty())
        // Deleting an unknown page is harmless.
        repo.deletePage("nope")
        assertEquals(2, repo.getDocument(d.id)!!.pageCount)
    }

    @Test fun deletingTheCoverPageRefreshesTheThumbnail() = runTest {
        val d = repo.createDocument(DocKind.NOTEBOOK)
        val p0 = repo.getPages(d.id).single()
        repo.addPage(d.id)
        h.hooks.coverChanged.clear()
        repo.deletePage(p0.id)
        assertEquals(listOf(d.id), h.hooks.coverChanged)
    }

    @Test fun duplicatePageGoesRightAfterItsSourceAndKeepsAudioLinks() = runTest {
        val d = repo.createDocument(DocKind.NOTEBOOK)
        val p0 = repo.getPages(d.id).single()
        val p1 = repo.addPage(d.id)
        val anchor = AudioAnchor("rec", 500)
        h.pageStore.save(d.id, p0.id, PageContent(strokes = listOf(ink("s", anchor))))
        repo.setBookmark(p0.id, true, "B")
        val copy = repo.duplicatePage(p0.id)
        assertEquals(listOf(p0.id, copy.id, p1.id), ids(d.id))
        assertEquals(listOf(0, 1, 2), indices(d.id))
        assertEquals(1, copy.index)
        assertFalse("bookmarks are not duplicated", copy.bookmarked)
        assertEquals(anchor, h.pageStore.load(d.id, copy.id).strokes.single().audio)
        assertEquals(3, repo.getDocument(d.id)!!.pageCount)
    }

    @Test fun copyPageToAnotherDocumentStripsAudioCopiesAssetsAndIndex() = runTest {
        val src = repo.createDocument(DocKind.NOTEBOOK)
        val dst = repo.createDocument(DocKind.NOTEBOOK)
        val sp = repo.getPages(src.id).single()
        val dp = repo.getPages(dst.id).single()
        h.pageStore.save(src.id, sp.id, PageContent(strokes = listOf(ink("s", AudioAnchor("rec", 1))), images = listOf(ImageItem("i", "img.png", 0f, 0f, 1f, 1f))))
        h.files.assetFile(src.id, "img.png").writeBytes(byteArrayOf(7))
        h.db.textIndex().insertAll(listOf(TextIndexEntity(documentId = src.id, pageId = sp.id, recordingId = null, source = TextSource.HANDWRITING, text = "hi", confidence = null, timestampMs = null, bounds = null, engine = "t", updatedAt = 1)))
        h.db.pages().markIndexed(sp.id, h.now + 1)

        val copy = repo.copyPageToDocument(sp.id, dst.id, atIndex = 0)

        assertEquals(listOf(copy.id, dp.id), ids(dst.id))
        assertEquals(0, copy.index)
        assertEquals(2, repo.getDocument(dst.id)!!.pageCount)
        val content = h.pageStore.load(dst.id, copy.id)
        assertNull(content.strokes.single().audio)
        assertTrue(h.files.assetFile(dst.id, "img.png").readBytes().contentEquals(byteArrayOf(7)))
        assertEquals(listOf(copy.id), h.db.textIndex().forDocument(dst.id).map { it.pageId })
        assertTrue(h.hooks.needIndexing.isEmpty())
        assertEquals(1, repo.getDocument(src.id)!!.pageCount)
    }

    @Test fun copyingAPdfPageIntoADocumentWithoutAPdfBringsThePdfAlong() = runTest {
        val src = repo.createDocument(DocKind.PDF, originalName = "a.pdf")
        h.files.sourcePdf(src.id).writeText("%PDF-A")
        val sp = repo.addPdfPages(src.id, listOf(600f to 800f), firstPdfIndex = 2).single()
        val dst = repo.createDocument(DocKind.NOTEBOOK)
        val copy = repo.copyPageToDocument(sp.id, dst.id)
        assertEquals(2, copy.pdfPageIndex)
        assertEquals("%PDF-A", h.files.sourcePdf(dst.id).readText())
        assertTrue(h.hooks.pdfRenders.isEmpty())
    }

    @Test fun copyingAPdfPageIntoADocumentWithAnotherPdfFlattensIt() = runTest {
        val src = repo.createDocument(DocKind.PDF, originalName = "a.pdf")
        h.files.sourcePdf(src.id).writeText("%PDF-A")
        val sp = repo.addPdfPages(src.id, listOf(600f to 800f), firstPdfIndex = 2).single()
        val dst = repo.createDocument(DocKind.PDF, originalName = "b.pdf")
        h.files.sourcePdf(dst.id).writeText("%PDF-B")
        repo.addPdfPages(dst.id, listOf(600f to 800f))
        h.hooks.pdfPng = byteArrayOf(1, 2, 3)
        h.hooks.needIndexing.clear()

        val copy = repo.copyPageToDocument(sp.id, dst.id)

        assertNull(copy.pdfPageIndex)
        assertEquals("%PDF-B", h.files.sourcePdf(dst.id).readText())
        val render = h.hooks.pdfRenders.single()
        assertEquals(src.id to 2, render.first to render.second)
        assertEquals(1500 to 2000, render.third)
        val content = h.pageStore.load(dst.id, copy.id)
        val bg = content.images.single()
        assertEquals(600f to 800f, bg.width to bg.height)
        assertTrue(h.files.assetFile(dst.id, bg.assetId).readBytes().contentEquals(byteArrayOf(1, 2, 3)))
        // A never-indexed PDF page is handed to the indexer.
        assertEquals(listOf(dst.id to listOf(copy.id)), h.hooks.needIndexing)
    }

    @Test fun flatteningFailureStillCopiesThePage() = runTest {
        val src = repo.createDocument(DocKind.PDF, originalName = "a.pdf")
        h.files.sourcePdf(src.id).writeText("%PDF-A")
        val sp = repo.addPdfPages(src.id, listOf(600f to 800f)).single()
        val dst = repo.createDocument(DocKind.PDF, originalName = "b.pdf")
        h.files.sourcePdf(dst.id).writeText("%PDF-B")
        h.hooks.pdfPng = null
        val copy = repo.copyPageToDocument(sp.id, dst.id)
        assertNull(copy.pdfPageIndex)
        assertEquals(1, repo.getDocument(dst.id)!!.pageCount)
        assertEquals(listOf(copy.id), ids(dst.id))
    }

    @Test fun copyToAMissingDocumentFailsWithoutLeavingFiles() = runTest {
        val src = repo.createDocument(DocKind.NOTEBOOK)
        val sp = repo.getPages(src.id).single()
        h.pageStore.save(src.id, sp.id, PageContent(strokes = listOf(ink("s"))))
        try {
            repo.copyPageToDocument(sp.id, "ghost")
            throw AssertionError("expected failure")
        } catch (e: IllegalArgumentException) {
            // expected
        }
        assertFalse(File(h.files.root, "ghost").exists())
    }

    @Test fun moveAndReorderPagesKeepEveryPage() = runTest {
        val d = repo.createDocument(DocKind.NOTEBOOK)
        val a = repo.getPages(d.id).single()
        val b = repo.addPage(d.id)
        val c = repo.addPage(d.id)
        repo.movePage(c.id, 0)
        assertEquals(listOf(c.id, a.id, b.id), ids(d.id))
        repo.movePage(c.id, 99)
        assertEquals(listOf(a.id, b.id, c.id), ids(d.id))
        repo.movePage("ghost", 0)
        assertEquals(listOf(a.id, b.id, c.id), ids(d.id))
        // A partial/stale reorder request never loses pages.
        repo.reorderPages(d.id, listOf(b.id, "ghost", b.id))
        assertEquals(listOf(b.id, a.id, c.id), ids(d.id))
        assertEquals(listOf(0, 1, 2), indices(d.id))
        assertEquals(3, repo.getDocument(d.id)!!.pageCount)
        repo.reorderPages(d.id, emptyList())
        assertEquals(listOf(b.id, a.id, c.id), ids(d.id))
    }

    @Test fun rotationNormalisesAndVisualChangesKeepTheIndexFresh() = runTest {
        val d = repo.createDocument(DocKind.NOTEBOOK)
        val p = repo.getPages(d.id).single()
        repo.rotatePage(p.id, 90)
        repo.rotatePage(p.id, 90)
        repo.rotatePage(p.id, -270)
        assertEquals(270, repo.getPage(p.id)!!.rotation)
        repo.rotatePage(p.id, 100)
        assertEquals(0, repo.getPage(p.id)!!.rotation)
        assertTrue("blank pages stay indexed after visual-only changes", h.db.pages().needingIndex().isEmpty())
        assertEquals(listOf(d.id, d.id, d.id, d.id), h.hooks.coverChanged)
    }

    @Test fun pageSizeAndPaperRules() = runTest {
        val d = repo.createDocument(DocKind.PDF, originalName = "x.pdf")
        val pdf = repo.addPdfPages(d.id, listOf(600f to 800f)).single()
        val blank = repo.addPage(d.id)
        repo.setPageSize(blank.id, 1f, 1f)
        assertEquals(LibraryPages.MIN_PAGE_PT, repo.getPage(blank.id)!!.widthPt)
        repo.setPageSize(blank.id, Float.NaN, 100f)
        assertEquals(LibraryPages.MIN_PAGE_PT, repo.getPage(blank.id)!!.widthPt)
        repo.setPageSize(blank.id, 0f, 0f)
        assertTrue(repo.getPage(blank.id)!!.isInfinite)
        repo.setPaperForAll(d.id, PaperSpec(PaperStyle.DOT_GRID))
        assertEquals(PaperStyle.BLANK, repo.getPage(pdf.id)!!.paper.style)
        assertEquals(PaperStyle.DOT_GRID, repo.getPage(blank.id)!!.paper.style)
        repo.setBookmark(blank.id, true, "  Chapter 1 ")
        assertEquals("Chapter 1", repo.getPage(blank.id)!!.bookmarkTitle)
        repo.setBookmark(blank.id, false)
        assertNull(repo.getPage(blank.id)!!.bookmarkTitle)
    }

    @Test fun pageContentChangedBumpsPageAndDocument() = runTest {
        val d = repo.createDocument(DocKind.NOTEBOOK)
        val p = repo.getPages(d.id).single()
        h.advance(777)
        repo.pageContentChanged(d.id, p.id)
        assertEquals(h.now, repo.getPage(p.id)!!.modifiedAt)
        assertEquals(h.now, repo.getDocument(d.id)!!.modifiedAt)
        assertEquals(listOf(p.id), h.db.pages().needingIndex().map { it.id })
    }

    @Test fun outlineEntriesAreValidated() = runTest {
        val d = repo.createDocument(DocKind.NOTEBOOK)
        val other = repo.createDocument(DocKind.NOTEBOOK)
        val p = repo.getPages(d.id).single()
        val op = repo.getPages(other.id).single()
        repo.upsertOutlineEntry(OutlineEntry("", d.id, "Auto id", p.id))
        repo.upsertOutlineEntry(OutlineEntry("x", d.id, "   ", p.id)) // blank title rejected
        repo.upsertOutlineEntry(OutlineEntry("y", d.id, "Wrong page", op.id)) // page of another document
        repo.upsertOutlineEntry(OutlineEntry("self", d.id, "Self parent", p.id, parentId = "self"))
        val entries = repo.observeOutline(d.id).first()
        assertEquals(setOf("Auto id", "Self parent"), entries.map { it.title }.toSet())
        assertTrue(entries.single { it.title == "Auto id" }.id.isNotBlank())
        assertNull(entries.single { it.title == "Self parent" }.parentId)
    }

    // ------------------------------------------------------------------ folders

    @Test fun folderNamesAreUniqueAmongSiblingsOnly() = runTest {
        val a = repo.createFolder("Econ", null)
        val b = repo.createFolder("econ", null)
        val c = repo.createFolder("Econ", a.id)
        val blank = repo.createFolder("   ", null)
        assertEquals("Econ", a.name)
        assertEquals("econ (2)", b.name)
        assertEquals("Econ", c.name)
        assertEquals(LibraryRepositoryImpl.DEFAULT_FOLDER_NAME, blank.name)
        assertEquals("Econ / Econ", repo.folderPath(c.id))
        assertEquals("", repo.folderPath(null))
        assertEquals("", repo.folderPath("ghost"))
    }

    @Test fun movingAFolderIntoItsOwnSubtreeIsRefused() = runTest {
        val a = repo.createFolder("A", null)
        val b = repo.createFolder("B", a.id)
        val c = repo.createFolder("C", b.id)
        repo.moveFolder(a.id, c.id)
        assertNull(repo.getFolder(a.id)!!.parentId)
        repo.moveFolder(a.id, a.id)
        assertNull(repo.getFolder(a.id)!!.parentId)
        repo.updateFolder(repo.getFolder(a.id)!!.copy(parentId = b.id, name = "A2"))
        val after = repo.getFolder(a.id)!!
        assertNull("cycle via updateFolder must fall back to the current parent", after.parentId)
        assertEquals("A2", after.name)
        // Moving to a trashed or unknown folder is refused too.
        val t = repo.createFolder("T", null)
        repo.trashFolder(t.id)
        repo.moveFolder(c.id, t.id)
        assertEquals(b.id, repo.getFolder(c.id)!!.parentId)
        repo.moveFolder(c.id, "ghost")
        assertEquals(b.id, repo.getFolder(c.id)!!.parentId)
    }

    @Test fun movingAFolderRenamesItWhenASiblingHasTheSameNameAndReindexesItsDocuments() = runTest {
        val a = repo.createFolder("A", null)
        val x1 = repo.createFolder("X", null)
        val x2 = repo.createFolder("X", a.id)
        val d = repo.createDocument(DocKind.NOTEBOOK, folderId = x2.id)
        h.hooks.metaChanged.clear()
        repo.moveFolder(x2.id, null)
        assertEquals("X (2)", repo.getFolder(x2.id)!!.name)
        assertNotNull(repo.getFolder(x1.id))
        assertEquals(listOf(d.id), h.hooks.metaChanged)
        assertEquals(listOf(d.id), h.hooks.moved)
    }

    @Test fun trashingAFolderTrashesItsSubtreeAndRestoreOnlyBringsBackWhatWentTogether() = runTest {
        val a = repo.createFolder("A", null)
        val b = repo.createFolder("B", a.id)
        val docA = repo.createDocument(DocKind.NOTEBOOK, folderId = a.id)
        val docB = repo.createDocument(DocKind.NOTEBOOK, folderId = b.id)
        val earlier = repo.createDocument(DocKind.NOTEBOOK, folderId = b.id)
        repo.trashDocument(earlier.id)
        h.advance(1000)
        repo.trashFolder(a.id)
        assertNotNull(repo.getFolder(b.id)!!.trashedAt)
        assertNotNull(repo.getDocument(docA.id)!!.trashedAt)
        assertNotNull(repo.getDocument(docB.id)!!.trashedAt)
        assertTrue(repo.observeFolders().first().isEmpty())
        h.advance(1000)
        repo.restoreFolder(a.id)
        assertNull(repo.getFolder(a.id)!!.trashedAt)
        assertNull(repo.getFolder(b.id)!!.trashedAt)
        assertNull(repo.getDocument(docA.id)!!.trashedAt)
        assertNull(repo.getDocument(docB.id)!!.trashedAt)
        assertNotNull("trashed separately before: stays in the trash", repo.getDocument(earlier.id)!!.trashedAt)
    }

    @Test fun restoringANestedFolderRevivesItsParentChain() = runTest {
        val a = repo.createFolder("A", null)
        val b = repo.createFolder("B", a.id)
        val c = repo.createFolder("C", b.id)
        repo.trashFolder(a.id)
        repo.restoreFolder(c.id)
        assertNull(repo.getFolder(c.id)!!.trashedAt)
        assertNull(repo.getFolder(b.id)!!.trashedAt)
        assertNull(repo.getFolder(a.id)!!.trashedAt)
        assertEquals("A / B / C", repo.folderPath(c.id))
    }

    @Test fun restoringAFolderRenamesItWhenANewSiblingTookItsName() = runTest {
        val old = repo.createFolder("Biology", null)
        val d = repo.createDocument(DocKind.NOTEBOOK, folderId = old.id)
        repo.trashFolder(old.id)
        val fresh = repo.createFolder("Biology", null)
        assertEquals("Biology", fresh.name)
        h.hooks.metaChanged.clear()
        h.hooks.moved.clear()
        repo.restoreFolder(old.id)
        assertEquals("Biology (2)", repo.getFolder(old.id)!!.name)
        assertEquals("Biology", repo.getFolder(fresh.id)!!.name)
        assertEquals(listOf(d.id), h.hooks.metaChanged)
        // Its folder path changed: sync must move the copies, not only search.
        assertEquals(listOf(d.id), h.hooks.moved)
    }

    @Test fun restoringADocumentRenamesARevivedFolderWhenANewSiblingTookItsName() = runTest {
        val a = repo.createFolder("A", null)
        val b = repo.createFolder("B", a.id)
        val d = repo.createDocument(DocKind.NOTEBOOK, folderId = b.id)
        repo.trashFolder(a.id)
        repo.createFolder("A", null)
        h.hooks.moved.clear()
        repo.restoreDocument(d.id)
        assertNull(repo.getFolder(a.id)!!.trashedAt)
        assertEquals("A (2) / B", repo.folderPath(b.id))
        assertEquals(b.id, repo.getDocument(d.id)!!.folderId)
        // Its folder path changed with the revived folder's new name: sync moves its copies.
        assertEquals(listOf(d.id), h.hooks.moved)
    }

    @Test fun emptyingTheTrashReRootsLiveContentOfDeletedFolders() = runTest {
        val a = repo.createFolder("A", null)
        val b = repo.createFolder("B", a.id)
        val d = repo.createDocument(DocKind.NOTEBOOK, folderId = b.id)
        repo.trashFolder(a.id)
        // The user restores the document alone (which revives its folder chain), then trashes A again
        // directly in the database the way a stale screen might: B and d stay live inside a trashed A.
        repo.restoreDocument(d.id)
        h.db.folders().upsert(com.inkwell.notes.core.storage.db.FolderEntity.from(repo.getFolder(a.id)!!.copy(trashedAt = h.now)))
        repo.emptyTrash()
        assertNull(repo.getFolder(a.id))
        assertNotNull(repo.getFolder(b.id))
        assertNull(repo.getFolder(b.id)!!.parentId)
        assertNotNull(repo.getDocument(d.id))
        assertEquals(b.id, repo.getDocument(d.id)!!.folderId)
    }

    @Test fun updateFolderRenamesUniquelyAndNotifiesDocuments() = runTest {
        val a = repo.createFolder("A", null)
        repo.createFolder("B", null)
        val d = repo.createDocument(DocKind.NOTEBOOK, folderId = a.id)
        h.hooks.metaChanged.clear()
        repo.updateFolder(repo.getFolder(a.id)!!.copy(name = "b", color = 5))
        val after = repo.getFolder(a.id)!!
        assertEquals("b (2)", after.name)
        assertEquals(5, after.color)
        assertEquals(listOf(d.id), h.hooks.metaChanged)
        assertEquals(listOf(d.id), h.hooks.moved)
        // Colour-only change: no rename, no notification.
        h.hooks.metaChanged.clear()
        repo.updateFolder(after.copy(color = 6))
        assertEquals("b (2)", repo.getFolder(a.id)!!.name)
        assertTrue(h.hooks.metaChanged.isEmpty())
    }
}
