package com.checkmate.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.*
import androidx.core.app.NotificationCompat
import com.checkmate.core.AttentionCycleManager
import com.checkmate.core.AttentionPhase
import com.checkmate.core.tts.CheckmateTTS
import kotlinx.coroutines.*

class AttentionCycleService : Service() {

    companion object {
        private const val CHANNEL_ID   = "attention_cycle_channel"
        private const val NOTIF_ID     = 42
        const val EXTRA_TASK_ID        = "task_id"
        const val EXTRA_TASK_NAME      = "task_name"
        const val EXTRA_DURATION_MIN   = "duration_min"

        fun start(context: Context, taskId: String, taskName: String, durationMinutes: Long) {
            val intent = Intent(context, AttentionCycleService::class.java).apply {
                putExtra(EXTRA_TASK_ID,      taskId)
                putExtra(EXTRA_TASK_NAME,    taskName)
                putExtra(EXTRA_DURATION_MIN, durationMinutes)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                context.startForegroundService(intent)
            else
                context.startService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AttentionCycleService::class.java))
        }
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var cycleJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val taskId       = intent?.getStringExtra(EXTRA_TASK_ID)      ?: "task"
        val taskName     = intent?.getStringExtra(EXTRA_TASK_NAME)    ?: "Task"
        val durationMin  = intent?.getLongExtra(EXTRA_DURATION_MIN, 60) ?: 60

        startForegroundCompat(buildNotification("FOCUS — 30:00", taskName))
        AttentionCycleManager.start(taskId, taskName, durationMin)
        startCycleLoop(taskName)
        return START_NOT_STICKY
    }

    private fun startCycleLoop(taskName: String) {
        cycleJob?.cancel()
        cycleJob = scope.launch {
            while (isActive) {
                val cycleState = AttentionCycleManager.tick()

                // Update notification
                val notif = buildNotification(
                    phaseLabel(cycleState.phase, cycleState.phaseSecondsLeft),
                    taskName,
                    cycleState.totalSessionSeconds,
                    cycleState.cycleIndex,
                    cycleState.needsAttentionCheck
                )
                (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, notif)

                // Phase change announcements
                if (cycleState.phaseJustChanged) {
                    when (cycleState.phase) {
                        AttentionPhase.SHORT_BREAK -> CheckmateTTS.speak(this@AttentionCycleService, "Take a 5 minute break.")
                        AttentionPhase.LONG_BREAK  -> CheckmateTTS.speak(this@AttentionCycleService, "Good work. 10 minute break.")
                        AttentionPhase.FOCUS       -> CheckmateTTS.speak(this@AttentionCycleService, "Break over. Back to focus.")
                        AttentionPhase.DONE        -> {
                            CheckmateTTS.speak(this@AttentionCycleService, "Session complete.")
                            stopSelf()
                            return@launch
                        }
                    }
                }

                // Attention check pulse
                if (cycleState.needsAttentionCheck && cycleState.phaseSecondsLeft % 30 == 0) {
                    CheckmateTTS.speak(this@AttentionCycleService, "Still focused? Tap the check.")
                }

                delay(1_000)
            }
        }
    }

    private fun phaseLabel(phase: AttentionPhase, secondsLeft: Long): String {
        val m = secondsLeft / 60
        val s = secondsLeft % 60
        val time = String.format("%d:%02d", m, s)
        return when (phase) {
            AttentionPhase.FOCUS       -> "FOCUS — $time"
            AttentionPhase.SHORT_BREAK -> "SHORT BREAK — $time"
            AttentionPhase.LONG_BREAK  -> "LONG BREAK — $time"
            AttentionPhase.DONE        -> "SESSION COMPLETE"
        }
    }

    private fun buildNotification(
        phaseText:    String,
        taskName:     String,
        totalSeconds: Long   = 0,
        cycleIndex:   Int    = 0,
        needsCheck:   Boolean = false
    ): Notification {
        val totalMin = totalSeconds / 60
        val totalSec = totalSeconds % 60
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(if (needsCheck) "⚡ $phaseText — TAP ✅ TO CONFIRM" else phaseText)
            .setContentText("$taskName  •  Session: ${totalMin}m ${totalSec}s  •  Cycle $cycleIndex")
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setOngoing(true)
            .setOnlyAlertOnce(!needsCheck)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else
            startForeground(NOTIF_ID, notification)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "Attention Cycle", NotificationManager.IMPORTANCE_HIGH)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
    }

    override fun onDestroy() {
        cycleJob?.cancel()
        scope.cancel()
        AttentionCycleManager.reset()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
