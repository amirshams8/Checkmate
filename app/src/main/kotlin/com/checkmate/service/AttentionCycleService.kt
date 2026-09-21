package com.checkmate.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.*
import androidx.core.app.NotificationCompat
import com.checkmate.core.AttentionCycleManager
import com.checkmate.core.AttentionPhase
import com.checkmate.core.CheckmatePrefs
import com.checkmate.core.tts.CheckmateTTS
import kotlinx.coroutines.*

class AttentionCycleService : Service() {

    companion object {
        private const val CHANNEL_ID        = "attention_cycle_channel"
        private const val NOTIF_ID          = 42
        const val EXTRA_TASK_ID             = "task_id"
        const val EXTRA_TASK_NAME           = "task_name"
        const val EXTRA_DURATION_MIN        = "duration_min"
        // Projection token extras — passed so getMediaProjection() runs AFTER startForeground()
        const val EXTRA_PROJECTION_CODE     = "projection_result_code"
        const val EXTRA_PROJECTION_DATA     = "projection_data"
        const val ACTION_PAUSE              = "com.checkmate.ATTENTION_PAUSE"
        const val ACTION_RESUME             = "com.checkmate.ATTENTION_RESUME"
        // Notification action buttons — these mirror what the floating attention
        // bar offers, so a student who turns the bar off in Settings can still
        // confirm attention / mark done / take a break from the notification.
        const val ACTION_MARK_DONE          = "com.checkmate.ATTENTION_MARK_DONE"
        const val ACTION_TAKE_BREAK         = "com.checkmate.ATTENTION_TAKE_BREAK"
        const val ACTION_CONFIRM_ATTENTION  = "com.checkmate.ATTENTION_CONFIRM"

        fun start(
            context: Context,
            taskId: String,
            taskName: String,
            durationMinutes: Long,
            projectionResultCode: Int = Activity.RESULT_CANCELED,
            projectionData: Intent?   = null
        ) {
            val intent = Intent(context, AttentionCycleService::class.java).apply {
                putExtra(EXTRA_TASK_ID,          taskId)
                putExtra(EXTRA_TASK_NAME,         taskName)
                putExtra(EXTRA_DURATION_MIN,      durationMinutes)
                putExtra(EXTRA_PROJECTION_CODE,   projectionResultCode)
                putExtra(EXTRA_PROJECTION_DATA,   projectionData)
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

        // ── Reboot survival ──────────────────────────────────────────────────
        // CycleState (AttentionCycleManager) lives only in memory, so a reboot
        // loses exact elapsed time/phase along with everything else in the
        // process. Rather than try to reconstruct that (and the media
        // projection token, which can't survive a process death anyway —
        // storeProjectionToken() would need a fresh user grant regardless),
        // a killed mid-session restart just re-launches the same task from a
        // full fresh duration. No screenshot capture on that resumed session
        // since there's no projection token to pass — StatusReporter's other
        // pushes (phase/timer) still work fine without it.
        private const val KEY_ACTIVE_TASK_ID   = "attention_active_task_id"
        private const val KEY_ACTIVE_TASK_NAME = "attention_active_task_name"
        private const val KEY_ACTIVE_DURATION  = "attention_active_duration_min"

        /** Called by BootReceiver. No-op if no session was running when the device went down. */
        fun resumeAfterRebootIfNeeded(context: Context) {
            val taskId = CheckmatePrefs.getString(KEY_ACTIVE_TASK_ID, null) ?: return
            val taskName = CheckmatePrefs.getString(KEY_ACTIVE_TASK_NAME, "Task") ?: "Task"
            val durationMin = CheckmatePrefs.getLong(KEY_ACTIVE_DURATION, 60L)
            start(context, taskId, taskName, durationMin)
        }
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var cycleJob: Job? = null
    private var isPaused = false
    private var lastTaskName = "Task"

    // Tracks the last elapsed-second mark at which we pushed a status+screenshot update,
    // so we fire roughly every StatusReporter.STATUS_INTERVAL_SECONDS regardless of pauses.
    private var lastStatusPushAt = 0L

    private val controlReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_PAUSE             -> { isPaused = true;  AttentionCycleManager.pause() }
                ACTION_RESUME            -> { isPaused = false; AttentionCycleManager.resume() }
                ACTION_MARK_DONE         -> AttentionCycleManager.confirmDone()
                ACTION_TAKE_BREAK        -> AttentionCycleManager.dismissPromptAndBreak()
                ACTION_CONFIRM_ATTENTION -> AttentionCycleManager.confirmAttention()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val filter = android.content.IntentFilter().apply {
            addAction(ACTION_PAUSE)
            addAction(ACTION_RESUME)
            addAction(ACTION_MARK_DONE)
            addAction(ACTION_TAKE_BREAK)
            addAction(ACTION_CONFIRM_ATTENTION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(controlReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(controlReceiver, filter)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val taskId      = intent?.getStringExtra(EXTRA_TASK_ID)         ?: "task"
        val taskName    = intent?.getStringExtra(EXTRA_TASK_NAME)       ?: "Task"
        val durationMin = intent?.getLongExtra(EXTRA_DURATION_MIN, 60L) ?: 60L
        lastTaskName = taskName

        // Persist just enough to relaunch this task from scratch if the process
        // dies from under it (reboot, OOM kill) — see resumeAfterRebootIfNeeded().
        // Cleared in onDestroy() on every normal end (DONE, explicit stop()),
        // so it's only ever still set here if something killed the process
        // without giving the service a chance to shut down cleanly.
        CheckmatePrefs.putString(KEY_ACTIVE_TASK_ID, taskId)
        CheckmatePrefs.putString(KEY_ACTIVE_TASK_NAME, taskName)
        CheckmatePrefs.putLong(KEY_ACTIVE_DURATION, durationMin)

        // startForeground() MUST be called before getMediaProjection() on Android 14+.
        // We call it first here, then immediately store the projection token below.
        startForegroundCompat(buildNotification(initialPhaseLabel(durationMin), taskName))

        // Now it is safe to call getMediaProjection() — the mediaProjection FGS type is active.
        val projCode = intent?.getIntExtra(EXTRA_PROJECTION_CODE, Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        val projData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            intent?.getParcelableExtra(EXTRA_PROJECTION_DATA, Intent::class.java)
        else
            @Suppress("DEPRECATION") intent?.getParcelableExtra(EXTRA_PROJECTION_DATA)

        if (projCode == Activity.RESULT_OK && projData != null) {
            ScreenCaptureManager.storeProjectionToken(applicationContext, projCode, projData)
        }

        AttentionCycleManager.start(taskId, taskName, durationMin)
        lastStatusPushAt = 0L
        startCycleLoop(taskName)
        return START_NOT_STICKY
    }

    private fun initialPhaseLabel(durationMin: Long): String {
        val pomodoro = AttentionCycleManager.currentState().pomodoroEnabled
        return if (pomodoro) "FOCUS — 30:00" else "FOCUS — ${durationMin}:00"
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
                        taskName, cs.totalSessionSeconds, cs.cycleIndex, cs.needsAttentionCheck, cs.phase))

                if (cs.phaseJustChanged) {
                    when (cs.phase) {
                        AttentionPhase.PROMPT_DONE -> CheckmateTTS.speak(
                            this@AttentionCycleService,
                            "30 minutes done. Mark task complete or take a break?"
                        )
                        AttentionPhase.SHORT_BREAK -> CheckmateTTS.speak(
                            this@AttentionCycleService, "Take a 5 minute break."
                        )
                        AttentionPhase.LONG_BREAK  -> CheckmateTTS.speak(
                            this@AttentionCycleService, "Good work. 10 minute break."
                        )
                        AttentionPhase.FOCUS       -> CheckmateTTS.speak(
                            this@AttentionCycleService, "Break over. Back to focus."
                        )
                        AttentionPhase.PAUSED      -> {}
                        AttentionPhase.DONE        -> {
                            CheckmateTTS.speak(this@AttentionCycleService, "Session complete.")
                            stopSelf(); return@launch
                        }
                    }
                }
                if (cs.needsAttentionCheck && cs.phaseSecondsLeft % 30L == 0L)
                    CheckmateTTS.speak(this@AttentionCycleService, "Still focused? Tap the check.")

                // ── Periodic status + screenshot push to guardian bot ──────────────
                if (cs.totalSessionSeconds - lastStatusPushAt >= StatusReporter.STATUS_INTERVAL_SECONDS) {
                    lastStatusPushAt = cs.totalSessionSeconds
                    val snapshot = cs
                    launch(Dispatchers.IO) {
                        StatusReporter.pushStatus(this@AttentionCycleService, snapshot)
                    }
                }

                delay(1_000)
            }
        }
    }

    private fun phaseLabel(phase: AttentionPhase, secondsLeft: Long): String {
        val m = secondsLeft / 60; val s = secondsLeft % 60
        val t = String.format("%d:%02d", m, s)
        return when (phase) {
            AttentionPhase.FOCUS        -> "FOCUS — $t"
            AttentionPhase.SHORT_BREAK  -> "SHORT BREAK — $t"
            AttentionPhase.LONG_BREAK   -> "LONG BREAK — $t"
            AttentionPhase.PAUSED       -> "PAUSED — $t"
            AttentionPhase.PROMPT_DONE  -> "MARK DONE?"
            AttentionPhase.DONE         -> "SESSION COMPLETE"
        }
    }

    private fun buildNotification(
        phaseText: String, taskName: String,
        totalSeconds: Long = 0L, cycleIndex: Int = 0, needsCheck: Boolean = false,
        phase: AttentionPhase = AttentionPhase.FOCUS
    ): Notification {
        val tm = totalSeconds / 60; val ts = totalSeconds % 60
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(if (needsCheck) "ATTN: $phaseText" else phaseText)
            .setContentText("$taskName  |  ${tm}m ${ts}s  |  Cycle $cycleIndex")
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setOngoing(true).setOnlyAlertOnce(!needsCheck)
            .setPriority(NotificationCompat.PRIORITY_HIGH)

        // Action buttons mirror the floating attention bar so the same controls
        // are available from the notification when the overlay bar is off.
        if (phase == AttentionPhase.PROMPT_DONE) {
            builder.addAction(0, "✅ Mark Done", actionPendingIntent(ACTION_MARK_DONE, 1))
            builder.addAction(0, "➡ Break",      actionPendingIntent(ACTION_TAKE_BREAK, 2))
        } else if (needsCheck) {
            builder.addAction(0, "✅ I'm here", actionPendingIntent(ACTION_CONFIRM_ATTENTION, 3))
        }
        if (phase != AttentionPhase.DONE && phase != AttentionPhase.PROMPT_DONE) {
            val pauseLabel = if (isPaused) "▶ Resume" else "⏸ Pause"
            val pauseAction = if (isPaused) ACTION_RESUME else ACTION_PAUSE
            builder.addAction(0, pauseLabel, actionPendingIntent(pauseAction, 4))
        }

        return builder.build()
    }

    private fun actionPendingIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(action).setPackage(packageName)
        return PendingIntent.getBroadcast(
            this, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun startForegroundCompat(n: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            startForeground(NOTIF_ID, n,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
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
        try { unregisterReceiver(controlReceiver) } catch (_: Exception) {}
        AttentionCycleManager.reset()
        // Normal end of this session (DONE or explicit stop()) — clear the
        // reboot-resume markers so a later reboot doesn't relaunch a task
        // that's already finished.
        CheckmatePrefs.remove(KEY_ACTIVE_TASK_ID)
        CheckmatePrefs.remove(KEY_ACTIVE_TASK_NAME)
        CheckmatePrefs.remove(KEY_ACTIVE_DURATION)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
