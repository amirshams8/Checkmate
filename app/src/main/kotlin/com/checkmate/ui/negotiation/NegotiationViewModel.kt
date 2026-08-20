package com.checkmate.ui.negotiation

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.checkmate.core.stt.CheckmateSTT
import com.checkmate.core.tts.CheckmateTTS
import com.checkmate.planner.PlanStore
import com.checkmate.planner.intervention.ActionExecutor
import com.checkmate.planner.intervention.ConversationalOutcome
import com.checkmate.planner.intervention.DecisionOutcome
import com.checkmate.planner.intervention.EscrowExtendResult
import com.checkmate.planner.intervention.ExecutionOutcome
import com.checkmate.planner.intervention.InterventionDatabase
import com.checkmate.planner.intervention.InterventionDecisionMaker
import com.checkmate.planner.intervention.InterventionFallback
import com.checkmate.planner.intervention.OutcomeLedgerWriter
import com.checkmate.planner.intervention.PermittedAction
import com.checkmate.planner.intervention.PlanStoreTaskMutator
import com.checkmate.planner.intervention.TaskEscrow
import com.checkmate.planner.model.StudyTask
import com.checkmate.psyche.intervention.ContextBuilder
import com.checkmate.psyche.intervention.InterventionContext
import com.checkmate.service.InterventionNotifier
import com.checkmate.service.InterventionSideEffects
import com.checkmate.service.InterventionSnoozeAlarmReceiver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class NegotiationMessage(val role: String, val content: String)

enum class NegotiationResolution {
    NONE, STARTED, SNOOZED, DISMISSED, ALREADY_RESOLVED, TASK_MISSING,
    // Step 11: any conversational decision other than STARTED — REDUCE_DURATION,
    // RESCHEDULE_TASK, TAKE_SHORT_BREAK, KEEP_PLAN, or REQUEST_GUARDIAN all land here.
    // The negotiation screen doesn't need to distinguish between them to close itself —
    // the assistant's last chat bubble already says what happened in plain language.
    PLAN_ADJUSTED
}

data class NegotiationUiState(
    val loading: Boolean = true,
    val task: StudyTask? = null,
    val context: InterventionContext? = null,
    val messages: List<NegotiationMessage> = emptyList(),
    val isSending: Boolean = false,
    val isListening: Boolean = false,
    val sttAvailable: Boolean = true,
    val sttError: String? = null,
    val inputText: String = "",
    val resolution: NegotiationResolution = NegotiationResolution.NONE
)

/**
 * Proactive Execution Engine — Step 10 + Step 11 (Blueprint Part One, §8-11, §16's
 * "Talk to Checkmate"), amended in Step 12 (§22) to wire the Outcome Ledger through every
 * [TaskEscrow] this ViewModel constructs.
 *
 * This is what a tap on "Talk to Checkmate" (or the notification body) opens into.
 *
 * STEP 11 UPDATE: the scope boundary this class's doc used to describe — "nothing parses
 * what the LLM says into an executable decision, because that parser doesn't exist yet" —
 * no longer holds. [send] now runs every reply through
 * [InterventionDecisionMaker.attemptConversationalTurn], which parses the raw LLM response
 * via [com.checkmate.planner.intervention.LlmIntentParser] and, for a genuinely decisive
 * intent, validates it through PolicyValidator and applies it through ActionExecutor — the
 * exact same pipeline [onStart] already used for the notification's own button. Most turns
 * still don't execute anything (a clarifying question, small talk, a rejected suggestion) —
 * see [InterventionDecisionMaker.attemptConversationalTurn]'s own doc for why those
 * deliberately leave the transaction open rather than resolving it. [onStart]/[onSnooze]/
 * [onDismiss] remain the three buttons that resolve things unconditionally, unrelated to
 * whatever the conversation is doing.
 */
class NegotiationViewModel : ViewModel() {

    private val _state = MutableStateFlow(NegotiationUiState())
    val state: StateFlow<NegotiationUiState> = _state.asStateFlow()

    private var stt: CheckmateSTT? = null
    private var initialized = false

    // Step 11: send() needs this to call attemptConversationalTurn, and previously nothing
    // retained it after init() beyond the local scope of the initial DB read.
    private var currentTransactionId: String? = null

    /** Idempotent on purpose — Compose may recompose/re-enter this call across
     *  configuration changes; only the first call for this ViewModel instance should do
     *  the DB read + opening TTS utterance. */
    fun init(context: Context, transactionId: String, taskId: String, lateMinutes: Int) {
        if (initialized) return
        initialized = true
        currentTransactionId = transactionId

        val sttInstance = CheckmateSTT(context.applicationContext)
        stt = sttInstance
        _state.update { it.copy(sttAvailable = sttInstance.isAvailable()) }

        viewModelScope.launch {
            val dao = InterventionDatabase.getInstance(context).interventionTransactionDao()
            val transaction = dao.getById(transactionId)
            if (transaction == null || transaction.currentState.isTerminal) {
                _state.update { it.copy(loading = false, resolution = NegotiationResolution.ALREADY_RESOLVED) }
                return@launch
            }

            val task = PlanStore.todayTasks.value.find { it.id == taskId }
            if (task == null) {
                _state.update { it.copy(loading = false, resolution = NegotiationResolution.TASK_MISSING) }
                return@launch
            }

            // Same best-effort posture InterventionNotifier already uses around
            // ContextBuilder.build — a missing behavioral summary shouldn't block the
            // conversation from opening at all.
            val ctx = runCatching { ContextBuilder.build(task, lateMinutes) }.getOrNull()

            // The opening line is the exact same deterministic STRICT_REMINDER speech the
            // no-gateway fallback (and the notification's "Start" action) would use — this
            // conversation is layered on top of that reality, not a different story.
            val opening = InterventionFallback.strictReminderIntent(task, lateMinutes).speech

            _state.update {
                it.copy(
                    loading = false,
                    task = task,
                    context = ctx,
                    messages = listOf(NegotiationMessage("assistant", opening))
                )
            }
            CheckmateTTS.speak(context, opening)

            // Runs for the lifetime of this ViewModel — a final transcript from voice input
            // becomes a sent message through exactly the same path as the text field's send
            // button (see the private send() overload below).
            launch {
                sttInstance.state.collect { s ->
                    _state.update { it.copy(isListening = s.isListening, sttError = s.error) }
                    val finalText = s.finalText
                    if (!finalText.isNullOrBlank()) {
                        sttInstance.consumeFinalText()
                        send(context, finalText)
                    }
                }
            }
        }
    }

    fun setInput(text: String) = _state.update { it.copy(inputText = text) }

    fun startListening() {
        if (_state.value.resolution != NegotiationResolution.NONE) return
        stt?.startListening()
    }

    fun stopListening() {
        stt?.stopListening()
    }

    /** Text-field send button entry point. */
    fun send(context: Context) {
        val text = _state.value.inputText.trim()
        if (text.isBlank()) return
        _state.update { it.copy(inputText = "") }
        send(context, text)
    }

    /**
     * Shared entry point for both the text field and a completed voice transcript.
     *
     * Step 11: this now asks the LLM for a structured intent (not just conversational
     * text), runs the raw response through [InterventionDecisionMaker.attemptConversationalTurn],
     * and reacts to whichever [ConversationalOutcome] comes back. The LLM call itself still
     * goes through [InterventionFallback.attemptLlm] directly (rather than letting
     * attemptConversationalTurn call it) so this function keeps ownership of the timeout
     * budget and the raw string, exactly as before — attemptConversationalTurn only takes
     * over from "here's what the model said" onward.
     */
    private fun send(context: Context, text: String) {
        if (_state.value.isSending || _state.value.resolution != NegotiationResolution.NONE) return
        val task = _state.value.task ?: return
        val transactionId = currentTransactionId ?: return
        val ctx = _state.value.context

        _state.update { it.copy(messages = it.messages + NegotiationMessage("user", text), isSending = true) }

        viewModelScope.launch {
            val systemPrompt = buildSystemPrompt(task, ctx)
            val history = buildHistoryForLlm()
            // A longer budget than InterventionFallback.DEFAULT_LLM_TIMEOUT_MILLIS (3s) on
            // purpose: that 3s default is sized for a silent background decision the
            // student never sees waiting happen (§14/§18). This is the opposite case — the
            // student is watching an active screen and knows a reply is coming, so it's
            // worth waiting longer for a real answer before falling back.
            val raw = InterventionFallback.attemptLlm(
                prompt = history,
                systemPrompt = systemPrompt,
                timeoutMillis = CONVERSATION_LLM_TIMEOUT_MILLIS
            )

            val (_, decisionMaker) = buildEscrowAndDecisionMaker(context)
            when (val outcome = decisionMaker.attemptConversationalTurn(transactionId, task, raw)) {
                ConversationalOutcome.LlmUnavailable ->
                    reply(context, "I'm having trouble reaching the mentor model right now — the plan still stands. Use Start, Snooze, or Dismiss below.")

                ConversationalOutcome.UnparseableResponse ->
                    reply(context, "Sorry, I didn't quite catch that — could you say it a different way?")

                is ConversationalOutcome.Continue ->
                    reply(context, outcome.speech)

                is ConversationalOutcome.Rejected ->
                    reply(context, outcome.speech)

                is ConversationalOutcome.Decided -> {
                    InterventionNotifier.cancel(context.applicationContext, transactionId)
                    // The only production path that can actually reach RequestGuardian or
                    // ShortBreak — see InterventionSideEffects' own doc for why onStart()
                    // and InterventionActionReceiver.handleStart() can't in practice today.
                    InterventionSideEffects.handle(context, task, transactionId, outcome.executionOutcome)
                    _state.update {
                        it.copy(
                            messages = it.messages + NegotiationMessage("assistant", outcome.speech),
                            isSending = false,
                            resolution = resolutionFor(outcome.executionOutcome)
                        )
                    }
                    CheckmateTTS.speak(context, outcome.speech)
                }
            }
        }
    }

    private fun reply(context: Context, text: String) {
        _state.update { it.copy(messages = it.messages + NegotiationMessage("assistant", text), isSending = false) }
        CheckmateTTS.speak(context, text)
    }

    /** Maps a resolved conversational turn onto the same [NegotiationResolution] the
     *  button-driven paths use, so the screen's existing "show a label, then close" flow
     *  (see NegotiationScreen's LaunchedEffect on state.resolution) needs no special case
     *  for how the resolution was reached. */
    private fun resolutionFor(outcome: ExecutionOutcome): NegotiationResolution = when (outcome) {
        is ExecutionOutcome.Applied ->
            if (outcome.action is PermittedAction.StartTask) NegotiationResolution.STARTED else NegotiationResolution.PLAN_ADJUSTED
        is ExecutionOutcome.NoOpAlreadyApplied ->
            if (outcome.action is PermittedAction.StartTask) NegotiationResolution.STARTED else NegotiationResolution.PLAN_ADJUSTED
        is ExecutionOutcome.NotApplicable -> NegotiationResolution.PLAN_ADJUSTED
        ExecutionOutcome.RequiresGuardianEscalation -> NegotiationResolution.PLAN_ADJUSTED
        is ExecutionOutcome.Failed -> NegotiationResolution.PLAN_ADJUSTED
        ExecutionOutcome.TransactionAlreadyResolved, ExecutionOutcome.TransactionNotFound -> NegotiationResolution.ALREADY_RESOLVED
    }

    /**
     * Student tapped "Start". Resolves through the identical deterministic
     * STRICT_REMINDER -> START_TASK -> PolicyValidator -> ActionExecutor path
     * [InterventionActionReceiver.handleStart] already uses for the notification's own
     * "Start" button — there is exactly one place "what happens when we decide to start
     * this task" lives, regardless of whether the student tapped a notification action or
     * talked their way here.
     */
    fun onStart(context: Context, transactionId: String, lateMinutes: Int) {
        if (_state.value.resolution != NegotiationResolution.NONE) return
        val task = _state.value.task ?: return
        stt?.stopListening()
        viewModelScope.launch {
            val (_, decisionMaker) = buildEscrowAndDecisionMaker(context)
            val decision = decisionMaker.decideAndExecute(
                transactionId = transactionId,
                task = task,
                lateMinutes = lateMinutes
            )
            if (decision is DecisionOutcome.Executed) {
                InterventionSideEffects.handle(context, task, transactionId, decision.executionOutcome)
            }
            InterventionNotifier.cancel(context.applicationContext, transactionId)
            _state.update { it.copy(resolution = NegotiationResolution.STARTED) }
        }
    }

    /** Same TaskEscrow.extend() + re-notify alarm as
     *  [InterventionActionReceiver.handleSnooze] — see that method's doc for why this
     *  doesn't re-run TriggerEvaluator or re-acquire escrow. */
    fun onSnooze(context: Context, transactionId: String, taskId: String, lateMinutes: Int) {
        if (_state.value.resolution != NegotiationResolution.NONE) return
        stt?.stopListening()
        viewModelScope.launch {
            val db = InterventionDatabase.getInstance(context)
            val dao = db.interventionTransactionDao()
            val ledgerWriter = OutcomeLedgerWriter(db.outcomeLedgerDao())
            val escrow = TaskEscrow(dao, ledgerWriter)
            val result = escrow.extend(transactionId, InterventionNotifier.SNOOZE_MILLIS)
            if (result is EscrowExtendResult.Extended) {
                InterventionSnoozeAlarmReceiver.scheduleRenotify(
                    context.applicationContext, transactionId, taskId, lateMinutes
                )
            }
            InterventionNotifier.cancel(context.applicationContext, transactionId)
            _state.update { it.copy(resolution = NegotiationResolution.SNOOZED) }
        }
    }

    /** USER_ABORTED (§3) — the student declined via the conversation screen rather than
     *  ignoring the notification (USER_IGNORED) or running out the TTL. */
    fun onDismiss(context: Context, transactionId: String) {
        if (_state.value.resolution != NegotiationResolution.NONE) return
        stt?.stopListening()
        viewModelScope.launch {
            val db = InterventionDatabase.getInstance(context)
            val dao = db.interventionTransactionDao()
            val ledgerWriter = OutcomeLedgerWriter(db.outcomeLedgerDao())
            val escrow = TaskEscrow(dao, ledgerWriter)
            escrow.abort(transactionId, reason = "Dismissed from negotiation screen")
            InterventionNotifier.cancel(context.applicationContext, transactionId)
            _state.update { it.copy(resolution = NegotiationResolution.DISMISSED) }
        }
    }

    private fun buildEscrowAndDecisionMaker(context: Context): Pair<TaskEscrow, InterventionDecisionMaker> {
        val db = InterventionDatabase.getInstance(context)
        val dao = db.interventionTransactionDao()
        val ledgerWriter = OutcomeLedgerWriter(db.outcomeLedgerDao())
        val escrow = TaskEscrow(dao, ledgerWriter)
        val executor = ActionExecutor(dao, escrow, PlanStoreTaskMutator())
        return escrow to InterventionDecisionMaker(escrow, executor)
    }

    private fun buildHistoryForLlm(): String {
        val msgs = _state.value.messages.takeLast(12)
        return msgs.joinToString("\n") { m ->
            if (m.role == "user") "Student: ${m.content}" else "Mentor: ${m.content}"
        }
    }

    /**
     * Step 11: this now asks for a strict JSON [com.checkmate.planner.intervention.LlmIntent]
     * instead of free conversational text — "speech" carries what previously was the whole
     * response. The intentType/parameters vocabulary listed here must stay in lockstep with
     * [com.checkmate.planner.intervention.InterventionIntentType] and what
     * [com.checkmate.planner.intervention.PolicyValidator] actually reads out of
     * `parameters` — if a new intent type or parameter key is ever added there, it needs to
     * be documented in this prompt too, or the model will never produce it.
     */
    private fun buildSystemPrompt(task: StudyTask, ctx: InterventionContext?): String = buildString {
        appendLine("You are Checkmate's AI Mentor, negotiating with a student about ${task.subject} — ${task.topic}")
        appendLine("(current planned duration: ${task.durationMinutes} minutes), which is late or off-plan right now.")
        appendLine()
        appendLine("Respond with ONLY a single JSON object — no markdown code fences, no text before or after it —")
        appendLine("in exactly this shape:")
        appendLine("""{"speech": "...", "intentType": "...", "targetTaskId": "${task.id}", "parameters": {}}""")
        appendLine()
        appendLine("\"speech\" is what you actually say to the student out loud: direct, warm, brief (1-3 sentences),")
        appendLine("a spoken reply, not an essay. Never claim you've already changed the plan in \"speech\" — describe")
        appendLine("what you're recommending. A separate policy layer decides what's actually permitted (for example,")
        appendLine("it will reject breaks longer than 30 minutes), and only that layer's decision, not your words,")
        appendLine("changes anything.")
        appendLine()
        appendLine("\"intentType\" must be exactly one of:")
        appendLine("  START_TASK            student is ready to begin right now")
        appendLine("  REDUCE_DURATION       wants a shorter session — parameters: {\"newDurationMinutes\": \"<int>\"}")
        appendLine("  RESCHEDULE_TASK       wants to move the start time — parameters: {\"newScheduledStartTime\": \"HH:mm\"}")
        appendLine("  TAKE_SHORT_BREAK      needs a short break first — parameters: {\"minutes\": \"<int, 30 max>\"}")
        appendLine("  KEEP_PLAN             no change needed, the plan as scheduled stands")
        appendLine("  REQUEST_CLARIFICATION you need more information before recommending anything")
        appendLine("  NO_ACTION             just talking/acknowledging, nothing to decide yet")
        appendLine("  REQUEST_GUARDIAN      this needs a real person (guardian), not you")
        appendLine()
        appendLine("Omit \"parameters\" (or leave it {}) for any intentType that doesn't list one above.")
        if (ctx != null) {
            appendLine()
            appendLine(ctx.toPromptText())
        }
    }.trim()

    override fun onCleared() {
        super.onCleared()
        stt?.stopListening()
    }

    companion object {
        private const val CONVERSATION_LLM_TIMEOUT_MILLIS = 12_000L
    }
}
