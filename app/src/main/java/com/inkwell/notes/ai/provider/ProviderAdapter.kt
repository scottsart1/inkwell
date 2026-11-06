package com.inkwell.notes.ai.provider

import com.inkwell.notes.ai.net.AiFailure
import com.inkwell.notes.ai.net.SseEvent
import com.inkwell.notes.core.model.AiCapability
import com.inkwell.notes.core.model.AiMessage
import com.inkwell.notes.core.model.AiModelInfo
import com.inkwell.notes.core.model.AiProviderId
import com.inkwell.notes.core.model.AiTask
import com.inkwell.notes.core.model.ModelRef
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request

/** Everything Inkwell knows about one model. Prices are estimates in USD. */
@Serializable
data class ModelSpec(
    val id: String,
    val displayName: String,
    val capabilities: Set<AiCapability>,
    val contextTokens: Int,
    val maxOutputTokens: Int = 8192,
    val inputUsdPerMTok: Double? = null,
    val outputUsdPerMTok: Double? = null,
    /** Speech-to-text models are billed per audio minute. */
    val usdPerAudioMinute: Double? = null,
    /** Came from the provider's live model list. */
    val live: Boolean = false,
    /** Prompts above this many input tokens are billed at the long-context prices below, for the whole request. */
    val longContextThresholdTokens: Int? = null,
    val longInputUsdPerMTok: Double? = null,
    val longOutputUsdPerMTok: Double? = null,
) {
    fun has(c: AiCapability) = c in capabilities
    fun toInfo(provider: AiProviderId) = AiModelInfo(
        ref = ModelRef(provider, id),
        displayName = displayName,
        capabilities = capabilities,
        contextTokens = contextTokens,
        inputUsdPerMTok = inputUsdPerMTok,
        outputUsdPerMTok = outputUsdPerMTok,
    )
}

/** How hard the model should think: short utility tasks run at low effort, conversations at medium. */
enum class Effort { LOW, MEDIUM }

fun AiTask.effort(): Effort = when (this) {
    AiTask.NAMING, AiTask.TRANSLATE, AiTask.OCR_ASSIST -> Effort.LOW
    else -> Effort.MEDIUM
}

/** One fully resolved chat request, independent of wire format. */
data class ChatCall(
    val baseUrl: String,
    /** True when [baseUrl] is the provider's official endpoint (no custom base URL configured). */
    val officialEndpoint: Boolean,
    val apiKey: String,
    val model: ModelSpec,
    val task: AiTask,
    val system: String?,
    /** User/assistant turns (system turns are folded into [system] by the adapters). */
    val messages: List<AiMessage>,
    val maxOutputTokens: Int,
    val temperature: Float? = null,
    /** Adapter-specific request variant (e.g. which max-tokens parameter name to use). */
    val variant: Int = 0,
)

enum class StopReason { END, MAX_TOKENS, REFUSAL, CONTENT_FILTER, OTHER }

/** One billed attempt of a response (Anthropic `usage.iterations`); [model] when the provider names it. */
data class UsageIteration(val type: String?, val model: String?, val inputTokens: Int, val outputTokens: Int)

/** Decoded piece of a streamed response. */
sealed interface StreamPart {
    data class Text(val text: String) : StreamPart
    /** Reasoning shown separately from the answer (DeepSeek reasoner `reasoning_content`). */
    data class Reasoning(val text: String) : StreamPart
    data class Usage(
        val inputTokens: Int?,
        val outputTokens: Int?,
        /** Model that actually produced the response, when the provider reports it. */
        val model: String? = null,
        /** Per-attempt usage when the response took several attempts (server-side fallbacks). */
        val iterations: List<UsageIteration>? = null,
    ) : StreamPart
    data class Stop(val reason: StopReason, val raw: String?) : StreamPart
    data class Failure(val failure: AiFailure) : StreamPart
    /** Explicit end of stream (`[DONE]`, `message_stop`). */
    data object End : StreamPart
}

/** Converts one provider's SSE events into [StreamPart]s. One instance per response. */
fun interface StreamDecoder {
    fun decode(event: SseEvent): List<StreamPart>
}

/**
 * A provider's endpoints, auth, request format and capabilities. Adding a provider means
 * implementing this and registering it in [ProviderRegistry].
 */
interface ProviderAdapter {
    val id: AiProviderId
    /** Default base URL without trailing slash, e.g. https://api.openai.com/v1. */
    val defaultBaseUrl: String
    /** Hint shown under the custom endpoint field. */
    val customBaseUrlExample: String
    /** Where users create keys (shown in settings). */
    val keyConsoleUrl: String
    val keyPrefixHint: String?

    /** Static catalogue, in order of preference. */
    val catalogue: List<ModelSpec>

    /** Facts for any model id: the catalogue entry, else derived from the id pattern. */
    fun spec(modelId: String): ModelSpec

    /** Ordered id preferences per capability, used to pick defaults from the live list. */
    fun preferredIds(capability: AiCapability): List<String>

    /** Hides ids from pickers (dated snapshots, non-chat models); they still work if typed in routing. */
    fun isListed(spec: ModelSpec, all: Collection<String>): Boolean = spec.capabilities.isNotEmpty()

    fun modelsRequest(baseUrl: String, apiKey: String): Request
    fun parseModels(body: String): List<ModelSpec>

    /** Output tokens to request for [model] given the caller's [requested] budget. */
    fun outputBudget(model: ModelSpec, requested: Int): Int =
        requested.coerceAtMost(model.maxOutputTokens.takeIf { it > 0 } ?: requested).coerceAtLeast(1)

    fun chatBody(call: ChatCall): JsonObject
    fun chatRequest(call: ChatCall): Request
    fun newDecoder(): StreamDecoder

    /** True when the provider may serve [call] with another model than the requested one (server-side fallbacks). */
    fun mayServeOtherModel(call: ChatCall): Boolean = false

    /** A different request variant to try once after a 400 (e.g. renamed max-tokens parameter), else null. */
    fun variantAfterError(call: ChatCall, status: Int, body: String): ChatCall? = null

    /** Picks a default for [capability] among [available] ids (live list), falling back to the catalogue. */
    fun defaultModel(capability: AiCapability, available: Collection<String>): String? {
        val preferred = preferredIds(capability)
        if (available.isNotEmpty()) {
            val set = available.toSet()
            preferred.firstOrNull { it in set }?.let { return it }
            catalogue.firstOrNull { it.id in set && it.has(capability) }?.let { return it.id }
            available.firstOrNull { val s = spec(it); s.has(capability) && isListed(s, available) }?.let { return it }
        }
        // No live list (offline, not fetched yet): static fallback.
        return preferred.firstOrNull() ?: catalogue.firstOrNull { it.has(capability) }?.id
    }
}

internal val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

/** All adapters, by provider id. */
class ProviderRegistry(adapters: List<ProviderAdapter>) {
    private val byId = adapters.associateBy { it.id }

    operator fun get(id: AiProviderId): ProviderAdapter =
        byId[id] ?: throw IllegalStateException("No adapter registered for ${id.name}")

    fun find(id: AiProviderId): ProviderAdapter? = byId[id]

    val all: List<ProviderAdapter> get() = AiProviderId.entries.mapNotNull { byId[it] }

    companion object {
        fun default() = ProviderRegistry(listOf(OpenAiAdapter(), AnthropicAdapter(), DeepSeekAdapter(), MoonshotAdapter()))
    }
}
