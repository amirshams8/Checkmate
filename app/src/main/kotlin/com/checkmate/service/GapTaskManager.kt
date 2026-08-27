package com.checkmate.service

import android.content.Context
import android.util.Log
import com.checkmate.core.ConsultationProfile
import com.checkmate.core.llm.LlmGateway
import com.checkmate.learning.analytics.PerformanceAnalyzer
import com.checkmate.learning.analytics.ScoreGainEstimator
import com.checkmate.learning.analytics.ScorePredictor
import com.checkmate.learning.engine.LearningDecisionEngine
import com.checkmate.learning.student.StudentModelBuilder
import com.checkmate.planner.PlanStore
import com.checkmate.planner.intervention.GapTaskLedger
import com.checkmate.planner.intervention.LearningInterventionOrchestrator
import com.checkmate.planner.model.StudyTask
import com.checkmate.planner.model.TaskState
import com.checkmate.psyche.BehaviorLedger
import com.checkmate.testmate.TestmateApi
import com.checkmate.testmate.TestmateQuestionPool
import com.checkmate.testmate.TestmateResultOutcome
import com.checkmate.testmate.TestmateTargetedTestOutcome
import com.checkmate.ui.mentor.MentorViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * "One gap-repair task a day, cycling through every gap the test surfaced, until each one
 * is actually done — and warn the student, with escalating persuasion, when one is being
 * ignored." Three entry points now, all called from [ReminderService]'s existing 15-min
 * loop (same wiring pattern as every [ProactiveMentor] check already there):
 *
 * - [generateIfNeeded] — the daily trigger [LearningInterventionOrchestrator]'s own class
 *   doc always said was still missing, now filled by a genuine day-level schedule instead
 *   of only firing on fresh test import ([com.checkmate.ui.testresults.TestResultsViewModel]
 *   still owns that import-time trigger; this is the *ongoing* one). Also now requests the
 *   P0b Testmate targeted test for whichever concept ends up active (see
 *   [createTargetedTestIfNeeded]).
 * - [escalationCheckIfNeeded] — reads [GapTaskLedger]'s streak for whichever concept is
 *   currently being served and, once it's gone unaddressed for more than a day, sends an
 *   escalating warning via the SAME delivery path [ProactiveMentor] already uses
 *   ([MentorViewModel.appendProactiveMessage] + [MentorNotifier.notify]).
 * - [evidencePollIfNeeded] — P0b: the actual return arrow. Every 15-min cycle (NOT
 *   once-a-day gated, unlike the two above — the student could submit the Testmate test at
 *   any time), checks whether the active concept's targeted-test result is in yet and, if
 *   so, hands it to [TargetedTestEvidenceImporter] so mastery moves off REAL evidence
 *   instead of only the task's DONE flag.
 *
 * Neither [generateIfNeeded] nor [escalationCheckIfNeeded] needs its own "is there anything
 * to analyze" gate beyond checking `studentModel.concepts.isEmpty()` — an empty StudentModel
 * (no test ever imported) just means [LearningDecisionEngine.decideFromReport] returns no
 * candidates and nothing happens, same as the import-time trigger's own behavior.
 */
object GapTaskManager {

    private const val TAG = "GapTaskManager"
    private const val TARGETED_TEST_QUESTION_COUNT = 15

    // ── Daily generation ─────────────────────────────────────────────────────

    /**
     * Once per calendar day (guarded by [GapTaskLedger.hasGeneratedToday]): resolves whether
     * yesterday's gap-task actually got finished (see [resolveActiveConceptState]), then runs
     * the exact same StudentModel -> PerformanceReport -> ScoreGainEstimator -> ExpectedScore
     * -> DecisionReport pipeline [com.checkmate.ui.testresults.TestResultsViewModel] runs
     * after a fresh import, sourcing `examType`/`targetScore` from [ConsultationProfile]
     * instead of a just-parsed report since there may be no fresh import today at all.
     * [LearningInterventionOrchestrator] itself handles skipping already-covered concepts
     * and picking up where yesterday left off. Once that's done, also requests (or confirms)
     * the P0b Testmate targeted test for whichever concept is now active — see
     * [createTargetedTestIfNeeded]. Marks today done, success or failure either way (a failed
     * analysis run shouldn't retry every 15 minutes for the rest of the day).
     */
    suspend fun generateIfNeeded(context: Context) {
        val todayKey = GapTaskLedger.todayKey()
        if (GapTaskLedger.hasGeneratedToday(todayKey)) return

        resolveActiveConceptState()

        try {
            val studentModel = withContext(Dispatchers.IO) { StudentModelBuilder.build(context) }
            if (studentModel.concepts.isEmpty()) return

            val profile = ConsultationProfile.load()
            val report = PerformanceAnalyzer.analyze(studentModel, profile.examTarget)
            val estimates = ScoreGainEstimator.rankFromReport(report, studentModel)
            val expectedScore = ScorePredictor.predictFromReport(report, studentModel, profile.targetScore)
            val decisionReport = LearningDecisionEngine.decideFromReport(
                report, studentModel, estimates, expectedScore
            )
            LearningInterventionOrchestrator.from(context).executeTopCandidate(decisionReport)
            createTargetedTestIfNeeded()
        } catch (e: Exception) {
            Log.e(TAG, "generateIfNeeded failed: ${e.message}", e)
        } finally {
            GapTaskLedger.markGeneratedToday(todayKey)
        }
    }

    /**
     * If [GapTaskLedger]'s active concept's task has actually reached DONE, marks it covered
     * BEFORE re-ranking — otherwise today's run would still see it as active (streak intact)
     * for the few seconds between "the student finished it" and "the ledger found out," and
     * [LearningInterventionOrchestrator] would have no way to know without this being called
     * first. Safe to call with nothing active (both lookups return null and this no-ops).
     */
    private fun resolveActiveConceptState() {
        val conceptId = GapTaskLedger.activeConceptId() ?: return
        val taskId = GapTaskLedger.activeTaskId() ?: return
        val dayKey = GapTaskLedger.activeTaskDayKey() ?: return
        val task = findTask(taskId, dayKey) ?: return
        if (task.state == TaskState.DONE) {
            GapTaskLedger.markCovered(conceptId)
        }
    }

    /** Checks today's live list first (cheap, common case), falls back to [PlanStore.loadDay]
     *  for a task created on an earlier day that hasn't rolled into today's plan. */
    private fun findTask(taskId: String, dayKey: String): StudyTask? =
        PlanStore.todayTasks.value.find { it.id == taskId }
            ?: PlanStore.loadDay(dayKey).find { it.id == taskId }

    // ── P0b: Testmate targeted-test creation ────────────────────────────────

    /**
     * Requests a Testmate targeted-repair test for the currently active gap concept, but
     * only the FIRST time — [GapTaskLedger.activeTestmateSessionId] being non-null already
     * means either this call already succeeded for this concept, or the concept is being
     * re-served after [GapTaskLedger.recordServed] deliberately preserved that session
     * across days (see its own doc). No-ops with nothing active, or with no
     * [GapTaskLedger.activeChapter] to target (shouldn't happen for a real
     * [LearningDecisionEngine.CandidateIntervention], but this stays defensive rather than
     * crashing the whole generation pass over it). A failed request is NOT retried until the
     * next [generateIfNeeded] run (i.e. tomorrow) — [evidencePollIfNeeded] only polls a
     * session that was actually recorded, so a create failure just means one day's targeted
     * test is missing, not a retry storm.
     */
    private suspend fun createTargetedTestIfNeeded() {
        val conceptId = GapTaskLedger.activeConceptId() ?: return
        if (GapTaskLedger.activeTestmateSessionId() != null) return
        val chapter = GapTaskLedger.activeChapter() ?: run {
            Log.w(TAG, "createTargetedTestIfNeeded: no chapter recorded for concept=$conceptId, skipping")
            return
        }
        val topic = GapTaskLedger.activeTopic()

        val outcome = try {
            TestmateApi.createTargetedTest(
                interventionId = conceptId,
                chapter = chapter,
                topic = topic,
                questionCount = TARGETED_TEST_QUESTION_COUNT,
                pool = TestmateQuestionPool.WRONG_SKIPPED
            )
        } catch (e: Exception) {
            Log.e(TAG, "createTargetedTest threw: ${e.message}", e)
            return
        }

        when (outcome) {
            is TestmateTargetedTestOutcome.Success -> {
                GapTaskLedger.recordTestmateSession(outcome.test.testId, outcome.test.sessionId)
                Log.d(TAG, "targeted test ready: concept=$conceptId session=${outcome.test.sessionId}")
            }
            is TestmateTargetedTestOutcome.Error -> {
                Log.w(TAG, "createTargetedTest error for concept=$conceptId: ${outcome.message}")
            }
        }
    }

    // ── P0b: evidence import ─────────────────────────────────────────────────

    /**
     * Every 15-min cycle: if the active concept has a Testmate session ([createTargetedTestIfNeeded]
     * already ran) whose evidence hasn't been imported yet, fetches the result and, once it's
     * actually there (the student has submitted — an [TestmateResultOutcome.Error], including
     * Testmate's "no result yet" 404, just means try again next cycle), hands the per-question
     * breakdown to [TargetedTestEvidenceImporter]. This is deliberately NOT gated to once/day
     * like [generateIfNeeded]/[escalationCheckIfNeeded] — the student can submit the test at any
     * time, and the whole point of P0b is that mastery reflects that as soon as it's known, not
     * up to 24 hours later.
     */
    suspend fun evidencePollIfNeeded(context: Context) {
        if (GapTaskLedger.isActiveEvidenceImported()) return
        val sessionId = GapTaskLedger.activeTestmateSessionId() ?: return

        val outcome = try {
            TestmateApi.fetchResult(sessionId)
        } catch (e: Exception) {
            Log.w(TAG, "evidencePollIfNeeded fetch threw: ${e.message}")
            return
        }
        val result = when (outcome) {
            is TestmateResultOutcome.Success -> outcome.result
            is TestmateResultOutcome.Error -> return // not submitted yet (or a real failure either way) — retry next cycle
        }
        if (result.breakdown.isEmpty()) return // submitted but no per-question data yet — treat like not-ready

        try {
            val exam = ConsultationProfile.load().examTarget
            val chapter = GapTaskLedger.activeChapter()
            val topic = GapTaskLedger.activeTopic()
            TargetedTestEvidenceImporter.import(
                context = context,
                sessionId = sessionId,
                exam = exam,
                chapter = chapter,
                topic = topic,
                result = result
            )
            GapTaskLedger.markActiveEvidenceImported()
        } catch (e: Exception) {
            Log.e(TAG, "evidence import failed: ${e.message}", e)
        }
    }

    // ── Escalating skip/stall warning ───────────────────────────────────────

    private val TIER_1_SYSTEM_PROMPT = """
You are a strict but adaptive study coach warning a student about ONE specific unfinished
task, not their whole day. Rules:
- 1-3 sentences, direct and consequence-based, no generic praise or "you can do it"
- Name the specific concept and why it matters (marks at stake), not vague encouragement
- Tone: firm reminder, not yet an ultimatum
""".trimIndent()

    private val TIER_2_SYSTEM_PROMPT = """
You are a strict but adaptive study coach. This specific gap has been ignored for several
days running — this is the escalated warning, not the first nudge. Rules:
- 3-5 sentences, still no fluff, but now make the full case
- State plainly how many days this has been unaddressed
- Connect this ONE concept to the real exam-day consequence (marks lost, gap to target score)
- Reference the student's own skip pattern if given one, as evidence this is a pattern, not a one-off
- End with a direct, specific ask — not "you should study" but "do this today"
""".trimIndent()

    /**
     * Once per calendar day (guarded by [GapTaskLedger.hasEscalatedToday]). Depth scales with
     * [GapTaskLedger.activeDaysServed]: day 1 gets no escalation at all (the task just showed
     * up today — that's the normal experience, not a warning), day 2-3 names the concrete
     * consequence, day 4+ is the full persuasive case tying this one concept to the exam-day
     * outcome and the student's own skip pattern (see [BehaviorLedger.getSnapshot]). Skips
     * entirely while the task is ACTIVE/PAUSED (the student is mid-session on it right now —
     * interrupting with a warning would be counterproductive) and clears/covers it instead of
     * warning if it turns out to already be DONE (belt-and-suspenders alongside
     * [resolveActiveConceptState], which normally catches this first).
     */
    suspend fun escalationCheckIfNeeded(context: Context) {
        val todayKey = GapTaskLedger.todayKey()
        if (GapTaskLedger.hasEscalatedToday(todayKey)) return

        val daysServed = GapTaskLedger.activeDaysServed()
        if (daysServed < 2) return // day 1: normal experience, no warning

        val conceptId = GapTaskLedger.activeConceptId() ?: return
        val taskId = GapTaskLedger.activeTaskId() ?: return
        val dayKey = GapTaskLedger.activeTaskDayKey() ?: return
        val task = findTask(taskId, dayKey)

        if (task == null || task.state == TaskState.DONE) {
            if (task?.state == TaskState.DONE) GapTaskLedger.markCovered(conceptId)
            GapTaskLedger.markEscalatedToday(todayKey)
            return
        }
        if (task.state != TaskState.PENDING && task.state != TaskState.SKIPPED) {
            return // ACTIVE/PAUSED right now — don't interrupt an in-progress session
        }

        val tier = if (daysServed >= 4) 2 else 1
        val message = try {
            buildEscalationMessage(tier, daysServed)
        } catch (e: Exception) {
            Log.e(TAG, "escalation message build failed: ${e.message}", e)
            GapTaskLedger.markEscalatedToday(todayKey)
            return
        }

        MentorViewModel.appendProactiveMessage(message)
        MentorNotifier.notify(context, message)
        GapTaskLedger.logWarningSent(conceptId, todayKey, daysServed, tier)
        GapTaskLedger.markEscalatedToday(todayKey)
        Log.d(TAG, "escalation sent: tier=$tier daysServed=$daysServed concept=$conceptId")
    }

    private suspend fun buildEscalationMessage(tier: Int, daysServed: Int): String {
        val subject = GapTaskLedger.activeSubject()?.takeIf { it.isNotBlank() } ?: "this subject"
        val topic = GapTaskLedger.activeTopic()?.takeIf { it.isNotBlank() } ?: subject
        val rationale = GapTaskLedger.activeRationale()?.takeIf { it.isNotBlank() }
            ?: "$topic still needs work."
        val marksAtStake = GapTaskLedger.activeExpectedGain()

        val patternLine = BehaviorLedger.getSnapshot().subjectPatterns
            .filter { it.subject.equals(subject, ignoreCase = true) }
            .maxByOrNull { it.occurrences }
            ?.let { "You've skipped $subject ${it.taskType} tasks ${it.occurrences} times recently — this fits the pattern." }
            ?: ""

        val ruleMsg = when (tier) {
            2 -> "Day $daysServed unaddressed: $topic. $rationale $patternLine " +
                "This gap alone is worth roughly ${"%.1f".format(marksAtStake)} marks — " +
                "left alone, it stays a hole on exam day. Do it today."
            else -> "$topic has gone $daysServed days without being done. $rationale Do it today."
        }

        val systemPrompt = if (tier == 2) TIER_2_SYSTEM_PROMPT else TIER_1_SYSTEM_PROMPT
        val userPrompt = "Concept: $topic ($subject). Days unaddressed: $daysServed. " +
            "Why it matters: $rationale Approx marks at stake: ${"%.1f".format(marksAtStake)}. " +
            "$patternLine Write the warning message now."

        return try {
            val llmMsg = LlmGateway.complete(userPrompt, systemPrompt)
            if (llmMsg.isBlank()) ruleMsg else llmMsg
        } catch (_: Exception) {
            ruleMsg
        }
    }
}
