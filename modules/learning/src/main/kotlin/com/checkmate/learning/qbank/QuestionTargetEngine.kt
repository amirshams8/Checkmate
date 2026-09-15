package com.checkmate.learning.qbank

import android.content.Context
import android.util.Log
import com.checkmate.learning.model.DailyQuestionTarget
import com.checkmate.learning.model.LearningIds
import com.checkmate.learning.model.TestPlan
import com.checkmate.learning.repository.LearningDatabase
import java.util.Calendar
import kotlin.math.roundToInt

/**
 * P0 Q-bank MVP (see chat history: "Test on the 23rd" / daily-target design thread).
 *
 * Responsibility is ONLY: TestPlan + StudentModel (mastery) + today -> a persisted
 * [DailyQuestionTarget]. Deliberately does NOT select actual Question rows — that's
 * [QBankSelector]'s job, same separation of concerns the chat history settled on.
 *
 * Computed once per calendar day and persisted (see DailyQuestionTarget's own doc
 * for why), gated by [com.checkmate.learning.repository.DailyQuestionTargetDao.getByDay]
 * rather than a separate "already ran today" flag — the row itself IS the gate.
 *
 * FORMULA (see chat history for the full derivation):
 *   effectiveDays = daysRemaining - FINAL_REVIEW_DAYS, floored at 1
 *   baseTarget    = totalRemainingQuestions / effectiveDays
 *   perChapter    = baseTarget * (chapter's readinessGap / sum of all readinessGaps)
 *
 * Readiness gap REDISTRIBUTES baseTarget across chapters — it is not an independent
 * per-chapter multiplier. Multiplying each chapter's own remaining/days figure by
 * its own weakness multiplier doesn't sum back to baseTarget, which is what actually
 * matters for "43 Q/day total" (see chat history's worked Physics/Chemistry/Biology
 * example).
 *
 * HONEST GAP: "not recently practiced" and "prerequisite weakness" from the original
 * question-selection priority list are QBankSelector's concern (per-question), not
 * this engine's (per-chapter allocation) — folding them in here would double-count
 * against readinessGap without a principled combination rule. Revisit if the P0
 * allocation turns out too coarse in practice.
 *
 * TESTING NOTE: like MasteryEngine/ErrorEngine/RetentionEngine/StudentModelBuilder,
 * this function touches LearningDatabase.getInstance(context) — the process-wide
 * on-disk singleton — directly, so it is not covered by an instrumented test here
 * (doing so would pollute real app data, same reasoning StudentModelBuilder's own
 * class doc gives). QBankProvenanceTest instead covers the DAO-level query/
 * TypeConverter behavior this engine depends on, against an isolated in-memory db.
 */
object QuestionTargetEngine {
    private const val TAG = "QuestionTargetEngine"

    /**
     * Days immediately before the exam reserved for revision/retention rather than
     * new material — see chat history: "reserve the final day(s) for revision/mock/
     * recovery," matching common exam-planner practice rather than pushing new
     * questions all the way to exam day. Tunable; not derived from anything.
     */
    private const val FINAL_REVIEW_DAYS = 2
    private const val MIN_EFFECTIVE_DAYS = 1

    private fun todayKey(): String {
        // Same "YYYY_dayOfYear" format GapTaskLedger/PlanStore already use for
        // day-keyed state — see GapTaskLedger.todayKey's own comment. Duplicated
        // rather than shared because that function is private to GapTaskLedger and
        // this module has no existing shared day-key utility to call into instead.
        val cal = Calendar.getInstance()
        return "${cal.get(Calendar.YEAR)}_${cal.get(Calendar.DAY_OF_YEAR)}"
    }

    private fun daysUntil(examDateMillis: Long): Long {
        fun midnight(millis: Long) = Calendar.getInstance().apply {
            timeInMillis = millis
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val today = midnight(System.currentTimeMillis())
        val exam = midnight(examDateMillis)
        return (exam - today) / (24L * 60 * 60 * 1000)
    }

    /**
     * Returns today's target for [testPlan], computing and persisting it if this is
     * the first call today for this test plan. Returns a zero-allocation target
     * (never null) for "exam already passed," "final review window," and "every
     * chapter already fully covered" — callers (QBankSelector) treat an empty
     * chapterAllocations map as "nothing to serve today," which is correct for all
     * three cases without needing to distinguish them.
     */
    suspend fun getOrComputeTodayTarget(context: Context, testPlan: TestPlan): DailyQuestionTarget {
        val dayKey = todayKey()
        val db = LearningDatabase.getInstance(context)
        db.dailyQuestionTargetDao().getByDay(dayKey, testPlan.id)?.let { return it }

        val daysRemaining = daysUntil(testPlan.examDateMillis)
        if (daysRemaining < 0 || daysRemaining <= FINAL_REVIEW_DAYS) {
            Log.d(TAG, "getOrComputeTodayTarget: testPlan=${testPlan.id} daysRemaining=$daysRemaining " +
                "— exam passed or inside final-review window (<=$FINAL_REVIEW_DAYS days), zero new-question target")
            return persistZero(db, dayKey, testPlan.id)
        }

        val effectiveDays = maxOf(MIN_EFFECTIVE_DAYS, (daysRemaining - FINAL_REVIEW_DAYS).toInt())
        val studentId = LearningIds.LOCAL_STUDENT_ID
        val questionDao = db.questionDao()
        val conceptDao = db.conceptDao()
        val masteryDao = db.masteryDao()

        data class ChapterState(val chapter: String, val remaining: Int, val readinessGap: Double)

        val states = testPlan.chapterTargets.mapNotNull { (chapter, target) ->
            if (target <= 0) return@mapNotNull null
            val alreadyCovered = questionDao.countQbankCorrectByChapter(chapter, studentId)
            val remaining = (target - alreadyCovered).coerceAtLeast(0)
            if (remaining == 0) return@mapNotNull null

            // No mastery row yet (never attempted) is treated as MAXIMALLY weak
            // (readinessGap = 1.0), not skipped or averaged toward zero — "no data"
            // is not "no risk." Chapters with zero qbank rows built yet (e.g. Biology
            // for now — see chat history) still get an allocation here; QBankSelector
            // is what actually has nothing to serve for them, and does so silently.
            val concepts = conceptDao.getByExamAndChapter(testPlan.exam, chapter)
            val masteries = concepts.mapNotNull { masteryDao.getByConcept(studentId, it.id) }
            val avgMastery = if (masteries.isEmpty()) 0.0 else masteries.map { it.mastery }.average()
            val readinessGap = (1.0 - avgMastery).coerceIn(0.0, 1.0)

            ChapterState(chapter, remaining, readinessGap)
        }

        if (states.isEmpty()) {
            Log.d(TAG, "getOrComputeTodayTarget: testPlan=${testPlan.id} — every chapter already fully covered")
            return persistZero(db, dayKey, testPlan.id)
        }

        val totalRemaining = states.sumOf { it.remaining }
        val baseTarget = totalRemaining.toDouble() / effectiveDays

        // If every chapter has identical readinessGap (including the common "no
        // mastery data anywhere yet" case where all gaps are 1.0), split evenly
        // rather than dividing by a zero/degenerate weight sum.
        val totalGapWeight = states.sumOf { it.readinessGap }
        val allocations = states.associate { s ->
            val weight = if (totalGapWeight <= 0.0) 1.0 / states.size else s.readinessGap / totalGapWeight
            val allocated = (baseTarget * weight).roundToInt().coerceIn(0, s.remaining)
            s.chapter to allocated
        }.filterValues { it > 0 }

        val target = DailyQuestionTarget(
            dayKey = dayKey,
            testPlanId = testPlan.id,
            totalQuestions = allocations.values.sum(),
            chapterAllocations = allocations
        )
        db.dailyQuestionTargetDao().upsert(target)
        Log.d(TAG, "getOrComputeTodayTarget: testPlan=${testPlan.id} daysRemaining=$daysRemaining " +
            "effectiveDays=$effectiveDays total=${target.totalQuestions} allocations=$allocations")
        return target
    }

    private suspend fun persistZero(
        db: LearningDatabase,
        dayKey: String,
        testPlanId: String
    ): DailyQuestionTarget {
        val zero = DailyQuestionTarget(dayKey, testPlanId, 0, emptyMap())
        db.dailyQuestionTargetDao().upsert(zero)
        return zero
    }
}
