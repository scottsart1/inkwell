package com.inkwell.notes.export.sync.ui

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.inkwell.notes.R
import com.inkwell.notes.core.model.DestinationPrefs
import com.inkwell.notes.export.SyncStatus
import com.inkwell.notes.export.backup.AutoBackupStatus
import com.inkwell.notes.export.sync.hasAnyTarget
import com.inkwell.notes.ui.theme.Inkwell
import kotlinx.coroutines.delay

@Composable
internal fun SyncCard(
    prefs: DestinationPrefs,
    status: SyncStatus,
    failed: Int,
    syncRequested: Boolean,
    onAutoSync: (Boolean) -> Unit,
    onWifiOnly: (Boolean) -> Unit,
    onSyncNow: () -> Unit,
) {
    val c = Inkwell.colors
    StorageCard(title = stringResource(R.string.sync_section_title), icon = Icons.Outlined.Sync) {
        SettingSwitch(
            stringResource(R.string.sync_auto_title), prefs.autoSync, onAutoSync,
            subtitle = stringResource(if (prefs.autoSync) R.string.sync_auto_on else R.string.sync_auto_off),
        )
        SettingSwitch(
            stringResource(R.string.sync_wifi_title), prefs.audioOnUnmeteredOnly, onWifiOnly,
            subtitle = stringResource(R.string.sync_wifi_subtitle),
        )
        HairlineDivider()
        val busy = status.running || syncRequested
        val now by produceState(System.currentTimeMillis()) {
            while (true) {
                delay(30_000)
                value = System.currentTimeMillis()
            }
        }
        val active = prefs.hasAnyTarget
        val last = if (status.lastSuccessAt > 0) {
            stringResource(R.string.sync_status_last, DateUtils.getRelativeTimeSpanString(status.lastSuccessAt, now.coerceAtLeast(status.lastSuccessAt), DateUtils.MINUTE_IN_MILLIS).toString())
        } else null
        val headline = when {
            !active -> stringResource(R.string.sync_status_off)
            busy -> stringResource(R.string.sync_syncing)
            status.pending > 0 -> listOfNotNull(pluralStringResource(R.plurals.sync_status_waiting, status.pending, status.pending), last).joinToString(" · ")
            status.lastSuccessAt > 0 -> listOfNotNull(stringResource(R.string.sync_status_up_to_date), last).joinToString(" · ")
            else -> stringResource(R.string.sync_status_never)
        }
        val dot = when {
            !active -> c.inkMuted
            busy -> c.accent
            status.lastError != null || failed > 0 -> c.danger
            status.pending > 0 -> c.warning
            else -> c.success
        }
        val statusText: @Composable (Modifier) -> Unit = { modifier ->
            Row(modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }, verticalAlignment = Alignment.Top) {
                Column(Modifier.padding(top = 6.dp)) { Dot(dot) }
                Spacer(Modifier.width(10.dp))
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(headline, style = MaterialTheme.typography.bodyMedium, color = if (active) c.ink else c.inkMuted)
                    if (active && failed > 0) {
                        Text(pluralStringResource(R.plurals.sync_status_failed, failed, failed), style = MaterialTheme.typography.bodySmall, color = c.danger)
                    }
                    val error = status.lastError
                    if (active && error != null && !busy) Text(error, style = MaterialTheme.typography.bodySmall, color = c.danger)
                }
            }
        }
        val button: @Composable () -> Unit = {
            PrimaryAction(
                stringResource(if (busy) R.string.sync_syncing else R.string.sync_now), onSyncNow,
                enabled = active, busy = busy, icon = Icons.Outlined.Sync,
            )
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxWidth < 440.dp) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    statusText(Modifier.fillMaxWidth())
                    button()
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    statusText(Modifier.weight(1f).padding(end = 16.dp))
                    button()
                }
            }
        }
    }
}

@Composable
internal fun BackupCard(
    state: BackupState,
    autoBackup: AutoBackupPref?,
    onBackupNow: () -> Unit,
    onRestore: () -> Unit,
    onAutoBackup: (Boolean) -> Unit,
    onChangeAutoFolder: () -> Unit,
    onDismissResult: () -> Unit,
    autoStatus: AutoBackupStatus = AutoBackupStatus(),
) {
    val c = Inkwell.colors
    val running = state is BackupState.Running
    StorageCard(title = stringResource(R.string.sync_backup_title), icon = Icons.Outlined.Backup) {
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SecondaryAction(stringResource(R.string.sync_backup_now), onBackupNow, enabled = !running, icon = Icons.Outlined.Backup)
            SecondaryAction(stringResource(R.string.sync_backup_restore), onRestore, enabled = !running, icon = Icons.Outlined.Restore)
        }
        when (state) {
            is BackupState.Running -> Column(
                Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    stringResource(if (state.restore) R.string.sync_restore_running else R.string.sync_backup_running),
                    style = MaterialTheme.typography.bodySmall, color = c.inkMuted,
                )
                LinearProgressIndicator(Modifier.fillMaxWidth(), color = c.accent, trackColor = c.surfaceAlt)
            }
            is BackupState.Done -> ResultRow(CheckState.Ok(state.message), onDismissResult)
            is BackupState.Failed -> ResultRow(CheckState.Failed(state.message), onDismissResult)
            BackupState.Idle -> Unit
        }
        HairlineDivider()
        SettingSwitch(
            stringResource(R.string.sync_backup_auto),
            checked = autoBackup != null,
            onChange = onAutoBackup,
            subtitle = autoBackup?.let { stringResource(R.string.sync_backup_auto_on, it.label.ifBlank { "the chosen folder" }) }
                ?: stringResource(R.string.sync_backup_auto_off),
        )
        if (autoBackup != null) {
            AutoBackupStatusText(autoStatus)
            QuietAction(stringResource(R.string.sync_backup_change), onChangeAutoFolder)
        }
        Text(stringResource(R.string.sync_backup_explainer), style = MaterialTheme.typography.bodySmall, color = c.inkMuted)
    }
}

/** Last complete automatic backup, and whether the last run left documents out or failed. */
@Composable
private fun AutoBackupStatusText(status: AutoBackupStatus) {
    val c = Inkwell.colors
    val problem = when (status.problem) {
        AutoBackupStatus.Problem.NONE -> null
        AutoBackupStatus.Problem.SKIPPED -> pluralStringResource(R.plurals.sync_backup_auto_skipped, status.lastSkipped, status.lastSkipped)
        AutoBackupStatus.Problem.FAILED -> stringResource(R.string.sync_backup_auto_failed)
    }
    if (status.lastSuccessAt <= 0 && problem == null) return
    Column(
        Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (status.lastSuccessAt > 0) {
            val now = System.currentTimeMillis()
            val last = DateUtils.getRelativeTimeSpanString(status.lastSuccessAt, now.coerceAtLeast(status.lastSuccessAt), DateUtils.MINUTE_IN_MILLIS).toString()
            Text(stringResource(R.string.sync_backup_auto_last, last), style = MaterialTheme.typography.bodySmall, color = c.inkMuted)
        }
        if (problem != null) Text(problem, style = MaterialTheme.typography.bodySmall, color = c.danger)
    }
}

@Composable
private fun ResultRow(check: CheckState, onDismiss: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        CheckResult(check, Modifier.weight(1f))
        QuietAction(stringResource(R.string.sync_dismiss), onDismiss, color = Inkwell.colors.inkMuted)
    }
}
