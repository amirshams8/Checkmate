package com.checkmate.learning.analytics

import com.checkmate.learning.model.ConceptSnapshot
import com.checkmate.learning.model.OverallLearningState
import com.checkmate.learning.model.PrerequisiteRef
import com.checkmate.learning.model.RetentionDecisionSnapshot
import com.checkmate.learning.model.StudentModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScoreGainEstimatorTest {

    private fun concept(
        id: String,
        subject: String?,
        chapter: String,
        topic: String,
        mastery: Double,
        attemptCount: Int,
        retentionDecision: RetentionDecisionSnapshot = RetentionDecisionSnapshot.TEACH,
        forgettingRisk: Double = 0.0,
        errorCount: Int = 0,
        prerequisiteIssues: List<PrerequisiteRef> = emptyList()
    ) = ConceptSnapshot(
        conceptId = id,
        exam = "NEET",
        subject = subject,
        chapter = chapter,
        topic = topic,
        mastery = mastery,
        masteryConfidence = 0.0,
        retentionDecision = retentionDecision,
        forgettingRisk = forgettingRisk,
        attemptCount = attemptCount,
        recentAccuracy = mastery,
        lifetimeAccuracy = mastery,
        errorCount = errorCount,
        lastSeen = 1_000L,
        prerequisiteIssues = prerequisiteIssues
    )

    private fun studentModel(concepts: List<ConceptSnapshot>) = StudentModel(
        studentId = "s1",
        generatedAt = 1_000L,
        overall = OverallLearningState(
            conceptsTracked = concepts.size,
            conceptsMastered = concepts.count { it.mastery >= 0.75 },
            conceptsWeak = concepts.count { it.mastery < 0.75 },
            averageMastery = concepts.map { it.mastery }.average(),
            totalAttempts = concepts.sumOf { it.attemptCount },
            unresolvedErrorCount = 0
        ),
        concepts = concepts.associateBy { it.conceptId },
        unresolvedErrors = emptyList(),
        weakPrerequisites = emptyList()
    )

    // Same fixtures PerformanceAnalyzerTest already proves resolve correctly
    // (Human Physiology / Biodiversity / Laws of Motion / unresolvable pair) —
    // reused deliberately so a ranking difference here can only come from
    // ScoreGainEstimator's own logic, not a fresh weightage-resolution question.

    @Test
    fun `a higher-weightage weak concept ranks above a lower-weightage equally-weak one`() {
        val highValue = concept(
            id = "c-high", subject = "Biology", chapter = "Human Physiology", topic = "Human Physiology",
            mastery = 0.13, attemptCount = 10, errorCount = 3
        )
        val lowValue = concept(
            id = "c-low", subject = "Biology", chapter = "Biodiversity", topic = "Biodiversity",
            mastery = 0.13, attemptCount = 10, errorCount = 3
        )

        val ranked = ScoreGainEstimator.rank(studentModel(listOf(lowValue, highValue)), examType = "NEET")

        assertEquals("c-high", ranked.first().conceptId)
        assertTrue(ranked.first().expectedGain > ranked.last().expectedGain)
    }

    @Test
    fun `a concept the resolver could not place has zero expected gain, not a crash`() {
        val unresolvable = concept(
            id = "c-unresolvable", subject = null, chapter = "Not A Real Chapter", topic = "Not A Real Topic",
            mastery = 0.2, attemptCount = 10
        )

        val ranked = ScoreGainEstimator.rank(studentModel(listOf(unresolvable)), examType = "NEET")

        assertEquals(1, ranked.size)
        assertEquals(0.0, ranked.first().expectedGain, 0.001)
        assertEquals(0.0, ranked.first().marksAtStake, 0.001)
    }

    @Test
    fun `a thinly-attempted concept is flagged LOW confidence even with a large mastery gap`() {
        val thin = concept(
            id = "c-thin", subject = "Physics", chapter = "Laws of Motion", topic = "Laws of Motion",
            mastery = 0.1, attemptCount = 1
        )

        val ranked = ScoreGainEstimator.rank(studentModel(listOf(thin)), examType = "NEET")

        assertEquals(ScoreGainEstimator.EstimateConfidence.LOW, ranked.first().confidence)
    }

    @Test
    fun `a concept still needing TEACH outranks an equally weak concept already at MOVE_ON`() {
        val needsTeach = concept(
            id = "c-teach", subject = "Physics", chapter = "Laws of Motion", topic = "Laws of Motion",
            mastery = 0.13, attemptCount = 10, retentionDecision = RetentionDecisionSnapshot.TEACH
        )
        val moveOn = concept(
            id = "c-moveon", subject = "Physics", chapter = "Laws of Motion", topic = "Laws of Motion",
            mastery = 0.13, attemptCount = 10, retentionDecision = RetentionDecisionSnapshot.MOVE_ON
        )

        val ranked = ScoreGainEstimator.rank(studentModel(listOf(moveOn, needsTeach)), examType = "NEET")

        assertEquals("c-teach", ranked.first().conceptId)
    }

    @Test
    fun `more weak prerequisites raises time cost and lowers expected gain versus an otherwise identical concept`() {
        val clean = concept(
            id = "c-clean", subject = "Biology", chapter = "Human Physiology", topic = "Human Physiology",
            mastery = 0.13, attemptCount = 10
        )
        val tangled = concept(
            id = "c-tangled", subject = "Biology", chapter = "Human Physiology", topic = "Human Physiology",
            mastery = 0.13, attemptCount = 10,
            prerequisiteIssues = listOf(
                PrerequisiteRef("prereq-1", "Biology", "Cell Structure And Function", "Cell Structure And Function")
            )
        )

        val ranked = studentModel(listOf(clean, tangled)).let { ScoreGainEstimator.rank(it, examType = "NEET") }
        val cleanEstimate = ranked.first { it.conceptId == "c-clean" }
        val tangledEstimate = ranked.first { it.conceptId == "c-tangled" }

        assertTrue(tangledEstimate.timeCostMinutes > cleanEstimate.timeCostMinutes)
        assertTrue(tangledEstimate.expectedGain < cleanEstimate.expectedGain)
    }
}
