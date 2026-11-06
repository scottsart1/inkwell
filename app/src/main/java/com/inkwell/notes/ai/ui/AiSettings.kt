package com.inkwell.notes.ai.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.inkwell.notes.ai.AiServiceImpl
import com.inkwell.notes.ai.engine.CostFormat
import com.inkwell.notes.ai.engine.PriceOverride
import com.inkwell.notes.ai.engine.providerSettings
import com.inkwell.notes.ai.engine.updateProvider
import com.inkwell.notes.ai.provider.OpenAiModels
import com.inkwell.notes.app.AppGraph
import com.inkwell.notes.core.model.AiCapability
import com.inkwell.notes.core.model.AiModelInfo
import com.inkwell.notes.core.model.AiPrefs
import com.inkwell.notes.core.model.AiProviderId
import com.inkwell.notes.core.model.AiTask
import com.inkwell.notes.core.model.AppSettings
import com.inkwell.notes.core.model.ModelRef
import com.inkwell.notes.ui.theme.Dimens
import com.inkwell.notes.ui.theme.Inkwell
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** Per-provider UI state. Never holds the API key itself. */
internal data class ProviderUi(
    val hasKey: Boolean = false,
    val hint: String? = null,
    val invalid: String? = null,
    val validating: Boolean = false,
    val message: String? = null,
    val messageIsError: Boolean = false,
    val models: List<AiModelInfo> = emptyList(),
    val loadingModels: Boolean = false,
    val baseUrlError: String? = null,
)

internal class AiSettingsViewModel(private val graph: AppGraph) : ViewModel() {
    val ai: AiServiceImpl? = graph.ai as? AiServiceImpl
    val settings: StateFlow<AppSettings> = graph.settings.settings
    val providers = mutableStateMapOf<AiProviderId, ProviderUi>().apply { AiProviderId.entries.forEach { put(it, ProviderUi()) } }
    /** Bumped when price overrides change so price labels recompose. */
    var priceVersion by mutableStateOf(0); private set

    init {
        AiProviderId.entries.forEach { p -> viewModelScope.launch { refresh(p, fetch = false) } }
        viewModelScope.launch {
            settings.map { s -> s.ai.providers.map { it.customBaseUrl } }.distinctUntilChanged().collect {
                AiProviderId.entries.forEach { p -> refresh(p, fetch = false) }
            }
        }
    }

    private fun update(p: AiProviderId, f: (ProviderUi) -> ProviderUi) {
        providers[p] = f(providers[p] ?: ProviderUi())
    }

    private suspend fun refresh(p: AiProviderId, fetch: Boolean) {
        val ai = ai ?: return
        val hasKey = ai.hasKey(p)
        update(p) { it.copy(hasKey = hasKey, hint = null, invalid = ai.invalidReason(p), loadingModels = fetch && hasKey) }
        val hint = if (hasKey) ai.keyHint(p) else null
        val models = runCatching { ai.models(p, refresh = fetch && hasKey) }.getOrElse { e ->
            if (fetch) update(p) { it.copy(message = e.message, messageIsError = true) }
            ai.engine.listed(p).map { it.toInfo(p) }
        }
        update(p) { it.copy(hasKey = hasKey, hint = hint, invalid = ai.invalidReason(p), models = models, loadingModels = false) }
    }

    fun saveKey(p: AiProviderId, key: String) {
        val ai = ai ?: return
        // saveKey requires a non-empty key after cleaning: a paste of only invisible characters
        // (zero-width space, soft hyphen) passes isNotBlank() and would crash the save.
        val k = AiServiceImpl.sanitizeKey(key)
        if (k.isEmpty()) {
            update(p) { it.copy(message = "That is not an API key. Copy the key again and paste it here.", messageIsError = true) }
            return
        }
        viewModelScope.launch {
            ai.saveKey(p, k)
            update(p) { it.copy(message = null) }
            refresh(p, fetch = false)
            validate(p)
        }
    }

    fun validate(p: AiProviderId) {
        val ai = ai ?: return
        viewModelScope.launch {
            val key = ai.storedKey(p) ?: return@launch
            update(p) { it.copy(validating = true, message = null) }
            val r = ai.validateKey(p, key, null)
            update(p) {
                it.copy(
                    validating = false,
                    message = r.fold({ m -> m }, { e -> e.message ?: "Validation failed." }),
                    messageIsError = r.isFailure,
                )
            }
            refresh(p, fetch = false)
        }
    }

    fun removeKey(p: AiProviderId) {
        val ai = ai ?: return
        viewModelScope.launch {
            ai.removeKey(p)
            update(p) { it.copy(message = "Key removed.", messageIsError = false) }
            refresh(p, fetch = false)
        }
    }

    fun refreshModels(p: AiProviderId) {
        viewModelScope.launch { refresh(p, fetch = true) }
    }

    fun setBaseUrl(p: AiProviderId, url: String) {
        val ai = ai ?: return
        viewModelScope.launch {
            val r = ai.setCustomBaseUrl(p, url)
            update(p) { it.copy(baseUrlError = r.exceptionOrNull()?.message) }
            if (r.isSuccess) refresh(p, fetch = false)
        }
    }

    private fun updateAi(f: (AiPrefs) -> AiPrefs) {
        viewModelScope.launch { graph.settings.update { it.copy(ai = f(it.ai)) } }
    }

    fun setEnabled(on: Boolean) = updateAi { it.copy(enabled = on) }
    fun setProviderDefault(p: AiProviderId, modelId: String?) = updateAi { it.updateProvider(p) { s -> s.copy(defaultModel = modelId) } }
    fun setGlobalDefault(ref: ModelRef?) = updateAi { it.copy(defaultModel = ref) }
    fun setRoute(task: AiTask, ref: ModelRef?) = updateAi { if (ref == null) it.copy(routing = it.routing - task) else it.copy(routing = it.routing + (task to ref)) }
    fun setConfirmAbove(v: Int) = updateAi { it.copy(confirmAboveTokens = v.coerceAtLeast(0)) }
    fun setWarning(v: Double) = updateAi { it.copy(monthlyWarningUsd = v.coerceAtLeast(0.0)) }
    fun setHardLimit(v: Double) = updateAi { it.copy(monthlyHardLimitUsd = v.coerceAtLeast(0.0)) }
    fun setAllowImages(v: Boolean) = updateAi { it.copy(allowImages = v) }
    fun setRetrieval(v: Boolean) = updateAi { it.copy(retrievalForLargeDocs = v) }
    fun setSemantic(v: Boolean) = updateAi { it.copy(semanticSearch = v) }

    fun price(ref: ModelRef): PriceOverride? = ai?.priceOverride(ref)

    fun setPrice(ref: ModelRef, override: PriceOverride?) {
        val ai = ai ?: return
        ai.setPriceOverride(ref, override)
        priceVersion++
        viewModelScope.launch { refresh(ref.provider, fetch = false) }
    }

    /** Models with [capability] from providers that have a key. */
    fun capableModels(capability: AiCapability): List<AiModelInfo> =
        AiProviderId.entries.filter { providers[it]?.hasKey == true && settings.value.ai.providerSettings(it).enabled }
            .flatMap { p -> providers[p]?.models.orEmpty() }
            .filter { capability in it.capabilities }

    fun modelName(ref: ModelRef?): String = ref?.let { ai?.modelInfo(it)?.displayName ?: it.modelId } ?: "None"
}

@Composable
internal fun AiSettingsImpl(modifier: Modifier = Modifier, contentPadding: PaddingValues = PaddingValues(0.dp)) {
    val graph = AppGraph.get(LocalContext.current)
    val vm: AiSettingsViewModel = viewModel(key = "inkwell-ai-settings") { AiSettingsViewModel(graph) }
    val settings by vm.settings.collectAsState()
    val prefs = settings.ai
    val c = Inkwell.colors

    AdaptiveScrollColumn(modifier, contentPadding) {
        SectionCard(title = null) {
            SwitchRow(
                "Use AI features", prefs.enabled, vm::setEnabled,
                subtitle = if (prefs.enabled) "Requests go directly from this device to the providers you set up, with your own keys."
                else "Off: nothing ever leaves this device. Notes, OCR, search and recording keep working offline.",
            )
        }
        if (vm.ai == null) {
            Text("AI is not available in this build.", style = MaterialTheme.typography.bodyMedium, color = c.inkMuted)
            return@AdaptiveScrollColumn
        }
        for (p in AiProviderId.entries) ProviderCard(vm, p, prefs)
        DefaultModelCard(vm, prefs)
        RoutingCard(vm, prefs)
        LimitsCard(vm, prefs)
        PrivacyCard()
    }
}

// ---------------------------------------------------------------------------------------------

@Composable
private fun ProviderCard(vm: AiSettingsViewModel, p: AiProviderId, prefs: AiPrefs) {
    val c = Inkwell.colors
    val ui = vm.providers[p] ?: ProviderUi()
    val ps = prefs.providerSettings(p)
    val adapter = vm.ai!!.registry[p]
    val uri = LocalUriHandler.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var editing by remember(ui.hasKey) { mutableStateOf(!ui.hasKey) }
    var keyText by remember { mutableStateOf("") }
    var reveal by remember { mutableStateOf(false) }
    var endpointOpen by remember { mutableStateOf(!ps.customBaseUrl.isNullOrBlank()) }
    var endpointText by remember(ps.customBaseUrl) { mutableStateOf(ps.customBaseUrl.orEmpty()) }
    val dateFormat = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }

    val (statusText, statusColor) = when {
        !ui.hasKey -> "Not set" to c.inkMuted
        ui.invalid != null -> "Invalid key" to c.danger
        ps.validatedAt > 0 -> "Connected · validated ${dateFormat.format(Date(ps.validatedAt))}" to c.success
        ui.validating -> "Checking…" to c.inkMuted
        else -> "Key saved · not validated yet" to c.warning
    }

    SectionCard(
        title = p.displayName,
        subtitle = statusText,
        trailing = { StatusDot(statusColor, 10.dp); Spacer(Modifier.width(6.dp)) },
    ) {
        // ---- key
        if (ui.hasKey && !editing) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Outlined.Lock, null, Modifier.size(Dimens.icon), tint = c.inkMuted)
                Text(ui.hint ?: "••••", style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace), color = c.ink, modifier = Modifier.weight(1f))
                InkOutlinedButton("Replace", { editing = true; keyText = "" })
                InkTextButton("Remove key", { vm.removeKey(p) }, color = c.danger)
            }
        } else {
            OutlinedTextField(
                value = keyText,
                onValueChange = { keyText = it.trim() },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("API key") },
                placeholder = { Text(adapter.keyPrefixHint?.let { "$it…" } ?: "Paste your key") },
                singleLine = true,
                shape = Radius12,
                colors = inkFieldColors(),
                visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (keyText.isNotBlank()) { vm.saveKey(p, keyText); keyText = ""; editing = false } }),
                trailingIcon = {
                    Row {
                        PanelIconButton(if (reveal) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, if (reveal) "Hide key" else "Show key", { reveal = !reveal })
                        PanelIconButton(Icons.Outlined.ContentPaste, "Paste key", {
                            scope.launch {
                                val text = clipboard.getClipEntry()?.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
                                if (!text.isNullOrBlank()) keyText = text.trim()
                            }
                        })
                    }
                },
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                InkPrimaryButton("Save", { vm.saveKey(p, keyText); keyText = ""; editing = false; reveal = false }, enabled = keyText.isNotBlank())
                if (ui.hasKey) InkTextButton("Cancel", { editing = false; keyText = "" }, color = c.inkMuted)
                Spacer(Modifier.weight(1f))
                InkTextButton("Get a key", { runCatching { uri.openUri(adapter.keyConsoleUrl) } })
                Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, Modifier.size(16.dp), tint = c.accent)
            }
        }
        if (ui.hasKey) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                InkOutlinedButton(if (ui.validating) "Validating…" else "Validate", { vm.validate(p) }, enabled = !ui.validating)
                val msg = ui.message ?: ui.invalid
                if (msg != null) {
                    Text(msg, style = MaterialTheme.typography.bodySmall, color = if (ui.messageIsError || (ui.message == null && ui.invalid != null)) c.danger else c.success, modifier = Modifier.weight(1f))
                }
            }
        } else if (ui.message != null) {
            Text(ui.message, style = MaterialTheme.typography.bodySmall, color = c.inkMuted)
        }

        // ---- endpoint
        Row(
            Modifier.fillMaxWidth().heightIn(min = Dimens.minTouch),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Custom endpoint", style = MaterialTheme.typography.bodyMedium, color = c.ink, modifier = Modifier.weight(1f))
            Text(if (ps.customBaseUrl.isNullOrBlank()) "Official" else "Custom", style = MaterialTheme.typography.bodySmall, color = c.inkMuted)
            PanelIconButton(if (endpointOpen) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, if (endpointOpen) "Hide endpoint" else "Edit endpoint", { endpointOpen = !endpointOpen })
        }
        if (endpointOpen) {
            OutlinedTextField(
                value = endpointText,
                onValueChange = { endpointText = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Base URL (https only)") },
                placeholder = { Text(adapter.customBaseUrlExample) },
                supportingText = { Text(ui.baseUrlError ?: "Leave empty for ${adapter.defaultBaseUrl}") },
                isError = ui.baseUrlError != null,
                singleLine = true,
                shape = Radius12,
                colors = inkFieldColors(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { vm.setBaseUrl(p, endpointText) }),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                InkOutlinedButton("Save endpoint", { vm.setBaseUrl(p, endpointText) }, enabled = endpointText.trim() != ps.customBaseUrl.orEmpty())
                if (!ps.customBaseUrl.isNullOrBlank()) InkTextButton("Use official", { endpointText = ""; vm.setBaseUrl(p, "") }, color = c.inkMuted)
            }
        }

        // ---- default model + capabilities
        val listed = ui.models
        val auto = vm.ai.engine.providerDefault(p, AiCapability.TEXT)
        val selectedId = ps.defaultModel
        val options: List<AiModelInfo?> = listOf<AiModelInfo?>(null) + listed.filter { AiCapability.TEXT in it.capabilities }
        Row(verticalAlignment = Alignment.Bottom) {
            InkDropdown(
                label = "Default model",
                selectedText = selectedId?.let { id -> listed.firstOrNull { it.ref.modelId == id }?.displayName ?: id }
                    ?: "Automatic" + (auto?.let { " (${vm.modelName(ModelRef(p, it))})" } ?: ""),
                options = options,
                optionText = { it?.displayName ?: "Automatic (recommended)" },
                optionDetail = { it?.let { m -> capabilityText(m) + " · " + priceLabel(m.inputUsdPerMTok, m.outputUsdPerMTok) } },
                isSelected = { (it?.ref?.modelId) == selectedId },
                onSelect = { vm.setProviderDefault(p, it?.ref?.modelId) },
                modifier = Modifier.weight(1f),
            )
            PanelIconButton(Icons.Outlined.Refresh, if (ui.loadingModels) "Refreshing models" else "Refresh model list", { vm.refreshModels(p) }, enabled = ui.hasKey && !ui.loadingModels)
        }
        val shown = (selectedId ?: auto)?.let { id -> listed.firstOrNull { it.ref.modelId == id } ?: vm.ai.modelInfo(ModelRef(p, id)) }
        if (shown != null) ModelFacts(vm, shown)
    }
}

private fun capabilityText(m: AiModelInfo): String = capabilityLabels(m.capabilities).joinToString(" · ").ifEmpty { "No supported capability" }

private fun capabilityLabels(caps: Set<AiCapability>): List<String> = buildList {
    if (AiCapability.TEXT in caps) add("Text")
    if (AiCapability.VISION in caps) add("Vision")
    if (AiCapability.AUDIO_TRANSCRIPTION in caps) add("Audio")
    if (AiCapability.EMBEDDINGS in caps) add("Embeddings")
    if (AiCapability.REASONING in caps) add("Reasoning")
}

@Composable
private fun ModelFacts(vm: AiSettingsViewModel, m: AiModelInfo) {
    val c = Inkwell.colors
    var editPrice by remember { mutableStateOf(false) }
    val version = vm.priceVersion
    val override = remember(m.ref, version) { vm.price(m.ref) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (label in capabilityLabels(m.capabilities)) InkChip(label, onClick = null, selected = label != "Reasoning")
            if (m.contextTokens > 0) InkChip("${compactTokens(m.contextTokens)} context", onClick = null)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                (if (m.inputUsdPerMTok == null && m.outputUsdPerMTok == null) "Price unknown" else
                    "Estimated ${CostFormat.pricePerM(m.inputUsdPerMTok)} input / ${CostFormat.pricePerM(m.outputUsdPerMTok)} output per million tokens") +
                    if (override != null) " (your price)" else "",
                style = MaterialTheme.typography.bodySmall, color = c.inkMuted, modifier = Modifier.weight(1f),
            )
            InkTextButton(if (override != null) "Edit price" else "Set price", { editPrice = true })
        }
    }
    if (editPrice) PriceDialog(m, override, onSave = { vm.setPrice(m.ref, it); editPrice = false }, onDismiss = { editPrice = false })
}

private fun compactTokens(n: Int): String = when {
    n >= 1_000_000 -> "${CostFormat.trimZeros(n / 1_000_000.0)}M"
    n >= 1_000 -> "${n / 1_000}K"
    else -> "$n"
}

@Composable
private fun PriceDialog(m: AiModelInfo, current: PriceOverride?, onSave: (PriceOverride?) -> Unit, onDismiss: () -> Unit) {
    val c = Inkwell.colors
    var input by remember { mutableStateOf((current?.inputUsdPerMTok ?: m.inputUsdPerMTok)?.let { CostFormat.trimZeros(it) }.orEmpty()) }
    var output by remember { mutableStateOf((current?.outputUsdPerMTok ?: m.outputUsdPerMTok)?.let { CostFormat.trimZeros(it) }.orEmpty()) }
    val inV = input.replace(',', '.').toDoubleOrNull()
    val outV = output.replace(',', '.').toDoubleOrNull()
    val valid = (input.isBlank() || (inV != null && inV >= 0)) && (output.isBlank() || (outV != null && outV >= 0))
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        shape = Radius16,
        title = { Text("Price for ${m.displayName}", style = MaterialTheme.typography.titleMedium, color = c.ink) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("USD per million tokens, used only for local estimates.", style = MaterialTheme.typography.bodySmall, color = c.inkMuted)
                OutlinedTextField(input, { input = it }, label = { Text("Input") }, singleLine = true, shape = Radius12, colors = inkFieldColors(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                OutlinedTextField(output, { output = it }, label = { Text("Output") }, singleLine = true, shape = Radius12, colors = inkFieldColors(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            }
        },
        confirmButton = { InkTextButton("Save", { onSave(PriceOverride(inV, outV)) }, enabled = valid) },
        dismissButton = {
            Row {
                if (current != null) InkTextButton("Use built-in", { onSave(null) }, color = c.inkMuted)
                InkTextButton("Cancel", onDismiss, color = c.inkMuted)
            }
        },
    )
}

@Composable
private fun DefaultModelCard(vm: AiSettingsViewModel, prefs: AiPrefs) {
    val options: List<AiModelInfo?> = listOf<AiModelInfo?>(null) + vm.capableModels(AiCapability.TEXT)
    SectionCard("Default model", subtitle = "Used for every task without its own route.") {
        InkDropdown(
            label = "Model",
            selectedText = prefs.defaultModel?.let { vm.modelName(it) + " · " + it.provider.displayName } ?: "Automatic (first connected provider)",
            options = options,
            optionText = { it?.displayName ?: "Automatic (first connected provider)" },
            optionDetail = { it?.let { m -> capabilityText(m) + " · " + priceLabel(m.inputUsdPerMTok, m.outputUsdPerMTok) } },
            header = { it?.ref?.provider?.displayName },
            isSelected = { it?.ref == prefs.defaultModel },
            onSelect = { vm.setGlobalDefault(it?.ref) },
            enabled = options.size > 1,
        )
        if (options.size <= 1) Text("Add a key above to choose a model.", style = MaterialTheme.typography.bodySmall, color = Inkwell.colors.inkMuted)
    }
}

@Composable
private fun RoutingCard(vm: AiSettingsViewModel, prefs: AiPrefs) {
    val c = Inkwell.colors
    SectionCard("Model routing", subtitle = "Send each kind of task to a different model. Only models with the needed capability are listed.") {
        for (task in AiTask.entries) {
            val route = prefs.routing[task]
            // The diarize model is chosen through speaker settings, not as the plain transcription route.
            val options: List<AiModelInfo?> = listOf<AiModelInfo?>(null) + vm.capableModels(task.required)
                .filterNot { task == AiTask.TRANSCRIPTION && it.ref != route && OpenAiModels.isDiarize(it.ref.modelId) }
            val resolved = vm.ai?.modelFor(task)
            val requirement = when (task) {
                AiTask.TRANSCRIPTION, AiTask.EMBEDDINGS -> " · needs an OpenAI key"
                AiTask.OCR_ASSIST -> " · needs a vision model"
                else -> ""
            }
            InkDropdown(
                label = task.label + requirement,
                selectedText = route?.let { vm.modelName(it) + " · " + it.provider.displayName }
                    ?: ("Use default" + (resolved?.let { " (${vm.modelName(it)})" } ?: " (none available)")),
                options = options,
                optionText = { it?.displayName ?: "Use default" },
                optionDetail = { it?.let { m -> priceLabel(m.inputUsdPerMTok, m.outputUsdPerMTok) } },
                header = { it?.ref?.provider?.displayName },
                isSelected = { it?.ref == route },
                onSelect = { vm.setRoute(task, it?.ref) },
                enabled = options.size > 1,
            )
        }
        if (prefs.routing.isNotEmpty()) {
            Text("Routes to a provider without a key fall back to the default model.", style = MaterialTheme.typography.bodySmall, color = c.inkMuted)
        }
    }
}

@Composable
private fun LimitsCard(vm: AiSettingsViewModel, prefs: AiPrefs) {
    SectionCard("Limits", subtitle = "Costs are estimated from public list prices.") {
        NumberSetting(
            "Ask before sending more than", "tokens", prefs.confirmAboveTokens.toString(),
            parse = { it.toIntOrNull()?.takeIf { v -> v >= 0 } }, onValue = { vm.setConfirmAbove(it) }, hint = "0 = never ask", decimal = false,
        )
        NumberSetting(
            "Monthly warning at", "USD", CostFormat.trimZeros(prefs.monthlyWarningUsd),
            parse = { it.replace(',', '.').toDoubleOrNull()?.takeIf { v -> v >= 0 } }, onValue = { vm.setWarning(it) }, hint = "0 = off",
        )
        NumberSetting(
            "Monthly hard limit", "USD", CostFormat.trimZeros(prefs.monthlyHardLimitUsd),
            parse = { it.replace(',', '.').toDoubleOrNull()?.takeIf { v -> v >= 0 } }, onValue = { vm.setHardLimit(it) }, hint = "Requests stop once the month's estimate reaches this. 0 = off",
        )
        SwitchRow("Allow images", prefs.allowImages, vm::setAllowImages, subtitle = "Send handwriting and page images to vision models (OCR assist, selections).")
        SwitchRow("Retrieval for large documents", prefs.retrievalForLargeDocs, vm::setRetrieval, subtitle = "For long documents, send only the passages relevant to your question.")
        SwitchRow("Semantic search", prefs.semanticSearch, vm::setSemantic, subtitle = "Search by meaning using OpenAI embeddings. Sends indexed note text to OpenAI.")
    }
}

@Composable
private fun <T : Any> NumberSetting(
    title: String, unit: String, value: String, parse: (String) -> T?, onValue: (T) -> Unit, hint: String, decimal: Boolean = true,
) {
    val c = Inkwell.colors
    // Local text so partial input such as "10." is not normalised away while typing.
    var text by remember { mutableStateOf(value) }
    val parsed = parse(text.trim())
    LaunchedEffect(text) { if (parsed != null && text.trim() != value) onValue(parsed) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = c.ink)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = c.inkMuted)
        }
        OutlinedTextField(
            value = text, onValueChange = { text = it },
            modifier = Modifier.widthIn(min = 96.dp, max = 140.dp),
            singleLine = true, shape = Radius12, colors = inkFieldColors(), isError = parsed == null,
            suffix = { Text(unit, style = MaterialTheme.typography.bodySmall, color = c.inkMuted) },
            keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number, imeAction = ImeAction.Done),
            textStyle = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun PrivacyCard() {
    val c = Inkwell.colors
    SectionCard("Privacy", subtitle = null) {
        val lines = listOf(
            "Nothing is sent unless you use an AI feature. With \"Use AI features\" off, no request ever leaves this device.",
            "When you ask the assistant, only the context you choose (a selection, the page, the document or added documents) and your question are sent, directly to the provider of the model you picked. Large documents send only relevant passages when retrieval is on.",
            "OCR assist sends page images to your vision model only when you turned it on in Recognition settings. Transcription uploads audio to OpenAI only when you ask for it. Semantic search sends indexed text to OpenAI only when enabled above.",
            "Automatic naming may send a short excerpt to your naming model when AI naming is on.",
            "API keys are encrypted with the Android Keystore, never shown in full, never logged and never included in exports or backups. Providers' own data policies apply to what you send.",
        )
        for (l in lines) Text(l, style = MaterialTheme.typography.bodySmall, color = c.inkMuted)
    }
}
