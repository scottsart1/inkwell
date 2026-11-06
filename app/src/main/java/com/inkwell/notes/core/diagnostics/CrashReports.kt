package com.inkwell.notes.core.diagnostics

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.util.Log
import com.inkwell.notes.BuildConfig
import com.inkwell.notes.core.storage.FileStore
import java.io.File
import kotlin.system.exitProcess

/**
 * Local crash reports, so a crash on the tablet can be diagnosed without a computer: the stack
 * trace of an uncaught Java exception (written as the process dies) and, at the next start,
 * Android's own record of why the process ended (ApplicationExitInfo: Java crash, native crash
 * with a summary of its tombstone, ANR, excessive resource use). Kept only on this device
 * (noBackupFilesDir), never uploaded; Settings > About shows the last one with Copy and Clear.
 * Holds no note content, transcripts or voice data: stack traces, signals and abort messages.
 */
object CrashReports {
    data class Report(val exitMs: Long, val headline: String, val text: String)

    private const val TAG = "CrashReports"
    private const val DIR = "diagnostics"
    private const val JAVA = "java-crash.txt"
    private const val REPORT = "last-crash.txt"
    /** When the reported exit happened: the report is written at the next start, so its file time is not. */
    private const val REPORT_TIME = "last-crash-time.txt"
    private const val SEEN = "seen-exit.txt"
    private const val MAX_TRACE_BYTES = 4 * 1024 * 1024

    /** Records uncaught Java exceptions, then lets the platform handler end the process as usual. */
    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { File(dir(app), JAVA).apply { parentFile?.mkdirs() }.writeText(CrashText.javaCrash(System.currentTimeMillis(), thread.name, error)) }
            if (previous != null) previous.uncaughtException(thread, error) else exitProcess(10)
        }
    }

    /** Turns the newest unseen abnormal exit into the report. Disk work: call off the main thread. */
    fun collect(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val am = context.getSystemService(ActivityManager::class.java) ?: return
        val dir = dir(context)
        val seenFile = File(dir, SEEN)
        val seen = runCatching { seenFile.readText().trim().toLong() }.getOrDefault(0L)
        val exits = runCatching { am.getHistoricalProcessExitReasons(context.packageName, 0, 8) }.getOrElse {
            Log.w(TAG, "Exit reasons unavailable: ${it.javaClass.simpleName}")
            return
        }.filter { it.timestamp > seen }
        if (exits.isEmpty()) return
        runCatching { FileStore.writeAtomically(seenFile, keepBackup = false) { it.write(exits.maxOf { e -> e.timestamp }.toString().toByteArray()) } }
        val exit = exits.filter { it.reason in CrashText.REPORTED }.maxByOrNull { it.timestamp } ?: return
        val details = when (exit.reason) {
            CrashText.REASON_CRASH_NATIVE -> nativeDetails(exit)
            CrashText.REASON_ANR -> readTrace(exit)?.toString(Charsets.UTF_8)?.take(CrashText.MAX_CHARS)
            CrashText.REASON_CRASH -> javaDetails(dir, exit.timestamp)
            else -> null
        }
        val app = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}), Android ${Build.VERSION.RELEASE}, ${Build.MANUFACTURER} ${Build.MODEL}"
        val text = CrashText.report(app, exit.timestamp, exit.reason, exit.status, exit.description, details)
        // Dropped first so an older report's time is never shown with this one.
        File(dir, REPORT_TIME).delete()
        runCatching { FileStore.writeAtomically(File(dir, REPORT), keepBackup = false) { it.write(text.toByteArray()) } }
            .onFailure { Log.w(TAG, "Could not save the crash report", it) }
            .onSuccess { runCatching { FileStore.writeAtomically(File(dir, REPORT_TIME), keepBackup = false) { it.write(exit.timestamp.toString().toByteArray()) } } }
    }

    /** The last report, or null. Disk read: call off the main thread. */
    fun latest(context: Context): Report? {
        val f = File(dir(context), REPORT)
        if (!f.isFile) return null
        val text = runCatching { f.readText() }.getOrNull() ?: return null
        val exitMs = CrashText.savedExitTime(runCatching { File(dir(context), REPORT_TIME).readText() }.getOrNull()) ?: f.lastModified()
        return Report(exitMs, CrashText.headline(text), text)
    }

    fun clear(context: Context) {
        File(dir(context), REPORT).delete()
        File(dir(context), REPORT_TIME).delete()
        File(dir(context), JAVA).delete()
    }

    private fun nativeDetails(exit: ApplicationExitInfo): String? {
        // Since Android 12 the trace of a native crash is its tombstone (protobuf).
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        val bytes = readTrace(exit) ?: return null
        return TombstoneSummary.parse(bytes)?.render()
    }

    private fun javaDetails(dir: File, exitMs: Long): String? {
        val f = File(dir, JAVA)
        val text = runCatching { if (f.isFile) f.readText() else null }.getOrNull() ?: return null
        val written = CrashText.javaCrashTime(text) ?: return null
        return if (CrashText.sameCrash(written, exitMs)) CrashText.javaCrashBody(text) else null
    }

    private fun readTrace(exit: ApplicationExitInfo): ByteArray? = runCatching {
        exit.traceInputStream?.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            while (out.size() < MAX_TRACE_BYTES) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
            }
            out.toByteArray()
        }
    }.getOrNull()

    private fun dir(context: Context) = File(context.noBackupFilesDir, DIR)
}
