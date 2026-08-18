package com.checkmate.ui.negotiation

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.checkmate.core.stt.CheckmateSTT
import com.checkmate.core.tts.CheckmateTTS
import com.checkmate.planner.PlanStore
import com.checkmate.planner.intervention.ActionExecutor
import com.checkmate.planner.intervention.EscrowExtendResult
import com.checkmate.planner.intervention.InterventionDatabase
import com.checkmate.planner.intervention.InterventionDecisionMaker
import com.checkmate.planner.intervention.InterventionFallback
import com.checkmate.planner.intervention.PlanStoreTaskMutator
import com.checkmate.planner.intervention.TaskEscrow
import com.checkmate.planner.model.StudyTask
import com.checkmate.psyche.intervention.ContextBuilder
import com.checkmate.psyche.intervention.InterventionContext
import com.checkmate.service.InterventionNotifier
import com.checkmate.service.InterventionSnoozeAlarmReceiver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class NegotiationMessage(val role: String, val content: String)

enum class NegotiationResolution { NONE, STARTED, SNOOZED, DISMISSED, ALREADY_RESOLVED, TASK_MISSING }

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
 * Proactive Execution Engine — Step 10 (Blueprint Part One, §8-9, §16's "Talk to Checkmate").
 *
 * This is what a tap on "Talk to Checkmate" (or the notification body) now actually opens
 * into — [InterventionNotifier]'s own doc comment on `talkPendingIntent` flagged this exact
 * gap: "today this just brings the app to the foreground; MainActivity does not currently
 * read EXTRA_TRANSACTION_ID out of its intent." This ViewModel is the consumer that closes
 * that gap, reusing [InterventionNotifier.EXTRA_TRANSACTION_ID]/`EXTRA_TASK_ID`/
 * `EXTRA_LATE_MINUTES` exactly as InterventionNotifier already attaches them.
 *
 * IMPORTANT SCOPE BOUNDARY — matches [InterventionFallback]'s own doc comment almost word
 * for word: Structured LLM intents (Step 11) are not built yet. The back-and-forth here —
 * student speaks or types, [InterventionFallback.attemptLlm] (→ LlmGateway) replies,
 * [CheckmateTTS] speaks it back — is genuinely conversational, but nothing parses what the
 * LLM says into an executable decision, because that parser doesn't exist yet. The only
 * things that actually resolve this transaction are the three explicit actions below
 * ([onStart], [onSnooze], [onDismiss]) — they reuse exactly the same
 * [InterventionDecisionMaker] / [TaskEscrow] construction [InterventionActionReceiver]
 * already uses for the notification's own "Start"/"Snooze" buttons, not a second parallel
 * execution path. Talking to Checkmate today is advisory, on purpose — it doesn't (and
 * shouldn't, until Step 11's parser exists to validate it) let the LLM decide anything on
 * its own. The system prompt below tells the LLM this explicitly, so it doesn't imply to
 * the student that saying something out loud has already changed the plan.
 */
class NegotiationViewModel : ViewModel() {

    private val _state = MutableStateFlow(NegotiationUiState())
    val state: StateFlow<NegotiationUiState> = _state.asStateFlow()

    private var stt: CheckmateSTT? = null
    private var initialized = false

    /** Idempotent on purpose — Compose may recompose/re-enter this call across
     *  configuration changes; only the first call for this ViewModel instance should do
     *  the DB read + opening TTS utterance. */
    fun init(context: Context, transactionId: String, taskId: String, lateMinutes: Int) {
        if (initialized) return
        initialized = true

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

    /** Shared entry point for both the text field and a completed voice transcript. */
    private fun send(context: Context, text: String) {
        if (_state.value.isSending || _state.value.resolution != NegotiationResolution.NONE) return
        val task = _state.value.task ?: return
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
            val reply = InterventionFallback.attemptLlm(
                prompt = history,
                systemPrompt = systemPrompt,
                timeoutMillis = CONVERSATION_LLM_TIMEOUT_MILLIS
            ) ?: "I'm having trouble reaching the mentor model right now — the plan still stands. Use Start, Snooze, or Dismiss below."

            _state.update { it.copy(messages = it.messages + NegotiationMessage("assistant", reply), isSending = false) }
            CheckmateTTS.speak(context, reply)
        }
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
            decisionMaker.decideAndExecute(
                transactionId = transactionId,
                task = task,
                lateMinutes = lateMinutes
            )
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
            val dao = InterventionDatabase.getInstance(context).interventionTransactionDao()
            val escrow = TaskEscrow(dao)
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
            val dao = InterventionDatabase.getInstance(context).interventionTransactionDao()
            val escrow = TaskEscrow(dao)
            escrow.abort(transactionId, reason = "Dismissed from negotiation screen")
            InterventionNotifier.cancel(context.applicationContext, transactionId)
            _state.update { it.copy(resolution = NegotiationResolution.DISMISSED) }
        }
    }

    private fun buildEscrowAndDecisionMaker(context: Context): Pair<TaskEscrow, InterventionDecisionMaker> {
        val dao = InterventionDatabase.getInstance(context).interventionTransactionDao()
        val escrow = TaskEscrow(dao)
        val executor = ActionExecutor(dao, escrow, PlanStoreTaskMutator())
        return escrow to InterventionDecisionMaker(escrow, executor)
    }

    private fun buildHistoryForLlm(): String {
        val msgs = _state.value.messages.takeLast(12)
        return msgs.joinToString("\n") { m ->
            if (m.role == "user") "Student: ${m.content}" else "Mentor: ${m.content}"
        }
    }

    private fun buildSystemPrompt(task: StudyTask, ctx: InterventionContext?): String = buildString {
        appendLine("You are Checkmate's AI Mentor, talking with a student about ${task.subject} — ${task.topic},")
        appendLine("which is late or off-plan right now. Be direct, warm, and brief — 1 to 3 sentences. This is")
        appendLine("a spoken conversation, not an essay.")
        appendLine("You cannot change the plan yourself. If the student wants a shorter session, a break, or a")
        appendLine("reschedule, acknowledge what they said honestly, but tell them to use the Start / Snooze 5m /")
        appendLine("Dismiss buttons on screen to actually act on it — never claim you've already changed anything,")
        appendLine("since nothing you say here executes on its own yet.")
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
