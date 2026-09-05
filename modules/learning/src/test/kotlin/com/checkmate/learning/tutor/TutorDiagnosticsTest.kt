package com.checkmate.learning.tutor

import com.checkmate.learning.engine.MasteryEngine
import com.checkmate.learning.model.ConceptSnapshot
import com.checkmate.learning.model.RetentionDecisionSnapshot
import org.junit.Assert.assertEquals
import org.junit.Test

class TutorDiagnosticsTest {

    private fun snapshot(
        mastery: Double,
        retentionDecision: RetentionDecisionSnapshot = RetentionDecisionSnapshot.MOVE_ON,
        errorCount: Int = 0
    ) = ConceptSnapshot(
        conceptId = "concept-1",
        exam = "NEET",
        subject = "Biology",
        chapter = "Biomolecules",
        topic = "Proteins",
        mastery = mastery,
        masteryConfidence = 0.0,
        retentionDecision = retentionDecision,
        forgettingRisk = 0.0,
        attemptCount = 10,
        recentAccuracy = mastery,
        lifetimeAccuracy = mastery,
        errorCount = errorCount,
        lastSeen = 1_000L,
        prerequisiteIssues = emptyList()
    )

    private val above = MasteryEngine.MASTERY_THRESHOLD + 0.1
    private val below = MasteryEngine.MASTERY_THRESHOLD - 0.1

    @Test
    fun `null snapshot is always UNKNOWN`() {
        assertEquals(DiagnosticFinding.UNKNOWN, TutorDiagnostics.diagnose(null))
    }

    @Test
    fun `mastered with no retention risk is KNOWN`() {
        val s = snapshot(mastery = above, retentionDecision = RetentionDecisionSnapshot.MOVE_ON, errorCount = 0)
        assertEquals(DiagnosticFinding.KNOWN, TutorDiagnostics.diagnose(s))
    }

    @Test
    fun `mastered but flagged for retention review is FORGOTTEN`() {
        val s = snapshot(mastery = above, retentionDecision = RetentionDecisionSnapshot.REVIEW, errorCount = 0)
        assertEquals(DiagnosticFinding.FORGOTTEN, TutorDiagnostics.diagnose(s))
    }

    @Test
    fun `below threshold with recorded errors is MISUNDERSTOOD`() {
        val s = snapshot(mastery = below, errorCount = 3)
        assertEquals(DiagnosticFinding.MISUNDERSTOOD, TutorDiagnostics.diagnose(s))
    }

    @Test
    fun `below threshold with zero errors is UNKNOWN, not assumed MISUNDERSTOOD`() {
        val s = snapshot(mastery = below, errorCount = 0)
        assertEquals(DiagnosticFinding.UNKNOWN, TutorDiagnostics.diagnose(s))
    }

    @Test
    fun `mastered takes priority over a stray error count`() {
        // Above threshold with old errorCount>0 but no retention flag: still KNOWN, not
        // MISUNDERSTOOD — the MISUNDERSTOOD branch only applies to still-below-threshold
        // mastery, per the class doc's own ordering.
        val s = snapshot(mastery = above, retentionDecision = RetentionDecisionSnapshot.MOVE_ON, errorCount = 5)
        assertEquals(DiagnosticFinding.KNOWN, TutorDiagnostics.diagnose(s))
    }
}
