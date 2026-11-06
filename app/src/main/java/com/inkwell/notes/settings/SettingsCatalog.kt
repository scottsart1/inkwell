package com.inkwell.notes.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ViewQuilt
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.Draw
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.ui.graphics.vector.ImageVector
import com.inkwell.notes.app.SettingsSection

/** Icons, one-line summaries and grouping of the settings sections. */
internal object SettingsCatalog {
    data class Group(val title: String, val sections: List<SettingsSection>)

    private val declared = listOf(
        Group("Writing", listOf(SettingsSection.GENERAL, SettingsSection.INPUT, SettingsSection.TOOLBAR, SettingsSection.NAMING, SettingsSection.PAPER)),
        Group("Capture", listOf(SettingsSection.RECORDING, SettingsSection.SPEAKERS, SettingsSection.OCR)),
        Group("AI", listOf(SettingsSection.AI, SettingsSection.USAGE)),
        Group("Your data", listOf(SettingsSection.STORAGE, SettingsSection.PRIVACY, SettingsSection.ABOUT)),
    )

    /** Every section exactly once; sections added to the enum later land in "More". */
    val groups: List<Group> = run {
        val listed = declared.flatMap { it.sections }.toSet()
        val rest = SettingsSection.entries.filterNot { it in listed }
        if (rest.isEmpty()) declared else declared + Group("More", rest)
    }

    fun icon(section: SettingsSection): ImageVector = when (section) {
        SettingsSection.GENERAL -> Icons.Outlined.Tune
        SettingsSection.INPUT -> Icons.Outlined.Draw
        SettingsSection.TOOLBAR -> Icons.AutoMirrored.Outlined.ViewQuilt
        SettingsSection.NAMING -> Icons.Outlined.DriveFileRenameOutline
        SettingsSection.PAPER -> Icons.Outlined.Description
        SettingsSection.RECORDING -> Icons.Outlined.Mic
        SettingsSection.SPEAKERS -> Icons.Outlined.RecordVoiceOver
        SettingsSection.OCR -> Icons.Outlined.DocumentScanner
        SettingsSection.AI -> Icons.Outlined.AutoAwesome
        SettingsSection.USAGE -> Icons.Outlined.Insights
        SettingsSection.STORAGE -> Icons.Outlined.Storage
        SettingsSection.PRIVACY -> Icons.Outlined.Lock
        SettingsSection.ABOUT -> Icons.Outlined.Info
    }

    /** One-line summary of "Voices & speakers". */
    const val SPEAKERS_SUMMARY = "Who's speaking, voice models and people"

    fun summary(section: SettingsSection): String = when (section) {
        SettingsSection.GENERAL -> "Theme, screen and page scrolling"
        SettingsSection.INPUT -> "S Pen, palm rejection, gestures and pressure"
        SettingsSection.TOOLBAR -> "Toolbar position, focus mode and pen presets"
        SettingsSection.NAMING -> "Automatic YYYYMMDD_Topic file names"
        SettingsSection.PAPER -> "Default page size, paper and lines"
        SettingsSection.RECORDING -> "Recorder, audio quality and transcription"
        SettingsSection.SPEAKERS -> SPEAKERS_SUMMARY
        SettingsSection.OCR -> "Handwriting languages, OCR and search index"
        SettingsSection.AI -> "Your own keys, models and routing"
        SettingsSection.USAGE -> "Tokens and estimated cost"
        SettingsSection.STORAGE -> "Folders, Google Drive and backups"
        SettingsSection.PRIVACY -> "App lock, screenshots and AI data"
        SettingsSection.ABOUT -> "Version, storage and licences"
    }
}
