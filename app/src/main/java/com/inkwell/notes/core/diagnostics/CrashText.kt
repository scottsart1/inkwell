package com.inkwell.notes.core.diagnostics

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Text of the local crash reports. Pure. */
object CrashText {
    /** Longest report kept; a report is for reading and pasting, not archiving. */
    const val MAX_CHARS = 24_000

    // ApplicationExitInfo.REASON_* values (stable public API constants).
    const val REASON_SIGNALED = 2
    const val REASON_LOW_MEMORY = 3
    const val REASON_CRASH = 4
    const val REASON_CRASH_NATIVE = 5
    const val REASON_ANR = 6
    const val REASON_EXCESSIVE_RESOURCE_USAGE = 9

    /** Exit reasons worth a report: the app stopped on its own, not because the user or system closed it normally. */
    val REPORTED = setOf(REASON_CRASH, REASON_CRASH_NATIVE, REASON_ANR, REASON_EXCESSIVE_RESOURCE_USAGE)

    fun reasonName(reason: Int): String = when (reason) {
        REASON_CRASH -> "Crash (Java)"
        REASON_CRASH_NATIVE -> "Crash (native)"
        REASON_ANR -> "Not responding (ANR)"
        REASON_EXCESSIVE_RESOURCE_USAGE -> "Stopped for excessive resource use"
        REASON_LOW_MEMORY -> "Stopped for low memory"
        REASON_SIGNALED -> "Killed by a signal"
        else -> "Exit reason $reason"
    }

    fun time(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(zone).format(Instant.ofEpochMilli(epochMs))

    /** What the uncaught-exception handler writes as the process dies. First line: the time in ms. */
    fun javaCrash(epochMs: Long, thread: String, error: Throwable): String =
        "$epochMs\nThread: $thread\n" + error.stackTraceToString().take(MAX_CHARS)

    /** The time a [javaCrash] text was written, or null. */
    fun javaCrashTime(text: String): Long? = text.substringBefore('\n').trim().toLongOrNull()

    /** The body of a [javaCrash] text, without its time line. */
    fun javaCrashBody(text: String): String = text.substringAfter('\n', "")

    /** The exit time saved next to the report (ms as text), or null when missing or unreadable. */
    fun savedExitTime(text: String?): Long? = text?.trim()?.toLongOrNull()?.takeIf { it > 0 }

    /** True when a Java crash written at [writtenMs] belongs to the process exit at [exitMs]. */
    fun sameCrash(writtenMs: Long, exitMs: Long): Boolean = writtenMs in (exitMs - 120_000)..(exitMs + 10_000)

    /** The full report shown in Settings and copied to share. */
    fun report(
        app: String,
        exitMs: Long,
        reason: Int,
        status: Int,
        description: String?,
        details: String?,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String = buildString {
        append("Inkwell crash report\n")
        append("App: ").append(app).append('\n')
        append("When: ").append(time(exitMs, zone)).append('\n')
        append("Reason: ").append(reasonName(reason)).append('\n')
        if (reason == REASON_CRASH_NATIVE || reason == REASON_SIGNALED) append("Signal: ").append(status).append('\n')
        description?.trim()?.takeIf { it.isNotEmpty() }?.let { append("Description: ").append(it).append('\n') }
        details?.trim()?.takeIf { it.isNotEmpty() }?.let { append('\n').append(it).append('\n') }
    }.take(MAX_CHARS).trimEnd()

    /** First line worth showing as a summary ("Abort: …", the exception line, or the reason). */
    fun headline(report: String): String {
        val lines = report.lines()
        val interesting = lines.firstOrNull { it.startsWith("Abort: ") }
            ?: lines.firstOrNull { it.contains("Exception") || it.contains("Error:") }
            ?: lines.firstOrNull { it.startsWith("Reason: ") }
            ?: lines.firstOrNull().orEmpty()
        return interesting.trim().take(200)
    }
}
