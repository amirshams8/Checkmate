package com.checkmate.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat

/**
 * MentorNotifier — Mentor v2 (spec 3.2).
 *
 * Small, standalone notification helper for proactive Mentor messages (skip reactions,
 * distraction-threshold reactions, idle check-ins). Kept separate from ReminderService's
 * own "reminder_channel" foreground notification since these are one-shot alerts about
 * something Mentor said, not the always-on "Monitoring tasks…" ongoing notification.
 */
object MentorNotifier {

    private const val CHANNEL_ID = "mentor_channel"
    private const val NOTIF_ID   = 77

    fun notify(context: Context, text: String) {
        createChannel(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("Mentor")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIF_ID, notification)
    }

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "Mentor", NotificationManager.IMPORTANCE_DEFAULT)
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(ch)
        }
    }
}
