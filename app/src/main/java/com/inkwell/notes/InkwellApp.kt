package com.inkwell.notes

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.inkwell.notes.app.AppGraph
import com.inkwell.notes.core.diagnostics.CrashReports
import com.inkwell.notes.speakers.ui.VoiceSampleRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class InkwellApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashReports.install(this)
        val graph = AppGraph.init(this)
        createNotificationChannels()
        graph.appScope.launch(Dispatchers.IO) {
            // Why the previous process ended, if it crashed (Settings > About shows it).
            runCatching { CrashReports.collect(this@InkwellApp) }
            // An enrollment sample (the user's voice, unencrypted) that a crash or kill left
            // behind: no enrollment can be under way in a process that is only starting.
            VoiceSampleRecorder.sweep(this@InkwellApp, olderThanMs = 0)
        }
        graph.appScope.launch {
            runCatching { graph.recording.recoverInterrupted() }
            runCatching { graph.naming.schedule() }
        }
        graph.appScope.launch {
            // Well after startup: creating the speaker service makes it prune the encrypted voice
            // data of recordings that no longer exist (documents deleted while the process was
            // dead, or before the delete path told it), even when no screen touches speakers.
            delay(SPEAKER_PRUNE_DELAY_MS)
            runCatching { graph.speakers }
        }
    }

    private fun createNotificationChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_RECORDING, getString(R.string.recording_channel_name), NotificationManager.IMPORTANCE_LOW).apply {
                description = getString(R.string.recording_channel_description)
                setShowBadge(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_SYNC, getString(R.string.sync_channel_name), NotificationManager.IMPORTANCE_MIN),
        )
    }

    companion object {
        const val CHANNEL_RECORDING = "recording"
        const val CHANNEL_SYNC = "sync"
        /** Startup stays quick; the prune itself is disk work on the speaker service's own scope. */
        private const val SPEAKER_PRUNE_DELAY_MS = 20_000L
    }
}
