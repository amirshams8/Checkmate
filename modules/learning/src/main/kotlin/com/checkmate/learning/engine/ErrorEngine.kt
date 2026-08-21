package com.checkmate.learning.engine

import android.content.Context
import android.util.Log
import com.checkmate.learning.graph.KnowledgeGraph
import com.checkmate.learning.model.ErrorPattern
import com.checkmate.learning.model.ErrorRecord
import com.checkmate.learning.model.ErrorType
import com.checkmate.learning.model.LearningIds
import com.checkmate.learning.model.QuestionAttempt
import com.checkmate.learning.repository.LearningDatabase

/**
 * Upgrade Blueprint Phase 1.6 ("ErrorEngine — classify every wrong answer").
 *
 * NOT WIRED: the blueprint's intended split is "LLM proposes classification from
 * (question metadata + answer + solution + time + error history); deterministic
 * layer validates/stores." No LLM call happens here — this module has no
 * dependency on :modules:core's LlmGateway, and Question.explanation is
 * essentially never populated by TestResultNormalizer (Testmate's report.md
 * carries no worked solution, only the correct option letter). [classify] below is
 * a deterministic time-based heuristic only, a placeholder for the real
 * LLM-proposal path — wire LlmGateway in here once question.explanation has real
 * content to reason over. Until then this reliably produces CARELESS/TIME_PRESSURE
 * for the two cases timing alone can actually distinguish, and defaults everything
 * else to UNKNOWN_CONCEPT rather than guessing a specific wrong bucket it has no
 * basis for.
 */
object ErrorEngine {

    private const val TAG = "ErrorEngine"
    private const val CARELESS_TIME_SECONDS = 15
    private const val TIME_PRESSURE_SECONDS = 150

    fun classify(attempt: QuestionAttempt): ErrorType {
        val t = attempt.timeTakenSeconds
        return when {
            t != null && t < CARELESS_TIME_SECONDS -> ErrorType.CARELESS
            t != null && t > TIME_PRESSURE_SECONDS -> ErrorType.TIME_PRESSURE
            else -> ErrorType.UNKNOWN_CONCEPT
        }
    }

    /**
     * Classifies every wrong [QuestionAttempt] for [studentId] that doesn't already
     * have an [ErrorRecord] (idempotent, same re-run-safe discipline as
     * TestResultNormalizer's import guard), then folds each new classification into
     * its [ErrorPattern] row — "you've made this mistake 7 times" per the blueprint.
     */
    suspend fun classifyAllUnclassified(
        context: Context,
        studentId: String = LearningIds.LOCAL_STUDENT_ID
    ): List<ErrorRecord> {
        val db = LearningDatabase.getInstance(context)
        val wrongAttempts = db.questionAttemptDao().getWrongAttempts(studentId)
        if (wrongAttempts.isEmpty()) return emptyList()

        val questionById = db.questionDao().getAll().associateBy { it.id }
        val newRecords = mutableListOf<ErrorRecord>()

        for (attempt in wrongAttempts) {
            if (db.errorRecordDao().getByAttempt(attempt.attemptId) != null) continue // already classified

            val question = questionById[attempt.questionId]
            val conceptId = KnowledgeGraph.conceptId(
                exam = question?.exam ?: "unknown",
                chapter = question?.chapter ?: "unknown",
                topic = question?.topic
            )
            val errorType = classify(attempt)

            val record = ErrorRecord(
                studentId = studentId,
                attemptId = attempt.attemptId,
                questionId = attempt.questionId,
                conceptId = conceptId,
                errorType = errorType,
                timestamp = attempt.timestamp
            )
            db.errorRecordDao().insert(record)
            newRecords.add(record)
            recordPattern(db, studentId, conceptId, errorType, attempt.timestamp)
        }

        Log.d(TAG, "Classified ${newRecords.size} new wrong attempt(s) for student=$studentId")
        return newRecords
    }

    private suspend fun recordPattern(
        db: LearningDatabase,
        studentId: String,
        conceptId: String,
        errorType: ErrorType,
        timestamp: Long
    ) {
        val existing = db.errorPatternDao().get(studentId, conceptId, errorType)
        val updated = if (existing == null) {
            ErrorPattern(
                studentId = studentId,
                conceptId = conceptId,
                errorType = errorType,
                occurrences = 1,
                firstSeen = timestamp,
                lastSeen = timestamp
            )
        } else {
            existing.copy(
                occurrences = existing.occurrences + 1,
                lastSeen = maxOf(existing.lastSeen, timestamp)
            )
        }
        db.errorPatternDao().upsert(updated)
    }
}
