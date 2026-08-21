package com.checkmate.learning.engine

import android.content.Context
import com.checkmate.learning.model.LearningIds
import com.checkmate.learning.repository.LearningDatabase
import kotlin.math.exp

/**
 * Upgrade Blueprint Phase 1.7 ("RetentionEngine — answers 'does the student *still*
 * know it,' not 'did they study it'").
 *
 * HONEST GAP: the blueprint's retention fields (learned_at, successful_recall_n,
 * failed_recall, last_review) are stored on
 * [com.checkmate.learning.model.ConceptMastery] itself (learnedAt /
 * successfulRecallCount / failedRecallCount / lastSeen) rather than a separate
 * retention table — the blueprint's own model/ listing for Phase 1.4-1.7 names
 * Concept.kt, ConceptMastery.kt, ErrorRecord.kt only, no separate retention entity,
 * so this reuses the mastery row rather than inventing an unlisted file.
 *
 * There is no spaced-repetition review scheduler anywhere in this codebase yet (no
 * SM-2/Leitner implementation) — [retentionScore]/[forgettingRisk] below are a
 * deliberately simple exponential-decay-by-days-since-last-seen model, explicitly
 * NOT the "successful_recall_n vs failed_recall" Ebbinghaus-curve fit the blueprint
 * gestures at. Good enough to feed [decide]'s three-way REVIEW/TEACH/MOVE_ON split;
 * revisit if/when real forgetting-curve data (repeated recall attempts spaced over
 * weeks) exists to fit against.
 */
object RetentionEngine {

    enum class RetentionDecision { REVIEW, TEACH, MOVE_ON }

    // Kept numerically in sync with MasteryEngine.MASTERY_THRESHOLD by hand, not
    // imported directly — RetentionEngine has no dependency on MasteryEngine today
    // and this avoids introducing one for a single constant.
    const val HIGH_MASTERY_THRESHOLD = 0.75
    const val HIGH_RISK_THRESHOLD = 0.5

    /** Days after which, with no further attempts, forgetting risk saturates near 1.0. */
    private const val DECAY_HALFLIFE_DAYS = 14.0
    private const val MS_PER_DAY = 86_400_000.0

    /**
     * 0..1, higher = better retained. Decays from [recentAccuracy] toward 0 as days
     * since [lastSeen] grow, so a concept nailed once three months ago scores lower
     * than one nailed yesterday, even with identical accuracy.
     */
    fun retentionScore(lastSeen: Long?, recentAccuracy: Double, now: Long = System.currentTimeMillis()): Double {
        if (lastSeen == null) return 0.0
        val daysSince = (now - lastSeen).coerceAtLeast(0) / MS_PER_DAY
        val decay = exp(-daysSince / DECAY_HALFLIFE_DAYS)
        return (recentAccuracy * decay).coerceIn(0.0, 1.0)
    }

    /** Inverse-shaped: rises with time since [lastSeen], damped by how well the concept was known. */
    fun forgettingRisk(lastSeen: Long?, mastery: Double, now: Long = System.currentTimeMillis()): Double {
        if (lastSeen == null) return 1.0
        val daysSince = (now - lastSeen).coerceAtLeast(0) / MS_PER_DAY
        val rawRisk = 1.0 - exp(-daysSince / DECAY_HALFLIFE_DAYS)
        // Well-mastered concepts decay slower than shakily-known ones — scale by
        // (1 - mastery/2) so a 0.9-mastery concept's risk rises at ~55% the rate of a 0.0-mastery one.
        return (rawRisk * (1.0 - mastery / 2.0)).coerceIn(0.0, 1.0)
    }

    /**
     * Blueprint §1.7 truth table:
     * HIGH mastery + HIGH forgetting risk -> REVIEW
     * LOW mastery                        -> TEACH
     * HIGH mastery + LOW risk            -> MOVE ON
     */
    fun decide(mastery: Double, forgettingRisk: Double): RetentionDecision = when {
        mastery < HIGH_MASTERY_THRESHOLD -> RetentionDecision.TEACH
        forgettingRisk >= HIGH_RISK_THRESHOLD -> RetentionDecision.REVIEW
        else -> RetentionDecision.MOVE_ON
    }

    suspend fun decideAll(
        context: Context,
        studentId: String = LearningIds.LOCAL_STUDENT_ID
    ): Map<String, RetentionDecision> {
        val db = LearningDatabase.getInstance(context)
        return db.masteryDao().getAll(studentId).associate { it.conceptId to decide(it.mastery, it.forgettingRisk) }
    }
}
