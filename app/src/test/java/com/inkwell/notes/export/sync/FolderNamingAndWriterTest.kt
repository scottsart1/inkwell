package com.inkwell.notes.export.sync

import com.inkwell.notes.core.model.NotesExportFormat
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

class FolderNamingAndWriterTest {
    private val tmp: File = Files.createTempDirectory("folder-sync").toFile()

    @After
    fun tearDown() {
        tmp.deleteRecursively()
    }

    // ------------------------------------------------------------------ naming

    @Test
    fun `segments drop characters that SAF, FAT and desktops refuse`() {
        assertEquals("20261015_Financial Markets", FolderNaming.segment("20261015_Financial Markets"))
        assertEquals("Q&A_ Rates _ FX_", FolderNaming.segment("Q&A: Rates / FX?"))
        assertEquals("a_b_c_d_e_f_g_h", FolderNaming.segment("a\\b*c\"d<e>f|g\u0007h"))
        assertEquals("Trailing", FolderNaming.segment("  Trailing...  "))
        assertEquals("one space", FolderNaming.segment("one \t\n  space"))
        assertEquals("Untitled", FolderNaming.segment("   "))
        assertEquals("Untitled", FolderNaming.segment("..."))
        assertEquals("CON_", FolderNaming.segment("CON"))
        assertEquals("nul.txt_", FolderNaming.segment("nul.txt"))
        assertEquals(100, FolderNaming.segment("x".repeat(300)).length)
        assertEquals("Ünïcödé 日本語", FolderNaming.segment("Ünïcödé 日本語"))
    }

    @Test
    fun `document directory is Inkwell, folders, then the title`() {
        assertEquals(
            listOf("Inkwell", "Economics", "Term 1", "20261015_Financial Markets"),
            FolderNaming.documentDir(listOf("Economics", "Term 1"), "20261015_Financial Markets"),
        )
        assertEquals(listOf("Inkwell", "Folder", "a_b"), FolderNaming.documentDir(listOf(" "), "a/b"))
        assertEquals(listOf("Inkwell", "Untitled"), FolderNaming.documentDir(emptyList(), ""))
    }

    @Test
    fun `file names follow the convention`() {
        assertEquals(listOf("notes.pdf"), FolderNaming.notesFiles(NotesExportFormat.PDF))
        assertEquals(listOf("notes.md"), FolderNaming.notesFiles(NotesExportFormat.MARKDOWN))
        assertEquals(listOf("notes.pdf", "notes.md"), FolderNaming.notesFiles(NotesExportFormat.PDF_AND_MARKDOWN))
        assertEquals("transcript_Lecture 3.md", FolderNaming.transcriptFile("Lecture 3"))
        assertEquals("audio_Lecture 3.m4a", FolderNaming.audioFile("Lecture 3"))
        assertTrue(FolderNaming.isOwnFile("notes.pdf"))
        assertTrue(FolderNaming.isOwnFile("transcript_x.md"))
        assertTrue(FolderNaming.isOwnFile("audio_x.m4a"))
        assertFalse(FolderNaming.isOwnFile("my thesis.docx"))
    }

    @Test
    fun `recordings with the same title get stable numbered stems`() {
        val a = recording(id = "a", title = "Lecture", startedAt = 100)
        val b = recording(id = "b", title = "Lecture", startedAt = 200)
        val c = recording(id = "c", title = "Lab: 1", startedAt = 50)
        val all = listOf(b, c, a)
        assertEquals("Lecture", FolderNaming.recordingStem(a, all))
        assertEquals("Lecture (2)", FolderNaming.recordingStem(b, all))
        assertEquals("Lab_ 1", FolderNaming.recordingStem(c, all))
        assertEquals("Recording", FolderNaming.recordingStem(recording(id = "d", title = ""), emptyList()))
    }

    // ------------------------------------------------------------------ writer

    @Test
    fun `ensureDirs creates the chain once and reuses it`() {
        val root = JavaFileNode(tmp)
        val dir = FolderWriter.ensureDirs(root, listOf("Inkwell", "Economics", "Doc"))
        assertEquals("Doc", dir.name)
        FolderWriter.ensureDirs(root, listOf("Inkwell", "Economics", "Doc"))
        assertEquals(listOf("Economics"), File(tmp, "Inkwell").list()!!.toList())
        assertTrue(File(tmp, "Inkwell/Economics/Doc").isDirectory)
        assertEquals("Doc", FolderWriter.findDir(root, listOf("Inkwell", "Economics", "Doc"))!!.name)
        assertNull(FolderWriter.findDir(root, listOf("Inkwell", "Nope")))
    }

    @Test
    fun `writing replaces the old file without duplicates or leftovers`() {
        val dir = FolderWriter.ensureDirs(JavaFileNode(tmp), listOf("Inkwell", "Doc"))
        FolderWriter.writeFile(dir, "notes.md", "text/markdown") { it.write("v1".encodeToByteArray()) }
        FolderWriter.writeFile(dir, "notes.md", "text/markdown") { it.write("v2".encodeToByteArray()) }
        val files = File(tmp, "Inkwell/Doc").list()!!.sorted()
        assertEquals(listOf("notes.md"), files)
        assertEquals("v2", File(tmp, "Inkwell/Doc/notes.md").readText())
    }

    @Test
    fun `providers without rename get a copied file`() {
        val dir = FolderWriter.ensureDirs(JavaFileNode(tmp, supportsRename = false), listOf("Doc"))
        FolderWriter.writeFile(dir, "notes.pdf", "application/pdf") { it.write("one".encodeToByteArray()) }
        FolderWriter.writeFile(dir, "notes.pdf", "application/pdf") { it.write("two".encodeToByteArray()) }
        assertEquals(listOf("notes.pdf"), File(tmp, "Doc").list()!!.toList())
        assertEquals("two", File(tmp, "Doc/notes.pdf").readText())
    }

    @Test
    fun `a failed write keeps the previous version and removes the partial file`() {
        val dir = FolderWriter.ensureDirs(JavaFileNode(tmp), listOf("Doc"))
        FolderWriter.writeFile(dir, "notes.md", "text/markdown") { it.write("good".encodeToByteArray()) }
        try {
            FolderWriter.writeFile(dir, "notes.md", "text/markdown") {
                it.write("half".encodeToByteArray())
                throw IOException("disk full")
            }
            fail("expected IOException")
        } catch (e: IOException) {
            assertEquals("disk full", e.message)
        }
        assertEquals(listOf("notes.md"), File(tmp, "Doc").list()!!.toList())
        assertEquals("good", File(tmp, "Doc/notes.md").readText())
    }

    @Test
    fun `partial names keep the extension`() {
        assertEquals("notes.partial.pdf", FolderWriter.partialName("notes.pdf"))
        assertEquals("audio_Lecture 3.partial.m4a", FolderWriter.partialName("audio_Lecture 3.m4a"))
        assertEquals("README.partial", FolderWriter.partialName("README"))
    }

    @Test
    fun `deleteOwnFiles leaves the user's files and the folder when not empty`() {
        val dir = FolderWriter.ensureDirs(JavaFileNode(tmp), listOf("Old"))
        listOf("notes.pdf", "notes.md", "transcript_a.md", "audio_a.m4a", "my.docx").forEach { File(tmp, "Old/$it").writeText("x") }
        FolderWriter.deleteOwnFiles(dir)
        assertEquals(listOf("my.docx"), File(tmp, "Old").list()!!.toList())

        val dir2 = FolderWriter.ensureDirs(JavaFileNode(tmp), listOf("Old2"))
        File(tmp, "Old2/notes.pdf").writeText("x")
        FolderWriter.deleteOwnFiles(dir2)
        assertFalse(File(tmp, "Old2").exists())
    }

    @Test
    fun `a directory holding sub-directories is never renamed in place`() {
        val root = JavaFileNode(tmp)
        // A notebook and a library folder both called "Physics" share Inkwell/Physics.
        val shared = FolderWriter.ensureDirs(root, listOf("Inkwell", "Physics"))
        FolderWriter.ensureDirs(root, listOf("Inkwell", "Physics", "Lecture 1"))
        val parent = FolderWriter.findDir(root, listOf("Inkwell"))!!
        assertFalse(FolderWriter.renameDirInPlace(shared, "Physics summary", parent))
        assertTrue(File(tmp, "Inkwell/Physics/Lecture 1").isDirectory)

        val alone = FolderWriter.ensureDirs(root, listOf("Inkwell", "Chemistry"))
        assertTrue(FolderWriter.renameDirInPlace(alone, "Chemistry summary", parent))
        assertTrue(File(tmp, "Inkwell/Chemistry summary").isDirectory)
    }

    @Test
    fun `probe creates and removes its test file`() {
        FolderWriter.probe(JavaFileNode(tmp), 42)
        assertEquals(0, tmp.list()!!.size)
    }
}
