package com.inkwell.notes.ai.provider

import com.inkwell.notes.ai.net.ErrorMapper
import com.inkwell.notes.ai.net.LenientJson
import com.inkwell.notes.ai.net.SseEvent
import com.inkwell.notes.core.model.AiCapability
import com.inkwell.notes.core.model.AiMessage
import com.inkwell.notes.core.model.AiPart
import com.inkwell.notes.core.model.AiProviderId
import com.inkwell.notes.core.model.AiRole
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Anthropic Messages API over raw HTTP: `POST /v1/messages` with `x-api-key` and
 * `anthropic-version: 2023-06-01`, top-level `system`, content blocks, `stream: true`.
 * Never sends sampling parameters (temperature/top_p/top_k), `thinking` or an assistant prefill.
 */
class AnthropicAdapter : ProviderAdapter {
    override val id = AiProviderId.ANTHROPIC
    override val defaultBaseUrl = "https://api.anthropic.com/v1"
    override val customBaseUrlExample = "https://your-gateway.example.com/v1"
    override val keyConsoleUrl = "https://console.anthropic.com/settings/keys"
    override val keyPrefixHint: String = "sk-ant-"

    override val catalogue: List<ModelSpec> = listOf(
        m("claude-opus-5-5", "Claude Opus 5.5", 1_000_000, 128_000, 4.0, 20.0),
        m("claude-sonnet-5-5", "Claude Sonnet 5.5", 1_000_000, 128_000, 2.0, 10.0),
        m("claude-fable-5-1", "Claude Fable 5.1", 1_000_000, 128_000, 10.0, 50.0),
        m("claude-opus-5", "Claude Opus 5", 1_000_000, 128_000, 5.0, 25.0),
        m("claude-sonnet-5", "Claude Sonnet 5", 1_000_000, 128_000, 2.0, 10.0),
        // Prompts over 100K input tokens cost $0.50 / $2.50.
        m("claude-haiku-5-5", "Claude Haiku 5.5", 1_000_000, 64_000, 0.10, 0.50)
            .copy(longContextThresholdTokens = 100_000, longInputUsdPerMTok = 0.50, longOutputUsdPerMTok = 2.50),
        m("claude-opus-4-8", "Claude Opus 4.8", 1_000_000, 128_000, 5.0, 25.0),
        m("claude-sonnet-4-6", "Claude Sonnet 4.6", 1_000_000, 64_000, 3.0, 15.0),
        m("claude-haiku-4-5", "Claude Haiku 4.5", 200_000, 64_000, 1.0, 5.0),
    )
    private val byId = catalogue.associateBy { it.id }

    private fun m(id: String, name: String, ctx: Int, out: Int, i: Double, o: Double) =
        ModelSpec(id, name, CLAUDE_CAPS, ctx, out, i, o)

    override fun spec(modelId: String): ModelSpec = byId[modelId] ?: ModelSpec(
        modelId, displayName(modelId), CLAUDE_CAPS,
        contextTokens = if (isCurrentModel(modelId)) 1_000_000 else 200_000,
        maxOutputTokens = 32_000,
    )

    override fun preferredIds(capability: AiCapability): List<String> = when (capability) {
        AiCapability.TEXT, AiCapability.VISION -> listOf("claude-opus-5-5", "claude-sonnet-5-5", "claude-opus-5", "claude-sonnet-5", "claude-opus-4-8", "claude-sonnet-4-6", "claude-haiku-5-5", "claude-haiku-4-5")
        else -> emptyList()
    }

    override fun isListed(spec: ModelSpec, all: Collection<String>): Boolean = true

    private fun headers(b: Request.Builder, apiKey: String) = b
        .header("x-api-key", apiKey)
        .header("anthropic-version", API_VERSION)

    override fun modelsRequest(baseUrl: String, apiKey: String): Request =
        headers(Request.Builder().url("$baseUrl/models?limit=1000").get(), apiKey)
            .header("Accept", "application/json")
            .build()

    override fun parseModels(body: String): List<ModelSpec> {
        val data = parseObject(body)?.get("data").arr() ?: return emptyList()
        return data.mapNotNull { it.obj() }.mapNotNull { o ->
            val id = o["id"].str() ?: return@mapNotNull null
            val base = spec(id)
            base.copy(
                displayName = o["display_name"].str()?.takeIf { it.isNotBlank() } ?: base.displayName,
                contextTokens = o["max_input_tokens"].int()?.takeIf { it > 0 } ?: base.contextTokens,
                maxOutputTokens = o["max_tokens"].int()?.takeIf { it > 0 } ?: base.maxOutputTokens,
                // Every Claude model reads text and images.
                capabilities = CLAUDE_CAPS,
                live = true,
            )
        }
    }

    /** Chat-like tasks get 16000 output tokens; deliberately short requests (naming) stay small. */
    override fun outputBudget(model: ModelSpec, requested: Int): Int {
        val wanted = if (requested < 2048) maxOf(requested, 256) else maxOf(requested, 16_000)
        return wanted.coerceAtMost(model.maxOutputTokens.takeIf { it > 0 } ?: wanted)
    }

    override fun chatBody(call: ChatCall): JsonObject {
        val (system, turns) = splitSystem(call.system, call.messages)
        val messages = normalizeTurns(turns)
        val modelId = call.model.id
        return buildJsonObject {
            put("model", modelId)
            put("max_tokens", call.maxOutputTokens)
            if (system != null) put("system", system)
            putJsonArray("messages") {
                for (msg in messages) addJsonObject {
                    put("role", if (msg.role == AiRole.ASSISTANT) "assistant" else "user")
                    putJsonArray("content") {
                        for (p in msg.parts) when (p) {
                            is AiPart.Text -> if (p.text.isNotBlank()) addJsonObject { put("type", "text"); put("text", p.text) }
                            is AiPart.Image -> if (msg.role == AiRole.USER) addJsonObject {
                                put("type", "image")
                                putJsonObject("source") {
                                    put("type", "base64")
                                    put("media_type", p.mimeType)
                                    put("data", base64(p.bytes))
                                }
                            }
                        }
                    }
                }
            }
            put("stream", true)
            if (isCurrentModel(modelId)) {
                putJsonObject("output_config") { put("effort", if (call.task.effort() == Effort.LOW) "low" else "medium") }
            }
            if (usesFallbacks(call)) put("fallbacks", "default")
        }
    }

    fun usesFallbacks(call: ChatCall): Boolean = call.officialEndpoint && call.model.id in FALLBACK_MODELS

    override fun mayServeOtherModel(call: ChatCall): Boolean = usesFallbacks(call)

    override fun chatRequest(call: ChatCall): Request {
        val bytes = LenientJson.encodeToString(JsonObject.serializer(), chatBody(call)).encodeToByteArray()
        return headers(Request.Builder().url("${call.baseUrl}/messages"), call.apiKey)
            .header("content-type", "application/json")
            .header("Accept", "text/event-stream")
            .apply { if (usesFallbacks(call)) header("anthropic-beta", FALLBACK_BETA) }
            .post(bytes.toRequestBody(PLAIN_JSON))
            .build()
    }

    override fun newDecoder(): StreamDecoder = AnthropicStreamDecoder()

    /**
     * A model outside the catalogue (typed into routing) gets the default output budget, which
     * older models reject with "max_tokens: 16000 > 8192, which is the maximum allowed ...".
     * Retry once with the limit the API named.
     */
    override fun variantAfterError(call: ChatCall, status: Int, body: String): ChatCall? {
        if (status != 400 || call.variant and VARIANT_MAX_TOKENS_CLAMPED != 0) return null
        val message = ErrorMapper.providerMessage(body) ?: body.take(2000)
        val m = MAX_TOKENS_LIMIT.find(message) ?: return null
        val limit = m.groupValues[1].toIntOrNull()?.takeIf { it in 1 until call.maxOutputTokens } ?: return null
        return call.copy(maxOutputTokens = limit, variant = call.variant or VARIANT_MAX_TOKENS_CLAMPED)
    }

    companion object {
        const val API_VERSION = "2023-06-01"
        const val FALLBACK_BETA = "server-side-fallback-2026-07-01"
        const val VARIANT_MAX_TOKENS_CLAMPED = 1
        private val MAX_TOKENS_LIMIT = Regex("""max_tokens:?\s*\d+\s*>\s*(\d+)""")
        private val PLAIN_JSON = "application/json".toMediaType()
        private val CLAUDE_CAPS = setOf(AiCapability.TEXT, AiCapability.VISION)

        /** Models that opt into server-side refusal fallbacks on the official endpoint. */
        val FALLBACK_MODELS = setOf("claude-fable-5-1", "claude-opus-5-5", "claude-opus-5", "claude-sonnet-5-5")

        private val CURRENT_PREFIXES = listOf(
            "claude-opus-5", "claude-sonnet-5", "claude-haiku-5", "claude-fable-5",
            "claude-opus-4-6", "claude-opus-4-7", "claude-opus-4-8", "claude-sonnet-4-6",
        )

        /** Current models accept `output_config.effort`; claude-haiku-4-5 and older do not. */
        fun isCurrentModel(id: String): Boolean = CURRENT_PREFIXES.any { id.startsWith(it) }

        fun displayName(id: String): String {
            if (!id.startsWith("claude-")) return id
            val parts = id.removePrefix("claude-").split('-')
            val family = parts.firstOrNull()?.replaceFirstChar { it.uppercase() } ?: return id
            val version = parts.drop(1).takeWhile { it.all(Char::isDigit) && it.length <= 2 }.joinToString(".")
            return if (version.isEmpty()) "Claude $family" else "Claude $family $version"
        }

        /**
         * Messages must alternate and start with a user turn; consecutive same-role turns are
         * merged, empty text dropped, and a trailing assistant turn (prefill) removed.
         */
        fun normalizeTurns(turns: List<AiMessage>): List<AiMessage> {
            val merged = ArrayList<AiMessage>()
            for (t in turns) {
                if (t.role == AiRole.SYSTEM) continue
                val parts = t.parts.filter { (it is AiPart.Text && it.text.isNotBlank()) || (it is AiPart.Image && t.role == AiRole.USER) }
                if (parts.isEmpty()) continue
                val last = merged.lastOrNull()
                if (last != null && last.role == t.role) merged[merged.lastIndex] = AiMessage(t.role, last.parts + parts)
                else merged += AiMessage(t.role, parts)
            }
            while (merged.isNotEmpty() && merged.first().role != AiRole.USER) merged.removeAt(0)
            while (merged.isNotEmpty() && merged.last().role != AiRole.USER) merged.removeAt(merged.lastIndex)
            return merged
        }
    }
}

/**
 * Anthropic SSE: message_start, content_block_start/delta/stop, message_delta, message_stop,
 * ping, error. Only `text_delta`s of text blocks become answer text; thinking, signatures,
 * fallback markers and other block types are ignored.
 */
class AnthropicStreamDecoder : StreamDecoder {
    private val blockTypes = HashMap<Int, String>()
    private var inputTokens: Int? = null

    override fun decode(event: SseEvent): List<StreamPart> {
        val json = parseObject(event.data) ?: return emptyList()
        return when (json["type"].str() ?: event.event) {
            "message_start" -> {
                val message = json["message"].obj()
                // With server-side fallbacks this names the model that serves the response.
                val served = message?.get("model").str()
                val u = message?.get("usage").obj()
                    ?: return served?.let { listOf(StreamPart.Usage(null, null, model = it)) } ?: emptyList()
                inputTokens = sumInput(u)
                listOf(StreamPart.Usage(inputTokens, null, model = served, iterations = iterations(u)))
            }
            "content_block_start" -> {
                val idx = json["index"].int() ?: 0
                val block = json["content_block"].obj()
                val type = block?.get("type").str() ?: "unknown"
                blockTypes[idx] = type
                val initial = if (type == "text") block?.get("text").str() else null
                if (!initial.isNullOrEmpty()) listOf(StreamPart.Text(initial)) else emptyList()
            }
            "content_block_delta" -> {
                val idx = json["index"].int() ?: 0
                val delta = json["delta"].obj() ?: return emptyList()
                val blockType = blockTypes[idx]
                if (delta["type"].str() == "text_delta" && (blockType == null || blockType == "text")) {
                    delta["text"].str()?.takeIf { it.isNotEmpty() }?.let { listOf(StreamPart.Text(it)) } ?: emptyList()
                } else emptyList()
            }
            "message_delta" -> buildList {
                json["delta"].obj()?.get("stop_reason").str()?.let { r ->
                    add(StreamPart.Stop(mapStop(r), r))
                }
                json["usage"].obj()?.let { u ->
                    val input = sumInput(u)?.takeIf { it > 0 } ?: inputTokens
                    add(StreamPart.Usage(input, u["output_tokens"].int(), iterations = iterations(u)))
                }
            }
            "message_stop" -> listOf(StreamPart.End)
            // Non-streamed response (a gateway that ignored `stream: true`).
            "message" -> buildList {
                json["usage"].obj()?.let { u ->
                    add(StreamPart.Usage(sumInput(u), u["output_tokens"].int(), model = json["model"].str(), iterations = iterations(u)))
                }
                json["content"].arr()?.mapNotNull { it.obj() }?.filter { it["type"].str() == "text" }?.forEach { b ->
                    b["text"].str()?.takeIf { it.isNotEmpty() }?.let { add(StreamPart.Text(it)) }
                }
                add(StreamPart.Stop(json["stop_reason"].str()?.let(::mapStop) ?: StopReason.END, json["stop_reason"].str()))
                add(StreamPart.End)
            }
            "error" -> {
                val err = json["error"].obj()
                listOf(StreamPart.Failure(ErrorMapper.streamError(AiProviderId.ANTHROPIC, err?.get("type").str(), err?.get("message").str())))
            }
            else -> emptyList() // ping, content_block_stop, unknown future events
        }
    }

    private fun sumInput(u: JsonObject): Int? {
        val base = u["input_tokens"].int() ?: return null
        return base + (u["cache_creation_input_tokens"].int() ?: 0) + (u["cache_read_input_tokens"].int() ?: 0)
    }

    /** `usage.iterations`: one entry per attempt; top-level usage covers only the attempt that answered. */
    private fun iterations(u: JsonObject): List<UsageIteration>? =
        u["iterations"].arr()?.mapNotNull { it.obj() }?.map { i ->
            UsageIteration(i["type"].str(), i["model"].str(), sumInput(i) ?: 0, i["output_tokens"].int() ?: 0)
        }?.takeIf { it.isNotEmpty() }

    companion object {
        fun mapStop(r: String): StopReason = when (r) {
            "end_turn", "stop_sequence", "tool_use", "pause_turn" -> StopReason.END
            "max_tokens", "model_context_window_exceeded" -> StopReason.MAX_TOKENS
            "refusal" -> StopReason.REFUSAL
            else -> StopReason.OTHER
        }
    }
}
