package com.inkwell.notes.audio

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.inkwell.notes.R
import com.inkwell.notes.app.AppGraph
import com.inkwell.notes.audio.logic.SegmentTimeline
import com.inkwell.notes.audio.recorder.AudioFiles
import com.inkwell.notes.core.model.Recording
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Plays a recording's segments as one gapless ExoPlayer playlist (one item per segment).
 * Positions are global: the sum of the previous segments' durations plus the position in
 * the current one, so they match the audio anchors stored on notes. Audio focus is handled
 * by the player, playback pauses when headphones are unplugged, and the player is released
 * after a while unused (it is rebuilt transparently on the next play or seek).
 *
 * Calls may come from any thread; they run on the main thread, where the player lives.
 */
class PlaybackControllerImpl(private val graph: AppGraph) : PlaybackController {
    private val _state = MutableStateFlow(PlaybackState())
    override val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var player: ExoPlayer? = null
    private var loadedFor: Recording? = null
    private var timeline = SegmentTimeline(emptyList())
    private var ticker: Job? = null
    private var idleRelease: Job? = null
    private var loadJob: Job? = null
    /** Bumped on every load so a slow file check never applies to a newer recording. */
    private var generation = 0

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _state.update { it.copy(playing = isPlaying, positionMs = currentGlobal()) }
            if (isPlaying) startTicker() else stopTicker()
            scheduleIdleRelease()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                _state.update { it.copy(playing = false, positionMs = timeline.totalMs) }
                stopTicker()
                scheduleIdleRelease()
            }
        }

        // Paused or let go while held back (see isHeld) without isPlaying changing: idle again.
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = scheduleIdleRelease()

        override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) = scheduleIdleRelease()

        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            _state.update { it.copy(positionMs = currentGlobal()) }
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.w(TAG, "Playback failed (${error.errorCodeName})")
            _state.update { it.copy(playing = false, error = graph.context.getString(R.string.audio_playback_error)) }
            stopTicker()
        }
    }

    override fun load(recording: Recording) = onMain {
        val current = loadedFor
        if (current != null && current.id == recording.id && current.segmentFiles == recording.segmentFiles &&
            current.segmentDurationsMs == recording.segmentDurationsMs
        ) {
            loadedFor = recording
            _state.update { it.copy(recording = recording, error = null) }
            return@onMain
        }
        val gen = ++generation
        stopTicker()
        pendingPlay = false
        // Drop the previous playlist so seeks before the new one is ready can't land in it.
        player?.run {
            stop()
            clearMediaItems()
        }
        loadedFor = recording
        timeline = SegmentTimeline(recording.segmentDurationsMs.take(recording.segmentFiles.size).let { d ->
            d + List(recording.segmentFiles.size - d.size) { 0L }
        })
        _state.value = PlaybackState(
            recording = recording,
            positionMs = 0,
            durationMs = timeline.totalMs.takeIf { it > 0 } ?: recording.durationMs,
            playing = false,
            speed = _state.value.speed,
        )
        // File checks are disk I/O: resolve the playable segments off the main thread.
        loadJob?.cancel()
        loadJob = scope.launch {
            val files = withContext(Dispatchers.IO) { AudioFiles.segments(graph.files, recording) }
            val playable = withContext(Dispatchers.IO) { files.indices.filter { files[it].isFile && files[it].length() > 0 } }
            if (gen != generation) return@launch
            timeline = SegmentTimeline(timeline.let { t -> List(t.segmentCount) { t.durationOf(it) } }, playable)
            if (playable.isEmpty()) {
                _state.update { it.copy(error = graph.context.getString(R.string.audio_no_audio)) }
                return@launch
            }
            val p = ensurePlayer()
            val target = _state.value.positionMs
            // Play at the end (the player was freed after it ended): start over, as play() does.
            val restart = pendingPlay && target >= timeline.totalMs
            val item = timeline.locateItem(if (restart) 0L else target) ?: SegmentTimeline.Position(0, 0)
            if (restart) _state.update { it.copy(positionMs = timeline.globalOfItem(item.index, item.offsetMs)) }
            p.setMediaItems(playable.map { MediaItem.fromUri(Uri.fromFile(files[it])) }, item.index, item.offsetMs)
            p.setPlaybackSpeed(_state.value.speed)
            p.prepare()
            if (pendingPlay) {
                pendingPlay = false
                p.play()
            } else {
                scheduleIdleRelease()
            }
        }
    }

    /** play() arrived before the segment files were resolved. */
    private var pendingPlay = false

    override fun play() = onMain {
        val rec = loadedFor ?: return@onMain
        _state.update { it.copy(error = null) }
        val p = player
        if (p == null || p.mediaItemCount == 0) {
            // Not prepared yet (just loaded) or released while idle: rebuild, then play.
            pendingPlay = true
            if (loadJob?.isActive != true) reload(rec)
            return@onMain
        }
        if (p.playbackState == Player.STATE_ENDED || _state.value.positionMs >= timeline.totalMs) {
            seekInternal(p, 0)
        }
        if (p.playbackState == Player.STATE_IDLE) p.prepare()
        p.play()
    }

    override fun pause() = onMain {
        pendingPlay = false
        player?.pause()
        _state.update { it.copy(playing = false, positionMs = currentGlobal()) }
    }

    override fun seekTo(positionMs: Long) = onMain {
        val target = positionMs.coerceIn(0, timeline.totalMs.coerceAtLeast(0))
        _state.update { it.copy(positionMs = target) }
        val p = player
        if (p == null || p.mediaItemCount == 0) {
            // Applied when the playlist is (re)built.
            val rec = loadedFor
            if (rec != null && loadJob?.isActive != true && p == null) reload(rec)
            return@onMain
        }
        seekInternal(p, target)
    }

    override fun setSpeed(speed: Float) = onMain {
        val s = speed.coerceIn(MIN_SPEED, MAX_SPEED)
        player?.setPlaybackSpeed(s)
        _state.update { it.copy(speed = s) }
    }

    override fun release() = onMain {
        generation++
        loadJob?.cancel()
        stopTicker()
        idleRelease?.cancel()
        releasePlayer()
        loadedFor = null
        pendingPlay = false
        timeline = SegmentTimeline(emptyList())
        _state.value = PlaybackState(speed = _state.value.speed)
    }

    // ------------------------------------------------------------------ internals

    private fun reload(rec: Recording) {
        val keep = _state.value.positionMs
        val wantPlay = pendingPlay
        loadedFor = null
        load(rec)
        _state.update { it.copy(positionMs = keep) }
        pendingPlay = wantPlay
    }

    private fun seekInternal(p: ExoPlayer, globalMs: Long) {
        val item = timeline.locateItem(globalMs) ?: return
        p.seekTo(item.index, item.offsetMs)
        _state.update { it.copy(positionMs = timeline.globalOfItem(item.index, item.offsetMs)) }
    }

    private fun ensurePlayer(): ExoPlayer {
        player?.let { return it }
        val attrs = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
            .build()
        return ExoPlayer.Builder(graph.context)
            .setAudioAttributes(attrs, /* handleAudioFocus = */ true)
            .setHandleAudioBecomingNoisy(true)
            .build()
            .also {
                it.addListener(listener)
                player = it
            }
    }

    private fun releasePlayer() {
        player?.let {
            it.removeListener(listener)
            runCatching { it.release() }
        }
        player = null
    }

    private fun currentGlobal(): Long {
        val p = player ?: return _state.value.positionMs
        if (p.mediaItemCount == 0) return _state.value.positionMs
        return timeline.globalOfItem(p.currentMediaItemIndex, p.currentPosition)
    }

    private fun startTicker() {
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            while (isActive) {
                _state.update { it.copy(positionMs = currentGlobal()) }
                delay(TICK_MS)
            }
        }
    }

    private fun stopTicker() {
        ticker?.cancel()
        ticker = null
    }

    /** Frees the decoder and audio session after a while without playback. */
    private fun scheduleIdleRelease() {
        idleRelease?.cancel()
        if (player?.let { it.isPlaying || isHeld(it) } == true) return
        idleRelease = scope.launch {
            delay(IDLE_RELEASE_MS)
            val p = player
            if (p == null || (!p.isPlaying && !isHeld(p))) {
                // An ended player reports the end of its last file: keep "at the end" so play() starts over.
                val ended = p?.playbackState == Player.STATE_ENDED
                _state.update { it.copy(positionMs = if (ended) timeline.totalMs else currentGlobal(), playing = false) }
                releasePlayer()
            }
        }
    }

    /** Meant to play but held back (a call took audio focus): it resumes by itself, so keep it. */
    private fun isHeld(p: ExoPlayer): Boolean =
        p.playWhenReady && p.playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE

    private inline fun onMain(crossinline block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post { block() }
    }

    private companion object {
        const val TAG = "Playback"
        const val TICK_MS = 100L
        const val IDLE_RELEASE_MS = 90_000L
        const val MIN_SPEED = 0.5f
        const val MAX_SPEED = 3f
    }
}
