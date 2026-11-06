package com.inkwell.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/** Files shared with or opened in Inkwell: every one is imported by its own type, and the user hears about the rest. */
class SharedImportTest {
    private fun split(vararg items: Pair<String, String?>, sharedType: String? = null) =
        SharedImport.split(items.toList()) { SharedImport.mimeOf(it.second, sharedType) }.let { (images, rest) ->
            images.map { it.first } to rest.map { it.first }
        }

    @Test fun severalPdfsAreAllImported() {
        val (images, pdfs) = split("a" to "application/pdf", "b" to "application/pdf", "c" to "application/pdf")
        assertEquals(emptyList<String>(), images)
        assertEquals(listOf("a", "b", "c"), pdfs)
    }

    @Test fun aMixedShareSendsEachItemToItsOwnImporter() {
        // Starting with an image used to send the PDFs to the image importer too.
        val (images, pdfs) = split("photo" to "image/jpeg", "doc" to "application/pdf", "scan" to "IMAGE/PNG")
        assertEquals(listOf("photo", "scan"), images)
        assertEquals(listOf("doc"), pdfs)
    }

    @Test fun anItemWithoutItsOwnTypeFallsBackToTheShareType() {
        assertEquals("image/*", SharedImport.mimeOf(null, "image/*"))
        assertEquals("application/pdf", SharedImport.mimeOf("application/pdf", "image/*"))
        assertEquals("", SharedImport.mimeOf(null, null))
        val (images, pdfs) = split("a" to null, "b" to "application/pdf", sharedType = "image/*")
        assertEquals(listOf("a"), images)
        assertEquals(listOf("b"), pdfs)
    }

    @Test fun oneDocumentThatOpensNeedsNoMessage() {
        assertNull(SharedImport.outcome(imported = 1, total = 1, documents = 1, error = null))
        // Three photos become one document.
        assertNull(SharedImport.outcome(imported = 3, total = 3, documents = 1, error = null))
    }

    @Test fun severalDocumentsAndFailuresAreCounted() {
        assertEquals(SharedImport.Outcome.Imported(3), SharedImport.outcome(imported = 3, total = 3, documents = 3, error = null))
        assertEquals(SharedImport.Outcome.Partial(2, 3), SharedImport.outcome(imported = 2, total = 3, documents = 2, error = "bad"))
        assertEquals(SharedImport.Outcome.Failed("bad"), SharedImport.outcome(imported = 0, total = 2, documents = 0, error = "bad"))
        assertEquals(SharedImport.Outcome.Failed(null), SharedImport.outcome(imported = 0, total = 1, documents = 0, error = null))
    }

    @Test fun aFullyImportedMixedShareCountsFilesNotDocuments() {
        // 4 photos (one document) and 2 PDFs: "Imported 6 files", not 3, like the partial message.
        assertEquals(SharedImport.Outcome.Imported(6), SharedImport.outcome(imported = 6, total = 6, documents = 3, error = null))
        assertEquals(SharedImport.Outcome.Partial(5, 6), SharedImport.outcome(imported = 5, total = 6, documents = 2, error = "bad"))
    }

    @Test fun inkwellIsNotOfferedForSharedText() {
        // Shared text has nowhere to go: the app must not appear as a share target for it.
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertFalse(manifest.contains("android:mimeType=\"text/plain\""))
    }
}
