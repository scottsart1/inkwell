package com.inkwell.notes.editor.logic

/**
 * A camera capture in flight: the file the camera app writes and where the photo goes. Kept in
 * saved state as one string, so a result delivered after process death is still inserted.
 */
data class PendingCapture(val path: String, val pageIndex: Int?, val x: Float?, val y: Float?) {

    fun encode(): String = listOf(pageIndex?.toString().orEmpty(), x?.toString().orEmpty(), y?.toString().orEmpty(), path).joinToString("|")

    companion object {
        /** The capture saved by [encode], or null when [saved] is not one. */
        fun decode(saved: String): PendingCapture? {
            val parts = saved.split('|', limit = 4)
            if (parts.size != 4 || parts[3].isEmpty()) return null
            return PendingCapture(parts[3], parts[0].toIntOrNull(), parts[1].toFloatOrNull(), parts[2].toFloatOrNull())
        }
    }
}
