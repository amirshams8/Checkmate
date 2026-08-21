package com.checkmate.learning.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * Upgrade Blueprint Phase 1.1 ("LearningEvent — everything educational becomes
 * an immutable event").
 *
 * Every educational occurrence — a question attempt, a concept taught/recalled/
 * forgotten, a mock started/completed, an error caught, a hint used — is recorded
 * as one of these rather than mutated in place anywhere. Downstream engines
 * (MasteryEngine, RetentionEngine, ErrorEngine — Phase 1.5-1.7, not yet built)
 * fold over the event log; they never read or write partial state directly.
 * This mirrors the same "record, don't mutate" discipline BehaviorLedger already
 * uses for behavioral events (:modules:psyche) — same shape, applied to the
 * knowledge/learning side the blueprint identifies as currently missing.
 *
 * Room is the store from day one (Phase 0 item #3 — "Confirm Room is the single
 * source of truth") — there is no legacy CheckmatePrefs blob to migrate here,
 * unlike BehaviorLedger, since this event type didn't exist before this module.
 *
 * `studentId` exists per the blueprint's field contract, but nothing elsewhere in
 * this codebase currently models multiple students on one device (CheckmatePrefs,
 * BehaviorLedger, PlanStore are all single-profile globals). [LearningIds.LOCAL_STUDENT_ID]
 * is used as the default everywhere in this module until/unless real multi-student
 * support is built — not invented here.
 */
enum class LearningEventType {
    QUESTION_ATTEMPTED,
    QUESTION_CORRECT,
    QUESTION_WRONG,
    QUESTION_SKIPPED,
    CONCEPT_TAUGHT,
    CONCEPT_RECALLED,
    CONCEPT_FORGOTTEN,
    PYQ_SOLVED,
    REVISION_COMPLETED,
    MOCK_STARTED,
    MOCK_COMPLETED,
    ERROR_DETECTED,
    ERROR_REPEATED,
    HINT_REQUESTED,
    SOLUTION_REVEALED
}

object LearningIds {
    /** See class doc on [LearningEvent] — placeholder until multi-student support exists. */
    const val LOCAL_STUDENT_ID = "local"
}

@Entity(
    tableName = "learning_events",
    indices = [
        Index(value = ["timestamp"]),
        Index(value = ["studentId", "subjectId"]),
        Index(value = ["questionId"]),
        Index(value = ["eventType"])
    ]
)
data class LearningEvent(
    @PrimaryKey val eventId: String = UUID.randomUUID().toString(),
    val studentId: String = LearningIds.LOCAL_STUDENT_ID,
    val eventType: LearningEventType,
    val timestamp: Long,
    val subjectId: String? = null,
    val chapterId: String? = null,
    val topicId: String? = null,
    val conceptIds: List<String> = emptyList(),
    val questionId: String? = null,
    val difficulty: String? = null,
    /** Where this event originated — e.g. "testmate_report", "in_app_practice". */
    val source: String? = null,
    /** Seconds spent, when meaningful (e.g. a single question attempt). */
    val duration: Int? = null,
    /** 0.0–1.0, only meaningful for aggregate events (e.g. MOCK_COMPLETED), not single questions. */
    val accuracy: Double? = null,
    val confidence: Int? = null,
    val errorType: String? = null
)
