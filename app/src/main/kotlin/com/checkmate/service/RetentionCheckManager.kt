package com.checkmate.service

import android.content.Context
import android.util.Log
import com.checkmate.core.ConsultationProfile
import com.checkmate.planner.intervention.RetentionTaskLedger
import com.checkmate.testmate.TestmateApi
import com.checkmate.testmate.TestmateQuestionPool
import com.checkmate.testmate.TestmateResultOutcome
import com.checkmate.testmate.TestmateTargetedTestOutcome

/**
 * next-session-retention-loop.txt — the RETENTION CHECK execution/evidence path. Mirrors
 * [GapTaskManager]'s P0b Testmate session/evidence functions exactly (same
 * [TestmateApi.createTargetedTest] -> [TestmateApi.fetchResult] -> [TargetedTestEvidenceImporter]
 * pipeline, reused rather than duplicated), but driven off
 * [com.checkmate.planner.intervention.RetentionTaskLedger] instead of
 * [com.checkmate.planner.intervention.GapTaskLedger] — see that ledger's own class doc for why
 * retention checks get a separate, per-task ledger instead of joining GapTaskLedger's
 * single-active-concept slot.
 *
 * Both entry points are called from [ReminderService]'s existing 15-min loop, same wiring
 * pattern as every [GapTaskManager] function already there. Neither is gated to once/day —
 * [createRetentionTestsIfNeeded] should fire the moment a RETENTION CHECK task exists with no
 * session yet, and [evidencePollIfNeeded] should pick up a submitted result as soon as it's
 * there, same reasoning as [GapTaskManager.createTargetedTestIfNeeded]/`evidencePollIfNeeded`.
 *
 * Deliberately does NOT push a new retention decision anywhere itself — see
 * [RetentionTaskLedger]'s own doc for why that isn't needed: [MasteryEngine.recomputeAll]
 * (called inside [TargetedTestEvidenceImporter.import]) already refreshes `lastSeen`/mastery
 * for the concept, and [com.checkmate.learning.engine.RetentionEngine.decide] re-reads that on
 * its own next pass through [com.checkmate.learning.engine.LearningDecisionEngine.decideFromReport]
 * (i.e. [GapTaskManager.generateIfNeeded]'s next daily run). RetentionEngine's 14-day decay and
 * REVIEW/TEACH/MOVE_ON thresholds are untouched — this file only closes the evidence loop
 * around it, per next-session-retention-loop.txt's explicit constraint.
 */
object RetentionCheckManager {

    private const val TAG = "RetentionCheckManager"

    // A retention check is a short recall probe, not a full repair set — sized to match
    // LearningDecisionEngine.RETENTION_TEST_MINUTES (10 min), the StudyTask duration a
    // SCHEDULE_RETENTION_TEST candidate already carries.
    private const val RETENTION_QUESTION_COUNT = 10

    // HONEST GAP (flagged, not silently worked around): TestmateQuestionPool only offers
    // WRONG / SKIPPED / WRONG_SKIPPED / NEW — there is no "previously answered correctly,
    // retest for recall" pool, which is what a genuine retention probe should draw from.
    // WRONG_SKIPPED is the closest available option and is what this uses today, but it
    // means a "retention check" and a gap-repair retest currently pull from the same
    // question pool on the Testmate side — testing what the student got wrong before, not
    // whether they still remember what they'd already gotten right. Properly closing this
    // needs a MASTERED/CORRECT pool added to Testmate's own `/api/tests/targeted` endpoint
    // (testmate2.vercel.app) — that's a Testmate-side change, out of scope for this
    // Checkmate-client-only pass, and NOT something to route around with client-side
    // filtering of Testmate's response (Testmate selects the question set server-side).
    private val RETENTION_POOL = TestmateQuestionPool.WRONG_SKIPPED

    /**
     * For every RETENTION CHECK task that doesn't have a Testmate session yet, requests one.
     * `intervention_id = retention-<taskId>` is already unique per task (every retention
     * task gets its own taskId from [LearningInterventionOrchestrator]), so — unlike
     * [GapTaskManager]'s P0b round counter — there's no round-suffix bookkeeping needed here;
     * a retry of an already-created session is naturally idempotent via Testmate's own
     * intervention_id de-dupe (see [TestmateApi.createTargetedTest]'s own doc).
     */
    suspend fun createRetentionTestsIfNeeded() {
        val pending = RetentionTaskLedger.pendingSessionCreation()
        for (session in pending) {
            val chapter = session.chapter?.takeIf { it.isNotBlank() } ?: run {
                Log.w(TAG, "createRetentionTestsIfNeeded: taskId=${session.taskId} has no chapter recorded, skipping")
                continue
            }
            // Same "only forward topic when it's genuinely distinct from chapter" rule
            // GapTaskManager.createTargetedTestIfNeeded applies, for the same reason:
            // GapTaskLedger/RetentionTaskLedger both fall back to the raw chapter string as
            // their "no real topic" sentinel.
            val topic = session.topic?.takeIf { it != chapter }

            val interventionId = "retention-${session.taskId}"
            val outcome = try {
                TestmateApi.createTargetedTest(
                    interventionId = interventionId,
                    chapter = chapter,
                    topic = topic,
                    questionCount = RETENTION_QUESTION_COUNT,
                    pool = RETENTION_POOL
                )
            } catch (e: Exception) {
                Log.e(TAG, "createTargetedTest threw for taskId=${session.taskId}: ${e.message}", e)
                continue
            }

            when (outcome) {
                is TestmateTargetedTestOutcome.Success -> {
                    RetentionTaskLedger.recordTestmateSession(
                        session.taskId, outcome.test.testId, outcome.test.sessionId
                    )
                    Log.d(TAG, "retention test ready: taskId=${session.taskId} session=${outcome.test.sessionId}")
                }
                is TestmateTargetedTestOutcome.Error -> {
                    Log.w(TAG, "createTargetedTest error for taskId=${session.taskId}: ${outcome.message}")
                }
            }
        }
    }

    /**
     * Every 15-min cycle: for every RETENTION CHECK task with a Testmate session whose
     * result hasn't been imported yet, fetches it and — once the student has actually
     * submitted (an [TestmateResultOutcome.Error], including Testmate's "no result yet" 404,
     * just means try again next cycle) — hands the per-question breakdown to
     * [TargetedTestEvidenceImporter], the exact same import path
     * [GapTaskManager.evidencePollIfNeeded] already uses for gap-repair retests. This is
     * the actual "student answers questions -> Testmate result -> QuestionAttempt +
     * LearningEvent -> MasteryEngine" arrow the retention loop was missing.
     */
    suspend fun evidencePollIfNeeded(context: Context) {
        val pending = RetentionTaskLedger.pendingEvidence()
        for (session in pending) {
            val sessionId = session.testmateSessionId ?: continue

            val outcome = try {
                TestmateApi.fetchResult(sessionId)
            } catch (e: Exception) {
                Log.w(TAG, "evidencePollIfNeeded fetch threw for taskId=${session.taskId}: ${e.message}")
                continue
            }
            val result = when (outcome) {
                is TestmateResultOutcome.Success -> outcome.result
                is TestmateResultOutcome.Error -> continue // not submitted yet (or a real failure) — retry next cycle
            }
            if (result.breakdown.isEmpty()) continue // submitted but no per-question data yet

            try {
                val exam = ConsultationProfile.load().examTarget
                TargetedTestEvidenceImporter.import(
                    context = context,
                    sessionId = sessionId,
                    exam = exam,
                    chapter = session.chapter,
                    topic = session.topic,
                    result = result,
                    // Distinct source tag from gap-repair's "testmate_targeted" so a
                    // LearningEvent/Question row's provenance is honest about which loop
                    // produced it, without needing a schema change to either model.
                    source = "testmate_retention"
                )
                RetentionTaskLedger.markEvidenceImported(session.taskId)
                Log.d(TAG, "retention evidence imported: taskId=${session.taskId} session=$sessionId")
            } catch (e: Exception) {
                Log.e(TAG, "retention evidence import failed for taskId=${session.taskId}: ${e.message}", e)
            }
        }
    }
}
