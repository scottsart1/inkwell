package com.inkwell.notes.speakers

import com.inkwell.notes.core.model.Person
import com.inkwell.notes.core.model.RecordingSpeaker
import com.inkwell.notes.core.model.SpeakerTurn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/** State of the on-device speaker models (downloaded once, then used offline). */
sealed interface SpeakerModelState {
    data object NotDownloaded : SpeakerModelState
    data class Downloading(val bytesDone: Long, val bytesTotal: Long) : SpeakerModelState
    data object Ready : SpeakerModelState
    data class Failed(val message: String) : SpeakerModelState
}

/** Where speaker identification of one recording stands. */
sealed interface DiarizationPhase {
    data object None : DiarizationPhase
    data object Queued : DiarizationPhase
    /** Waiting for the on-device models to download. */
    data object WaitingForModels : DiarizationPhase
    /** [done] of [total] audio segments analysed. */
    data class Running(val done: Int, val total: Int) : DiarizationPhase
    data object Done : DiarizationPhase
    data class Failed(val message: String?) : DiarizationPhase

    val busy: Boolean get() = this is Queued || this is WaitingForModels || this is Running
}

/**
 * Speakers found in one recording by an engine, before anyone is named. Labels are "S1",
 * "S2", ... in order of first appearance.
 */
data class DiarizationResult(
    val recordingId: String,
    /** "device:<embedding model id>" or "openai:<model id>". */
    val engine: String,
    /** Sorted by start, non-overlapping. */
    val turns: List<SpeakerTurn>,
    val speakers: List<DetectedSpeaker>,
)

data class DetectedSpeaker(
    val label: String,
    /** A known person this voice was recognised as, or null. */
    val personId: String?,
    /** Voiceprint similarity 0..1 when recognised on the device; null otherwise. */
    val confidence: Float?,
    /**
     * L2-normalised mean embedding of this voice (device engine only). Kept encrypted on the
     * device so naming the voice later can enroll it; never synced, exported or uploaded.
     */
    val centroid: FloatArray?,
    val speechMs: Long,
    /** A representative 2-10 s clip on the recording timeline. */
    val sampleStartMs: Long,
    val sampleEndMs: Long,
)

/** A known voice sent to a cloud diarization model (OpenAI known_speaker_references). */
data class SpeakerReference(
    /** Name the provider echoes back for this voice: a person's name or a recording label. */
    val name: String,
    /** The person it belongs to, or null for a voice heard earlier in the same recording. */
    val personId: String?,
    /** 16 kHz mono 16-bit PCM WAV, 2-10 s. */
    val wav: ByteArray,
)

/**
 * Who spoke when, and who they are. Voices are matched against people the user has named;
 * after a recording the user is asked to name voices the app has not met yet, and from then
 * on those people are labelled automatically in every recording.
 *
 * Voiceprints (embeddings and reference clips) are biometric data: they stay on this device,
 * encrypted with a Keystore key, outside the database, every backup, export and sync.
 * Implemented by [SpeakerServiceImpl].
 */
interface SpeakerService {
    // ------------------------------------------------------------------ models

    val modelState: StateFlow<SpeakerModelState>

    /** Queues the one-time model download (WorkManager). No-op when already ready. */
    suspend fun downloadModels()

    /** Deletes the downloaded models (they are downloaded again when needed). */
    suspend fun deleteModels()

    // ------------------------------------------------------------------ people

    fun people(): Flow<List<Person>>

    suspend fun person(id: String): Person?

    /** Creates a person with no voiceprint yet (names can be typed before anyone speaks). */
    suspend fun createPerson(name: String, isMe: Boolean = false): Person

    suspend fun renamePerson(personId: String, name: String)

    /** Marks [personId] as the tablet's owner (null: nobody). */
    suspend fun setMe(personId: String?)

    /**
     * Moves every label and voiceprint sample of [fromId] onto [intoId], then deletes [fromId].
     * Throws, changing nothing, while stored voiceprints are temporarily unreadable (Keystore).
     */
    suspend fun mergePeople(fromId: String, intoId: String)

    /**
     * Forgets a person and their voiceprint. Their voices become unnamed ("Speaker N") again in
     * every recording, so transcripts, search, exports and Google Drive agree with the panel.
     */
    suspend fun deletePerson(personId: String)

    /**
     * Forgets every voiceprint and reference clip, including the per-recording voice data kept
     * for naming later (voices named afterwards then label their recording but are not
     * remembered). People and names in transcripts stay.
     */
    suspend fun deleteAllVoiceprints()

    /**
     * Enrolls [personId]'s voice from a clip recorded for that purpose ("Record my voice").
     * [audio] is any file the platform decoder reads (m4a, wav). Returns false when the clip
     * holds too little speech, and also when nothing can be stored: voices are not remembered,
     * the person no longer exists, or the on-device models are missing or fail verification (the
     * download is then queued). Callers check [modelState] and SpeakerPrefs.rememberVoices first.
     */
    suspend fun enroll(personId: String, audio: File): Boolean

    // ------------------------------------------------------------------ one recording

    fun speakers(recordingId: String): Flow<List<RecordingSpeaker>>

    fun turns(recordingId: String): Flow<List<SpeakerTurn>>

    fun phase(recordingId: String): Flow<DiarizationPhase>

    /**
     * Works out who spoke when on the device (engine DEVICE): queues the job, downloading the
     * models first if needed. [replace] re-runs a finished or failed job.
     */
    suspend fun identify(docId: String, recordingId: String, replace: Boolean = false)

    suspend fun cancel(recordingId: String)

    /**
     * Names a voice. Reuses the person called [name] (case-insensitive) or creates one, stores
     * the voiceprint when voices are remembered, and writes the name into the transcript,
     * search index and sync.
     */
    suspend fun nameSpeaker(recordingId: String, label: String, name: String, isMe: Boolean = false): Person

    /** Labels a voice as an existing person (or clears it with null). */
    suspend fun assignSpeaker(recordingId: String, label: String, personId: String?)

    /** Accepts an automatic match, which also refines that person's voiceprint. */
    suspend fun confirmSpeaker(recordingId: String, label: String)

    /** Moves the transcript block starting at [startMs] to voice [label]. */
    suspend fun reassignSegment(recordingId: String, startMs: Long, label: String)

    /** Two labels turned out to be the same voice. */
    suspend fun mergeSpeakers(recordingId: String, fromLabel: String, intoLabel: String)

    /** True while the recording has unnamed voices the user has not skipped. */
    fun needsNaming(recordingId: String): Flow<Boolean>

    /** "Skip": do not ask to name this recording's voices again. */
    suspend fun dismissNaming(recordingId: String)

    // ------------------------------------------------------------------ pipeline hooks

    /** A recording finished: queue identification when the engine is DEVICE. */
    suspend fun onRecordingFinished(docId: String, recordingId: String)

    /** A transcript was stored: label its blocks with the recording's speakers. */
    suspend fun onTranscriptReady(docId: String, recordingId: String)

    /** Stores an engine's result, recognises known people and labels the transcript. */
    suspend fun saveDiarization(docId: String, result: DiarizationResult)

    /**
     * Up to [max] known people for a cloud diarization request on [recordingId]: people already
     * labelled in this recording first, then the owner and people from recent recordings of
     * [docId]. Unnamed voices are not included (the caller cuts its own clips for those).
     */
    suspend fun referencesFor(docId: String, recordingId: String, max: Int = 4): List<SpeakerReference>

    /** Removes a deleted recording's temporary voice data. */
    suspend fun onRecordingDeleted(recordingId: String)
}

/**
 * Decodes recording audio (AAC/M4A segments, WAV) to 16 kHz mono float PCM for the speaker
 * models and reference clips. Implemented by [SpeakerAudioImpl].
 */
interface SpeakerAudio {
    /** PCM of [file] between [startMs] and [endMs] (file-relative), in -1..1 at [SAMPLE_RATE]. */
    suspend fun decode(file: File, startMs: Long = 0, endMs: Long = Long.MAX_VALUE): FloatArray

    /** A 16 kHz mono 16-bit WAV clip of a recording's timeline (spanning segments as needed). */
    suspend fun clip(recordingId: String, startMs: Long, endMs: Long): ByteArray?

    companion object {
        const val SAMPLE_RATE = 16_000
    }
}
