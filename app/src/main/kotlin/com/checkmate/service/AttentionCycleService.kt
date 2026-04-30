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
        private const val CHANNEL_ID = "attention_cycle_channel"
        private const val NOTIF_ID   = 42
        const val EXTRA_TASK_ID      = "task_id"
        const val EXTRA_TASK_NAME    = "task_name"
        const val EXTRA_DURATION_MIN = "duration_min"
        const val ACTION_PAUSE       = "com.checkmate.ATTENTION_PAUSE"
        const val ACTION_RESUME      = "com.checkmate.ATTENTION_RESUME"

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

        fun stop(context: Context) =
            context.stopService(Intent(context, AttentionCycleService::class.java))

        fun sendPause(context: Context) =
            context.sendBroadcast(Intent(ACTION_PAUSE).setPackage(context.packageName))

        fun sendResume(context: Context) =
            context.sendBroadcast(Intent(ACTION_RESUME).setPackage(context.packageName))
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var cycleJob: Job? = null
    private var isPaused = false

    private val pauseReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_PAUSE  -> { isPaused = true;  AttentionCycleManager.pause() }
                ACTION_RESUME -> { isPaused = false; AttentionCycleManager.resume() }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val filter = android.content.IntentFilter().apply {
            addAction(ACTION_PAUSE)
            addAction(ACTION_RESUME)
        }
        // FIX: Android 14 (API 34) requires RECEIVER_NOT_EXPORTED for dynamic receivers
        // on non-system broadcasts. Without this -> SecurityException -> FATAL at onCreate:69.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(pauseReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(pauseReceiver, filter)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val taskId      = intent?.getStringExtra(EXTRA_TASK_ID)         ?: "task"
        val taskName    = intent?.getStringExtra(EXTRA_TASK_NAME)       ?: "Task"
        val durationMin = intent?.getLongExtra(EXTRA_DURATION_MIN, 60L) ?: 60L
        startForegroundCompat(buildNotification("FOCUS — 30:00", taskName))
        AttentionCycleManager.start(taskId, taskName, durationMin)
        startCycleLoop(taskName)
        return START_NOT_STICKY
    }

    private fun startCycleLoop(taskName: String) {
        cycleJob?.cancel()
        cycleJob = scope.launch {
            while (isActive) {
                if (isPaused) {
                    val cs = AttentionCycleManager.currentState()
                    (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                        .notify(NOTIF_ID, buildNotification("PAUSED", taskName, cs.totalSessionSeconds, cs.cycleIndex))
                    delay(1_000)
                    continue
                }
                val cs = AttentionCycleManager.tick()
                (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                    .notify(NOTIF_ID, buildNotification(
                        phaseLabel(cs.phase, cs.phaseSecondsLeft),
                        taskName, cs.totalSessionSeconds, cs.cycleIndex, cs.needsAttentionCheck))

                if (cs.phaseJustChanged) {
                    when (cs.phase) {
                        AttentionPhase.SHORT_BREAK -> CheckmateTTS.speak(this@AttentionCycleService, "Take a 5 minute break.")
                        AttentionPhase.LONG_BREAK  -> CheckmateTTS.speak(this@AttentionCycleService, "Good work. 10 minute break.")
                        AttentionPhase.FOCUS       -> CheckmateTTS.speak(this@AttentionCycleService, "Break over. Back to focus.")
                        AttentionPhase.PAUSED      -> {}
                        AttentionPhase.DONE        -> {
                            CheckmateTTS.speak(this@AttentionCycleService, "Session complete.")
                            stopSelf(); return@launch
                        }
                    }
                }
                if (cs.needsAttentionCheck && cs.phaseSecondsLeft % 30L == 0L)
                    CheckmateTTS.speak(this@AttentionCycleService, "Still focused? Tap the check.")
                delay(1_000)
            }
        }
    }

    private fun phaseLabel(phase: AttentionPhase, secondsLeft: Long): String {
        val m = secondsLeft / 60; val s = secondsLeft % 60
        val t = String.format("%d:%02d", m, s)
        return when (phase) {
            AttentionPhase.FOCUS       -> "FOCUS — $t"
            AttentionPhase.SHORT_BREAK -> "SHORT BREAK — $t"
            AttentionPhase.LONG_BREAK  -> "LONG BREAK — $t"
            AttentionPhase.PAUSED      -> "PAUSED — $t"
            AttentionPhase.DONE        -> "SESSION COMPLETE"
        }
    }

    private fun buildNotification(
        phaseText: String, taskName: String,
        totalSeconds: Long = 0L, cycleIndex: Int = 0, needsCheck: Boolean = false
    ): Notification {
        val tm = totalSeconds / 60; val ts = totalSeconds % 60
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(if (needsCheck) "ATTN: $phaseText" else phaseText)
            .setContentText("$taskName  |  ${tm}m ${ts}s  |  Cycle $cycleIndex")
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setOngoing(true).setOnlyAlertOnce(!needsCheck)
            .setPriority(NotificationCompat.PRIORITY_HIGH).build()
    }

    private fun startForegroundCompat(n: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(NOTIF_ID, n)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "Attention Cycle", NotificationManager.IMPORTANCE_HIGH)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
    }

    override fun onDestroy() {
        cycleJob?.cancel(); scope.cancel()
        try { unregisterReceiver(pauseReceiver) } catch (_: Exception) {}
        AttentionCycleManager.reset()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
