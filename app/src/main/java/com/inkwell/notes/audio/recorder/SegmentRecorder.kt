package com.inkwell.notes.audio.recorder

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import java.io.File
import java.io.IOException

/**
 * MediaRecorder writing consecutive, independently playable AAC/M4A segment files.
 *
 * Rollover is gapless: each file gets a size limit worth about one segment of audio at the
 * configured bitrate; when the recorder reports MAX_FILESIZE_APPROACHING the next file is
 * handed over with setNextOutputFile, and NEXT_OUTPUT_FILE_STARTED means the previous file
 * was closed and is complete on disk. (MediaRecorder only supports seamless switching for
 * the size limit; a duration limit stops the recorder instead.) If a switch is missed, or a
 * segment runs far longer than intended (very quiet VBR audio), [forceRoll] restarts the
 * recorder into the next file instead.
 *
 * Thread-confined: create and call it on one thread with a Looper (the recorder thread);
 * MediaRecorder delivers its callbacks on that thread.
 */
internal class SegmentRecorder(
    private val context: Context,
    private val config: Config,
    private val fileFor: (Int) -> File,
    private val listener: Listener,
) {
    data class Config(val sampleRate: Int, val bitrate: Int, val segmentMs: Long)

    enum class Failure { MIC_LOST, SERVER_DIED, MIC_TAKEN, STORAGE_FULL }

    interface Listener {
        /** Segment [index] was finalised into [file]; recording goes on in the next one. */
        fun onSegmentFinished(index: Int, file: File)

        /** The recorder failed and has been released. */
        fun onRecorderError(failure: Failure)
    }

    /** Result of [stop]: the file of the last segment and whether the recorder closed it cleanly. */
    data class Stopped(val index: Int, val file: File, val clean: Boolean)

    private var recorder: MediaRecorder? = null
    private var nextQueued = false
    private var released = false

    /** Segment currently being written. */
    var currentIndex: Int = 0
        private set

    val isRunning: Boolean get() = recorder != null

    /** Starts writing segment [index]. Throws when the microphone cannot be opened. */
    fun start(index: Int) {
        currentIndex = index
        val mr = create(fileFor(index))
        try {
            mr.start()
        } catch (t: Throwable) {
            // Not ours yet: free the prepared recorder now rather than at some GC.
            runCatching { mr.reset() }
            runCatching { mr.release() }
            throw t
        }
        recorder = mr
    }

    fun pause() {
        recorder?.pause()
    }

    fun resume() {
        recorder?.resume()
    }

    /** Peak amplitude since the last call (0..32767), 0 when unavailable. */
    fun maxAmplitude(): Int = try {
        recorder?.maxAmplitude ?: 0
    } catch (_: RuntimeException) {
        0
    }

    /** Whether the system is silencing this recorder's own capture (another app took the microphone). */
    fun isSilenced(): Boolean = try {
        recorder?.activeRecordingConfiguration?.isClientSilenced == true
    } catch (_: RuntimeException) {
        false
    }

    /** Closes the current segment and continues in a new recorder (brief gap). */
    fun forceRoll() {
        val old = recorder ?: return
        val finished = currentIndex
        recorder = null
        nextQueued = false
        stopQuietly(old)
        listener.onSegmentFinished(finished, fileFor(finished))
        startNextOrFail(finished + 1)
    }

    /** Stops recording, finalising the current segment. */
    fun stop(): Stopped {
        val r = recorder
        val index = currentIndex
        recorder = null
        released = true
        val clean = r != null && stopQuietly(r)
        return Stopped(index, fileFor(index), clean)
    }

    /** Releases without finalising (after errors). */
    fun release() {
        released = true
        recorder?.let { runCatching { it.reset() }; runCatching { it.release() } }
        recorder = null
    }

    private fun maxBytes(): Long {
        val bytesPerSecond = config.bitrate.coerceAtLeast(16_000) / 8.0
        return (bytesPerSecond * config.segmentMs.coerceAtLeast(10_000) / 1000.0).toLong().coerceAtLeast(MIN_SEGMENT_BYTES)
    }

    @Suppress("DEPRECATION")
    private fun newRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else MediaRecorder()

    private fun create(file: File): MediaRecorder {
        file.parentFile?.mkdirs()
        val mr = newRecorder()
        try {
            mr.setAudioSource(MediaRecorder.AudioSource.MIC)
            mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            mr.setAudioChannels(1)
            mr.setAudioSamplingRate(config.sampleRate.coerceIn(8_000, 48_000))
            mr.setAudioEncodingBitRate(config.bitrate.coerceIn(16_000, 320_000))
            mr.setMaxFileSize(maxBytes())
            mr.setOutputFile(file)
            mr.setOnInfoListener { r, what, _ -> if (r === recorder) onInfo(r, what) }
            mr.setOnErrorListener { r, what, extra -> if (r === recorder) onError(what, extra) }
            mr.prepare()
        } catch (t: Throwable) {
            runCatching { mr.release() }
            throw t
        }
        return mr
    }

    private fun onInfo(r: MediaRecorder, what: Int) {
        when (what) {
            MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_APPROACHING -> if (!nextQueued) {
                try {
                    r.setNextOutputFile(fileFor(currentIndex + 1))
                    nextQueued = true
                } catch (e: IOException) {
                    Log.w(TAG, "Next segment file rejected; will restart at the limit", e)
                } catch (e: IllegalStateException) {
                    Log.w(TAG, "Next segment file rejected; will restart at the limit", e)
                }
            }
            MediaRecorder.MEDIA_RECORDER_INFO_NEXT_OUTPUT_FILE_STARTED -> {
                val finished = currentIndex
                currentIndex = finished + 1
                nextQueued = false
                listener.onSegmentFinished(finished, fileFor(finished))
            }
            MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED,
            MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED -> {
                // The recorder stopped on its own (the next file was not accepted in time).
                val finished = currentIndex
                recorder = null
                nextQueued = false
                stopQuietly(r)
                listener.onSegmentFinished(finished, fileFor(finished))
                startNextOrFail(finished + 1)
            }
        }
    }

    private fun startNextOrFail(index: Int) {
        if (released) return
        try {
            start(index)
        } catch (t: Throwable) {
            Log.w(TAG, "Could not continue into segment $index", t)
            recorder = null
            listener.onRecorderError(Failure.MIC_LOST)
        }
    }

    private fun onError(what: Int, extra: Int) {
        Log.w(TAG, "MediaRecorder error what=$what extra=$extra")
        val r = recorder
        recorder = null
        if (r != null) {
            // Try to finalise what was written so far; the session keeps it if readable.
            stopQuietly(r)
        }
        listener.onRecorderError(if (what == MediaRecorder.MEDIA_ERROR_SERVER_DIED) Failure.SERVER_DIED else Failure.MIC_LOST)
    }

    /** stop() + release(); true when stop() succeeded (the file is complete). */
    private fun stopQuietly(r: MediaRecorder): Boolean {
        val ok = try {
            r.stop()
            true
        } catch (_: RuntimeException) {
            // Thrown when no audio was captured or the recorder already stopped itself.
            false
        }
        runCatching { r.reset() }
        runCatching { r.release() }
        return ok
    }

    companion object {
        private const val TAG = "SegmentRecorder"
        private const val MIN_SEGMENT_BYTES = 64L * 1024
    }
}
