package com.inkwell.notes.ai.engine

import com.inkwell.notes.ai.net.LenientJson
import com.inkwell.notes.ai.provider.ModelSpec
import com.inkwell.notes.ai.provider.ProviderRegistry
import com.inkwell.notes.core.model.AiModelInfo
import com.inkwell.notes.core.model.AiProviderId
import com.inkwell.notes.core.model.ModelRef
import kotlinx.serialization.Serializable
import java.util.concurrent.ConcurrentHashMap

/** Minimal string store (SharedPreferences on device, a map in tests). */
interface KeyValueStore {
    fun get(key: String): String?
    fun put(key: String, value: String?)
    fun keys(): Set<String>
}

class MemoryStore : KeyValueStore {
    private val map = ConcurrentHashMap<String, String>()
    override fun get(key: String): String? = map[key]
    override fun put(key: String, value: String?) { if (value == null) map.remove(key) else map[key] = value }
    override fun keys(): Set<String> = map.keys.toSet()
}

/** A user-entered price (USD per million tokens) that replaces the built-in estimate. */
@Serializable
data class PriceOverride(val inputUsdPerMTok: Double?, val outputUsdPerMTok: Double?)

/**
 * Model facts: the static catalogue, the providers' live model lists (cached on disk so
 * pickers work offline) and per-model price overrides.
 */
class ModelRegistry(
    private val providers: ProviderRegistry,
    private val cacheStore: KeyValueStore,
    private val priceStore: KeyValueStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    @Serializable
    private data class LiveList(val fetchedAt: Long, val models: List<ModelSpec>)

    private val live = ConcurrentHashMap<AiProviderId, LiveList>()
    private val loaded = ConcurrentHashMap<AiProviderId, Boolean>()
    private val prices = ConcurrentHashMap<String, PriceOverride>()
    @Volatile private var pricesLoaded = false

    private fun liveList(p: AiProviderId): LiveList? {
        if (loaded.putIfAbsent(p, true) == null) {
            cacheStore.get(cacheKey(p))?.let { json ->
                runCatching { LenientJson.decodeFromString(LiveList.serializer(), json) }.getOrNull()?.let { live[p] = it }
            }
        }
        return live[p]
    }

    fun liveFetchedAt(p: AiProviderId): Long? = liveList(p)?.fetchedAt

    fun liveIds(p: AiProviderId): List<String> = liveList(p)?.models?.map { it.id }.orEmpty()

    fun storeLive(p: AiProviderId, models: List<ModelSpec>) {
        val list = LiveList(clock(), models)
        loaded[p] = true
        live[p] = list
        cacheStore.put(cacheKey(p), LenientJson.encodeToString(LiveList.serializer(), list))
    }

    fun clearLive(p: AiProviderId) {
        loaded[p] = true
        live.remove(p)
        cacheStore.put(cacheKey(p), null)
    }

    /** Known models for [p]: catalogue (in preference order, with live metadata) plus live-only listed models. */
    fun known(p: AiProviderId): List<ModelSpec> {
        val adapter = providers[p]
        val liveModels = liveList(p)?.models.orEmpty()
        val liveById = liveModels.associateBy { it.id }
        val allIds = liveModels.map { it.id }.toSet() + adapter.catalogue.map { it.id }
        val fromCatalogue = adapter.catalogue.map { cat ->
            liveById[cat.id]?.let { l ->
                // Live lists carry names/limits; prices and capabilities come from the catalogue.
                cat.copy(displayName = l.displayName.takeIf { it != l.id } ?: cat.displayName, contextTokens = l.contextTokens.takeIf { it > 0 } ?: cat.contextTokens, live = true)
            } ?: cat
        }
        val catalogueIds = adapter.catalogue.map { it.id }.toSet()
        val liveOnly = liveModels.filter { it.id !in catalogueIds && adapter.isListed(it, allIds) }.sortedBy { it.id }
        return (fromCatalogue + liveOnly).map { withOverride(p, it) }
    }

    fun spec(ref: ModelRef): ModelSpec {
        val adapter = providers[ref.provider]
        val cat = adapter.catalogue.firstOrNull { it.id == ref.modelId }
        val base = cat ?: liveList(ref.provider)?.models?.firstOrNull { it.id == ref.modelId } ?: adapter.spec(ref.modelId)
        return withOverride(ref.provider, base)
    }

    fun info(ref: ModelRef): AiModelInfo = spec(ref).toInfo(ref.provider)

    // ---- Price overrides

    private fun ensurePrices() {
        if (pricesLoaded) return
        synchronized(this) {
            if (pricesLoaded) return
            for (k in priceStore.keys()) {
                priceStore.get(k)?.let { json -> runCatching { LenientJson.decodeFromString(PriceOverride.serializer(), json) }.getOrNull()?.let { prices[k] = it } }
            }
            pricesLoaded = true
        }
    }

    fun priceOverride(ref: ModelRef): PriceOverride? {
        ensurePrices()
        return prices[ref.toString()]
    }

    fun setPriceOverride(ref: ModelRef, override: PriceOverride?) {
        ensurePrices()
        val k = ref.toString()
        if (override == null || (override.inputUsdPerMTok == null && override.outputUsdPerMTok == null)) {
            prices.remove(k)
            priceStore.put(k, null)
        } else {
            prices[k] = override
            priceStore.put(k, LenientJson.encodeToString(PriceOverride.serializer(), override))
        }
    }

    private fun withOverride(p: AiProviderId, s: ModelSpec): ModelSpec {
        val o = priceOverride(ModelRef(p, s.id)) ?: return s
        // The user's prices replace the list prices, including any long-context tier.
        return s.copy(
            inputUsdPerMTok = o.inputUsdPerMTok ?: s.inputUsdPerMTok,
            outputUsdPerMTok = o.outputUsdPerMTok ?: s.outputUsdPerMTok,
            longContextThresholdTokens = null,
            longInputUsdPerMTok = null,
            longOutputUsdPerMTok = null,
        )
    }

    private fun cacheKey(p: AiProviderId) = "models.${p.name}"

    companion object {
        /** Live lists older than this are refreshed when a key is available. */
        const val STALE_MS = 24L * 60 * 60 * 1000
    }
}
