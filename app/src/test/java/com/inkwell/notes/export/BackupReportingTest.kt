package com.inkwell.notes.export

import com.inkwell.notes.export.backup.AutoBackupStatus
import com.inkwell.notes.export.backup.BackupPrefs
import com.inkwell.notes.export.format.RowSlices
import com.inkwell.notes.export.sync.ui.BackupJobs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupReportingTest {

    @Test fun skippedDocumentsAreReported() {
        assertEquals("Restored 3 documents.", BackupJobs.restoreMessage(3, 0))
        assertEquals("Restored 1 document. 2 documents could not be restored.", BackupJobs.restoreMessage(1, 2))
        assertEquals("Restored 37 documents. 1 document could not be restored.", BackupJobs.restoreMessage(37, 1))
        assertEquals("The backup had no documents to restore.", BackupJobs.restoreMessage(0, 0))
        assertEquals("None of the documents in the backup could be restored.", BackupJobs.restoreMessage(0, 4))
        assertEquals("", BackupJobs.skippedNote(0, "backed up"))
        assertEquals(" 3 documents could not be backed up.", BackupJobs.skippedNote(3, "backed up"))
    }

    @Test fun automaticBackupsThatLeaveDocumentsOutAreShown() {
        assertEquals(AutoBackupStatus.Problem.NONE, AutoBackupStatus().problem)
        assertEquals(AutoBackupStatus.Problem.NONE, AutoBackupStatus(lastRunAt = 5, lastSuccessAt = 5).problem)
        // A run that skipped documents says how many; one that failed outright says so.
        assertEquals(AutoBackupStatus.Problem.SKIPPED, AutoBackupStatus(lastRunAt = 9, lastSuccessAt = 5, lastFailed = true, lastSkipped = 2).problem)
        assertEquals(AutoBackupStatus.Problem.FAILED, AutoBackupStatus(lastRunAt = 9, lastSuccessAt = 5, lastFailed = true).problem)
    }

    @Test fun anotherAutomaticBackupFolderStartsWithACleanStatus() {
        val old = "content://com.android.externalstorage.documents/tree/1234-5678%3ABackups"
        val new = "content://com.android.externalstorage.documents/tree/primary%3ABackups"
        assertTrue("same folder rescheduled: its runs still apply", BackupPrefs.keepsRunStatus(old, old))
        assertFalse("a new folder: the old folder's failure no longer applies", BackupPrefs.keepsRunStatus(old, new))
        assertFalse("turned off", BackupPrefs.keepsRunStatus(old, null))
        assertFalse("turned on after being off", BackupPrefs.keepsRunStatus(null, new))
    }

    @Test fun reportsDefaultToNothingSkipped() {
        assertEquals(0, BackupReport(2, 10, null).skipped)
        assertEquals(0, RestoreReport(2).skipped)
    }

    @Test fun rowsTallerThanAPageSplitBetweenLines() {
        // 25 lines of 12pt in a row 300pt tall, with 100pt of room per page.
        val bottoms = (1..25).map { it * 12f }
        val slices = RowSlices.of(300f, bottoms, 100f)
        assertEquals(listOf(0f to 96f, 96f to 192f, 192f to 288f, 288f to 300f), slices)
        // Every part fits on a page and together they cover the whole row.
        slices.forEach { (from, end) -> assertTrue(end - from in 0.1f..100f) }
        assertEquals(300f, slices.last().second, 0f)
    }

    @Test fun aLineTallerThanAPageIsCut() {
        assertEquals(listOf(0f to 100f, 100f to 200f, 200f to 250f), RowSlices.of(250f, listOf(250f), 100f))
        assertEquals(listOf(0f to 80f), RowSlices.of(80f, listOf(40f, 80f), 100f))
    }
}
