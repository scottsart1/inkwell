package com.inkwell.notes.ink.input

import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.TypedValue
import android.view.InputDevice
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import androidx.input.motionprediction.MotionEventPredictor
import com.inkwell.notes.core.model.InputPrefs
import com.inkwell.notes.core.model.LongPressAction
import com.inkwell.notes.core.model.SideButtonMode
import com.inkwell.notes.core.model.TapGestureAction
import com.inkwell.notes.core.model.ToolState
import com.inkwell.notes.core.model.ToolType
import com.inkwell.notes.ink.engine.InkEngine
import com.inkwell.notes.ink.geometry.Geo
import com.inkwell.notes.ink.model.Items
import com.inkwell.notes.ink.tools.InkTool
import com.inkwell.notes.ink.tools.ToolEvent
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * Turns MotionEvents into tool gestures and navigation.
 *
 * Stylus (TOOL_TYPE_STYLUS / TOOL_TYPE_ERASER): unbuffered dispatch, every historical sample,
 * optional motion prediction, and the side-button state machine deciding the effective tool on
 * every event (switching tools mid-contact). Fingers: navigation (or ink/select when allowed),
 * pinch and pan, multi-finger taps and long press. Palm rejection: large contacts are ignored,
 * fingers are blocked while the stylus is near (except deliberate two-finger gestures with
 * zoom-while-writing), cancelled pointers leave no marks.
 */
internal class InputRouter(private val e: InkEngine) {
    private val view get() = e.view
    private val handler = Handler(Looper.getMainLooper())
    private val config = ViewConfiguration.get(e.context)
    private val touchSlop = config.scaledTouchSlop.toFloat()
    private val minFling = config.scaledMinimumFlingVelocity.toFloat()
    private val maxFling = config.scaledMaximumFlingVelocity.toFloat()
    private val longPressMs = ViewConfiguration.getLongPressTimeout().toLong()
    private val tapTimeoutMs = 320L

    val policy = TouchPolicy().apply {
        palmMajorPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_MM, TouchPolicy.PALM_MM, e.context.resources.displayMetrics)
    }
    val buttons = StylusButtonStateMachine()
    private val taps = MultiFingerTapTracker(tapTimeoutMs, touchSlop * 1.5f)
    private val ev = ToolEvent()
    private val predEv = ToolEvent()
    private val tmp = FloatArray(2)
    private var predictor: MotionEventPredictor? = null

    // ---- Stylus state
    private var stylusId = -1
    private var stylusTool: InkTool? = null
    private var stylusToolType: ToolType? = null
    private var stylusPage = -1
    private var stylusIsFinger = false
    private var stylusIsMouse = false
    private var stylusConsumed = false
    private var stylusDownX = 0f
    private var stylusDownY = 0f
    private var stylusMoved = false
    private var lastSX = 0f
    private var lastSY = 0f
    private var lastSPressure = 0f
    private var lastSTilt = 0f
    private var lastSTime = 0L
    private var stylusHovering = false
    private var stylusLastSeen = 0L
    private var stylusDetected = false
    private var lastKind = PointerKind.STYLUS

    // ---- Finger state
    private enum class Mode { NONE, PENDING, SCROLL, TOOL, MULTI, IGNORED, CONSUMED }
    private class Finger(val id: Int, val downX: Float, val downY: Float, val downTime: Long) {
        var x = downX
        var y = downY
        var palm = false
    }
    private var mode = Mode.NONE
    private val fingers = LinkedHashMap<Int, Finger>()
    private var firstFingerTime = 0L
    private var fingerTool: InkTool? = null
    private var fingerToolStarted = false
    private var fingerToolPage = -1
    private class Sample(val x: Float, val y: Float, val t: Long)
    private val pendingSamples = ArrayList<Sample>()
    private var lastFocusX = 0f
    private var lastFocusY = 0f
    private var lastSpan = 0f
    private var velocity: VelocityTracker? = null

    // ---- Hover
    var hoverX = 0f
        private set
    var hoverY = 0f
        private set
    var hoverActive = false
        private set
    private var exclusionCenter = Float.NaN

    // ------------------------------------------------------------------ Configuration

    fun onPrefsChanged(prefs: InputPrefs) {
        policy.prefs = prefs
        buttons.mode = prefs.sideButton
        apply(buttons.refresh())
        if (!prefs.motionPrediction) predictor = null
    }

    fun onToolStateChanged(state: ToolState) {
        buttons.setBaseTool(state.tool)
        e.invalidateOverlay()
    }

    /** Effective stylus tool right now (temporary side-button tool or the selected one). */
    val effectiveTool: ToolType get() = buttons.effectiveTool

    /** Tools with a gesture in progress (for overlay drawing). */
    fun activeTools(): List<InkTool> = listOfNotNull(stylusTool, fingerTool?.takeIf { fingerToolStarted })

    val stylusInContact: Boolean get() = stylusId != -1

    private fun stylusNear(): Boolean =
        stylusId != -1 || stylusHovering || SystemClock.uptimeMillis() - stylusLastSeen < TouchPolicy.STYLUS_LINGER_MS

    // ------------------------------------------------------------------ Touch

    fun onTouchEvent(event: MotionEvent): Boolean {
        try {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    view.parent?.requestDisallowInterceptTouchEvent(true)
                    // With a physical keyboard (DeX) the canvas takes focus so shortcuts reach it.
                    if (!view.isFocused && (e.keys.hasHardwareKeyboard() || event.isFromSource(InputDevice.SOURCE_MOUSE))) view.requestFocus()
                    pointerDown(event, 0)
                }
                MotionEvent.ACTION_POINTER_DOWN -> pointerDown(event, event.actionIndex)
                MotionEvent.ACTION_MOVE -> move(event)
                MotionEvent.ACTION_POINTER_UP -> pointerUp(event, event.actionIndex, isCanceled(event))
                MotionEvent.ACTION_UP -> {
                    pointerUp(event, 0, isCanceled(event))
                    endSequence()
                }
                MotionEvent.ACTION_CANCEL -> cancelAll()
            }
        } catch (t: Throwable) {
            // Input handling must never take the app down; drop the gesture instead.
            Log.e(TAG, "Input handling failed", t)
            cancelAll()
        }
        return true
    }

    private fun isCanceled(event: MotionEvent): Boolean = (event.flags and FLAG_CANCELED) != 0

    private fun kindOf(event: MotionEvent, index: Int): PointerKind = when (event.getToolType(index)) {
        MotionEvent.TOOL_TYPE_STYLUS -> PointerKind.STYLUS
        MotionEvent.TOOL_TYPE_ERASER -> PointerKind.ERASER_TIP
        MotionEvent.TOOL_TYPE_FINGER -> PointerKind.FINGER
        MotionEvent.TOOL_TYPE_MOUSE -> PointerKind.MOUSE
        else -> PointerKind.UNKNOWN
    }

    private fun pointerDown(event: MotionEvent, index: Int) {
        when (val kind = kindOf(event, index)) {
            PointerKind.STYLUS, PointerKind.ERASER_TIP -> stylusDown(event, index, kind)
            PointerKind.MOUSE -> {
                if ((event.buttonState and MotionEvent.BUTTON_PRIMARY) != 0 || event.buttonState == 0) stylusDown(event, index, kind)
                else fingerDown(event, index, mouse = true)
            }
            else -> fingerDown(event, index, mouse = false)
        }
    }

    private fun move(event: MotionEvent) {
        if (stylusId != -1) {
            val idx = event.findPointerIndex(stylusId)
            if (idx >= 0) stylusMove(event, idx)
        }
        if (fingers.isNotEmpty()) fingerMove(event)
    }

    private fun pointerUp(event: MotionEvent, index: Int, canceled: Boolean) {
        val id = event.getPointerId(index)
        if (id == stylusId) stylusUp(event, index, canceled) else fingerUp(event, index, canceled)
    }

    private fun endSequence() {
        if (stylusId != -1) { stylusTool?.onCancel(e); clearStylusGesture() }
        resetFingers()
    }

    /** Cancels everything in progress without leaving marks. */
    fun cancelAll() {
        handler.removeCallbacks(longPress)
        stylusTool?.let { try { it.onCancel(e) } catch (t: Throwable) { Log.e(TAG, "Tool cancel failed", t) } }
        clearStylusGesture()
        fingerTool?.let { if (fingerToolStarted) it.onCancel(e) }
        if (mode == Mode.MULTI) e.viewportCtl.endPinch()
        resetFingers()
    }

    /** Called after flush(): forget gestures whose tools were committed. */
    fun endGestures() {
        if (stylusTool?.isActive == false) { stylusTool = null; stylusToolType = null; stylusConsumed = true }
        if (fingerTool?.isActive == false) { fingerTool = null; fingerToolStarted = false; mode = Mode.CONSUMED }
    }

    fun onViewportChanged() {
        stylusTool?.onViewportChanged(e)
        if (fingerToolStarted) fingerTool?.onViewportChanged(e)
    }

    // ------------------------------------------------------------------ Stylus

    private fun stylusDown(event: MotionEvent, index: Int, kind: PointerKind) {
        if (stylusId != -1) return
        stylusId = event.getPointerId(index)
        stylusIsMouse = kind == PointerKind.MOUSE
        stylusIsFinger = false
        stylusConsumed = false
        lastKind = kind
        if (!stylusIsMouse) {
            view.requestUnbufferedDispatch(event)
            markStylusNear(event.getY(index))
            if (!stylusDetected) { stylusDetected = true; e.listener?.onStylusDetected() }
        }
        val r = buttons.onEvent(StylusPhase.DOWN, if (stylusIsMouse) PointerKind.MOUSE else kind, event.buttonState, event.eventTime)
        apply(r)
        val type = if (stylusIsMouse) e.toolState.tool else r.effectiveTool
        stylusDownX = event.getX(index); stylusDownY = event.getY(index)
        stylusMoved = false
        recordLast(event, index)
        stylusPage = e.pageIndexAt(stylusDownX, stylusDownY)
        if (stylusPage < 0) { stylusConsumed = true; return }
        if (type != ToolType.LASSO && type != ToolType.HAND) e.viewportCtl.stop()
        startStylusTool(type)
        if (e.inputPrefs.motionPrediction && !stylusIsMouse && stylusTool?.isInking == true) {
            ensurePredictor()?.let { p -> try { p.record(event) } catch (t: Throwable) { predictor = null } }
        }
        if (allowsStylusLongPress(type, stylusTool?.isInking == true)) handler.postDelayed(longPress, longPressMs)
    }

    private fun startStylusTool(type: ToolType) {
        // A contact on the selection box moves, resizes or rotates it with any tool but the eraser.
        val onSelection = type != ToolType.ERASER && e.selection.hit(lastSX, lastSY) != null
        val tool = if (onSelection) e.selectionTool else e.tool(type)
        stylusTool = tool
        stylusToolType = type
        fillSingle(ev, stylusPage, lastSX, lastSY, lastSPressure, lastSTilt, lastSTime, stylus = !stylusIsMouse)
        tool.onDown(e, ev)
    }

    private fun stylusMove(event: MotionEvent, idx: Int) {
        if (!stylusIsMouse) {
            val r = buttons.onEvent(StylusPhase.MOVE, kindOf(event, idx), event.buttonState, event.eventTime)
            apply(r)
            if (!stylusConsumed && r.effectiveTool != stylusToolType) {
                // Side button pressed or released mid-contact: commit and continue with the new tool.
                switchStylusTool(r.effectiveTool)
            }
            markStylusNear(event.getY(idx))
        }
        if (stylusConsumed) return
        val tool = stylusTool ?: return
        val predict = e.inputPrefs.motionPrediction && !stylusIsMouse && tool.isInking
        fillBatch(ev, event, idx, stylusPage, stylus = !stylusIsMouse)
        ev.willPredict = predict
        recordLast(event, idx)
        if (!stylusMoved && Geo.dist(stylusDownX, stylusDownY, lastSX, lastSY) > touchSlop) {
            stylusMoved = true
            handler.removeCallbacks(longPress)
        }
        tool.onMove(e, ev)
        if (predict) predictAndDispatch(event, tool)
    }

    private fun predictAndDispatch(event: MotionEvent, tool: InkTool) {
        val p = ensurePredictor()
        predEv.reset(stylusPage)
        predEv.isStylus = true
        if (p != null) {
            try {
                p.record(event)
                val predicted = p.predict()
                if (predicted != null) {
                    try {
                        var pi = predicted.findPointerIndex(stylusId)
                        if (pi < 0 && predicted.pointerCount == 1) pi = 0
                        if (pi >= 0) {
                            for (h in 0 until predicted.historySize) addSample(predEv, predicted.getHistoricalX(pi, h), predicted.getHistoricalY(pi, h), 0f, 0f, predicted.getHistoricalEventTime(h))
                            addSample(predEv, predicted.getX(pi), predicted.getY(pi), 0f, 0f, predicted.eventTime)
                        }
                    } finally {
                        predicted.recycle()
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Motion prediction disabled", t)
                predictor = null
            }
        }
        tool.onPredicted(e, predEv)
    }

    private fun ensurePredictor(): MotionEventPredictor? {
        if (predictor == null) {
            predictor = try { MotionEventPredictor.newInstance(view) } catch (t: Throwable) { null }
        }
        return predictor
    }

    private fun switchStylusTool(newType: ToolType) {
        val cur = stylusTool
        handler.removeCallbacks(longPress)
        if (keepsSelectionManipulation(cur === e.selectionTool, newType)) {
            // The selection tool works the same under any tool but the eraser: keep the running
            // move/resize/rotate (or pending tap) instead of committing and hit-testing again,
            // which would ink from a rotate handle that moved, or count one press as a double tap.
            stylusToolType = newType
            return
        }
        if (cur != null) {
            fillSingle(ev, stylusPage, lastSX, lastSY, lastSPressure, lastSTilt, lastSTime, stylus = true)
            cur.onUp(e, ev)
        }
        if (endsContactOnLassoRelease(stylusToolType, buttons.mode, buttons.temporaryTool)) {
            // Hold-to-lasso button released before lifting: the loop is done. Continuing with the
            // base tool would clear the new selection and draw a stray stroke to the lift point.
            stylusTool = null
            stylusToolType = null
            stylusConsumed = true
            return
        }
        startStylusTool(newType)
    }

    private fun stylusUp(event: MotionEvent, index: Int, canceled: Boolean) {
        handler.removeCallbacks(longPress)
        if (!stylusIsMouse) {
            apply(buttons.onEvent(StylusPhase.UP, kindOf(event, index), event.buttonState, event.eventTime))
            stylusLastSeen = SystemClock.uptimeMillis()
        }
        val tool = stylusTool
        if (tool != null && !stylusConsumed) {
            if (canceled) tool.onCancel(e) else {
                fillBatch(ev, event, index, stylusPage, stylus = !stylusIsMouse)
                tool.onUp(e, ev)
            }
        }
        try { predictor?.record(event) } catch (t: Throwable) { predictor = null }
        clearStylusGesture()
        // Devices without hover events never send HOVER_EXIT; release the edge exclusion anyway.
        handler.removeCallbacks(clearExclusion)
        handler.postDelayed(clearExclusion, TouchPolicy.STYLUS_LINGER_MS + 50)
        e.invalidateOverlay()
    }

    private fun clearStylusGesture() {
        stylusId = -1
        stylusTool = null
        stylusToolType = null
        stylusConsumed = false
        handler.removeCallbacks(longPress)
    }

    private fun recordLast(event: MotionEvent, idx: Int) {
        lastSX = event.getX(idx); lastSY = event.getY(idx)
        lastSPressure = event.getPressure(idx)
        lastSTilt = event.getAxisValue(MotionEvent.AXIS_TILT, idx)
        lastSTime = event.eventTime
    }

    private val longPress = Runnable {
        // "Nothing" leaves the gesture alone: checked before cancelling or consuming anything.
        if (e.inputPrefs.longPress == LongPressAction.NONE) return@Runnable
        val x: Float; val y: Float
        if (stylusId != -1) {
            val type = stylusToolType ?: return@Runnable
            if (stylusMoved || !allowsStylusLongPress(type, stylusTool?.isInking == true)) return@Runnable
            x = lastSX; y = lastSY
            stylusTool?.onCancel(e)
            stylusConsumed = true
        } else if (mode == Mode.PENDING || (mode == Mode.TOOL && !fingerMovedBeyondSlop())) {
            val f = fingers.values.firstOrNull { !it.palm } ?: return@Runnable
            if (fingerToolStarted) fingerTool?.onCancel(e)
            fingerToolStarted = false
            fingerTool = null
            mode = Mode.CONSUMED
            x = f.x; y = f.y
        } else return@Runnable
        val page = e.pageIndexAt(x, y)
        if (page < 0) return@Runnable
        e.viewToPage(page, x, y, tmp)
        view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        e.listener?.onLongPress(page, tmp[0], tmp[1], x, y)
    }

    // ------------------------------------------------------------------ Hover, buttons, wheel

    fun onGenericMotionEvent(event: MotionEvent): Boolean {
        try {
            if (!event.isFromSource(InputDevice.SOURCE_CLASS_POINTER)) return false
            val kind = kindOf(event, 0)
            val isStylus = kind == PointerKind.STYLUS || kind == PointerKind.ERASER_TIP
            // Finger hover (accessibility touch exploration) and mouse hover go to the framework.
            val hoverAction = event.actionMasked == MotionEvent.ACTION_HOVER_ENTER || event.actionMasked == MotionEvent.ACTION_HOVER_MOVE ||
                event.actionMasked == MotionEvent.ACTION_HOVER_EXIT
            if (hoverAction && !isStylus) return false
            when (event.actionMasked) {
                MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> {
                    if (kind == PointerKind.STYLUS || kind == PointerKind.ERASER_TIP) {
                        stylusHovering = true
                        markStylusNear(event.y)
                        if (!stylusDetected) { stylusDetected = true; e.listener?.onStylusDetected() }
                        val phase = if (event.actionMasked == MotionEvent.ACTION_HOVER_ENTER) StylusPhase.HOVER_ENTER else StylusPhase.HOVER_MOVE
                        apply(buttons.onEvent(phase, kind, event.buttonState, event.eventTime))
                        hover(event.x, event.y, true)
                    }
                }
                MotionEvent.ACTION_HOVER_EXIT -> {
                    if (kind == PointerKind.STYLUS || kind == PointerKind.ERASER_TIP) {
                        stylusHovering = false
                        stylusLastSeen = SystemClock.uptimeMillis()
                        buttons.onEvent(StylusPhase.HOVER_EXIT, kind, event.buttonState, event.eventTime)
                        handler.removeCallbacks(buttonTimeout)
                        handler.postDelayed(buttonTimeout, StylusButtonStateMachine.EXIT_GRACE_MS + 20)
                        hover(event.x, event.y, false)
                        handler.removeCallbacks(clearExclusion)
                        handler.postDelayed(clearExclusion, TouchPolicy.STYLUS_LINGER_MS + 50)
                    }
                }
                MotionEvent.ACTION_BUTTON_PRESS, MotionEvent.ACTION_BUTTON_RELEASE -> {
                    if (kind == PointerKind.STYLUS || kind == PointerKind.ERASER_TIP) {
                        val phase = if (event.actionMasked == MotionEvent.ACTION_BUTTON_PRESS) StylusPhase.BUTTON_PRESS else StylusPhase.BUTTON_RELEASE
                        val r = buttons.onEvent(phase, kind, event.buttonState, event.eventTime)
                        apply(r)
                        if (stylusId != -1 && !stylusConsumed && r.effectiveTool != stylusToolType) switchStylusTool(r.effectiveTool)
                        e.invalidateOverlay()
                    }
                }
                MotionEvent.ACTION_SCROLL -> wheel(event)
                else -> return false
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Hover handling failed", t)
        }
        return true
    }

    private val buttonTimeout = Runnable {
        apply(buttons.onTimeout(SystemClock.uptimeMillis()))
        e.invalidateOverlay()
    }

    private fun hover(x: Float, y: Float, active: Boolean) {
        hoverX = x; hoverY = y; hoverActive = active
        val tool = e.tool(buttons.effectiveTool)
        if (stylusId == -1) {
            if (active) {
                val page = e.pageIndexAt(x, y)
                if (page >= 0) {
                    e.viewToPage(page, x, y, tmp)
                    fillSingle(ev, page, x, y, 0f, 0f, SystemClock.uptimeMillis(), stylus = true)
                    tool.onHover(e, ev)
                }
            } else tool.onHover(e, null)
        }
        e.listener?.onHover(x, y, active)
        e.invalidateOverlay()
    }

    private fun wheel(event: MotionEvent) {
        val v = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
        val h = event.getAxisValue(MotionEvent.AXIS_HSCROLL)
        if (v == 0f && h == 0f) return
        if ((event.metaState and android.view.KeyEvent.META_CTRL_ON) != 0) {
            e.viewportCtl.zoomAround(Math.pow(1.12, v.toDouble()).toFloat(), event.x, event.y)
        } else {
            val shift = (event.metaState and android.view.KeyEvent.META_SHIFT_ON) != 0
            val fy = config.scaledVerticalScrollFactor
            val fx = config.scaledHorizontalScrollFactor
            if (shift) e.viewportCtl.panBy(v * fx, 0f) else e.viewportCtl.panBy(-h * fx, v * fy)
        }
    }

    /** Applies side-button results: notify the host of temporary / requested tool changes. */
    private fun apply(r: StylusButtonStateMachine.Result) {
        if (r.temporaryChanged) {
            e.listener?.onTemporaryToolChanged(r.temporaryTool)
            e.invalidateOverlay()
        }
        r.requestTool?.let { e.listener?.onToolChangeRequested(it) }
    }

    // ------------------------------------------------------------------ Palm rejection helpers

    private fun markStylusNear(y: Float) {
        stylusLastSeen = SystemClock.uptimeMillis()
        // Palm or finger interactions that started before the pen arrived are dropped without marks.
        when (mode) {
            Mode.PENDING, Mode.SCROLL, Mode.TOOL -> {
                if (fingerToolStarted) fingerTool?.onCancel(e)
                fingerTool = null
                fingerToolStarted = false
                pendingSamples.clear()
                handler.removeCallbacks(startFingerTool)
                handler.removeCallbacks(longPress)
                e.viewportCtl.stop()
                mode = Mode.IGNORED
            }
            Mode.MULTI -> if (!e.inputPrefs.zoomWhileWriting) { e.viewportCtl.endPinch(); mode = Mode.IGNORED }
            else -> Unit
        }
        updateExclusion(y)
    }

    /** Lets strokes start at the screen edges by excluding system back gestures near the pen. */
    private fun updateExclusion(y: Float) {
        handler.removeCallbacks(clearExclusion)
        val d = e.density
        if (!exclusionCenter.isNaN() && abs(exclusionCenter - y) < 40f * d) return
        exclusionCenter = y
        val half = (EXCLUSION_HEIGHT_DP * d / 2f).toInt()
        val w = view.width; val h = view.height
        if (w <= 0 || h <= 0) return
        val top = (y.toInt() - half).coerceIn(0, max(0, h - half * 2))
        val bottom = (top + half * 2).coerceAtMost(h)
        val edge = (EXCLUSION_WIDTH_DP * d).toInt()
        view.systemGestureExclusionRects = listOf(Rect(0, top, edge, bottom), Rect(w - edge, top, w, bottom))
    }

    private val clearExclusion = Runnable {
        if (stylusId != -1 || stylusHovering) return@Runnable
        exclusionCenter = Float.NaN
        view.systemGestureExclusionRects = emptyList()
    }

    // ------------------------------------------------------------------ Fingers

    private fun fingerDown(event: MotionEvent, index: Int, mouse: Boolean) {
        val id = event.getPointerId(index)
        val f = Finger(id, event.getX(index), event.getY(index), event.eventTime)
        f.palm = !mouse && policy.isPalm(event.getTouchMajor(index))
        fingers[id] = f
        if (f.palm) return
        val active = activeFingerCount()
        val near = !mouse && stylusNear()
        val permission = if (mouse) FingerPermission.ALL else policy.fingerPermission(near)
        if (permission == FingerPermission.NONE) {
            if (mode == Mode.NONE) mode = Mode.IGNORED
            return
        }
        if (active == 1 && (mode == Mode.NONE || mode == Mode.IGNORED && fingers.size == 1)) {
            firstFingerTime = event.eventTime
            if (permission == FingerPermission.MULTI_ONLY) { mode = Mode.IGNORED; return }
            mode = Mode.PENDING
            taps.begin(event.eventTime)
            velocity?.recycle()
            velocity = VelocityTracker.obtain().also { it.addMovement(event) }
            e.viewportCtl.stop()
            handler.postDelayed(longPress, longPressMs)
            val role = if (mouse) FingerRole.NAVIGATE else policy.fingerRole()
            // A finger on the selection box drags it, whatever the finger otherwise does (unless the
            // selection fills the screen: then the finger keeps scrolling).
            val onSelection = e.selection.hit(f.x, f.y) != null && !e.selection.coversView()
            if (onSelection || role != FingerRole.NAVIGATE) {
                pendingSamples.clear()
                pendingSamples += Sample(f.x, f.y, event.eventTime)
                fingerTool = if (onSelection) e.selectionTool else e.tool(if (role == FingerRole.SELECT) ToolType.LASSO else e.toolState.tool)
                fingerToolStarted = false
                fingerToolPage = e.pageIndexAt(f.x, f.y)
                handler.postDelayed(startFingerTool, TouchPolicy.FINGER_INK_DELAY_MS)
            }
            return
        }
        if (active >= 2) {
            if (mode == Mode.CONSUMED) return
            if (near && mode != Mode.MULTI) {
                // While writing, only fingers landing together form a gesture; older contacts are a
                // resting hand and stay ignored.
                val recent = fingers.values.filter { !it.palm && policy.deliberateMultiTouch(event.eventTime - it.downTime, true) }
                if (recent.size < 2) {
                    if (mode == Mode.PENDING || mode == Mode.TOOL || mode == Mode.SCROLL) abandonFingers()
                    mode = Mode.IGNORED
                    return
                }
                for (other in fingers.values) if (!other.palm && other !in recent) other.palm = true
                firstFingerTime = recent.minOf { it.downTime }
            }
            // A second finger turns any tentative single-finger action into navigation.
            handler.removeCallbacks(startFingerTool)
            handler.removeCallbacks(longPress)
            if (fingerToolStarted) fingerTool?.onCancel(e)
            fingerTool = null
            fingerToolStarted = false
            pendingSamples.clear()
            if (mode != Mode.MULTI) {
                // PENDING / SCROLL / TOOL already began (and, when moved, invalidated) the tracker for
                // this gesture. Otherwise, e.g. when the first finger was ignored because the stylus
                // was near, start it fresh: single-finger gestures never end the tracker, so a stale
                // "active" state from an earlier scroll would silently swallow two-finger taps.
                if (mode != Mode.PENDING && mode != Mode.SCROLL && mode != Mode.TOOL) taps.begin(firstFingerTime)
                e.viewportCtl.beginPinch()
                mode = Mode.MULTI
            }
            taps.pointerCount(active)
            if (velocity == null) velocity = VelocityTracker.obtain()
            velocity?.addMovement(event)
            baselineMulti()
        }
    }

    private fun abandonFingers() {
        handler.removeCallbacks(startFingerTool)
        handler.removeCallbacks(longPress)
        if (fingerToolStarted) fingerTool?.onCancel(e)
        fingerTool = null
        fingerToolStarted = false
        if (mode == Mode.MULTI) e.viewportCtl.endPinch()
        mode = Mode.IGNORED
    }

    private fun activeFingerCount(): Int = fingers.values.count { !it.palm }

    private fun fingerMovedBeyondSlop(): Boolean {
        val f = fingers.values.firstOrNull { !it.palm } ?: return false
        return Geo.dist(f.downX, f.downY, f.x, f.y) > touchSlop
    }

    private val startFingerTool = Runnable { beginFingerTool() }

    private fun beginFingerTool() {
        if (fingerToolStarted || mode != Mode.PENDING) return
        val tool = fingerTool ?: return
        if (fingerToolPage < 0 || pendingSamples.isEmpty()) return
        handler.removeCallbacks(startFingerTool)
        fingerToolStarted = true
        mode = Mode.TOOL
        val first = pendingSamples[0]
        fillSingle(ev, fingerToolPage, first.x, first.y, 0.6f, 0f, first.t, stylus = false)
        ev.isFinger = true
        tool.onDown(e, ev)
        if (pendingSamples.size > 1) {
            ev.reset(fingerToolPage)
            ev.isStylus = false; ev.isFinger = true; ev.willPredict = false
            for (k in 1 until pendingSamples.size) { val s = pendingSamples[k]; addSample(ev, s.x, s.y, 0.6f, 0f, s.t) }
            tool.onMove(e, ev)
        }
        pendingSamples.clear()
    }

    private fun fingerMove(event: MotionEvent) {
        // Track positions and late palm detection (contact growing into a palm).
        var newPalm = false
        for (i in 0 until event.pointerCount) {
            val f = fingers[event.getPointerId(i)] ?: continue
            f.x = event.getX(i); f.y = event.getY(i)
            if (!f.palm && kindOf(event, i) == PointerKind.FINGER && policy.isPalm(event.getTouchMajor(i))) {
                f.palm = true
                newPalm = true
                if (mode == Mode.PENDING || mode == Mode.TOOL) abandonFingers()
            }
        }
        if (newPalm && mode == Mode.MULTI) {
            // The focal point and span now exclude the palm: re-baseline so the canvas does not jump.
            taps.invalidate()
            baselineMulti()
            return
        }
        velocity?.addMovement(event)
        when (mode) {
            Mode.PENDING -> {
                val f = fingers.values.firstOrNull { !it.palm } ?: return
                val idx = event.findPointerIndex(f.id)
                if (fingerTool != null && idx >= 0) {
                    for (h in 0 until event.historySize) pendingSamples += Sample(event.getHistoricalX(idx, h), event.getHistoricalY(idx, h), event.getHistoricalEventTime(h))
                    pendingSamples += Sample(f.x, f.y, event.eventTime)
                }
                if (Geo.dist(f.downX, f.downY, f.x, f.y) > touchSlop) {
                    handler.removeCallbacks(longPress)
                    taps.invalidate()
                    if (fingerTool != null) beginFingerTool() else {
                        mode = Mode.SCROLL
                        lastFocusX = f.x; lastFocusY = f.y
                    }
                }
            }
            Mode.SCROLL -> {
                val f = fingers.values.firstOrNull { !it.palm } ?: return
                val dx = f.x - lastFocusX; val dy = f.y - lastFocusY
                lastFocusX = f.x; lastFocusY = f.y
                if (dx != 0f || dy != 0f) e.viewportCtl.panBy(dx, dy)
            }
            Mode.TOOL -> {
                val tool = fingerTool ?: return
                val f = fingers.values.firstOrNull { !it.palm } ?: return
                val idx = event.findPointerIndex(f.id)
                if (idx < 0) return
                fillBatch(ev, event, idx, fingerToolPage, stylus = false)
                ev.isFinger = true
                ev.willPredict = false
                tool.onMove(e, ev)
            }
            Mode.MULTI -> multiMove()
            else -> Unit
        }
    }

    private fun focus(out: FloatArray): Float {
        var sx = 0f; var sy = 0f; var n = 0
        for (f in fingers.values) if (!f.palm) { sx += f.x; sy += f.y; n++ }
        if (n == 0) return 0f
        val cx = sx / n; val cy = sy / n
        var span = 0f
        for (f in fingers.values) if (!f.palm) span += hypot(f.x - cx, f.y - cy)
        out[0] = cx; out[1] = cy
        return span / n
    }

    private fun baselineMulti() {
        lastSpan = focus(tmp)
        lastFocusX = tmp[0]; lastFocusY = tmp[1]
    }

    private fun multiMove() {
        val span = focus(tmp)
        val fx = tmp[0]; val fy = tmp[1]
        var maxTravel = 0f
        for (f in fingers.values) if (!f.palm) maxTravel = max(maxTravel, Geo.dist(f.downX, f.downY, f.x, f.y))
        taps.moved(maxTravel)
        val factor = if (lastSpan > touchSlop && span > touchSlop && activeFingerCount() >= 2) span / lastSpan else 1f
        val dx = fx - lastFocusX; val dy = fy - lastFocusY
        lastSpan = span; lastFocusX = fx; lastFocusY = fy
        if (factor != 1f || dx != 0f || dy != 0f) e.viewportCtl.pinch(fx, fy, factor, dx, dy)
    }

    private fun fingerUp(event: MotionEvent, index: Int, canceled: Boolean) {
        val id = event.getPointerId(index)
        val f = fingers[id] ?: return
        f.x = event.getX(index); f.y = event.getY(index)
        val wasPalm = f.palm
        fingers.remove(id)
        if (wasPalm) return
        when (mode) {
            Mode.PENDING -> {
                handler.removeCallbacks(longPress)
                handler.removeCallbacks(startFingerTool)
                val tool = fingerTool
                if (!canceled && tool != null) {
                    // A quick tap with a finger tool: run it as a full (short) gesture.
                    beginFingerTool()
                    if (fingerToolStarted) {
                        fillSingle(ev, fingerToolPage, f.x, f.y, 0.6f, 0f, event.eventTime, stylus = false)
                        ev.isFinger = true
                        tool.onUp(e, ev)
                    }
                } else if (!canceled) fingerTap(f.x, f.y)
                fingerTool = null
                fingerToolStarted = false
                mode = if (fingers.isEmpty()) Mode.NONE else Mode.CONSUMED
            }
            Mode.SCROLL -> {
                if (!canceled) {
                    val vt = velocity
                    if (vt != null) {
                        vt.computeCurrentVelocity(1000, maxFling)
                        val vx = vt.getXVelocity(id); val vy = vt.getYVelocity(id)
                        if (hypot(vx, vy) > minFling) e.viewportCtl.fling(vx, vy)
                    }
                }
                mode = if (fingers.isEmpty()) Mode.NONE else Mode.CONSUMED
            }
            Mode.TOOL -> {
                val tool = fingerTool
                if (tool != null) {
                    if (canceled) tool.onCancel(e) else {
                        fillSingle(ev, fingerToolPage, f.x, f.y, 0.6f, 0f, event.eventTime, stylus = false)
                        ev.isFinger = true
                        tool.onUp(e, ev)
                    }
                }
                fingerTool = null
                fingerToolStarted = false
                mode = if (fingers.isEmpty()) Mode.NONE else Mode.CONSUMED
            }
            Mode.MULTI -> {
                if (canceled) taps.invalidate()
                if (activeFingerCount() == 0) {
                    e.viewportCtl.endPinch()
                    val n = taps.end(event.eventTime)
                    if (n == 2) tapAction(e.inputPrefs.twoFingerTap)
                    else if (n == 3) tapAction(e.inputPrefs.threeFingerTap)
                    else {
                        val vt = velocity
                        if (vt != null && !canceled) {
                            vt.computeCurrentVelocity(1000, maxFling)
                            val vx = vt.getXVelocity(id); val vy = vt.getYVelocity(id)
                            if (hypot(vx, vy) > minFling * 2) e.viewportCtl.fling(vx, vy)
                        }
                    }
                    mode = Mode.NONE
                } else baselineMulti()
            }
            else -> if (fingers.isEmpty()) mode = Mode.NONE
        }
        if (fingers.isEmpty()) {
            velocity?.recycle()
            velocity = null
        }
    }

    private fun resetFingers() {
        handler.removeCallbacks(startFingerTool)
        if (stylusId == -1) handler.removeCallbacks(longPress)
        fingers.clear()
        fingerTool = null
        fingerToolStarted = false
        pendingSamples.clear()
        if (mode == Mode.MULTI) e.viewportCtl.endPinch()
        mode = Mode.NONE
        taps.cancel()
        velocity?.recycle()
        velocity = null
    }

    private fun tapAction(action: TapGestureAction) {
        when (action) {
            TapGestureAction.UNDO -> e.undo()
            TapGestureAction.REDO -> e.redo()
            TapGestureAction.TOGGLE_ERASER -> {
                val target = if (e.toolState.tool == ToolType.ERASER) buttons.lastNonEraserTool else ToolType.ERASER
                e.listener?.onToolChangeRequested(target)
            }
            TapGestureAction.NONE -> Unit
        }
    }

    /** A finger tap: tap tools act; otherwise report the tapped item. */
    private fun fingerTap(x: Float, y: Float) {
        val page = e.pageIndexAt(x, y)
        if (page < 0) return
        val tool = e.toolState.tool
        if (tool == ToolType.TEXT || tool == ToolType.STICKY || tool == ToolType.IMAGE || tool == ToolType.LASSO) {
            val t = e.tool(tool)
            fillSingle(ev, page, x, y, 0.6f, 0f, SystemClock.uptimeMillis(), stylus = false)
            ev.isFinger = true
            t.onDown(e, ev)
            t.onUp(e, ev)
            return
        }
        val h = e.page(page) ?: return
        if (!h.isLoaded) return
        e.viewToPage(page, x, y, tmp)
        val hit = h.index().hitTest(tmp[0], tmp[1], 8f * e.density / e.scale) ?: run {
            e.taps.tap(SystemClock.uptimeMillis(), x, y, null)
            if (e.selectedIds.isNotEmpty()) e.clearSelection()
            return
        }
        // Double-tap a sticky note or text box to edit it.
        val double = e.taps.tap(SystemClock.uptimeMillis(), x, y, Items.id(hit))
        if (double && (hit is com.inkwell.notes.core.model.StickyItem || hit is com.inkwell.notes.core.model.TextItem)) {
            e.requestEdit(page, hit, tmp[0], tmp[1])
            return
        }
        e.listener?.onItemTapped(Items.ref(h.id, hit))
    }

    // ------------------------------------------------------------------ Sample filling

    private fun fillSingle(target: ToolEvent, page: Int, vx: Float, vy: Float, pressure: Float, tilt: Float, time: Long, stylus: Boolean) {
        target.reset(page)
        target.isStylus = stylus
        target.isFinger = false
        target.willPredict = false
        addSample(target, vx, vy, pressure, tilt, time)
    }

    private fun fillBatch(target: ToolEvent, event: MotionEvent, idx: Int, page: Int, stylus: Boolean) {
        target.reset(page)
        target.isStylus = stylus
        target.isFinger = false
        for (h in 0 until event.historySize) {
            addSample(
                target, event.getHistoricalX(idx, h), event.getHistoricalY(idx, h), event.getHistoricalPressure(idx, h),
                event.getHistoricalAxisValue(MotionEvent.AXIS_TILT, idx, h), event.getHistoricalEventTime(h),
            )
        }
        addSample(target, event.getX(idx), event.getY(idx), event.getPressure(idx), event.getAxisValue(MotionEvent.AXIS_TILT, idx), event.eventTime)
    }

    private fun addSample(target: ToolEvent, vx: Float, vy: Float, pressure: Float, tilt: Float, time: Long) {
        val page = target.pageIndex
        if (page >= 0) e.viewToPage(page, vx, vy, tmp) else { tmp[0] = vx; tmp[1] = vy }
        target.add(tmp[0], tmp[1], vx, vy, pressure, tilt, time)
    }

    companion object {
        private const val TAG = "InkInput"

        /**
         * Whether a stylus contact with this tool may turn into a long press. Inking tools hold
         * their own gestures, and the eraser edits live: cancelling it after a long press would
         * restore what it just erased and pop a menu whenever the pen rests on a speck.
         */
        fun allowsStylusLongPress(type: ToolType, isInking: Boolean): Boolean = !isInking && type != ToolType.ERASER

        /**
         * Whether a side-button change mid-contact leaves a running selection manipulation
         * ([manipulatingSelection]) untouched, only relabelling the contact with [newType]. True for
         * every tool but the eraser, which never acts on the selection: the move, resize or rotate
         * keeps following the pen and a press without movement stays a single tap.
         */
        fun keepsSelectionManipulation(manipulatingSelection: Boolean, newType: ToolType): Boolean =
            manipulatingSelection && newType != ToolType.ERASER

        /**
         * Whether a side-button change mid-contact ends the contact instead of continuing with the
         * new tool: true when the hold-to-lasso button was released while drawing a lasso loop (or
         * while manipulating the selection with the eraser as the base tool, see
         * [keepsSelectionManipulation]).
         */
        fun endsContactOnLassoRelease(
            contactType: ToolType?,
            mode: SideButtonMode,
            temporaryTool: ToolType?,
        ): Boolean = contactType == ToolType.LASSO &&
            mode == SideButtonMode.HOLD_TO_LASSO && temporaryTool == null
        /** MotionEvent.FLAG_CANCELED (API 33): the pointer was cancelled, e.g. a palm. */
        private const val FLAG_CANCELED = 0x20
        private const val EXCLUSION_HEIGHT_DP = 200f
        private const val EXCLUSION_WIDTH_DP = 40f
    }
}
