package com.inkwell.notes.export.sync.ui

import android.net.Uri
import android.text.format.Formatter
import android.util.Log
import com.inkwell.notes.app.AppGraph
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal sealed interface BackupState {
    data object Idle : BackupState
    data class Running(val restore: Boolean, val startedAt: Long) : BackupState
    data class Done(val message: String) : BackupState
    data class Failed(val message: String) : BackupState
}

/**
 * Backup and restore run in the app scope so they finish even when the settings screen closes;
 * the screen re-attaches to [state] when it is shown again.
 */
internal object BackupJobs {
    private val _state = MutableStateFlow<BackupState>(BackupState.Idle)
    val state: StateFlow<BackupState> = _state.asStateFlow()

    val isRunning: Boolean get() = _state.value is BackupState.Running

    fun backup(graph: AppGraph, target: Uri) = launch(graph, restore = false) {
        val report = graph.backup.backupTo(target)
        val size = Formatter.formatShortFileSize(graph.context, report.bytes)
        val docs = if (report.documents == 1) "1 document" else "${report.documents} documents"
        "Backed up $docs ($size)." + skippedNote(report.skipped, "backed up")
    }

    fun restore(graph: AppGraph, source: Uri) = launch(graph, restore = true) {
        val report = graph.backup.restoreWithReport(source)
        restoreMessage(report.documents, report.skipped)
    }

    internal fun restoreMessage(restored: Int, skipped: Int): String = when {
        restored == 0 && skipped == 0 -> "The backup had no documents to restore."
        restored == 0 -> "None of the documents in the backup could be restored."
        restored == 1 -> "Restored 1 document." + skippedNote(skipped, "restored")
        else -> "Restored $restored documents." + skippedNote(skipped, "restored")
    }

    /** " 2 documents could not be restored." for documents left out; empty when none were. */
    internal fun skippedNote(skipped: Int, verb: String): String = when {
        skipped <= 0 -> ""
        skipped == 1 -> " 1 document could not be $verb."
        else -> " $skipped documents could not be $verb."
    }

    fun dismiss() {
        if (!isRunning) _state.value = BackupState.Idle
    }

    private fun launch(graph: AppGraph, restore: Boolean, work: suspend () -> String) {
        if (isRunning) return
        _state.value = BackupState.Running(restore, System.currentTimeMillis())
        graph.appScope.launch {
            _state.value = try {
                BackupState.Done(withContext(Dispatchers.IO) { work() })
            } catch (e: CancellationException) {
                BackupState.Idle
            } catch (e: Throwable) {
                Log.w("InkwellBackup", "${if (restore) "Restore" else "Backup"} failed: ${e.javaClass.simpleName}")
                BackupState.Failed(failureMessage(restore, e))
            }
        }
    }

    private fun failureMessage(restore: Boolean, e: Throwable): String {
        val detail = when (e) {
            is SecurityException -> "Inkwell doesn't have access to that location."
            is java.io.IOException -> e.message?.takeIf { it.isNotBlank() && it.length < 160 } ?: "A file couldn't be read or written."
            is OutOfMemoryError -> "Not enough memory."
            else -> e.message?.takeIf { it.isNotBlank() && it.length < 160 } ?: "Something went wrong."
        }
        return if (restore) "Restore failed: $detail" else "Backup failed: $detail"
    }
}
