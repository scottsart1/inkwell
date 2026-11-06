package com.inkwell.notes.ai.ui

import android.content.ClipData
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.NoteAdd
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AddComment
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Gesture
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import com.inkwell.notes.ai.chat.ChatItem
import com.inkwell.notes.ai.context.ContextScope
import com.inkwell.notes.ai.context.Prompts
import com.inkwell.notes.ai.engine.CostFormat
import com.inkwell.notes.app.AppGraph
import com.inkwell.notes.core.model.AiCapability
import com.inkwell.notes.core.model.AiRole
import com.inkwell.notes.core.model.DocKind
import com.inkwell.notes.core.model.LibraryFilter
import com.inkwell.notes.core.model.LibrarySort
import com.inkwell.notes.ui.theme.Dimens
import com.inkwell.notes.ui.theme.Inkwell
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@Composable
internal fun AiPanelImpl(
    context: AiPanelContext,
    onInsert: (String) -> Unit,
    onOpenSource: (Int) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val graph = AppGraph.get(LocalContext.current)
    val vm: AiPanelViewModel = viewModel(key = "inkwell-ai-panel:${context.docId}") { AiPanelViewModel(graph, context.docId) }
    LaunchedEffect(context) { vm.updateContext(context) }
    val settings by vm.settings.collectAsState()
    var showSettings by remember { mutableStateOf(false) }
    var showPicker by remember { mutableStateOf(false) }
    val c = Inkwell.colors

    // Phones get the full width; tablets a 420dp side panel (or the slot the editor gives it).
    val phone = LocalConfiguration.current.screenWidthDp < 600
    BoxWithConstraints(modifier) {
        val width = if (phone) maxWidth else minOf(Dimens.panelWidth, maxWidth)
        Surface(
            Modifier.align(Alignment.TopEnd).width(width).fillMaxHeight(),
            color = c.surface,
        ) {
            Row(Modifier.fillMaxSize()) {
                if (!phone) VerticalDivider(color = c.outline, thickness = 1.dp)
                Column(Modifier.fillMaxSize().imePadding()) {
                    PanelHeader(vm, onClose, onOpenSettings = { showSettings = true })
                    HorizontalDivider(color = c.outline, thickness = 1.dp)
                    when {
                        vm.ai == null -> EmptyState(
                            title = "Assistant unavailable",
                            body = "The AI service is not available in this build.",
                            primary = null, onPrimary = {},
                        )
                        !settings.ai.enabled -> EmptyState(
                            title = "AI features are off",
                            body = "\"Use AI features\" is turned off, so nothing from your notes leaves this device. Turn it on to use the assistant with your own API key.",
                            primary = "Open AI settings", onPrimary = { showSettings = true },
                        )
                        !vm.modelsLoaded -> Box(Modifier.fillMaxSize())
                        !vm.configured -> SetupState(onOpenSettings = { showSettings = true })
                        else -> Conversation(vm, onInsert, onOpenSource, onOpenSettings = { showSettings = true }, onAddDocument = { showPicker = true })
                    }
                }
            }
        }
    }

    if (showSettings) {
        SettingsDialog(onDismiss = {
            showSettings = false
            vm.onSettingsClosed()
        })
    }
    if (showPicker) {
        DocumentPicker(graph, exclude = setOf(vm.docId) + vm.extraDocs.map { it.id }, onPick = { vm.addDocument(it); showPicker = false }, onDismiss = { showPicker = false })
    }
    vm.pendingConfirm?.let { p ->
        AlertDialog(
            onDismissRequest = vm::cancelLarge,
            containerColor = c.surface,
            shape = Radius16,
            title = { Text("Send a large request?", style = MaterialTheme.typography.titleMedium, color = c.ink) },
            text = {
                Text(
                    "This request will send about ${CostFormat.tokens(p.estimatedTokens)} tokens (est. ${CostFormat.usd(p.estimatedCostUsd)}). Continue?",
                    style = MaterialTheme.typography.bodyMedium, color = c.inkMuted,
                )
            },
            confirmButton = { InkTextButton("Continue", vm::confirmLarge) },
            dismissButton = { InkTextButton("Cancel", vm::cancelLarge, color = c.inkMuted) },
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Header

@Composable
private fun PanelHeader(vm: AiPanelViewModel, onClose: () -> Unit, onOpenSettings: () -> Unit) {
    val c = Inkwell.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Assistant", style = MaterialTheme.typography.titleMedium, color = c.ink)
        Spacer(Modifier.width(12.dp))
        Box(Modifier.weight(1f)) {
            if (vm.ai != null && vm.aiEnabled && vm.configured) ModelPill(vm, onOpenSettings)
        }
        PanelIconButton(Icons.Outlined.AddComment, "New conversation", vm::newThread, enabled = vm.ai != null)
        PanelIconButton(Icons.Outlined.Close, "Close assistant", onClose)
    }
}

@Composable
private fun ModelPill(vm: AiPanelViewModel, onOpenSettings: () -> Unit) {
    val c = Inkwell.colors
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.heightIn(min = Dimens.minTouch).padding(vertical = 6.dp).clip(Radius16).border(1.dp, c.outline, Radius16)
                .clickable(role = Role.DropdownList, onClickLabel = "Switch model") { open = true }
                .padding(start = 12.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                vm.modelName(vm.selectedModel), style = MaterialTheme.typography.labelMedium, color = c.ink,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 180.dp),
            )
            Icon(Icons.Outlined.ExpandMore, null, Modifier.size(18.dp), tint = c.inkMuted)
        }
        DropdownMenu(open, onDismissRequest = { open = false }, containerColor = c.surface, shape = Radius12, border = BorderStroke(1.dp, c.outline)) {
            val groups = vm.modelOptions.groupBy { it.ref.provider }
            if (groups.isEmpty()) {
                Text(
                    if (vm.modelsLoaded) "No chat models available" else "Loading models…",
                    style = MaterialTheme.typography.bodySmall, color = c.inkMuted, modifier = Modifier.padding(16.dp),
                )
            }
            for ((provider, options) in groups) {
                Text(provider.displayName, style = MaterialTheme.typography.labelSmall, color = c.inkMuted, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 4.dp))
                for (o in options) {
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(o.info.displayName, style = MaterialTheme.typography.bodyMedium, color = c.ink)
                                val detail = listOfNotNull(
                                    if (o.vision) "Vision" else "Text",
                                    if (AiCapability.REASONING in o.info.capabilities) "Reasoning" else null,
                                    priceLabel(o.info.inputUsdPerMTok, o.info.outputUsdPerMTok),
                                ).joinToString(" · ")
                                Text(detail, style = MaterialTheme.typography.bodySmall, color = c.inkMuted)
                            }
                        },
                        trailingIcon = if (o.ref == vm.selectedModel) ({ Icon(Icons.Outlined.Check, "Selected", Modifier.size(Dimens.icon), tint = c.accent) }) else null,
                        onClick = { open = false; vm.selectModel(o.ref) },
                        modifier = Modifier.heightIn(min = Dimens.minTouch),
                    )
                }
            }
            HorizontalDivider(color = c.outline, modifier = Modifier.padding(vertical = 4.dp))
            DropdownMenuItem(
                text = { Text("AI settings", style = MaterialTheme.typography.bodyMedium, color = c.ink) },
                leadingIcon = { Icon(Icons.Outlined.Settings, null, Modifier.size(Dimens.icon), tint = c.inkMuted) },
                onClick = { open = false; onOpenSettings() },
                modifier = Modifier.heightIn(min = Dimens.minTouch),
            )
        }
    }
}

internal fun priceLabel(input: Double?, output: Double?): String =
    if (input == null && output == null) "price unknown"
    else "est. ${CostFormat.pricePerM(input)} / ${CostFormat.pricePerM(output)} per M"

// ---------------------------------------------------------------------------------------------
// Empty / setup states

@Composable
private fun EmptyState(title: String, body: String, primary: String?, onPrimary: () -> Unit) {
    val c = Inkwell.colors
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = c.ink)
        Spacer(Modifier.size(8.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = c.inkMuted)
        if (primary != null) {
            Spacer(Modifier.size(20.dp))
            InkPrimaryButton(primary, onPrimary)
        }
    }
}

@Composable
private fun SetupState(onOpenSettings: () -> Unit) {
    val c = Inkwell.colors
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.size(24.dp))
        Box(Modifier.size(44.dp).clip(Radius12).background(c.accentContainer), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Key, null, Modifier.size(Dimens.icon), tint = c.accent)
        }
        Text("Set up the assistant", style = MaterialTheme.typography.titleLarge, color = c.ink)
        Text(
            "Inkwell works fully offline. The assistant is optional and uses your own API key, so requests go straight from this tablet to the provider you choose.",
            style = MaterialTheme.typography.bodyMedium, color = c.inkMuted,
        )
        Step(1, "Create an API key with OpenAI, Anthropic, DeepSeek or Moonshot (Kimi).")
        Step(2, "Open AI settings, paste the key and tap Validate. Keys are encrypted on this device.")
        Step(3, "Choose a default model. You can route tasks such as OCR or transcription to other models.")
        InkPrimaryButton("Open AI settings", onOpenSettings, icon = Icons.Outlined.Settings)
    }
}

@Composable
private fun Step(n: Int, text: String) {
    val c = Inkwell.colors
    Row(verticalAlignment = Alignment.Top) {
        Box(Modifier.size(24.dp).clip(Radius8).border(1.dp, c.outline, Radius8), contentAlignment = Alignment.Center) {
            Text("$n", style = MaterialTheme.typography.labelMedium, color = c.ink)
        }
        Text(text, style = MaterialTheme.typography.bodyMedium, color = c.ink, modifier = Modifier.padding(start = 12.dp, top = 2.dp))
    }
}

// ---------------------------------------------------------------------------------------------
// Conversation

@Composable
private fun Conversation(
    vm: AiPanelViewModel,
    onInsert: (String) -> Unit,
    onOpenSource: (Int) -> Unit,
    onOpenSettings: () -> Unit,
    onAddDocument: () -> Unit,
) {
    val c = Inkwell.colors
    val messages by vm.messages.collectAsState()
    val settings by vm.settings.collectAsState()
    val listState = rememberLazyListState()
    val streaming = vm.streaming
    val itemCount = messages.size + (if (streaming != null) 1 else 0) + (if (vm.error != null) 1 else 0)

    LaunchedEffect(itemCount, streaming?.text?.length) {
        if (itemCount > 0) {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            if (streaming == null || lastVisible >= itemCount - 2) listState.scrollToItem(itemCount - 1)
        }
    }

    Column(Modifier.fillMaxSize()) {
        ContextChips(vm, onAddDocument)
        SpendBanner(vm.monthSpendUsd, settings.ai.monthlyWarningUsd, settings.ai.monthlyHardLimitUsd, onOpenSettings)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (itemCount == 0) {
                Suggestions(vm)
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    // Retry only on an answer that ends the thread (regenerate appends the new answer at the end).
                    val lastAssistantId = messages.lastOrNull()?.takeIf { it.role == AiRole.ASSISTANT }?.id
                    items(messages, key = { it.id }) { m ->
                        when (m.role) {
                            AiRole.USER -> UserBubble(m.text)
                            else -> AssistantMessage(
                                vm, m, isLast = m.id == lastAssistantId && streaming == null,
                                onInsert = onInsert, onOpenSource = onOpenSource,
                            )
                        }
                    }
                    if (streaming != null) item(key = "streaming") { StreamingMessage(vm, streaming) }
                    vm.error?.let { err -> item(key = "error") { ErrorCard(err, onRetry = if (err.retry != null) vm::retryError else null, onDismiss = { vm.error = null }) } }
                }
            }
        }
        if (vm.hasSelection) QuickActions(vm)
        vm.pendingNotice?.let { TransmissionNotice(it.model.provider.displayName, vm) }
        InputBar(vm)
        UsageFooter(vm)
    }
}

@Composable
private fun ContextChips(vm: AiPanelViewModel, onAddDocument: () -> Unit) {
    val ctx = vm.panelContext
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (vm.hasSelection) {
            InkChip(vm.selectionLabel(), onClick = { vm.toggleScope(ContextScope.SELECTION) }, selected = vm.scope == ContextScope.SELECTION, leadingIcon = Icons.Outlined.Gesture)
        }
        InkChip(
            "This page", onClick = { vm.toggleScope(ContextScope.PAGE) }, selected = vm.scope == ContextScope.PAGE,
            enabled = ctx.pageId != null || ctx.pageIndex != null,
        )
        InkChip("Whole document", onClick = { vm.toggleScope(ContextScope.DOCUMENT) }, selected = vm.scope == ContextScope.DOCUMENT, leadingIcon = Icons.Outlined.Description)
        for (d in vm.extraDocs) {
            InkChip(d.title, onClick = null, selected = true, leadingIcon = Icons.Outlined.Description, onRemove = { vm.removeDocument(d.id) }, modifier = Modifier.widthIn(max = 220.dp))
        }
        InkChip("Add document", onClick = onAddDocument, leadingIcon = Icons.Outlined.Add)
    }
}

@Composable
private fun SpendBanner(spent: Double, warning: Double, hardLimit: Double, onOpenSettings: () -> Unit) {
    val c = Inkwell.colors
    when {
        hardLimit > 0 && spent >= hardLimit -> Banner(
            "Monthly limit reached: about ${CostFormat.usd(spent)} of ${CostFormat.usd(hardLimit)} (estimate). Requests are paused.",
            Icons.Outlined.ErrorOutline, c.danger, Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        ) { InkTextButton("Limits", onOpenSettings) }
        warning > 0 && spent >= warning -> Banner(
            "About ${CostFormat.usd(spent)} spent on AI this month (estimate), above your ${CostFormat.usd(warning)} warning.",
            Icons.Outlined.WarningAmber, c.warning, Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun Suggestions(vm: AiPanelViewModel) {
    val c = Inkwell.colors
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Ask about your notes", style = MaterialTheme.typography.titleMedium, color = c.ink)
        Text(
            "Answers cite pages like [p.3]; tap a citation to jump there. Nothing is added to your page unless you choose Insert.",
            style = MaterialTheme.typography.bodySmall, color = c.inkMuted,
        )
        val prompts = listOf("Summarize the key points", "What questions does this leave open?", "Turn this into a study outline")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (p in prompts) InkChip(p, onClick = { vm.send(p) }, enabled = !vm.busy)
        }
    }
}

@Composable
private fun UserBubble(text: String) {
    val c = Inkwell.colors
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        SelectionContainer {
            Text(
                text, style = MaterialTheme.typography.bodyMedium, color = c.ink,
                modifier = Modifier.widthIn(max = 320.dp).clip(Radius12).background(c.accentContainer).padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun AssistantMessage(
    vm: AiPanelViewModel,
    m: ChatItem,
    isLast: Boolean,
    onInsert: (String) -> Unit,
    onOpenSource: (Int) -> Unit,
) {
    val c = Inkwell.colors
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val chips = remember(m.id, m.text, vm.pageCount) { vm.sourceChips(m) }
    var copied by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        vm.messageReasoning[m.id]?.let { ThinkingSection(it, live = false) }
        SelectionContainer {
            MarkdownView(m.text, onCitation = { label -> chips.firstOrNull { it.label == label }?.pageIndex?.let(onOpenSource) })
        }
        vm.messageNotes[m.id]?.forEach { NoteLine(it) }
        if (chips.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (s in chips) {
                    InkChip(
                        s.label,
                        onClick = s.pageIndex?.let { idx -> { onOpenSource(idx) } },
                        leadingIcon = Icons.Outlined.Description,
                        modifier = Modifier.semantics { contentDescription = sourceDescription(s) },
                    )
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            InkTextButton("Insert into page", onClick = { onInsert(Markdown.toPlainText(m.text)) })
            PanelIconButton(Icons.Outlined.ContentCopy, if (copied) "Copied" else "Copy", onClick = {
                scope.launch {
                    clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Assistant answer", m.text)))
                    copied = true
                }
            })
            if (isLast) PanelIconButton(Icons.Outlined.Refresh, "Retry", onClick = { vm.regenerate(m.id) }, enabled = !vm.busy)
            Spacer(Modifier.weight(1f))
            m.model?.let { Text(vm.modelName(it), style = MaterialTheme.typography.labelSmall, color = c.inkMuted, maxLines = 1) }
        }
    }
}

private fun sourceDescription(s: SourceChip): String = when {
    s.pageIndex != null -> "Open page ${s.pageIndex + 1}"
    s.source?.documentTitle != null -> "Source ${s.label} in ${s.source.documentTitle}"
    else -> "Source ${s.label}"
}

@Composable
private fun StreamingMessage(vm: AiPanelViewModel, reply: StreamingReply) {
    val c = Inkwell.colors
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (reply.reasoning.isNotBlank()) ThinkingSection(reply.reasoning, live = reply.text.isEmpty())
        if (reply.text.isNotEmpty()) MarkdownView(reply.text)
        Text(
            reply.status ?: if (reply.text.isEmpty()) "${vm.modelName(reply.model)} is thinking…" else "Writing…",
            style = MaterialTheme.typography.labelSmall, color = c.inkMuted,
        )
    }
}

@Composable
private fun ThinkingSection(text: String, live: Boolean) {
    val c = Inkwell.colors
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clip(Radius8).border(1.dp, c.outline, Radius8)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = Dimens.minTouch).clickable(role = Role.Button, onClickLabel = if (expanded) "Hide thinking" else "Show thinking") { expanded = !expanded }
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(if (live) "Thinking…" else "Thinking", style = MaterialTheme.typography.labelMedium, color = c.inkMuted, modifier = Modifier.weight(1f))
            Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, Modifier.size(Dimens.icon), tint = c.inkMuted)
        }
        AnimatedVisibility(expanded) {
            Text(
                text.trim(), style = MaterialTheme.typography.bodySmall, color = c.inkMuted,
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
            )
        }
    }
}

@Composable
private fun NoteLine(text: String) {
    val c = Inkwell.colors
    Row(verticalAlignment = Alignment.Top) {
        Icon(Icons.Outlined.Info, null, Modifier.padding(top = 1.dp).size(14.dp), tint = c.inkMuted)
        Text(text, style = MaterialTheme.typography.bodySmall, color = c.inkMuted, modifier = Modifier.padding(start = 6.dp))
    }
}

@Composable
private fun ErrorCard(err: PanelError, onRetry: (() -> Unit)?, onDismiss: () -> Unit) {
    val c = Inkwell.colors
    Column(
        Modifier.fillMaxWidth().clip(Radius12).border(1.dp, c.outline, Radius12).background(c.surfaceAlt).padding(start = 12.dp, top = 12.dp, end = 4.dp, bottom = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(Icons.Outlined.ErrorOutline, null, Modifier.size(Dimens.icon), tint = c.danger)
            Text(err.message, style = MaterialTheme.typography.bodyMedium, color = c.ink, modifier = Modifier.weight(1f).padding(start = 10.dp, end = 8.dp))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            InkTextButton("Dismiss", onDismiss, color = c.inkMuted)
            if (onRetry != null) InkTextButton("Retry", onRetry)
        }
    }
}

@Composable
private fun QuickActions(vm: AiPanelViewModel) {
    val c = Inkwell.colors
    var languageMenu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (a in Prompts.QuickAction.entries) {
            if (a == Prompts.QuickAction.TRANSLATE) {
                Box {
                    InkChip("Translate · ${vm.language}", onClick = { languageMenu = true }, leadingIcon = Icons.Outlined.Translate, enabled = !vm.busy)
                    DropdownMenu(languageMenu, onDismissRequest = { languageMenu = false }, containerColor = c.surface, shape = Radius12, border = BorderStroke(1.dp, c.outline)) {
                        for (lang in Prompts.LANGUAGES) {
                            DropdownMenuItem(
                                text = { Text(lang, style = MaterialTheme.typography.bodyMedium, color = c.ink) },
                                onClick = {
                                    languageMenu = false
                                    vm.language = lang
                                    vm.send("", Prompts.QuickAction.TRANSLATE)
                                },
                                modifier = Modifier.heightIn(min = Dimens.minTouch),
                            )
                        }
                    }
                }
            } else {
                InkChip(a.label, onClick = { vm.send("", a) }, enabled = !vm.busy)
            }
        }
    }
}

@Composable
private fun TransmissionNotice(providerName: String, vm: AiPanelViewModel) {
    val c = Inkwell.colors
    var dontAsk by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).clip(Radius12).border(1.dp, c.outline, Radius12).background(c.surfaceAlt)
            .padding(start = 14.dp, end = 6.dp, top = 12.dp, bottom = 4.dp),
    ) {
        Text("Selected content will be sent to $providerName", style = MaterialTheme.typography.titleSmall, color = c.ink)
        Text(
            "Only the context chosen above and your question are sent, directly with your API key. Inkwell has no server in between.",
            style = MaterialTheme.typography.bodySmall, color = c.inkMuted, modifier = Modifier.padding(top = 4.dp, end = 8.dp),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.weight(1f).heightIn(min = Dimens.minTouch).clip(Radius8).clickable(role = Role.Checkbox) { dontAsk = !dontAsk },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(dontAsk, onCheckedChange = null, colors = CheckboxDefaults.colors(checkedColor = c.accent, uncheckedColor = c.inkMuted, checkmarkColor = c.onAccent))
                Text("Don't ask again", style = MaterialTheme.typography.bodySmall, color = c.ink, modifier = Modifier.padding(start = 8.dp))
            }
            InkTextButton("Cancel", vm::cancelNotice, color = c.inkMuted)
            InkTextButton("Send", { vm.acceptNotice(dontAsk) })
        }
    }
}

@Composable
private fun InputBar(vm: AiPanelViewModel) {
    val c = Inkwell.colors
    val streaming = vm.streaming != null
    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 4.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        OutlinedTextField(
            value = vm.input,
            onValueChange = { vm.input = it },
            modifier = Modifier.weight(1f).heightIn(min = Dimens.minTouch),
            placeholder = { Text("Ask about your notes…", style = MaterialTheme.typography.bodyMedium) },
            textStyle = MaterialTheme.typography.bodyMedium,
            shape = Radius12,
            colors = inkFieldColors(),
            minLines = 1,
            maxLines = 6,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { vm.send() }),
        )
        Spacer(Modifier.width(4.dp))
        if (streaming) {
            PanelIconButton(Icons.Outlined.Stop, "Stop", vm::stop, tint = c.ink)
        } else {
            PanelIconButton(
                Icons.AutoMirrored.Outlined.Send, "Send", { vm.send() },
                enabled = vm.input.isNotBlank() && !vm.preparing, tint = c.accent,
            )
        }
    }
}

@Composable
private fun UsageFooter(vm: AiPanelViewModel) {
    val c = Inkwell.colors
    val u = vm.lastUsage
    val text = if (u != null) {
        val tokens = "${CostFormat.tokens(u.usage.inputTokens)} in · ${CostFormat.tokens(u.usage.outputTokens)} out" + if (u.estimated) " (estimated)" else ""
        "Last reply: $tokens tokens · est. ${CostFormat.usd(u.usage.estimatedCostUsd)}"
    } else if (vm.preparing) "Preparing context…" else null
    if (text != null) {
        Text(
            text, style = MaterialTheme.typography.labelSmall, color = c.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
        )
    } else Spacer(Modifier.size(4.dp))
}

// ---------------------------------------------------------------------------------------------
// Dialogs

@Composable
internal fun SettingsDialog(onDismiss: () -> Unit) {
    val c = Inkwell.colors
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            Modifier.widthIn(max = 760.dp).fillMaxWidth(0.94f).fillMaxHeight(0.92f),
            shape = Radius16, color = c.ground, border = BorderStroke(1.dp, c.outline),
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 20.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("AI settings", style = MaterialTheme.typography.titleLarge, color = c.ink, modifier = Modifier.weight(1f))
                    PanelIconButton(Icons.Outlined.Close, "Close AI settings", onDismiss)
                }
                HorizontalDivider(color = c.outline)
                AiSettingsImpl(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp))
            }
        }
    }
}

@Composable
private fun DocumentPicker(graph: AppGraph, exclude: Set<String>, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val c = Inkwell.colors
    val docs by remember { graph.library.observeDocuments(LibraryFilter(inFolderOnly = false, sort = LibrarySort.MODIFIED_DESC)) }.collectAsState(initial = emptyList())
    var query by remember { mutableStateOf("") }
    val dateFormat = remember { DateFormat.getDateInstance(DateFormat.MEDIUM) }
    Dialog(onDismissRequest = onDismiss) {
        Surface(Modifier.widthIn(max = 520.dp).fillMaxWidth().fillMaxHeight(0.8f), shape = Radius16, color = c.surface, border = BorderStroke(1.dp, c.outline)) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 20.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Add a document", style = MaterialTheme.typography.titleMedium, color = c.ink, modifier = Modifier.weight(1f))
                    PanelIconButton(Icons.Outlined.Close, "Close", onDismiss)
                }
                OutlinedTextField(
                    value = query, onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    placeholder = { Text("Search documents") },
                    leadingIcon = { Icon(Icons.Outlined.Search, null, Modifier.size(Dimens.icon)) },
                    singleLine = true, shape = Radius12, colors = inkFieldColors(),
                )
                val filtered = docs.filter { it.id !in exclude && it.trashedAt == null && (query.isBlank() || it.title.contains(query.trim(), ignoreCase = true)) }
                if (filtered.isEmpty()) {
                    Text(
                        if (docs.isEmpty()) "No other documents yet." else "No matching documents.",
                        style = MaterialTheme.typography.bodyMedium, color = c.inkMuted, modifier = Modifier.padding(20.dp),
                    )
                }
                LazyColumn(Modifier.fillMaxSize().padding(top = 8.dp)) {
                    items(filtered, key = { it.id }) { d ->
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button) { onPick(d.id) }.padding(horizontal = 20.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                when (d.kind) { DocKind.PDF -> Icons.Outlined.Description; else -> Icons.AutoMirrored.Outlined.NoteAdd },
                                null, Modifier.size(Dimens.icon), tint = c.inkMuted,
                            )
                            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                                Text(d.title, style = MaterialTheme.typography.bodyLarge, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    "${d.kind.name.lowercase().replaceFirstChar { it.uppercase() }} · ${dateFormat.format(Date(d.modifiedAt))}",
                                    style = MaterialTheme.typography.bodySmall, color = c.inkMuted,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
