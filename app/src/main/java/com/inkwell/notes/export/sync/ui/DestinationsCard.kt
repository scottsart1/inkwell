package com.inkwell.notes.export.sync.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.TabletAndroid
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.inkwell.notes.R
import com.inkwell.notes.core.model.DestinationPrefs
import com.inkwell.notes.core.model.NotesExportFormat
import com.inkwell.notes.core.model.OutputTarget
import com.inkwell.notes.export.sync.SyncKind
import com.inkwell.notes.export.sync.targetsFor
import com.inkwell.notes.ui.theme.Dimens
import com.inkwell.notes.ui.theme.Inkwell

/** "Where outputs are saved": notes, transcripts and audio, each with its destinations. */
@Composable
internal fun DestinationsCard(
    prefs: DestinationPrefs,
    onToggle: (SyncKind, OutputTarget, Boolean) -> Unit,
    onFormat: (NotesExportFormat) -> Unit,
) {
    StorageCard(title = stringResource(R.string.sync_where_title), subtitle = stringResource(R.string.sync_where_subtitle)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val narrow = maxWidth < 520.dp
            Column(verticalArrangement = Arrangement.spacedBy(if (narrow) 12.dp else 4.dp)) {
                DestinationRow(stringResource(R.string.sync_row_notes), SyncKind.NOTES, prefs, narrow, onToggle)
                DestinationRow(stringResource(R.string.sync_row_transcripts), SyncKind.TRANSCRIPT, prefs, narrow, onToggle)
                DestinationRow(stringResource(R.string.sync_row_audio), SyncKind.AUDIO, prefs, narrow, onToggle)
            }
        }
        HairlineDivider()
        val labels = mapOf(
            NotesExportFormat.PDF to stringResource(R.string.sync_format_pdf),
            NotesExportFormat.MARKDOWN to stringResource(R.string.sync_format_markdown),
            NotesExportFormat.PDF_AND_MARKDOWN to stringResource(R.string.sync_format_both),
        )
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.sync_notes_format),
                style = MaterialTheme.typography.bodyLarge, color = Inkwell.colors.ink,
                modifier = Modifier.width(ROW_LABEL_WIDTH),
            )
            Segmented(
                options = NotesExportFormat.entries.toList(),
                selected = prefs.notesFormat,
                label = { labels.getValue(it) },
                onSelect = onFormat,
            )
        }
    }
}

@Composable
private fun DestinationRow(
    label: String,
    kind: SyncKind,
    prefs: DestinationPrefs,
    narrow: Boolean,
    onToggle: (SyncKind, OutputTarget, Boolean) -> Unit,
) {
    val targets = prefs.targetsFor(kind)
    val chips: @Composable () -> Unit = {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            TargetChip(stringResource(R.string.sync_chip_device), selected = true, onClick = null, enabled = false, icon = Icons.Outlined.TabletAndroid)
            val folderOn = OutputTarget.FOLDER in targets
            TargetChip(stringResource(R.string.sync_chip_folder), selected = folderOn, onClick = { onToggle(kind, OutputTarget.FOLDER, !folderOn) }, icon = Icons.Outlined.Folder)
            val driveOn = OutputTarget.DRIVE in targets
            TargetChip(stringResource(R.string.sync_chip_drive), selected = driveOn, onClick = { onToggle(kind, OutputTarget.DRIVE, !driveOn) }, icon = Icons.Outlined.CloudUpload)
            val vaultOn = OutputTarget.VAULT in targets
            TargetChip(stringResource(R.string.sync_chip_vault), selected = vaultOn, onClick = { onToggle(kind, OutputTarget.VAULT, !vaultOn) }, icon = Icons.Outlined.Lock)
        }
    }
    val c = Inkwell.colors
    if (narrow) {
        Column {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = c.ink)
            chips()
        }
    } else {
        Row(Modifier.fillMaxWidth().heightIn(min = Dimens.minTouch), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = c.ink, modifier = Modifier.width(ROW_LABEL_WIDTH))
            chips()
        }
    }
}

private val ROW_LABEL_WIDTH = 184.dp
