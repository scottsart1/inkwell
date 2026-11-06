package com.inkwell.notes.export

import com.inkwell.notes.core.model.AppSettings
import com.inkwell.notes.core.model.DestinationPrefs
import com.inkwell.notes.core.model.Folder
import com.inkwell.notes.core.model.PrivacyPrefs
import com.inkwell.notes.core.model.ThemeMode
import com.inkwell.notes.export.backup.BackupNaming
import com.inkwell.notes.export.backup.LibraryManifest
import com.inkwell.notes.export.backup.SettingsSanitizer
import com.inkwell.notes.export.backup.ZipSafety
import com.inkwell.notes.export.format.ExportNaming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class ExportNamingTest {

    @Test fun titlesBecomeSafeFileNames() {
        assertEquals("20261015_Financial Markets", ExportNaming.sanitize("20261015_Financial Markets"))
        assertEquals("a b c d e", ExportNaming.sanitize("a/b\\c:d*e"))
        assertEquals("Q1 or Q2", ExportNaming.sanitize("Q1 <or> Q2?"))
        assertEquals("tabs and lines", ExportNaming.sanitize("tabs\tand\nlines"))
        assertEquals("dots", ExportNaming.sanitize("...dots..."))
        assertEquals("Inkwell", ExportNaming.sanitize("   "))
        assertEquals("Inkwell", ExportNaming.sanitize("///"))
        assertEquals("CON_", ExportNaming.sanitize("con").uppercase())
        assertEquals("Café", ExportNaming.sanitize("Cafe\u0301")) // NFC
        assertEquals("日本語のノート", ExportNaming.sanitize("日本語のノート"))
    }

    @Test fun longTitlesAreCutWithoutSplittingSurrogates() {
        val long = "a".repeat(ExportNaming.MAX_BASE_LENGTH - 1) + "\uD83D\uDE00" + "tail"
        val s = ExportNaming.sanitize(long)
        assertTrue(s.length <= ExportNaming.MAX_BASE_LENGTH)
        assertFalse(Character.isHighSurrogate(s.last()))
    }

    @Test fun longNonLatinTitlesFitTheFileNameByteLimit() {
        // ext4/f2fs refuse names over 255 bytes: 120 Korean or Chinese characters are 360 bytes.
        for (title in listOf("한국어 노트".repeat(40), "漢字".repeat(100), "😀".repeat(100), "é".repeat(300), "x" + "語".repeat(150))) {
            val name = ExportNaming.fileName(title, ExportFormat.DOCX, 1)
            assertTrue(name, name.toByteArray(Charsets.UTF_8).size <= 255)
            val second = ExportNaming.unique(name, listOf(name, "${name.substringBeforeLast('.')} (2).docx"))
            assertTrue(second, second.toByteArray(Charsets.UTF_8).size <= 255)
            assertTrue(name, name.endsWith(".docx") && !name.startsWith("."))
            name.forEachIndexed { i, c ->
                if (Character.isHighSurrogate(c)) assertTrue("lone surrogate in $name", i + 1 < name.length && Character.isLowSurrogate(name[i + 1]))
                if (Character.isLowSurrogate(c)) assertTrue("lone surrogate in $name", i > 0 && Character.isHighSurrogate(name[i - 1]))
            }
        }
        // Latin titles keep the character limit.
        assertEquals(ExportNaming.MAX_BASE_LENGTH, ExportNaming.sanitize("a".repeat(300)).length)
        assertEquals("日本語のノート", ExportNaming.sanitize("日本語のノート"))
        assertEquals("ab", ExportNaming.truncateBytes("ab漢", 4))
        assertEquals("ab漢", ExportNaming.truncateBytes("ab漢", 5))
    }

    @Test fun extensionsAndZipsForMultiPageImages() {
        assertEquals("Notes.pdf", ExportNaming.fileName("Notes", ExportFormat.PDF, 4))
        assertEquals("Notes.png", ExportNaming.fileName("Notes", ExportFormat.PNG, 1))
        assertEquals("Notes.zip", ExportNaming.fileName("Notes", ExportFormat.JPEG, 3))
        assertEquals("Notes.inkwell", ExportNaming.fileName("Notes", ExportFormat.INKWELL, 1))
        assertEquals("application/zip", ExportNaming.mime(ExportFormat.PNG, 2))
        assertEquals("image/jpeg", ExportNaming.mime(ExportFormat.JPEG, 1))
        // Providers would otherwise append ".zip" to ".inkwell" files.
        assertEquals("application/octet-stream", ExportNaming.safMime(ExportFormat.INKWELL, 1))
        assertEquals("application/pdf", ExportNaming.safMime(ExportFormat.PDF, 1))
        assertEquals("page_001.png", ExportNaming.pageImageName(0, ExportFormat.PNG))
        assertEquals("page_012.jpg", ExportNaming.pageImageName(11, ExportFormat.JPEG))
    }

    @Test fun uniqueNamesIgnoreCase() {
        assertEquals("a.pdf", ExportNaming.unique("a.pdf", setOf("b.pdf")))
        assertEquals("a (2).pdf", ExportNaming.unique("a.pdf", setOf("A.PDF")))
        assertEquals("a (3).pdf", ExportNaming.unique("a.pdf", setOf("a.pdf", "a (2).pdf")))
        assertEquals("noext (2)", ExportNaming.unique("noext", setOf("noext")))
    }

    @Test fun backupNamesSortAndPrune() {
        val cal = Calendar.getInstance(TimeZone.getDefault(), Locale.ROOT).apply { set(2026, Calendar.OCTOBER, 16, 9, 5, 0) }
        assertEquals("Inkwell-backup-20261016-0905.zip", BackupNaming.fileName(cal.timeInMillis))
        assertTrue(BackupNaming.isBackupName("Inkwell-backup-20261016-0905 (1).zip"))
        assertFalse(BackupNaming.isBackupName("notes.zip"))

        data class F(val name: String, val modified: Long)
        val files = listOf(
            F("Inkwell-backup-20261001-0100.zip", 1), F("Inkwell-backup-20261002-0100.zip", 2), F("Inkwell-backup-20261003-0100.zip", 3),
            F("Inkwell-backup-20261004-0100.zip", 4), F("Inkwell-backup-20261005-0100.zip", 5), F("Inkwell-backup-20261006-0100.zip", 6),
            F("Inkwell-backup-20261006-0100 (1).zip", 7), F("my-notes.zip", 0), F("Inkwell-backup-notes.zip", 0),
        )
        val delete = BackupNaming.toDelete(files, { it.name }, { it.modified }, keep = 5)
        assertEquals(listOf("Inkwell-backup-20261002-0100.zip", "Inkwell-backup-20261001-0100.zip"), delete.map { it.name })
        assertTrue(BackupNaming.toDelete(files.take(3), { it.name }, { it.modified }).isEmpty())
    }

    @Test fun backupEntriesAreUniqueAndReadable() {
        val a = BackupNaming.entryName("Notes / Week 1", "id-1")
        val b = BackupNaming.entryName("Notes / Week 1", "id-2")
        assertTrue(a.startsWith(LibraryManifest.DOCS_DIR))
        assertTrue(a.endsWith(".inkwell"))
        assertTrue(a != b)
        assertEquals(a, ZipSafety.safeEntryPath(a))
    }

    @Test fun folderPathsSurviveCycles() {
        val folders = listOf(
            Folder("a", "Economics", null, createdAt = 0, modifiedAt = 0),
            Folder("b", "Term 1", "a", createdAt = 0, modifiedAt = 0),
            Folder("x", "Loop X", "y", createdAt = 0, modifiedAt = 0),
            Folder("y", "Loop Y", "x", createdAt = 0, modifiedAt = 0),
            Folder("o", "Orphan", "missing", createdAt = 0, modifiedAt = 0),
        )
        val paths = BackupNaming.folderPaths(folders)
        assertEquals(listOf("Economics", "Term 1"), paths["b"])
        assertEquals(listOf("Loop Y", "Loop X"), paths["x"])
        assertEquals(listOf("Orphan"), paths["o"])
    }

    @Test fun settingsLoseDeviceSpecificDestinations() {
        val s = AppSettings(
            theme = ThemeMode.DARK,
            destinations = DestinationPrefs(folderUri = "content://tree/abc", folderLabel = "Drive", vaultUrl = "https://yourpitbox.com/notes/secret-link"),
        )
        val clean = SettingsSanitizer.forBackup(s)
        assertNull(clean.destinations.folderUri)
        assertEquals("", clean.destinations.vaultUrl)
        assertEquals(ThemeMode.DARK, clean.theme)

        val current = AppSettings(privacy = PrivacyPrefs(appLock = true), destinations = DestinationPrefs(folderLabel = "Mine"))
        val merged = SettingsSanitizer.merge(current, clean.copy(privacy = PrivacyPrefs(appLock = false)))
        assertEquals(ThemeMode.DARK, merged.theme)
        assertTrue(merged.privacy.appLock)
        assertEquals("Mine", merged.destinations.folderLabel)
    }

    @Test fun zipEntryPathsAreConfined() {
        assertEquals("pages/a.ink", ZipSafety.safeEntryPath("pages/./a.ink"))
        assertEquals("a/b", ZipSafety.safeEntryPath("a\\b"))
        assertNull(ZipSafety.safeEntryPath("../evil"))
        assertNull(ZipSafety.safeEntryPath("a/../../evil"))
        assertNull(ZipSafety.safeEntryPath("/etc/passwd"))
        assertNull(ZipSafety.safeEntryPath("C:/x"))
        assertNull(ZipSafety.safeEntryPath(""))
    }

    @Test fun extractionStopsAtUnsafeEntriesAndSizeLimits() {
        fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
            val out = ByteArrayOutputStream()
            ZipOutputStream(out).use { z -> entries.forEach { (n, b) -> z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() } }
            return out.toByteArray()
        }
        val dir = Files.createTempDirectory("zip-safety").toFile()
        try {
            val ok = ZipSafety.extract(ZipInputStream(ByteArrayInputStream(zipOf("manifest.json" to "{}".toByteArray(), "pages/p.ink" to ByteArray(10)))), dir, 1_000)
            assertEquals(listOf("manifest.json", "pages/p.ink"), ok)
            assertEquals(10L, dir.resolve("pages/p.ink").length())

            val dir2 = Files.createTempDirectory("zip-safety").toFile()
            try {
                ZipSafety.extract(ZipInputStream(ByteArrayInputStream(zipOf("../escape.txt" to ByteArray(1)))), dir2, 1_000)
                throw AssertionError("expected rejection")
            } catch (e: com.inkwell.notes.export.backup.PackageException) {
                assertEquals(com.inkwell.notes.export.backup.PackageException.Reason.CORRUPT, e.reason)
            } finally {
                dir2.deleteRecursively()
            }
            val dir3 = Files.createTempDirectory("zip-safety").toFile()
            try {
                ZipSafety.extract(ZipInputStream(ByteArrayInputStream(zipOf("big.bin" to ByteArray(5_000)))), dir3, 1_000)
                throw AssertionError("expected size limit")
            } catch (e: com.inkwell.notes.export.backup.PackageException) {
                assertEquals(com.inkwell.notes.export.backup.PackageException.Reason.TOO_LARGE, e.reason)
            } finally {
                dir3.deleteRecursively()
            }
            // A library backup picked as a package is refused at its first entry, before any
            // of its (possibly huge) documents is unpacked.
            val dir4 = Files.createTempDirectory("zip-safety").toFile()
            try {
                val backup = zipOf(LibraryManifest.FILE to "{}".toByteArray(), "documents/a__1.inkwell" to ByteArray(5_000))
                ZipSafety.extract(ZipInputStream(ByteArrayInputStream(backup)), dir4, 1_000_000, refuse = setOf(LibraryManifest.FILE))
                throw AssertionError("expected a backup to be refused")
            } catch (e: com.inkwell.notes.export.backup.PackageException) {
                assertEquals(com.inkwell.notes.export.backup.PackageException.Reason.NOT_A_PACKAGE, e.reason)
                assertTrue(dir4.walk().none { it.isFile })
            } finally {
                dir4.deleteRecursively()
            }
        } finally {
            dir.deleteRecursively()
        }
    }
}
