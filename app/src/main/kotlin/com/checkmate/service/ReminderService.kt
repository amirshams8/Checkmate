package com.checkmate.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.checkmate.core.tts.CheckmateTTS
import com.checkmate.planner.PlanStore
import com.checkmate.planner.model.TaskState
import kotlinx.coroutines.*

class ReminderService : Service() {
    companion object {
        private const val CHANNEL_ID = "reminder_channel"
        private const val NOTIF_ID   = 55

        fun start(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                context.startForegroundService(Intent(context, ReminderService::class.java))
            else
                context.startService(Intent(context, ReminderService::class.java))
        }
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIF_ID, buildNotification("Monitoring tasks…"))
        scope.launch {
            while (isActive) {
                checkPendingTasks()
                delay(15 * 60 * 1000L) // check every 15 min
            }
        }
    }

    private suspend fun checkPendingTasks() {
        val tasks = PlanStore.getTodayTasksSnapshot()
        val pending = tasks.filter { it.state == TaskState.PENDING }
        if (pending.isNotEmpty()) {
            val next = pending.first()
            CheckmateTTS.speak(this, "Reminder: ${next.subject} — ${next.topic} is pending.")
        }
    }

    private fun buildNotification(text: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("Checkmate")
        .setContentText(text)
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setOngoing(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .build()

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "Reminders", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
}
