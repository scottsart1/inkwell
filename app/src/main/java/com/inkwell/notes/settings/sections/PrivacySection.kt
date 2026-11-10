package com.inkwell.notes.settings.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.inkwell.notes.core.model.AiProviderId
import com.inkwell.notes.core.model.AppSettings
import com.inkwell.notes.core.model.PrivacyPrefs
import com.inkwell.notes.core.settings.SecretStore
import com.inkwell.notes.settings.lock.AppLockManager
import com.inkwell.notes.settings.lock.AuthAvailability
import com.inkwell.notes.settings.lock.AuthErrors
import com.inkwell.notes.settings.lock.AuthFailureKind
import com.inkwell.notes.settings.lock.Biometrics
import com.inkwell.notes.settings.lock.LockPolicy
import com.inkwell.notes.settings.logic.SettingsFormat
import com.inkwell.notes.settings.ui.ActionRow
import com.inkwell.notes.settings.ui.Choice
import com.inkwell.notes.settings.ui.ConfirmDialog
import com.inkwell.notes.settings.ui.Note
import com.inkwell.notes.settings.ui.NoteTone
import com.inkwell.notes.settings.ui.QuietButton
import com.inkwell.notes.settings.ui.RowDivider
import com.inkwell.notes.settings.ui.ChipsRow
import com.inkwell.notes.settings.ui.SettingsActions
import com.inkwell.notes.settings.ui.SettingsGroup
import com.inkwell.notes.settings.ui.SwitchRow
import com.inkwell.notes.ui.theme.Inkwell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private enum class PrivacyConfirm { DELETE_KEYS, CLEAR_USAGE }

@Composable
internal fun PrivacySection(s: AppSettings, actions: SettingsActions) {
    val context = LocalContext.current
    val graph = actions.graph
    val privacy = s.privacy
    var enrollHelp by rememberSaveable { mutableStateOf<String?>(null) }
    var confirm by rememberSaveable { mutableStateOf<PrivacyConfirm?>(null) }
    var keysVersion by remember { mutableIntStateOf(0) }
    val storedKeys by produceState<Int?>(null, keysVersion) {
        value = withContext(Dispatchers.IO) {
            runCatching { AiProviderId.entries.count { graph.secrets.has(SecretStore.aiKey(it)) } }.getOrNull()
        }
    }
    fun set(transform: (PrivacyPrefs) -> PrivacyPrefs) = actions.update { it.copy(privacy = transform(it.privacy)) }

    fun enableLock() {
        val activity = Biometrics.findActivity(context)
        if (activity == null) {
            actions.message("App lock can only be turned on from the Inkwell window.")
            return
        }
        when (val availability = Biometrics.availability(context)) {
            AuthAvailability.READY -> Biometrics.authenticate(
                activity = activity,
                title = "Turn on app lock",
                subtitle = "Confirm it's you to lock Inkwell",
                onSuccess = {
                    enrollHelp = null
                    set { it.copy(appLock = true) }
                    actions.message("App lock is on")
                },
                onFailure = { failure ->
                    when (failure.kind) {
                        AuthFailureKind.CANCELLED -> Unit
                        AuthFailureKind.NOT_SET_UP -> enrollHelp = failure.message
                        AuthFailureKind.RETRY -> actions.message(failure.message ?: "Couldn't verify you. Try again.")
                    }
                },
            )
            AuthAvailability.NOT_SET_UP, AuthAvailability.UNSUPPORTED -> enrollHelp = AuthErrors.availabilityMessage(availability)
            else -> actions.message(AuthErrors.availabilityMessage(availability) ?: "Couldn't verify you. Try again.")
        }
    }

    SectionColumn {
        SettingsGroup("App lock") {
            SwitchRow(
                "Lock Inkwell",
                privacy.appLock,
                { on ->
                    if (on) {
                        enableLock()
                    } else {
                        set { it.copy(appLock = false) }
                        AppLockManager.onLockDisabled()
                    }
                },
                subtitle = "Ask for your fingerprint, face or screen lock when you open Inkwell. Turning it on asks you to confirm first.",
            )
            enrollHelp?.let { help ->
                Note(
                    help,
                    tone = NoteTone.WARNING,
                    icon = Icons.Outlined.WarningAmber,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                ) {
                    QuietButton("Open Android settings", onClick = {
                        if (!Biometrics.openSecuritySettings(context)) actions.message("Couldn't open Android settings.")
                    })
                }
            }
            RowDivider()
            ChipsRow(
                title = "Lock after",
                subtitle = "How long Inkwell can stay in the background before it locks. Returning from a file picker or share sheet gets a few minutes' grace.",
                choices = LockPolicy.DELAY_CHOICES.map { sec ->
                    Choice(sec, if (sec == 0) "Immediately" else "After ${SettingsFormat.minutesLabel(sec)}")
                },
                selected = privacy.lockAfterSeconds,
                onSelect = { sec -> set { it.copy(lockAfterSeconds = sec) } },
                enabled = privacy.appLock,
            )
            RowDivider()
            ActionRow(
                title = "Lock now",
                subtitle = "Recording keeps running while Inkwell is locked.",
                actionLabel = "Lock",
                onAction = { AppLockManager.lockNow() },
                enabled = privacy.appLock,
            )
        }

        SettingsGroup("Screen privacy") {
            SwitchRow(
                "Block screenshots and recents preview",
                privacy.blockScreenshots,
                { v -> set { it.copy(blockScreenshots = v) } },
                subtitle = "Hides Inkwell in screenshots, screen recordings, screen sharing and the recent apps view.",
            )
        }

        SettingsGroup("AI data") {
            SwitchRow(
                "Confirm before sending content to AI",
                privacy.confirmAiTransmission,
                { v -> set { it.copy(confirmAiTransmission = v) } },
                subtitle = "Inkwell asks before any page, recording or document text leaves this device.",
            )
            RowDivider()
            ActionRow(
                title = "Delete all AI keys",
                subtitle = when (val n = storedKeys) {
                    null -> "Checking…"
                    0 -> "No keys are stored on this device."
                    1 -> "1 key is stored, encrypted, on this device."
                    else -> "$n keys are stored, encrypted, on this device."
                },
                actionLabel = "Delete",
                onAction = { confirm = PrivacyConfirm.DELETE_KEYS },
                enabled = (storedKeys ?: 0) > 0,
                destructive = true,
            )
            RowDivider()
            ActionRow(
                title = "Clear AI usage history",
                subtitle = "Token counts and cost estimates. Your notes and chats are not affected.",
                actionLabel = "Clear",
                onAction = { confirm = PrivacyConfirm.CLEAR_USAGE },
                destructive = true,
            )
        }

        SettingsGroup("Your privacy, in plain words") {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                PrivacyPoint(
                    Icons.Outlined.PhoneAndroid,
                    "Local first",
                    "Notes, PDFs, recordings and the search index are stored on this tablet and work offline. Nothing is uploaded unless you set up sync, backup or AI.",
                )
                PrivacyPoint(
                    Icons.Outlined.Block,
                    "No tracking",
                    "Inkwell has no analytics, no ads and no telemetry.",
                )
                PrivacyPoint(
                    Icons.Outlined.Key,
                    "Keys stay locked",
                    "AI keys and your notes vault password are encrypted with a key held in the Android Keystore. They are never written to settings, exports or backups. Google Drive access is held by Google Play services; Inkwell never sees your Google password.",
                )
                PrivacyPoint(
                    Icons.Outlined.Visibility,
                    "AI sees only what a request needs",
                    "A request carries just the selection, page or passages it is about, and goes straight from this device to the provider you chose with your key.",
                )
                PrivacyPoint(
                    Icons.Outlined.Tune,
                    "You're in control",
                    "Switch AI off completely in AI providers, and delete keys or usage history here at any time.",
                )
            }
        }
    }

    when (confirm) {
        PrivacyConfirm.DELETE_KEYS -> ConfirmDialog(
            title = "Delete all AI keys?",
            text = "Keys for OpenAI, Anthropic, DeepSeek and Moonshot are removed from this device. AI features stop until you add a key again. Your notes are not affected.",
            confirmLabel = "Delete keys",
            onConfirm = {
                confirm = null
                actions.background("Couldn't delete the keys. Please try again.") {
                    AiProviderId.entries.forEach { graph.secrets.remove(SecretStore.aiKey(it)) }
                    actions.update { st -> st.copy(ai = st.ai.copy(providers = st.ai.providers.map { it.copy(validatedAt = 0) })) }
                    withContext(Dispatchers.Main) { keysVersion++ }
                    actions.message("All AI keys deleted")
                }
            },
            onDismiss = { confirm = null },
        )
        PrivacyConfirm.CLEAR_USAGE -> ConfirmDialog(
            title = "Clear AI usage history?",
            text = "All recorded token counts and cost estimates are deleted. Monthly limits start again from zero.",
            confirmLabel = "Clear",
            onConfirm = {
                confirm = null
                actions.background("Couldn't clear the usage history. Please try again.") {
                    graph.db.aiUsage().clear()
                    actions.message("AI usage history cleared")
                }
            },
            onDismiss = { confirm = null },
        )
        null -> Unit
    }
}

@Composable
private fun PrivacyPoint(icon: ImageVector, title: String, body: String) {
    val c = Inkwell.colors
    Row(verticalAlignment = Alignment.Top) {
        Icon(icon, null, Modifier.padding(top = 2.dp).size(20.dp), tint = c.inkMuted)
        Spacer(Modifier.width(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium), color = c.ink)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = c.inkMuted)
        }
    }
}
