package com.checkmate.planner.intervention

import android.content.Context
import com.checkmate.planner.model.StudyTask

/**
 * Fixes a gap flagged in [ActionExecutor]'s own doc comment: `PermittedAction.RequestGuardian`
 * was always recognized and correctly resolved the owning transaction as COMPLETED, but
 * nothing downstream ever actually told a guardian — the student (or, live in an AI Mentor
 * negotiation, the LLM speaking on their behalf) could ask for a real person and the app
 * would just silently agree and move on.
 *
 * Same shape as [InterventionNotificationGateway]/[InterventionNotificationBridge] and this
 * codebase's older DistractionGuard.listener / UninstallGuard.listener pattern: an interface
 * owned by the module that needs to call out (planner), implemented and wired by the module
 * that can actually reach GuardianNotifier (`app`) — planner has no visibility into `app`,
 * so this is the seam. See [InterventionNotificationGateway]'s own doc for the fuller
 * rationale of why this can't just live in planner directly.
 *
 * [InterventionGuardianBridge] is the settable holder — set once from CheckmateApp.onCreate()
 * alongside [InterventionNotificationBridge.gateway]. If nothing has wired
 * [InterventionGuardianBridge.gateway] (a plain JVM unit test, or any future build variant
 * that doesn't want this), every call site treats a null gateway as a safe no-op — the
 * transaction still resolves correctly either way; this is purely the "does someone
 * downstream get told" half, never something the FSM's own correctness depends on.
 */
interface InterventionGuardianGateway {
    /**
     * Called once a `REQUEST_GUARDIAN` intent has actually resolved (i.e. right where
     * [ExecutionOutcome.RequiresGuardianEscalation] is observed) — not from inside
     * [ActionExecutor] itself, since that class has no Context to work with. Implementations
     * are expected to notify the guardian out of band (WhatsApp/Telegram, matching
     * GuardianNotifier's existing dual-channel pattern) and return immediately; there is
     * nothing further for the transaction to wait on.
     */
    fun notifyGuardianRequested(context: Context, task: StudyTask, transactionId: String)
}

object InterventionGuardianBridge {
    @Volatile
    var gateway: InterventionGuardianGateway? = null
}
