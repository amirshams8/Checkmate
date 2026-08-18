package com.checkmate.planner.intervention

import com.checkmate.core.llm.LlmGateway
import com.checkmate.planner.model.StudyTask
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Proactive Execution Engine — Step 6 (Blueprint Part One, §14, §25 principle 2).
 *
 * "The internet must never become Checkmate's single point of failure." [attemptLlm] wraps
 * an LLM call with a short, intervention-specific timeout — deliberately not a change to
 * [LlmGateway]'s own client timeouts, which other callers (e.g. AdaptivePlanner's plan
 * generation) reasonably rely on being longer. [strictReminderIntent] is the deterministic
 * fallback itself: a fixed, non-LLM response that still goes through PolicyValidator like
 * any other intent (§14's own diagram routes STRICT_REMINDER through PolicyValidator, not
 * around it) — the policy boundary doesn't get to be softer just because nothing chose to
 * be clever here.
 *
 * Structured LLM intent parsing (turning a real LLM response into a validated [LlmIntent])
 * is step 11, not yet built. Until it exists, there is nothing safe to do with even a
 * successful LLM response — no parser to trust it through — so [InterventionDecisionMaker]
 * always resolves via the deterministic path below for now. [attemptLlm] is still exercised
 * on the LLM path (rather than skipped) so the timeout/failure detection this step is
 * actually about is real and tested today, not deferred to step 11 along with the parser.
 */
object InterventionFallback {

    /** Blueprint §14/§18: "3-second timeout." */
    const val DEFAULT_LLM_TIMEOUT_MILLIS = 3_000L

    /**
     * Attempts an LLM call with a hard timeout. Returns null on timeout, on a blank
     * response, or on any failure LlmGateway itself already collapses to "" (missing API
     * key, HTTP error, exception — see LlmGateway.complete's own doc). Callers should treat
     * null uniformly as "the LLM path did not produce anything usable in time."
     *
     * [llmCall] defaults to the real [LlmGateway.complete] and is only overridden in tests,
     * so this doesn't need Robolectric/instrumented tests to verify timeout behavior — a
     * fake that suspends past the timeout exercises the exact same withTimeoutOrNull path
     * the production call goes through.
     */
    suspend fun attemptLlm(
        prompt: String,
        systemPrompt: String = "",
        timeoutMillis: Long = DEFAULT_LLM_TIMEOUT_MILLIS,
        // Was `= LlmGateway::complete` — a suspend function *reference* as a default
        // parameter value isn't supported by this Kotlin compiler config ("Unsupported
        // [suspend function calls in a context of default parameter value]"). A lambda
        // wrapping the same call compiles fine and is behaviorally identical.
        llmCall: suspend (String, String) -> String = { p, s -> LlmGateway.complete(p, s) }
    ): String? {
        val result = withTimeoutOrNull(timeoutMillis) { llmCall(prompt, systemPrompt) }
        return result?.takeIf { it.isNotBlank() }
    }

    /**
     * The deterministic STRICT_REMINDER (§14's own worked example). Always a START_TASK
     * intent — the fallback's whole purpose is to get the student moving on what was
     * already planned, not to negotiate or reduce scope; negotiation is the AI Mentor's
     * job once it exists.
     */
    fun strictReminderIntent(task: StudyTask, lateMinutes: Int): LlmIntent {
        val speech = if (lateMinutes > 0) {
            "You're $lateMinutes minutes late. Start ${task.topic} now. " +
                "I'll adjust the remaining schedule afterward."
        } else {
            "It's time to start ${task.topic}."
        }
        return LlmIntent(
            speech = speech,
            intentType = InterventionIntentType.START_TASK,
            targetTaskId = task.id
        )
    }
}

/**
 * Orchestrates one intervention decision: try the LLM within budget (if asked to at all),
 * then resolve deterministically either way, through PolicyValidator and ActionExecutor —
 * never around them. Owns nothing about escrow lifecycle itself beyond resolving
 * POLICY_REJECTED directly (ActionExecutor already resolves every other outcome as part of
 * [ActionExecutor.execute]); the transaction must already be acquired via [TaskEscrow]
 * before this is called.
 */
class InterventionDecisionMaker(
    private val taskEscrow: TaskEscrow,
    private val actionExecutor: ActionExecutor
) {
    suspend fun decideAndExecute(
        transactionId: String,
        task: StudyTask,
        lateMinutes: Int,
        now: Long = System.currentTimeMillis(),
        llmPrompt: String? = null,
        llmSystemPrompt: String = "",
        llmTimeoutMillis: Long = InterventionFallback.DEFAULT_LLM_TIMEOUT_MILLIS,
        // Same fix as InterventionFallback.attemptLlm above.
        llmCall: suspend (String, String) -> String = { p, s -> LlmGateway.complete(p, s) }
    ): DecisionOutcome {
        if (llmPrompt != null) {
            // Result intentionally unused for now — see InterventionFallback's class doc.
            InterventionFallback.attemptLlm(llmPrompt, llmSystemPrompt, llmTimeoutMillis, llmCall)
        }
        val usedFallback = true

        val intent = InterventionFallback.strictReminderIntent(task, lateMinutes)
        val policyState = PolicyState(task = task, nowMillis = now)

        return when (val result = PolicyValidator.validate(intent, policyState)) {
            is PolicyResult.Permitted -> {
                val executionOutcome = actionExecutor.execute(transactionId, result.action, now)
                DecisionOutcome.Executed(executionOutcome, usedFallback)
            }
            is PolicyResult.Rejected -> {
                taskEscrow.resolveAs(
                    transactionId,
                    InterventionState.POLICY_REJECTED,
                    failureReason = result.detail
                )
                DecisionOutcome.PolicyRejected(result.reason, result.detail, usedFallback)
            }
        }
    }
}

sealed class DecisionOutcome {
    data class Executed(val executionOutcome: ExecutionOutcome, val usedFallback: Boolean) : DecisionOutcome()
    data class PolicyRejected(val reason: RejectionReason, val detail: String, val usedFallback: Boolean) : DecisionOutcome()
}
