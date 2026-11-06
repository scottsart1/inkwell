package com.inkwell.notes.export.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.inkwell.notes.app.AppGraph
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/** Where automatic backups go, kept in a private preferences file of the export feature. */
internal class BackupPrefs(context: Context) {
    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    var treeUri: Uri?
        get() = prefs.getString(KEY_TREE, null)?.let(Uri::parse)
        set(value) { prefs.edit().putString(KEY_TREE, value?.toString()).apply() }

    var intervalDays: Int
        get() = prefs.getInt(KEY_DAYS, 1)
        set(value) { prefs.edit().putInt(KEY_DAYS, value).apply() }

    var lastSuccessAt: Long
        get() = prefs.getLong(KEY_LAST_OK, 0)
        set(value) { prefs.edit().putLong(KEY_LAST_OK, value).apply() }

    var lastFailed: Boolean
        get() = prefs.getBoolean(KEY_LAST_FAILED, false)
        set(value) { prefs.edit().putBoolean(KEY_LAST_FAILED, value).apply() }

    /** When the last automatic run ended (complete, partial or failed); 0 when it never ran. */
    val lastRunAt: Long
        get() = prefs.getLong(KEY_LAST_RUN, 0)

    /** Documents the last automatic run had to leave out (0 when it failed outright or was complete). */
    val lastSkipped: Int
        get() = prefs.getInt(KEY_LAST_SKIPPED, 0)

    /** What the Storage settings screen shows about automatic backups. */
    fun status(): AutoBackupStatus = AutoBackupStatus(lastRunAt, lastSuccessAt, lastFailed, lastSkipped)

    /** Records how an automatic run ended: [skipped] documents left out, or [failed] outright. */
    fun recordRun(now: Long, failed: Boolean, skipped: Int) {
        val complete = !failed && skipped == 0
        prefs.edit().apply {
            putLong(KEY_LAST_RUN, now)
            putBoolean(KEY_LAST_FAILED, !complete)
            putInt(KEY_LAST_SKIPPED, if (failed) 0 else skipped)
            if (complete) putLong(KEY_LAST_OK, now)
        }.apply()
    }

    /** File name of the newest automatic backup that holds every document; pruning keeps it. */
    var lastCompleteName: String?
        get() = prefs.getString(KEY_LAST_COMPLETE, null)
        set(value) { prefs.edit().putString(KEY_LAST_COMPLETE, value).apply() }

    /**
     * Forgets how earlier runs went (they were for another folder, or auto-backup is off). The
     * name of the last complete backup stays: pruning only matches it inside the folder it prunes,
     * and forgetting it would let partial runs prune the only complete backup away.
     */
    fun resetRunStatus() {
        prefs.edit()
            .remove(KEY_LAST_RUN).remove(KEY_LAST_FAILED).remove(KEY_LAST_SKIPPED)
            .remove(KEY_LAST_OK)
            .apply()
    }

    companion object {
        /** True when the run status recorded for folder [previous] still describes folder [next]. */
        fun keepsRunStatus(previous: String?, next: String?): Boolean = next != null && previous == next

        private const val FILE = "inkwell_export_backup"
        private const val KEY_TREE = "auto_tree_uri"
        private const val KEY_DAYS = "auto_interval_days"
        private const val KEY_LAST_OK = "auto_last_success"
        private const val KEY_LAST_FAILED = "auto_last_failed"
        private const val KEY_LAST_COMPLETE = "auto_last_complete_name"
        private const val KEY_LAST_RUN = "auto_last_run"
        private const val KEY_LAST_SKIPPED = "auto_last_skipped"
    }
}

/**
 * How automatic backups have gone, for the Storage settings screen: [lastSuccessAt] is the
 * last run that held every document, [lastSkipped] how many the last run left out.
 */
internal data class AutoBackupStatus(
    val lastRunAt: Long = 0,
    val lastSuccessAt: Long = 0,
    val lastFailed: Boolean = false,
    val lastSkipped: Int = 0,
) {
    enum class Problem { NONE, SKIPPED, FAILED }

    /** What went wrong in the last run, if anything. */
    val problem: Problem
        get() = when {
            !lastFailed -> Problem.NONE
            lastSkipped > 0 -> Problem.SKIPPED
            else -> Problem.FAILED
        }
}

/** Schedules and runs automatic backups into a user-chosen folder. */
internal object AutoBackup {
    const val WORK_NAME = "inkwell-auto-backup"
    const val KEEP = 5

    fun schedule(context: Context, treeUri: Uri?, intervalDays: Int) {
        val prefs = BackupPrefs(context)
        val wm = WorkManager.getInstance(context)
        // A new folder (or none) starts with a clean status: an old failure no longer applies.
        if (!BackupPrefs.keepsRunStatus(prefs.treeUri?.toString(), treeUri?.toString())) prefs.resetRunStatus()
        if (treeUri == null) {
            prefs.treeUri = null
            wm.cancelUniqueWork(WORK_NAME)
            return
        }
        // Keep access to the folder across restarts (no-op when the grant is already persisted).
        try {
            context.contentResolver.takePersistableUriPermission(
                treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        } catch (e: SecurityException) {
            Log.w(TAG, "Folder grant could not be persisted")
        }
        val days = intervalDays.coerceIn(1, 90)
        prefs.treeUri = treeUri
        prefs.intervalDays = days
        val request = PeriodicWorkRequestBuilder<AutoBackupWorker>(days.toLong(), TimeUnit.DAYS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiresCharging(true)
                    .setRequiresBatteryNotLow(true)
                    .setRequiresStorageNotLow(true)
                    .build(),
            )
            .addTag(WORK_NAME)
            .build()
        wm.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    /**
     * Deletes all but the [KEEP] newest backups in [tree] (never [keepName]) and partial
     * backups left by a run that was killed. Other files are never touched.
     */
    fun prune(context: Context, tree: Uri, keepName: String? = null) {
        val dir = DocumentFile.fromTreeUri(context, tree) ?: return
        val files = dir.listFiles().filter { it.isFile }
        val old = BackupNaming.toDelete(files, { it.name.orEmpty() }, { it.lastModified() }, KEEP).filter { it.name != keepName }
        val stale = BackupNaming.stalePartials(files, { it.name.orEmpty() }, { it.lastModified() }, System.currentTimeMillis(), STALE_PARTIAL_MS)
        for (f in old + stale) {
            runCatching { f.delete() }
        }
    }

    /** No backup takes this long; a partial file this old was left by a killed run. */
    private const val STALE_PARTIAL_MS = 24L * 60 * 60 * 1000

    private const val TAG = "AutoBackup"
}

/** Periodic backup job (charging, battery not low). Backups are never touched by sync or deletions. */
class AutoBackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val prefs = BackupPrefs(applicationContext)
        val tree = prefs.treeUri ?: return Result.success()
        val dir = DocumentFile.fromTreeUri(applicationContext, tree)
        if (dir == null || !dir.exists() || !dir.canWrite()) {
            prefs.recordRun(System.currentTimeMillis(), failed = true, skipped = 0)
            return Result.failure()
        }
        return try {
            val graph = AppGraph.get(applicationContext)
            val report = graph.backup.backupTo(tree, includeAudio = true)
            val complete = report.skipped == 0
            if (complete) report.file?.let { DocumentFile.fromSingleUri(applicationContext, it)?.name }?.let { prefs.lastCompleteName = it }
            // A backup that had to leave documents out never pushes out the last one that has them all.
            AutoBackup.prune(applicationContext, tree, keepName = prefs.lastCompleteName)
            // Shown in Storage settings, so documents left out never go unnoticed.
            prefs.recordRun(System.currentTimeMillis(), failed = false, skipped = report.skipped)
            Result.success()
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            Log.w("AutoBackup", "Automatic backup failed (${t.javaClass.simpleName})")
            prefs.recordRun(System.currentTimeMillis(), failed = true, skipped = 0)
            if (runAttemptCount < 2) Result.retry() else Result.failure()
        }
    }
}
