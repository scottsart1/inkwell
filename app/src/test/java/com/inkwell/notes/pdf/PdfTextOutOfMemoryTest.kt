package com.inkwell.notes.pdf

import com.inkwell.notes.core.storage.UnreadablePageException
import com.inkwell.notes.ocr.work.PdfTextFallback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/**
 * A source PDF whose text extraction keeps running out of memory is given up on (its pages are
 * OCRed instead of retried forever), and split/extract refuse to copy an unreadable page as blank.
 */
class PdfTextOutOfMemoryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test fun failuresAreCountedPerVersionOfTheFile() {
        val oom = PdfTextOutOfMemory()
        assertEquals(0, oom.count("d", 100, 1))
        assertEquals(1, oom.record("d", 100, 1))
        assertFalse(oom.givenUp("d", 100, 1))
        assertEquals(2, oom.record("d", 100, 1))
        assertTrue(oom.givenUp("d", 100, 1))
        // Another document, or a replaced file, starts from zero.
        assertFalse(oom.givenUp("other", 100, 1))
        assertFalse(oom.givenUp("d", 200, 1))
        assertFalse(oom.givenUp("d", 100, 2))
        assertEquals(1, oom.record("d", 200, 1))
        assertFalse(oom.givenUp("d", 100, 1))
    }

    @Test fun heavyPagesAreGivenUpOnOneByOneNotTheWholeFile() {
        val oom = PdfTextOutOfMemory()
        // Two heavy pages, each running out of memory twice.
        oom.record("d", 100, 1, pdfPageIndex = 3)
        oom.record("d", 100, 1, pdfPageIndex = 5)
        assertFalse(oom.givenUp("d", 100, 1, pdfPageIndex = 3))
        oom.record("d", 100, 1, pdfPageIndex = 3)
        oom.record("d", 100, 1, pdfPageIndex = 5)
        assertTrue(oom.givenUp("d", 100, 1, pdfPageIndex = 3))
        assertTrue(oom.givenUp("d", 100, 1, pdfPageIndex = 5))
        // The file and its other pages are still read.
        assertFalse(oom.givenUp("d", 100, 1))
        assertFalse(oom.givenUp("d", 100, 1, pdfPageIndex = 4))
        assertEquals(0, oom.count("d", 100, 1))
        // A replaced file starts from zero for its pages too.
        assertFalse(oom.givenUp("d", 200, 1, pdfPageIndex = 3))
        // A file that cannot be loaded gives up on every page.
        oom.record("e", 100, 1)
        oom.record("e", 100, 1)
        assertTrue(oom.givenUp("e", 100, 1, pdfPageIndex = 0))
    }

    @Test fun aFileWhosePagesRunOutOfMemoryOneAfterAnotherIsGivenUpAsAWhole() {
        val oom = PdfTextOutOfMemory()
        // A heavy resource every page shares: pages 0, 1 and 2 each run out of memory once.
        oom.record("d", 100, 1, pdfPageIndex = 0)
        oom.record("d", 100, 1, pdfPageIndex = 1)
        assertFalse(oom.givenUp("d", 100, 1, pdfPageIndex = 7))
        oom.record("d", 100, 1, pdfPageIndex = 2)
        assertTrue(oom.givenUp("d", 100, 1))
        assertTrue(oom.givenUp("d", 100, 1, pdfPageIndex = 7))
        // Another version of the file starts again.
        assertFalse(oom.givenUp("d", 100, 2, pdfPageIndex = 7))
    }

    @Test fun aPageReadInBetweenKeepsTheFileAlive() {
        val oom = PdfTextOutOfMemory()
        oom.record("d", 100, 1, pdfPageIndex = 0)
        oom.record("d", 100, 1, pdfPageIndex = 1)
        oom.recordSuccess("d")
        oom.record("d", 100, 1, pdfPageIndex = 5)
        assertFalse(oom.givenUp("d", 100, 1))
        assertFalse(oom.givenUp("d", 100, 1, pdfPageIndex = 6))
    }

    @Test fun givenUpPagesKeepTheirPdfTextWhenNothingWouldReplaceIt() {
        // The failure may pass and there are rows to keep: retry later.
        assertEquals(PdfTextFallback.Plan.RETRY_LATER, PdfTextFallback.plan(indexedBefore = true, givenUp = false, autoOcr = true))
        assertEquals(PdfTextFallback.Plan.RETRY_LATER, PdfTextFallback.plan(indexedBefore = true, givenUp = false, autoOcr = false))
        // OCR replaces the text layer when it runs.
        assertEquals(PdfTextFallback.Plan.OCR, PdfTextFallback.plan(indexedBefore = true, givenUp = true, autoOcr = true))
        assertEquals(PdfTextFallback.Plan.OCR, PdfTextFallback.plan(indexedBefore = false, givenUp = false, autoOcr = true))
        // Given up without OCR: the earlier PDF text stays searchable.
        assertEquals(PdfTextFallback.Plan.KEEP_PDF_TEXT, PdfTextFallback.plan(indexedBefore = true, givenUp = true, autoOcr = false))
        // Never indexed and no OCR: nothing to keep.
        assertEquals(PdfTextFallback.Plan.NO_TEXT, PdfTextFallback.plan(indexedBefore = false, givenUp = true, autoOcr = false))
        assertEquals(PdfTextFallback.Plan.NO_TEXT, PdfTextFallback.plan(indexedBefore = false, givenUp = false, autoOcr = false))
    }

    @Test fun extractorStopsLoadingAFileThatKeepsRunningOutOfMemory() = runBlocking<Unit> {
        val dir = tmp.newFolder("work")
        val source = File(dir, "source.pdf").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        var loads = 0
        val ops = PdfBoxOps(initialise = { loads++; throw OutOfMemoryError("test") }, tempDir = { dir })
        val scope = CoroutineScope(Job())
        try {
            val extractor = PdfTextExtractor(ops, sourceFile = { source }, scope = scope)
            assertNull(extractor.pageTextOrNull("d", 0))
            assertFalse(extractor.givenUp("d", 0))
            assertNull(extractor.pageTextOrNull("d", 1))
            // Loading the file itself ran out of memory: every page is given up.
            assertTrue(extractor.givenUp("d", 0))
            assertTrue(extractor.givenUp("d", 7))
            // Given up: the file is not loaded again.
            assertNull(extractor.pageTextOrNull("d", 2))
            assertEquals(2, loads)
            // A replaced source file is tried again.
            source.writeBytes(byteArrayOf(1, 2, 3, 4))
            assertFalse(extractor.givenUp("d", 0))
            assertNull(extractor.pageTextOrNull("d", 0))
            assertEquals(3, loads)
        } finally {
            scope.cancel()
        }
    }

    @Test fun pagesWithNothingToKeepOrAGivenUpFileAreOcredInstead() {
        // Indexed before and the failure may pass: keep the earlier rows and retry later.
        assertFalse(PdfTextFallback.ocrInstead(indexedBefore = true, givenUp = false))
        // Never indexed: nothing to keep, so OCR now instead of leaving the page unsearchable.
        assertTrue(PdfTextFallback.ocrInstead(indexedBefore = false, givenUp = false))
        // The file keeps running out of memory: retrying would never succeed.
        assertTrue(PdfTextFallback.ocrInstead(indexedBefore = true, givenUp = true))
        assertTrue(PdfTextFallback.ocrInstead(indexedBefore = false, givenUp = true))
    }

    @Test fun unreadablePagesAbortCopiesWithTheirOwnError() {
        assertEquals(PdfError.PAGE_UNREADABLE, PdfErrors.writeError(UnreadablePageException("p1")))
        assertEquals(PdfError.SAVE_FAILED, PdfErrors.writeError(IOException("Broken pipe")))
        assertEquals(PdfError.NO_SPACE, PdfErrors.writeError(IOException("write failed: ENOSPC (No space left on device)")))
        assertEquals(PdfError.TOO_LARGE, PdfErrors.writeError(OutOfMemoryError()))
    }
}
