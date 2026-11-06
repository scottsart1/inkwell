package com.inkwell.notes.ai.engine

import com.inkwell.notes.ai.context.Prompts
import com.inkwell.notes.ai.net.AiFailure
import com.inkwell.notes.ai.net.Endpoints
import com.inkwell.notes.ai.net.Redactor
import com.inkwell.notes.ai.net.SseEvent
import com.inkwell.notes.ai.net.ErrorMapper
import com.inkwell.notes.ai.net.SseParser
import com.inkwell.notes.ai.provider.ChatCall
import com.inkwell.notes.ai.provider.DiarizeReferences
import com.inkwell.notes.ai.provider.DiarizedTranscriptionParser
import com.inkwell.notes.ai.provider.ModelSpec
import com.inkwell.notes.ai.provider.OpenAiAdapter
import com.inkwell.notes.ai.provider.OpenAiModels
import com.inkwell.notes.ai.provider.ProviderAdapter
import com.inkwell.notes.ai.provider.ProviderRegistry
import com.inkwell.notes.ai.provider.StopReason
import com.inkwell.notes.ai.provider.StreamPart
import com.inkwell.notes.ai.provider.TranscriptionParser
import com.inkwell.notes.ai.provider.UsageIteration
import com.inkwell.notes.core.model.AiCapability
import com.inkwell.notes.core.model.AiEvent
import com.inkwell.notes.core.model.AiException
import com.inkwell.notes.core.model.AiMessage
import com.inkwell.notes.core.model.AiModelInfo
import com.inkwell.notes.core.model.AiPart
import com.inkwell.notes.core.model.AiPrefs
import com.inkwell.notes.core.model.AiProviderId
import com.inkwell.notes.core.model.AiRequest
import com.inkwell.notes.core.model.AiResult
import com.inkwell.notes.core.model.AiRole
import com.inkwell.notes.core.model.AiTask
import com.inkwell.notes.core.model.AiUsage
import com.inkwell.notes.core.model.ModelRef
import com.inkwell.notes.core.model.Transcript
import com.inkwell.notes.core.model.TranscriptSegment
import com.inkwell.notes.core.storage.db.AiUsageEntity
import com.inkwell.notes.speakers.SpeakerReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean

/** Access to stored API keys. Values never leave the engine except in request headers. */
interface KeyProvider {
    fun has(provider: AiProviderId): Boolean
    fun get(provider: AiProviderId): String?
}

/** Usage accounting (Room's `ai_usage` table on device). */
interface UsageSink {
    suspend fun record(row: AiUsageEntity)
    suspend fun costSince(sinceMs: Long): Double
}

/** Detailed stream events for the assistant panel; [AiEngine.toAiEvents] reduces them to the public contract. */
sealed interface EngineEvent {
    data class Started(val model: ModelRef, val estimatedInputTokens: Int, val notes: List<String>) : EngineEvent
    data class Text(val delta: String) : EngineEvent
    /** Model reasoning (DeepSeek reasoner); never part of the answer. */
    data class Reasoning(val delta: String) : EngineEvent
    data class Retrying(val attempt: Int, val delayMs: Long, val reason: String) : EngineEvent
    data class Completed(
        val text: String,
        val reasoning: String,
        val usage: AiUsage,
        val model: ModelRef,
        val stop: StopReason,
        val notes: List<String>,
        /** True when token counts are local estimates (the provider reported none). */
        val usageEstimated: Boolean,
    ) : EngineEvent
    data class Failed(val failure: AiFailure, val partialText: String) : EngineEvent
}

private class PrepareException(val failure: AiFailure) : Exception(failure.message)

/**
 * Provider-neutral AI core: routing, key/endpoint resolution, image policy, budget limits,
 * streaming with retries, usage accounting, transcription and embeddings. Plain Kotlin
 * (no Android types) so it is fully unit-testable.
 */
class AiEngine(
    http: OkHttpClient,
    private val prefs: () -> AiPrefs,
    private val keys: KeyProvider,
    private val usage: UsageSink,
    val models: ModelRegistry,
    val providers: ProviderRegistry = ProviderRegistry.default(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    /** Custom endpoints must be https; tests against a local server turn this off. */
    private val requireHttps: Boolean = true,
    /** Replaces providers' official base URLs (tests). Calls still count as "official". */
    private val officialBaseOverride: Map<AiProviderId, String> = emptyMap(),
) {
    /**
     * Redirects are not followed: on a host change OkHttp strips only `Authorization`, so it would
     * replay `x-api-key` / `x-goog-api-key` (and, for 307/308, the note content) to the new host.
     * A 3xx surfaces as a configuration error instead (see [ErrorMapper.http]).
     */
    private val http: OkHttpClient = http.newBuilder().followRedirects(false).followSslRedirects(false).build()

    // ------------------------------------------------------------------ configuration

    fun usable(p: AiProviderId): Boolean = prefs().providerSettings(p).enabled && keys.has(p)

    fun configuredProviders(): List<AiProviderId> = prefs().providerOrder().filter { usable(it) }

    fun providerDefault(p: AiProviderId, capability: AiCapability): String? =
        providers.find(p)?.defaultModel(capability, models.liveIds(p))

    fun modelFor(task: AiTask): ModelRef? = ModelRouter.route(
        task, prefs(), ::usable, { models.spec(it).capabilities }, ::providerDefault,
    )

    fun isAvailable(task: AiTask): Boolean {
        val p = prefs()
        if (!p.enabled) return false
        if (task == AiTask.EMBEDDINGS && !p.semanticSearch) return false
        if (task == AiTask.OCR_ASSIST && !p.allowImages) return false
        return modelFor(task) != null
    }

    fun modelInfo(ref: ModelRef): AiModelInfo? = providers.find(ref.provider)?.let { models.info(ref) }

    fun spec(ref: ModelRef): ModelSpec = models.spec(ref)

    /** Effective base URL for [p] and whether it is the provider's official endpoint. */
    fun endpoint(p: AiProviderId, customOverride: String? = null): Pair<String, Boolean> {
        val adapter = providers[p]
        val custom = (customOverride ?: prefs().providerSettings(p).customBaseUrl)?.trim().orEmpty()
        val official = officialBaseOverride[p] ?: adapter.defaultBaseUrl
        return if (custom.isEmpty()) official.trimEnd('/') to true
        else Endpoints.resolve(official, custom, requireHttps) to false
    }

    // ------------------------------------------------------------------ models & keys

    suspend fun models(p: AiProviderId, refresh: Boolean): List<AiModelInfo> = withContext(io) {
        val fetchedAt = models.liveFetchedAt(p)
        val stale = fetchedAt == null || clock() - fetchedAt > ModelRegistry.STALE_MS
        // Offline or failing: do not retry the list on every call.
        val recentlyTried = (lastFetchAttempt[p] ?: 0L).let { clock() - it < FETCH_RETRY_MS && it > 0L }
        if ((refresh || (stale && !recentlyTried)) && keys.has(p)) {
            lastFetchAttempt[p] = clock()
            val key = keys.get(p)
            if (key != null) {
                runCatching {
                    val (base, _) = endpoint(p)
                    models.storeLive(p, fetchModels(p, key, base))
                }.onFailure { if (it is CancellationException) throw it; if (refresh) throw asAiException(p, it, key) }
            }
        }
        listed(p).map { it.toInfo(p) }
    }

    private val lastFetchAttempt = java.util.concurrent.ConcurrentHashMap<AiProviderId, Long>()

    /** Models shown in pickers for [p] (catalogue + live, without hidden snapshots). */
    fun listed(p: AiProviderId): List<ModelSpec> = models.known(p)

    suspend fun modelsWith(capability: AiCapability): List<AiModelInfo> =
        configuredProviders().flatMap { p ->
            runCatching { models(p, refresh = false) }.getOrElse { if (it is CancellationException) throw it; listed(p).map { s -> s.toInfo(p) } }
                .filter { capability in it.capabilities }
        }

    /**
     * Result of an authenticated key check; [rejected] when the provider refused the key itself,
     * [authenticated] when the check failed although the provider accepted the key (a 403 on /models).
     */
    data class KeyCheck(val ok: Boolean, val message: String, val rejected: Boolean, val authenticated: Boolean = false)

    suspend fun checkKey(p: AiProviderId, apiKey: String, baseUrl: String?): KeyCheck {
        val key = apiKey.trim()
        if (key.isEmpty()) return KeyCheck(false, "Enter an API key first.", rejected = false)
        keyFailure(p, key)?.let { return KeyCheck(false, it.message, rejected = false) }
        return try {
            // null = the configured endpoint; "" = the official one.
            val (base, _) = endpoint(p, baseUrl)
            val list = fetchModelsOrFail(p, key, base)
            val currentBase = runCatching { endpoint(p).first }.getOrNull()
            if (currentBase == base) models.storeLive(p, list)
            val ids = list.map { it.id }
            val listedCount = list.count { providers[p].isListed(it, ids) }
            KeyCheck(true, if (listedCount > 0) "Key works. $listedCount models available." else "Key works.", rejected = false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: FailureException) {
            // Only a 401 rejects the key itself. A 403 on /models means it authenticated but may lack the
            // model-list scope (OpenAI restricted keys) or be region/proxy blocked; chat can still work.
            KeyCheck(
                false, e.failure.message,
                rejected = e.failure.kind == AiFailure.Kind.AUTH,
                authenticated = e.failure.kind == AiFailure.Kind.FORBIDDEN,
            )
        } catch (e: Throwable) {
            KeyCheck(false, asAiException(p, e, key).message ?: "Validation failed.", rejected = false)
        }
    }

    suspend fun validateKey(p: AiProviderId, apiKey: String, baseUrl: String?): Result<String> {
        val r = checkKey(p, apiKey, baseUrl)
        return if (r.ok) Result.success(r.message) else Result.failure(AiException(r.message))
    }

    private suspend fun fetchModels(p: AiProviderId, key: String, base: String): List<ModelSpec> =
        try { fetchModelsOrFail(p, key, base) } catch (e: FailureException) { throw e.failure.toException() }

    private suspend fun fetchModelsOrFail(p: AiProviderId, key: String, base: String): List<ModelSpec> = withContext(io) {
        val adapter = providers[p]
        val body = executeForBodyOrFail(p, adapter.modelsRequest(base, key), key, modelId = null)
        adapter.parseModels(body)
    }

    private fun asAiException(p: AiProviderId, e: Throwable, key: String?): AiException = when (e) {
        is AiException -> AiException(Redactor.redact(e.message, key), e.retryable, null)
        else -> ErrorMapper.io(p, e, key).toException()
    }

    /**
     * Failure for a stored key that cannot travel in an HTTP header (OkHttp rejects anything
     * outside printable ASCII, e.g. a zero-width space picked up when copying the key), or null.
     * Checked before any request is built so the rejection never surfaces as an exception
     * carrying the key.
     */
    private fun keyFailure(p: AiProviderId, key: String): AiFailure? =
        if (headerSafe(key)) null else AiFailure(
            "The ${p.displayName} API key contains characters that cannot be sent (for example an invisible character " +
                "picked up when copying it). Open Settings > AI providers and paste the key again.",
            retryable = false, kind = AiFailure.Kind.CONFIG,
        )

    // ------------------------------------------------------------------ budget

    fun monthStart(): Long = MonthWindow.startOfMonth(clock(), zone())

    /** Last month spend read from the usage table, for checks that cannot query it (main thread). */
    private class SpendSnapshot(val monthStart: Long, val spent: Double)

    @Volatile private var spendSnapshot: SpendSnapshot? = null

    suspend fun monthSpendUsd(): Double {
        val start = monthStart()
        return runCatching { usage.costSince(start) }
            .onSuccess { spendSnapshot = SpendSnapshot(start, it) }
            .getOrDefault(0.0)
    }

    /** True when the last known spend of this month already reached the hard limit (unknown: false). */
    fun budgetKnownExceeded(): Boolean {
        val limit = prefs().monthlyHardLimitUsd
        if (limit <= 0.0) return false
        val snap = spendSnapshot ?: return false
        return snap.monthStart == monthStart() && snap.spent >= limit
    }

    /** Failure when the monthly hard limit is reached, else null. */
    suspend fun budgetFailure(): AiFailure? {
        val limit = prefs().monthlyHardLimitUsd
        if (limit <= 0.0) return null
        val spent = monthSpendUsd()
        if (spent < limit) return null
        return AiFailure(
            "This month's estimated AI spend (${CostFormat.usd(spent)}) has reached your limit of ${CostFormat.usd(limit)}. " +
                "Raise or remove the limit in Settings > AI providers > Limits to continue.",
            retryable = false, kind = AiFailure.Kind.LIMIT,
        )
    }

    // ------------------------------------------------------------------ estimates

    fun estimateTokens(request: AiRequest): Int = TokenEstimator.messages(request.system, request.messages)

    fun estimateCostUsd(request: AiRequest, expectedOutputTokens: Int): Double? {
        val ref = request.model ?: modelFor(request.task) ?: return null
        return CostCalculator.tokens(models.spec(ref), estimateTokens(request), expectedOutputTokens)
    }

    // ------------------------------------------------------------------ chat

    private data class Prepared(val call: ChatCall, val ref: ModelRef, val adapter: ProviderAdapter, val estimatedInput: Int, val notes: List<String>)

    private fun fail(message: String, kind: AiFailure.Kind = AiFailure.Kind.CONFIG): Nothing =
        throw PrepareException(AiFailure(message, retryable = false, kind = kind))

    private fun prepare(request: AiRequest): Prepared {
        val p = prefs()
        if (!p.enabled) fail("AI features are turned off. Turn on \"Use AI features\" in Settings > AI providers.")
        val ref = request.model ?: modelFor(request.task)
            ?: fail(
                if (configuredProviders().isEmpty()) "No AI provider is set up. Add an API key in Settings > AI providers."
                else "None of your configured models can handle ${request.task.label.lowercase()}. " +
                    "Choose a model with ${request.task.required.name.lowercase().replace('_', ' ')} support in AI settings.",
            )
        val adapter = providers.find(ref.provider) ?: fail("${ref.provider.displayName} is not supported in this version.")
        if (!p.providerSettings(ref.provider).enabled) fail("${ref.provider.displayName} is turned off in AI settings.")
        val key = keys.get(ref.provider)?.takeIf { it.isNotBlank() }
            ?: fail("Add your ${ref.provider.displayName} API key in Settings > AI providers to use ${ref.modelId}.")
        keyFailure(ref.provider, key)?.let { throw PrepareException(it) }
        val spec = models.spec(ref)
        if (!spec.has(AiCapability.TEXT)) fail("${spec.displayName} is not a chat model. Choose a text model.")
        val (base, official) = try { endpoint(ref.provider) } catch (e: AiException) { fail(e.message ?: "Invalid custom endpoint.") }

        val notes = ArrayList<String>()
        var messages = request.messages
        if (messages.any { m -> m.parts.any { it is AiPart.Image } }) {
            val visionTask = request.task.required == AiCapability.VISION
            val reason = when {
                !p.allowImages -> if (visionTask) fail("Sending images is turned off in AI settings (Limits > Allow images).") else "Images are turned off in AI settings, so only text was sent."
                !spec.has(AiCapability.VISION) -> if (visionTask) fail("${spec.displayName} cannot read images. Choose a vision model for ${request.task.label}.") else "${spec.displayName} does not accept images, so only text was sent."
                else -> null
            }
            if (reason != null) {
                notes += reason
                messages = stripImages(messages)
            }
        }
        if (messages.none { it.role == AiRole.USER && (it.text().isNotBlank() || it.parts.any { p2 -> p2 is AiPart.Image }) }) {
            fail("There is nothing to send: the selection has no recognised text yet. Choose a vision model to read handwriting directly.", AiFailure.Kind.BAD_REQUEST)
        }
        val estIn = TokenEstimator.messages(request.system, messages)
        if (spec.contextTokens in 1 until estIn) {
            fail(
                "This request (about ${CostFormat.tokens(estIn)} tokens) is larger than ${spec.displayName}'s context window " +
                    "(${CostFormat.tokens(spec.contextTokens)} tokens). Select less content or choose a model with a larger context.",
                AiFailure.Kind.CONTEXT_TOO_LONG,
            )
        }
        var budget = adapter.outputBudget(spec, request.maxOutputTokens)
        if (spec.contextTokens > 0) budget = minOf(budget, (spec.contextTokens - estIn - 64).coerceAtLeast(minOf(256, budget)))
        val call = ChatCall(
            baseUrl = base, officialEndpoint = official, apiKey = key, model = spec, task = request.task,
            system = request.system, messages = messages, maxOutputTokens = budget, temperature = request.temperature,
        )
        return Prepared(call, ref, adapter, estIn, notes)
    }

    private fun stripImages(messages: List<AiMessage>): List<AiMessage> = messages.map { m ->
        if (m.parts.none { it is AiPart.Image }) m
        else AiMessage(m.role, m.parts.filterIsInstance<AiPart.Text>() + AiPart.Text("(An image attached here was not sent.)"))
    }

    private sealed interface Outcome {
        data object Success : Outcome
        data class Http(val status: Int, val body: String, val retryAfter: String?) : Outcome
        data class Stream(val failure: AiFailure) : Outcome
        data class Io(val error: Throwable) : Outcome
    }

    /** Streams [request] with up to [MAX_RETRIES] retries for retryable errors before the first token. */
    fun streamDetailed(request: AiRequest): Flow<EngineEvent> = flow {
        val prepared = try {
            prepare(request)
        } catch (e: PrepareException) {
            emit(EngineEvent.Failed(e.failure, ""))
            return@flow
        }
        budgetFailure()?.let { emit(EngineEvent.Failed(it, "")); return@flow }
        emit(EngineEvent.Started(prepared.ref, prepared.estimatedInput, prepared.notes))

        val text = StringBuilder()
        val reasoning = StringBuilder()
        var inTokens: Int? = null
        var outTokens: Int? = null
        var stop: StopReason? = null
        var servedModel: String? = null
        var iterations: List<UsageIteration>? = null
        var call = prepared.call
        var attempt = 0
        var variantSwitches = 0
        var finished = false
        try {
            while (true) {
                inTokens = null; outTokens = null; stop = null; servedModel = null; iterations = null
                val outcome = executeStream(call, prepared.adapter) { part ->
                    when (part) {
                        is StreamPart.Text -> { text.append(part.text); emit(EngineEvent.Text(part.text)) }
                        is StreamPart.Reasoning -> { reasoning.append(part.text); emit(EngineEvent.Reasoning(part.text)) }
                        is StreamPart.Usage -> {
                            part.inputTokens?.let { inTokens = it }; part.outputTokens?.let { outTokens = it }
                            part.model?.let { servedModel = it }; part.iterations?.let { iterations = it }
                        }
                        is StreamPart.Stop -> if (stop != StopReason.REFUSAL) stop = part.reason
                        is StreamPart.Failure, StreamPart.End -> Unit
                    }
                }
                val failure: AiFailure = when (outcome) {
                    Outcome.Success -> break
                    is Outcome.Http -> {
                        val alt = if (text.isEmpty() && variantSwitches < 3) prepared.adapter.variantAfterError(call, outcome.status, outcome.body) else null
                        if (alt != null) { call = alt; variantSwitches++; continue }
                        ErrorMapper.http(prepared.ref.provider, outcome.status, outcome.body, outcome.retryAfter, prepared.ref.modelId, call.apiKey, clock())
                    }
                    // Decoders do not know the key: a gateway echoing it inside an error event is redacted here.
                    is Outcome.Stream -> outcome.failure.copy(message = Redactor.redact(outcome.failure.message, call.apiKey))
                    is Outcome.Io -> ErrorMapper.io(prepared.ref.provider, outcome.error, call.apiKey)
                }
                if (failure.retryable && text.isEmpty() && reasoning.isEmpty() && attempt < MAX_RETRIES) {
                    val wait = backoffMs(attempt, failure.retryAfterMs)
                    if (wait != null) {
                        attempt++
                        emit(EngineEvent.Retrying(attempt, wait, failure.message))
                        sleep(wait)
                        continue
                    }
                }
                finished = true
                if (text.isNotEmpty() || reasoning.isNotEmpty()) record(prepared, request, inTokens, outTokens, text.length + reasoning.length, servedModel, iterations)
                emit(EngineEvent.Failed(failure, text.toString()))
                return@flow
            }
            finished = true
            val usageRow = record(prepared, request, inTokens, outTokens, text.length + reasoning.length, servedModel, iterations)
            emit(
                EngineEvent.Completed(
                    text = text.toString(),
                    reasoning = reasoning.toString(),
                    usage = usageRow.first,
                    model = prepared.ref,
                    stop = stop ?: StopReason.END,
                    notes = prepared.notes,
                    usageEstimated = usageRow.second,
                ),
            )
        } finally {
            if (!finished && (text.isNotEmpty() || reasoning.isNotEmpty() || inTokens != null)) {
                // Cancelled (Stop button, screen closed): the provider still bills what it generated.
                withContext(NonCancellable) { runCatching { record(prepared, request, inTokens, outTokens, text.length + reasoning.length, servedModel, iterations) } }
            }
        }
    }.flowOn(io)

    /** Writes a usage row; returns the usage and whether tokens were estimated. */
    private suspend fun record(
        prepared: Prepared,
        request: AiRequest,
        inTokens: Int?,
        outTokens: Int?,
        outChars: Int,
        servedModel: String? = null,
        iterations: List<UsageIteration>? = null,
    ): Pair<AiUsage, Boolean> {
        val requested = prepared.call.model
        // Server-side fallbacks: the response may come from another model, billed at that model's prices,
        // and every attempt (including declined ones) is listed in `iterations`.
        val fallbacks = prepared.adapter.mayServeOtherModel(prepared.call)
        val served = servedModel?.takeIf { fallbacks && it != requested.id }
        val servedSpec = served?.let { models.spec(ModelRef(prepared.ref.provider, it)) } ?: requested
        val attempts = iterations?.takeIf { fallbacks && inTokens != null }
        val input: Int
        val output: Int
        val cost: Double?
        if (attempts != null) {
            input = attempts.sumOf { it.inputTokens }
            output = attempts.sumOf { it.outputTokens }
            val costs = attempts.map { a ->
                val spec = a.model?.let { models.spec(ModelRef(prepared.ref.provider, it)) }
                    ?: if (a.type == "fallback_message") servedSpec else requested
                CostCalculator.tokens(spec, a.inputTokens, a.outputTokens)
            }
            cost = if (costs.any { it == null }) null else costs.sumOf { it ?: 0.0 }
        } else {
            input = inTokens ?: prepared.estimatedInput
            output = outTokens ?: ((outChars + 3) / 4)
            cost = CostCalculator.tokens(servedSpec, input, output)
        }
        val u = AiUsage(input, output, cost)
        runCatching {
            usage.record(
                AiUsageEntity(
                    timestamp = clock(), provider = prepared.ref.provider.name, model = served ?: prepared.ref.modelId,
                    task = request.task.name, inputTokens = input, outputTokens = output,
                    estimatedCostUsd = cost, documentId = request.documentId,
                ),
            )
        }
        return u to (inTokens == null || outTokens == null)
    }

    private suspend fun executeStream(call: ChatCall, adapter: ProviderAdapter, onPart: suspend (StreamPart) -> Unit): Outcome {
        // Building the request can reject a header value (OkHttp's message may quote it): never let that escape the flow.
        val request = try {
            adapter.chatRequest(call)
        } catch (e: IllegalArgumentException) {
            return Outcome.Stream(
                AiFailure(
                    "The request to ${adapter.id.displayName} could not be built: " + Redactor.redact(e.message ?: "invalid value", call.apiKey).take(MAX_DETAIL_CHARS),
                    retryable = false, kind = AiFailure.Kind.CONFIG,
                ),
            )
        }
        return withCancellableCall(request) { response ->
            if (!response.isSuccessful) {
                return@withCancellableCall Outcome.Http(response.code, readLimited(response), response.header("Retry-After"))
            }
            val body = response.body ?: return@withCancellableCall Outcome.Io(IOException("empty response"))
            val contentType = body.contentType()?.toString().orEmpty()
            if (contentType.startsWith("application/json")) {
                // A gateway ignored `stream: true`: decode the complete response the same way.
                val raw = body.string()
                val parts = adapter.newDecoder().decode(SseEvent(null, raw))
                parts.firstOrNull { it is StreamPart.Failure }?.let { return@withCancellableCall Outcome.Stream((it as StreamPart.Failure).failure) }
                if (parts.none { it is StreamPart.Text || it is StreamPart.Stop }) {
                    val msg = ErrorMapper.providerMessage(raw)
                    return@withCancellableCall Outcome.Stream(ErrorMapper.streamError(adapter.id, null, msg ?: "Unexpected response format."))
                }
                for (part in parts) onPart(part)
                return@withCancellableCall Outcome.Success
            }
            val source = body.source()
            val parser = SseParser()
            val decoder = adapter.newDecoder()
            var ended = false
            var stopped = false
            suspend fun handle(events: List<SseEvent>): Outcome? {
                for (ev in events) for (part in decoder.decode(ev)) {
                    when (part) {
                        is StreamPart.Failure -> return Outcome.Stream(part.failure)
                        StreamPart.End -> ended = true
                        is StreamPart.Stop -> stopped = true
                        else -> Unit
                    }
                    onPart(part)
                }
                return null
            }
            while (!ended) {
                currentCoroutineContext().ensureActive()
                val line = source.readUtf8Line() ?: break
                handle(parser.feedLine(line))?.let { return@withCancellableCall it }
            }
            if (!ended) handle(parser.finish())?.let { return@withCancellableCall it }
            if (ended || stopped) Outcome.Success
            else Outcome.Io(IOException("the connection closed before the answer was complete"))
        }
    }

    /**
     * Executes [request] so that cancelling the coroutine cancels the HTTP call (which unblocks
     * reads). I/O errors become [Outcome.Io]; cancellation propagates.
     */
    private suspend fun withCancellableCall(request: Request, block: suspend (Response) -> Outcome): Outcome = coroutineScope {
        val okCall = http.newCall(request)
        val done = AtomicBoolean(false)
        val watcher = launch { try { awaitCancellation() } finally { if (!done.get()) okCall.cancel() } }
        try {
            okCall.execute().use { block(it) }
        } catch (e: IOException) {
            currentCoroutineContext().ensureActive()
            Outcome.Io(e)
        } finally {
            done.set(true)
            watcher.cancel()
        }
    }

    private fun readLimited(response: Response): String =
        runCatching { response.peekBody(MAX_ERROR_BODY).string() }.getOrDefault("")

    /** Exponential backoff (1 s, 2 s) or the server's Retry-After; null when the wait is too long to retry. */
    fun backoffMs(attempt: Int, retryAfterMs: Long?): Long? {
        if (retryAfterMs != null) return if (retryAfterMs > MAX_RETRY_AFTER_MS) null else retryAfterMs.coerceAtLeast(250)
        return BASE_BACKOFF_MS shl attempt
    }

    /** Public-contract events: answer deltas, then Done or Error (refusals become errors). */
    fun toAiEvents(events: Flow<EngineEvent>): Flow<AiEvent> = flow {
        events.collect { e ->
            when (e) {
                is EngineEvent.Text -> emit(AiEvent.Delta(e.delta))
                is EngineEvent.Completed -> emit(
                    when (e.stop) {
                        StopReason.REFUSAL -> AiEvent.Error(REFUSAL_MESSAGE)
                        StopReason.CONTENT_FILTER -> AiEvent.Error(FILTER_MESSAGE)
                        else -> AiEvent.Done(e.text, e.usage, e.model)
                    },
                )
                is EngineEvent.Failed -> emit(AiEvent.Error(e.failure.message, e.failure.retryable))
                else -> Unit
            }
        }
    }

    suspend fun complete(request: AiRequest): AiResult {
        var result: AiResult? = null
        var error: AiException? = null
        streamDetailed(request).collect { e ->
            when (e) {
                is EngineEvent.Completed -> when (e.stop) {
                    StopReason.REFUSAL -> error = AiException(REFUSAL_MESSAGE)
                    StopReason.CONTENT_FILTER -> error = AiException(FILTER_MESSAGE)
                    else -> result = AiResult(e.text, e.usage, e.model)
                }
                is EngineEvent.Failed -> error = e.failure.toException()
                else -> Unit
            }
        }
        error?.let { throw it }
        return result ?: throw AiException("The model returned no answer.", retryable = true)
    }

    // ------------------------------------------------------------------ vision OCR

    /** Vision transcription of a page image (PNG/JPEG bytes). Null when no vision model is available. */
    suspend fun visionTranscribe(image: ByteArray, mimeType: String, hint: String?, documentId: String? = null): AiResult? {
        if (!isAvailable(AiTask.OCR_ASSIST)) return null
        val request = AiRequest(
            task = AiTask.OCR_ASSIST,
            system = Prompts.OCR_SYSTEM,
            messages = listOf(AiMessage(AiRole.USER, listOf(AiPart.Image(image, mimeType), AiPart.Text(Prompts.ocrUser(hint))))),
            maxOutputTokens = 4096,
            documentId = documentId,
        )
        return complete(request)
    }

    // ------------------------------------------------------------------ non-streaming helpers

    /** Executes with retries (before any data) and returns the body, or throws [AiException]. */
    private suspend fun executeForBody(p: AiProviderId, request: Request, key: String, modelId: String?): String =
        try { executeForBodyOrFail(p, request, key, modelId) } catch (e: FailureException) { throw e.failure.toException() }

    private suspend fun executeForBodyOrFail(p: AiProviderId, request: Request, key: String, modelId: String?): String {
        var attempt = 0
        while (true) {
            val result = runCatching {
                withCancellableBody(request) { r ->
                    if (r.isSuccessful) Result.success(r.body?.string().orEmpty())
                    else Result.failure(ErrorMapper.http(p, r.code, readLimited(r), r.header("Retry-After"), modelId, key, clock()).let { FailureException(it) })
                }
            }.getOrElse { e -> if (e is CancellationException) throw e; Result.failure(FailureException(ErrorMapper.io(p, e, key))) }
            result.onSuccess { return it }
            val failure = (result.exceptionOrNull() as FailureException).failure
            if (failure.retryable && attempt < MAX_RETRIES) {
                val wait = backoffMs(attempt, failure.retryAfterMs)
                if (wait != null) { attempt++; sleep(wait); continue }
            }
            throw FailureException(failure)
        }
    }

    private class FailureException(val failure: AiFailure) : Exception(failure.message)

    private suspend fun <T> withCancellableBody(request: Request, block: (Response) -> T): T = withContext(io) {
        coroutineScope {
            val okCall = http.newCall(request)
            val done = AtomicBoolean(false)
            val watcher = launch { try { awaitCancellation() } finally { if (!done.get()) okCall.cancel() } }
            try {
                okCall.execute().use(block)
            } catch (e: IOException) {
                currentCoroutineContext().ensureActive()
                throw e
            } finally {
                done.set(true)
                watcher.cancel()
            }
        }
    }

    // ------------------------------------------------------------------ transcription

    /** Transcribes ordered audio segments with the TRANSCRIPTION model; null when unavailable. */
    suspend fun transcribe(recordingId: String, segments: List<File>, segmentDurationsMs: List<Long>, language: String?): Transcript? = withContext(io) {
        transcribeOnIo(recordingId, segments, segmentDurationsMs, language)
    }

    private suspend fun transcribeOnIo(recordingId: String, segments: List<File>, segmentDurationsMs: List<Long>, language: String?): Transcript? {
        if (!isAvailable(AiTask.TRANSCRIPTION)) return null
        val ref = modelFor(AiTask.TRANSCRIPTION) ?: return null
        val adapter = providers.find(ref.provider) as? OpenAiAdapter ?: return null
        budgetFailure()?.let { throw it.toException() }
        val key = keys.get(ref.provider) ?: return null
        keyFailure(ref.provider, key)?.let { throw it.toException() }
        val (base, _) = endpoint(ref.provider)
        val spec = models.spec(ref)
        val all = ArrayList<TranscriptSegment>()
        var offset = 0L
        var detected: String? = null
        var totalMs = 0L
        for ((i, file) in segments.withIndex()) {
            val duration = segmentDurationsMs.getOrNull(i)?.coerceAtLeast(0) ?: 0L
            if (!file.exists() || file.length() == 0L) { offset += duration; continue }
            if (file.length() > MAX_AUDIO_UPLOAD_BYTES) {
                throw AiException("Audio segment ${i + 1} is larger than OpenAI's 25 MB upload limit.")
            }
            val body = executeForBody(ref.provider, adapter.transcriptionRequest(base, key, file, ref.modelId, language), key, ref.modelId)
            val parsed = TranscriptionParser.parse(body, offset, duration)
            all += parsed.segments
            if (detected == null) detected = parsed.language
            val advance = if (duration > 0) duration else (parsed.segments.lastOrNull()?.endMs?.minus(offset) ?: 0L)
            offset += advance
            totalMs += advance
        }
        val text = all.joinToString("\n") { it.text }
        val outTokens = TokenEstimator.text(text)
        runCatching {
            usage.record(
                AiUsageEntity(
                    timestamp = clock(), provider = ref.provider.name, model = ref.modelId, task = AiTask.TRANSCRIPTION.name,
                    inputTokens = 0, outputTokens = outTokens, estimatedCostUsd = CostCalculator.audio(spec, totalMs), documentId = null,
                ),
            )
        }
        return Transcript(recordingId, all, "openai:${ref.modelId}", language ?: detected)
    }

    // ------------------------------------------------------------------ speaker-labelled transcription

    /**
     * True when speakers can be labelled by OpenAI: AI on, OpenAI enabled with a stored key and
     * a usable (official or custom) endpoint. The monthly limit is not part of this check.
     */
    fun diarizeConfigured(): Boolean {
        val p = AiProviderId.OPENAI
        if (!prefs().enabled || !usable(p)) return false
        if (providers.find(p) !is OpenAiAdapter) return false
        return runCatching { endpoint(p) }.isSuccess
    }

    /** [diarizeConfigured] and the monthly limit not known to be reached. Never touches the disk or network. */
    fun canDiarize(): Boolean = diarizeConfigured() && !budgetKnownExceeded()

    /**
     * Transcribes one file with speaker labels (gpt-4o-transcribe-diarize, diarized_json).
     * Segments carry the provider's speaker label, are shifted by [offsetMs] and clamped to
     * [durationMs]. [references] are filtered to what the API accepts; when the API still
     * refuses the request (400, 413, 422) it is sent once more without them, so voices are labelled
     * as new rather than the transcript failing. Null when unavailable; throws [AiException].
     */
    suspend fun transcribeDiarized(
        recordingId: String,
        file: File,
        durationMs: Long,
        offsetMs: Long,
        references: List<SpeakerReference>,
        language: String?,
    ): Transcript? = withContext(io) { transcribeDiarizedOnIo(recordingId, file, durationMs, offsetMs, references, language) }

    private suspend fun transcribeDiarizedOnIo(
        recordingId: String,
        file: File,
        durationMs: Long,
        offsetMs: Long,
        references: List<SpeakerReference>,
        language: String?,
    ): Transcript? {
        val p = AiProviderId.OPENAI
        if (!diarizeConfigured()) return null
        val adapter = providers.find(p) as? OpenAiAdapter ?: return null
        budgetFailure()?.let { throw it.toException() }
        val key = keys.get(p)?.takeIf { it.isNotBlank() } ?: return null
        keyFailure(p, key)?.let { throw it.toException() }
        val (base, _) = endpoint(p)
        val model = OpenAiModels.DIARIZE_MODEL
        val source = "openai:$model"
        if (!file.isFile || file.length() == 0L) return Transcript(recordingId, emptyList(), source, language)
        if (file.length() > MAX_AUDIO_UPLOAD_BYTES) throw AiException("This audio segment is larger than OpenAI's 25 MB upload limit.")
        val refs = DiarizeReferences.prepare(references)
        val body = try {
            executeForBodyOrFail(p, adapter.diarizedTranscriptionRequest(base, key, file, model, language, refs), key, model)
        } catch (e: FailureException) {
            if (refs.isEmpty() || e.failure.httpStatus !in REFERENCE_REFUSED_STATUSES) throw e.failure.toException()
            try {
                executeForBodyOrFail(p, adapter.diarizedTranscriptionRequest(base, key, file, model, language, emptyList()), key, model)
            } catch (e2: FailureException) {
                throw e2.failure.toException()
            }
        }
        val parsed = DiarizedTranscriptionParser.parse(body, offsetMs, durationMs)
        val spec = models.spec(ModelRef(p, model))
        val billedMs = durationMs.takeIf { it > 0 } ?: parsed.durationMs
        val outTokens = parsed.outputTokens ?: TokenEstimator.text(parsed.segments.joinToString("\n") { it.text })
        runCatching {
            usage.record(
                AiUsageEntity(
                    timestamp = clock(), provider = p.name, model = model, task = AiTask.TRANSCRIPTION.name,
                    inputTokens = parsed.inputTokens ?: 0, outputTokens = outTokens,
                    estimatedCostUsd = CostCalculator.audio(spec, billedMs), documentId = null,
                ),
            )
        }
        return Transcript(recordingId, parsed.segments, source, language ?: parsed.language)
    }

    // ------------------------------------------------------------------ embeddings

    /** Embeddings in batches of 64; null when semantic search or an embedding model is unavailable. */
    suspend fun embed(texts: List<String>, documentId: String? = null): List<FloatArray>? = withContext(io) { embedOnIo(texts, documentId) }

    private suspend fun embedOnIo(texts: List<String>, documentId: String?): List<FloatArray>? {
        if (!isAvailable(AiTask.EMBEDDINGS)) return null
        val ref = modelFor(AiTask.EMBEDDINGS) ?: return null
        val adapter = providers.find(ref.provider) as? OpenAiAdapter ?: return null
        if (texts.isEmpty()) return emptyList()
        budgetFailure()?.let { throw it.toException() }
        val key = keys.get(ref.provider) ?: return null
        keyFailure(ref.provider, key)?.let { throw it.toException() }
        val (base, _) = endpoint(ref.provider)
        val out = ArrayList<FloatArray>(texts.size)
        var tokens = 0
        try {
            for (batch in texts.chunked(EMBED_BATCH)) {
                val inputs = batch.map { it.ifBlank { " " }.take(MAX_EMBED_CHARS) }
                val body = executeForBody(ref.provider, adapter.embeddingsRequest(base, key, ref.modelId, inputs), key, ref.modelId)
                val (vectors, used) = adapter.parseEmbeddings(body)
                if (vectors.size != batch.size) throw AiException("OpenAI returned ${vectors.size} embeddings for ${batch.size} texts.", retryable = true)
                out += vectors
                tokens += used ?: inputs.sumOf { TokenEstimator.text(it) }
            }
        } finally {
            if (tokens > 0) withContext(NonCancellable) {
                runCatching {
                    usage.record(
                        AiUsageEntity(
                            timestamp = clock(), provider = ref.provider.name, model = ref.modelId, task = AiTask.EMBEDDINGS.name,
                            inputTokens = tokens, outputTokens = 0, estimatedCostUsd = CostCalculator.tokens(models.spec(ref), tokens, 0),
                            documentId = documentId,
                        ),
                    )
                }
            }
        }
        return out
    }

    companion object {
        const val MAX_RETRIES = 2
        const val FETCH_RETRY_MS = 10L * 60 * 1000
        const val BASE_BACKOFF_MS = 1_000L
        const val MAX_RETRY_AFTER_MS = 30_000L
        const val MAX_ERROR_BODY = 64L * 1024
        const val MAX_AUDIO_UPLOAD_BYTES = 25L * 1024 * 1024
        /** Responses after which a speaker-labelled request is sent again without its reference clips. */
        private val REFERENCE_REFUSED_STATUSES = setOf(400, 413, 422)
        const val EMBED_BATCH = 64
        const val MAX_EMBED_CHARS = 24_000
        const val REFUSAL_MESSAGE = "The model declined this request."
        const val FILTER_MESSAGE = "The provider's content filter stopped this answer."
        private const val MAX_DETAIL_CHARS = 200

        /** True when [value] can be sent as an HTTP header value (printable ASCII or tab, as OkHttp requires). */
        fun headerSafe(value: String): Boolean = value.all { it == '\t' || it in ' '..'~' }
    }
}
