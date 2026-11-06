package com.inkwell.notes.ai.engine

import com.inkwell.notes.ai.provider.ModelSpec
import com.inkwell.notes.core.model.AiMessage
import com.inkwell.notes.core.model.AiPart
import java.time.Instant
import java.time.ZoneId
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Rough, provider-neutral token estimates (about 4 characters per token). */
object TokenEstimator {
    private const val PER_MESSAGE_OVERHEAD = 4
    private const val DEFAULT_IMAGE_TOKENS = 1_200
    private const val MAX_IMAGE_TOKENS = 1_600

    fun text(s: String?): Int = if (s.isNullOrEmpty()) 0 else ceil(s.length / 4.0).toInt()

    fun image(img: AiPart.Image): Int {
        val dims = imageSize(img.bytes) ?: return DEFAULT_IMAGE_TOKENS
        var (w, h) = dims
        // Providers downscale large images to about 1568 px on the long edge.
        val long = max(w, h)
        if (long > 1568) {
            val s = 1568.0 / long
            w = (w * s).roundToInt()
            h = (h * s).roundToInt()
        }
        return min(MAX_IMAGE_TOKENS, max(85, (w.toLong() * h / 750).toInt()))
    }

    fun messages(system: String?, messages: List<AiMessage>): Int =
        text(system) + messages.sumOf { m ->
            PER_MESSAGE_OVERHEAD + m.parts.sumOf { p ->
                when (p) {
                    is AiPart.Text -> text(p.text)
                    is AiPart.Image -> image(p)
                }
            }
        }

    /** Width/height from a PNG IHDR or JPEG SOF header, or null. */
    fun imageSize(b: ByteArray): Pair<Int, Int>? {
        if (b.size >= 24 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte() && b[3] == 'G'.code.toByte()) {
            val w = be32(b, 16)
            val h = be32(b, 20)
            return if (w > 0 && h > 0) w to h else null
        }
        if (b.size >= 4 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte()) {
            var i = 2
            while (i + 9 < b.size) {
                if (b[i] != 0xFF.toByte()) { i++; continue }
                val marker = b[i + 1].toInt() and 0xFF
                if (marker == 0xD8 || marker == 0x01 || marker in 0xD0..0xD7) { i += 2; continue }
                val len = ((b[i + 2].toInt() and 0xFF) shl 8) or (b[i + 3].toInt() and 0xFF)
                if (marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
                    val h = ((b[i + 5].toInt() and 0xFF) shl 8) or (b[i + 6].toInt() and 0xFF)
                    val w = ((b[i + 7].toInt() and 0xFF) shl 8) or (b[i + 8].toInt() and 0xFF)
                    return if (w > 0 && h > 0) w to h else null
                }
                i += 2 + len
            }
        }
        return null
    }

    private fun be32(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)
}

/** Estimated cost from public list prices. Always an estimate, never the provider's bill. */
object CostCalculator {
    /** Null when the model has no known price for a component that was used. */
    fun tokens(spec: ModelSpec, inputTokens: Int, outputTokens: Int): Double? {
        val long = spec.longContextThresholdTokens?.let { inputTokens > it } == true
        val inPrice = if (long) spec.longInputUsdPerMTok ?: spec.inputUsdPerMTok else spec.inputUsdPerMTok
        val outPrice = if (long) spec.longOutputUsdPerMTok ?: spec.outputUsdPerMTok else spec.outputUsdPerMTok
        if (inPrice == null && outPrice == null) return null
        if (inputTokens > 0 && inPrice == null) return null
        if (outputTokens > 0 && outPrice == null) return null
        return inputTokens * (inPrice ?: 0.0) / 1_000_000.0 + outputTokens * (outPrice ?: 0.0) / 1_000_000.0
    }

    fun audio(spec: ModelSpec, durationMs: Long): Double? =
        spec.usdPerAudioMinute?.let { it * (durationMs.coerceAtLeast(0) / 60_000.0) }
}

/** Calendar helpers for monthly limits (device time zone). */
object MonthWindow {
    fun startOfMonth(nowMs: Long, zone: ZoneId): Long {
        val d = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate().withDayOfMonth(1)
        return d.atStartOfDay(zone).toInstant().toEpochMilli()
    }
}

/** Display formatting for estimates. */
object CostFormat {
    fun usd(v: Double?): String = when {
        v == null -> "price unknown"
        v == 0.0 -> "$0.00"
        v < 0.0001 -> "< $0.0001"
        v < 0.01 -> "$" + String.format(java.util.Locale.US, "%.4f", v)
        v < 1.0 -> "$" + String.format(java.util.Locale.US, "%.3f", v)
        else -> "$" + String.format(java.util.Locale.US, "%,.2f", v)
    }

    fun tokens(n: Long): String = String.format(java.util.Locale.US, "%,d", n)
    fun tokens(n: Int): String = tokens(n.toLong())

    fun pricePerM(v: Double?): String = if (v == null) "?" else "$" + trimZeros(v)

    fun trimZeros(v: Double): String {
        val s = String.format(java.util.Locale.US, "%.4f", v).trimEnd('0')
        return if (s.endsWith('.')) s.dropLast(1) else s
    }
}
