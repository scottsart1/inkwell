package com.inkwell.notes.export.sync.ui

import android.content.ActivityNotFoundException
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.inkwell.notes.R
import com.inkwell.notes.app.AppGraph
import com.inkwell.notes.core.model.OutputTarget
import com.inkwell.notes.export.sync.SyncKind
import com.inkwell.notes.ui.theme.Dimens
import com.inkwell.notes.ui.theme.Inkwell

/** MIME types offered when picking a backup to restore. */
private val BACKUP_TYPES = arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")

/**
 * "Storage, sync & backup" detail pane. Scrolls itself when given a bounded height; lays out
 * flat inside a host that already scrolls.
 */
@Composable
internal fun StorageSettingsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val graph = AppGraph.get(context)
    val vm: StorageSettingsViewModel = viewModel(key = "inkwell-storage-settings") { StorageSettingsViewModel(graph) }
    val settings by vm.settings.collectAsState()
    val status by vm.status.collectAsState()
    val failed by vm.failedCount.collectAsState()
    val backup by vm.backup.collectAsState()
    val prefs = settings.destinations

    var folderFor by rememberSaveable { mutableStateOf<String?>(null) }
    // The output whose Drive chip started the connection: survives process death during consent.
    var driveFor by rememberSaveable { mutableStateOf<String?>(null) }
    var restoreUri by rememberSaveable { mutableStateOf<String?>(null) }
    val pickerMissing = stringResource(R.string.sync_no_picker)

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val kind = folderFor?.let { runCatching { SyncKind.valueOf(it) }.getOrNull() }
        folderFor = null
        if (uri != null) vm.onFolderPicked(uri, kind)
    }
    val backupPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.backupTo(uri)
    }
    val restorePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) restoreUri = uri.toString()
    }
    val autoBackupPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.enableAutoBackup(uri)
    }
    val driveConsent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val kind = driveFor?.let { runCatching { SyncKind.valueOf(it) }.getOrNull() }
        driveFor = null
        vm.onDriveConsent(result.resultCode, result.data, kind)
    }
    // Google's account and consent screen, when the view model asks for it.
    val consent = vm.driveConsent
    LaunchedEffect(consent) {
        val pending = if (consent != null) vm.takeDriveConsent() else null
        if (pending != null) {
            try {
                driveConsent.launch(IntentSenderRequest.Builder(pending.intentSender).build())
            } catch (e: Exception) {
                vm.onDriveConsentUnavailable()
            }
        }
    }
    val uriHandler = LocalUriHandler.current

    fun launchTree(launcher: androidx.activity.result.ActivityResultLauncher<Uri?>, initial: String?) {
        try {
            launcher.launch(initial?.let { runCatching { Uri.parse(it) }.getOrNull() })
        } catch (e: ActivityNotFoundException) {
            vm.notice = pickerMissing
        }
    }

    BoxWithConstraints(modifier) {
        val scroll = if (constraints.hasBoundedHeight) Modifier.verticalScroll(rememberScrollState()) else Modifier
        Column(
            Modifier.fillMaxWidth().then(scroll).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(Modifier.widthIn(max = MAX_CONTENT_WIDTH), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                vm.notice?.let { Notice(it) { vm.notice = null } }
                DestinationsCard(
                    prefs = prefs,
                    onToggle = { kind, target, on ->
                        if (target == OutputTarget.FOLDER && on && prefs.folderUri == null) {
                            folderFor = kind.name
                            launchTree(folderPicker, null)
                        } else if (target == OutputTarget.DRIVE && on && prefs.driveAccount == null) {
                            driveFor = kind.name
                            vm.connectDrive(enableFor = kind)
                        } else {
                            vm.setTarget(kind, target, on)
                        }
                    },
                    onFormat = vm::setFormat,
                )
                FolderCard(
                    prefs = prefs,
                    check = vm.folderCheck,
                    onChoose = { folderFor = null; launchTree(folderPicker, prefs.folderUri) },
                    onTest = vm::testFolder,
                )
                DriveCard(
                    prefs = prefs,
                    check = vm.driveCheck,
                    setup = vm.driveSetup,
                    setupOpen = vm.driveNeedsSetup,
                    onConnect = { driveFor = null; vm.connectDrive(enableFor = null) },
                    onHideSetup = vm::hideDriveSetup,
                    sharedLink = vm.driveSharedLink,
                    sharing = vm.driveSharing,
                    onShareLink = vm::setDriveShareLink,
                    onSendLink = { link ->
                        val send = android.content.Intent(android.content.Intent.ACTION_SEND)
                            .setType("text/plain")
                            .putExtra(android.content.Intent.EXTRA_TEXT, link)
                        try {
                            context.startActivity(android.content.Intent.createChooser(send, null))
                        } catch (e: ActivityNotFoundException) {
                            vm.notice = pickerMissing
                        }
                    },
                    onTest = vm::testDrive,
                    onDisconnect = vm::disconnectDrive,
                    onOpenDrive = {
                        try {
                            uriHandler.openUri(vm.driveUrl())
                        } catch (e: Exception) {
                            vm.notice = context.getString(R.string.sync_drive_browser_error)
                        }
                    },
                    onCopied = { vm.notice = null },
                )
                VaultCard(
                    prefs = prefs,
                    hasPassword = vm.hasPassword,
                    hasAnyPassword = vm.hasAnyPassword,
                    check = vm.vaultCheck,
                    onUrlChanged = vm::onUrlChanged,
                    onUrlCommit = vm::commitUrl,
                    onPasswordChanged = vm::onPasswordChanged,
                    onPasswordCommit = vm::commitPassword,
                    onForgetPassword = vm::forgetPassword,
                    onTest = vm::testVault,
                    onBrowserError = { vm.notice = context.getString(R.string.sync_vault_browser_error) },
                )
                WorkEmailCard(saved = prefs.workEmail, onSave = vm::setWorkEmail)
                SyncCard(
                    prefs = prefs,
                    status = status,
                    failed = failed,
                    syncRequested = vm.syncRequested,
                    onAutoSync = vm::setAutoSync,
                    onWifiOnly = vm::setAudioWifiOnly,
                    onSyncNow = vm::syncNow,
                )
                BackupCard(
                    state = backup,
                    autoBackup = vm.autoBackup,
                    onBackupNow = { launchTree(backupPicker, null) },
                    onRestore = {
                        try {
                            restorePicker.launch(BACKUP_TYPES)
                        } catch (e: ActivityNotFoundException) {
                            vm.notice = pickerMissing
                        }
                    },
                    onAutoBackup = { on -> if (on) launchTree(autoBackupPicker, null) else vm.disableAutoBackup() },
                    onChangeAutoFolder = { launchTree(autoBackupPicker, vm.autoBackup?.uri) },
                    onDismissResult = vm::dismissBackupResult,
                    autoStatus = vm.autoBackupStatus,
                )
            }
        }
    }

    restoreUri?.let { pending ->
        RestoreConfirmDialog(
            onConfirm = {
                restoreUri = null
                vm.restoreFrom(Uri.parse(pending))
            },
            onDismiss = { restoreUri = null },
        )
    }
}

@Composable
private fun RestoreConfirmDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val c = Inkwell.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        shape = R12,
        title = { Text(stringResource(R.string.sync_restore_confirm_title), style = MaterialTheme.typography.titleLarge, color = c.ink) },
        text = { Text(stringResource(R.string.sync_restore_confirm_body), style = MaterialTheme.typography.bodyMedium, color = c.inkMuted) },
        confirmButton = { PrimaryAction(stringResource(R.string.sync_restore_confirm), onConfirm) },
        dismissButton = { QuietAction(stringResource(R.string.sync_cancel), onDismiss, color = c.inkMuted) },
    )
}

@Composable
private fun Notice(text: String, onDismiss: () -> Unit) {
    val c = Inkwell.colors
    Surface(Modifier.fillMaxWidth(), shape = R8, color = c.surfaceAlt, border = BorderStroke(1.dp, c.outline)) {
        Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.ErrorOutline, null, Modifier.size(Dimens.icon), tint = c.danger)
            Spacer(Modifier.width(10.dp))
            Text(text, style = MaterialTheme.typography.bodySmall, color = c.ink, modifier = Modifier.weight(1f))
            QuietAction(stringResource(R.string.sync_dismiss), onDismiss, color = c.inkMuted)
        }
    }
}

private val MAX_CONTENT_WIDTH = 760.dp
