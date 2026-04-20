package com.checkmate.workmode

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*

/**
 * Foreground service that holds Work Mode active.
 * App blocking is enforced by AppAutomationService (accessibility)
 * which checks WorkModeManager.getBlockedApps() on every window change.
 */
class WorkModeService : Service() {

    companion object {
        private const val CHANNEL_ID = "work_mode_channel"
        private const val NOTIF_ID   = 77
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIF_ID, buildNotification())
    }

    private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("Work Mode — ON")
        .setContentText("Distraction apps are blocked")
        .setSmallIcon(android.R.drawable.ic_lock_lock)
        .setOngoing(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .build()

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "Work Mode", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
    }

    override fun onDestroy() { super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
}
