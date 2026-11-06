package com.inkwell.notes.speakers.ui.logic

import com.inkwell.notes.audio.logic.TranscriptFilter
import com.inkwell.notes.core.model.RecordingSpeaker
import com.inkwell.notes.core.model.SpeakerTurn
import com.inkwell.notes.core.model.TranscriptSegment
import com.inkwell.notes.speakers.logic.NamingRules
import java.util.Locale

/**
 * Who is speaking in a transcript: the voice of each block, grouping of consecutive blocks,
 * name-aware search, talk-time shares and jumping between one voice's blocks. Pure.
 */
object SpeakerTranscript {

    /** The voice of a transcript block as the panel shows it. */
    data class Voice(
        /** Groups and filters blocks: the recording label, or the stored name for a block without one. */
        val key: String,
        val label: String?,
        /** "Priya", "Speaker 2". */
        val name: String,
        /** Palette index, or null for a name without a voice record (shown without a dot). */
        val colorIndex: Int?,
        /** Recognised automatically and not confirmed yet: shown as "Priya?". */
        val tentative: Boolean,
        /** Voiceprint similarity 0..1 of an automatic match, when known. */
        val confidence: Float?,
    )

    /** One transcript block in the list; [startsGroup] shows the speaker's name above it. */
    data class Row(val match: TranscriptFilter.Match, val voice: Voice?, val startsGroup: Boolean)

    private const val NAME_KEY = "name:"
    private val NUMBERED_LABEL = Regex("^S(\\d+)$")
    /** The generic "Speaker N" a block carries while its voice is unnamed (see SpeakerNames.unnamed). */
    private val GENERIC_NAME = Regex("^Speaker \\d+$")

    fun isTentative(s: RecordingSpeaker): Boolean = s.personId != null && !s.confirmed

    /**
     * The voice as the panel shows it. [storedName] is a real name written into the transcript
     * for a voice that has no person any more (the person was deleted): transcripts, exports,
     * search and Google Drive keep that name, so the panel shows it too instead of "Speaker N".
     */
    fun voice(s: RecordingSpeaker, storedName: String? = null): Voice {
        val stored = storedName?.trim()?.takeIf { s.personId == null && it.isNotEmpty() && !GENERIC_NAME.matches(it) }
        return Voice(s.label, s.label, stored ?: s.displayName.trim().ifEmpty { s.label }, s.colorIndex, isTentative(s), s.confidence)
    }

    /**
     * The voice of [segment]: its recording speaker when the label is known (falling back to the
     * block's stored name while that voice has no person), otherwise the name stored on the block
     * (older transcripts, or a label whose speaker row is gone), else null.
     */
    fun voiceOf(segment: TranscriptSegment, byLabel: Map<String, RecordingSpeaker>): Voice? {
        val label = segment.speakerLabel?.trim()?.takeIf { it.isNotEmpty() }
        label?.let { byLabel[it] }?.let { return voice(it, segment.speaker) }
        val name = segment.speaker?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return Voice(label ?: NAME_KEY + name.lowercase(Locale.ROOT), label, name, null, tentative = false, confidence = null)
    }

    /**
     * Label to the real name the transcript still holds for voices without a person (deleted
     * people), so chips and the filter bar say what the blocks say. Voices with a person, and
     * blocks carrying only the generic "Speaker N", are not included.
     */
    fun storedNames(segments: List<TranscriptSegment>, byLabel: Map<String, RecordingSpeaker>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (s in segments) {
            val label = s.speakerLabel?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            if (label in out) continue
            val row = byLabel[label] ?: continue
            if (row.personId != null) continue
            val name = s.speaker?.trim()?.takeIf { it.isNotEmpty() && !GENERIC_NAME.matches(it) } ?: continue
            out[label] = name
        }
        return out
    }

    /**
     * The rows to show: blocks containing every term of [query] in their text or their speaker's
     * name (case-insensitive) and, when [onlyKey] is set, spoken by that voice. Highlights cover
     * the text. A block starts a group when the block before it in the transcript is not shown
     * or belongs to another voice, so a name is shown once per stretch of speech.
     */
    fun rows(
        segments: List<TranscriptSegment>,
        query: String,
        byLabel: Map<String, RecordingSpeaker>,
        onlyKey: String? = null,
    ): List<Row> {
        val terms = TranscriptFilter.terms(query)
        val out = ArrayList<Row>()
        var prevIndex = Int.MIN_VALUE
        var prevKey: String? = null
        for ((i, s) in segments.withIndex()) {
            val v = voiceOf(s, byLabel)
            if (onlyKey != null && v?.key != onlyKey) continue
            val ranges = if (terms.isEmpty()) emptyList() else matchRanges(s.text, v?.name, terms) ?: continue
            val starts = v != null && (prevIndex != i - 1 || prevKey != v.key)
            out += Row(TranscriptFilter.Match(i, s, ranges), v, starts)
            prevIndex = i
            prevKey = v?.key
        }
        return out
    }

    /** Text highlight ranges when every term is in [text] or [name]; null when one is missing. */
    internal fun matchRanges(text: String, name: String?, terms: List<String>): List<IntRange>? {
        val hay = text.lowercase(Locale.ROOT)
        val who = name?.lowercase(Locale.ROOT).orEmpty()
        val raw = ArrayList<IntRange>()
        for (t in terms) {
            var from = hay.indexOf(t)
            if (from < 0) {
                if (who.contains(t)) continue
                return null
            }
            while (from >= 0) {
                raw += from until from + t.length
                from = hay.indexOf(t, from + t.length)
            }
        }
        // Lower-casing can change length for a few scripts; fall back to no highlighting then.
        return if (hay.length == text.length) TranscriptFilter.merge(raw) else emptyList()
    }

    /**
     * Whole-percent share of talk time per label, adding up to 100 (largest remainder). Uses the
     * stored speech time, or the turns when no speaker has any. Empty when nobody spoke.
     */
    fun talkShares(speakers: List<RecordingSpeaker>, turns: List<SpeakerTurn>): Map<String, Int> {
        val ms = LinkedHashMap<String, Long>()
        if (speakers.any { it.speechMs > 0 }) {
            for (s in speakers) ms[s.label] = (ms[s.label] ?: 0L) + s.speechMs.coerceAtLeast(0)
        } else {
            for (s in speakers) ms[s.label] = 0L
            for (t in turns) ms[t.label] = (ms[t.label] ?: 0L) + (t.endMs - t.startMs).coerceAtLeast(0)
        }
        val total = ms.values.sum()
        if (total <= 0) return emptyMap()
        val floors = LinkedHashMap<String, Int>()
        val remainders = ArrayList<Triple<String, Long, Long>>() // label, remainder numerator, ms
        for ((label, v) in ms) {
            val scaled = v * 100
            floors[label] = (scaled / total).toInt()
            remainders += Triple(label, scaled % total, v)
        }
        var left = 100 - floors.values.sum()
        for ((label, _, _) in remainders.sortedWith(compareByDescending<Triple<String, Long, Long>> { it.second }.thenByDescending { it.third })) {
            if (left <= 0) break
            floors[label] = floors.getValue(label) + 1
            left--
        }
        return floors
    }

    /**
     * Start times (ascending) of the blocks of voice [key]: its transcript blocks, or its turns
     * when the transcript has none (not transcribed yet).
     */
    fun blockStarts(key: String, segments: List<TranscriptSegment>, byLabel: Map<String, RecordingSpeaker>, turns: List<SpeakerTurn>): LongArray {
        val fromTranscript = segments.filter { voiceOf(it, byLabel)?.key == key }.map { it.startMs }
        val starts = fromTranscript.ifEmpty { turns.filter { it.label == key }.map { it.startMs } }
        return starts.sorted().distinct().toLongArray()
    }

    /**
     * The first block starting more than [minStepMs] after [positionMs], or null at the end.
     * The small step keeps a press right after a jump from landing on the same block.
     */
    fun next(starts: LongArray, positionMs: Long, minStepMs: Long = 50): Long? {
        for (s in starts) if (s > positionMs + minStepMs) return s
        return null
    }

    /**
     * Like a media "previous" button: more than [restartMs] into a block goes back to its start,
     * otherwise to the block before it. Null before the first block.
     */
    fun previous(starts: LongArray, positionMs: Long, restartMs: Long = 2_000): Long? {
        var best: Long? = null
        for (s in starts) {
            if (s <= positionMs - restartMs) best = s else break
        }
        return best
    }

    /** The number of a label "S<n>", or null for any other label. */
    fun labelNumber(label: String): Int? = NUMBERED_LABEL.matchEntire(label.trim())?.groupValues?.get(1)?.toIntOrNull()

    /** Voices in order of first appearance (S1, S2, ... S10), other labels last. */
    fun inOrder(speakers: List<RecordingSpeaker>): List<RecordingSpeaker> =
        speakers.sortedWith(compareBy<RecordingSpeaker> { labelNumber(it.label) ?: Int.MAX_VALUE }.thenBy { it.label })

    /** "S<n>" one past the highest numbered label, for a voice added by hand. */
    fun nextFreeLabel(existing: Collection<String>): String {
        val max = existing.mapNotNull { labelNumber(it) }.maxOrNull() ?: 0
        return "S${max + 1}"
    }

    /** Voices of this recording nobody has named yet. */
    /** Voices still to name, counted exactly as SpeakerService.needsNaming counts them. */
    fun unnamedCount(speakers: List<RecordingSpeaker>): Int =
        NamingRules.countToName(speakers.map { NamingRules.Voice(it.personId, it.confirmed, it.speechMs) })
}
