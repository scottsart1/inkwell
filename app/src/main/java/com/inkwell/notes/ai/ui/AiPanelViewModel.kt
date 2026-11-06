package com.inkwell.notes.ai.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.inkwell.notes.ai.AiServiceImpl
import com.inkwell.notes.ai.chat.ChatItem
import com.inkwell.notes.ai.chat.ChatStore
import com.inkwell.notes.ai.context.AssembledContext
import com.inkwell.notes.ai.context.Citations
import com.inkwell.notes.ai.context.ContextInput
import com.inkwell.notes.ai.context.ContextScope
import com.inkwell.notes.ai.context.Prompts
import com.inkwell.notes.ai.context.SourceRef
import com.inkwell.notes.ai.engine.AiEngine
import com.inkwell.notes.ai.engine.EngineEvent
import com.inkwell.notes.ai.provider.StopReason
import com.inkwell.notes.app.AppGraph
import com.inkwell.notes.core.model.AiCapability
import com.inkwell.notes.core.model.AiMessage
import com.inkwell.notes.core.model.AiModelInfo
import com.inkwell.notes.core.model.AiPart
import com.inkwell.notes.core.model.AiProviderId
import com.inkwell.notes.core.model.AiRequest
import com.inkwell.notes.core.model.AiRole
import com.inkwell.notes.core.model.AiTask
import com.inkwell.notes.core.model.AiUsage
import com.inkwell.notes.core.model.AppSettings
import com.inkwell.notes.core.model.DocKind
import com.inkwell.notes.core.model.ModelRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** A model in the switcher. */
internal data class ModelOption(val info: AiModelInfo) {
    val ref: ModelRef get() = info.ref
    val vision: Boolean get() = AiCapability.VISION in info.capabilities
}

/** The reply being streamed. */
internal data class StreamingReply(
    val model: ModelRef,
    val text: String = "",
    val reasoning: String = "",
    val status: String? = null,
    val notes: List<String> = emptyList(),
)

internal data class ReplyUsage(val usage: AiUsage, val estimated: Boolean, val model: ModelRef)

/** A fully prepared request waiting for the privacy notice or the size confirmation. */
internal data class PendingSend(
    val request: AiRequest,
    val display: String,
    val context: AssembledContext,
    val model: ModelRef,
    val estimatedTokens: Int,
    val estimatedCostUsd: Double?,
    val persistUser: Boolean = true,
    val noticeAccepted: Boolean = false,
    val sizeConfirmed: Boolean = false,
    /** Assistant message this answer regenerates; deleted only once the new answer is stored. */
    val replaces: String? = null,
)

internal data class PanelError(val message: String, val retry: PendingSend? = null)

internal data class DocChip(val id: String, val title: String)

/** State and actions of the assistant panel for one document. Never edits the document. */
internal class AiPanelViewModel(private val graph: AppGraph, val docId: String) : ViewModel() {
    val ai: AiServiceImpl? = graph.ai as? AiServiceImpl
    val settings: StateFlow<AppSettings> = graph.settings.settings
    private val chats: ChatStore? = ai?.chats

    var panelContext by mutableStateOf(AiPanelContext(docId)); private set
    var scope by mutableStateOf<ContextScope?>(ContextScope.DOCUMENT)
    val extraDocs = mutableStateListOf<DocChip>()
    var selectedModel by mutableStateOf<ModelRef?>(null); private set
    var modelOptions by mutableStateOf<List<ModelOption>>(emptyList()); private set
    var modelsLoaded by mutableStateOf(false); private set
    var threadId by mutableStateOf<String?>(null); private set
    var streaming by mutableStateOf<StreamingReply?>(null); private set
    var preparing by mutableStateOf(false); private set
    var error by mutableStateOf<PanelError?>(null)
    var lastUsage by mutableStateOf<ReplyUsage?>(null); private set
    var pendingNotice by mutableStateOf<PendingSend?>(null); private set
    var pendingConfirm by mutableStateOf<PendingSend?>(null); private set
    var monthSpendUsd by mutableStateOf(0.0); private set
    var input by mutableStateOf("")
    var language by mutableStateOf(defaultLanguage())
    var documentTitle by mutableStateOf<String?>(null); private set
    var pageCount by mutableStateOf(0); private set
    var docKind by mutableStateOf<DocKind?>(null); private set
    /** Notes shown under assistant messages (cut off, stopped, images not sent, retrieval). In memory only. */
    val messageNotes = mutableStateMapOf<String, List<String>>()
    val messageReasoning = mutableStateMapOf<String, String>()

    val messages: StateFlow<List<ChatItem>> = snapshotFlow { threadId }
        .flatMapLatest { id -> if (id == null || chats == null) flowOf(emptyList()) else chats.observe(id) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private var job: Job? = null
    private var stopRequested = false
    private val acknowledged = mutableSetOf<AiProviderId>()
    private var lastAutoSent: AiPanelContext? = null
    private var userPickedModel = false

    init {
        viewModelScope.launch {
            threadId = runCatching { chats?.latestThread(docId) }.getOrNull() ?: ChatStore.newThreadId()
        }
        viewModelScope.launch {
            runCatching { graph.library.getDocument(docId) }.getOrNull()?.let { documentTitle = it.title; pageCount = it.pageCount; docKind = it.kind }
        }
        viewModelScope.launch { refreshModels() }
        viewModelScope.launch { refreshSpend() }
        // Keys, routing or providers changed in settings: refresh the switcher.
        viewModelScope.launch {
            settings.map { it.ai }.distinctUntilChanged().drop(1).collect { refreshModels() }
        }
    }

    // ---------------------------------------------------------------- configuration

    val aiEnabled: Boolean get() = settings.value.ai.enabled

    /** Some provider has a key (observable; refreshed with the model list). */
    var configured by mutableStateOf(false); private set

    fun onSettingsClosed() {
        viewModelScope.launch {
            refreshModels()
            refreshSpend()
        }
    }

    suspend fun refreshModels() {
        val ai = ai ?: return
        configured = ai.engine.configuredProviders().isNotEmpty()
        val options = runCatching { ai.modelsWith(AiCapability.TEXT) }.getOrDefault(emptyList())
            .filter { it.ref.provider in ai.engine.configuredProviders() }
            .map { ModelOption(it) }
        modelOptions = options
        val current = selectedModel
        // Keep a model the user picked while its provider stays usable; otherwise follow routing.
        selectedModel = if (current != null && userPickedModel && ai.engine.usable(current.provider)) current
        else ai.modelFor(AiTask.CHAT) ?: options.firstOrNull()?.ref
        modelsLoaded = true
    }

    fun selectModel(ref: ModelRef) {
        selectedModel = ref
        userPickedModel = true
    }

    fun modelName(ref: ModelRef?): String = ref?.let { r -> ai?.modelInfo(r)?.displayName ?: r.modelId } ?: "No model"

    private suspend fun refreshSpend() {
        monthSpendUsd = ai?.monthSpendUsd() ?: 0.0
    }

    // ---------------------------------------------------------------- context

    fun updateContext(ctx: AiPanelContext) {
        val previous = panelContext
        panelContext = ctx
        val hasSelection = !ctx.selectionText.isNullOrBlank() || ctx.selectionImage != null
        val selectionChanged = ctx.selectionText != previous.selectionText || ctx.selectionImage !== previous.selectionImage
        if (hasSelection && (selectionChanged || scope == null)) scope = ContextScope.SELECTION
        if (!hasSelection && scope == ContextScope.SELECTION) scope = if (ctx.pageId != null) ContextScope.PAGE else ContextScope.DOCUMENT
        for (id in ctx.extraDocIds) addDocument(id)
        if (!ctx.initialPrompt.isNullOrBlank() && ctx.initialPrompt != previous.initialPrompt) {
            input = ctx.initialPrompt
        }
        if (ctx.autoSend && !ctx.initialPrompt.isNullOrBlank() && lastAutoSent != ctx) {
            lastAutoSent = ctx
            viewModelScope.launch {
                // Wait until the thread and models are known.
                snapshotFlow { threadId != null && modelsLoaded }.first { it }
                send(ctx.initialPrompt)
            }
        }
    }

    val hasSelection: Boolean get() = !panelContext.selectionText.isNullOrBlank() || panelContext.selectionImage != null

    fun selectionLabel(): String {
        val p = panelContext.pageIndex?.let { " · p.${it + 1}" } ?: ""
        val kind = if (panelContext.selectionImage != null) " handwriting" else ""
        return "Selection$p$kind"
    }

    fun toggleScope(s: ContextScope) {
        scope = if (scope == s) null else s
    }

    fun addDocument(id: String) {
        if (id == docId || extraDocs.any { it.id == id }) return
        extraDocs += DocChip(id, "Document")
        viewModelScope.launch {
            val title = runCatching { graph.library.getDocument(id)?.title }.getOrNull() ?: return@launch
            val i = extraDocs.indexOfFirst { it.id == id }
            if (i >= 0) extraDocs[i] = DocChip(id, title)
        }
    }

    fun removeDocument(id: String) {
        extraDocs.removeAll { it.id == id }
    }

    // ---------------------------------------------------------------- sending

    val busy: Boolean get() = streaming != null || preparing

    fun send(text: String = input, action: Prompts.QuickAction? = null) {
        val prompt = text.trim()
        if ((prompt.isEmpty() && action == null) || busy) return
        error = null
        viewModelScope.launch {
            preparing = true
            try {
                val pending = prepare(prompt, action, persistUser = true) ?: return@launch
                if (action == null) input = ""
                proceed(pending)
            } finally {
                preparing = false
            }
        }
    }

    private suspend fun prepare(prompt: String, action: Prompts.QuickAction?, persistUser: Boolean): PendingSend? {
        val ai = ai ?: return null
        val earlyScope = if (action != null && hasSelection) ContextScope.SELECTION else scope
        val task = action?.task ?: if (earlyScope == ContextScope.DOCUMENT && docKind == DocKind.PDF) AiTask.PDF_ANALYSIS else AiTask.CHAT
        // Routing applies (e.g. Solve -> the MATH model) unless the user picked a model in the switcher.
        val model = (if (userPickedModel) selectedModel else ai.modelFor(task) ?: selectedModel) ?: ai.modelFor(AiTask.CHAT)
        if (model == null) {
            error = PanelError("No chat model is available. Add an API key in AI settings.")
            return null
        }
        val prefs = settings.value.ai
        val spec = ai.engine.spec(model)
        val ctx = panelContext
        // Quick actions always work on the selection when there is one.
        val effectiveScope = when {
            action != null && hasSelection -> ContextScope.SELECTION
            action != null && scope == null -> ContextScope.PAGE
            else -> scope
        }
        val wantImage = effectiveScope == ContextScope.SELECTION && ctx.selectionImage != null && prefs.allowImages && spec.has(AiCapability.VISION)
        val png = if (wantImage) withContext(Dispatchers.Default) {
            runCatching { AiServiceImpl.encodePng(ctx.selectionImage!!, AiServiceImpl.MAX_VISION_EDGE) }.getOrNull()
        } else null
        val question = action?.prompt(language) ?: prompt
        val assembled = withContext(Dispatchers.IO) {
            ai.contextAssembler.assemble(
                scope = effectiveScope,
                input = ContextInput(docId, ctx.selectionText, png, ctx.pageId, ctx.pageIndex),
                question = if (action != null) (ctx.selectionText?.takeIf { it.isNotBlank() } ?: action.label) else prompt,
                extraDocIds = extraDocs.map { it.id },
                retrieval = prefs.retrievalForLargeDocs,
                includeImage = png != null,
            )
        }
        if (effectiveScope == ContextScope.SELECTION && assembled.isEmpty && action != null) {
            error = PanelError(
                if (ctx.selectionImage != null && !spec.has(AiCapability.VISION)) {
                    "The selection is handwriting that has not been recognised yet, and ${spec.displayName} cannot read images. Switch to a vision model, or try again once recognition has run."
                } else if (ctx.selectionImage != null && !prefs.allowImages) {
                    "The selection has no recognised text yet, and sending images is turned off in AI settings."
                } else "The selection has no recognised text yet.",
            )
            return null
        }
        val content = buildString {
            assembled.block?.let { append(it).append("\n\n") }
            append(question)
            if (assembled.sources.isNotEmpty()) append("\n\n").append(Prompts.CITE_REMINDER)
        }
        val history = history(messages.value)
        val userMessage = AiMessage(AiRole.USER, assembled.images + AiPart.Text(content))
        val request = AiRequest(
            task = task,
            messages = history + userMessage,
            system = Prompts.CHAT_SYSTEM,
            maxOutputTokens = 4096,
            model = model,
            documentId = docId,
        )
        return PendingSend(
            request = request,
            display = action?.display(language) ?: prompt,
            context = assembled,
            model = model,
            estimatedTokens = ai.estimateTokens(request),
            estimatedCostUsd = ai.estimateCostUsd(request, 800),
            persistUser = persistUser,
        )
    }

    /** Recent turns (text only), newest last, within a character budget. */
    private fun history(items: List<ChatItem>): List<AiMessage> {
        val out = ArrayList<AiMessage>()
        var budget = HISTORY_CHARS
        for (m in items.asReversed()) {
            if (m.role == AiRole.SYSTEM) continue
            if (out.size >= HISTORY_MESSAGES || m.text.length > budget) break
            budget -= m.text.length
            out += AiMessage(m.role, m.text)
        }
        out.reverse()
        while (out.isNotEmpty() && out.first().role != AiRole.USER) out.removeAt(0)
        return out
    }

    private fun proceed(p: PendingSend) {
        val privacy = settings.value.privacy
        if (!p.noticeAccepted && privacy.confirmAiTransmission && !p.context.isEmpty && p.model.provider !in acknowledged) {
            pendingNotice = p
            return
        }
        val limit = settings.value.ai.confirmAboveTokens
        if (!p.sizeConfirmed && limit > 0 && p.estimatedTokens > limit) {
            pendingConfirm = p
            return
        }
        start(p)
    }

    fun acceptNotice(dontAskAgain: Boolean) {
        val p = pendingNotice ?: return
        pendingNotice = null
        acknowledged += p.model.provider
        if (dontAskAgain) viewModelScope.launch {
            graph.settings.update { it.copy(privacy = it.privacy.copy(confirmAiTransmission = false)) }
        }
        proceed(p.copy(noticeAccepted = true))
    }

    fun cancelNotice() {
        pendingNotice?.let { if (it.persistUser && input.isBlank()) input = it.display }
        pendingNotice = null
    }

    fun confirmLarge() {
        val p = pendingConfirm ?: return
        pendingConfirm = null
        proceed(p.copy(sizeConfirmed = true))
    }

    fun cancelLarge() {
        pendingConfirm?.let { if (it.persistUser && input.isBlank()) input = it.display }
        pendingConfirm = null
    }

    private fun start(p: PendingSend) {
        val ai = ai ?: return
        val chats = chats ?: return
        job?.cancel()
        stopRequested = false
        job = viewModelScope.launch {
            val tid = threadId ?: ChatStore.newThreadId().also { threadId = it }
            if (p.persistUser) runCatching { chats.add(tid, docId, AiRole.USER, p.display) }
            var reply = StreamingReply(p.model, notes = p.context.notes)
            streaming = reply
            var finished = false
            try {
                ai.streamDetailed(p.request).collect { e ->
                    when (e) {
                        is EngineEvent.Started -> reply = reply.copy(notes = reply.notes + e.notes)
                        is EngineEvent.Text -> reply = reply.copy(text = reply.text + e.delta, status = null)
                        is EngineEvent.Reasoning -> reply = reply.copy(reasoning = reply.reasoning + e.delta)
                        is EngineEvent.Retrying -> reply = reply.copy(status = "Retrying (${e.attempt} of ${AiEngine.MAX_RETRIES})…")
                        is EngineEvent.Completed -> {
                            finished = true
                            lastUsage = ReplyUsage(e.usage, e.usageEstimated, e.model)
                            when (e.stop) {
                                StopReason.REFUSAL -> error = PanelError(AiEngine.REFUSAL_MESSAGE, p.copy(persistUser = false))
                                StopReason.CONTENT_FILTER -> error = PanelError(AiEngine.FILTER_MESSAGE, p.copy(persistUser = false))
                                else -> {
                                    val notes = reply.notes + if (e.stop == StopReason.MAX_TOKENS) listOf("The answer was cut off at the output limit.") else emptyList()
                                    val item = chats.add(tid, docId, AiRole.ASSISTANT, e.text, e.model, p.context.sources)
                                    if (notes.isNotEmpty()) messageNotes[item.id] = notes
                                    if (e.reasoning.isNotBlank()) messageReasoning[item.id] = e.reasoning
                                    p.replaces?.let { old ->
                                        runCatching { chats.delete(old) }
                                        messageNotes.remove(old)
                                        messageReasoning.remove(old)
                                    }
                                }
                            }
                        }
                        is EngineEvent.Failed -> {
                            finished = true
                            error = PanelError(e.failure.message, p.copy(persistUser = false))
                        }
                    }
                    streaming = reply
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                // An unexpected engine error must not crash the app from viewModelScope.
                finished = true
                error = PanelError("Something went wrong talking to ${p.model.provider.displayName}. Try again.", p.copy(persistUser = false))
            } finally {
                // A stopped regeneration keeps the complete answer it was replacing instead of adding a partial
                // second answer to the same question.
                if (!finished && stopRequested && p.replaces == null && reply.text.isNotBlank()) {
                    withContext(NonCancellable) {
                        runCatching {
                            val item = chats.add(tid, docId, AiRole.ASSISTANT, reply.text, p.model, p.context.sources)
                            messageNotes[item.id] = reply.notes + "Stopped before the answer was complete."
                            if (reply.reasoning.isNotBlank()) messageReasoning[item.id] = reply.reasoning
                        }
                    }
                }
                streaming = null
                withContext(NonCancellable) { refreshSpend() }
            }
        }
    }

    fun stop() {
        stopRequested = true
        job?.cancel()
    }

    fun retryError() {
        val p = error?.retry ?: return
        error = null
        if (busy) return
        start(p.copy(noticeAccepted = true, sizeConfirmed = true))
    }

    /**
     * Regenerates an assistant answer from the user turn before it, with the current context. The old
     * answer stays until the new one is stored, so a cancelled notice or a failed request loses nothing.
     */
    fun regenerate(messageId: String) {
        if (busy) return
        val list = messages.value
        val idx = list.indexOfFirst { it.id == messageId }
        if (idx < 0) return
        // The new answer is appended at the end of the thread: only the answer to the last turn can be replaced.
        if (list.subList(idx + 1, list.size).any { it.role == AiRole.USER }) return
        val user = list.subList(0, idx).lastOrNull { it.role == AiRole.USER } ?: return
        error = null
        viewModelScope.launch {
            preparing = true
            try {
                val action = quickActionFor(user.text)
                // Turns before the user message, so the history never includes the answer being replaced.
                val historyCut = list.takeWhile { it.id != user.id }
                val pending = prepareWithHistory(user.text, action, historyCut) ?: return@launch
                proceed(pending.copy(replaces = messageId))
            } finally {
                preparing = false
            }
        }
    }

    private suspend fun prepareWithHistory(text: String, action: Prompts.QuickAction?, historyItems: List<ChatItem>): PendingSend? {
        val base = prepare(text, action, persistUser = false) ?: return null
        val last = base.request.messages.last()
        val req = base.request.copy(messages = history(historyItems) + last)
        val ai = ai ?: return null
        return base.copy(request = req, estimatedTokens = ai.estimateTokens(req), estimatedCostUsd = ai.estimateCostUsd(req, 800))
    }

    private fun quickActionFor(display: String): Prompts.QuickAction? {
        Regex("^Translate to (.+)$").matchEntire(display)?.let { language = it.groupValues[1]; return Prompts.QuickAction.TRANSLATE }
        return Prompts.QuickAction.entries.firstOrNull { it.label == display }
    }

    fun newThread() {
        stop()
        threadId = ChatStore.newThreadId()
        error = null
        lastUsage = null
        messageNotes.clear()
        messageReasoning.clear()
    }

    // ---------------------------------------------------------------- sources

    /** Citations in [item] resolved against the sources sent with it. */
    fun sourceChips(item: ChatItem): List<SourceChip> {
        val byLabel = item.sources.associateBy { it.label }
        return Citations.parse(item.text).mapNotNull { c ->
            val src = byLabel[c.label]
            when {
                c.docTag == null && c.page != null -> {
                    if (pageCount > 0 && c.page > pageCount) null
                    else SourceChip(c.label, pageIndex = c.page - 1, source = src)
                }
                src != null -> SourceChip(c.label, pageIndex = if (src.docTag == null) src.pageIndex else null, source = src)
                else -> null
            }
        }
    }

    override fun onCleared() {
        job?.cancel()
        super.onCleared()
    }

    companion object {
        const val HISTORY_CHARS = 24_000
        const val HISTORY_MESSAGES = 20

        private fun defaultLanguage(): String {
            val name = Locale.getDefault().getDisplayLanguage(Locale.ENGLISH)
            return Prompts.LANGUAGES.firstOrNull { it.startsWith(name) } ?: "English"
        }
    }
}

/** A tappable source under an answer. [pageIndex] is set when it opens a page of this document. */
internal data class SourceChip(val label: String, val pageIndex: Int?, val source: SourceRef?)
