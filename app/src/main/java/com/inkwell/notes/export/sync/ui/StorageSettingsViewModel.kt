package com.inkwell.notes.export.sync.ui

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.inkwell.notes.R
import com.inkwell.notes.app.AppGraph
import com.inkwell.notes.core.model.AppSettings
import com.inkwell.notes.core.model.DestinationPrefs
import com.inkwell.notes.core.model.NotesExportFormat
import com.inkwell.notes.core.model.OutputTarget
import com.inkwell.notes.export.SyncManagerImpl
import com.inkwell.notes.export.SyncStatus
import com.inkwell.notes.export.backup.AutoBackupStatus
import com.inkwell.notes.export.backup.BackupPrefs
import com.inkwell.notes.export.sync.DriveException
import com.inkwell.notes.export.sync.PlayServicesDriveAuth
import com.inkwell.notes.export.sync.SafFolderAccess
import com.inkwell.notes.export.sync.SyncKind
import com.inkwell.notes.export.sync.targetsFor
import com.inkwell.notes.export.sync.withTargets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Folder chosen for automatic backups (the backup service keeps its own schedule). */
internal data class AutoBackupPref(val uri: String, val label: String)

/** Small per-device UI memory for this screen (no secrets). */
internal class StorageUiPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("inkwell_storage_ui", Context.MODE_PRIVATE)

    fun autoBackup(): AutoBackupPref? {
        val uri = prefs.getString(KEY_URI, null) ?: return null
        return AutoBackupPref(uri, prefs.getString(KEY_LABEL, null).orEmpty())
    }

    fun setAutoBackup(value: AutoBackupPref?) {
        prefs.edit().apply {
            if (value == null) remove(KEY_URI).remove(KEY_LABEL) else putString(KEY_URI, value.uri).putString(KEY_LABEL, value.label)
        }.apply()
    }

    private companion object {
        const val KEY_URI = "autoBackupUri"
        const val KEY_LABEL = "autoBackupLabel"
    }
}

/** What Google needs to know about this app (shown in the one-time Drive setup steps). */
internal data class DriveSetupInfo(val packageName: String, val sha1: String?)

/**
 * State and actions of "Storage, sync & backup". Google Drive access is held by Google Play
 * services; this screen only starts the consent flow and keeps the connected account's email.
 */
internal class StorageSettingsViewModel(private val graph: AppGraph) : ViewModel() {
    private val context: Context get() = graph.context
    private val impl: SyncManagerImpl? = graph.sync as? SyncManagerImpl
    private val uiPrefs = StorageUiPrefs(graph.context)

    val settings: StateFlow<AppSettings> = graph.settings.settings
    val status: StateFlow<SyncStatus> = graph.sync.status
    val failedCount: StateFlow<Int> = (impl?.observeFailedCount() ?: flowOf(0))
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    val backup: StateFlow<BackupState> = BackupJobs.state

    var driveCheck by mutableStateOf<CheckState>(CheckState.Idle)
        private set
    /** Google's account/consent screen to show; the screen launches it and calls [onDriveConsent]. */
    var driveConsent by mutableStateOf<PendingIntent?>(null)
        private set
    /** True once Google said Inkwell isn't registered: the setup steps open by themselves. */
    var driveNeedsSetup by mutableStateOf(false)
        private set
    val driveSetup: DriveSetupInfo by lazy { DriveSetupInfo(context.packageName, signingSha1(context)) }
    private var driveFor: SyncKind? = null
    var folderCheck by mutableStateOf<CheckState>(CheckState.Idle)
        private set
    var syncRequested by mutableStateOf(false)
        private set
    var autoBackup by mutableStateOf<AutoBackupPref?>(null)
        private set
    /** How the automatic backups have gone (complete, or documents left out). */
    var autoBackupStatus by mutableStateOf(AutoBackupStatus())
        private set
    /** One-off problem to show (picker unavailable, permission refused...). */
    var notice by mutableStateOf<String?>(null)

    init {
        viewModelScope.launch {
            autoBackup = withContext(Dispatchers.IO) { runCatching { uiPrefs.autoBackup() }.getOrNull() }
            autoBackupStatus = withContext(Dispatchers.IO) { runCatching { BackupPrefs(context).status() }.getOrNull() } ?: AutoBackupStatus()
        }
    }

    // ------------------------------------------------------------------ destinations

    fun setTarget(kind: SyncKind, target: OutputTarget, on: Boolean) = updateDestinations { d ->
        d.withTargets(kind, if (on) d.targetsFor(kind) + target else d.targetsFor(kind) - target)
    }

    fun setFormat(format: NotesExportFormat) = updateDestinations { it.copy(notesFormat = format) }
    fun setAutoSync(on: Boolean) = updateDestinations { it.copy(autoSync = on) }
    fun setAudioWifiOnly(on: Boolean) = updateDestinations { it.copy(audioOnUnmeteredOnly = on) }

    private fun updateDestinations(transform: (DestinationPrefs) -> DestinationPrefs) {
        viewModelScope.launch { graph.settings.update { it.copy(destinations = transform(it.destinations)) } }
    }

    // ------------------------------------------------------------------ folder

    /** A tree was picked: keep read/write access, store it, optionally switch it on for [enableFor], test it. */
    fun onFolderPicked(uri: Uri, enableFor: SyncKind?) {
        viewModelScope.launch {
            val label = withContext(Dispatchers.IO) { persistTree(uri) }
            if (label == null) {
                folderCheck = CheckState.Failed(context.getString(R.string.sync_folder_permission_error))
                return@launch
            }
            val previous = settings.value.destinations.folderUri
            graph.settings.update { s ->
                var d = s.destinations.copy(folderUri = uri.toString(), folderLabel = label)
                if (enableFor != null) d = d.withTargets(enableFor, d.targetsFor(enableFor) + OutputTarget.FOLDER)
                s.copy(destinations = d)
            }
            releaseIfUnused(previous, keep = uri.toString())
            testFolder()
        }
    }

    fun testFolder() {
        val uri = settings.value.destinations.folderUri ?: return
        if (folderCheck == CheckState.Running) return
        folderCheck = CheckState.Running
        viewModelScope.launch {
            val r = graph.sync.testFolder(Uri.parse(uri))
            folderCheck = r.fold({ CheckState.Ok(it) }, { CheckState.Failed(it.message ?: context.getString(R.string.sync_folder_not_writable)) })
        }
    }

    /** Takes persistable read/write access; returns a display label or null when refused. */
    private fun persistTree(uri: Uri): String? = try {
        context.contentResolver.takePersistableUriPermission(uri, SafFolderAccess.RW_FLAGS)
        runCatching { DocumentFile.fromTreeUri(context, uri)?.name }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringAfterLast(':')?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: "Folder"
    } catch (e: SecurityException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }

    private suspend fun releaseIfUnused(uri: String?, keep: String?) {
        if (uri == null || uri == keep) return
        val stillUsed = uri == settings.value.destinations.folderUri || uri == autoBackup?.uri
        if (stillUsed) return
        withContext(Dispatchers.IO) {
            runCatching { context.contentResolver.releasePersistableUriPermission(Uri.parse(uri), SafFolderAccess.RW_FLAGS) }
        }
    }

    // ------------------------------------------------------------------ Google Drive

    /** Asks Google for Drive access; [enableFor] switches Drive on for that output once connected. */
    fun connectDrive(enableFor: SyncKind?) {
        if (driveCheck == CheckState.Running) return
        driveFor = enableFor
        driveCheck = CheckState.Running
        viewModelScope.launch {
            try {
                when (val begin = PlayServicesDriveAuth.begin(context)) {
                    is PlayServicesDriveAuth.Begin.NeedsConsent -> driveConsent = begin.intent
                    is PlayServicesDriveAuth.Begin.Granted -> finishConnect(begin.token)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: DriveException) {
                driveFailed(e)
            }
        }
    }

    /**
     * Hands the pending consent screen to the UI exactly once (a rotation must not show it twice);
     * the result comes back through [onDriveConsent].
     */
    fun takeDriveConsent(): PendingIntent? = driveConsent.also { if (it != null) driveConsent = null }

    /** The consent screen could not be shown at all. */
    fun onDriveConsentUnavailable() {
        driveConsent = null
        driveFor = null
        driveCheck = CheckState.Failed(context.getString(R.string.sync_drive_no_consent))
    }

    /** Google's account/consent screen closed with [resultCode] and [data]. */
    fun onDriveConsent(resultCode: Int, data: Intent?) {
        driveConsent = null
        if (data == null) {
            driveFor = null
            driveCheck = CheckState.Failed(context.getString(R.string.sync_drive_cancelled))
            return
        }
        driveCheck = CheckState.Running
        viewModelScope.launch {
            try {
                finishConnect(PlayServicesDriveAuth.finish(context, data))
            } catch (e: CancellationException) {
                throw e
            } catch (e: DriveException) {
                driveFailed(e)
            }
        }
    }

    private suspend fun finishConnect(token: String) {
        val manager = impl ?: run {
            driveFailed(DriveException.NotConnected())
            return
        }
        val result = manager.connectDrive(token, driveFor)
        driveFor = null
        driveCheck = result.fold(
            onSuccess = { email ->
                driveNeedsSetup = false
                CheckState.Ok(context.getString(R.string.sync_drive_connected_as, email))
            },
            onFailure = { e ->
                if (e.cause is DriveException.NotSetUp) driveNeedsSetup = true
                CheckState.Failed(e.message ?: context.getString(R.string.sync_drive_unavailable))
            },
        )
    }

    private fun driveFailed(e: DriveException) {
        driveFor = null
        if (e is DriveException.NotSetUp) driveNeedsSetup = true
        driveCheck = CheckState.Failed(
            if (e is DriveException.Cancelled) context.getString(R.string.sync_drive_cancelled)
            else e.message ?: context.getString(R.string.sync_drive_unavailable),
        )
    }

    fun testDrive() {
        if (driveCheck == CheckState.Running) return
        driveCheck = CheckState.Running
        viewModelScope.launch {
            val r = graph.sync.testDrive()
            driveCheck = r.fold({ CheckState.Ok(it) }, { e ->
                if (e.cause is DriveException.NotSetUp) driveNeedsSetup = true
                CheckState.Failed(e.message ?: context.getString(R.string.sync_drive_unavailable))
            })
        }
    }

    /** Where "Open Drive in browser" goes: the Inkwell folder once it exists. */
    fun driveUrl(): String = impl?.driveFolderUrl() ?: "https://drive.google.com/drive/my-drive"

    /** Stops copying to Drive and withdraws access; what is already in Drive stays. */
    fun disconnectDrive() {
        driveCheck = CheckState.Idle
        // App scope: leaving the screen must not leave Drive half disconnected.
        graph.appScope.launch { impl?.disconnectDrive() }
    }

    // ------------------------------------------------------------------ sync

    /** Runs in the app scope so leaving the screen does not cancel it. */
    fun syncNow() {
        if (syncRequested) return
        syncRequested = true
        graph.appScope.launch {
            try {
                graph.sync.syncNow()
            } finally {
                withContext(Dispatchers.Main) { syncRequested = false }
            }
        }
    }

    // ------------------------------------------------------------------ backup

    fun backupTo(uri: Uri) = BackupJobs.backup(graph, uri)
    fun restoreFrom(uri: Uri) = BackupJobs.restore(graph, uri)
    fun dismissBackupResult() = BackupJobs.dismiss()

    fun enableAutoBackup(uri: Uri) {
        viewModelScope.launch {
            val label = withContext(Dispatchers.IO) { persistTree(uri) }
            if (label == null) {
                notice = context.getString(R.string.sync_folder_permission_error)
                return@launch
            }
            val previous = autoBackup?.uri
            val ok = withContext(Dispatchers.IO) {
                runCatching { graph.backup.scheduleAutomatic(uri, AUTO_BACKUP_DAYS) }.isSuccess
            }
            if (!ok) {
                notice = context.getString(R.string.sync_backup_schedule_failed)
                return@launch
            }
            val pref = AutoBackupPref(uri.toString(), label)
            withContext(Dispatchers.IO) { uiPrefs.setAutoBackup(pref) }
            autoBackup = pref
            // Scheduling a new folder cleared the old folder's status (AutoBackup.schedule).
            autoBackupStatus = withContext(Dispatchers.IO) { runCatching { BackupPrefs(context).status() }.getOrNull() } ?: AutoBackupStatus()
            releaseIfUnused(previous, keep = uri.toString())
        }
    }

    fun disableAutoBackup() {
        viewModelScope.launch {
            val previous = autoBackup?.uri
            withContext(Dispatchers.IO) {
                runCatching { graph.backup.scheduleAutomatic(null) }
                uiPrefs.setAutoBackup(null)
            }
            autoBackup = null
            autoBackupStatus = AutoBackupStatus()
            releaseIfUnused(previous, keep = null)
        }
    }

    private companion object {
        const val AUTO_BACKUP_DAYS = 1

        /** SHA-1 of the certificate this app is signed with, as Google Cloud asks for it ("AB:CD:..."). */
        fun signingSha1(context: Context): String? = runCatching {
            val pm = context.packageManager
            val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val info = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                info.signingInfo?.let { si -> if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory }
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures
            }
            val cert = signatures?.lastOrNull() ?: return@runCatching null
            java.security.MessageDigest.getInstance("SHA-1").digest(cert.toByteArray()).joinToString(":") { "%02X".format(it) }
        }.getOrNull()
    }
}
