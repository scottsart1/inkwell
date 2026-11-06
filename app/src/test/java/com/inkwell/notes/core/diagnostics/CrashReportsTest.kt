package com.inkwell.notes.core.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.time.ZoneId

/** The pieces of the local crash report that do not need Android. */
class CrashReportsTest {
    // ---- a tiny protobuf writer for test tombstones
    private class Pb {
        val out = ByteArrayOutputStream()
        fun varint(v: Long) { var x = v; while (true) { if (x and 0x7FL.inv() == 0L) { out.write(x.toInt()); return }; out.write(((x and 0x7F) or 0x80).toInt()); x = x ushr 7 } }
        fun tag(field: Int, wire: Int) = varint(((field shl 3) or wire).toLong())
        fun int(field: Int, v: Long) = apply { tag(field, 0); varint(v) }
        fun bytes(field: Int, b: ByteArray) = apply { tag(field, 2); varint(b.size.toLong()); out.write(b) }
        fun str(field: Int, s: String) = bytes(field, s.toByteArray())
        fun msg(field: Int, m: Pb) = bytes(field, m.out.toByteArray())
        fun fixed64(field: Int) = apply { tag(field, 1); out.write(ByteArray(8)) }
        fun build() = out.toByteArray()
    }

    private fun frame(fn: String, file: String, offset: Long = 0) = Pb().int(1, 0x1234).str(4, fn).int(5, offset).str(6, file)

    private fun thread(name: String, vararg frames: Pb) = Pb().int(1, 42).str(2, name).also { t ->
        // Registers and memory dumps are skipped.
        t.msg(3, Pb().str(1, "x0").int(2, 7))
        frames.forEach { t.msg(4, it) }
        t.msg(5, Pb().str(1, "secret memory bytes"))
    }

    @Test
    fun aNativeAbortIsSummarisedWithoutMemoryOrLogs() {
        val crashing = thread(
            "InkwellVoices",
            frame("abort", "/apex/com.android.runtime/lib64/bionic/libc.so", 164),
            frame("art::JavaVMExt::JniAbort(char const*, char const*)", "/apex/com.android.art/lib64/libart.so"),
            frame("Java_com_k2fsa_sherpa_onnx_OfflineSpeakerDiarization_processWithCallback", "/data/app/lib/arm64/libsherpa-onnx-jni.so", 512),
        )
        val other = thread("main", frame("epoll_wait", "libc.so"))
        val tomb = Pb()
            .int(1, 3)
            .str(2, "samsung/build/fingerprint")
            .int(5, 100).int(6, 42)
            .msg(10, Pb().int(1, 6).str(2, "SIGABRT").str(4, "SI_TKILL"))
            .str(14, "JNI DETECTED ERROR IN APPLICATION: JNI NewObjectArray called with pending exception java.lang.NoSuchMethodError")
            .msg(15, Pb().str(1, "Abort called"))
            .msg(16, Pb().int(1, 7).msg(2, other))
            .msg(16, Pb().int(1, 42).msg(2, crashing))
            .msg(18, Pb().str(1, "main").str(2, "log line with user text"))
            .fixed64(99)
            .build()
        val s = TombstoneSummary.parse(tomb)!!
        assertEquals("SIGABRT (6) code SI_TKILL", s.signal)
        assertTrue(s.abortMessage!!.startsWith("JNI DETECTED ERROR"))
        assertEquals(listOf("Abort called"), s.causes)
        assertEquals("InkwellVoices", s.threadName)
        assertEquals("abort+164 (libc.so)", s.frames[0])
        assertEquals("Java_com_k2fsa_sherpa_onnx_OfflineSpeakerDiarization_processWithCallback+512 (libsherpa-onnx-jni.so)", s.frames[2])
        val text = s.render()
        assertFalse(text.contains("secret memory"))
        assertFalse(text.contains("user text"))
        assertFalse(text.contains("fingerprint"))
    }

    @Test
    fun garbageIsNotATombstone() {
        assertNull(TombstoneSummary.parse(ByteArray(0)))
        assertNull(TombstoneSummary.parse(byteArrayOf(0x0A, 0x7F, 0x01)))
        assertNull(TombstoneSummary.parse("plain text trace".toByteArray()))
    }

    @Test
    fun longBacktracesAreCut() {
        val t = thread("w", *Array(40) { frame("f$it", "lib.so") })
        val tomb = Pb().int(6, 42).msg(16, Pb().int(1, 42).msg(2, t)).build()
        val text = TombstoneSummary.parse(tomb)!!.render(maxFrames = 10)
        assertTrue(text.contains("#09 f9 (lib.so)"))
        assertFalse(text.contains("f10 "))
        assertTrue(text.contains("30 more frames"))
    }

    @Test
    fun javaCrashTextRoundTripsAndMatchesItsExit() {
        val t = CrashText.javaCrash(1_000_000L, "main", IllegalStateException("boom"))
        assertEquals(1_000_000L, CrashText.javaCrashTime(t))
        assertTrue(CrashText.javaCrashBody(t).contains("IllegalStateException: boom"))
        assertTrue(CrashText.sameCrash(1_000_000L, 1_002_000L))
        assertFalse(CrashText.sameCrash(1_000_000L, 1_000_000L + 10 * 60_000))
    }

    @Test
    fun theSavedExitTimeIsReadBackOrLeftToTheFallback() {
        // The report is written at the next start; the card shows when the app actually stopped.
        assertEquals(1_760_000_000_123L, CrashText.savedExitTime("1760000000123"))
        assertEquals(1_760_000_000_123L, CrashText.savedExitTime(" 1760000000123\n"))
        assertNull(CrashText.savedExitTime(null))
        assertNull(CrashText.savedExitTime(""))
        assertNull(CrashText.savedExitTime("yesterday"))
        assertNull(CrashText.savedExitTime("0"))
        assertNull(CrashText.savedExitTime("-5"))
    }

    @Test
    fun theReportNamesWhatHappened() {
        val r = CrashText.report(
            app = "1.1.1 (3), Android 16, samsung SM-X930",
            exitMs = 0L,
            reason = CrashText.REASON_CRASH_NATIVE,
            status = 6,
            description = null,
            details = "Signal: SIGABRT (6)\nAbort: JNI DETECTED ERROR IN APPLICATION: boom",
            zone = ZoneId.of("UTC"),
        )
        assertTrue(r.contains("When: 1970-01-01 00:00:00"))
        assertTrue(r.contains("Reason: Crash (native)"))
        assertEquals("Abort: JNI DETECTED ERROR IN APPLICATION: boom", CrashText.headline(r))
        assertTrue(CrashText.REASON_CRASH_NATIVE in CrashText.REPORTED)
        assertFalse(CrashText.REASON_LOW_MEMORY in CrashText.REPORTED)
    }
}
