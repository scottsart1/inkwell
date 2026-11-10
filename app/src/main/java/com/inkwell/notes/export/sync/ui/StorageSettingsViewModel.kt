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
import com.inkwell.notes.core.settings.SecretStore
import com.inkwell.notes.export.SyncManagerImpl
import com.inkwell.notes.export.SyncStatus
import com.inkwell.notes.export.backup.AutoBackupStatus
import com.inkwell.notes.export.backup.BackupPrefs
import com.inkwell.notes.export.email.WorkEmail
import com.inkwell.notes.export.sync.DriveException
import com.inkwell.notes.export.sync.PlayServicesDriveAuth
import com.inkwell.notes.export.sync.SafFolderAccess
import com.inkwell.notes.export.sync.SyncKind
import com.inkwell.notes.export.sync.VaultPassword
import com.inkwell.notes.export.sync.VaultUrl
import com.inkwell.notes.export.sync.targetsFor
import com.inkwell.notes.export.sync.withTargets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
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
 * Never holds the vault password after it is saved; [hasPassword] only says whether one is saved
 * for the current vault link.
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
    /** The Inkwell folder's link while it is shared with anyone who has it; null otherwise. */
    var driveSharedLink by mutableStateOf<String?>(null)
        private set
    /** Sharing by link is being switched on or off right now. */
    var driveSharing by mutableStateOf(false)
        private set
    private var driveFor: SyncKind? = null
    var hasPassword by mutableStateOf(false)
        private set
    /** A password is saved at all (perhaps for another vault link): it can still be forgotten. */
    var hasAnyPassword by mutableStateOf(false)
        private set
    /** The vault link saved when the screen opened: links saved while typing never claim a password. */
    private val openingLink = graph.settings.current.destinations.vaultUrl
    var vaultCheck by mutableStateOf<CheckState>(CheckState.Idle)
        private set
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

    private var passwordJob: Job? = null
    private var pendingPassword: String? = null
    private var urlJob: Job? = null
    private var pendingUrl: String? = null
    /** Bumped when the password is saved or forgotten here, so an older [refreshHasPassword] can't undo it. */
    private var passwordEdits = 0

    init {
        viewModelScope.launch {
            autoBackup = withContext(Dispatchers.IO) { runCatching { uiPrefs.autoBackup() }.getOrNull() }
            autoBackupStatus = withContext(Dispatchers.IO) { runCatching { BackupPrefs(context).status() }.getOrNull() } ?: AutoBackupStatus()
        }
        // The link appears once the folder exists and is shared (after connecting, a copy, a test).
        viewModelScope.launch { status.collect { refreshDriveLink() } }
        // A saved password only counts for the link it belongs to; the link can change here or
        // in the background (the vault moved between www.host and host).
        viewModelScope.launch {
            settings.map { it.destinations.vaultUrl }.distinctUntilChanged().collect { refreshHasPassword() }
        }
    }

    private suspend fun refreshHasPassword() {
        val edits = passwordEdits
        val link = settings.value.destinations.vaultUrl
        val (has, any) = withContext(Dispatchers.IO) {
            runCatching { VaultPassword.savedFor(graph.secrets, link) }.getOrDefault(false) to
                runCatching { graph.secrets.has(SecretStore.VAULT_PASSWORD) }.getOrDefault(false)
        }
        if (edits == passwordEdits) {
            hasPassword = has
            hasAnyPassword = any
        }
    }

    private suspend fun refreshDriveLink() {
        driveSharedLink = withContext(Dispatchers.IO) {
            impl?.takeIf { it.driveLinkShared() }?.driveFolderLink()
        }
    }

    /**
     * Opt-in: lets anyone who has the Inkwell folder's link open it without signing in to Google,
     * or withdraws that. Applied to Drive right away.
     */
    fun setDriveShareLink(on: Boolean) {
        if (driveSharing) return
        driveSharing = true
        // App scope: leaving the screen must not leave the folder shared when the user said no.
        graph.appScope.launch {
            try {
                graph.settings.update { it.copy(destinations = it.destinations.copy(driveShareLink = on)) }
                val result = impl?.applyDriveSharing()
                withContext(Dispatchers.Main) {
                    result?.exceptionOrNull()?.let { driveCheck = CheckState.Failed(it.message ?: context.getString(R.string.sync_drive_unavailable)) }
                }
                refreshDriveLink()
            } finally {
                withContext(Dispatchers.Main) { driveSharing = false }
            }
        }
    }

    // ------------------------------------------------------------------ destinations

    fun setTarget(kind: SyncKind, target: OutputTarget, on: Boolean) = updateDestinations { d ->
        d.withTargets(kind, if (on) d.targetsFor(kind) + target else d.targetsFor(kind) - target)
    }

    fun setFormat(format: NotesExportFormat) = updateDestinations { it.copy(notesFormat = format) }
    fun setAutoSync(on: Boolean) = updateDestinations { it.copy(autoSync = on) }
    fun setAudioWifiOnly(on: Boolean) = updateDestinations { it.copy(audioOnUnmeteredOnly = on) }

    /** The work address for "Email to work" (already checked by the card); blank clears it. */
    fun setWorkEmail(address: String) = updateDestinations { it.copy(workEmail = WorkEmail.normalize(address)) }

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

    /**
     * Asks Google for Drive access; [enableFor] switches Drive on for that output once connected.
     * With an account already connected this is "Reconnect": Google asks for that account again
     * (access was withdrawn, the password changed, or a Testing-mode grant expired).
     */
    fun connectDrive(enableFor: SyncKind?) {
        if (driveCheck == CheckState.Running) return
        driveFor = enableFor
        driveCheck = CheckState.Running
        val account = settings.value.destinations.driveAccount
        viewModelScope.launch {
            try {
                when (val begin = PlayServicesDriveAuth.begin(context, account)) {
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

    /**
     * Google's account/consent screen closed with [resultCode] and [data]. [enableFor] is the
     * output the user tapped, kept by the screen (this view model may be new after process death).
     */
    fun onDriveConsent(resultCode: Int, data: Intent?, enableFor: SyncKind? = null) {
        driveConsent = null
        if (enableFor != null) driveFor = enableFor
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
        refreshDriveLink()
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
            refreshDriveLink()
            val cause = r.exceptionOrNull()?.cause
            if (cause is DriveException.NeedsSignIn || cause is DriveException.Unauthorized) {
                // Google wants the user to approve again: show its screen right away.
                driveCheck = CheckState.Idle
                connectDrive(enableFor = null)
                return@launch
            }
            driveCheck = r.fold({ CheckState.Ok(it) }, { e ->
                if (e.cause is DriveException.NotSetUp) driveNeedsSetup = true
                CheckState.Failed(e.message ?: context.getString(R.string.sync_drive_unavailable))
            })
        }
    }

    /** The user closed the setup steps that opened by themselves. */
    fun hideDriveSetup() {
        driveNeedsSetup = false
    }

    /** Where "Open Drive in browser" goes: the Inkwell folder once it exists. */
    fun driveUrl(): String = impl?.driveFolderUrl() ?: "https://drive.google.com/drive/my-drive"

    /** Stops copying to Drive and withdraws access; what is already in Drive stays. */
    fun disconnectDrive() {
        driveCheck = CheckState.Idle
        // App scope: leaving the screen must not leave Drive half disconnected.
        graph.appScope.launch { impl?.disconnectDrive() }
    }

    // ------------------------------------------------------------------ notes vault

    /** Saves a valid link shortly after typing stops; an empty field clears it. */
    fun onUrlChanged(text: String) {
        urlJob?.cancel()
        vaultCheck = CheckState.Idle
        pendingUrl = text
        urlJob = viewModelScope.launch {
            delay(URL_SAVE_DELAY_MS)
            saveUrl(text)
        }
    }

    fun commitUrl(text: String) {
        urlJob?.cancel()
        viewModelScope.launch { saveUrl(text) }
    }

    private suspend fun saveUrl(text: String) {
        try {
            val value = when (val p = VaultUrl.parse(text)) {
                is VaultUrl.Parsed.Valid -> p.endpoint.baseUrl
                VaultUrl.Parsed.Empty -> ""
                is VaultUrl.Parsed.Invalid -> return
            }
            val oldLink = settings.value.destinations.vaultUrl
            if (value != oldLink) {
                // The saved password stays tied to the link it was for and never goes to another
                // one; going back to that link brings it back (hasPassword follows the saved link).
                // Never forgotten here: this runs on every pause while the link is being edited. Only
                // the link the screen opened with claims a password not yet tied to one, never a
                // half-typed link saved on the way.
                if (oldLink == openingLink) {
                    withContext(Dispatchers.IO) { runCatching { VaultPassword.keepWith(graph.secrets, oldLink) } }
                }
                graph.settings.update { it.copy(destinations = it.destinations.copy(vaultUrl = value)) }
            }
        } finally {
            // Only now: until the link is saved, a password typed meanwhile is for it (passwordVault).
            if (pendingUrl == text) pendingUrl = null
        }
    }

    /** Saves the password to SecretStore shortly after typing stops. An empty field keeps the saved one. */
    fun onPasswordChanged(text: String) {
        passwordJob?.cancel()
        vaultCheck = CheckState.Idle
        if (text.isEmpty()) {
            pendingPassword = null
            return
        }
        pendingPassword = text
        passwordJob = viewModelScope.launch {
            delay(PASSWORD_SAVE_DELAY_MS)
            flushPassword()
        }
    }

    fun commitPassword() {
        passwordJob?.cancel()
        viewModelScope.launch { flushPassword() }
    }

    private suspend fun flushPassword() {
        val text = pendingPassword ?: return
        pendingPassword = null
        val vault = passwordVault()
        val saved = withContext(Dispatchers.IO) {
            runCatching { VaultPassword.save(graph.secrets, text, vault) }.isSuccess
        }
        if (saved) {
            passwordEdits++
            hasPassword = true
            hasAnyPassword = true
            impl?.onVaultCredentialsChanged()
        } else {
            notice = context.getString(R.string.sync_password_store_failed)
        }
    }

    /** The vault a password typed now belongs to: the link in the field (still being typed, or saved). */
    private fun passwordVault() =
        pendingUrl?.let { VaultUrl.endpointOrNull(it) } ?: VaultUrl.endpointOrNull(settings.value.destinations.vaultUrl)

    fun forgetPassword() {
        passwordJob?.cancel()
        pendingPassword = null
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                runCatching { VaultPassword.forget(graph.secrets) }
            }
            passwordEdits++
            hasPassword = false
            hasAnyPassword = false
            vaultCheck = CheckState.Idle
            // Releases a hold or lockout from the old password and retries what waited for it.
            impl?.onVaultCredentialsChanged()
        }
    }

    /** Test connection with what is in the fields (an empty password field uses the saved one). */
    fun testVault(urlText: String, passwordText: String) {
        if (vaultCheck == CheckState.Running) return
        vaultCheck = CheckState.Running
        urlJob?.cancel()
        passwordJob?.cancel()
        viewModelScope.launch {
            saveUrl(urlText)
            // A password typed and tested now is for this link: save it for it, so sync sends
            // what passed the test (and the test keeps its session and releases holds).
            if (passwordText.isNotEmpty()) pendingPassword = passwordText
            flushPassword()
            val r = graph.sync.testVault(urlText, passwordText)
            vaultCheck = r.fold({ CheckState.Ok(it) }, { CheckState.Failed(it.message ?: "The connection test failed.") })
        }
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

    override fun onCleared() {
        // Keep what was typed: finish pending saves outside the closing screen.
        val vault = passwordVault()
        val url = pendingUrl
        val password = pendingPassword
        pendingPassword = null
        if (url != null || password != null) {
            // In order: the link first, then the password typed for it.
            graph.appScope.launch {
                if (url != null) saveUrl(url)
                if (password != null) {
                    withContext(Dispatchers.IO) { runCatching { VaultPassword.save(graph.secrets, password, vault) } }
                    impl?.onVaultCredentialsChanged()
                }
            }
        }
        super.onCleared()
    }

    private companion object {
        const val URL_SAVE_DELAY_MS = 900L
        const val PASSWORD_SAVE_DELAY_MS = 700L
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
