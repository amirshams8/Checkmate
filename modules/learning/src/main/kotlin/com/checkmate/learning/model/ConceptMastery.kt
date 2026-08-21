package com.checkmate.learning.model

import androidx.room.Entity
import androidx.room.Index

/**
 * Upgrade Blueprint Phase 1.5 ("MasteryEngine... Track per concept: mastery,
 * confidence, recentAccuracy, lifetimeAccuracy, difficultyAdjustedAccuracy,
 * medianTime, attemptCount, lastSeen, lastMastered, forgettingRisk, errorRate")
 * plus Phase 1.7's retention fields (learned_at, successful_recall_n, failed_recall,
 * last_review) — folded into this same row rather than a separate table. The
 * blueprint's own model/ listing for Phase 1.4-1.7 names Concept.kt,
 * ConceptMastery.kt, ErrorRecord.kt only, no separate retention entity — see
 * [com.checkmate.learning.engine.RetentionEngine]'s class doc for the full
 * reasoning.
 *
 * Composite primary key (studentId, conceptId): one row per student per concept,
 * recomputed IN PLACE by [com.checkmate.learning.engine.MasteryEngine.recomputeAll]
 * — this is the one entity in :modules:learning that's mutated rather than
 * appended, because it's a derived aggregate folded over the immutable
 * QuestionAttempt/LearningEvent history, not a fact in its own right.
 */
@Entity(
    tableName = "concept_mastery",
    primaryKeys = ["studentId", "conceptId"],
    indices = [Index(value = ["conceptId"]), Index(value = ["studentId"])]
)
data class ConceptMastery(
    val studentId: String = LearningIds.LOCAL_STUDENT_ID,
    val conceptId: String,
    /** 0.0-1.0, the blueprint §1.5 weighted formula (renormalized — see MasteryEngine). */
    val mastery: Double,
    /** 0.0-1.0 confidence-calibration score. Always 0.0 today — no stated-confidence input exists anywhere yet. */
    val confidence: Double = 0.0,
    val recentAccuracy: Double,
    val lifetimeAccuracy: Double,
    /** Null until Question.difficulty carries real data — see MasteryEngine's HONEST GAP note. */
    val difficultyAdjustedAccuracy: Double? = null,
    val medianTimeSeconds: Double? = null,
    val attemptCount: Int,
    val lastSeen: Long?,
    /** Timestamp of the last attempt while mastery was >= MasteryEngine.MASTERY_THRESHOLD, else null. */
    val lastMastered: Long? = null,
    val forgettingRisk: Double = 0.0,
    val errorRate: Double = 0.0,
    /** Phase 1.7: timestamp of the first-ever attempt recorded at this concept. */
    val learnedAt: Long? = null,
    val successfulRecallCount: Int = 0,
    val failedRecallCount: Int = 0,
    val lastUpdated: Long = System.currentTimeMillis()
)
