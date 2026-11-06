package com.inkwell.notes.export.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.inkwell.notes.export.sync.ui.StorageSettingsScreen

/**
 * "Where outputs are saved": per output type (notes, transcripts, audio) choose this device
 * only, a folder (SAF), and/or Google Drive (connect, test, disconnect),
 * sync now, backup/restore. Scrolls itself when given a bounded height; lays out flat inside a
 * host that already scrolls.
 */
@Composable
fun StorageSettingsContent(modifier: Modifier = Modifier) {
    StorageSettingsScreen(modifier)
}
