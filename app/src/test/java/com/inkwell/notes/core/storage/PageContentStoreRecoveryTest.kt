package com.inkwell.notes.core.storage

import android.content.ContextWrapper
import com.inkwell.notes.core.model.InkPoints
import com.inkwell.notes.core.model.PageContent
import com.inkwell.notes.core.model.PenType
import com.inkwell.notes.core.model.Stroke
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Crash and corruption scenarios of the page content files. */
class PageContentStoreRecoveryTest {
    private lateinit var tmp: File
    private lateinit var files: FileStore
    private lateinit var store: PageContentStore

    @Before fun setUp() {
        tmp = Files.createTempDirectory("inkwell-pages").toFile()
        val context = object : ContextWrapper(null) {
            override fun getFilesDir(): File = File(tmp, "files").apply { mkdirs() }
            override fun getCacheDir(): File = File(tmp, "cache").apply { mkdirs() }
        }
        files = FileStore(context)
        store = PageContentStore(files)
    }

    @After fun tearDown() { tmp.deleteRecursively() }

    private fun stroke(id: String, n: Int = 20): Stroke {
        val p = FloatArray(n * InkPoints.STRIDE) { (it * 0.37f) }
        return Stroke(id, PenType.BALLPOINT, 0xFF000000.toInt(), 2f, p, createdAt = 1L)
    }

    private fun page(vararg ids: String) = PageContent(strokes = ids.map { stroke(it) })

    @Test fun saveKeepsThePreviousVersionAsBackup() = runTest {
        store.save("d", "p", page("a"))
        store.save("d", "p", page("a", "b"))
        val f = files.pageFile("d", "p")
        assertTrue(f.exists())
        assertTrue(FileStore.backupOf(f).exists())
        assertEquals(listOf("a"), PageContentStore.decodeFile(FileStore.backupOf(f).readBytes())!!.strokes.map { it.id })
        assertFalse(File(f.parentFile, f.name + ".tmp").exists())
    }

    @Test fun garbageInTheMainFileFallsBackToTheBackup() = runTest {
        store.save("d", "p", page("a"))
        store.save("d", "p", page("a", "b"))
        files.pageFile("d", "p").writeBytes(ByteArray(64) { 0x7f })
        assertEquals(listOf("a"), store.load("d", "p").strokes.map { it.id })
    }

    @Test fun truncatedMainFileFallsBackToTheBackupEvenWhenCutAtARecordBoundary() = runTest {
        // Protobuf is self-delimiting per field: a file cut exactly between two strokes still decodes,
        // silently as fewer strokes. The store must notice that the file is not what was written.
        store.save("d", "p", page("a"))
        val full = page("a", "b", "c")
        store.save("d", "p", full)
        val f = files.pageFile("d", "p")
        val bytes = f.readBytes()
        val oneStroke = PageContentStore.encode(page("a")).size
        val twoStrokes = PageContentStore.encode(page("a", "b")).size
        assertTrue(oneStroke < twoStrokes && twoStrokes < bytes.size)
        // Cut the current file so that it holds exactly the first two strokes of the three.
        f.writeBytes(bytes.copyOf(bytes.size - (bytes.size - twoStrokes)))
        val loaded = store.load("d", "p")
        assertTrue(
            "truncated file silently loaded as ${loaded.strokes.map { it.id }}",
            loaded.strokes.map { it.id } == listOf("a", "b", "c") || loaded.strokes.map { it.id } == listOf("a"),
        )
    }

    @Test fun zeroLengthMainFileWithABackupRecoversTheBackup() = runTest {
        // A crash between creating the file and writing it (or an fs truncation) leaves 0 bytes.
        store.save("d", "p", page("a"))
        store.save("d", "p", page("a", "b"))
        files.pageFile("d", "p").writeBytes(ByteArray(0))
        val loaded = store.load("d", "p")
        assertFalse("0-byte page file was treated as an intentionally empty page although a backup exists", loaded.isEmpty)
    }

    @Test fun deliberatelyEmptiedPageLoadsEmptyNotTheBackup() = runTest {
        store.save("d", "p", page("a"))
        store.save("d", "p", PageContent.EMPTY) // user erased everything
        assertTrue(store.load("d", "p").isEmpty)
        // And survives a second save/load cycle (an empty page must stay empty).
        store.save("d", "p", PageContent.EMPTY)
        assertTrue(store.load("d", "p").isEmpty)
    }

    @Test fun crashAfterRenamingToBackupButBeforeRenamingTmpLosesAtMostOneEdit() = runTest {
        store.save("d", "p", page("a"))
        val f = files.pageFile("d", "p")
        // Simulate: previous version moved to .bak, new version complete in .tmp, main file missing.
        val bak = FileStore.backupOf(f)
        f.renameTo(bak)
        File(f.parentFile, f.name + ".tmp").writeBytes(PageContentStore.encode(page("a", "b")))
        val loaded = store.load("d", "p")
        assertTrue("got ${loaded.strokes.map { it.id }}", loaded.strokes.map { it.id } == listOf("a") || loaded.strokes.map { it.id } == listOf("a", "b"))
        // Saving again must work and clean up.
        store.save("d", "p", page("a", "b", "c"))
        assertEquals(listOf("a", "b", "c"), store.load("d", "p").strokes.map { it.id })
    }

    @Test fun framingDetectsTruncationCorruptionAndPassesLegacyBytesThrough() {
        val payload = PageContentStore.encode(page("a", "b"))
        val framed = PageContentStore.frame(payload)
        assertEquals(PageContentStore.HEADER_BYTES + payload.size, framed.size)
        assertTrue(PageContentStore.isFramed(framed))
        assertTrue(PageContentStore.unframe(framed)!!.contentEquals(payload))
        assertEquals(null, PageContentStore.unframe(framed.copyOf(framed.size - 1)))
        assertEquals(null, PageContentStore.unframe(framed + byteArrayOf(0)))
        val flipped = framed.copyOf().also { it[PageContentStore.HEADER_BYTES + 3] = (it[PageContentStore.HEADER_BYTES + 3] + 1).toByte() }
        assertEquals(null, PageContentStore.unframe(flipped))
        assertEquals("cut inside the header", null, PageContentStore.unframe(framed.copyOf(5)))
        // An empty page is a header with no payload, never a 0-byte file.
        val empty = PageContentStore.frame(ByteArray(0))
        assertEquals(PageContentStore.HEADER_BYTES, empty.size)
        assertTrue(PageContentStore.decodeFile(empty)!!.isEmpty)
        assertEquals(null, PageContentStore.decodeFile(ByteArray(0)))
        assertEquals(null, PageContentStore.decodeFile(null))
        // Legacy raw protobuf passes through unchanged.
        assertTrue(PageContentStore.unframe(payload)!!.contentEquals(payload))
        assertEquals(listOf("a", "b"), PageContentStore.decodeFile(payload)!!.strokes.map { it.id })
    }

    @Test fun missingFilesLoadAsEmpty() = runTest {
        assertTrue(store.load("none", "none").isEmpty)
    }

    @Test fun legacyRawProtobufFilesStillLoad() = runTest {
        // Files written before any framing/checksum (plain protobuf bytes) must still be readable.
        val f = files.pageFile("d", "legacy")
        f.writeBytes(PageContentStore.encode(page("x", "y")))
        assertEquals(listOf("x", "y"), store.load("d", "legacy").strokes.map { it.id })
    }

    @Test fun deleteRemovesMainBackupAndTemp() = runTest {
        store.save("d", "p", page("a"))
        store.save("d", "p", page("b"))
        val f = files.pageFile("d", "p")
        File(f.parentFile, f.name + ".tmp").writeBytes(byteArrayOf(1))
        store.delete("d", "p")
        assertFalse(f.exists())
        assertFalse(FileStore.backupOf(f).exists())
        assertFalse(FileStore.tempOf(f).exists())
        assertTrue(store.load("d", "p").isEmpty)
    }

    @Test fun strictLoadRefusesAPageWhoseFileAndBackupAreUnreadable() = runTest {
        // Saved only once (duplicated, imported, restored): there is no backup to fall back to.
        store.save("d", "p", page("a"))
        val f = files.pageFile("d", "p")
        assertFalse(FileStore.backupOf(f).exists())
        f.writeBytes(ByteArray(64) { 0x7f })
        try {
            store.load("d", "p", strict = true)
            fail("an unreadable page loaded as empty, so the editor would save over it")
        } catch (e: UnreadablePageException) {
            // expected
        }
        // A damaged backup is no better.
        FileStore.backupOf(f).writeBytes(ByteArray(0))
        try {
            store.load("d", "p", strict = true)
            fail("an unreadable page with an unreadable backup loaded as empty")
        } catch (e: UnreadablePageException) {
            // expected
        }
        // Read-only callers keep getting an empty page, and the damaged bytes stay on disk.
        assertTrue(store.load("d", "p").isEmpty)
        assertEquals(64, f.length())
    }

    @Test fun strictLoadOfAPageWithoutFilesIsEmpty() = runTest {
        assertTrue(store.load("none", "none", strict = true).isEmpty)
        store.save("d", "p", PageContent.EMPTY)
        assertTrue(store.load("d", "p", strict = true).isEmpty)
    }
}
