package com.inkwell.notes.audio.ui.panel

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.inkwell.notes.R
import com.inkwell.notes.audio.logic.AudioText
import com.inkwell.notes.audio.ui.dock.RecordingDot
import com.inkwell.notes.core.model.Recording
import com.inkwell.notes.core.model.RecordingStatus
import com.inkwell.notes.core.model.TranscriptStatus
import com.inkwell.notes.ui.theme.Dimens
import com.inkwell.notes.ui.theme.Inkwell
import java.util.Date

/** One recording in the list: title (inline rename), date · duration, badges, menu. */
@Composable
internal fun RecordingRow(
    rec: Recording,
    liveElapsedMs: Long?,
    livePaused: Boolean,
    renaming: Boolean,
    exporting: Boolean,
    onOpen: () -> Unit,
    onRename: (String) -> Unit,
    onStartRename: () -> Unit,
    onCancelRename: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
    newVoices: Boolean = false,
    onEmail: (() -> Unit)? = null,
) {
    val c = Inkwell.colors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clip(R8)
            .clickable(enabled = !renaming, role = Role.Button, onClick = onOpen)
            .padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(32.dp).clip(CircleShape).background(c.surfaceAlt), contentAlignment = Alignment.Center) {
            if (liveElapsedMs != null) RecordingDot(paused = livePaused)
            else Icon(Icons.Outlined.GraphicEq, null, Modifier.size(18.dp), tint = c.inkMuted)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (renaming) {
                RenameField(rec.title, onRename, onCancelRename)
            } else {
                Text(rec.title, style = MaterialTheme.typography.titleSmall, color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            RecordingMeta(rec, liveElapsedMs, newVoices)
        }
        if (!renaming) {
            RecordingMenu(
                exporting = exporting,
                canExport = liveElapsedMs == null && rec.segmentFiles.isNotEmpty(),
                onRename = onStartRename,
                onExport = onExport,
                onDelete = onDelete,
                onEmail = onEmail.takeIf { liveElapsedMs == null && rec.transcriptStatus == TranscriptStatus.DONE },
            )
        }
    }
}

@Composable
internal fun RecordingMeta(rec: Recording, liveElapsedMs: Long?, newVoices: Boolean = false) {
    val c = Inkwell.colors
    val context = LocalContext.current
    val date = remember(rec.startedAt) {
        val d = Date(rec.startedAt)
        DateFormat.getMediumDateFormat(context).format(d) + " · " + DateFormat.getTimeFormat(context).format(d)
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val duration = AudioText.clock(liveElapsedMs ?: rec.durationMs)
        Text("$date · $duration", style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"), color = c.inkMuted)
        if (liveElapsedMs != null) AudioBadge(stringResource(R.string.audio_badge_recording), color = c.record)
        if (rec.status == RecordingStatus.INTERRUPTED) AudioBadge(stringResource(R.string.audio_badge_recovered), color = c.warning)
        when (rec.transcriptStatus) {
            TranscriptStatus.DONE -> AudioBadge(stringResource(R.string.audio_status_transcribed))
            TranscriptStatus.RUNNING -> AudioBadge(stringResource(R.string.audio_status_transcribing), color = c.accent)
            TranscriptStatus.QUEUED -> AudioBadge(stringResource(R.string.audio_status_queued))
            TranscriptStatus.FAILED -> AudioBadge(stringResource(R.string.audio_status_failed), color = c.danger)
            TranscriptStatus.NONE -> Unit
        }
        if (newVoices) AudioBadge(stringResource(R.string.spk_badge_new_voices), color = c.accent)
    }
}

@Composable
internal fun RenameField(current: String, onRename: (String) -> Unit, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    var value by remember { mutableStateOf(TextFieldValue(current, TextRange(0, current.length))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = value,
            onValueChange = { value = it },
            singleLine = true,
            label = { Text(stringResource(R.string.audio_rename_hint)) },
            textStyle = MaterialTheme.typography.bodyMedium,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onRename(value.text) }),
            shape = R8,
            colors = audioFieldColors(),
            modifier = Modifier.weight(1f).focusRequester(focus),
        )
        AudioIconButton(Icons.Outlined.Check, stringResource(R.string.audio_rename_save), { onRename(value.text) }, tint = Inkwell.colors.accent)
        AudioIconButton(Icons.Outlined.Close, stringResource(R.string.audio_cancel), onCancel)
    }
}

@Composable
internal fun RecordingMenu(
    exporting: Boolean,
    canExport: Boolean,
    onRename: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
    /** "Email transcript to work"; null hides it (no finished transcript). */
    onEmail: (() -> Unit)? = null,
) {
    val c = Inkwell.colors
    var open by remember { mutableStateOf(false) }
    Box {
        AudioIconButton(Icons.Outlined.MoreVert, stringResource(R.string.audio_more), { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = c.surface) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.audio_rename), color = c.ink) },
                leadingIcon = { Icon(Icons.Outlined.Edit, null, Modifier.size(Dimens.icon), tint = c.inkMuted) },
                onClick = { open = false; onRename() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(if (exporting) R.string.audio_export_preparing else R.string.audio_export), color = if (canExport && !exporting) c.ink else c.inkMuted) },
                leadingIcon = { Icon(Icons.Outlined.IosShare, null, Modifier.size(Dimens.icon), tint = c.inkMuted) },
                enabled = canExport && !exporting,
                onClick = { open = false; onExport() },
            )
            if (onEmail != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.work_email_transcript_action), color = c.ink) },
                    leadingIcon = { Icon(Icons.Outlined.Email, null, Modifier.size(Dimens.icon), tint = c.inkMuted) },
                    onClick = { open = false; onEmail() },
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.audio_delete), color = c.danger) },
                leadingIcon = { Icon(Icons.Outlined.Delete, null, Modifier.size(Dimens.icon), tint = c.danger) },
                onClick = { open = false; onDelete() },
            )
        }
    }
}
