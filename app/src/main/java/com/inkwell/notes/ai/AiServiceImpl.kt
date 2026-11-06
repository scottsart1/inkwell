package com.inkwell.notes.ai

import android.content.Context
import android.graphics.Bitmap
import androidx.core.graphics.scale
import com.inkwell.notes.ai.chat.ChatStore
import com.inkwell.notes.ai.context.ContextAssembler
import com.inkwell.notes.ai.engine.AiEngine
import com.inkwell.notes.ai.engine.EngineEvent
import com.inkwell.notes.ai.engine.KeyProvider
import com.inkwell.notes.ai.engine.KeyValueStore
import com.inkwell.notes.ai.engine.ModelRegistry
import com.inkwell.notes.ai.engine.PriceOverride
import com.inkwell.notes.ai.engine.UsageSink
import com.inkwell.notes.ai.engine.providerSettings
import com.inkwell.notes.ai.engine.updateProvider
import com.inkwell.notes.ai.net.Endpoints
import com.inkwell.notes.ai.provider.OpenAiModels
import com.inkwell.notes.ai.provider.ProviderRegistry
import com.inkwell.notes.app.AppGraph
import com.inkwell.notes.core.model.AiCapability
import com.inkwell.notes.core.model.AiEvent
import com.inkwell.notes.core.model.AiException
import com.inkwell.notes.core.model.AiModelInfo
import com.inkwell.notes.core.model.AiProviderId
import com.inkwell.notes.core.model.AiRequest
import com.inkwell.notes.core.model.AiResult
import com.inkwell.notes.core.model.AiTask
import com.inkwell.notes.core.model.ModelRef
import com.inkwell.notes.core.model.SpeakerEngine
import com.inkwell.notes.core.model.Transcript
import com.inkwell.notes.core.settings.SecretStore
import com.inkwell.notes.core.storage.db.AiUsageEntity
import com.inkwell.notes.speakers.SpeakerReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/** Private SharedPreferences file owned by the AI feature (model cache, price overrides, key state). */
internal class SharedPrefsStore(context: Context, name: String) : KeyValueStore {
    private val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    override fun get(key: String): String? = prefs.getString(key, null)
    override fun put(key: String, value: String?) {
        prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
    }
    override fun keys(): Set<String> = prefs.all.keys.toSet()
}

/**
 * Bring-your-own-key AI on top of [AiEngine]. Keys come from [SecretStore], settings from
 * [com.inkwell.notes.core.settings.SettingsRepository], usage goes to Room. Network runs on
 * Dispatchers.IO; no key or note content is ever logged.
 */
class AiServiceImpl(private val graph: AppGraph) : AiService {
    private val modelCache: KeyValueStore by lazy { SharedPrefsStore(graph.context, "inkwell_ai_models") }
    private val priceStore: KeyValueStore by lazy { SharedPrefsStore(graph.context, "inkwell_ai_prices") }
    private val stateStore: KeyValueStore by lazy { SharedPrefsStore(graph.context, "inkwell_ai_state") }

    val registry: ProviderRegistry = ProviderRegistry.default()

    val engine: AiEngine by lazy {
        AiEngine(
            http = graph.http,
            prefs = { graph.settings.current.ai },
            keys = object : KeyProvider {
                override fun has(provider: AiProviderId) = graph.secrets.has(SecretStore.aiKey(provider))
                override fun get(provider: AiProviderId) = graph.secrets.get(SecretStore.aiKey(provider))
            },
            usage = object : UsageSink {
                override suspend fun record(row: AiUsageEntity) = graph.db.aiUsage().insert(row)
                override suspend fun costSince(sinceMs: Long) = graph.db.aiUsage().costSince(sinceMs)
            },
            models = ModelRegistry(registry, modelCache, priceStore),
            providers = registry,
        )
    }

    /** Persisted assistant threads. */
    val chats: ChatStore by lazy { ChatStore({ graph.db.chat() }) }

    /** Document context for the assistant panel (retrieval over the local index). */
    val contextAssembler: ContextAssembler by lazy {
        ContextAssembler(graph.search, docTitle = { id -> graph.library.getDocument(id)?.title })
    }

    init {
        // Warm the model cache off the main thread so pickers open instantly.
        graph.appScope.launch(Dispatchers.IO) {
            runCatching { AiProviderId.entries.forEach { engine.listed(it) } }
            // Lets canDiarize() see a reached monthly limit without querying the database.
            runCatching { engine.monthSpendUsd() }
        }
    }

    // ---------------------------------------------------------------- AiService

    override fun isAvailable(task: AiTask): Boolean = engine.isAvailable(task)

    override fun modelFor(task: AiTask): ModelRef? = engine.modelFor(task)

    override suspend fun models(provider: AiProviderId, refresh: Boolean): List<AiModelInfo> = engine.models(provider, refresh)

    override suspend fun modelsWith(capability: AiCapability): List<AiModelInfo> = engine.modelsWith(capability)

    override fun modelInfo(ref: ModelRef): AiModelInfo? = engine.modelInfo(ref)

    override suspend fun validateKey(provider: AiProviderId, apiKey: String, baseUrl: String?): Result<String> {
        val check = engine.checkKey(provider, apiKey, baseUrl)
        val stored = withContext(Dispatchers.IO) { graph.secrets.get(SecretStore.aiKey(provider)) }
        val configuredBase = graph.settings.current.ai.providerSettings(provider).customBaseUrl?.trim().orEmpty()
        val sameEndpoint = baseUrl == null || baseUrl.trim() == configuredBase
        if (stored != null && stored == apiKey.trim() && sameEndpoint) {
            when {
                check.ok -> {
                    setInvalid(provider, null)
                    val now = System.currentTimeMillis()
                    graph.settings.update { s -> s.copy(ai = s.ai.updateProvider(provider) { it.copy(validatedAt = now) }) }
                }
                check.rejected -> {
                    setInvalid(provider, check.message)
                    graph.settings.update { s -> s.copy(ai = s.ai.updateProvider(provider) { it.copy(validatedAt = 0) }) }
                }
                // The provider accepted the key but refused the model list (403). Older builds flagged that as a
                // rejected key; clear that stale flag without marking the key validated. A 403 can also come
                // before the key is checked (region, proxy), so a flag a 401 set stays.
                check.authenticated -> if (isStaleForbiddenFlag(invalidReason(provider))) setInvalid(provider, null)
            }
        }
        return if (check.ok) Result.success(check.message) else Result.failure(AiException(check.message))
    }

    override fun stream(request: AiRequest): Flow<AiEvent> = engine.toAiEvents(engine.streamDetailed(request))

    override suspend fun complete(request: AiRequest): AiResult = engine.complete(request)

    override fun estimateTokens(request: AiRequest): Int = engine.estimateTokens(request)

    override fun estimateCostUsd(request: AiRequest, expectedOutputTokens: Int): Double? = engine.estimateCostUsd(request, expectedOutputTokens)

    override suspend fun transcribe(recordingId: String, segments: List<File>, segmentDurationsMs: List<Long>, language: String?): Transcript? =
        engine.transcribe(recordingId, segments, segmentDurationsMs, language)

    /** OpenAI configured, its stored key not rejected, this month's limit not known to be reached. */
    override fun canDiarize(): Boolean =
        runCatching { engine.canDiarize() && invalidReason(AiProviderId.OPENAI) == null }.getOrDefault(false)

    override suspend fun transcribeDiarized(
        recordingId: String,
        file: File,
        durationMs: Long,
        offsetMs: Long,
        references: List<SpeakerReference>,
        language: String?,
    ): Transcript? = engine.transcribeDiarized(recordingId, file, durationMs, offsetMs, references, language)

    override suspend fun embed(texts: List<String>): List<FloatArray>? = engine.embed(texts)

    override suspend fun visionTranscribe(image: Bitmap, hint: String?): AiResult? {
        if (!engine.isAvailable(AiTask.OCR_ASSIST)) return null
        val png = withContext(Dispatchers.Default) { encodePng(image, MAX_VISION_EDGE) }
        return engine.visionTranscribe(png, "image/png", hint)
    }

    // ---------------------------------------------------------------- panel/settings helpers

    /** Detailed stream (reasoning, retries, stop reason) for the assistant panel. */
    fun streamDetailed(request: AiRequest): Flow<EngineEvent> = engine.streamDetailed(request)

    suspend fun monthSpendUsd(): Double = engine.monthSpendUsd()

    fun hasKey(provider: AiProviderId): Boolean = graph.secrets.has(SecretStore.aiKey(provider))

    /** "••••4f2a" for display, never the key. */
    suspend fun keyHint(provider: AiProviderId): String? = withContext(Dispatchers.IO) { graph.secrets.hint(SecretStore.aiKey(provider)) }

    suspend fun storedKey(provider: AiProviderId): String? = withContext(Dispatchers.IO) { graph.secrets.get(SecretStore.aiKey(provider)) }

    /** Stores a key (Keystore-encrypted) and resets its validation state. */
    suspend fun saveKey(provider: AiProviderId, key: String) {
        val k = sanitizeKey(key)
        require(k.isNotEmpty())
        withContext(Dispatchers.IO) { graph.secrets.put(SecretStore.aiKey(provider), k) }
        setInvalid(provider, null)
        graph.settings.update { s -> s.copy(ai = s.ai.updateProvider(provider) { it.copy(validatedAt = 0) }) }
    }

    suspend fun removeKey(provider: AiProviderId) {
        withContext(Dispatchers.IO) { graph.secrets.remove(SecretStore.aiKey(provider)) }
        setInvalid(provider, null)
        engine.models.clearLive(provider)
        graph.settings.update { s -> s.copy(ai = s.ai.updateProvider(provider) { it.copy(validatedAt = 0) }) }
    }

    /** Saves a custom endpoint (https only); blank restores the official one. */
    suspend fun setCustomBaseUrl(provider: AiProviderId, url: String?): Result<Unit> {
        val v = url?.trim().orEmpty()
        val normalized = if (v.isEmpty()) null else Endpoints.normalizeCustom(v).getOrElse { return Result.failure(it) }
        val current = graph.settings.current.ai.providerSettings(provider).customBaseUrl
        if (current == normalized) return Result.success(Unit)
        engine.models.clearLive(provider)
        graph.settings.update { s -> s.copy(ai = s.ai.updateProvider(provider) { it.copy(customBaseUrl = normalized, validatedAt = 0) }) }
        return Result.success(Unit)
    }

    /** Last rejection message for a stored key (shown as "Invalid"), or null. */
    fun invalidReason(provider: AiProviderId): String? = stateStore.get("invalid.${provider.name}")

    private fun setInvalid(provider: AiProviderId, message: String?) = stateStore.put("invalid.${provider.name}", message)

    // ---------------------------------------------------------------- transcription cost helpers

    /** The model speaker-labelled transcription uses (OpenAI gpt-4o-transcribe-diarize). */
    val diarizeModel: ModelRef get() = ModelRef(AiProviderId.OPENAI, OpenAiModels.DIARIZE_MODEL)

    /** Estimated USD per audio minute for a speech-to-text model, or null when unknown. */
    fun usdPerAudioMinute(ref: ModelRef): Double? = runCatching { engine.models.spec(ref).usdPerAudioMinute }.getOrNull()

    /**
     * The model a transcription queued now would use: [diarizeModel] when speakers are set to
     * be labelled by OpenAI and that is available, otherwise the TRANSCRIPTION route. For the
     * consent prompt and its cost estimate.
     */
    fun transcriptionJobModel(): ModelRef? =
        if (graph.settings.current.speakers.engine == SpeakerEngine.OPENAI && canDiarize()) diarizeModel
        else engine.modelFor(AiTask.TRANSCRIPTION)

    fun priceOverride(ref: ModelRef): PriceOverride? = engine.models.priceOverride(ref)

    fun setPriceOverride(ref: ModelRef, override: PriceOverride?) = engine.models.setPriceOverride(ref, override)

    companion object {
        /** Long edge for vision OCR images: enough for handwriting, within providers' native resolution. */
        const val MAX_VISION_EDGE = 1600

        /**
         * A pasted key with whitespace or invisible format characters (zero-width space, soft
         * hyphen, word joiner: common when copying from mail or web pages) is cleaned, since no
         * provider key contains them and such a value could not be sent as a header anyway.
         */
        fun sanitizeKey(raw: String): String =
            raw.filterNot { it.isWhitespace() || Character.getType(it) == Character.FORMAT.toInt() }

        /**
         * True for an "Invalid" reason older builds stored after a 403 ("refused access"), which
         * did not mean the key was rejected; false for a 401's "rejected the API key" or no flag.
         */
        fun isStaleForbiddenFlag(reason: String?): Boolean =
            reason != null && ("(403)" in reason || "refused access" in reason)

        fun encodePng(src: Bitmap, maxEdge: Int): ByteArray {
            val long = max(src.width, src.height)
            val bmp = if (long > maxEdge) {
                val s = maxEdge.toFloat() / long
                src.scale((src.width * s).roundToInt().coerceAtLeast(1), (src.height * s).roundToInt().coerceAtLeast(1))
            } else src
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            if (bmp !== src) bmp.recycle()
            return out.toByteArray()
        }
    }
}
