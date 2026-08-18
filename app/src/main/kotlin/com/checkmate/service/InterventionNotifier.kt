package com.checkmate.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.checkmate.MainActivity
import com.checkmate.core.stopwatch.NotificationPermissionHelper
import com.checkmate.planner.intervention.InterventionNotificationGateway
import com.checkmate.planner.intervention.PromptDispatchResult
import com.checkmate.planner.model.StudyTask
import com.checkmate.psyche.intervention.ContextBuilder

/**
 * Proactive Execution Engine — Step 9 (Blueprint Part One, §16).
 *
 * The production [InterventionNotificationGateway] — the piece [com.checkmate.planner.intervention.InterventionTriggerWorker]
 * (modules/planner) cannot itself be, since it needs NotificationManager/PendingIntent
 * (Android framework, fine for planner) AND psyche's ContextBuilder (modules/psyche, which
 * planner cannot depend on — see the gateway interface's own doc for why). Only `app` sees
 * both sides, so this lives here and is wired from CheckmateApp.onCreate() via
 * InterventionNotificationBridge.gateway, the same settable-listener pattern this codebase
 * already uses for DistractionGuard.listener / UninstallGuard.listener.
 *
 * "Checkmate should earn the user's attention, not hijack the screen constantly" (§16) —
 * this is a plain heads-up NotificationCompat notification, never a full-screen intent.
 * Three actions, exactly as worked-example'd in §16: Start / Talk to Checkmate / Snooze.
 * Kept in its own channel (`intervention_channel`), separate from MentorNotifier's
 * `mentor_channel` (one-shot Mentor commentary, IMPORTANCE_DEFAULT) and ReminderService's
 * always-on `reminder_channel` (deliberately IMPORTANCE_LOW/quiet, "Monitoring tasks…") —
 * this one needs IMPORTANCE_HIGH to actually heads-up, and mixing that into either existing
 * channel would either mute this or make the other noisy.
 *
 * Context Builder (Step 8) was fully built and tested but genuinely unreachable from
 * production code until this step gave it a caller — this is that caller. [ContextBuilder.build]
 * runs even though nothing downstream parses its output into an LLM prompt yet (Structured
 * LLM intents are Step 11) — its immediate, non-speculative use today is enriching the
 * notification body itself (recent skip rate, streak), which needed exactly the "structured
 * reality snapshot" Step 8 already produces. This reads only fields Step 8 actually
 * populated — nothing here fabricates the three fields that step's own doc left out
 * (upcoming test / available time / recent distraction).
 */
object InterventionNotifier : InterventionNotificationGateway {

    private const val TAG = "InterventionNotifier"
    private const val CHANNEL_ID = "intervention_channel"
    private const val CHANNEL_NAME = "Study Prompts"

    const val ACTION_START = "com.checkmate.intervention.START"
    const val ACTION_SNOOZE = "com.checkmate.intervention.SNOOZE"

    const val EXTRA_TRANSACTION_ID = "transaction_id"
    const val EXTRA_TASK_ID = "task_id"
    const val EXTRA_LATE_MINUTES = "late_minutes"

    /** Blueprint §16: "Snooze 5 min." Also the amount [InterventionActionReceiver] extends
     *  the escrow TTL by, and the delay before [InterventionSnoozeAlarmReceiver] re-fires
     *  this same notification — all three have to agree, or a snoozed prompt could
     *  reappear after its own (extended) escrow had already expired, or vice versa. */
    const val SNOOZE_MILLIS = 5 * 60_000L

    override suspend fun promptStudent(
        context: Context,
        transactionId: String,
        task: StudyTask,
        lateMinutes: Int,
        now: Long
    ): PromptDispatchResult = show(context, transactionId, task, lateMinutes, now)

    /**
     * Split out from [promptStudent] (the [InterventionNotificationGateway] entry point) so
     * [InterventionSnoozeAlarmReceiver] can re-show the exact same notification after a
     * snooze without going through the gateway seam again — the transaction this re-prompt
     * belongs to already exists (extended, not re-created, by
     * [InterventionActionReceiver.handleSnooze]), so re-entering via the trigger worker's
     * path would be the wrong call site entirely.
     */
    fun show(
        context: Context,
        transactionId: String,
        task: StudyTask,
        lateMinutes: Int,
        now: Long
    ): PromptDispatchResult {
        if (!NotificationPermissionHelper.isPermissionGranted(context)) {
            Log.w(TAG, "POST_NOTIFICATIONS not granted — cannot prompt for $transactionId")
            return PromptDispatchResult.UNAVAILABLE
        }
        createChannel(context)

        // Best-effort: BehaviorLedger/TodayContext are Android-Context-free singletons
        // (initialized once via CheckmatePrefs.init, same as every other object here), so
        // this can't fail on missing Context — but wrapped defensively anyway, matching
        // this codebase's existing runCatching-around-behavioral-reads style (see
        // MainActivity's overlay/mic permission calls), so a notification still gets shown
        // even if the behavioral summary genuinely can't be computed right now.
        val context7d = runCatching { ContextBuilder.build(task, lateMinutes, now) }.getOrNull()

        val title = if (lateMinutes > 0) {
            "${task.subject} — $lateMinutes min late"
        } else {
            "${task.subject} is scheduled now"
        }
        val bodyLines = mutableListOf("${task.topic} was due to start.")
        if (context7d != null) {
            if (context7d.recentSkipRatePercent > 0) {
                bodyLines += "Recent skip rate: ${context7d.recentSkipRatePercent}%."
            }
            if (context7d.streakDays > 0) {
                bodyLines += "Streak: ${context7d.streakDays}d — don't break it."
            }
        }
        val body = bodyLines.joinToString(" ")

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(talkPendingIntent(context, transactionId, task.id, lateMinutes))
            .addAction(
                0, "Start",
                actionPendingIntent(context, ACTION_START, transactionId, task.id, lateMinutes, requestCode(transactionId, 1))
            )
            .addAction(0, "Talk to Checkmate", talkPendingIntent(context, transactionId, task.id, lateMinutes))
            .addAction(
                0, "Snooze 5 min",
                actionPendingIntent(context, ACTION_SNOOZE, transactionId, task.id, lateMinutes, requestCode(transactionId, 3))
            )
            .build()

        NotificationManagerCompat.from(context).notify(notificationId(transactionId), notification)
        Log.d(TAG, "Prompted for $transactionId (${task.subject} — ${task.topic}, ${lateMinutes}min late)")
        return PromptDispatchResult.SHOWN
    }

    fun cancel(context: Context, transactionId: String) {
        NotificationManagerCompat.from(context).cancel(notificationId(transactionId))
    }

    private fun actionPendingIntent(
        context: Context,
        action: String,
        transactionId: String,
        taskId: String,
        lateMinutes: Int,
        requestCode: Int
    ): PendingIntent {
        val intent = Intent(context, InterventionActionReceiver::class.java).apply {
            this.action = action
            putExtra(EXTRA_TRANSACTION_ID, transactionId)
            putExtra(EXTRA_TASK_ID, taskId)
            putExtra(EXTRA_LATE_MINUTES, lateMinutes)
        }
        return PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * "Talk to Checkmate" (and tapping the notification body) opens MainActivity with the
     * transaction id attached. STT/TTS conversational layer is Step 10 — not built yet — so
     * today this just brings the app to the foreground; MainActivity does not currently read
     * [EXTRA_TRANSACTION_ID] out of its intent. Passing it through now rather than dropping
     * it means Step 10 doesn't have to touch this notification-building code at all when it
     * lands — same "flagging, not forcing" posture already used for Step 8's Context Builder
     * before this step gave it a caller.
     */
    private fun talkPendingIntent(
        context: Context,
        transactionId: String,
        taskId: String,
        lateMinutes: Int
    ): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_TRANSACTION_ID, transactionId)
            putExtra(EXTRA_TASK_ID, taskId)
            putExtra(EXTRA_LATE_MINUTES, lateMinutes)
        }
        return PendingIntent.getActivity(
            context, requestCode(transactionId, 2), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** Stable per-transaction notification id — [transactionId] is a UUID string;
     *  hashCode() collisions are astronomically unlikely, and worst case only mean two
     *  unrelated prompts share one notification slot rather than any correctness issue
     *  (the underlying InterventionTransaction rows are never conflated). */
    private fun notificationId(transactionId: String): Int = transactionId.hashCode()

    /** PendingIntent request codes must be distinct per (transaction, action) pair, or
     *  FLAG_UPDATE_CURRENT would silently overwrite one action's intent with another's for
     *  the same transaction. [slot] disambiguates Start(1)/Talk(2)/Snooze(3) for one
     *  transactionId. */
    private fun requestCode(transactionId: String, slot: Int): Int =
        transactionId.hashCode() * 10 + slot

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Checkmate proactive study prompts — late or missed tasks"
            }
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }
}
