package com.checkmate.ui.mentor

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.checkmate.core.AppUsageTracker
import com.checkmate.core.CheckmatePrefs
import com.checkmate.core.CoachingPlannerEntry
import com.checkmate.core.ConsultationProfile
import com.checkmate.core.ConsultationProfile.Companion.toPromptContext
import com.checkmate.core.MentorKnowledge
import com.checkmate.core.TodayContext
import com.checkmate.core.llm.LlmGateway
import com.checkmate.core.tts.CheckmateTTS
import com.checkmate.learning.model.RetentionDecisionSnapshot
import com.checkmate.learning.model.StudentModel
import com.checkmate.learning.student.StudentModelBuilder
import com.checkmate.planner.PlanStore
import com.checkmate.planner.model.TaskState
import com.checkmate.psyche.BehaviorLedger
import com.checkmate.service.StudyStateSyncManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

private val historyJson = Json { ignoreUnknownKeys = true }
private const val PREFS_KEY_HISTORY = "mentor_chat_history"
private const val MAX_PERSISTED_MESSAGES = 40

@Serializable
data class MentorMessage(
    val role:    String,
    val content: String,
    val ts:      Long = System.currentTimeMillis()
)

data class MentorUiState(
    val messages:   List<MentorMessage> = emptyList(),
    val inputText:  String              = "",
    val isLoading:  Boolean             = false,
    val ttsEnabled: Boolean             = CheckmatePrefs.getBoolean("tts_enabled", true)
)

class MentorViewModel : ViewModel() {

    private val _state = MutableStateFlow(MentorUiState())
    val state: StateFlow<MentorUiState> = _state.asStateFlow()

    init {
        loadHistory()
        // CORRECTION: chat previously synced through a /mentor endpoint that was
        // never added to the worker (permanent 404 — see StudyStateSyncManager's
        // class doc). Now routed through /studystate's dedicated mentor path
        // instead. Pull-on-open picks up whatever the OTHER device pushed since
        // this device last had the screen open — pullStateIfEmpty() alone can't
        // do this because it only restores when the local field is unset, which
        // is false the moment this device has ever sent one message.
        pullRemoteIfNewer()
    }

    private fun loadHistory() {
        val saved = CheckmatePrefs.getString(PREFS_KEY_HISTORY, null)
        val messages = if (saved != null) {
            try { historyJson.decodeFromString<List<MentorMessage>>(saved) }
            catch (_: Exception) { emptyList() }
        } else emptyList()

        val initial = if (messages.isEmpty())
            listOf(MentorMessage("assistant", "Ready. What do you need help with?"))
        else messages

        _state.update { it.copy(messages = initial) }
    }

    /**
     * Fetches the server's mentor history and adopts it only if it's strictly
     * newer than what's already loaded locally (by last-message timestamp) —
     * last-write-wins, matching StudyStateSyncManager's flat-overwrite model.
     * This guards against a slow network response landing after the student
     * has already sent a new local message and stomping it with a stale pull.
     * On adopt, re-persists locally but does NOT push back — that would just
     * echo the same history the server already has.
     */
    private fun pullRemoteIfNewer() {
        viewModelScope.launch(Dispatchers.IO) {
            val remoteJson = StudyStateSyncManager.pullMentorHistory() ?: return@launch
            val remoteMessages = try {
                historyJson.decodeFromString<List<MentorMessage>>(remoteJson)
            } catch (_: Exception) {
                return@launch
            }
            if (remoteMessages.isEmpty()) return@launch

            val remoteLastTs = remoteMessages.last().ts
            val localLastTs = _state.value.messages.lastOrNull()?.ts ?: 0L
            if (remoteLastTs > localLastTs) {
                _state.update { it.copy(messages = remoteMessages) }
                persistHistory(remoteMessages)
            }
        }
    }

    private fun persistHistory(messages: List<MentorMessage>): String {
        val trimmed = messages.takeLast(MAX_PERSISTED_MESSAGES)
        val json = historyJson.encodeToString(trimmed)
        CheckmatePrefs.putString(PREFS_KEY_HISTORY, json)
        return json
    }

    /** Persists locally, then pushes to the other device off the main thread. */
    private fun persistAndSync(messages: List<MentorMessage>) {
        val json = persistHistory(messages)
        viewModelScope.launch(Dispatchers.IO) { StudyStateSyncManager.pushMentorMessage(json) }
    }

    fun setInput(text: String) = _state.update { it.copy(inputText = text) }

    fun send(context: Context) {
        val text = _state.value.inputText.trim()
        if (text.isBlank() || _state.value.isLoading) return

        val userMsg = MentorMessage("user", text)
        val updatedMessages = _state.value.messages + userMsg
        _state.update { it.copy(messages = updatedMessages, inputText = "", isLoading = true) }
        persistAndSync(updatedMessages)

        viewModelScope.launch {
            val response = try {
                val systemPrompt = buildSystemPrompt(context, text)
                val history      = buildHistoryForLlm()
                LlmGateway.complete(history, systemPrompt)
            } catch (e: Exception) {
                "I couldn't process that right now. Try again."
            }

            val assistantMsg = MentorMessage(
                "assistant",
                response.ifBlank { "No response. Check your API key in Settings." }
            )
            val finalMessages = _state.value.messages + assistantMsg
            _state.update { it.copy(messages = finalMessages, isLoading = false) }
            persistAndSync(finalMessages)

            if (_state.value.ttsEnabled && response.isNotBlank()) {
                val firstSentence = response.split(". ", ".\n").firstOrNull()?.take(200) ?: response.take(200)
                CheckmateTTS.speak(context, firstSentence)
            }
        }
    }

    fun clearHistory() {
        val initial = listOf(MentorMessage("assistant", "Ready. What do you need help with?"))
        _state.update { it.copy(messages = initial) }
        // FIX: putString(key, null) crashes on non-null String param — use remove() instead
        CheckmatePrefs.remove(PREFS_KEY_HISTORY)
        // Push the reset state too, so the other device's next pullRemoteIfNewer()
        // picks up the clear instead of silently keeping stale history around.
        viewModelScope.launch(Dispatchers.IO) {
            StudyStateSyncManager.pushMentorMessage(historyJson.encodeToString(initial))
        }
    }

    private fun buildHistoryForLlm(): String {
        val msgs = _state.value.messages.takeLast(20)
        return msgs.joinToString("\n") { msg ->
            if (msg.role == "user") "Student: ${msg.content}"
            else "Mentor: ${msg.content}"
        }
    }

    // Upgrade Blueprint Phase 2 wiring ("Mentor" as a StudentModel consumer):
    // suspend now — StudentModelBuilder.build() is a suspend DB read, same as every
    // other suspend call this function already awaits nothing for (buildSystemPrompt
    // itself did no suspending work before this). Its one call site (send(), above)
    // already invokes it from inside viewModelScope.launch, so this is a signature-only
    // change — no call-site rewrite needed.
    private suspend fun buildSystemPrompt(context: Context, currentQuery: String): String {
        val profile         = ConsultationProfile.load()
        val ledger          = BehaviorLedger.getSummaryForPlanner()
        val knowledgeBlocks = MentorKnowledge.getContextForQuery(currentQuery)

        // Mentor v2 (spec 3.1): previously Mentor only saw the 7-day aggregate ledger string —
        // blind to today's actual task list, custom tasks, live app usage, same-day free-text
        // updates, and upcoming coaching tests. These four blocks close that gap.
        val todayTasks = PlanStore.getTodayTasksSnapshot_Sync()
        val planSummary = if (todayTasks.isEmpty()) "No plan generated for today yet." else {
            todayTasks.joinToString("\n") { t ->
                val marker = when (t.state) {
                    TaskState.DONE    -> "[DONE]"
                    TaskState.SKIPPED -> "[SKIPPED]"
                    TaskState.ACTIVE  -> "[ACTIVE]"
                    TaskState.PAUSED  -> "[PAUSED]"
                    TaskState.PENDING -> "[PENDING]"
                }
                val custom = if (t.isCustom) " (custom)" else ""
                "$marker ${t.subject}: ${t.topic} — ${t.durationMinutes}min, ${t.taskType}$custom"
            }
        }

        val usageSummary = try {
            AppUsageTracker.getTodayUsage(context, limit = 5)
                .joinToString("\n") { "  ${it.label}: ${AppUsageTracker.formatDuration(it.foregroundMillis)}" }
        } catch (_: Exception) { "" }

        val todayContext = TodayContext.getSummaryText()
        val coachingContext = try { CoachingPlannerEntry.upcomingContext(3) } catch (_: Exception) { "" }

        // Upgrade Blueprint Phase 2 wiring: StudentModelBuilder.build() aggregates
        // Mastery/Error/Retention/KnowledgeGraph output (computed on every report.md
        // import — see TestResultNormalizer) into one StudentModel, but nothing read
        // it — Mentor was answering "how am I doing" purely from the 7-day behavior
        // ledger, blind to actual test performance. buildLearningSummary() below
        // compacts it into the same plain-text block style as planSummary/ledger.
        val learningSummary = try {
            buildLearningSummary(StudentModelBuilder.build(context))
        } catch (e: Exception) {
            "" // same fail-open pattern as usageSummary/coachingContext above — a
               // learning-state read failure shouldn't block the rest of the prompt.
        }

        return buildString {
            appendLine("""
You are a strict but smart study mentor for a ${profile.examTarget} aspirant.
You have full context about this student. Be specific, direct, and curriculum-aware.
Keep responses under 5 lines unless a detailed breakdown is needed.
Never give generic motivation. React to actual data.
Refer to specific topics, chapters, marks gaps, and deadlines.
You also have access to the full conversation history above — refer to it naturally when relevant.
If TODAY'S PLAN shows pending/skipped tasks and the student is asking something unrelated,
you may briefly flag it — but don't derail the actual question they asked.
If LEARNING STATE shows weak concepts, review-due topics, or recurring errors relevant to
the student's question, reference them specifically instead of speaking generically. When a
weak concept lists a prerequisite issue, name the specific prerequisite topic (e.g. "your
Rolling Motion mistakes trace back to Laws of Motion, which is still at 38%") rather than
saying only that a prerequisite gap exists.
            """.trimIndent())
            appendLine()
            appendLine("STUDENT PROFILE:")
            appendLine(profile.toPromptContext())
            appendLine()
            appendLine("BEHAVIOR DATA: $ledger")
            appendLine()
            appendLine("TODAY'S PLAN:")
            appendLine(planSummary)
            appendLine()
            if (learningSummary.isNotBlank()) {
                appendLine("LEARNING STATE (from imported test reports — mastery/errors/retention):")
                appendLine(learningSummary)
                appendLine()
            }
            if (todayContext.isNotBlank()) {
                appendLine("TODAY'S LOGGED UPDATES:")
                appendLine(todayContext)
                appendLine()
            }
            if (usageSummary.isNotBlank()) {
                appendLine("TODAY'S APP USAGE (top 5):")
                appendLine(usageSummary)
                appendLine()
            }
            if (coachingContext.isNotBlank()) {
                appendLine("UPCOMING COACHING TESTS/LECTURES (next 3 days):")
                appendLine(coachingContext)
                appendLine()
            }
            if (knowledgeBlocks.isNotBlank()) {
                appendLine("RELEVANT KNOWLEDGE:")
                appendLine(knowledgeBlocks)
                appendLine()
            }
            appendLine("Respond as Mentor.")
        }.trim()
    }

    /**
     * Upgrade Blueprint Phase 2 wiring: compacts a [StudentModel] into a short
     * plain-text block matching the existing planSummary/ledger style — Mentor's
     * prompt is plain text throughout, unlike AdaptivePlanner's JSON-to-LLM
     * convention, so this deliberately doesn't reuse AdaptivePlanner's
     * StudentModelPromptSummary/Json.encodeToString approach.
     *
     * Empty-guard: returns "" when no report has ever been imported
     * (conceptsTracked == 0) — same pattern as the other optional context blocks
     * above (usageSummary/coachingContext) — so a student who hasn't imported a
     * mock yet doesn't get a misleading "0 concepts tracked" section.
     */
    private fun buildLearningSummary(model: StudentModel): String {
        if (model.overall.conceptsTracked == 0) return ""

        val overall = model.overall
        val weakest = model.concepts.values.sortedBy { it.mastery }.take(5)
        val reviewDue = model.concepts.values
            .filter { it.retentionDecision == RetentionDecisionSnapshot.REVIEW }
            .sortedByDescending { it.forgettingRisk }
            .take(5)
        val topErrors = model.unresolvedErrors.take(5)

        return buildString {
            appendLine(
                "Overall: ${overall.conceptsMastered}/${overall.conceptsTracked} concepts mastered, " +
                    "avg mastery ${(overall.averageMastery * 100).toInt()}%, " +
                    "${overall.unresolvedErrorCount} unresolved error occurrence(s)"
            )
            if (weakest.isNotEmpty()) {
                appendLine("Weakest concepts:")
                weakest.forEach { c ->
                    val label = c.topic ?: c.chapter ?: "unknown"
                    // Upgrade Blueprint Phase 2 wiring: name the specific weak
                    // prerequisite(s) and, when known, their own mastery — see
                    // ConceptSnapshot.prerequisiteIssues' CORRECTNESS FIX note.
                    val prereqNote = if (c.prerequisiteIssues.isNotEmpty()) {
                        val names = c.prerequisiteIssues.joinToString { ref ->
                            val prereqLabel = ref.topic ?: ref.chapter ?: ref.subject ?: ref.conceptId
                            val prereqMastery = model.concepts[ref.conceptId]?.mastery
                            if (prereqMastery != null) "$prereqLabel (${(prereqMastery * 100).toInt()}%)"
                            else "$prereqLabel (not yet attempted)"
                        }
                        " — traces to weak prerequisite(s): $names"
                    } else ""
                    appendLine("  ${c.subject ?: "?"}/${c.chapter ?: "?"}/$label — ${(c.mastery * 100).toInt()}% mastery$prereqNote")
                }
            }
            if (reviewDue.isNotEmpty()) {
                appendLine("Due for review (forgetting risk rising):")
                reviewDue.forEach { c ->
                    val label = c.topic ?: c.chapter ?: "unknown"
                    appendLine("  ${c.subject ?: "?"}/${c.chapter ?: "?"}/$label")
                }
            }
            if (topErrors.isNotEmpty()) {
                appendLine("Recurring errors:")
                topErrors.forEach { e ->
                    val concept = model.concepts[e.conceptId]
                    val label = concept?.topic ?: concept?.chapter ?: e.conceptId
                    appendLine("  $label — ${e.errorType} ×${e.occurrences}")
                }
            }
        }.trim()
    }

    companion object {
        /**
         * Mentor v2 (spec 3.2): lets code outside a MentorViewModel instance (HomeViewModel on
         * skip, DistractionGuard's listener on threshold, ReminderService's idle check) write
         * directly into the same persisted chat history MentorViewModel reads on init — turning
         * Mentor chat into a running log the mentor writes into, not only a box the user opens.
         * Uses the same PREFS_KEY_HISTORY / historyJson / MAX_PERSISTED_MESSAGES as the instance
         * methods above so a message appended here shows up next time MentorScreen is opened.
         *
         * Also pushes the update via StudyStateSyncManager. These callers (HomeViewModel button
         * handlers, DistractionGuard's listener, ReminderService) aren't guaranteed to already be
         * on a background thread the way the ViewModel's own viewModelScope.launch(Dispatchers.IO)
         * calls are, so the push is wrapped in its own short-lived Thread here rather than assumed
         * safe to run inline — StudyStateSyncManager's synchronous OkHttp calls must not run on
         * the main thread.
         */
        fun appendProactiveMessage(text: String) {
            val clean = text.trim()
            if (clean.isEmpty()) return
            val saved = CheckmatePrefs.getString(PREFS_KEY_HISTORY, null)
            val existing = if (saved != null) {
                try { historyJson.decodeFromString<List<MentorMessage>>(saved) }
                catch (_: Exception) { emptyList() }
            } else emptyList()
            val updated = (existing + MentorMessage("assistant", clean)).takeLast(MAX_PERSISTED_MESSAGES)
            val json = historyJson.encodeToString(updated)
            CheckmatePrefs.putString(PREFS_KEY_HISTORY, json)
            Thread { StudyStateSyncManager.pushMentorMessage(json) }.start()
        }
    }
}
