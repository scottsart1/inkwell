package com.inkwell.notes.audio.ui.panel

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.inkwell.notes.R
import com.inkwell.notes.app.AppGraph
import com.inkwell.notes.audio.RecordingState
import com.inkwell.notes.audio.logic.AudioText
import com.inkwell.notes.audio.logic.TranscriptAction
import com.inkwell.notes.audio.logic.TranscriptFilter
import com.inkwell.notes.audio.transcription.TranscriptPhase
import com.inkwell.notes.audio.ui.dock.RecordingDot
import com.inkwell.notes.core.model.Recording
import com.inkwell.notes.core.model.RecordingSpeaker
import com.inkwell.notes.core.model.RecordingStatus
import com.inkwell.notes.core.model.SpeakerEngine
import com.inkwell.notes.core.model.TranscriptStatus
import com.inkwell.notes.export.email.WorkEmailLauncher
import com.inkwell.notes.export.email.rememberWorkEmail
import com.inkwell.notes.speakers.ui.logic.LaneGeometry
import com.inkwell.notes.speakers.ui.logic.SpeakerLanes
import com.inkwell.notes.speakers.ui.logic.SpeakerTranscript
import com.inkwell.notes.ui.theme.Dimens
import com.inkwell.notes.ui.theme.Inkwell
import kotlinx.coroutines.delay

@Composable
internal fun RecordingsPanelImpl(
    docId: String,
    onShowNotesAt: (recordingId: String, positionMs: Long) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    onInsert: ((String) -> Unit)? = null,
) {
    val context = LocalContext.current
    val graph = remember(context) { AppGraph.get(context) }
    val vm: RecordingsViewModel = viewModel(key = "inkwell-recordings:$docId") { RecordingsViewModel(graph, docId) }
    val selected by vm.selected.collectAsStateWithLifecycle()
    val c = Inkwell.colors
    val phone = LocalConfiguration.current.screenWidthDp < 600
    val workEmail = rememberWorkEmail()

    BackHandler(enabled = selected != null) { vm.select(null) }

    BoxWithConstraints(modifier) {
        val width = if (phone) maxWidth else minOf(Dimens.panelWidth, maxWidth)
        Surface(Modifier.align(Alignment.TopEnd).width(width).fillMaxHeight(), color = c.surface) {
            Row(Modifier.fillMaxSize()) {
                if (!phone) VerticalDivider(color = c.outline, thickness = 1.dp)
                Box(Modifier.fillMaxSize().imePadding()) {
                    val rec = selected
                    if (rec == null) ListContent(vm, workEmail, onClose) else DetailContent(vm, rec, workEmail, onShowNotesAt, onClose)
                    PanelMessage(vm, Modifier.align(Alignment.BottomCenter))
                }
            }
        }
    }
    PanelDialogs(vm, onInsert)
}

@Composable
private fun PanelHeader(
    title: String,
    onClose: () -> Unit,
    onBack: (() -> Unit)? = null,
    trailing: @Composable () -> Unit = {},
) {
    val c = Inkwell.colors
    Row(
        Modifier.fillMaxWidth().height(Dimens.topBarHeight + 8.dp).padding(start = if (onBack != null) 4.dp else 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) AudioIconButton(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.audio_back_to_list), onBack, tint = c.ink)
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = c.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        trailing()
        AudioIconButton(Icons.Outlined.Close, stringResource(R.string.audio_close), onClose)
    }
    HorizontalDivider(color = c.outline, thickness = 1.dp)
}

/** The live recording at one-second resolution, so 20 Hz level updates don't recompose the panel. */
internal data class LiveInfo(val recordingId: String, val elapsedMs: Long, val paused: Boolean)

@Composable
private fun rememberLive(vm: RecordingsViewModel): State<LiveInfo?> {
    val state = vm.recordingState.collectAsStateWithLifecycle()
    return remember {
        derivedStateOf {
            (state.value as? RecordingState.Active)?.let { LiveInfo(it.recordingId, it.elapsedMs / 1000 * 1000, it.paused) }
        }
    }
}

@Composable
private fun ListContent(vm: RecordingsViewModel, workEmail: WorkEmailLauncher, onClose: () -> Unit) {
    val context = LocalContext.current
    val recordings by vm.recordings.collectAsStateWithLifecycle()
    val loaded by vm.loaded.collectAsStateWithLifecycle()
    val live by rememberLive(vm)
    val speakerPrefs by vm.speakerPrefs.collectAsStateWithLifecycle()
    val askNames = speakerPrefs.engine != SpeakerEngine.OFF && speakerPrefs.askToName
    Column(Modifier.fillMaxSize()) {
        PanelHeader(stringResource(R.string.audio_panel_title), onClose)
        when {
            !loaded -> Spacer(Modifier.weight(1f))
            recordings.isEmpty() -> EmptyState()
            else -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(recordings, key = { it.id }) { rec ->
                    val active = live?.takeIf { it.recordingId == rec.id }
                    val newVoices = if (askNames) {
                        val flow = remember(rec.id) { vm.needsNamingOf(rec.id) }
                        flow.collectAsStateWithLifecycle().value
                    } else {
                        false
                    }
                    RecordingRow(
                        rec = rec,
                        liveElapsedMs = active?.elapsedMs,
                        livePaused = active?.paused == true,
                        newVoices = newVoices && active == null,
                        renaming = vm.renamingId == rec.id,
                        exporting = vm.exportingId == rec.id,
                        onOpen = { vm.select(rec.id) },
                        onRename = { vm.rename(rec, it) },
                        onStartRename = { vm.renamingId = rec.id },
                        onCancelRename = { vm.renamingId = null },
                        onExport = { vm.export(rec) { intent -> launchShare(context, intent, vm) } },
                        onDelete = { vm.requestDelete(rec) },
                        onEmail = { workEmail.sendRecording(rec.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyState() {
    val c = Inkwell.colors
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(48.dp).clip(R12).background(c.surfaceAlt), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.GraphicEq, null, Modifier.size(Dimens.icon), tint = c.inkMuted)
        }
        Text(stringResource(R.string.audio_empty_title), style = MaterialTheme.typography.titleSmall, color = c.ink)
        Text(
            stringResource(R.string.audio_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = c.inkMuted,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@Composable
private fun DetailContent(
    vm: RecordingsViewModel,
    rec: Recording,
    workEmail: WorkEmailLauncher,
    onShowNotesAt: (String, Long) -> Unit,
    onClose: () -> Unit,
) {
    val c = Inkwell.colors
    val context = LocalContext.current
    val live by rememberLive(vm)
    val playback by vm.playback.collectAsStateWithLifecycle()
    val waveform by vm.waveform.collectAsStateWithLifecycle()
    val availability by vm.availability.collectAsStateWithLifecycle()
    val transcript by vm.transcript.collectAsStateWithLifecycle()
    val rows by vm.rows.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val work by vm.work.collectAsStateWithLifecycle()
    val speakerPrefs by vm.speakerPrefs.collectAsStateWithLifecycle()
    val speakers by vm.speakers.collectAsStateWithLifecycle()
    val turns by vm.turns.collectAsStateWithLifecycle()
    val shares by vm.shares.collectAsStateWithLifecycle()
    val diarization by vm.diarization.collectAsStateWithLifecycle()
    val models by vm.modelState.collectAsStateWithLifecycle()
    val speakerFilter by vm.speakerFilter.collectAsStateWithLifecycle()
    val speakerNote by vm.speakerNote.collectAsStateWithLifecycle()
    val needsNaming by vm.needsNaming.collectAsStateWithLifecycle()
    val storedNames by vm.storedNames.collectAsStateWithLifecycle()
    val active = live?.takeIf { it.recordingId == rec.id }
    val phase = TranscriptPhase.of(rec.transcriptStatus, work)
    val loaded = playback.recording?.id == rec.id
    val activeIndex = if (loaded) TranscriptFilter.activeIndex(transcript, playback.positionMs) else -1
    val speakerState = SpeakerSectionState(speakerPrefs, speakers, shares, diarization, models, speakerFilter, speakerNote, storedNames)
    // Lanes share the waveform's timeline; rebuilt only when turns, voices or the duration change.
    val laneDuration = (if (loaded && playback.durationMs > 0) playback.durationMs else rec.durationMs).coerceAtLeast(0)
    val lanes = remember(turns, speakers, laneDuration, speakerState.enabled) {
        if (!speakerState.enabled) LaneGeometry.EMPTY
        else {
            val colors = speakers.associate { it.label to it.colorIndex }
            SpeakerLanes.build(turns, laneDuration, { label -> colors[label] ?: ((SpeakerTranscript.labelNumber(label) ?: 1) - 1) })
        }
    }
    val lanesDescription = lanesDescription(speakers, shares, storedNames)
    val unnamed = SpeakerTranscript.unnamedCount(speakers)
    val hasSpeakerNames = remember(transcript) { transcript.any { !it.speaker.isNullOrBlank() } }
    val showBanner = active == null && needsNaming && unnamed > 0 && speakerState.enabled && speakerPrefs.askToName

    Column(Modifier.fillMaxSize()) {
        if (vm.renamingId == rec.id) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                RenameField(rec.title, onRename = { vm.rename(rec, it) }, onCancel = { vm.renamingId = null })
            }
            HorizontalDivider(color = c.outline, thickness = 1.dp)
        } else {
            PanelHeader(
                title = rec.title,
                onClose = onClose,
                onBack = { vm.select(null) },
                trailing = {
                    RecordingMenu(
                        exporting = vm.exportingId == rec.id,
                        canExport = active == null && rec.segmentFiles.isNotEmpty(),
                        onRename = { vm.renamingId = rec.id },
                        onExport = { vm.export(rec) { intent -> launchShare(context, intent, vm) } },
                        onDelete = { vm.requestDelete(rec) },
                        onEmail = { workEmail.sendRecording(rec.id) }.takeIf { active == null && rec.transcriptStatus == TranscriptStatus.DONE },
                    )
                },
            )
        }

        BoxWithConstraints(Modifier.fillMaxSize()) {
            val fixedPlayer = maxHeight >= 600.dp
            val player: @Composable () -> Unit = {
                PlayerCard(
                    recording = rec,
                    playback = playback,
                    waveform = waveform,
                    onTogglePlay = { vm.togglePlay(rec) },
                    onSeek = { vm.seek(rec, it) },
                    onSkip = { vm.skip(rec, it) },
                    onSpeed = { vm.setSpeed(it) },
                    onShowNotes = { onShowNotesAt(rec.id, vm.positionFor(rec)) },
                    lanes = lanes,
                    lanesDescription = lanesDescription,
                    focusLabel = speakerFilter.takeIf { speakerState.enabled },
                )
            }
            Column(Modifier.fillMaxSize()) {
                if (showBanner) {
                    NamingBanner(unnamed, onName = { vm.namingFor = rec.id }, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp))
                }
                if (active == null && fixedPlayer) {
                    Box(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp)) { player() }
                }
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = if (active == null && fixedPlayer) 4.dp else 16.dp, bottom = 72.dp),
                ) {
                    item(key = "meta") {
                        Column(Modifier.padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            RecordingMeta(rec, active?.elapsedMs)
                            if (rec.status == RecordingStatus.INTERRUPTED) {
                                Text(stringResource(R.string.audio_recovered_hint), style = MaterialTheme.typography.bodySmall, color = c.inkMuted)
                            }
                        }
                    }
                    if (active != null) {
                        item(key = "live") { LiveCard(active) }
                    } else {
                        if (!fixedPlayer) item(key = "player") { player() }
                        speakerItems(vm, rec, speakerState)
                        transcriptItems(
                            vm = vm,
                            rec = rec,
                            phase = phase,
                            availability = availability,
                            hasTranscript = transcript.isNotEmpty(),
                            rows = rows,
                            query = query,
                            activeIndex = activeIndex,
                            onSegment = { index ->
                                if (vm.pickingPassage) vm.runAction(TranscriptAction.EXPLAIN, index)
                                else transcript.getOrNull(index)?.let { vm.seek(rec, it.startMs, play = true) }
                            },
                            speakersEnabled = speakerState.enabled,
                            filtered = speakerState.enabled && speakerFilter != null,
                            hasSpeakerNames = hasSpeakerNames,
                            onChangeSpeaker = { vm.changeSpeaker(rec, it) },
                        )
                    }
                }
            }
        }
    }
}

/** "Who spoke when. Priya 45%, Speaker 2 30%, ..." for the speaker lanes. */
@Composable
private fun lanesDescription(speakers: List<RecordingSpeaker>, shares: Map<String, Int>, storedNames: Map<String, String>): String {
    val small = stringResource(R.string.spk_share_small)
    val parts = speakers.map { s ->
        val share = shares[s.label]?.let { if (it == 0) small else stringResource(R.string.spk_share, it) }.orEmpty()
        stringResource(R.string.spk_lanes_entry, SpeakerTranscript.voice(s, storedNames[s.label]).name, share).trim()
    }
    return stringResource(R.string.spk_lanes_cd, parts.joinToString(", "))
}

@Composable
private fun LiveCard(active: LiveInfo) {
    val c = Inkwell.colors
    Column(
        Modifier.fillMaxWidth().clip(R12).border(1.dp, c.outline, R12).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RecordingDot(paused = active.paused)
            Spacer(Modifier.width(10.dp))
            Text(
                stringResource(R.string.audio_live_title, AudioText.clock(active.elapsedMs)),
                style = MaterialTheme.typography.titleSmall.copy(fontFeatureSettings = "tnum"),
                color = c.ink,
            )
        }
        Text(stringResource(R.string.audio_live_body), style = MaterialTheme.typography.bodyMedium, color = c.inkMuted)
    }
}

@Composable
private fun PanelMessage(vm: RecordingsViewModel, modifier: Modifier) {
    val c = Inkwell.colors
    val message = vm.message
    LaunchedEffect(message) {
        if (message != null) {
            delay(4_500)
            vm.message = null
        }
    }
    AnimatedVisibility(visible = message != null, enter = fadeIn(), exit = fadeOut(), modifier = modifier.padding(16.dp)) {
        Row(
            Modifier.clip(R12).background(c.ink).padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                message.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = c.surface,
                modifier = Modifier.weight(1f, fill = false).padding(vertical = 12.dp),
            )
            AudioIconButton(Icons.Outlined.Close, stringResource(R.string.audio_dismiss), { vm.message = null }, tint = c.surface)
        }
    }
}

private fun launchShare(context: Context, intent: Intent, vm: RecordingsViewModel) {
    try {
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        vm.message = context.getString(R.string.audio_export_failed)
    } catch (_: SecurityException) {
        vm.message = context.getString(R.string.audio_export_failed)
    }
}
