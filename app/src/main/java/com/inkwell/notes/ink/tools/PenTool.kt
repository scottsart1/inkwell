package com.inkwell.notes.ink.tools

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.RectF
import android.os.SystemClock
import com.inkwell.notes.core.model.AudioAnchor
import com.inkwell.notes.core.model.InkPoints
import com.inkwell.notes.core.model.PenConfig
import com.inkwell.notes.core.model.PenType
import com.inkwell.notes.core.model.ShapeItem
import com.inkwell.notes.core.model.ShapeKind
import com.inkwell.notes.core.model.Stroke
import com.inkwell.notes.core.model.ToolType
import com.inkwell.notes.ink.geometry.Geo
import com.inkwell.notes.ink.input.PointSmoother
import com.inkwell.notes.ink.input.PressureCurve
import com.inkwell.notes.ink.model.ContentOps
import com.inkwell.notes.ink.recognize.LoopDetector
import com.inkwell.notes.ink.recognize.PathOps
import com.inkwell.notes.ink.recognize.ScribbleDetector
import com.inkwell.notes.ink.recognize.ShapeRecognizer
import com.inkwell.notes.ink.render.PageRenderer
import com.inkwell.notes.ink.render.PenModel
import com.inkwell.notes.ink.select.SelectionClipboard
import com.inkwell.notes.ink.select.SelectionMath
import kotlin.math.hypot

/**
 * Pen, pencil and highlighter. Samples are stabilised (one-euro filter), pressure goes through
 * the user's curve, the live stroke is shown by [com.inkwell.notes.ink.render.WetInkPresenter]
 * and pen-up commits exactly the samples that were shown (prediction is never committed).
 *
 * Gestures on top of inking (each behind its InputPrefs switch):
 * - circle-to-select: a closed loop around existing ink followed by a short hold selects what it
 *   encloses instead of inking;
 * - hold-to-straighten: resting the pen about half a second at the end of a stroke snaps it to a
 *   straight line or a clean shape; keep holding to snap a line's angle to 15 degree steps, and
 *   move to adjust the line's end before lifting;
 * - automatic shape recognition of confidently closed shapes on pen-up;
 * - scribble-to-erase: a fast zig-zag over existing ink deletes what it covers and leaves no ink.
 */
class PenTool(override val type: ToolType) : InkTool {
    override val isInking: Boolean get() = true
    private var active = false
    override val isActive: Boolean get() = active

    private enum class Mode { INK, SHAPE, CONSUMED }

    private val smoother = PointSmoother()
    private var cfg: PenConfig = PenConfig.DefaultPen
    private var color = 0
    private var pageIndex = -1
    private var startTime = 0L
    private var createdAt = 0L
    private var anchor: AudioAnchor? = null
    private var stylus = false
    private var finger = false
    private var lastPressure = 0.5f
    private val pred = FloatArray(com.inkwell.notes.ink.render.WetStroke.MAX_PREDICTED * InkPoints.STRIDE)

    // ---- Gesture state
    private var mode = Mode.INK
    private var host: ToolHost? = null
    private var holdX = 0f
    private var holdY = 0f
    private var holdSince = 0L
    private var gestureArmed = false
    private var timerPending = false
    /** Recognised shape being previewed (SHAPE mode). */
    private var shape: ShapeRecognizer.Result? = null
    /** Samples of the stroke when it was recognised (pressure and timing for straightened lines). */
    private var shapeSamples: FloatArray = FloatArray(0)
    private var lineEndX = 0f
    private var lineEndY = 0f
    private var angleSnap = false
    private val matrix = Matrix()
    private val clip = RectF()
    private val holdCheck = Runnable { onHoldTimer() }

    private fun config(host: ToolHost): PenConfig = when (type) {
        ToolType.PENCIL -> host.toolState.pencil
        ToolType.HIGHLIGHTER -> host.toolState.highlighter
        else -> host.toolState.pen
    }

    private val isHighlighter: Boolean get() = cfg.type == PenType.HIGHLIGHTER

    override fun onDown(host: ToolHost, e: ToolEvent) {
        active = false
        this.host = host
        resetGesture(host)
        if (!host.isEditable(e.pageIndex)) return
        host.clearSelection()
        cfg = config(host)
        val baseAlpha = (cfg.color ushr 24) and 0xFF
        val alpha = (baseAlpha * cfg.opacity.coerceIn(0.05f, 1f)).toInt().coerceIn(1, 255)
        color = (alpha shl 24) or (cfg.color and 0x00FFFFFF)
        smoother.configure(cfg.stabilization)
        smoother.reset()
        pageIndex = e.pageIndex
        startTime = e.eventTime
        createdAt = System.currentTimeMillis()
        anchor = host.audioAnchor()
        stylus = e.isStylus
        finger = e.isFinger
        active = true
        host.wetInk.begin(pageIndex, cfg.type, color, cfg.width.coerceIn(0.1f, 200f), cfg.pressureSensitivity, allowFrontBuffer = !e.isFinger)
        addSamples(host, e, isUp = false)
        host.wetInk.refresh()
        val prefs = host.inputPrefs
        gestureArmed = prefs.holdToStraighten || (prefs.circleToSelect && !isHighlighter)
        holdX = e.viewX; holdY = e.viewY; holdSince = e.eventTime
        if (gestureArmed) armHold(host, firstHoldDelay(host))
    }

    override fun onMove(host: ToolHost, e: ToolEvent) {
        if (!active) return
        trackHold(host, e)
        when (mode) {
            Mode.CONSUMED -> return
            Mode.SHAPE -> {
                if (shape?.kind == ShapeKind.LINE) {
                    // Keep adjusting the line's end while the pen stays down.
                    setLineEnd(e.x, e.y)
                    host.invalidateOverlay()
                }
                return
            }
            Mode.INK -> Unit
        }
        addSamples(host, e, isUp = false)
        if (!e.willPredict) {
            host.wetInk.clearPredicted()
            host.wetInk.refresh()
        }
    }

    override fun onPredicted(host: ToolHost, e: ToolEvent) {
        if (!active || mode != Mode.INK) return
        val wet = host.wetInk.stroke
        val n = minOf(e.count, com.inkwell.notes.ink.render.WetStroke.MAX_PREDICTED)
        if (n == 0 || wet.count == 0) {
            host.wetInk.clearPredicted()
        } else {
            // Predictions start at the raw pen position; shift them so they continue the
            // smoothed stroke without a kink.
            val dx = wet.lastX() - lastRawX; val dy = wet.lastY() - lastRawY
            val st = InkPoints.STRIDE
            val tLast = wet.points[(wet.count - 1) * st + InkPoints.TIME]
            for (i in 0 until n) {
                pred[i * st] = e.px[i] + dx
                pred[i * st + 1] = e.py[i] + dy
                pred[i * st + InkPoints.PRESSURE] = lastPressure
                pred[i * st + InkPoints.TILT] = e.tilt[i]
                pred[i * st + InkPoints.TIME] = tLast + (i + 1) * 4f
            }
            host.wetInk.setPredicted(pred, n)
        }
        host.wetInk.refresh()
    }

    override fun onUp(host: ToolHost, e: ToolEvent) {
        if (!active) return
        when (mode) {
            Mode.CONSUMED -> { finishGesture(host); return }
            Mode.SHAPE -> {
                if (shape?.kind == ShapeKind.LINE) setLineEnd(e.x, e.y)
                commitShape(host)
                return
            }
            Mode.INK -> Unit
        }
        addSamples(host, e, isUp = true)
        if (tryScribbleErase(host)) return
        if (tryAutoShape(host)) return
        commit(host)
    }

    override fun flush(host: ToolHost) {
        if (!active) return
        when (mode) {
            Mode.CONSUMED -> finishGesture(host)
            Mode.SHAPE -> commitShape(host)
            Mode.INK -> commit(host)
        }
    }

    private fun commit(host: ToolHost) {
        active = false
        host.unschedule(holdCheck)
        val wet = host.wetInk.stroke
        if (wet.count == 0) { host.wetInk.cancel(); return }
        val stroke = Stroke(
            id = host.newId(),
            pen = cfg.type,
            color = color,
            width = cfg.width.coerceIn(0.1f, 200f),
            points = wet.committedPoints(),
            createdAt = createdAt,
            z = host.nextZ(pageIndex),
            audio = anchor,
            pressureSensitivity = cfg.pressureSensitivity,
        )
        host.commitNewItems(pageIndex, listOf(stroke), "Ink")
        host.wetInk.finish()
    }

    override fun onCancel(host: ToolHost) {
        host.unschedule(holdCheck)
        if (!active) return
        active = false
        if (mode == Mode.INK) host.wetInk.cancel()
        resetGesture(host)
        host.invalidateOverlay()
    }

    private var lastRawX = 0f
    private var lastRawY = 0f

    private fun addSamples(host: ToolHost, e: ToolEvent, isUp: Boolean) {
        val wet = host.wetInk
        val scale = host.scale
        val minDist = 0.35f / scale
        val curve = host.inputPrefs.pressureCurve
        for (i in 0 until e.count) {
            val pressure = if (stylus) PressureCurve.apply(e.pressure[i], curve) else FINGER_PRESSURE
            smoother.push(e.px[i], e.py[i], e.time[i], scale)
            lastRawX = e.px[i]; lastRawY = e.py[i]
            val x = smoother.outX; val y = smoother.outY
            val t = (e.time[i] - startTime).toFloat()
            val s = wet.stroke
            if (s.count > 0 && Geo.dist(s.lastX(), s.lastY(), x, y) < minDist) {
                // Pen-up pressure is ignored so the committed tip matches what was shown.
                if (!isUp) { s.updateLastPressure(pressure); lastPressure = pressure }
                continue
            }
            val p = if (isUp && s.count > 0) lastPressure else pressure
            wet.add(x, y, p, e.tilt[i], t)
            lastPressure = p
        }
    }

    // ------------------------------------------------------------------ Hold detection

    private fun armHold(host: ToolHost, delayMs: Long) {
        timerPending = true
        host.schedule(delayMs, holdCheck)
    }

    private fun resetGesture(host: ToolHost) {
        host.unschedule(holdCheck)
        timerPending = false
        mode = Mode.INK
        shape = null
        shapeSamples = FloatArray(0)
        angleSnap = false
        gestureArmed = false
    }

    private fun firstHoldDelay(host: ToolHost): Long =
        if (host.inputPrefs.circleToSelect && !isHighlighter) LOOP_HOLD_MS else HOLD_MS

    /** Restarts the stillness clock whenever the pen moves beyond a small slop (view pixels). */
    private fun trackHold(host: ToolHost, e: ToolEvent) {
        if (!gestureArmed) return
        val slop = HOLD_SLOP_DP * host.density
        for (i in 0 until e.count) {
            if (hypot(e.vx[i] - holdX, e.vy[i] - holdY) > slop) {
                holdX = e.vx[i]; holdY = e.vy[i]; holdSince = e.time[i]
                // A pause that was evaluated earlier left no timer: watch for the next one.
                if (!timerPending && mode != Mode.CONSUMED && !(mode == Mode.SHAPE && angleSnap)) {
                    armHold(host, if (mode == Mode.SHAPE) HOLD_MS else firstHoldDelay(host))
                }
            }
        }
    }

    private fun onHoldTimer() {
        timerPending = false
        val host = host ?: return
        if (!active || !gestureArmed) return
        val required = when (mode) {
            Mode.INK -> firstHoldDelay(host)
            Mode.SHAPE -> HOLD_MS
            Mode.CONSUMED -> return
        }
        val still = SystemClock.uptimeMillis() - holdSince
        if (still < required) { armHold(host, required - still); return }
        try {
            when (mode) {
                Mode.INK -> onInkHold(host, still)
                Mode.SHAPE -> onShapeHold(host)
                Mode.CONSUMED -> Unit
            }
        } catch (t: Throwable) {
            android.util.Log.e(TAG, "Hold gesture failed", t)
        }
    }

    private fun wetXY(host: ToolHost): FloatArray {
        val wet = host.wetInk.stroke
        return PathOps.xyOf(wet.points, InkPoints.STRIDE, wet.count)
    }

    private fun onInkHold(host: ToolHost, stillMs: Long) {
        val prefs = host.inputPrefs
        val xy = wetXY(host)
        if (PathOps.count(xy) < 4) return
        val toPage = host.density / host.scale
        if (prefs.circleToSelect && !isHighlighter) {
            val loop = LoopDetector.closedLoop(xy, MIN_LOOP_DP * toPage)
            val holder = host.page(pageIndex)
            if (loop != null && holder != null && holder.isLoaded) {
                val ids = LassoSelector.select(holder.content, holder.index(), loop, host.toolState.lassoFilter)
                if (ids.isNotEmpty()) {
                    host.wetInk.cancel()
                    mode = Mode.CONSUMED
                    host.unschedule(holdCheck)
                    timerPending = false
                    host.setSelection(pageIndex, ids)
                    host.haptic()
                    return
                }
            }
        }
        if (!prefs.holdToStraighten) return
        if (stillMs < HOLD_MS) { armHold(host, HOLD_MS - stillMs); return }
        val r = ShapeRecognizer.recognize(xy, MIN_LINE_DP * toPage) ?: return
        if (isHighlighter && r.kind != ShapeKind.LINE) return
        if (r.isClosed && !bigEnough(xy, MIN_SHAPE_DP * toPage)) return
        val wet = host.wetInk.stroke
        shapeSamples = wet.committedPoints()
        host.wetInk.cancel()
        shape = r
        mode = Mode.SHAPE
        if (r.kind == ShapeKind.LINE) {
            lineEndX = r.points[2]; lineEndY = r.points[3]
            // Holding on snaps the angle.
            holdSince = SystemClock.uptimeMillis()
            armHold(host, HOLD_MS)
        }
        host.haptic()
        host.invalidateOverlay()
    }

    private fun onShapeHold(host: ToolHost) {
        val r = shape ?: return
        if (r.kind != ShapeKind.LINE || angleSnap) return
        angleSnap = true
        setLineEnd(lineEndX, lineEndY)
        host.haptic()
        host.invalidateOverlay()
    }

    private fun setLineEnd(x: Float, y: Float) {
        val r = shape ?: return
        if (angleSnap) {
            val p = SelectionMath.snapLineAngle(r.points[0], r.points[1], x, y, 15f)
            lineEndX = p[0]; lineEndY = p[1]
        } else {
            lineEndX = x; lineEndY = y
        }
    }

    private fun bigEnough(xy: FloatArray, min: Float): Boolean {
        val b = PathOps.bounds(xy)
        return maxOf(b.width, b.height) >= min
    }

    private fun finishGesture(host: ToolHost) {
        active = false
        resetGesture(host)
        host.invalidateOverlay()
    }

    // ------------------------------------------------------------------ Shapes

    /** The straightened line as a stroke: original pressure and timing laid along the segment. */
    private fun straightStroke(id: String, z: Int): Stroke? {
        val r = shape ?: return null
        val src = shapeSamples
        val st = InkPoints.STRIDE
        val n = src.size / st
        if (n < 2) return null
        val x0 = r.points[0]; val y0 = r.points[1]
        val total = Geo.polylineLength(src, st, n).coerceAtLeast(1e-3f)
        val out = FloatArray(n * st)
        var acc = 0f
        for (i in 0 until n) {
            if (i > 0) acc += Geo.dist(src[(i - 1) * st], src[(i - 1) * st + 1], src[i * st], src[i * st + 1])
            val f = acc / total
            out[i * st] = x0 + (lineEndX - x0) * f
            out[i * st + 1] = y0 + (lineEndY - y0) * f
            out[i * st + InkPoints.PRESSURE] = src[i * st + InkPoints.PRESSURE]
            out[i * st + InkPoints.TILT] = src[i * st + InkPoints.TILT]
            out[i * st + InkPoints.TIME] = src[i * st + InkPoints.TIME]
        }
        return Stroke(
            id = id, pen = cfg.type, color = color, width = cfg.width.coerceIn(0.1f, 200f), points = out,
            createdAt = createdAt, z = z, audio = anchor, pressureSensitivity = cfg.pressureSensitivity,
        )
    }

    /** The recognised closed shape or arrow as a shape item with the pen's colour and weight. */
    private fun shapeItem(r: ShapeRecognizer.Result, id: String, z: Int): ShapeItem {
        val weight = cfg.width * PenModel.widthFactor(cfg.type, 0.5f, cfg.pressureSensitivity)
        return ShapeItem(
            id = id, kind = r.kind, points = r.points.copyOf(), strokeColor = color,
            strokeWidth = weight.coerceIn(0.25f, 60f), rotation = r.rotation, z = z,
            createdAt = createdAt, audio = anchor,
        )
    }

    private fun commitShape(host: ToolHost) {
        val r = shape
        active = false
        host.unschedule(holdCheck)
        if (r == null) { finishGesture(host); return }
        val z = host.nextZ(pageIndex)
        val item: Any? = if (r.kind == ShapeKind.LINE) straightStroke(host.newId(), z) else shapeItem(r, host.newId(), z)
        resetGesture(host)
        host.invalidateOverlay()
        if (item != null) host.commitNewItems(pageIndex, listOf(item), if (r.kind == ShapeKind.LINE) "Straight line" else "Shape")
    }

    private fun tryAutoShape(host: ToolHost): Boolean {
        if (!host.inputPrefs.autoShapeRecognition || isHighlighter) return false
        val xy = wetXY(host)
        val toPage = host.density / host.scale
        val r = ShapeRecognizer.recognize(xy, MIN_AUTO_SHAPE_DP * toPage, closedOnly = true) ?: return false
        if (r.error > ShapeRecognizer.CONFIDENT_ERROR) return false
        shapeSamples = host.wetInk.stroke.committedPoints()
        host.wetInk.cancel()
        shape = r
        mode = Mode.SHAPE
        commitShape(host)
        return true
    }

    // ------------------------------------------------------------------ Scribble to erase

    private fun tryScribbleErase(host: ToolHost): Boolean {
        if (!host.inputPrefs.scribbleToErase || isHighlighter) return false
        val wet = host.wetInk.stroke
        if (wet.count < 12) return false
        val xy = PathOps.xyOf(wet.points, InkPoints.STRIDE, wet.count)
        val times = FloatArray(wet.count) { wet.points[it * InkPoints.STRIDE + InkPoints.TIME] }
        val analysis = ScribbleDetector.analyze(xy, times)
        val minSpeed = SCRIBBLE_MIN_SPEED_DP_PER_MS * host.density / host.scale
        if (!ScribbleDetector.looksLikeScribble(analysis, minSpeed)) return false
        val holder = host.page(pageIndex) ?: return false
        if (!holder.isLoaded) return false
        val ids = ScribbleDetector.coveredIds(holder.content, holder.index(), xy, cfg.width)
        if (ids.isEmpty()) return false
        active = false
        host.unschedule(holdCheck)
        host.wetInk.cancel()
        resetGesture(host)
        host.applyEdit(pageIndex, "Scribble erase") { c -> SelectionClipboard.detachRemoved(c, ContentOps.remove(c, ids)) }
        host.haptic()
        return true
    }

    // ------------------------------------------------------------------ Preview

    override fun drawOverlay(host: ToolHost, canvas: Canvas) {
        if (!active || mode != Mode.SHAPE) return
        val r = shape ?: return
        canvas.save()
        if (host.pageClip(pageIndex, clip)) canvas.clipRect(clip)
        host.pageMatrix(pageIndex, matrix)
        canvas.concat(matrix)
        if (r.kind == ShapeKind.LINE) straightStroke("preview", 0)?.let { PageRenderer.drawStroke(canvas, it) }
        else PageRenderer.drawShape(canvas, shapeItem(r, "preview", 0))
        canvas.restore()
    }

    companion object {
        private const val TAG = "InkPen"
        /** Fingers and mice have no pressure sensor. */
        const val FINGER_PRESSURE = 0.6f
        /** Pen resting this long at the end of a stroke straightens it. */
        const val HOLD_MS = 500L
        /** A loop around ink becomes a selection after this shorter hold. */
        const val LOOP_HOLD_MS = 380L
        /** Movement below this (dp) still counts as holding still. */
        const val HOLD_SLOP_DP = 4f
        const val MIN_LINE_DP = 32f
        const val MIN_SHAPE_DP = 36f
        const val MIN_AUTO_SHAPE_DP = 48f
        const val MIN_LOOP_DP = 28f
        /** Scribbles are fast: average speed in dp per millisecond. */
        const val SCRIBBLE_MIN_SPEED_DP_PER_MS = 0.6f
    }
}
