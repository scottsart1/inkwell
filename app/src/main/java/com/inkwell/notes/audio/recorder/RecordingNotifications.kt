package com.inkwell.notes.audio.recorder

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.compose.ui.graphics.toArgb
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.inkwell.notes.InkwellApp
import com.inkwell.notes.MainActivity
import com.inkwell.notes.R
import com.inkwell.notes.app.AppGraph
import com.inkwell.notes.audio.RecordingService
import com.inkwell.notes.audio.logic.AudioText
import com.inkwell.notes.ui.theme.LightInkwell

/** The ongoing "Recording · <document> · 18:40" notification with Pause/Resume and Stop. */
internal class RecordingNotifications(private val context: Context) {
    data class Content(val docTitle: String, val elapsedSeconds: Long, val paused: Boolean)

    fun build(content: Content?): Notification {
        val docTitle = content?.docTitle?.ifBlank { null } ?: context.getString(R.string.audio_untitled_doc)
        val time = AudioText.clock((content?.elapsedSeconds ?: 0L) * 1000)
        val paused = content?.paused == true
        // The document name stays off the lock screen, and out of the notification entirely with app lock on.
        val generic = context.getString(if (paused) R.string.audio_notif_paused_generic else R.string.audio_notif_recording_generic, time)
        val title = if (appLocked()) generic
        else context.getString(if (paused) R.string.audio_notif_paused else R.string.audio_notif_recording, docTitle, time)
        val publicVersion = base(generic, content, paused).build()
        return base(title, content, paused)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .build()
    }

    private fun base(title: String, content: Content?, paused: Boolean): NotificationCompat.Builder {
        val builder = NotificationCompat.Builder(context, InkwellApp.CHANNEL_RECORDING)
            .setSmallIcon(R.drawable.ic_stat_record)
            .setContentTitle(title)
            .setContentText(context.getString(R.string.audio_notif_text))
            .setContentIntent(openApp())
            .setColor(LightInkwell.record.toArgb())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setLocalOnly(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (content != null) {
            if (paused) {
                builder.addAction(0, context.getString(R.string.audio_notif_resume), serviceAction(RecordingService.ACTION_RESUME, REQ_RESUME))
            } else {
                builder.addAction(0, context.getString(R.string.audio_notif_pause), serviceAction(RecordingService.ACTION_PAUSE, REQ_PAUSE))
            }
            builder.addAction(0, context.getString(R.string.audio_notif_stop), serviceAction(RecordingService.ACTION_STOP, REQ_STOP))
        }
        return builder
    }

    private fun appLocked(): Boolean = runCatching { AppGraph.get(context).settings.current.privacy.appLock }.getOrDefault(false)

    /** Replaces the posted notification; silently skipped when notifications are not allowed. */
    fun update(content: Content) {
        try {
            val nm = NotificationManagerCompat.from(context)
            if (nm.areNotificationsEnabled()) nm.notify(NOTIFICATION_ID, build(content))
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS not granted: the recording continues without the notification text update.
        }
    }

    private fun openApp(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(context, REQ_OPEN, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun serviceAction(action: String, request: Int): PendingIntent =
        PendingIntent.getService(
            context, request,
            Intent(context, RecordingService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    companion object {
        const val NOTIFICATION_ID = 0x1A0D
        private const val REQ_OPEN = 10
        private const val REQ_PAUSE = 11
        private const val REQ_RESUME = 12
        private const val REQ_STOP = 13
    }
}
