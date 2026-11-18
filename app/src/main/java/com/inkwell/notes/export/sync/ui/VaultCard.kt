package com.inkwell.notes.export.sync.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.inkwell.notes.R
import com.inkwell.notes.core.model.DestinationPrefs
import com.inkwell.notes.core.model.OutputTarget
import com.inkwell.notes.export.sync.VaultUrl
import com.inkwell.notes.export.sync.uses
import com.inkwell.notes.ui.theme.Inkwell

/**
 * The notes vault on yourpitbox.com: its link, a connection test and a way to open it in the
 * browser. The vault has no password.
 */
@Composable
internal fun VaultCard(
    prefs: DestinationPrefs,
    check: CheckState,
    onUrlChanged: (String) -> Unit,
    onUrlCommit: (String) -> Unit,
    onTest: (url: String) -> Unit,
    onBrowserError: () -> Unit,
) {
    val c = Inkwell.colors
    val uriHandler = LocalUriHandler.current
    var url by rememberSaveable { mutableStateOf(prefs.vaultUrl) }
    // Inkwell itself can update the saved link (the vault moved between www.host and host):
    // show the new one, unless the field holds something else the user typed.
    var lastSaved by rememberSaveable { mutableStateOf(prefs.vaultUrl) }
    LaunchedEffect(prefs.vaultUrl) {
        if (prefs.vaultUrl != lastSaved && VaultUrl.normalize(url) == VaultUrl.normalize(lastSaved)) url = prefs.vaultUrl
        lastSaved = prefs.vaultUrl
    }
    var urlFocused by remember { mutableStateOf(false) }
    val parsed = remember(url) { VaultUrl.parse(url) }
    // Explain a malformed link once the user leaves the field, not on every keystroke.
    val urlError = (parsed as? VaultUrl.Parsed.Invalid)?.reason?.takeIf { !urlFocused }
    val normalized = (parsed as? VaultUrl.Parsed.Valid)?.endpoint?.baseUrl

    StorageCard(title = stringResource(R.string.sync_vault_title), subtitle = stringResource(R.string.sync_vault_subtitle), icon = Icons.Outlined.Public) {
        LabeledField(stringResource(R.string.sync_vault_url)) {
            OutlinedTextField(
                value = url,
                onValueChange = {
                    url = it
                    onUrlChanged(it)
                },
                modifier = Modifier.fillMaxWidth().onFocusChanged {
                    if (urlFocused && !it.isFocused) onUrlCommit(url)
                    urlFocused = it.isFocused
                },
                placeholder = { Text(VaultUrl.PLACEHOLDER, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                singleLine = true,
                isError = urlError != null,
                supportingText = urlError?.let { { Text(it) } },
                shape = R12,
                colors = inkFieldColors(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false, imeAction = ImeAction.Done),
            )
        }
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            SecondaryAction(
                stringResource(if (check == CheckState.Running) R.string.sync_testing else R.string.sync_vault_test),
                { onTest(url) },
                enabled = normalized != null,
                busy = check == CheckState.Running,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                QuietAction(stringResource(R.string.sync_vault_open), {
                    val target = normalized ?: return@QuietAction
                    try {
                        uriHandler.openUri(target)
                    } catch (e: Exception) {
                        onBrowserError()
                    }
                }, enabled = normalized != null)
                Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, Modifier.size(16.dp), tint = if (normalized != null) c.accent else c.inkMuted)
            }
        }
        CheckResult(check)
        if (prefs.uses(OutputTarget.VAULT) && normalized == null) {
            InfoLine(stringResource(R.string.sync_vault_needs_setup))
        }
    }
}

/** Small label above a field, so the placeholder stays visible while the field is empty. */
@Composable
private fun LabeledField(label: String, field: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = Inkwell.colors.inkMuted)
        field()
    }
}
