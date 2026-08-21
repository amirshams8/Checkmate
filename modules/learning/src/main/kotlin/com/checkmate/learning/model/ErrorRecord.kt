package com.checkmate.learning.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * Upgrade Blueprint Phase 1.6 ("ErrorEngine — classify every wrong answer").
 * See [com.checkmate.learning.engine.ErrorEngine]'s class doc: only the CARELESS
 * and TIME_PRESSURE branches are actually distinguishable by the heuristic
 * currently wired up. The rest of this enum exists per the blueprint's full list
 * and is ready for the real LLM-proposal classifier once question.explanation has
 * content worth reasoning over — Testmate's report.md never populates it today.
 */
enum class ErrorType {
    UNKNOWN_CONCEPT, MISCONCEPTION, FORMULA_RECALL, FORMULA_SELECTION,
    CALCULATION, UNIT_ERROR, SIGN_ERROR, QUESTION_MISREAD, CARELESS,
    TIME_PRESSURE, BAD_GUESS
}

/**
 * One row per classified wrong [QuestionAttempt] — immutable, same "record, don't
 * mutate" discipline as [LearningEvent]. `conceptId` is nullable defensively even
 * though [com.checkmate.learning.graph.KnowledgeGraph.conceptId] always returns a
 * non-null id in practice (it falls back to "unknown" exam/chapter strings rather
 * than failing), in case that contract ever changes.
 */
@Entity(
    tableName = "error_records",
    indices = [
        Index(value = ["conceptId"]), Index(value = ["questionId"]),
        Index(value = ["attemptId"]), Index(value = ["errorType"])
    ]
)
data class ErrorRecord(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val studentId: String = LearningIds.LOCAL_STUDENT_ID,
    val attemptId: String,
    val questionId: String,
    val conceptId: String?,
    val errorType: ErrorType,
    val timestamp: Long,
    /** "heuristic" today — see ErrorEngine's NOT WIRED note. Would become "llm" once that path lands. */
    val classifiedBy: String = "heuristic"
)

/**
 * Blueprint §1.6: "Repeated errors become first-class ErrorPattern objects
 * (concept, type, occurrences, firstSeen, lastSeen, interventions[], resolved)."
 * One row per (studentId, conceptId, errorType) combination, incremented IN PLACE
 * by [com.checkmate.learning.engine.ErrorEngine] as matching [ErrorRecord]s get
 * classified — same derived-aggregate reasoning as [ConceptMastery] for why this
 * one is mutated rather than appended.
 */
@Entity(
    tableName = "error_patterns",
    primaryKeys = ["studentId", "conceptId", "errorType"]
)
data class ErrorPattern(
    val studentId: String = LearningIds.LOCAL_STUDENT_ID,
    val conceptId: String,
    val errorType: ErrorType,
    val occurrences: Int = 1,
    val firstSeen: Long,
    val lastSeen: Long,
    /** Intervention labels applied so far. Nothing in this module writes to this yet — Phase 2 territory. */
    val interventions: List<String> = emptyList(),
    val resolved: Boolean = false
)
