package com.checkmate.planner.intervention

import com.checkmate.planner.model.TaskState
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeParseException

/**
 * Proactive Execution Engine — Step 1 (Blueprint Part One, §10-11).
 *
 * "AI recommends. Code decides." PolicyValidator is the only thing standing between an
 * LLM-produced [LlmIntent] and an actually-permitted mutation. It receives no Android
 * Context, no network access, no LLM, no UI, no SharedPreferences — everything it needs
 * comes in through [PolicyState]. That's deliberate: it makes this module trivially and
 * aggressively unit-testable (see PolicyValidatorTest's policy test matrix), and it means
 * the boundary that actually protects the app can be reasoned about and tested in
 * isolation from everything else in the pipeline.
 *
 * PolicyValidator never talks the LLM out of anything — it either maps an intent onto a
 * [PermittedAction] the deterministic ActionExecutor knows how to run, or it rejects it
 * with a reason. There is no path from here to a protected action (device unlock,
 * disabling the guardian, removing WorkMode) because no such action is representable in
 * [InterventionIntentType] to begin with.
 */
object PolicyValidator {

    /** Below this, a "reduced session" isn't meaningfully different from abandoning the
     *  task — matches the blueprint's "no negative/degenerate durations" invariant. */
    const val MIN_DURATION_MINUTES = 5

    /** Blueprint §11 example: "15-minute break -> permitted, 4-hour break -> rejected."
     *  Picked to comfortably permit that example while still capping well short of
     *  a session-length break. */
    const val MAX_BREAK_MINUTES = 30

    private val RESCHEDULE_TIME_REGEX = Regex("^([01]\\d|2[0-3]):[0-5]\\d$")

    fun validate(intent: LlmIntent, state: PolicyState): PolicyResult {
        val type = intent.intentType
            ?: return reject(RejectionReason.UNRECOGNIZED_INTENT, "intentType did not match the closed schema")

        return when (type) {
            InterventionIntentType.START_TASK          -> validateStartTask(state)
            InterventionIntentType.REDUCE_DURATION      -> validateReduceDuration(intent, state)
            InterventionIntentType.RESCHEDULE_TASK       -> validateRescheduleTask(intent, state)
            // Amended in step 5: a break has to pause a specific task, so it now goes
            // through the same task-presence/state checks as every other task-scoped
            // intent instead of being validated in isolation.
            InterventionIntentType.TAKE_SHORT_BREAK      -> validateShortBreak(intent, state)
            InterventionIntentType.KEEP_PLAN             -> PolicyResult.Permitted(PermittedAction.KeepPlan)
            InterventionIntentType.REQUEST_CLARIFICATION -> PolicyResult.Permitted(PermittedAction.RequestClarification)
            InterventionIntentType.NO_ACTION             -> PolicyResult.Permitted(PermittedAction.NoAction)
            // Escalation to a human guardian is never blocked by policy — see Blueprint §12:
            // genuine emergencies route through the guardian override protocol, not AI judgment.
            InterventionIntentType.REQUEST_GUARDIAN      -> PolicyResult.Permitted(PermittedAction.RequestGuardian)
        }
    }

    private fun validateStartTask(state: PolicyState): PolicyResult {
        val task = state.task
            ?: return reject(RejectionReason.UNKNOWN_TASK_ID, "no task in PolicyState")
        if (task.state != TaskState.PENDING && task.state != TaskState.PAUSED) {
            return reject(
                RejectionReason.TASK_NOT_ACTIVE_STATE,
                "task ${task.id} is ${task.state}, expected PENDING or PAUSED"
            )
        }
        return PolicyResult.Permitted(PermittedAction.StartTask(task.id))
    }

    private fun validateReduceDuration(intent: LlmIntent, state: PolicyState): PolicyResult {
        val task = state.task
            ?: return reject(RejectionReason.UNKNOWN_TASK_ID, "no task in PolicyState")
        if (task.state != TaskState.PENDING && task.state != TaskState.ACTIVE) {
            return reject(
                RejectionReason.TASK_NOT_ACTIVE_STATE,
                "task ${task.id} is ${task.state}, cannot reduce a task that isn't pending/active"
            )
        }

        val raw = intent.parameters["newDurationMinutes"]
            ?: return reject(RejectionReason.MALFORMED_INTENT, "missing newDurationMinutes")
        val newDuration = raw.toIntOrNull()
            ?: return reject(RejectionReason.MALFORMED_INTENT, "newDurationMinutes '$raw' is not an integer")

        if (newDuration <= 0) {
            return reject(RejectionReason.NEGATIVE_OR_ZERO_DURATION, "newDurationMinutes=$newDuration")
        }
        if (newDuration < MIN_DURATION_MINUTES) {
            return reject(
                RejectionReason.DURATION_TOO_SHORT,
                "newDurationMinutes=$newDuration below minimum $MIN_DURATION_MINUTES"
            )
        }
        if (newDuration >= task.durationMinutes) {
            return reject(
                RejectionReason.DURATION_NOT_A_REDUCTION,
                "newDurationMinutes=$newDuration is not less than current ${task.durationMinutes}"
            )
        }
        return PolicyResult.Permitted(PermittedAction.ReduceDuration(task.id, newDuration))
    }

    private fun validateRescheduleTask(intent: LlmIntent, state: PolicyState): PolicyResult {
        val task = state.task
            ?: return reject(RejectionReason.UNKNOWN_TASK_ID, "no task in PolicyState")

        val raw = intent.parameters["newScheduledStartTime"]
            ?: return reject(RejectionReason.MALFORMED_INTENT, "missing newScheduledStartTime")
        if (!RESCHEDULE_TIME_REGEX.matches(raw)) {
            return reject(RejectionReason.INVALID_RESCHEDULE_TIME, "'$raw' is not a valid HH:mm 24h time")
        }

        val newTime = try {
            LocalTime.parse(raw)
        } catch (e: DateTimeParseException) {
            return reject(RejectionReason.INVALID_RESCHEDULE_TIME, "'$raw' failed to parse: ${e.message}")
        }

        val nowTime = Instant.ofEpochMilli(state.nowMillis).atZone(ZoneId.systemDefault()).toLocalTime()
        if (!newTime.isAfter(nowTime)) {
            return reject(RejectionReason.INVALID_RESCHEDULE_TIME, "'$raw' is not later than current time $nowTime")
        }

        return PolicyResult.Permitted(PermittedAction.RescheduleTask(task.id, raw))
    }

    private fun validateShortBreak(intent: LlmIntent, state: PolicyState): PolicyResult {
        val task = state.task
            ?: return reject(RejectionReason.UNKNOWN_TASK_ID, "no task in PolicyState")
        if (task.state != TaskState.PENDING && task.state != TaskState.ACTIVE) {
            return reject(
                RejectionReason.TASK_NOT_ACTIVE_STATE,
                "task ${task.id} is ${task.state}, cannot take a break on a task that isn't pending/active"
            )
        }

        val raw = intent.parameters["minutes"]
            ?: return reject(RejectionReason.MALFORMED_INTENT, "missing minutes")
        val minutes = raw.toIntOrNull()
            ?: return reject(RejectionReason.MALFORMED_INTENT, "minutes '$raw' is not an integer")

        if (minutes <= 0) {
            return reject(RejectionReason.NEGATIVE_OR_ZERO_DURATION, "minutes=$minutes")
        }
        if (minutes > MAX_BREAK_MINUTES) {
            return reject(RejectionReason.BREAK_TOO_LONG, "minutes=$minutes exceeds max $MAX_BREAK_MINUTES")
        }
        return PolicyResult.Permitted(PermittedAction.ShortBreak(task.id, minutes))
    }

    private fun reject(reason: RejectionReason, detail: String) = PolicyResult.Rejected(reason, detail)
}
