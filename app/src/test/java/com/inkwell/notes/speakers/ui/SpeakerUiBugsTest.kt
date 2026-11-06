package com.inkwell.notes.speakers.ui

import com.inkwell.notes.core.model.Recording
import com.inkwell.notes.core.model.RecordingStatus
import com.inkwell.notes.core.model.SpeakerEngine
import com.inkwell.notes.core.model.SpeakerPrefs
import com.inkwell.notes.core.model.TranscriptSegment
import com.inkwell.notes.settings.logic.SpeakerPrefsLogic
import com.inkwell.notes.speakers.DiarizationPhase
import com.inkwell.notes.speakers.SpeakerModelState
import com.inkwell.notes.speakers.ui.logic.BlockMove
import com.inkwell.notes.speakers.ui.logic.EnrollmentLogic
import com.inkwell.notes.speakers.ui.logic.EnrollmentLogic.Gate
import com.inkwell.notes.speakers.ui.logic.EnrollmentLogic.Phase
import com.inkwell.notes.speakers.ui.logic.EnrollmentLogic.Reason
import com.inkwell.notes.speakers.ui.logic.NamingDraft
import com.inkwell.notes.speakers.ui.logic.NamingOp
import com.inkwell.notes.speakers.ui.logic.NamingPlan
import com.inkwell.notes.speakers.ui.logic.NamingPromptLogic
import com.inkwell.notes.speakers.ui.logic.SpeakerEdits
import com.inkwell.notes.speakers.ui.logic.SpeakerStatus
import com.inkwell.notes.speakers.ui.logic.SpeakerStatusLogic
import com.inkwell.notes.speakers.ui.logic.SpeakerTranscript
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Bugs found in the speaker UI during the fourth bug pass; each test failed (or could not be written) before its fix. */
class SpeakerUiBugsTest {

    // ------------------------------------------------------------------ status card

    @Test
    fun aDeviceJobThatFailedBecauseTheEngineWasSwitchedIsNotShownAsAFailure() {
        // The job was queued with engine DEVICE; the user switched to OpenAI before it ran, so the
        // worker ended with "engine off". The red card offered no way out (no retry) and nothing
        // the user could do about it in this panel: it must not be shown at all.
        for (engine in listOf(SpeakerEngine.OPENAI)) {
            val s = SpeakerStatusLogic.of(
                engine = engine,
                phase = DiarizationPhase.Failed("Telling voices apart on this tablet is turned off."),
                models = SpeakerModelState.Ready,
                hasSpeakers = false,
                hasAudio = true,
                wifiOnly = false,
            )
            assertEquals("engine $engine", SpeakerStatus.Hidden, s)
        }
        // On the device a failure still shows, with a retry.
        val device = SpeakerStatusLogic.of(SpeakerEngine.DEVICE, DiarizationPhase.Failed("x"), SpeakerModelState.Ready, false, true, false)
        assertEquals(SpeakerStatus.Failed("x", canRetry = true), device)
    }

    // ------------------------------------------------------------------ deleted people

    @Test
    fun aVoiceWhosePersonWasDeletedKeepsTheNameWrittenIntoTheTranscript() {
        // Deleting a person nulls recording_speakers.personId (foreign key) but the transcript,
        // exports, search and Google Drive keep "Priya". The panel showed "Speaker 1" for the same
        // blocks, so the screen and the export disagreed.
        val row = speaker("S1", personId = null, confirmed = false, speechMs = 10_000)
        assertEquals("Speaker 1", row.displayName)
        val byLabel = mapOf("S1" to row)
        val seg = TranscriptSegment(0, 4_000, "Welcome everyone.", "Priya", "S1")

        val v = SpeakerTranscript.voiceOf(seg, byLabel)!!
        assertEquals("Priya", v.name)
        assertEquals("the block still groups and filters under its voice", "S1", v.key)
        assertEquals("and keeps the voice's colour", row.colorIndex, v.colorIndex)

        // A genuinely unnamed voice stays "Speaker 1" (the stored generic name is not a real name).
        val generic = TranscriptSegment(4_000, 8_000, "Next.", "Speaker 1", "S1")
        assertEquals("Speaker 1", SpeakerTranscript.voiceOf(generic, byLabel)!!.name)
        // A named voice always shows its person's current name, whatever older blocks hold.
        val named = speaker("S2", "Tom", personId = "p2")
        assertEquals("Tom", SpeakerTranscript.voiceOf(TranscriptSegment(0, 1, "x", "Thomas", "S2"), mapOf("S2" to named))!!.name)

        // Searching the person's name still finds those blocks.
        val rows = SpeakerTranscript.rows(listOf(seg, generic), "priya", byLabel)
        assertEquals(listOf(0), rows.map { it.match.index })
        assertTrue(rows.single().match.ranges.isEmpty())
    }

    @Test
    fun chipsAndTheFilterBarUseTheSameStoredNamesAsTheBlocks() {
        val gone = speaker("S1", personId = null, confirmed = false)
        val named = speaker("S2", "Tom", personId = "p2")
        val unnamed = speaker("S3", personId = null, confirmed = false)
        val byLabel = listOf(gone, named, unnamed).associateBy { it.label }
        val segs = listOf(
            TranscriptSegment(0, 1_000, "a", "Priya", "S1"),
            TranscriptSegment(1_000, 2_000, "b", "Thomas", "S2"),
            TranscriptSegment(2_000, 3_000, "c", "Speaker 3", "S3"),
            TranscriptSegment(3_000, 4_000, "d", "Priya Shah", "S1"),
            TranscriptSegment(4_000, 5_000, "e", "Lee", null),
        )
        val names = SpeakerTranscript.storedNames(segs, byLabel)
        assertEquals("only voices without a person, first name seen wins", mapOf("S1" to "Priya"), names)
        assertEquals("Priya", SpeakerTranscript.voice(gone, names["S1"]).name)
        assertEquals("Speaker 3", SpeakerTranscript.voice(unnamed, names["S3"]).name)
        assertEquals("a stored name never overrides a living person", "Tom", SpeakerTranscript.voice(named, "Thomas").name)

        // Filtering to that voice and jumping between its blocks still work by label.
        val only = SpeakerTranscript.rows(segs, "", byLabel, onlyKey = "S1")
        assertEquals(listOf(0, 3), only.map { it.match.index })
        assertEquals(listOf(true, true), only.map { it.startsGroup })
        assertEquals(listOf(0L, 3_000L), SpeakerTranscript.blockStarts("S1", segs, byLabel, emptyList()).toList())
    }

    // ------------------------------------------------------------------ naming sheet

    private val me = person("me", "Sarthak", isMe = true)
    private val priya = person("p1", "Priya Shah")
    private val people = listOf(me, priya)

    @Test
    fun skipSavesTheNamesAlreadyTypedAndDismissesOnlyTheRest() {
        // Before: "Skip" only called dismissNaming, throwing away names the user had typed.
        val speakers = listOf(speaker("S1"), speaker("S2"), speaker("S3"))
        var d = NamingPlan.initial(speakers, null)
        d = NamingPlan.edit(d, "S1", "Ana", people)
        d = NamingPlan.pick(d, "S2", priya)
        assertTrue(NamingPlan.hasChanges(speakers, d))
        assertEquals(
            listOf(NamingOp.Name("S1", "Ana", false), NamingOp.Assign("S2", "p1"), NamingOp.Dismiss),
            NamingPlan.skipOperations(speakers, d),
        )
        // Nothing typed: a plain dismissal, exactly once.
        assertEquals(listOf(NamingOp.Dismiss), NamingPlan.skipOperations(speakers, NamingPlan.initial(speakers, null)))
        // Everyone named: still dismisses (a no-op in the service) after saving, never twice.
        d = NamingPlan.edit(d, "S3", "Tom", people)
        val ops = NamingPlan.skipOperations(speakers, d)
        assertEquals(1, ops.count { it == NamingOp.Dismiss })
        assertEquals(NamingOp.Dismiss, ops.last())
    }

    @Test
    fun thisIsMeWithoutANameIsNotSilentlyIgnored() {
        val speakers = listOf(speaker("S1"), speaker("S2"))
        // No owner known yet: ticking "This is me" on an empty field has nothing to save...
        val ticked = NamingPlan.toggleMe(NamingPlan.initial(speakers, null), "S1", true)
        assertEquals("the plan itself can only dismiss", listOf(NamingOp.Dismiss), NamingPlan.operations(speakers, ticked))
        // ...so Done must ask for the name instead.
        assertEquals(setOf("S1"), NamingPlan.missingNames(speakers, ticked))
        assertTrue(NamingPlan.missingNames(speakers, NamingPlan.edit(ticked, "S1", "Sarthak", people)).isEmpty())
        assertTrue("untouched rows are never flagged", NamingPlan.missingNames(speakers, NamingPlan.initial(speakers, null)).isEmpty())
        // A confirmed voice whose owner tick moves is fine without typing (SetMe is enough).
        val confirmed = listOf(speaker("S1", "Priya Shah", personId = "p1"))
        val moved = NamingPlan.toggleMe(NamingPlan.initial(confirmed, null), "S1", true)
        assertTrue(NamingPlan.missingNames(confirmed, moved).isEmpty())
        assertEquals(listOf(NamingOp.SetMe("p1")), NamingPlan.operations(confirmed, moved))

        // With an owner on file the tick fills in their name, so the voice is saved as them.
        val prefilled = NamingPlan.toggleMe(NamingPlan.initial(speakers, "me"), "S2", true, owner = me)
        assertEquals(NamingDraft("Sarthak", "me", isMe = true, touched = true), prefilled["S2"])
        assertTrue(NamingPlan.missingNames(speakers, prefilled).isEmpty())
        assertEquals(listOf(NamingOp.Assign("S2", "me"), NamingOp.SetMe("me"), NamingOp.Dismiss), NamingPlan.operations(speakers, prefilled))
        // A name already typed is left alone.
        val typed = NamingPlan.toggleMe(NamingPlan.edit(NamingPlan.initial(speakers, "me"), "S2", "Ana", people), "S2", true, owner = me)
        assertEquals("Ana", typed["S2"]!!.text)
        assertNull(typed["S2"]!!.personId)
    }

    // ------------------------------------------------------------------ dock prompt

    private fun recording(id: String, status: RecordingStatus, endedAt: Long?) =
        Recording(id, "d1", id, emptyList(), emptyList(), 60_000, startedAt = (endedAt ?: 0L) - 60_000, endedAt = endedAt, status = status)

    @Test
    fun theDockPromptComesFromStoredRecordingsThatFinishedRecently() {
        // Before: the candidate was only a live Active -> idle transition seen in the same
        // composition, lost when the user left the board before identification finished, after
        // process death, and when the notification opened the document.
        val now = 1_000_000_000_000L
        val h = 60L * 60_000
        val recs = listOf(
            recording("old", RecordingStatus.COMPLETE, now - 25 * h),
            recording("earlier", RecordingStatus.COMPLETE, now - 3 * h),
            recording("latest", RecordingStatus.INTERRUPTED, now - 10 * 60_000),
            recording("live", RecordingStatus.RECORDING, null),
            recording("skewed", RecordingStatus.COMPLETE, now + 60_000),
        )
        assertEquals(listOf("skewed", "latest", "earlier"), NamingPromptLogic.recent(recs, now))
        assertEquals(listOf("skewed", "latest"), NamingPromptLogic.recent(recs, now, windowMs = h))
        assertTrue(NamingPromptLogic.recent(emptyList(), now).isEmpty())

        val recent = listOf("latest", "earlier")
        assertEquals("latest", NamingPromptLogic.pick(recent, mapOf("latest" to true, "earlier" to true), emptySet()))
        assertEquals("the most recent recording wins even when an older one also waits", "latest", NamingPromptLogic.pick(recent, mapOf("latest" to true, "earlier" to true), emptySet()))
        assertEquals("earlier", NamingPromptLogic.pick(recent, mapOf("latest" to false, "earlier" to true), emptySet()))
        assertEquals("closing the card moves on to the next one", "earlier", NamingPromptLogic.pick(recent, mapOf("latest" to true, "earlier" to true), setOf("latest")))
        assertNull(NamingPromptLogic.pick(recent, mapOf("latest" to false), emptySet()))
        assertNull(NamingPromptLogic.pick(emptyList(), emptyMap(), emptySet()))
    }

    // ------------------------------------------------------------------ enrollment

    @Test
    fun recordingAVoiceSampleWaitsForTheModelsAndRememberVoices() {
        // Before: enroll() returning false was always shown as "too little speech", also when it
        // had only queued the 32 MB model download, when Remember voices was off, or when the
        // person had been deleted.
        assertEquals(Gate.Ready, EnrollmentLogic.gate(SpeakerModelState.Ready, rememberVoices = true))
        assertEquals(Gate.VoicesOff, EnrollmentLogic.gate(SpeakerModelState.Ready, rememberVoices = false))
        assertEquals("voices off wins: no download is offered for nothing", Gate.VoicesOff, EnrollmentLogic.gate(SpeakerModelState.NotDownloaded, false))
        val downloading = SpeakerModelState.Downloading(8, 32)
        assertEquals(Gate.NeedsModels(SpeakerModelState.NotDownloaded), EnrollmentLogic.gate(SpeakerModelState.NotDownloaded, true))
        assertEquals(Gate.NeedsModels(downloading), EnrollmentLogic.gate(downloading, true))

        assertTrue(EnrollmentLogic.canStart(Gate.Ready))
        assertFalse(EnrollmentLogic.canStart(Gate.NeedsModels(SpeakerModelState.NotDownloaded)))
        assertFalse(EnrollmentLogic.canStart(Gate.VoicesOff))
        assertTrue(EnrollmentLogic.offersDownload(Gate.NeedsModels(SpeakerModelState.NotDownloaded)))
        assertTrue(EnrollmentLogic.offersDownload(Gate.NeedsModels(SpeakerModelState.Failed("offline"))))
        assertFalse("not while one is running", EnrollmentLogic.offersDownload(Gate.NeedsModels(downloading)))
        assertFalse(EnrollmentLogic.offersDownload(Gate.Ready))

        assertEquals(Phase.Saved, EnrollmentLogic.afterEnroll(true, Gate.Ready))
        assertEquals("only now does false mean too little speech", Phase.TooLittleSpeech, EnrollmentLogic.afterEnroll(false, Gate.Ready))
        assertEquals(Phase.Failed(Reason.MODELS_MISSING), EnrollmentLogic.afterEnroll(false, Gate.NeedsModels(SpeakerModelState.NotDownloaded)))
        assertEquals(Phase.Failed(Reason.VOICES_OFF), EnrollmentLogic.afterEnroll(false, Gate.VoicesOff))
        assertEquals(Phase.Failed(Reason.PERSON_GONE), EnrollmentLogic.afterEnroll(false, Gate.Ready, personExists = false))
    }

    @Test
    fun theModelsRowShowsWheneverAVoiceSampleCanBeRecorded() {
        val device = SpeakerPrefs(engine = SpeakerEngine.DEVICE)
        val openai = SpeakerPrefs(engine = SpeakerEngine.OPENAI)
        val off = SpeakerPrefs(engine = SpeakerEngine.OFF)
        assertTrue(SpeakerPrefsLogic.canEnroll(device))
        assertTrue("the voiceprint is made on the device whichever engine labels recordings", SpeakerPrefsLogic.canEnroll(openai))
        assertFalse(SpeakerPrefsLogic.canEnroll(off))
        assertFalse(SpeakerPrefsLogic.canEnroll(openai.copy(rememberVoices = false)))
        assertTrue(SpeakerPrefsLogic.showModels(device))
        assertTrue("before: hidden with OpenAI although enrollment needs the models", SpeakerPrefsLogic.showModels(openai))
        assertTrue("the device engine always needs them", SpeakerPrefsLogic.showModels(device.copy(rememberVoices = false)))
        assertFalse(SpeakerPrefsLogic.showModels(openai.copy(rememberVoices = false)))
        assertFalse(SpeakerPrefsLogic.showModels(off))
    }

    // ------------------------------------------------------------------ change speaker

    @Test
    fun typingAnUnnamedVoicesGenericNameMovesTheBlockToThatVoice() {
        // Before: "Speaker 2" typed in "Change speaker" created a person called "Speaker 2".
        val speakers = listOf(speaker("S1", "Priya Shah", personId = "p1"), speaker("S2"))
        assertEquals(BlockMove.ToVoice("S2"), SpeakerEdits.moveToName(speakers, people, "speaker 2"))
        assertEquals(BlockMove.ToVoice("S1"), SpeakerEdits.moveToName(speakers, people, "Priya Shah"))
        assertEquals(BlockMove.ToNewName("S3", "Speaker 7"), SpeakerEdits.moveToName(speakers, people, "Speaker 7"))
    }
}
