package com.checkmate.learning.analytics

import com.checkmate.learning.engine.MasteryEngine
import com.checkmate.learning.model.ConceptSnapshot
import com.checkmate.learning.model.OverallLearningState
import com.checkmate.learning.model.RetentionDecisionSnapshot
import com.checkmate.learning.model.StudentModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PerformanceAnalyzerTest {

    private fun concept(
        id: String,
        subject: String?,
        chapter: String,
        topic: String,
        mastery: Double,
        recentAccuracy: Double,
        lifetimeAccuracy: Double,
        attemptCount: Int
    ) = ConceptSnapshot(
        conceptId = id,
        exam = "NEET",
        subject = subject,
        chapter = chapter,
        topic = topic,
        mastery = mastery,
        masteryConfidence = 0.0,
        retentionDecision = RetentionDecisionSnapshot.MOVE_ON,
        forgettingRisk = 0.0,
        attemptCount = attemptCount,
        recentAccuracy = recentAccuracy,
        lifetimeAccuracy = lifetimeAccuracy,
        errorCount = 0,
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
    fun `classifyTrend requires the minimum attempt count before calling a trend`() {
        val trend = PerformanceAnalyzer.classifyTrend(recentAccuracy = 1.0, lifetimeAccuracy = 0.2, attemptCount = 3)
        assertEquals(PerformanceAnalyzer.PerformanceTrend.INSUFFICIENT_DATA, trend)
    }

    @Test
    fun `classifyTrend flags a real improvement above the noise threshold`() {
        val trend = PerformanceAnalyzer.classifyTrend(recentAccuracy = 0.9, lifetimeAccuracy = 0.6, attemptCount = 10)
        assertEquals(PerformanceAnalyzer.PerformanceTrend.IMPROVING, trend)
    }

    @Test
    fun `classifyTrend flags decline and treats a small delta as stable`() {
        val declining = PerformanceAnalyzer.classifyTrend(recentAccuracy = 0.3, lifetimeAccuracy = 0.7, attemptCount = 10)
        assertEquals(PerformanceAnalyzer.PerformanceTrend.DECLINING, declining)

        val stable = PerformanceAnalyzer.classifyTrend(recentAccuracy = 0.62, lifetimeAccuracy = 0.6, attemptCount = 10)
        assertEquals(PerformanceAnalyzer.PerformanceTrend.STABLE, stable)
    }

    @Test
    fun `topicImpacts are sorted by marksAtStakeGap descending`() {
        // High weightage, low mastery -> should rank above low weightage, low mastery.
        val weak = concept(
            id = "c-weak-highvalue", subject = "Biology", chapter = "Human Physiology",
            topic = "Human Physiology", mastery = 0.2, recentAccuracy = 0.2, lifetimeAccuracy = 0.2, attemptCount = 10
        )
        val lowValue = concept(
            id = "c-weak-lowvalue", subject = "Biology", chapter = "Biodiversity",
            topic = "Biodiversity", mastery = 0.2, recentAccuracy = 0.2, lifetimeAccuracy = 0.2, attemptCount = 10
        )
        val mastered = concept(
            id = "c-mastered", subject = "Physics", chapter = "Electrostatics",
            topic = "Electrostatics", mastery = 0.95, recentAccuracy = 0.95, lifetimeAccuracy = 0.95, attemptCount = 10
        )

        val report = PerformanceAnalyzer.analyze(studentModel(listOf(lowValue, mastered, weak)), examType = "NEET")

        assertEquals("c-weak-highvalue", report.topicImpacts.first().conceptId)
        assertTrue(report.topicImpacts.first().marksAtStakeGap > report.topicImpacts.last().marksAtStakeGap)
    }

    /**
     * Replaces the old "concepts with no subject are excluded from subjectAccuracy"
     * test — that behavior was the bug (see PerformanceAnalyzer's own doc on the
     * subject-resolution fix). "Current Electricity" is the same fixture
     * ConceptWeightageTest uses to prove fuzzy resolution finds Physics for a
     * real-imported concept (subject == null) — this test proves subjectAccuracy
     * now benefits from that same resolution, not just topicImpacts.
     */
    @Test
    fun `a real-import concept with no subject is included in subjectAccuracy via fuzzy resolution`() {
        val realImport = concept(
            id = "c-real-import", subject = null, chapter = "Current Electricity",
            topic = "Current Electricity", mastery = 0.5, recentAccuracy = 0.5, lifetimeAccuracy = 0.5, attemptCount = 6
        )

        val report = PerformanceAnalyzer.analyze(studentModel(listOf(realImport)), examType = "NEET")

        assertEquals(1, report.topicImpacts.size)
        assertEquals(1, report.subjectAccuracy.size)
        assertEquals("Physics", report.subjectAccuracy.first().subject)
        assertEquals(6, report.subjectAccuracy.first().attemptCount)
    }

    /**
     * The fix narrows exclusion to "fuzzy resolution genuinely found nothing," not
     * "raw subject was null" — this fixture (same as
     * ConceptWeightageTest's "unresolvable topic" case) can't be placed under any
     * subject at all, so it's still correctly excluded from subjectAccuracy while
     * remaining visible in topicImpacts (with zero marksAtStake, per
     * ConceptWeightage's own "unresolved -> 0, not an error" contract).
     */
    @Test
    fun `a concept unresolvable by fuzzy matching stays out of subjectAccuracy but still appears in topicImpacts`() {
        val unresolvable = concept(
            id = "c-unresolvable", subject = null, chapter = "Not A Real Chapter",
            topic = "Not A Real Topic", mastery = 0.5, recentAccuracy = 0.5, lifetimeAccuracy = 0.5, attemptCount = 6
        )

        val report = PerformanceAnalyzer.analyze(studentModel(listOf(unresolvable)), examType = "NEET")

        assertEquals(1, report.topicImpacts.size)
        assertTrue(report.subjectAccuracy.isEmpty())
        assertEquals(0.0, report.topicImpacts.first().marksAtStakeGap, 0.001)
    }

    @Test
    fun `overall trend is attempt-weighted across concepts, not a plain average`() {
        // One concept with many attempts and a strong recent improvement should
        // dominate one concept with few attempts and a mild decline.
        val dominant = concept(
            id = "c-dominant", subject = "Physics", chapter = "Kinematics", topic = "Kinematics",
            mastery = 0.8, recentAccuracy = 0.9, lifetimeAccuracy = 0.5, attemptCount = 50
        )
        val minor = concept(
            id = "c-minor", subject = "Chemistry", chapter = "Solutions", topic = "Solutions",
            mastery = 0.5, recentAccuracy = 0.4, lifetimeAccuracy = 0.5, attemptCount = 4
        )

        val report = PerformanceAnalyzer.analyze(studentModel(listOf(dominant, minor)), examType = "NEET")

        assertEquals(PerformanceAnalyzer.PerformanceTrend.IMPROVING, report.overallTrend)
        assertEquals(54, report.totalAttempts)
    }
}
