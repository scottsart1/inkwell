package com.inkwell.notes.whiteboard.logic

import kotlin.math.abs
import kotlin.math.roundToInt

enum class CropHandle { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT, LEFT, TOP, RIGHT, BOTTOM, MOVE, NONE }

/** Integer pixel rectangle (left/top inclusive, width/height >= 1). */
data class PixelRect(val left: Int, val top: Int, val width: Int, val height: Int)

/** Crop rectangle editing in normalised (0..1) image coordinates. */
object CropMath {
    val FULL = Box(0f, 0f, 1f, 1f)

    /** Default crop: the whole page minus a small margin. */
    fun initial(): Box = Box(0.06f, 0.06f, 0.94f, 0.94f)

    /** Which handle a touch at normalised ([x], [y]) grabs; tolerances are normalised per axis. */
    fun hitTest(r: Box, x: Float, y: Float, tolX: Float, tolY: Float): CropHandle {
        val nearL = abs(x - r.left) <= tolX
        val nearR = abs(x - r.right) <= tolX
        val nearT = abs(y - r.top) <= tolY
        val nearB = abs(y - r.bottom) <= tolY
        val inX = x >= r.left - tolX && x <= r.right + tolX
        val inY = y >= r.top - tolY && y <= r.bottom + tolY
        return when {
            nearL && nearT -> CropHandle.TOP_LEFT
            nearR && nearT -> CropHandle.TOP_RIGHT
            nearL && nearB -> CropHandle.BOTTOM_LEFT
            nearR && nearB -> CropHandle.BOTTOM_RIGHT
            nearL && inY -> CropHandle.LEFT
            nearR && inY -> CropHandle.RIGHT
            nearT && inX -> CropHandle.TOP
            nearB && inX -> CropHandle.BOTTOM
            r.contains(x, y) -> CropHandle.MOVE
            else -> CropHandle.NONE
        }
    }

    /** Applies a normalised drag to [handle], keeping the rectangle inside 0..1 and at least [minSize]. */
    fun drag(r: Box, handle: CropHandle, dx: Float, dy: Float, minSize: Float = 0.04f): Box {
        var l = r.left; var t = r.top; var rt = r.right; var b = r.bottom
        when (handle) {
            CropHandle.MOVE -> {
                val mx = dx.coerceIn(-l, 1f - rt)
                val my = dy.coerceIn(-t, 1f - b)
                return Box(l + mx, t + my, rt + mx, b + my)
            }
            CropHandle.NONE -> return r
            else -> Unit
        }
        if (handle == CropHandle.TOP_LEFT || handle == CropHandle.BOTTOM_LEFT || handle == CropHandle.LEFT) l = (l + dx).coerceIn(0f, rt - minSize)
        if (handle == CropHandle.TOP_RIGHT || handle == CropHandle.BOTTOM_RIGHT || handle == CropHandle.RIGHT) rt = (rt + dx).coerceIn(l + minSize, 1f)
        if (handle == CropHandle.TOP_LEFT || handle == CropHandle.TOP_RIGHT || handle == CropHandle.TOP) t = (t + dy).coerceIn(0f, b - minSize)
        if (handle == CropHandle.BOTTOM_LEFT || handle == CropHandle.BOTTOM_RIGHT || handle == CropHandle.BOTTOM) b = (b + dy).coerceIn(t + minSize, 1f)
        return Box(l, t, rt, b)
    }

    /** The crop in pixels of a [width] x [height] image, clamped and at least 1x1. */
    fun toPixels(r: Box, width: Int, height: Int): PixelRect {
        val l = (r.left.coerceIn(0f, 1f) * width).roundToInt().coerceIn(0, width - 1)
        val t = (r.top.coerceIn(0f, 1f) * height).roundToInt().coerceIn(0, height - 1)
        val rr = (r.right.coerceIn(0f, 1f) * width).roundToInt().coerceIn(l + 1, width)
        val bb = (r.bottom.coerceIn(0f, 1f) * height).roundToInt().coerceIn(t + 1, height)
        return PixelRect(l, t, rr - l, bb - t)
    }
}

/** Maths for exporting a dragged region of the board as an image. */
object ExportMath {
    /** Converts a rectangle in view pixels to board points, given the [visible] board region shown in a [viewWidth] x [viewHeight] view. */
    fun viewToBoard(view: Box, viewWidth: Float, viewHeight: Float, visible: Box): Box {
        val sx = visible.width / maxOf(viewWidth, 1f)
        val sy = visible.height / maxOf(viewHeight, 1f)
        return Box.of(visible.left + view.left * sx, visible.top + view.top * sy, visible.left + view.right * sx, visible.top + view.bottom * sy)
    }

    /**
     * Output pixels per board point: [factor] times the on-screen density ([pxPerPoint]),
     * at least [minScale], and small enough that neither side exceeds [maxSide] pixels.
     */
    fun outputScale(
        region: Box,
        pxPerPoint: Float,
        factor: Float = 2f,
        maxSide: Float = 6000f,
        minScale: Float = 1f,
        maxPixels: Float = 12_000_000f,
    ): Float {
        var s = maxOf(pxPerPoint * factor, minScale)
        val w = maxOf(region.width, 1f)
        val h = maxOf(region.height, 1f)
        if (maxOf(w, h) * s > maxSide) s = maxSide / maxOf(w, h)
        if (w * h * s * s > maxPixels) s = kotlin.math.sqrt(maxPixels / (w * h))
        return s
    }

    /**
     * Whether a save target whose image could not be written may be deleted: only when it is
     * known to be the empty document the picker just created ([targetSize] == 0). An unknown
     * size or any content means the user may have picked an existing file to overwrite.
     */
    fun mayDeleteUnwrittenTarget(targetSize: Long?): Boolean = targetSize == 0L
}
