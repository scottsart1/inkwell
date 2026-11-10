package com.inkwell.notes.export.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.inkwell.notes.export.sync.ui.StorageSettingsScreen

/**
 * "Where outputs are saved": per output type (notes, transcripts, audio) choose this device
 * only, a folder (SAF), Google Drive (connect, test, disconnect) and/or the yourpitbox.com notes
 * vault (private link + password, test connection),
 * sync now, backup/restore. Scrolls itself when given a bounded height; lays out flat inside a
 * host that already scrolls.
 */
@Composable
fun StorageSettingsContent(modifier: Modifier = Modifier) {
    StorageSettingsScreen(modifier)
}
