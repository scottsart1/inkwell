package com.inkwell.notes.settings.sections

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.inkwell.notes.core.model.AiTask
import com.inkwell.notes.core.model.AppSettings
import com.inkwell.notes.core.model.NamingPrefs
import com.inkwell.notes.core.naming.DateNaming
import com.inkwell.notes.settings.ui.Choice
import com.inkwell.notes.settings.ui.Note
import com.inkwell.notes.settings.ui.Radius8
import com.inkwell.notes.settings.ui.RowDivider
import com.inkwell.notes.settings.ui.SegmentedRow
import com.inkwell.notes.settings.ui.SettingsActions
import com.inkwell.notes.settings.ui.SettingsGroup
import com.inkwell.notes.settings.ui.SwitchRow
import com.inkwell.notes.ui.theme.Inkwell

private const val EXAMPLE_TOPIC = "Monetary Policy"

@Composable
internal fun NamingSection(s: AppSettings, actions: SettingsActions) {
    val naming = s.naming
    val aiStatus by rememberAiTaskStatus(actions.graph, AiTask.NAMING, s.ai)
    fun set(transform: (NamingPrefs) -> NamingPrefs) = actions.update { it.copy(naming = transform(it.naming)) }
    // Books (or cancels) the next naming check once the change is saved: schedule() reads the
    // saved settings, so calling it straight away would still see the old values.
    fun setAndReschedule(transform: (NamingPrefs) -> NamingPrefs) = actions.update(
        onApplied = { actions.background("Couldn't schedule automatic naming.") { actions.graph.naming.schedule() } },
    ) { it.copy(naming = transform(it.naming)) }

    SectionColumn {
        SettingsGroup("How files are named") {
            NamingExplainer(Modifier.padding(16.dp))
        }

        SettingsGroup("Automatic naming") {
            SwitchRow(
                "Name files automatically",
                naming.autoNameEnabled,
                { v -> setAndReschedule { it.copy(autoNameEnabled = v) } },
                subtitle = "Untitled files get a short topic once they have enough content.",
            )
            RowDivider()
            SegmentedRow(
                title = "Wait before naming",
                subtitle = "Time to keep writing before a topic is chosen. Files without enough content stay Untitled and are checked again later.",
                choices = listOf(Choice(12, "12 hours"), Choice(24, "24 hours"), Choice(48, "48 hours")),
                selected = naming.delayHours,
                onSelect = { h -> setAndReschedule { it.copy(delayHours = h) } },
                enabled = naming.autoNameEnabled,
            )
            RowDivider()
            SwitchRow(
                "Use AI when available",
                naming.useAi,
                { v -> set { it.copy(useAi = v) } },
                subtitle = "Asks your auto-naming model for the topic. Without a key, topics come from keywords found on this device.",
                enabled = naming.autoNameEnabled,
            )
            if (naming.autoNameEnabled && naming.useAi) {
                val status = aiStatus
                if (status != null) {
                    Note(
                        when {
                            !status.available -> "No AI model is set up, so naming uses on-device keywords. Nothing is sent anywhere."
                            // Background naming cannot ask first, so it does not send content while confirmation is on.
                            s.privacy.confirmAiTransmission ->
                                "Using ${status.modelName ?: "your auto-naming model"} when you ask for a name. Automatic naming uses " +
                                    "on-device keywords while \"Confirm before sending content to AI\" is on in Privacy."
                            else -> "Using ${status.modelName ?: "your auto-naming model"}."
                        },
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                    )
                }
            }
            RowDivider()
            SwitchRow(
                "Rename imported files too",
                naming.renameImported,
                { v -> set { it.copy(renameImported = v) } },
                subtitle = "Off keeps the original names of imported PDFs and images.",
                enabled = naming.autoNameEnabled,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NamingExplainer(modifier: Modifier = Modifier) {
    val c = Inkwell.colors
    val now = remember { System.currentTimeMillis() }
    val before = remember(now) { DateNaming.initialTitle(now) }
    val after = remember(now) { DateNaming.titleFor(now, EXAMPLE_TOPIC) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "Every new file is named YYYYMMDD_Topic. The date is the day you created it and never changes. The topic starts as Untitled and is filled in from what you write.",
            style = MaterialTheme.typography.bodyMedium,
            color = c.ink,
        )
        FlowRow(
            Modifier.semantics { contentDescription = "Example: $before becomes $after" },
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            NameChip(before, muted = true)
            Text("becomes", style = MaterialTheme.typography.bodyMedium, color = c.inkMuted, modifier = Modifier.align(Alignment.CenterVertically))
            Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, Modifier.size(16.dp), tint = c.inkMuted)
            NameChip(after, muted = false)
        }
        Note("Rename a file yourself and Inkwell never renames it again.")
    }
}

@Composable
private fun NameChip(text: String, muted: Boolean) {
    val c = Inkwell.colors
    Text(
        text,
        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
        color = if (muted) c.inkMuted else c.ink,
        modifier = Modifier
            .background(if (muted) c.surfaceAlt else c.accentContainer, Radius8)
            .border(1.dp, if (muted) c.outline else c.accent.copy(alpha = 0.5f), Radius8)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}
