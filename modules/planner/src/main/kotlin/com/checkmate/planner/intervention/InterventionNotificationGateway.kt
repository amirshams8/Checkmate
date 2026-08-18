package com.checkmate.planner.intervention

import android.content.Context
import com.checkmate.planner.model.StudyTask

/**
 * Proactive Execution Engine — Step 9 (Blueprint Part One, §16).
 *
 * "Proactive does not mean Checkmate can freely pop an Activity over whatever the user is
 * doing... Default interaction should be: Trigger -> High-quality notification / heads-up
 * -> User interaction -> Voice session." This is the seam [InterventionTriggerWorker] calls
 * instead of resolving a NEGOTIATING transaction immediately through the deterministic
 * fallback. Same shape as this codebase's existing DistractionGuard.listener /
 * UninstallGuard.listener pattern (see CheckmateApp.onCreate): an interface owned by the
 * module that needs to call out (planner), implemented and wired by the module that can
 * actually build the notification — which has to be `app`, since a heads-up notification
 * with tap actions needs NotificationManager/PendingIntent/BroadcastReceiver, AND because
 * building the richest possible notification text means pulling in psyche's ContextBuilder
 * (Step 8) — planner cannot see modules/psyche (psyche depends on planner, not the reverse;
 * see Step 8's own note on this), so only `app` sees both sides at once.
 *
 * [InterventionNotificationBridge] is the settable holder — set once from
 * CheckmateApp.onCreate(), read by [InterventionTriggerWorker] on every run. If nothing has
 * set [InterventionNotificationBridge.gateway] (e.g. a plain JVM unit test, or any future
 * build variant that doesn't want the notification path), the worker falls back to exactly
 * the deterministic-fallback-only behavior it had before this step — offline-first (§14)
 * extends to "gateway-absent-first" too: the trigger engine must keep working even if the
 * one thing that can show a notification was never wired.
 */
interface InterventionNotificationGateway {
    /**
     * Called once per fired trigger, right after escrow is acquired. Implementations are
     * expected to show a user-facing prompt (heads-up notification, per §16) and return
     * immediately — resolution of the transaction happens later, out of band, when the
     * student taps a notification action (or the TTL lapses and
     * [TaskEscrow.expireIfPastTtl]/reconciliation cleans it up).
     *
     * Returning [PromptDispatchResult.UNAVAILABLE] (e.g. POST_NOTIFICATIONS not granted)
     * tells the worker to fall back to the deterministic path immediately instead of leaving
     * the transaction to expire silently with nothing ever shown to the student.
     */
    suspend fun promptStudent(
        context: Context,
        transactionId: String,
        task: StudyTask,
        lateMinutes: Int,
        now: Long
    ): PromptDispatchResult
}

enum class PromptDispatchResult { SHOWN, UNAVAILABLE }

object InterventionNotificationBridge {
    /** Blueprint §16's notification is a new "waiting for a human to glance at their phone"
     *  step — materially longer than [TaskEscrow.DEFAULT_TTL_MILLIS] (60s, sized for an
     *  active voice negotiation per §6's own example, not for a notification that might sit
     *  unread for a few minutes). 5 minutes matches the notifier's own snooze duration so
     *  "Snooze 5 min" always has a live, non-expired transaction to extend against. */
    const val PROMPT_TTL_MILLIS = 5 * 60_000L

    @Volatile
    var gateway: InterventionNotificationGateway? = null
}
