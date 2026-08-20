package com.checkmate.service

import android.content.Context
import com.checkmate.planner.intervention.ExecutionOutcome
import com.checkmate.planner.intervention.InterventionGuardianBridge
import com.checkmate.planner.intervention.PermittedAction
import com.checkmate.planner.model.StudyTask

/**
 * Fixes two gaps flagged in [com.checkmate.planner.intervention.ActionExecutor]'s own
 * comments: `RequestGuardian` resolving the transaction without ever notifying anyone, and
 * `ShortBreak`'s negotiated `minutes` being discarded right after validation with nothing
 * ever scheduled to wake the task back up.
 *
 * Both [InterventionActionReceiver] and NegotiationViewModel observe a freshly-resolved
 * [ExecutionOutcome] with a Context on hand — this is the one place that shared handling
 * lives, rather than being duplicated at each call site. [InterventionTriggerWorker] does
 * NOT use this: it lives in `modules:planner`, which cannot reference
 * [InterventionShortBreakAlarmReceiver] (an `app`-layer class) without its own bridge, and
 * — unlike the two `app`-layer call sites — its `decideAndExecute` call never receives an
 * `llmPrompt`, so its deterministic-only fallback can in practice never produce
 * `RequestGuardian` or `ShortBreak` in the first place (see its own doc comment).
 */
object InterventionSideEffects {

    fun handle(
        context: Context,
        task: StudyTask,
        transactionId: String,
        executionOutcome: ExecutionOutcome
    ) {
        if (executionOutcome is ExecutionOutcome.RequiresGuardianEscalation) {
            InterventionGuardianBridge.gateway?.notifyGuardianRequested(context, task, transactionId)
        }

        val action = when (executionOutcome) {
            is ExecutionOutcome.Applied -> executionOutcome.action
            is ExecutionOutcome.NoOpAlreadyApplied -> executionOutcome.action
            else -> null
        }
        if (action is PermittedAction.ShortBreak) {
            InterventionShortBreakAlarmReceiver.scheduleResume(context, action.taskId, action.minutes)
        }
    }
}
