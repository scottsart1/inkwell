package com.inkwell.notes.export.sync.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.inkwell.notes.R
import com.inkwell.notes.core.model.DestinationPrefs
import com.inkwell.notes.core.model.OutputTarget
import com.inkwell.notes.export.sync.uses
import com.inkwell.notes.ui.theme.Dimens
import com.inkwell.notes.ui.theme.Inkwell

@Composable
internal fun FolderCard(
    prefs: DestinationPrefs,
    check: CheckState,
    onChoose: () -> Unit,
    onTest: () -> Unit,
) {
    val c = Inkwell.colors
    val chosen = prefs.folderUri != null
    StorageCard(title = stringResource(R.string.sync_folder_title), subtitle = stringResource(R.string.sync_folder_subtitle), icon = Icons.Outlined.Folder) {
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Row(Modifier.widthIn(max = 360.dp).heightIn(min = Dimens.minTouch), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.FolderOpen, null, Modifier.size(Dimens.icon), tint = if (chosen) c.accent else c.inkMuted)
                Spacer(Modifier.width(10.dp))
                Text(
                    prefs.folderLabel?.takeIf { chosen } ?: stringResource(R.string.sync_folder_none),
                    style = MaterialTheme.typography.bodyLarge, color = if (chosen) c.ink else c.inkMuted,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            SecondaryAction(stringResource(if (chosen) R.string.sync_folder_change else R.string.sync_folder_choose), onChoose)
            if (chosen) SecondaryAction(
                stringResource(if (check == CheckState.Running) R.string.sync_testing else R.string.sync_folder_test),
                onTest, busy = check == CheckState.Running,
            )
        }
        CheckResult(check)
        Text(stringResource(R.string.sync_folder_layout), style = MaterialTheme.typography.bodySmall, color = c.inkMuted)
    }
}

@Composable
internal fun DriveCard(
    prefs: DestinationPrefs,
    check: CheckState,
    setup: DriveSetupInfo,
    setupOpen: Boolean,
    onConnect: () -> Unit,
    onTest: () -> Unit,
    onDisconnect: () -> Unit,
    onOpenDrive: () -> Unit,
    onCopied: (String) -> Unit,
    onHideSetup: () -> Unit = {},
) {
    val c = Inkwell.colors
    val account = prefs.driveAccount
    val busy = check == CheckState.Running
    var showSetup by rememberSaveable { mutableStateOf(false) }
    var confirmDisconnect by rememberSaveable { mutableStateOf(false) }
    StorageCard(title = stringResource(R.string.sync_drive_title), subtitle = stringResource(R.string.sync_drive_subtitle), icon = Icons.Outlined.CloudUpload) {
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Row(Modifier.widthIn(max = 360.dp).heightIn(min = Dimens.minTouch), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.AccountCircle, null, Modifier.size(Dimens.icon), tint = if (account != null) c.accent else c.inkMuted)
                Spacer(Modifier.width(10.dp))
                Text(
                    account ?: stringResource(R.string.sync_drive_not_connected),
                    style = MaterialTheme.typography.bodyLarge, color = if (account != null) c.ink else c.inkMuted,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            if (account == null) {
                PrimaryAction(stringResource(if (busy) R.string.sync_drive_connecting else R.string.sync_drive_connect), onConnect, busy = busy)
            } else {
                SecondaryAction(stringResource(if (busy) R.string.sync_testing else R.string.sync_folder_test), onTest, busy = busy)
                // Google may ask again (access withdrawn, password changed, Testing-mode expiry).
                QuietAction(stringResource(R.string.sync_drive_reconnect), onConnect, enabled = !busy)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    QuietAction(stringResource(R.string.sync_drive_open), onOpenDrive)
                    Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, Modifier.size(16.dp), tint = c.accent)
                }
                QuietAction(stringResource(R.string.sync_drive_disconnect), { confirmDisconnect = true }, color = c.danger, enabled = !busy)
            }
        }
        CheckResult(check)
        if (account != null && !prefs.uses(OutputTarget.DRIVE)) InfoLine(stringResource(R.string.sync_drive_pick_outputs))
        Text(stringResource(R.string.sync_drive_layout), style = MaterialTheme.typography.bodySmall, color = c.inkMuted)
        if (account == null || setupOpen) {
            HairlineDivider()
            val open = showSetup || setupOpen
            QuietAction(
                stringResource(if (open) R.string.sync_drive_setup_hide else R.string.sync_drive_setup_show),
                {
                    if (open) {
                        showSetup = false
                        onHideSetup()
                    } else {
                        showSetup = true
                    }
                },
                color = c.accent,
            )
            if (open) DriveSetupSteps(setup, onCopied)
        }
    }
    if (confirmDisconnect) {
        AlertDialog(
            onDismissRequest = { confirmDisconnect = false },
            containerColor = c.surface,
            shape = R12,
            title = { Text(stringResource(R.string.sync_drive_disconnect_title), style = MaterialTheme.typography.titleLarge, color = c.ink) },
            text = { Text(stringResource(R.string.sync_drive_disconnect_body), style = MaterialTheme.typography.bodyMedium, color = c.inkMuted) },
            confirmButton = { PrimaryAction(stringResource(R.string.sync_drive_disconnect), { confirmDisconnect = false; onDisconnect() }) },
            dismissButton = { QuietAction(stringResource(R.string.sync_cancel), { confirmDisconnect = false }, color = c.inkMuted) },
        )
    }
}

/** The one-time Google Cloud setup, with the two values Google asks for and a way to copy them. */
@Composable
private fun DriveSetupSteps(setup: DriveSetupInfo, onCopied: (String) -> Unit) {
    val c = Inkwell.colors
    val clipboard = LocalClipboardManager.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.sync_drive_setup_intro), style = MaterialTheme.typography.bodySmall, color = c.ink)
        listOf(
            R.string.sync_drive_setup_step1,
            R.string.sync_drive_setup_step2,
            R.string.sync_drive_setup_step3,
            R.string.sync_drive_setup_step4,
            R.string.sync_drive_setup_step5,
        ).forEachIndexed { i, step ->
            Row(verticalAlignment = Alignment.Top) {
                Text("${i + 1}.", style = MaterialTheme.typography.bodySmall, color = c.inkMuted, modifier = Modifier.width(20.dp))
                Text(stringResource(step), style = MaterialTheme.typography.bodySmall, color = c.ink)
            }
        }
        CopyValue(stringResource(R.string.sync_drive_setup_package), setup.packageName) {
            clipboard.setText(AnnotatedString(setup.packageName)); onCopied(setup.packageName)
        }
        val sha1 = setup.sha1
        if (sha1 != null) {
            CopyValue(stringResource(R.string.sync_drive_setup_sha1), sha1) { clipboard.setText(AnnotatedString(sha1)); onCopied(sha1) }
        }
        Text(stringResource(R.string.sync_drive_setup_privacy), style = MaterialTheme.typography.bodySmall, color = c.inkMuted)
    }
}

@Composable
private fun CopyValue(label: String, value: String, onCopy: () -> Unit) {
    val c = Inkwell.colors
    Row(Modifier.fillMaxWidth().heightIn(min = Dimens.minTouch), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = c.inkMuted)
            SelectionContainer {
                Text(value, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), color = c.ink)
            }
        }
        SmallIconButton(Icons.Outlined.ContentCopy, stringResource(R.string.sync_drive_copy, label), onCopy)
    }
}

@Composable
internal fun InfoLine(text: String) {
    val c = Inkwell.colors
    Row(verticalAlignment = Alignment.Top) {
        Icon(Icons.Outlined.Info, null, Modifier.size(16.dp), tint = c.warning)
        Spacer(Modifier.width(8.dp))
        Column { Text(text, style = MaterialTheme.typography.bodySmall, color = c.ink) }
    }
}
