package com.checkmate.learning.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class RetentionEngineTest {

    @Test
    fun `low mastery always means TEACH regardless of forgetting risk`() {
        assertEquals(RetentionEngine.RetentionDecision.TEACH, RetentionEngine.decide(mastery = 0.3, forgettingRisk = 0.0))
        assertEquals(RetentionEngine.RetentionDecision.TEACH, RetentionEngine.decide(mastery = 0.3, forgettingRisk = 0.9))
    }

    @Test
    fun `high mastery with high forgetting risk means REVIEW`() {
        assertEquals(
            RetentionEngine.RetentionDecision.REVIEW,
            RetentionEngine.decide(mastery = 0.9, forgettingRisk = RetentionEngine.HIGH_RISK_THRESHOLD)
        )
    }

    @Test
    fun `high mastery with low forgetting risk means MOVE_ON`() {
        assertEquals(
            RetentionEngine.RetentionDecision.MOVE_ON,
            RetentionEngine.decide(mastery = 0.9, forgettingRisk = 0.1)
        )
    }

    @Test
    fun `retentionScore decays toward zero as days since lastSeen grow`() {
        val now = 1_000_000_000L
        val oneDayAgo = now - 86_400_000L
        val sixtyDaysAgo = now - 60L * 86_400_000L

        val recent = RetentionEngine.retentionScore(oneDayAgo, recentAccuracy = 0.9, now = now)
        val stale = RetentionEngine.retentionScore(sixtyDaysAgo, recentAccuracy = 0.9, now = now)

        assertEquals(true, recent > stale)
    }

    @Test
    fun `null lastSeen means zero retention and maximum forgetting risk`() {
        assertEquals(0.0, RetentionEngine.retentionScore(null, recentAccuracy = 1.0), 0.0001)
        assertEquals(1.0, RetentionEngine.forgettingRisk(null, mastery = 1.0), 0.0001)
    }
}
