package com.inkwell.notes.audio.ui.dock

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.inkwell.notes.R
import com.inkwell.notes.app.AppGraph
import com.inkwell.notes.audio.RecordingState
import com.inkwell.notes.audio.logic.AudioText
import com.inkwell.notes.audio.logic.DockMetrics
import com.inkwell.notes.audio.logic.DockMode
import com.inkwell.notes.audio.logic.DockPlacement
import com.inkwell.notes.audio.logic.DockStateMachine
import com.inkwell.notes.audio.recorder.RecordingRuntime
import com.inkwell.notes.core.model.DockSide
import com.inkwell.notes.ui.theme.Dimens
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private data class ActiveKey(val recordingId: String, val docId: String, val paused: Boolean)

/**
 * Overlay for the editor. It has no pointer handling of its own: only the capsule, the sliver
 * or the small "name the new voices" card consume touches, so ink, scrolling and every other
 * control underneath keep working.
 */
@Composable
internal fun RecordingDockImpl(docId: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val graph = remember(context) { AppGraph.get(context) }
    val recording = graph.recording.state.collectAsStateWithLifecycle()
    val active by remember { derivedStateOf { (recording.value as? RecordingState.Active)?.let { ActiveKey(it.recordingId, it.docId, it.paused) } } }
    val error by remember { derivedStateOf { recording.value as? RecordingState.Error } }
    val errorReport by RecordingRuntime.errorReport.collectAsStateWithLifecycle()

    // Only one dock shows the recorder when two editors are on screen (split view).
    val token = remember { Any() }
    DisposableEffect(token, docId) {
        DockRegistry.attach(token, docId)
        onDispose { DockRegistry.detach(token) }
    }

    RequestRecordingNotificationPermission(recordingActive = active != null && DockRegistry.isPrimary(token))

    BoxWithConstraints(modifier.fillMaxSize()) {
        val a = active
        if (a != null && DockRegistry.shows(token, docId, a.docId)) {
            key(a.recordingId) { DockedControl(graph, a, recording, otherDocument = a.docId != docId) }
        }
        val e = error
        if (e != null && DockRegistry.isPrimary(token)) DockErrorToast(e, errorReport)
        // After a recording of this document: offer to name its new voices (one dock per document).
        if (DockRegistry.shows(token, docId, docId)) DockNamingPrompt(graph, docId, recording)
    }
}

@Composable
private fun BoxWithConstraintsScope.DockedControl(
    graph: AppGraph,
    active: ActiveKey,
    recording: State<RecordingState>,
    otherDocument: Boolean,
) {
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val settings by graph.settings.settings.collectAsStateWithLifecycle()

    // Unbounded constraints (an unusual host) fall back to the window size.
    val window = LocalConfiguration.current
    val widthPx = if (constraints.hasBoundedWidth) constraints.maxWidth.toFloat() else with(density) { window.screenWidthDp.dp.toPx() }
    val heightPx = if (constraints.hasBoundedHeight) constraints.maxHeight.toFloat() else with(density) { window.screenHeightDp.dp.toPx() }
    val metrics = remember(widthPx, heightPx, density) {
        with(density) {
            DockMetrics(
                containerWidth = widthPx,
                containerHeight = heightPx,
                capsuleWidth = CapsuleWidth.toPx(),
                capsuleHeight = CapsuleHeight.toPx(),
                sliverTouchHeight = SliverTouchHeight.toPx(),
                margin = 12.dp.toPx(),
                collapseDistance = 32.dp.toPx(),
                expandDistance = 16.dp.toPx(),
                flingVelocity = 700.dp.toPx(),
            )
        }
    }
    val touchW = with(density) { Dimens.recorderSliverTouch.toPx() }
    val hiddenExtra = with(density) { 24.dp.toPx() }
    val sliverInwardMax = with(density) { 48.dp.toPx() }

    var placement by remember {
        val side = settings.recording.dockSide
        mutableStateOf(
            DockMemory.modeFor(active.recordingId)?.let { DockPlacement(side, it) }
                ?: DockStateMachine.initial(side, settings.recording.startCollapsed),
        )
    }
    var centerY by remember { mutableFloatStateOf(DockStateMachine.clampForBoth(DockMemory.centerFraction * heightPx, metrics)) }
    fun hiddenX(side: DockSide) = DockStateMachine.capsuleHiddenX(side, metrics) +
        if (side == DockSide.RIGHT) hiddenExtra else -hiddenExtra
    fun restX(side: DockSide) = DockStateMachine.capsuleRestX(side, metrics)

    val capsuleX = remember { Animatable(if (placement.collapsed) hiddenX(placement.side) else restX(placement.side)) }
    /** Sliver shift outward from its resting place: 0 = shown, [touchW] = hidden. */
    val sliverShift = remember { Animatable(if (placement.collapsed) 0f else touchW) }
    var dragX by remember { mutableFloatStateOf(0f) }
    var sliverDragX by remember { mutableFloatStateOf(0f) }
    var capsuleVisible by remember { mutableStateOf(!placement.collapsed) }
    var sliverVisible by remember { mutableStateOf(placement.collapsed) }
    var sliverSide by remember { mutableStateOf(placement.side) }
    var dragging by remember { mutableStateOf(false) }

    val motion = spring<Float>(dampingRatio = 0.86f, stiffness = Spring.StiffnessMediumLow * 1.6f)

    suspend fun animateTo(target: DockPlacement) = coroutineScope {
        if (target.mode == DockMode.EXPANDED) {
            capsuleVisible = true
            if (sliverVisible) {
                launch {
                    sliverShift.animateTo(touchW, motion)
                    if (placement.mode == DockMode.EXPANDED) sliverVisible = false
                }
            }
            val from = capsuleX.value
            val offscreen = from <= -metrics.capsuleWidth || from >= metrics.containerWidth
            if (offscreen) capsuleX.snapTo(hiddenX(target.side))
            capsuleX.animateTo(restX(target.side), motion)
        } else {
            sliverSide = target.side
            sliverVisible = true
            launch { sliverShift.animateTo(0f, motion) }
            capsuleX.animateTo(hiddenX(target.side), motion)
            if (placement.mode == DockMode.COLLAPSED) capsuleVisible = false
        }
    }

    fun settle(target: DockPlacement) {
        val previous = placement
        placement = target
        DockMemory.remember(active.recordingId, target.mode, centerY / heightPx.coerceAtLeast(1f))
        if (target.side != previous.side) {
            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
            graph.appScope.launch { runCatching { graph.settings.update { it.copy(recording = it.recording.copy(dockSide = target.side)) } } }
        }
        // Collapsing or expanding is remembered for this recording only (DockMemory). The
        // "Start collapsed" setting is the user's choice for new recordings and is never
        // rewritten here: stopping from the expanded capsule would otherwise reset it every time.
        if (target.mode != previous.mode) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
        scope.launch {
            // Continue from where the finger left the control.
            if (dragX != 0f) {
                capsuleX.snapTo(capsuleX.value + dragX)
                dragX = 0f
            }
            if (sliverDragX != 0f) {
                val sign = if (sliverSide == DockSide.RIGHT) 1f else -1f
                sliverShift.snapTo(sliverShift.value + sliverDragX * sign)
                sliverDragX = 0f
            }
            animateTo(target)
        }
    }

    // Rotation, split-screen or a resized editor: keep the control on its edge and on screen.
    LaunchedEffect(metrics) {
        centerY = DockStateMachine.clampForBoth(DockMemory.centerFraction * heightPx, metrics)
        if (!dragging && !capsuleX.isRunning) {
            capsuleX.snapTo(if (placement.collapsed) hiddenX(placement.side) else restX(placement.side))
        }
    }

    // Dock side changed in Settings while recording: move over.
    val preferredSide = settings.recording.dockSide
    LaunchedEffect(preferredSide) {
        if (preferredSide != placement.side && !dragging) settle(placement.copy(side = preferredSide))
    }

    val state = remember { derivedStateOf { recording.value as? RecordingState.Active } }
    val timeText by remember { derivedStateOf { AudioText.clock(state.value?.elapsedMs ?: 0L) } }

    if (capsuleVisible) {
        val capsuleH = metrics.capsuleHeight
        DockCapsule(
            side = placement.side,
            paused = active.paused,
            otherDocument = otherDocument,
            time = { timeText },
            level = { state.value?.level ?: 0f },
            onPauseResume = { if (active.paused) graph.recording.resume() else graph.recording.pause() },
            onStop = {
                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                graph.recording.stop()
            },
            onCollapse = { settle(DockStateMachine.onChevron(placement)) },
            modifier = Modifier
                .offset {
                    val x = (capsuleX.value + dragX).coerceIn(-metrics.capsuleWidth - hiddenExtra * 2, metrics.containerWidth + hiddenExtra * 2)
                    IntOffset(x.roundToInt(), (centerY - capsuleH / 2f).roundToInt())
                }
                .pointerInput(metrics) {
                    val tracker = VelocityTracker()
                    var total = Offset.Zero
                    var startCenter = 0f
                    detectDragGestures(
                        onDragStart = {
                            dragging = true
                            tracker.resetTracking()
                            total = Offset.Zero
                            startCenter = centerY
                            scope.launch { capsuleX.stop() }
                        },
                        onDrag = { change, amount ->
                            change.consume()
                            total += amount
                            tracker.addPosition(change.uptimeMillis, total)
                            dragX = total.x
                            centerY = DockStateMachine.clampForBoth(startCenter + total.y, metrics)
                        },
                        onDragEnd = {
                            dragging = false
                            val v = tracker.calculateVelocity()
                            settle(DockStateMachine.onCapsuleDragEnd(placement, metrics, total.x, v.x, v.y))
                        },
                        onDragCancel = {
                            dragging = false
                            settle(placement)
                        },
                    )
                },
        )
    }

    if (sliverVisible) {
        val expandLabel = stringResource(R.string.audio_dock_expand)
        DockSliver(
            side = sliverSide,
            paused = active.paused,
            modifier = Modifier
                .offset {
                    val sign = if (sliverSide == DockSide.RIGHT) 1f else -1f
                    val base = if (sliverSide == DockSide.RIGHT) metrics.containerWidth - touchW else 0f
                    val x = base + sliverShift.value * sign + sliverDragX
                    IntOffset(x.roundToInt(), (centerY - metrics.sliverTouchHeight / 2f).roundToInt())
                }
                .pointerInput(metrics) {
                    val tracker = VelocityTracker()
                    var total = Offset.Zero
                    var startCenter = 0f
                    detectDragGestures(
                        onDragStart = {
                            dragging = true
                            tracker.resetTracking()
                            total = Offset.Zero
                            startCenter = centerY
                        },
                        onDrag = { change, amount ->
                            change.consume()
                            total += amount
                            tracker.addPosition(change.uptimeMillis, total)
                            // Follows the finger inward a little for feedback; never past the edge.
                            sliverDragX = if (sliverSide == DockSide.RIGHT) total.x.coerceIn(-sliverInwardMax, 0f)
                            else total.x.coerceIn(0f, sliverInwardMax)
                            centerY = DockStateMachine.clampForBoth(startCenter + total.y, metrics)
                        },
                        onDragEnd = {
                            dragging = false
                            val v = tracker.calculateVelocity()
                            settle(DockStateMachine.onSliverDragEnd(placement, metrics, total.x, v.x, v.y))
                        },
                        onDragCancel = {
                            dragging = false
                            settle(placement)
                        },
                    )
                }
                .clickable(role = Role.Button, onClickLabel = expandLabel) { settle(DockStateMachine.onSliverTap(placement)) }
                .semantics { contentDescription = expandLabel },
        )
    }
}
