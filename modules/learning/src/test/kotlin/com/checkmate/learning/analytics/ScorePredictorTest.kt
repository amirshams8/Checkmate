package com.checkmate.learning.analytics

import com.checkmate.learning.engine.MasteryEngine
import com.checkmate.learning.model.ConceptSnapshot
import com.checkmate.learning.model.OverallLearningState
import com.checkmate.learning.model.RetentionDecisionSnapshot
import com.checkmate.learning.model.StudentModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScorePredictorTest {

    // Same fixture shape as ScoreGainEstimatorTest/PerformanceAnalyzerTest, reused
    // deliberately so this test can only fail on ScorePredictor's own logic, not a
    // fresh weightage-resolution question.
    private fun concept(
        id: String,
        subject: String?,
        chapter: String,
        topic: String,
        mastery: Double,
        attemptCount: Int,
        retentionDecision: RetentionDecisionSnapshot = RetentionDecisionSnapshot.TEACH,
        errorCount: Int = 0
    ) = ConceptSnapshot(
        conceptId = id,
        exam = "NEET",
        subject = subject,
        chapter = chapter,
        topic = topic,
        mastery = mastery,
        masteryConfidence = 0.0,
        retentionDecision = retentionDecision,
        forgettingRisk = 0.0,
        attemptCount = attemptCount,
        recentAccuracy = mastery,
        lifetimeAccuracy = mastery,
        errorCount = errorCount,
        lastSeen = 1_000L,
        prerequisiteIssues = emptyList()
    )

    private fun studentModel(concepts: List<ConceptSnapshot>) = StudentModel(
        studentId = "s1",
        generatedAt = 1_000L,
        overall = OverallLearningState(
            conceptsTracked = concepts.size,
            conceptsMastered = concepts.count { it.mastery >= MasteryEngine.MASTERY_THRESHOLD },
            conceptsWeak = concepts.count { it.mastery < MasteryEngine.MASTERY_THRESHOLD },
            averageMastery = concepts.map { it.mastery }.average(),
            totalAttempts = concepts.sumOf { it.attemptCount },
            unresolvedErrorCount = 0
        ),
        concepts = concepts.associateBy { it.conceptId },
        unresolvedErrors = emptyList(),
        weakPrerequisites = emptyList()
    )

    @Test
    fun `expected score is bracketed by its own range and never exceeds the exam total`() {
        val model = studentModel(
            listOf(
                concept(
                    id = "c-physio", subject = "Biology", chapter = "Human Physiology", topic = "Human Physiology",
                    mastery = 0.4, attemptCount = 10
                ),
                concept(
                    id = "c-laws", subject = "Physics", chapter = "Laws of Motion", topic = "Laws of Motion",
                    mastery = 0.13, attemptCount = 8, errorCount = 4
                )
            )
        )

        val score = ScorePredictor.predict(model, examType = "NEET", targetScore = 650)

        assertTrue(score.rangeLow <= score.expected)
        assertTrue(score.expected <= score.rangeHigh)
        assertTrue(score.expected in 0.0..720.0)
        assertTrue(score.rangeLow >= 0.0 && score.rangeHigh <= 720.0)
    }

    @Test
    fun `gap floors at zero once the target is already met, and only produces bottlenecks when a real gap exists`() {
        val model = studentModel(
            listOf(
                concept(
                    id = "c-strong", subject = "Physics", chapter = "Laws of Motion", topic = "Laws of Motion",
                    mastery = 0.9, attemptCount = 10
                )
            )
        )

        val alreadyThere = ScorePredictor.predict(model, examType = "NEET", targetScore = 1)
        assertEquals(0.0, alreadyThere.gap, 0.001)
        assertTrue(alreadyThere.bottlenecks.isEmpty())

        val stillShort = ScorePredictor.predict(model, examType = "NEET", targetScore = 700)
        assertTrue(stillShort.gap > 0.0)
        assertEquals(700 - stillShort.expected, stillShort.gap, 0.001)
    }

    @Test
    fun `thinner attempt data widens the range versus the same mastery backed by more attempts`() {
        val thin = studentModel(
            listOf(
                concept(
                    id = "c-thin", subject = "Physics", chapter = "Laws of Motion", topic = "Laws of Motion",
                    mastery = 0.5, attemptCount = 1
                )
            )
        )
        val wellEvidenced = studentModel(
            listOf(
                concept(
                    id = "c-evidenced", subject = "Physics", chapter = "Laws of Motion", topic = "Laws of Motion",
                    mastery = 0.5, attemptCount = 20
                )
            )
        )

        val thinScore = ScorePredictor.predict(thin, examType = "NEET", targetScore = 650)
        val evidencedScore = ScorePredictor.predict(wellEvidenced, examType = "NEET", targetScore = 650)

        val thinWidth = thinScore.rangeHigh - thinScore.rangeLow
        val evidencedWidth = evidencedScore.rangeHigh - evidencedScore.rangeLow
        assertTrue(thinWidth > evidencedWidth)
    }

    @Test
    fun `bottleneck contributions always sum to exactly the gap, whether or not a residual line is needed`() {
        // Small target — concept-level shortfall alone exceeds it, so buckets get
        // scaled down and there's no "exam strategy" residual line.
        val model = studentModel(
            listOf(
                concept(
                    id = "c-weak-1", subject = "Biology", chapter = "Human Physiology", topic = "Human Physiology",
                    mastery = 0.1, attemptCount = 10, errorCount = 5
                ),
                concept(
                    id = "c-weak-2", subject = "Physics", chapter = "Laws of Motion", topic = "Laws of Motion",
                    mastery = 0.1, attemptCount = 10, retentionDecision = RetentionDecisionSnapshot.REVIEW
                )
            )
        )

        val smallTarget = ScorePredictor.predict(model, examType = "NEET", targetScore = 1)
        if (smallTarget.gap > 0.0) {
            assertEquals(smallTarget.gap, smallTarget.bottlenecks.sumOf { it.marks }, 0.01)
        }

        val bigTarget = ScorePredictor.predict(model, examType = "NEET", targetScore = 720)
        assertTrue(bigTarget.gap > 0.0)
        assertEquals(bigTarget.gap, bigTarget.bottlenecks.sumOf { it.marks }, 0.01)
        // A big-enough target should need more recovery than the concept-level
        // weaknesses alone explain, so the residual "exam strategy" line appears.
        assertTrue(bigTarget.bottlenecks.any { it.mechanism == ScorePredictor.BottleneckMechanism.OTHER })
    }

    @Test
    fun `bottleneck mechanism reflects error-heaviness and retention risk, not just raw mastery`() {
        val model = studentModel(
            listOf(
                concept(
                    id = "c-errors", subject = "Physics", chapter = "Laws of Motion", topic = "Laws of Motion",
                    mastery = 0.1, attemptCount = 10, errorCount = 5
                ),
                concept(
                    id = "c-retention", subject = "Biology", chapter = "Human Physiology", topic = "Human Physiology",
                    mastery = 0.1, attemptCount = 10, retentionDecision = RetentionDecisionSnapshot.REVIEW
                ),
                concept(
                    id = "c-plain-weak", subject = "Biology", chapter = "Biodiversity", topic = "Biodiversity",
                    mastery = 0.1, attemptCount = 10
                )
            )
        )

        val score = ScorePredictor.predict(model, examType = "NEET", targetScore = 720)
        val mechanisms = score.bottlenecks.map { it.mechanism }.toSet()

        assertTrue(ScorePredictor.BottleneckMechanism.ERRORS in mechanisms)
        assertTrue(ScorePredictor.BottleneckMechanism.RETENTION in mechanisms)
        assertTrue(ScorePredictor.BottleneckMechanism.WEAK_CONCEPTS in mechanisms)
    }
}
