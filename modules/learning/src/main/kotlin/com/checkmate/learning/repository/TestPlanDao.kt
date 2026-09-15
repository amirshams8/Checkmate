package com.checkmate.learning.repository

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.checkmate.learning.model.DailyQuestionTarget
import com.checkmate.learning.model.TestPlan

/**
 * Q-bank MVP. [DailyQuestionTargetDao] lives alongside [TestPlanDao] rather than its
 * own file, same "don't invent an unlisted file for a row that has no meaning
 * without its parent" precedent QuestionAttemptDao/ConceptDao already set.
 */
@Dao
interface TestPlanDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(testPlan: TestPlan)

    @Query("SELECT * FROM test_plans WHERE id = :id")
    suspend fun getById(id: String): TestPlan?

    // "Nearest upcoming test" — QuestionTargetEngine's actual entry point.
    // examDateMillis >= :nowMillis excludes tests that have already passed; nothing
    // here decides what "today" or "final review" means, that's QuestionTargetEngine.
    @Query("SELECT * FROM test_plans WHERE examDateMillis >= :nowMillis ORDER BY examDateMillis ASC LIMIT 1")
    suspend fun getNearestUpcoming(nowMillis: Long): TestPlan?

    @Query("SELECT * FROM test_plans ORDER BY examDateMillis ASC")
    suspend fun getAll(): List<TestPlan>
}

@Dao
interface DailyQuestionTargetDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(target: DailyQuestionTarget)

    @Query("SELECT * FROM daily_question_targets WHERE dayKey = :dayKey AND testPlanId = :testPlanId")
    suspend fun getByDay(dayKey: String, testPlanId: String): DailyQuestionTarget?
}
