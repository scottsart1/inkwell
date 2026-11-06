package com.inkwell.notes.speakers.logic

import com.inkwell.notes.core.model.SpeakerTurn
import com.inkwell.notes.speakers.DetectedSpeaker
import com.inkwell.notes.speakers.DiarizationResult
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One diarized stretch of a window, in seconds from the start of the window's audio. */
data class RawSegment(val startSec: Float, val endSec: Float, val speaker: Int)

/**
 * The on-device speaker models (segmentation + embedding). Implemented over sherpa-onnx in
 * the app and by fakes in tests, so the pipeline below never touches native code there.
 */
interface VoiceEngine : AutoCloseable {
    /** Embedding model id recorded with every voiceprint ("wespeaker_resnet34_lm"). */
    val modelId: String

    /** Who spoke when in [samples] (16 kHz mono, -1..1). [progress] receives 0..1. */
    fun diarize(samples: FloatArray, progress: (Float) -> Unit = {}): List<RawSegment>

    /** L2-normalised embedding of [samples] (16 kHz mono), or null when there is too little audio. */
    fun embed(samples: FloatArray): FloatArray?

    override fun close()
}

/** A turn of a window-local voice, on the recording timeline. */
@Serializable
data class LocalTurn(val voice: String, val startMs: Long, val endMs: Long)

/** A voice found in one window. Its embedding lives only in the encrypted voice store. */
@Serializable
data class LocalVoiceInfo(val id: String, val speechMs: Long, val firstStartMs: Long, val hasEmbedding: Boolean)

data class WindowResult(
    val turns: List<LocalTurn>,
    val voices: List<LocalVoiceInfo>,
    /** Voice id to L2-normalised embedding. */
    val embeddings: Map<String, FloatArray>,
)

/**
 * Progress of an on-device identification, saved after every window so a stopped job resumes.
 * Holds no voice data: window embeddings are kept in the encrypted voice store.
 */
@Serializable
data class DiarizationCheckpoint(
    val version: Int = VERSION,
    val modelId: String,
    /** The segment files (and durations) this belongs to; any change invalidates it. */
    val files: List<String>,
    val durations: List<Long>,
    val windowsDone: Int = 0,
    val turns: List<LocalTurn> = emptyList(),
    val voices: List<LocalVoiceInfo> = emptyList(),
    /** Window length of the plan [windowsDone] counts in; another plan means other windows. */
    val windowMs: Long = AudioPlan.MAX_WINDOW_MS,
    /** Runs that failed (an exception, not a stop) since the last window was analysed. */
    val failures: Int = 0,
) {
    fun matches(modelId: String, files: List<String>, durations: List<Long>, windows: Int, windowMs: Long = AudioPlan.MAX_WINDOW_MS): Boolean =
        version == VERSION && this.windowMs == windowMs && this.modelId == modelId && this.files == files &&
            this.durations == durations && windowsDone in 0..windows

    /** True when [embeddings] holds every embedding the voices claim to have. */
    fun consistentWith(embeddings: Set<String>): Boolean = voices.filter { it.hasEmbedding }.all { it.id in embeddings }

    companion object {
        /** 2: windows of [AudioPlan.MAX_WINDOW_MS] (5 min) instead of 10, and [windowMs] recorded. */
        const val VERSION = 2
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun encode(c: DiarizationCheckpoint): String = json.encodeToString(serializer(), c)

        fun decode(text: String): DiarizationCheckpoint? = runCatching { json.decodeFromString(serializer(), text) }.getOrNull()
    }
}

/** Analyses one window: diarize, then one embedding per window-local voice. Pure over [VoiceEngine]. */
object WindowAnalyzer {
    /** Turns shorter than this are too short for a reliable embedding. */
    const val MIN_EMBED_TURN_MS = 1_000L

    /** Fallback for voices with only short turns: their speech pooled, if at least this long. */
    const val MIN_POOLED_MS = 500L

    /** At most this much of a voice's speech is embedded per window (longest turns first). */
    const val MAX_EMBED_MS = 60_000L

    /** Long turns are embedded in chunks of at most this length. */
    const val MAX_CHUNK_MS = 15_000L

    fun analyze(
        windowIndex: Int,
        globalStartMs: Long,
        samples: FloatArray,
        engine: VoiceEngine,
        sampleRate: Int = 16_000,
        progress: (Float) -> Unit = {},
        checkCancelled: () -> Unit = {},
    ): WindowResult {
        if (samples.size < sampleRate / 2) return WindowResult(emptyList(), emptyList(), emptyMap())
        val raw = engine.diarize(samples) { progress(0.8f * it.coerceIn(0f, 1f)) }
        checkCancelled()
        val windowMs = Pcm.msOf(samples.size, sampleRate)
        data class T(val speaker: Int, val startMs: Long, val endMs: Long)
        val local = raw.mapNotNull { r ->
            if (!r.startSec.isFinite() || !r.endSec.isFinite()) return@mapNotNull null
            val a = (r.startSec * 1000).toLong().coerceIn(0, windowMs)
            val b = (r.endSec * 1000).toLong().coerceIn(0, windowMs)
            if (b > a) T(r.speaker, a, b) else null
        }.sortedBy { it.startMs }
        val bySpeaker = local.groupBy { it.speaker }
        val voices = ArrayList<LocalVoiceInfo>()
        val embeddings = HashMap<String, FloatArray>()
        val turns = ArrayList<LocalTurn>()
        var done = 0
        for ((speaker, ts) in bySpeaker.entries.sortedBy { e -> e.value.minOf { it.startMs } }) {
            val id = "w$windowIndex:$speaker"
            val speech = ts.sumOf { it.endMs - it.startMs }
            val emb = embedVoice(samples, sampleRate, ts.map { it.startMs to it.endMs }, engine, checkCancelled)
            if (emb != null) embeddings[id] = emb
            voices += LocalVoiceInfo(id, speech, globalStartMs + ts.minOf { it.startMs }, emb != null)
            ts.forEach { turns += LocalTurn(id, globalStartMs + it.startMs, globalStartMs + it.endMs) }
            done++
            progress(0.8f + 0.2f * done / bySpeaker.size)
        }
        return WindowResult(turns.sortedBy { it.startMs }, voices, embeddings)
    }

    /** Speech-weighted embedding of one voice from its turns (window-relative ms). */
    fun embedVoice(
        samples: FloatArray,
        sampleRate: Int,
        turns: List<Pair<Long, Long>>,
        engine: VoiceEngine,
        checkCancelled: () -> Unit = {},
    ): FloatArray? {
        val vectors = ArrayList<FloatArray>()
        val weights = ArrayList<Double>()
        var budget = MAX_EMBED_MS
        for ((a, b) in turns.filter { it.second - it.first >= MIN_EMBED_TURN_MS }.sortedByDescending { it.second - it.first }) {
            if (budget <= 0) break
            val end = minOf(b, a + budget)
            for ((ca, cb) in chunks(a, end)) {
                checkCancelled()
                val e = engine.embed(slice(samples, sampleRate, ca, cb)) ?: continue
                vectors += e
                weights += (cb - ca).toDouble()
            }
            budget -= end - a
        }
        if (vectors.isEmpty()) {
            // Only short turns: pool them.
            val total = turns.sumOf { it.second - it.first }
            if (total < MIN_POOLED_MS) return null
            val pooled = Pcm.concat(turns.map { (a, b) -> slice(samples, sampleRate, a, b) })
            checkCancelled()
            return engine.embed(pooled)?.let { VectorMath.normalize(it) }?.takeIf { VectorMath.norm(it) > 0f }
        }
        return VectorMath.weightedMean(vectors, weights)
    }

    /** [a, b) cut into chunks of at most [MAX_CHUNK_MS]; a short tail joins the previous chunk. */
    fun chunks(a: Long, b: Long): List<Pair<Long, Long>> {
        val out = ArrayList<Pair<Long, Long>>()
        var s = a
        while (s < b) {
            var e = minOf(b, s + MAX_CHUNK_MS)
            if (b - e < MIN_EMBED_TURN_MS) e = b
            out += s to e
            s = e
        }
        return out
    }

    private fun slice(samples: FloatArray, rate: Int, fromMs: Long, toMs: Long): FloatArray {
        val a = Pcm.samplesFor(fromMs, rate).coerceIn(0, samples.size)
        val b = Pcm.samplesFor(toMs, rate).coerceIn(a, samples.size)
        return samples.copyOfRange(a, b)
    }
}

/** Turns the windows' voices into the recording's voices and turns. Pure. */
object DiarizationAssembler {
    fun assemble(
        recordingId: String,
        engineId: String,
        turns: List<LocalTurn>,
        voices: List<LocalVoiceInfo>,
        embeddings: Map<String, FloatArray>,
        totalMs: Long,
        expectedSpeakers: Int = 0,
        linkThreshold: Float = VoiceScores.LINK_THRESHOLD,
    ): DiarizationResult {
        val link = ClusterLinker.link(
            voices.map { ClusterLinker.LocalVoice(it.id, it.speechMs, embeddings[it.id], it.firstStartMs) },
            linkThreshold,
            expectedSpeakers,
        )
        // Voices without an embedding (under half a second of speech) are left out.
        val global = turns.mapNotNull { t -> link.assignment[t.voice]?.let { SpeakerTurn("G$it", t.startMs, t.endMs) } }
        val post = TurnPostProcessor.process(global)
        val speech = TurnPostProcessor.speechByLabel(post.turns)
        val speakers = post.labels.entries.sortedBy { SpeakerNames.number(it.value) }.mapNotNull { (g, label) ->
            val voice = link.voices.getOrNull(g.removePrefix("G").toIntOrNull() ?: -1) ?: return@mapNotNull null
            val window = SampleWindows.pick(post.turns, label, totalMs) ?: return@mapNotNull null
            DetectedSpeaker(
                label = label,
                personId = null,
                confidence = null,
                centroid = voice.centroid,
                speechMs = speech[label] ?: 0L,
                sampleStartMs = window.startMs,
                sampleEndMs = window.endMs,
            )
        }
        return DiarizationResult(recordingId, engineId, post.turns, speakers)
    }

    /** Pieces done for progress: whole windows done plus the running window's share. */
    fun progress(windows: List<AnalysisWindow>, windowsDone: Int, runningFraction: Float): Pair<Int, Int> {
        val total = windows.sumOf { it.pieces.size }
        val before = windows.take(windowsDone.coerceIn(0, windows.size)).sumOf { it.pieces.size }
        val current = windows.getOrNull(windowsDone)?.pieces?.size ?: 0
        val done = before + (current * runningFraction.coerceIn(0f, 1f)).toInt()
        return done.coerceAtMost(total) to total
    }
}
