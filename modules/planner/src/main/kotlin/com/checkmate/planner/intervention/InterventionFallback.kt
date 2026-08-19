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
 *
 * Two entry points, for two different kinds of caller:
 *  - [decideAndExecute] backs callers that must resolve the transaction *right now* — the
 *    notification's own Start button ([InterventionActionReceiver]), the negotiation
 *    screen's Start button, and the TTL-expiry sweep. If [llmPrompt] is null, or the LLM
 *    doesn't respond in time, or [LlmIntentParser] can't make sense of what it said, this
 *    always falls back to [InterventionFallback.strictReminderIntent] — these callers have
 *    no "come back later," so something decisive has to happen.
 *  - [attemptConversationalTurn] backs the live back-and-forth on the negotiation screen
 *    (Step 11 wiring — see its own doc below) — a single chat message is *not* the same
 *    kind of event as a Start-button tap, and forcing a resolution on every turn (e.g.
 *    silently START_TASK-ing the student's plan just because one LLM call timed out mid-
 *    conversation) would be actively hostile UX. Most turns are just talk.
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
        // Step 11: a raw LLM response is now actually trusted through LlmIntentParser
        // before it can influence anything — parse failure (or no response at all) falls
        // back to the deterministic reminder exactly as it always did, it just no longer
        // discards a *successful, parseable* response the way it did before this step.
        val rawResponse = if (llmPrompt != null) {
            InterventionFallback.attemptLlm(llmPrompt, llmSystemPrompt, llmTimeoutMillis, llmCall)
        } else null

        val parsedIntent = rawResponse?.let { LlmIntentParser.parse(it, fallbackTaskId = task.id) }
        val intent = parsedIntent ?: InterventionFallback.strictReminderIntent(task, lateMinutes)
        val usedFallback = parsedIntent == null

        val policyState = PolicyState(task = task, nowMillis = now)

        return when (val result = PolicyValidator.validate(intent, policyState)) {
            is PolicyResult.Permitted -> {
                val executionOutcome = actionExecutor.execute(transactionId, result.action, now)
                DecisionOutcome.Executed(executionOutcome, usedFallback, intent.speech)
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

    /**
     * Step 11. One turn of live negotiation: a raw LLM response the caller already has in
     * hand (the ViewModel owns the attemptLlm call + chat transcript, since it needs the
     * text to display either way — this function starts from the raw string rather than
     * calling attemptLlm itself, to avoid a second, subtly different timeout/prompt path).
     *
     * Deliberately does NOT fall back to [InterventionFallback.strictReminderIntent] on a
     * failed/unparseable response, unlike [decideAndExecute] — mid-conversation, "the model
     * didn't answer in time" should read as "let me try that again," not as the app quietly
     * force-starting the task and ending the negotiation the student is actively having.
     * [Continue] and [Rejected] both leave the transaction open in NEGOTIATING; only a
     * genuinely decisive [PermittedAction] reaches [actionExecutor] and resolves it.
     * RequestClarification/NoAction are Permitted by PolicyValidator like anything else (so
     * they're still covered by the same policy test matrix) but are intentionally excluded
     * here from reaching the executor: [ActionExecutor.execute] commits the transaction for
     * *every* outcome including [ExecutionOutcome.NotApplicable], and committing the
     * transaction the moment the AI Mentor asks a clarifying question would end the
     * conversation after a single exchange.
     */
    suspend fun attemptConversationalTurn(
        transactionId: String,
        task: StudyTask,
        rawLlmResponse: String?,
        now: Long = System.currentTimeMillis()
    ): ConversationalOutcome {
        if (rawLlmResponse == null) return ConversationalOutcome.LlmUnavailable

        val intent = LlmIntentParser.parse(rawLlmResponse, fallbackTaskId = task.id)
            ?: return ConversationalOutcome.UnparseableResponse

        val policyState = PolicyState(task = task, nowMillis = now)
        return when (val result = PolicyValidator.validate(intent, policyState)) {
            is PolicyResult.Rejected ->
                ConversationalOutcome.Rejected(intent.speech, result.reason, result.detail)
            is PolicyResult.Permitted -> when (result.action) {
                is PermittedAction.RequestClarification, is PermittedAction.NoAction ->
                    ConversationalOutcome.Continue(intent.speech)
                else -> {
                    val executionOutcome = actionExecutor.execute(transactionId, result.action, now)
                    ConversationalOutcome.Decided(intent.speech, executionOutcome)
                }
            }
        }
    }
}

sealed class DecisionOutcome {
    data class Executed(
        val executionOutcome: ExecutionOutcome,
        val usedFallback: Boolean,
        // Step 11: the speech actually being shown/spoken now varies (a parsed LLM intent
        // vs. the fixed strict-reminder line) — callers no longer have to reach back into
        // InterventionFallback themselves to know what was said.
        val speech: String
    ) : DecisionOutcome()
    data class PolicyRejected(val reason: RejectionReason, val detail: String, val usedFallback: Boolean) : DecisionOutcome()
}

/** Step 11. Outcome of one [InterventionDecisionMaker.attemptConversationalTurn] call. */
sealed class ConversationalOutcome {
    /** attemptLlm produced nothing within budget (timeout, blank, or LlmGateway failure). */
    object LlmUnavailable : ConversationalOutcome()
    /** A response arrived but didn't match the closed schema — see [LlmIntentParser]. */
    object UnparseableResponse : ConversationalOutcome()
    /** RequestClarification / NoAction — nothing executed, transaction stays NEGOTIATING. */
    data class Continue(val speech: String) : ConversationalOutcome()
    /** A well-formed intent that PolicyValidator declined. Transaction stays NEGOTIATING —
     *  a rejected suggestion is a "no" to one idea, not a reason to end the conversation. */
    data class Rejected(val speech: String, val reason: RejectionReason, val detail: String) : ConversationalOutcome()
    /** A decisive action reached ActionExecutor and the transaction has resolved. */
    data class Decided(val speech: String, val executionOutcome: ExecutionOutcome) : ConversationalOutcome()
}
