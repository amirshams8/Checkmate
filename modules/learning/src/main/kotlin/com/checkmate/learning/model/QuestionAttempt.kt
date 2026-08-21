package com.checkmate.learning.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * Upgrade Blueprint Phase 1.2. One row per attempt at a [Question]. A question
 * attempted twice (e.g. re-attempted in a later revision test) produces two
 * QuestionAttempt rows, not an overwrite — same "immutable event" discipline as
 * [com.checkmate.learning.model.LearningEvent]; MasteryEngine/RetentionEngine
 * (Phase 1.5/1.7, not yet built) need the full attempt history, not just the
 * latest result, to compute recentAccuracy vs lifetimeAccuracy and forgetting risk.
 *
 * `questionId` has a foreign key to [Question] with CASCADE delete: if a question
 * is ever purged from the bank (e.g. a bad import), its attempt history goes with
 * it rather than dangling.
 */
@Entity(
    tableName = "question_attempts",
    indices = [
        Index(value = ["questionId"]),
        Index(value = ["timestamp"]),
        Index(value = ["studentId"])
    ],
    foreignKeys = [
        ForeignKey(
            entity = Question::class,
            parentColumns = ["id"],
            childColumns = ["questionId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class QuestionAttempt(
    @PrimaryKey val attemptId: String = UUID.randomUUID().toString(),
    val studentId: String = LearningIds.LOCAL_STUDENT_ID,
    val questionId: String,
    val timestamp: Long,
    /** Null means skipped/unattempted — mirrors report.md's "—" answer. */
    val selectedOption: String? = null,
    val correct: Boolean,
    val timeTakenSeconds: Int? = null,
    val confidence: Int? = null,
    val hintUsed: Boolean = false,
    val solutionViewed: Boolean = false,
    val errorType: String? = null
)
