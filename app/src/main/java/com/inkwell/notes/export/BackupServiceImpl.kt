package com.inkwell.notes.export

import android.net.Uri
import com.inkwell.notes.app.AppGraph
import com.inkwell.notes.export.backup.AutoBackup
import com.inkwell.notes.export.backup.LibraryBackup

/**
 * Full-library backup into a portable zip of native packages, restore, and automatic
 * periodic backups. Independent of sync: removing a synced note never touches a backup.
 */
class BackupServiceImpl(private val graph: AppGraph) : BackupService {
    private val engine by lazy { LibraryBackup(graph) }

    override suspend fun backupTo(treeOrFileUri: Uri, includeAudio: Boolean): BackupReport =
        engine.backupTo(treeOrFileUri, includeAudio)

    override suspend fun restoreFrom(uri: Uri): Int = engine.restoreFrom(uri).documents

    override suspend fun restoreWithReport(uri: Uri): RestoreReport = engine.restoreFrom(uri)

    override fun scheduleAutomatic(treeUri: Uri?, intervalDays: Int) {
        AutoBackup.schedule(graph.context, treeUri, intervalDays)
    }
}
