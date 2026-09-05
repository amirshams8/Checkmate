package com.checkmate.learning.engine

import com.checkmate.learning.model.ConceptSnapshot
import com.checkmate.learning.model.ErrorPatternSnapshot
import com.checkmate.learning.model.OverallLearningState
import com.checkmate.learning.model.PrerequisiteIssue
import com.checkmate.learning.model.PrerequisiteRef
import com.checkmate.learning.model.RetentionDecisionSnapshot
import com.checkmate.learning.model.StudentModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LearningDecisionEngineTest {

    // Same fixture shape as ScorePredictorTest/ScoreGainEstimatorTest, reused
    // deliberately so this test can only fail on LearningDecisionEngine's own
    // classification/ranking logic, not a fresh weightage-resolution question.
    private fun concept(
        id: String,
        subject: String?,
        chapter: String,
        topic: String,
        mastery: Double,
        attemptCount: Int,
        retentionDecision: RetentionDecisionSnapshot = RetentionDecisionSnapshot.TEACH,
        errorCount: Int = 0,
        prerequisiteIssues: List<com.checkmate.learning.model.PrerequisiteRef> = emptyList()
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
        prerequisiteIssues = prerequisiteIssues
    )

    private fun studentModel(
        concepts: List<ConceptSnapshot>,
        unresolvedErrors: List<ErrorPatternSnapshot> = emptyList(),
        weakPrerequisites: List<PrerequisiteIssue> = emptyList()
    ) = StudentModel(
        studentId = "s1",
        generatedAt = 1_000L,
        overall = OverallLearningState(
            conceptsTracked = concepts.size,
            conceptsMastered = concepts.count { it.mastery >= MasteryEngine.MASTERY_THRESHOLD },
            conceptsWeak = concepts.count { it.mastery < MasteryEngine.MASTERY_THRESHOLD },
            averageMastery = if (concepts.isEmpty()) 0.0 else concepts.map { it.mastery }.average(),
            totalAttempts = concepts.sumOf { it.attemptCount },
            unresolvedErrorCount = unresolvedErrors.sumOf { it.occurrences }
        ),
        concepts = concepts.associateBy { it.conceptId },
        unresolvedErrors = unresolvedErrors,
        weakPrerequisites = weakPrerequisites
    )

    // Padded with well-mastered filler concepts so overall.totalAttempts clears
    // THIN_DATA_ATTEMPT_THRESHOLD when a test isn't specifically exercising the
    // thin-data / START_MOCK path — keeps that path's own test the only one
    // where it's expected to fire.
    private fun filler(count: Int = 3) = (1..count).map {
        concept(
            id = "filler-$it", subject = "Physics", chapter = "Filler $it", topic = "Filler $it",
            mastery = 0.95, attemptCount = 10
        )
    }

    @Test
    fun `ranks a clear weak concept as REPAIR_CONCEPT with the estimator's own expectedGain`() {
        val model = studentModel(
            filler() + concept(
                id = "c-weak", subject = "Biology", chapter = "Human Physiology", topic = "Human Physiology",
                mastery = 0.1, attemptCount = 10
            )
        )

        val report = LearningDecisionEngine.decide(model, examType = "NEET", targetScore = 650)
        val top = report.candidates.first()

        assertEquals(LearningDecisionEngine.LearningInterventionIntent.REPAIR_CONCEPT, top.intent)
        assertEquals("c-weak", top.conceptId)
        assertTrue(top.expectedGain > 0.0)
        assertEquals(top.expectedGain, top.priorityScore, 0.0001)
    }

    @Test
    fun `high mastery plus REVIEW retention becomes a retention test, not a full repair`() {
        val model = studentModel(
            filler() + concept(
                id = "c-review", subject = "Biology", chapter = "Human Physiology", topic = "Human Physiology",
                mastery = 0.85, attemptCount = 10, retentionDecision = RetentionDecisionSnapshot.REVIEW
            )
        )

        val report = LearningDecisionEngine.decide(model, examType = "NEET", targetScore = 650)
        val candidate = report.candidates.first { it.conceptId == "c-review" }

        assertEquals(LearningDecisionEngine.LearningInterventionIntent.SCHEDULE_RETENTION_TEST, candidate.intent)
        assertEquals(10, candidate.durationMinutes)
    }

    @Test
    fun `high mastery with careless errors suggests increasing difficulty, not repair`() {
        val model = studentModel(
            filler() + concept(
                id = "c-careless", subject = "Physics", chapter = "Laws of Motion", topic = "Laws of Motion",
                mastery = 0.85, attemptCount = 10, errorCount = 2
            ),
            unresolvedErrors = listOf(
                ErrorPatternSnapshot(
                    conceptId = "c-careless", errorType = "CARELESS", occurrences = 2,
                    firstSeen = 100L, lastSeen = 900L
                )
            )
        )

        val report = LearningDecisionEngine.decide(model, examType = "NEET", targetScore = 650)
        val candidate = report.candidates.firstOrNull { it.conceptId == "c-careless" }

        assertTrue(candidate != null)
        assertEquals(LearningDecisionEngine.LearningInterventionIntent.INCREASE_DIFFICULTY, candidate!!.intent)
    }

    @Test
    fun `heavy high-rate errors at low mastery suggest reducing difficulty`() {
        val model = studentModel(
            filler() + concept(
                id = "c-mismatch", subject = "Chemistry", chapter = "Chemical Bonding", topic = "Chemical Bonding",
                mastery = 0.15, attemptCount = 10, errorCount = 7
            )
        )

        val report = LearningDecisionEngine.decide(model, examType = "NEET", targetScore = 650)
        val candidate = report.candidates.first { it.conceptId == "c-mismatch" }

        assertEquals(LearningDecisionEngine.LearningInterventionIntent.REDUCE_DIFFICULTY, candidate.intent)
    }

    @Test
    fun `an untested prerequisite becomes a diagnostic, an already-attempted one does not`() {
        val untested = PrerequisiteRef(
            conceptId = "prereq-untested", subject = "Physics", chapter = "Kinematics", topic = "Kinematics"
        )
        val attempted = PrerequisiteRef(
            conceptId = "prereq-attempted", subject = "Physics", chapter = "Vectors", topic = "Vectors"
        )
        val model = studentModel(
            filler() + concept(
                id = "c-downstream", subject = "Physics", chapter = "Rolling Motion", topic = "Rolling Motion",
                mastery = 0.2, attemptCount = 8
            ) + concept(
                id = "prereq-attempted", subject = "Physics", chapter = "Vectors", topic = "Vectors",
                mastery = 0.3, attemptCount = 5
            ),
            weakPrerequisites = listOf(
                PrerequisiteIssue(conceptId = "c-downstream", weakPrerequisites = listOf(untested, attempted))
            )
        )

        val report = LearningDecisionEngine.decide(model, examType = "NEET", targetScore = 650)

        assertTrue(report.candidates.any {
            it.conceptId == "prereq-untested" &&
                it.intent == LearningDecisionEngine.LearningInterventionIntent.START_DIAGNOSTIC
        })
        assertTrue(report.candidates.none { it.conceptId == "prereq-attempted" && it.intent == LearningDecisionEngine.LearningInterventionIntent.START_DIAGNOSTIC })
    }

    @Test
    fun `thin overall attempt data surfaces START_MOCK among the candidates`() {
        val model = studentModel(
            listOf(
                concept(
                    id = "c-thin", subject = "Biology", chapter = "Human Physiology", topic = "Human Physiology",
                    mastery = 0.2, attemptCount = 3
                )
            )
        )

        val report = LearningDecisionEngine.decide(model, examType = "NEET", targetScore = 650)

        assertTrue(model.overall.totalAttempts < 20)
        assertTrue(report.candidates.any { it.intent == LearningDecisionEngine.LearningInterventionIntent.START_MOCK })
    }

    @Test
    fun `three or more weak topics in one chapter collapse into one targeted set`() {
        // "Human Physiology" resolves via ConceptWeightage's exact (exam, subject,
        // chapter) tier since subject is provided directly — same fixture chapter
        // ScorePredictorTest already relies on for a real, non-zero weightage.
        val model = studentModel(
            filler() + listOf(
                concept(id = "c1", subject = "Biology", chapter = "Human Physiology", topic = "Excretion", mastery = 0.2, attemptCount = 6),
                concept(id = "c2", subject = "Biology", chapter = "Human Physiology", topic = "Circulation", mastery = 0.25, attemptCount = 6),
                concept(id = "c3", subject = "Biology", chapter = "Human Physiology", topic = "Neural Control", mastery = 0.15, attemptCount = 6)
            )
        )

        val report = LearningDecisionEngine.decide(model, examType = "NEET", targetScore = 650)

        val targetedSets = report.candidates.filter {
            it.intent == LearningDecisionEngine.LearningInterventionIntent.ASSIGN_TARGETED_SET && it.chapter == "Human Physiology"
        }
        assertEquals(1, targetedSets.size)
        assertTrue(report.candidates.none { it.chapter == "Human Physiology" && it.intent == LearningDecisionEngine.LearningInterventionIntent.REPAIR_CONCEPT })
    }

    @Test
    fun `every candidate list is capped and sorted by priority descending`() {
        // Eight distinct, real, resolvable chapters (none repeated, so nothing
        // collapses into a targeted set) — enough weak concepts to force the
        // MAX_CANDIDATES cap to actually bind.
        val chapters = listOf(
            "Biology" to "Human Physiology",
            "Physics" to "Laws of Motion",
            "Physics" to "Kinematics",
            "Chemistry" to "Chemical Bonding",
            "Biology" to "Ecology And Environment",
            "Physics" to "Vectors and 3D",
            "Chemistry" to "Coordination Compounds",
            "Biology" to "Biodiversity"
        )
        val model = studentModel(
            chapters.mapIndexed { i, (subject, chapter) ->
                concept(
                    id = "c-many-$i", subject = subject, chapter = chapter, topic = chapter,
                    mastery = 0.1 + i * 0.02, attemptCount = 8
                )
            }
        )

        val report = LearningDecisionEngine.decide(model, examType = "NEET", targetScore = 650)

        assertTrue(report.candidates.isNotEmpty())
        assertTrue(report.candidates.size <= 5)
        val scores = report.candidates.map { it.priorityScore }
        assertEquals(scores.sortedDescending(), scores)
    }
}
