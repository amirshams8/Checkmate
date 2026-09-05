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
import com.checkmate.workmode.UninstallGuard
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class ReminderService : Service() {
    companion object {
        private const val CHANNEL_ID = "reminder_channel"
        private const val NOTIF_ID   = 55
        // Mentor v2 (spec 3.5): same Cloudflare Worker StatusReporter already pushes to.
        // NOTE: the worker-side route that lets a guardian issue an "unlock"/"override"
        // command via Telegram and have it show up here isn't part of this Android repo —
        // this only polls a /override-status endpoint and expects {"override": true|false}
        // back. Add the matching route to worker.js to make this live end-to-end; until then
        // this poll just always sees false and grantRemoteOverride() is never called, so it's
        // a safe no-op rather than a broken dependency.
        private const val OVERRIDE_URL = "https://steep-band-1bd0.amirshamse8.workers.dev/override-status"

        fun start(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                context.startForegroundService(Intent(context, ReminderService::class.java))
            else
                context.startService(Intent(context, ReminderService::class.java))
        }
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIF_ID, buildNotification("Monitoring tasks…"))
        scope.launch {
            while (isActive) {
                // Bugfix ("old tasks not clearing at end of day"): PlanStore.todayTasks is
                // only ever populated at process start or by a local write, so a DONE/SKIPPED
                // task just sat there until the app restarted. Runs first each cycle so every
                // check below it (checkPendingTasks, ProactiveMentor, GapTaskManager) sees the
                // cleaned-up list. See PlanStore.cleanupCompletedIfDue's own doc for the 9 PM
                // guard and the tomorrow-carry-forward for still-unresolved tasks. When it
                // returns non-null, tasks were carried onto tomorrow's plan — push that day's
                // list to TaskSyncManager right away instead of leaving it stranded locally
                // until some unrelated future edit happens to touch tomorrow's plan and
                // trigger a push (PlanStore itself stays sync-unaware; this is the one place
                // that bridges the two, same as HomeViewModel already does for today's list).
                try {
                    val carried = PlanStore.cleanupCompletedIfDue()
                    if (carried != null) {
                        withContext(Dispatchers.IO) {
                            TaskSyncManager.pushTasks(carried.tasks, carried.dayKey, carried.updatedAt)
                        }
                    }
                } catch (_: Exception) {}
                checkPendingTasks()
                // Mentor v2 (spec 3.2): idle check-in — appends to Mentor chat + notifies if
                // nothing's been started by the configured hour. No-ops after the first fire
                // each day (see ProactiveMentor.idleCheckIfNeeded's day-key guard).
                try { ProactiveMentor.idleCheckIfNeeded(applicationContext) } catch (_: Exception) {}
                // "No tasks in plan == no study": once-daily check for a multi-day gap with
                // no plan/completion, nudges the student and alerts the guardian. Same
                // no-op-after-first-fire-per-day guard as idleCheckIfNeeded above.
                try { ProactiveMentor.consistencyCheckIfNeeded(applicationContext) } catch (_: Exception) {}
                // Weekly (Wednesdays only) reminder to review/mark upcoming holidays — see
                // ProactiveMentor.holidayPromptIfNeeded's week-key guard.
                try { ProactiveMentor.holidayPromptIfNeeded(applicationContext) } catch (_: Exception) {}
                // Gap-task daily cadence: the ongoing trigger LearningInterventionOrchestrator's
                // own class doc always said was still missing — runs the analysis pipeline once
                // a day and lets the orchestrator pick up wherever GapTaskLedger left off. Also
                // requests the P0b Testmate targeted test for whatever concept ends up active.
                // See GapTaskManager.generateIfNeeded's own doc for the once-per-day guard.
                try { GapTaskManager.generateIfNeeded(applicationContext) } catch (_: Exception) {}
                // Gap-task escalation: warns the student, with escalating persuasion, when the
                // currently-active gap concept has gone unaddressed for more than a day — see
                // GapTaskManager.escalationCheckIfNeeded's own doc for the depth tiers.
                try { GapTaskManager.escalationCheckIfNeeded(applicationContext) } catch (_: Exception) {}
                // P0b: the actual evidence-loop return arrow — polls the active concept's
                // Testmate targeted-test session and, once it's submitted, imports real
                // QuestionAttempt/LearningEvent evidence instead of leaving mastery to move
                // only off the task's DONE flag. Deliberately every cycle, not once/day — see
                // GapTaskManager.evidencePollIfNeeded's own doc.
                try { GapTaskManager.evidencePollIfNeeded(applicationContext) } catch (_: Exception) {}
                // Phase 3 (adaptive tutor state machine) execution bridge: drives whatever
                // DIAGNOSE/EXPLAIN/PRACTICE/VERIFY session is currently active — DIAGNOSE and
                // EXPLAIN auto-advance immediately (no external evidence needed), PRACTICE and
                // VERIFY consume GapTaskLedger's own already-imported P0b evidence (just
                // polled/imported by the call directly above, same tick) rather than firing a
                // second competing Testmate request. See TutorCycleManager's own class doc.
                try { TutorCycleManager.driveActiveSession(applicationContext) } catch (_: Exception) {}
                // Retention-check evidence loop (next-session-retention-loop.txt): requests a
                // Testmate session for any RETENTION CHECK task that doesn't have one yet —
                // see RetentionCheckManager's own doc for why this is a separate ledger/
                // manager from the gap-repair pair above rather than folded into it.
                try { RetentionCheckManager.createRetentionTestsIfNeeded() } catch (_: Exception) {}
                // Retention-check evidence loop, return arrow: polls any outstanding retention
                // session and, once submitted, imports real QuestionAttempt/LearningEvent
                // evidence — same every-cycle (not once/day) cadence as the gap-repair poll
                // above, for the same reason (the student can submit at any time).
                try { RetentionCheckManager.evidencePollIfNeeded(applicationContext) } catch (_: Exception) {}
                // Mentor v2 (spec 3.5): best-effort remote-override poll — see OVERRIDE_URL note.
                try { pollRemoteOverride() } catch (_: Exception) {}
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

    /** See OVERRIDE_URL note above — safe no-op until the worker-side route exists. */
    private suspend fun pollRemoteOverride() = withContext(Dispatchers.IO) {
        val chatId = TelegramAlertBot.getChatId() ?: return@withContext
        val request = Request.Builder()
            .url("$OVERRIDE_URL?chatId=$chatId")
            .get()
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext
            val body = response.body?.string() ?: return@withContext
            val overridden = JSONObject(body).optBoolean("override", false)
            if (overridden) UninstallGuard.grantRemoteOverride()
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
