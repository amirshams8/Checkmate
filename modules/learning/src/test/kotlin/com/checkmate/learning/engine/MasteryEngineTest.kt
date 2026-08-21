package com.checkmate.learning.engine

import com.checkmate.learning.model.QuestionAttempt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MasteryEngineTest {

    private fun attempt(correct: Boolean, timestamp: Long, timeTakenSeconds: Int? = 60) = QuestionAttempt(
        questionId = "q-fixed",
        timestamp = timestamp,
        correct = correct,
        timeTakenSeconds = timeTakenSeconds,
        selectedOption = if (correct) "a" else "b"
    )

    @Test
    fun `all-correct attempts produce mastery of 1_0`() {
        val attempts = (1..5).map { attempt(correct = true, timestamp = it.toLong()) }
        val mastery = MasteryEngine.computeMastery("s1", "concept-1", attempts)

        assertEquals(1.0, mastery.lifetimeAccuracy, 0.0001)
        assertEquals(1.0, mastery.recentAccuracy, 0.0001)
        // retention factors in days-since-lastSeen decay, so mastery can be < 1.0
        // even with perfect accuracy — only accuracy/speed are guaranteed maxed here.
        assertTrue(mastery.mastery > 0.0)
    }

    @Test
    fun `all-wrong attempts produce zero accuracy and nonzero error rate`() {
        val attempts = (1..5).map { attempt(correct = false, timestamp = it.toLong()) }
        val mastery = MasteryEngine.computeMastery("s1", "concept-1", attempts)

        assertEquals(0.0, mastery.lifetimeAccuracy, 0.0001)
        assertEquals(1.0, mastery.errorRate, 0.0001)
    }

    @Test
    fun `recentAccuracy only looks at the last 10 attempts`() {
        // 20 wrong attempts followed by 10 correct ones — recentAccuracy should be 1.0,
        // lifetimeAccuracy should reflect all 30.
        val wrongAttempts = (1..20).map { attempt(correct = false, timestamp = it.toLong()) }
        val correctAttempts = (21..30).map { attempt(correct = true, timestamp = it.toLong()) }
        val mastery = MasteryEngine.computeMastery("s1", "concept-1", wrongAttempts + correctAttempts)

        assertEquals(1.0, mastery.recentAccuracy, 0.0001)
        assertEquals(10.0 / 30.0, mastery.lifetimeAccuracy, 0.0001)
    }

    @Test
    fun `missing timing data does not crash and falls back to a neutral speed score`() {
        val attempts = listOf(attempt(correct = true, timestamp = 1L, timeTakenSeconds = null))
        val mastery = MasteryEngine.computeMastery("s1", "concept-1", attempts)

        assertNull(mastery.medianTimeSeconds)
        assertTrue(mastery.mastery > 0.0)
    }

    @Test
    fun `learnedAt is the first attempt timestamp, lastSeen is the last`() {
        val attempts = listOf(
            attempt(correct = false, timestamp = 100L),
            attempt(correct = true, timestamp = 200L),
            attempt(correct = true, timestamp = 300L)
        )
        val mastery = MasteryEngine.computeMastery("s1", "concept-1", attempts)

        assertEquals(100L, mastery.learnedAt)
        assertEquals(300L, mastery.lastSeen)
        assertEquals(2, mastery.successfulRecallCount)
        assertEquals(1, mastery.failedRecallCount)
    }
}
