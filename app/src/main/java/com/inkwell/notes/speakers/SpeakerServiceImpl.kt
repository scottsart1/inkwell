package com.inkwell.notes.speakers

import android.util.Log
import androidx.room.withTransaction
import com.inkwell.notes.app.AppGraph
import com.inkwell.notes.core.model.Person
import com.inkwell.notes.core.model.RecordingSpeaker
import com.inkwell.notes.core.model.SpeakerEngine
import com.inkwell.notes.core.model.SpeakerTurn
import com.inkwell.notes.core.storage.FileStore
import com.inkwell.notes.core.storage.db.PersonEntity
import com.inkwell.notes.core.storage.db.RecordingSpeakerEntity
import com.inkwell.notes.core.storage.db.SpeakerDao
import com.inkwell.notes.speakers.engine.SherpaVoiceEngine
import com.inkwell.notes.speakers.logic.NamingRules
import com.inkwell.notes.speakers.logic.NamingNotice
import com.inkwell.notes.speakers.logic.PersonMatcher
import com.inkwell.notes.speakers.logic.PersonPrint
import com.inkwell.notes.speakers.logic.Pcm
import com.inkwell.notes.speakers.logic.ReferenceClip
import com.inkwell.notes.speakers.logic.ReferenceOrder
import com.inkwell.notes.speakers.logic.ReferencePolicy
import com.inkwell.notes.speakers.logic.SampleWindows
import com.inkwell.notes.speakers.logic.SpeakerColors
import com.inkwell.notes.speakers.logic.SpeakerModelSpecs
import com.inkwell.notes.speakers.logic.SpeakerNames
import com.inkwell.notes.speakers.logic.SpeakerPhases
import com.inkwell.notes.speakers.logic.SpeakerRecords
import com.inkwell.notes.speakers.logic.SpeakerRecords.MANUAL_ENGINE
import com.inkwell.notes.speakers.logic.TurnEditor
import com.inkwell.notes.speakers.logic.TurnPostProcessor
import com.inkwell.notes.speakers.logic.VectorMath
import com.inkwell.notes.speakers.logic.VoiceSamples
import com.inkwell.notes.speakers.logic.VoiceVector
import com.inkwell.notes.speakers.logic.Voiceprint
import com.inkwell.notes.speakers.logic.WavWriter
import com.inkwell.notes.speakers.logic.WindowAnalyzer
import com.inkwell.notes.speakers.models.SpeakerModels
import com.inkwell.notes.speakers.voices.VoiceprintStore
import com.inkwell.notes.speakers.voices.valueOrNull
import com.inkwell.notes.speakers.work.AwaitingModels
import com.inkwell.notes.speakers.work.DiarizationWorker
import com.inkwell.notes.speakers.work.Diarizations
import com.inkwell.notes.speakers.work.NativeRunGuard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Speaker identification: on-device diarization (sherpa-onnx, [work.DiarizationWorker]),
 * recognition of known people by voiceprint, naming, corrections, and the speaker labels and
 * names written into transcripts, search and sync.
 *
 * Only names live in Room and travel (transcripts, exports, vault). Voiceprints, per-recording
 * centroids and reference clips are encrypted on this device in [VoiceprintStore]. With
 * SpeakerPrefs.rememberVoices off nothing about a voice is ever written.
 *
 * Concurrency: edits of one recording run under that recording's lock; read-modify-write of
 * voice files under [voiceLock] (always taken inside a recording lock, never the reverse).
 */
class SpeakerServiceImpl(private val graph: AppGraph) : SpeakerService {
    private val dao: SpeakerDao get() = graph.db.speakers()
    private val prefs get() = graph.settings.current.speakers

    internal val models: SpeakerModels by lazy { SpeakerModels(graph.context, graph.appScope) }
    internal val store: VoiceprintStore by lazy { VoiceprintStore(graph.context) }
    /** Recordings whose analysis died inside native code (crash-loop protection). */
    internal val nativeRuns by lazy { NativeRunGuard(graph.context) }
    private val awaiting by lazy { AwaitingModels(graph.context) }
    private val notifications by lazy { SpeakerNotifications(graph.context) }

    private val voiceLock = Mutex()
    private val peopleLock = Mutex()
    private val recordingLocks = ConcurrentHashMap<String, Mutex>()

    init {
        graph.appScope.launch(Dispatchers.IO) {
            // Models that finished downloading while recordings waited (also after process death).
            models.state.filter { it is SpeakerModelState.Ready }.collect {
                runCatching { onModelsReady() }.onFailure { e -> Log.w(TAG, "Queueing waiting recordings failed", e) }
            }
        }
        graph.appScope.launch(Dispatchers.IO) {
            // Identification queued for the on-device engine would only fail once it is switched
            // off (and recordings waiting for the models would be queued again on download).
            graph.settings.settings.map { it.speakers.engine }.distinctUntilChanged().collect { engine ->
                if (engine != SpeakerEngine.DEVICE) {
                    runCatching {
                        awaiting.clear()
                        Diarizations.cancelAll(graph.context)
                        // A cancelled job that was only queued never runs its own cleanup: drop the
                        // window embeddings of every unfinished analysis (it starts over if re-enabled).
                        voiceLock.withLock { store.deleteAllWork() }
                    }.onFailure { Log.w(TAG, "Cancelling device identification failed", it) }
                }
            }
        }
        graph.appScope.launch(Dispatchers.IO) {
            delay(PRUNE_DELAY_MS)
            runCatching { pruneDeletedRecordings() }.onFailure { Log.w(TAG, "Pruning voice data failed", it) }
        }
    }

    // ------------------------------------------------------------------ models

    override val modelState: StateFlow<SpeakerModelState> get() = models.state

    override suspend fun downloadModels() {
        if (withContext(Dispatchers.IO) { models.present() }) {
            // Already here (perhaps just finished): anything still waiting can go.
            onModelsReady()
            return
        }
        models.enqueue(prefs.downloadOnUnmeteredOnly)
    }

    override suspend fun deleteModels() = models.delete()

    /** The download finished: queue every recording that waited for it. */
    internal suspend fun onModelsReady() = withContext(Dispatchers.IO) {
        models.changed()
        if (!models.present()) return@withContext
        if (graph.settings.current.speakers.engine != SpeakerEngine.DEVICE) {
            awaiting.clear()
            return@withContext
        }
        for (e in awaiting.drain()) {
            runCatching { Diarizations.enqueue(graph.context, e.docId, e.recordingId, replace = false) }
                .onFailure { Log.w(TAG, "Could not queue a waiting recording", it) }
        }
    }

    internal fun onModelsFailed() = models.changed()

    /** [recordingId] needs the models: remember it and start the download. */
    internal suspend fun awaitModels(docId: String, recordingId: String) {
        withContext(Dispatchers.IO) { awaiting.add(docId, recordingId) }
        downloadModels()
    }

    // ------------------------------------------------------------------ people

    override fun people(): Flow<List<Person>> =
        dao.observePeople().map { list -> list.map { SpeakerRecords.person(it.person, it.recordingCount) } }

    override suspend fun person(id: String): Person? =
        dao.person(id)?.let { SpeakerRecords.person(it, dao.recordingCountOf(id)) }

    override suspend fun createPerson(name: String, isMe: Boolean): Person {
        val e = personFor(name, isMe)
        return SpeakerRecords.person(e, dao.recordingCountOf(e.id))
    }

    /** The person called [name] (case-insensitive), created when there is none. */
    private suspend fun personFor(name: String, isMe: Boolean): PersonEntity {
        val clean = requireNotNull(SpeakerNames.clean(name)) { "A name is needed" }
        val e = peopleLock.withLock {
            dao.personNamed(clean) ?: run {
                val now = System.currentTimeMillis()
                PersonEntity(
                    id = UUID.randomUUID().toString(),
                    name = clean,
                    isMe = false,
                    colorIndex = SpeakerColors.next(dao.people().map { it.colorIndex }),
                    createdAt = now,
                    updatedAt = now,
                    voiceSamples = 0,
                ).also { dao.upsertPerson(it) }
            }
        }
        if (isMe && !e.isMe) {
            setMe(e.id)
            return dao.person(e.id) ?: e
        }
        return e
    }

    override suspend fun renamePerson(personId: String, name: String) {
        val clean = requireNotNull(SpeakerNames.clean(name)) { "A name is needed" }
        peopleLock.withLock {
            val p = dao.person(personId) ?: return
            if (p.name == clean) return
            // Names are how typed names find people (personFor): two people must not share one.
            val other = dao.personNamed(clean)
            require(other == null || other.id == personId) { "Someone is already called that" }
            dao.upsertPerson(p.copy(name = clean, updatedAt = System.currentTimeMillis()))
        }
        rewriteNames(dao.speakersOf(personId).map { it.recordingId }.toSet())
    }

    override suspend fun setMe(personId: String?) {
        if (personId == null) {
            dao.clearMeExcept("")
            return
        }
        val p = dao.person(personId) ?: return
        graph.db.withTransaction {
            dao.clearMeExcept(personId)
            if (!p.isMe) dao.upsertPerson(p.copy(isMe = true, updatedAt = System.currentTimeMillis()))
        }
    }

    override suspend fun mergePeople(fromId: String, intoId: String) {
        if (fromId == intoId) return
        if (dao.person(fromId) == null || dao.person(intoId) == null) return
        // One voice-lock section. Files of [intoId] first and unguarded: a failure (unreadable
        // print, full disk) throws with nothing changed, so the user sees it and can retry. Then
        // the rows, read inside the transaction so links made meanwhile move too. [fromId]'s
        // files go last; an enrollment of [fromId] finishing meanwhile re-checks its row under
        // this lock and stores nothing.
        val (rows, count) = voiceLock.withLock {
            withContext(Dispatchers.IO) {
                val pa = store.readPrint(intoId)
                val pb = store.readPrint(fromId)
                if (pa is VoiceprintStore.Read.Unavailable || pb is VoiceprintStore.Read.Unavailable) {
                    throw IllegalStateException("Voiceprints are temporarily unreadable")
                }
                var merged = pa.valueOrNull()?.vectors.orEmpty()
                for (v in pb.valueOrNull()?.vectors.orEmpty()) merged = VoiceSamples.add(merged, v)
                writePrint(intoId, merged)
                val ra = store.readReference(intoId).valueOrNull()
                val rb = store.readReference(fromId).valueOrNull()
                if (rb != null && ReferencePolicy.shouldReplace(ra?.speechMs, ra?.wav?.let { ReferencePolicy.wavDurationMs(it.size) }, rb.speechMs, ReferencePolicy.wavDurationMs(rb.wav.size))) {
                    store.writeReference(intoId, rb)
                }
            }
            val moved = graph.db.withTransaction {
                val from = dao.person(fromId) ?: return@withTransaction null
                val into = dao.person(intoId) ?: return@withTransaction null
                val current = dao.speakersOf(fromId)
                dao.upsertSpeakers(current.map { it.copy(personId = intoId) })
                dao.upsertPerson(into.copy(isMe = into.isMe || from.isMe, updatedAt = System.currentTimeMillis()))
                dao.deletePerson(fromId)
                if (from.isMe) dao.clearMeExcept(intoId)
                current
            } ?: return@withLock null
            withContext(Dispatchers.IO) {
                store.deletePerson(fromId)
                moved to sampleCount(intoId)
            }
        } ?: return
        dao.setVoiceSamples(intoId, count, System.currentTimeMillis())
        rewriteNames(rows.map { it.recordingId }.toSet())
    }

    override suspend fun deletePerson(personId: String) {
        val affected = dao.speakersOf(personId).mapTo(LinkedHashSet()) { it.recordingId }
        // recording_speakers.personId is set to null by the foreign key; their voices read
        // "Speaker N" again everywhere (transcript, search, exports, vault), not only in the panel.
        // The row goes first: enrollment re-checks it under the voice lock, so a job finishing
        // concurrently cannot write a voiceprint for this person after the files are deleted.
        dao.deletePerson(personId)
        voiceLock.withLock { withContext(Dispatchers.IO) { store.deletePerson(personId) } }
        rewriteNames(affected)
    }

    override suspend fun deleteAllVoiceprints() {
        voiceLock.withLock { withContext(Dispatchers.IO) { store.wipe() } }
        dao.clearAllVoiceSamples()
    }

    override suspend fun enroll(personId: String, audio: File): Boolean {
        if (!prefs.rememberVoices) return false
        val person = dao.person(personId) ?: return false
        if (!withContext(Dispatchers.IO) { models.present() } || !models.verifyOnce()) {
            downloadModels()
            return false
        }
        val samples = graph.speakerAudio.decode(audio, 0, MAX_ENROLL_MS)
        if (samples.size < SpeakerAudio.SAMPLE_RATE * MIN_ENROLL_SPEECH_MS / 1000) return false
        val dispatcher = SherpaVoiceEngine.newDispatcher()
        val voice = try {
            withContext(dispatcher) {
                SherpaVoiceEngine(models.file(SpeakerModelSpecs.SEGMENTATION), models.file(SpeakerModelSpecs.EMBEDDING)).use { engine ->
                    val raw = engine.diarize(samples)
                    // The clip is meant to be one person: take the voice with the most speech.
                    val main = raw.groupBy { it.speaker }.maxByOrNull { (_, s) -> s.sumOf { (it.endSec - it.startSec).toDouble() } }?.value
                        ?: return@use null
                    val turns = main.map { (it.startSec * 1000).toLong() to (it.endSec * 1000).toLong() }.filter { it.second > it.first }
                    val speech = turns.sumOf { it.second - it.first }
                    if (speech < MIN_ENROLL_SPEECH_MS) return@use null
                    val emb = WindowAnalyzer.embedVoice(samples, SpeakerAudio.SAMPLE_RATE, turns, engine) ?: return@use null
                    Triple(emb, turns.map { SpeakerTurn("S1", it.first, it.second) }, speech)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: LinkageError) {
            Log.e(TAG, "Speaker models unavailable", e)
            null
        } catch (e: Exception) {
            Log.w(TAG, "Enrolling a voice failed: ${e.javaClass.simpleName}")
            null
        } finally {
            dispatcher.close()
        }
        val (embedding, turns, speech) = voice ?: return false
        val tag = "enroll:${System.currentTimeMillis()}"
        val clip = SampleWindows.pick(turns, "S1", Pcm.msOf(samples.size, SpeakerAudio.SAMPLE_RATE))?.let { w ->
            val a = Pcm.samplesFor(w.startMs, SpeakerAudio.SAMPLE_RATE).coerceIn(0, samples.size)
            val b = Pcm.samplesFor(w.endMs, SpeakerAudio.SAMPLE_RATE).coerceIn(a, samples.size)
            ReferenceClip(SampleWindows.speechIn(turns, "S1", w), WavWriter.encode(samples.copyOfRange(a, b)), tag)
        }
        val count = voiceLock.withLock {
            // Deleted meanwhile: store nothing for them.
            if (dao.person(personId) == null) return@withLock null
            withContext(Dispatchers.IO) {
                val read = store.readPrint(personId)
                // Keystore hiccup: writing now would replace the samples we cannot read.
                if (read is VoiceprintStore.Read.Unavailable) return@withContext null
                val current = read.valueOrNull()?.vectors.orEmpty()
                writePrint(personId, VoiceSamples.add(current, VoiceVector(tag, speech.toFloat(), embedding)))
                if (clip != null) maybeReplaceReference(personId, clip)
                sampleCount(personId)
            }
        } ?: return false
        dao.setVoiceSamples(person.id, count, System.currentTimeMillis())
        return true
    }

    // ------------------------------------------------------------------ one recording

    override fun speakers(recordingId: String): Flow<List<RecordingSpeaker>> =
        combine(dao.observeSpeakers(recordingId), dao.observePeople()) { rows, people ->
            SpeakerRecords.recordingSpeakers(rows, people.associate { it.person.id to it.person })
        }.distinctUntilChanged()

    override fun turns(recordingId: String): Flow<List<SpeakerTurn>> =
        dao.observeTurns(recordingId).map { list -> list.map(SpeakerRecords::turn) }.distinctUntilChanged()

    override fun phase(recordingId: String): Flow<DiarizationPhase> =
        combine(
            Diarizations.observe(graph.context, recordingId),
            dao.observeTurns(recordingId).map { it.isNotEmpty() }.distinctUntilChanged(),
            // The waiting list is read from disk on first use: keep that off the main thread.
            flow { emitAll(awaiting.ids) }.flowOn(Dispatchers.IO).map { recordingId in it }.distinctUntilChanged(),
            models.state,
        ) { info, hasTurns, waiting, modelState ->
            SpeakerPhases.of(
                info?.state, Diarizations.progressOf(info), Diarizations.errorOf(info), hasTurns, waiting, modelState,
                jobWaiting = Diarizations.waitingOf(info),
            )
        }.distinctUntilChanged()

    override suspend fun identify(docId: String, recordingId: String, replace: Boolean) {
        if (prefs.engine != SpeakerEngine.DEVICE) {
            Log.i(TAG, "On-device identification is off; not queued")
            return
        }
        if (!withContext(Dispatchers.IO) { models.present() }) {
            awaitModels(docId, recordingId)
            return
        }
        withContext(Dispatchers.IO) {
            awaiting.remove(recordingId)
            // "Try again" is the user's call: earlier native crashes of this recording no longer stop it.
            if (replace) nativeRuns.reset(recordingId)
        }
        Diarizations.enqueue(graph.context, docId, recordingId, replace)
    }

    override suspend fun cancel(recordingId: String) {
        Diarizations.cancel(graph.context, recordingId)
        withContext(Dispatchers.IO) { awaiting.remove(recordingId) }
        // A cancelled analysis is not resumed: its window embeddings (temporary voice data, kept
        // even when voices are not remembered) and the checkpoint go now, not with the recording.
        val docId = runCatching { recordingDoc(recordingId) }.getOrNull()
        voiceLock.withLock {
            withContext(Dispatchers.IO) {
                store.deleteWork(recordingId)
                if (docId != null) DiarizationWorker.checkpointFile(graph.files, docId, recordingId).delete()
            }
        }
    }

    override suspend fun nameSpeaker(recordingId: String, label: String, name: String, isMe: Boolean): Person {
        val p = personFor(name, isMe)
        link(recordingId, label, p.id, confirmOnly = false)
        return person(p.id) ?: SpeakerRecords.person(p)
    }

    override suspend fun assignSpeaker(recordingId: String, label: String, personId: String?) {
        if (personId == null) unlink(recordingId, label) else link(recordingId, label, personId, confirmOnly = false)
    }

    override suspend fun confirmSpeaker(recordingId: String, label: String) {
        val row = dao.speakers(recordingId).firstOrNull { it.label == label } ?: return
        val pid = row.personId ?: return
        link(recordingId, label, pid, confirmOnly = true)
    }

    /** Labels voice [label] as [personId] (the user's decision), enrolling the voice when remembered. */
    private suspend fun link(recordingId: String, label: String, personId: String, confirmOnly: Boolean) {
        val docId = withRecording(recordingId) {
            val row = dao.speakers(recordingId).firstOrNull { it.label == label } ?: return@withRecording null
            val person = dao.person(personId) ?: return@withRecording null
            val previous = row.personId
            val keepConfidence = confirmOnly && previous == personId
            val updated = row.copy(personId = personId, confirmed = true, confidence = if (keepConfidence) row.confidence else null)
            dao.upsertSpeakers(listOf(updated))
            dao.setSegmentNames(recordingId, label, person.name)
            voiceData("Voiceprint") {
                if (previous != null && previous != personId) unenroll(previous, recordingId, label)
                if (prefs.rememberVoices && row.engine != MANUAL_ENGINE) enrollFromRecording(person, recordingId, updated)
            }
            recordingDoc(recordingId)
        } ?: return
        reindex(docId, recordingId)
        cancelNoticeIfDone(recordingId)
    }

    private suspend fun unlink(recordingId: String, label: String) {
        val docId = withRecording(recordingId) {
            val row = dao.speakers(recordingId).firstOrNull { it.label == label } ?: return@withRecording null
            val previous = row.personId ?: return@withRecording null
            dao.upsertSpeakers(listOf(row.copy(personId = null, confirmed = false, confidence = null)))
            dao.setSegmentNames(recordingId, label, SpeakerNames.unnamed(label))
            voiceData("Voiceprint") { unenroll(previous, recordingId, label) }
            recordingDoc(recordingId)
        } ?: return
        reindex(docId, recordingId)
    }

    override suspend fun reassignSegment(recordingId: String, startMs: Long, label: String) {
        val target = label.trim().takeIf { it.isNotEmpty() } ?: return
        val docId = withRecording(recordingId) {
            val segs = graph.db.transcripts().forRecording(recordingId)
            val block = segs.firstOrNull { it.startMs == startMs } ?: return@withRecording null
            if (block.speakerLabel == target) return@withRecording null
            val rows = dao.speakers(recordingId)
            val people = peopleById()
            val row = rows.firstOrNull { it.label == target } ?: RecordingSpeakerEntity(
                recordingId = recordingId,
                label = target,
                personId = null,
                confidence = null,
                confirmed = false,
                speechMs = 0,
                // Picked from the new turns below.
                sampleStartMs = 0,
                sampleEndMs = 0,
                // Split off by the user: no voiceprint of its own, never enrolled into anyone.
                engine = MANUAL_ENGINE,
            ).also { dao.upsertSpeakers(listOf(it)) }
            val name = SpeakerNames.display(target, row.personId?.let { people[it]?.name })
            dao.setSegmentSpeaker(recordingId, startMs, target, name)
            val turns = TurnEditor.assignRange(dao.turns(recordingId).map(SpeakerRecords::turn), block.startMs, maxOf(block.endMs, block.startMs + 1), target)
            dao.replaceTurns(recordingId, SpeakerRecords.turnEntities(recordingId, turns))
            refreshRows(recordingId, turns)
            recordingDoc(recordingId)
        } ?: return
        reindex(docId, recordingId)
    }

    override suspend fun mergeSpeakers(recordingId: String, fromLabel: String, intoLabel: String) {
        if (fromLabel == intoLabel) return
        val docId = withRecording(recordingId) {
            val rows = dao.speakers(recordingId)
            val from = rows.firstOrNull { it.label == fromLabel } ?: return@withRecording null
            val into = rows.firstOrNull { it.label == intoLabel } ?: return@withRecording null
            val merged = SpeakerRecords.merged(from, into)
            dao.relabelSegments(recordingId, fromLabel, intoLabel)
            val turns = TurnEditor.merge(dao.turns(recordingId).map(SpeakerRecords::turn), fromLabel, intoLabel)
            graph.db.withTransaction {
                dao.replaceTurns(recordingId, SpeakerRecords.turnEntities(recordingId, turns))
                dao.deleteSpeaker(recordingId, fromLabel)
                dao.upsertSpeakers(listOf(merged))
            }
            // The user says both are one voice: their centroids become one.
            voiceData("Recording voices") { voiceLock.withLock {
                withContext(Dispatchers.IO) {
                    val rec = store.readRecording(recordingId).valueOrNull()
                    if (rec != null) {
                        val a = rec.vectors.firstOrNull { it.tag == intoLabel }
                        val b = rec.vectors.firstOrNull { it.tag == fromLabel }
                        val combined = when {
                            a != null && b != null -> VectorMath.weightedMean(listOf(a.vector, b.vector), listOf(a.weight.toDouble(), b.weight.toDouble()))
                                ?.let { VoiceVector(intoLabel, a.weight + b.weight, it) }
                            a != null -> a
                            b != null -> VoiceVector(intoLabel, b.weight, b.vector)
                            else -> null
                        }
                        val rest = rec.vectors.filter { it.tag != intoLabel && it.tag != fromLabel }
                        store.writeRecording(recordingId, rec.copy(vectors = rest + listOfNotNull(combined)))
                    }
                }
            } }
            val person = merged.personId?.let { dao.person(it) }
            voiceData("Voiceprint") {
                from.personId?.let { unenroll(it, recordingId, fromLabel) }
                if (person != null && merged.confirmed && prefs.rememberVoices && merged.engine != MANUAL_ENGINE) {
                    enrollFromRecording(person, recordingId, merged)
                }
            }
            refreshRows(recordingId, turns)
            dao.setSegmentNames(recordingId, intoLabel, SpeakerNames.display(intoLabel, person?.name))
            recordingDoc(recordingId)
        } ?: return
        reindex(docId, recordingId)
    }

    override fun needsNaming(recordingId: String): Flow<Boolean> =
        combine(dao.observeSpeakers(recordingId), graph.settings.settings) { rows, s ->
            NamingRules.needsNaming(rows.map { NamingRules.Voice(it.personId, it.confirmed, it.speechMs) }, s.speakers.askToName)
        }.distinctUntilChanged()

    override suspend fun dismissNaming(recordingId: String) {
        withRecording(recordingId) {
            val rows = dao.speakers(recordingId).filter { it.personId == null && !it.confirmed }
            if (rows.isNotEmpty()) dao.upsertSpeakers(rows.map { it.copy(confirmed = true) })
        }
        notifications.cancel(recordingId)
    }

    // ------------------------------------------------------------------ pipeline hooks

    override suspend fun onRecordingFinished(docId: String, recordingId: String) {
        if (prefs.engine == SpeakerEngine.DEVICE) identify(docId, recordingId, replace = false)
    }

    override suspend fun onTranscriptReady(docId: String, recordingId: String) {
        val changed = withRecording(recordingId) { labelTranscriptLocked(recordingId, keepExistingLabels = true) }
        if (changed) reindex(docId, recordingId)
    }

    override suspend fun saveDiarization(docId: String, result: DiarizationResult) {
        val recordingId = result.recordingId
        val rec = graph.db.recordings().get(recordingId) ?: return
        val device = result.engine.startsWith("device:")
        val remember = prefs.rememberVoices
        val threshold = prefs.matchThreshold.coerceIn(0f, 1f)
        val totalMs = rec.durationMs.takeIf { it > 0 } ?: rec.segmentDurations.split(',').mapNotNull { it.toLongOrNull() }.sum()
        val changed = withRecording(recordingId) {
            val oldRows = dao.speakers(recordingId)
            val oldTurns = dao.turns(recordingId).map(SpeakerRecords::turn)
            val turns = TurnPostProcessor.mergeAdjacent(TurnPostProcessor.flatten(result.turns), 1)
            val people = dao.people()
            val assignments = HashMap(SpeakerRecords.carryOver(result.copy(turns = turns), oldRows, oldTurns, people.map { it.id }.toSet()))

            // Recognise known people among the voices nobody has decided on yet.
            if (device && remember) {
                val open = result.speakers.filter { it.centroid != null && it.label !in assignments }
                if (open.isNotEmpty()) {
                    val prints = loadPrints(people)
                    val taken = assignments.values.mapNotNull { it.personId }.toSet()
                    for (m in PersonMatcher.match(open.associate { it.label to it.centroid!! }, prints, threshold, taken)) {
                        assignments[m.label] = SpeakerRecords.Assignment(m.personId, m.score, confirmed = false)
                    }
                }
            }

            val rows = SpeakerRecords.rowsFor(result.copy(turns = turns), turns, assignments, totalMs)
            graph.db.withTransaction {
                dao.deleteSpeakers(recordingId)
                dao.upsertSpeakers(rows)
                dao.replaceTurns(recordingId, SpeakerRecords.turnEntities(recordingId, turns))
            }

            // The transcript follows the rows just committed, whatever happens to the voice files below.
            val labelled = labelTranscriptLocked(recordingId, keepExistingLabels = !device)
            voiceData("Recording voices") {
                voiceLock.withLock {
                    withContext(Dispatchers.IO) {
                        // Centroids are keyed by this result's labels. A cloud result has none, and the
                        // labels of an earlier device analysis no longer apply to its voices.
                        val centroids = result.speakers.mapNotNull { s -> s.centroid?.let { VoiceVector(s.label, s.speechMs.toFloat(), VectorMath.normalize(it)) } }
                        // The old centroids go first: if writing the new ones fails, enrollment must find
                        // none rather than an earlier analysis's voice under the same label.
                        store.deleteRecording(recordingId)
                        if (device && remember && centroids.isNotEmpty()) {
                            store.writeRecording(recordingId, Voiceprint(SpeakerModelSpecs.EMBEDDING_MODEL_ID, centroids.first().vector.size, centroids))
                        }
                    }
                }
            }
            if (remember) voiceData("Voiceprint") {
                // A device result replaces this recording's earlier samples with its own. A cloud result
                // carries no voice data, so people still linked keep what this recording gave them and
                // only people whose link disappeared lose it.
                val kept = rows.filter { it.confirmed }.mapNotNullTo(HashSet()) { it.personId }
                for (pid in oldRows.mapNotNull { it.personId }.distinct()) {
                    if (device || pid !in kept) unenroll(pid, recordingId, label = null)
                }
                for (r in rows) {
                    if (r.personId == null || !r.confirmed) continue
                    val p = people.firstOrNull { it.id == r.personId } ?: continue
                    enrollFromRecording(p, recordingId, r)
                }
            }
            labelled
        }
        reindex(docId, recordingId, transcriptChanged = changed)
    }

    override suspend fun referencesFor(docId: String, recordingId: String, max: Int): List<SpeakerReference> {
        if (max <= 0) return emptyList()
        val out = ArrayList<SpeakerReference>()
        val usedPeople = HashSet<String>()
        val people = dao.people()
        val byId = people.associateBy { it.id }
        val withClip = withContext(Dispatchers.IO) { people.filter { store.hasReference(it.id) }.map { it.id }.toSet() }

        // People already heard in this recording, in label order. Unnamed voices are skipped: the
        // cloud transcription cuts its own clips for voices it hears, so a slot spent on one would
        // only crowd out a known person.
        val rows = dao.speakers(recordingId).sortedWith(compareBy<RecordingSpeakerEntity> { SpeakerNames.number(it.label) ?: Int.MAX_VALUE }.thenBy { it.label })
        for (r in rows) {
            if (out.size >= max) break
            val p = r.personId?.let { byId[it] } ?: continue
            if (p.id in usedPeople) continue
            val stored = if (p.id in withClip) personClip(p.id) else null
            val wav = stored ?: clipOf(recordingId, r) ?: continue
            out += SpeakerReference(name = p.name, personId = p.id, wav = wav)
            usedPeople += p.id
        }

        // Then the owner, people from recent recordings of the document, everyone else.
        if (out.size < max) {
            val recent = graph.db.recordings().forDocument(docId).filter { it.id != recordingId }.sortedByDescending { it.startedAt }
                .flatMap { r -> dao.speakers(r.id).sortedBy { SpeakerNames.number(it.label) ?: Int.MAX_VALUE }.mapNotNull { it.personId } }
            val order = ReferenceOrder.people(
                people.filter { it.id !in usedPeople }.map { ReferenceOrder.Candidate(it.id, it.isMe, it.updatedAt) },
                inThisRecording = emptyList(),
                recentInDocument = recent,
                withClip = withClip,
                max = max - out.size,
            )
            for (id in order) {
                val p = byId[id] ?: continue
                val wav = personClip(id) ?: continue
                out += SpeakerReference(name = p.name, personId = id, wav = wav)
            }
        }
        return out.take(max)
    }

    override suspend fun onRecordingDeleted(recordingId: String) {
        withContext(Dispatchers.IO) { runCatching { nativeRuns.reset(recordingId) } }
        Diarizations.cancel(graph.context, recordingId)
        withContext(Dispatchers.IO) { awaiting.remove(recordingId) }
        voiceLock.withLock {
            withContext(Dispatchers.IO) {
                store.deleteRecording(recordingId)
                store.deleteWork(recordingId)
            }
        }
        notifications.cancel(recordingId)
        recordingLocks.remove(recordingId)
    }

    // ------------------------------------------------------------------ notification

    /** After a background identification: ask for names when the app is not on screen. */
    internal suspend fun notifyIfNamesNeeded(docId: String, recordingId: String) {
        val settings = graph.settings.current
        if (!settings.speakers.askToName) return
        val count = NamingRules.countToName(dao.speakers(recordingId).map { NamingRules.Voice(it.personId, it.confirmed, it.speechMs) })
        if (count == 0) return
        val title = runCatching { graph.library.getDocument(docId)?.title }.getOrNull()
        val text = NamingNotice.decide(count, title, settings.privacy.appLock, notifications.inForeground(), notifications.canNotify()) ?: return
        notifications.post(docId, recordingId, text)
    }

    private suspend fun cancelNoticeIfDone(recordingId: String) {
        val rows = dao.speakers(recordingId)
        if (NamingRules.countToName(rows.map { NamingRules.Voice(it.personId, it.confirmed, it.speechMs) }) == 0) notifications.cancel(recordingId)
    }

    // ------------------------------------------------------------------ transcript labels

    /** Writes labels and names into the transcript; true when it changed. Caller holds the recording lock. */
    private suspend fun labelTranscriptLocked(recordingId: String, keepExistingLabels: Boolean): Boolean =
        graph.db.withTransaction {
            val segs = graph.db.transcripts().forRecording(recordingId)
            if (segs.isEmpty()) return@withTransaction false
            val names = SpeakerRecords.names(dao.speakers(recordingId), peopleById())
            val turns = dao.turns(recordingId).map(SpeakerRecords::turn)
            val next = SpeakerRecords.relabel(segs, turns, names, keepExistingLabels) ?: return@withTransaction false
            graph.db.transcripts().replace(recordingId, next)
            true
        }

    /** Rewrites the names of every voice in [recordingIds] and re-indexes them. */
    private suspend fun rewriteNames(recordingIds: Set<String>) {
        val people = peopleById()
        for (id in recordingIds) {
            val docId = withRecording(id) {
                for (r in dao.speakers(id)) dao.setSegmentNames(id, r.label, SpeakerNames.display(r.label, r.personId?.let { people[it]?.name }))
                recordingDoc(id)
            } ?: continue
            reindex(docId, id)
        }
    }

    /** Hands the stored transcript to search and sync. Logs never include transcript text or names. */
    private suspend fun reindex(docId: String, recordingId: String, transcriptChanged: Boolean = true) {
        if (!transcriptChanged) return
        val segs = runCatching { graph.db.transcripts().forRecording(recordingId) }.getOrNull() ?: return
        if (segs.isEmpty()) return
        val source = segs.first().source
        runCatching { graph.search.replaceTranscript(docId, recordingId, segs.map { it.toModel() }, source) }
            .onFailure { Log.w(TAG, "Re-indexing the transcript failed: ${it.javaClass.simpleName}") }
        runCatching { graph.sync.onTranscriptReady(docId, recordingId) }
            .onFailure { Log.w(TAG, "Sync hook failed: ${it.javaClass.simpleName}") }
    }

    // ------------------------------------------------------------------ voiceprints

    /** People with a stored voiceprint, for matching. Unreadable prints are dropped quietly. */
    private suspend fun loadPrints(people: List<PersonEntity>): List<PersonPrint> {
        val stale = ArrayList<Pair<PersonEntity, Int>>()
        val prints = voiceLock.withLock {
            withContext(Dispatchers.IO) {
                people.mapNotNull { p ->
                    when (val r = store.readPrint(p.id)) {
                        is VoiceprintStore.Read.Found -> PersonPrint(p.id, p.isMe, r.value.vectors.map { it.vector })
                        is VoiceprintStore.Read.Dropped -> {
                            stale += p to sampleCount(p.id)
                            null
                        }
                        else -> null
                    }
                }
            }
        }
        for ((p, n) in stale) if (p.voiceSamples != n) dao.setVoiceSamples(p.id, n, System.currentTimeMillis())
        return prints
    }

    /** Adds voice [row] of [recordingId] to [person]'s voiceprint and maybe their reference clip. */
    private suspend fun enrollFromRecording(person: PersonEntity, recordingId: String, row: RecordingSpeakerEntity) {
        val tag = VoiceSamples.recordingTag(recordingId, row.label)
        val window = SampleWindows.Window(row.sampleStartMs, row.sampleEndMs)
        val turns = dao.turns(recordingId).map(SpeakerRecords::turn)
        val speech = SampleWindows.speechIn(turns, row.label, window)
        // Decode outside the lock; it is only kept if it beats the stored clip.
        val stored = withContext(Dispatchers.IO) { store.readReference(person.id).valueOrNull() }
        val wantsClip = ReferencePolicy.shouldReplace(stored?.speechMs, stored?.wav?.let { ReferencePolicy.wavDurationMs(it.size) }, speech, window.durationMs)
        val wav = if (wantsClip) clipOf(recordingId, row) else null
        val count = voiceLock.withLock {
            // Deleted meanwhile: store nothing for them.
            if (dao.person(person.id) == null) return@withLock null
            withContext(Dispatchers.IO) {
                val read = store.readPrint(person.id)
                // Keystore hiccup: change nothing (not even the count) rather than replace the print.
                if (read is VoiceprintStore.Read.Unavailable) return@withContext null
                val centroid = store.readRecording(recordingId).valueOrNull()?.vectors?.firstOrNull { it.tag == row.label }
                if (centroid != null) {
                    val current = read.valueOrNull()?.vectors.orEmpty()
                    writePrint(person.id, VoiceSamples.add(current, VoiceVector(tag, centroid.weight, centroid.vector)))
                }
                if (wav != null) maybeReplaceReference(person.id, ReferenceClip(speech, wav, tag))
                sampleCount(person.id)
            }
        } ?: return
        dao.setVoiceSamples(person.id, count, System.currentTimeMillis())
    }

    /** Removes what voice [label] (null: every voice) of [recordingId] contributed to [personId]'s voiceprint. */
    private suspend fun unenroll(personId: String, recordingId: String, label: String?) {
        // Samples are tagged with the labels of the device analysis that made them. Once a cloud
        // transcription relabels the recording those tags no longer match its labels, so a voice
        // of a cloud-labelled recording removes everything the recording gave this person (unless
        // another voice there is still theirs).
        val scope = if (label != null && wholeRecordingScope(personId, recordingId, label)) null else label
        val prefix = if (scope == null) VoiceSamples.recordingPrefix(recordingId) else VoiceSamples.recordingTag(recordingId, scope)
        val count = voiceLock.withLock {
            withContext(Dispatchers.IO) {
                val read = store.readPrint(personId)
                if (read is VoiceprintStore.Read.Unavailable) return@withContext null
                val current = read.valueOrNull()?.vectors
                if (current != null) {
                    val kept = current.filterNot { if (scope == null) it.tag.startsWith(prefix) else it.tag == prefix }
                    if (kept.size != current.size) writePrint(personId, kept)
                }
                val ref = store.readReference(personId).valueOrNull()
                if (ref != null && (if (scope == null) ref.tag.startsWith(prefix) else ref.tag == prefix)) store.deleteReference(personId)
                sampleCount(personId)
            }
        } ?: return
        val p = dao.person(personId) ?: return
        if (p.voiceSamples != count) dao.setVoiceSamples(personId, count, System.currentTimeMillis())
    }

    /** True when [recordingId] carries cloud labels and no voice but [label] there is [personId]'s. */
    private suspend fun wholeRecordingScope(personId: String, recordingId: String, label: String): Boolean {
        val rows = dao.speakers(recordingId)
        if (rows.none { it.engine.startsWith(CLOUD_ENGINE_PREFIX) }) return false
        return rows.none { it.label != label && it.personId == personId }
    }

    /** Caller holds [voiceLock]; on Dispatchers.IO. */
    private fun maybeReplaceReference(personId: String, clip: ReferenceClip) {
        val stored = store.readReference(personId).valueOrNull()
        val clipMs = ReferencePolicy.wavDurationMs(clip.wav.size)
        if (stored == null || stored.tag == clip.tag ||
            ReferencePolicy.shouldReplace(stored.speechMs, ReferencePolicy.wavDurationMs(stored.wav.size), clip.speechMs, clipMs)
        ) {
            if (clipMs in ReferencePolicy.MIN_CLIP_MS..ReferencePolicy.MAX_CLIP_MS) store.writeReference(personId, clip)
        }
    }

    /** Caller holds [voiceLock]; on Dispatchers.IO. */
    private fun writePrint(personId: String, samples: List<VoiceVector>) {
        val dim = samples.firstOrNull()?.vector?.size
        if (dim == null) store.writePrint(personId, Voiceprint(SpeakerModelSpecs.EMBEDDING_MODEL_ID, 1, emptyList()))
        else store.writePrint(personId, Voiceprint(SpeakerModelSpecs.EMBEDDING_MODEL_ID, dim, samples.filter { it.vector.size == dim }))
    }

    /** Stored samples, or 1 for a person known only by a reference clip (cloud recognition). On IO. */
    private fun sampleCount(personId: String): Int {
        val n = store.readPrint(personId).valueOrNull()?.vectors?.size ?: 0
        return if (n == 0 && store.hasReference(personId)) 1 else n
    }

    private suspend fun personClip(personId: String): ByteArray? =
        withContext(Dispatchers.IO) { store.readReference(personId).valueOrNull()?.wav }

    /** A 2-10 s clip of voice [row] from the recording itself. */
    private suspend fun clipOf(recordingId: String, row: RecordingSpeakerEntity): ByteArray? {
        val w = SampleWindows.Window(row.sampleStartMs, row.sampleEndMs)
        if (w.durationMs !in SampleWindows.MIN_MS..SampleWindows.MAX_MS) return null
        return try {
            graph.speakerAudio.clip(recordingId, w.startMs, w.endMs)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Could not cut a voice clip: ${e.javaClass.simpleName}")
            null
        }
    }

    // ------------------------------------------------------------------ helpers

    private suspend fun refreshRows(recordingId: String, turns: List<SpeakerTurn>) {
        val rows = dao.speakers(recordingId)
        val labels = graph.db.transcripts().forRecording(recordingId).mapNotNull { it.speakerLabel }.toSet()
        val total = graph.db.recordings().get(recordingId)?.durationMs ?: 0L
        val r = SpeakerRecords.refresh(rows, turns, labels, total)
        if (r.upserts.isNotEmpty()) dao.upsertSpeakers(r.upserts)
        for (label in r.deletes) {
            rows.firstOrNull { it.label == label }?.personId?.let { pid -> voiceData("Voiceprint") { unenroll(pid, recordingId, label) } }
            dao.deleteSpeaker(recordingId, label)
        }
        if (r.deletes.isNotEmpty()) voiceData("Recording voices") {
            voiceLock.withLock {
                withContext(Dispatchers.IO) {
                    store.readRecording(recordingId).valueOrNull()?.let { rec ->
                        store.writeRecording(recordingId, rec.copy(vectors = rec.vectors.filter { it.tag !in r.deletes }))
                    }
                }
            }
        }
    }

    private suspend fun peopleById(): Map<String, PersonEntity> = dao.people().associateBy { it.id }

    private suspend fun recordingDoc(recordingId: String): String? = graph.db.recordings().get(recordingId)?.documentId

    private suspend fun <T> withRecording(recordingId: String, block: suspend () -> T): T =
        recordingLocks.computeIfAbsent(recordingId) { Mutex() }.withLock { block() }

    /**
     * Voice files are best effort: a full disk or a Keystore hiccup must never leave the speaker
     * rows, transcript, search index, exports and vault out of step. Logs carry no voice data.
     */
    private suspend inline fun voiceData(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "$what not stored: ${e.javaClass.simpleName}")
        }
    }

    /** Removes voice data of recordings and people that no longer exist (deleted elsewhere). */
    private suspend fun pruneDeletedRecordings() {
        // Files are named by the sanitised id, so compare sanitised ids.
        voiceLock.withLock {
            // Snapshots under the lock: every writer of these files holds it, so none can be stale.
            val existing = dao.allRecordingIds().map { FileStore.safe(it) }.toSet()
            val people = dao.people().map { FileStore.safe(it.id) }.toSet()
            withContext(Dispatchers.IO) {
                for (name in store.recordingIds()) {
                    if (name in existing) continue
                    store.deleteRecording(name)
                    store.deleteWork(name)
                }
                for (name in store.personIds()) if (name !in people) store.deletePerson(name)
            }
        }
    }

    private companion object {
        const val TAG = "Speakers"
        const val PRUNE_DELAY_MS = 30_000L
        /** RecordingSpeakerEntity.engine of voices labelled by a cloud transcription. */
        const val CLOUD_ENGINE_PREFIX = "openai:"
        const val MAX_ENROLL_MS = 120_000L
        const val MIN_ENROLL_SPEECH_MS = 3_000L
    }
}
