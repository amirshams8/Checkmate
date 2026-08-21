package com.checkmate.learning.engine

import android.content.Context
import android.util.Log
import com.checkmate.learning.graph.KnowledgeGraph
import com.checkmate.learning.model.Concept
import com.checkmate.learning.model.ConceptMastery
import com.checkmate.learning.model.LearningIds
import com.checkmate.learning.model.QuestionAttempt
import com.checkmate.learning.repository.LearningDatabase

/**
 * Upgrade Blueprint Phase 1.5 ("MasteryEngine — deterministic first, no LLM-guessed
 * scores").
 *
 * mastery = 0.35·recentAccuracy + 0.20·lifetimeAccuracy + 0.15·difficultyPerformance
 *         + 0.15·retention + 0.10·speed + 0.05·confidenceCalibration
 *
 * HONEST GAP: two of the six blueprint components have no real data source yet in
 * this codebase — Question.difficulty is always null (TestResultNormalizer never
 * sets it; Testmate's report.md carries no difficulty field) and
 * QuestionAttempt.confidence is always null (nothing captures a stated-confidence
 * input anywhere). Treating a missing input as 0 would drag every mastery score
 * down by exactly its weight regardless of actual performance, so missing
 * components are dropped from the weighted sum and the remaining weights are
 * renormalized to sum to 1.0 (see [renormalizedMastery]). Wire
 * Question.difficulty / a real confidence input in here the moment either exists —
 * nothing else needs to change.
 *
 * Concept identity: [KnowledgeGraph.conceptId] keys on (exam, chapter, topic) only —
 * NOT subject — because TestResultNormalizer-imported Questions never carry a
 * subject. This means a mastery row can exist for a concept before/without ever
 * running [KnowledgeGraph.seedExamSyllabus] for that exam — [recomputeAll] below
 * upserts a minimal [Concept] row itself from whatever Question data it has, so
 * ConceptMastery always has a matching, queryable Concept row even with zero
 * syllabus seeding done.
 */
object MasteryEngine {

    private const val TAG = "MasteryEngine"

    const val MASTERY_THRESHOLD = 0.75
    private const val RECENT_WINDOW = 10

    private const val W_RECENT = 0.35
    private const val W_LIFETIME = 0.20
    private const val W_DIFFICULTY = 0.15
    private const val W_RETENTION = 0.15
    private const val W_SPEED = 0.10
    private const val W_CONFIDENCE = 0.05

    suspend fun recomputeAll(
        context: Context,
        studentId: String = LearningIds.LOCAL_STUDENT_ID
    ): List<ConceptMastery> {
        val db = LearningDatabase.getInstance(context)
        val questions = db.questionDao().getAll()
        val attempts = db.questionAttemptDao().getAll(studentId)
        if (attempts.isEmpty()) return emptyList()

        val questionById = questions.associateBy { it.id }

        val grouped = attempts.groupBy { attempt ->
            val q = questionById[attempt.questionId]
            KnowledgeGraph.conceptId(
                exam = q?.exam ?: "unknown",
                chapter = q?.chapter ?: "unknown",
                topic = q?.topic
            )
        }

        val results = mutableListOf<ConceptMastery>()
        val concepts = mutableListOf<Concept>()

        for ((conceptId, conceptAttempts) in grouped) {
            val sorted = conceptAttempts.sortedBy { it.timestamp }
            results.add(computeMastery(studentId, conceptId, sorted))

            val sampleQuestion = questionById[sorted.last().questionId]
            concepts.add(
                Concept(
                    id = conceptId,
                    exam = sampleQuestion?.exam ?: "unknown",
                    subject = sampleQuestion?.subject,
                    chapter = sampleQuestion?.chapter ?: "unknown",
                    topic = sampleQuestion?.topic ?: sampleQuestion?.chapter ?: "unknown"
                )
            )
        }

        db.conceptDao().upsertAll(concepts)
        db.masteryDao().upsertAll(results)
        Log.d(TAG, "Recomputed mastery for ${results.size} concept(s), student=$studentId")
        return results
    }

    /** Pure, testable — no DB/context. Exposed for MasteryEngineTest. */
    fun computeMastery(
        studentId: String,
        conceptId: String,
        attemptsSortedAscending: List<QuestionAttempt>
    ): ConceptMastery {
        require(attemptsSortedAscending.isNotEmpty()) { "computeMastery requires at least one attempt" }

        val attemptCount = attemptsSortedAscending.size
        val lifetimeAccuracy = attemptsSortedAscending.count { it.correct }.toDouble() / attemptCount
        val recentSlice = attemptsSortedAscending.takeLast(RECENT_WINDOW)
        val recentAccuracy = recentSlice.count { it.correct }.toDouble() / recentSlice.size
        val lastSeen = attemptsSortedAscending.last().timestamp
        val medianTime = median(attemptsSortedAscending.mapNotNull { it.timeTakenSeconds })
        val speed = speedScore(medianTime)
        val retention = RetentionEngine.retentionScore(lastSeen, recentAccuracy)

        // difficulty / confidence: see the HONEST GAP note on the class doc. Both null today.
        val difficultyPerformance: Double? = null
        val confidenceCalibration: Double? = null

        val mastery = renormalizedMastery(
            recentAccuracy = recentAccuracy,
            lifetimeAccuracy = lifetimeAccuracy,
            difficultyPerformance = difficultyPerformance,
            retention = retention,
            speed = speed,
            confidenceCalibration = confidenceCalibration
        )

        return ConceptMastery(
            studentId = studentId,
            conceptId = conceptId,
            mastery = mastery,
            confidence = 0.0,
            recentAccuracy = recentAccuracy,
            lifetimeAccuracy = lifetimeAccuracy,
            difficultyAdjustedAccuracy = difficultyPerformance,
            medianTimeSeconds = medianTime,
            attemptCount = attemptCount,
            lastSeen = lastSeen,
            lastMastered = if (mastery >= MASTERY_THRESHOLD) lastSeen else null,
            forgettingRisk = RetentionEngine.forgettingRisk(lastSeen, mastery),
            errorRate = 1.0 - lifetimeAccuracy,
            learnedAt = attemptsSortedAscending.first().timestamp,
            successfulRecallCount = attemptsSortedAscending.count { it.correct },
            failedRecallCount = attemptsSortedAscending.count { !it.correct },
            lastUpdated = System.currentTimeMillis()
        )
    }

    private fun renormalizedMastery(
        recentAccuracy: Double,
        lifetimeAccuracy: Double,
        difficultyPerformance: Double?,
        retention: Double,
        speed: Double,
        confidenceCalibration: Double?
    ): Double {
        val components = mutableListOf(
            W_RECENT to recentAccuracy,
            W_LIFETIME to lifetimeAccuracy,
            W_RETENTION to retention,
            W_SPEED to speed
        )
        difficultyPerformance?.let { components.add(W_DIFFICULTY to it) }
        confidenceCalibration?.let { components.add(W_CONFIDENCE to it) }

        val weightSum = components.sumOf { it.first }
        return components.sumOf { it.first * it.second } / weightSum
    }

    /**
     * No external timing baseline exists anywhere in this codebase (no per-question
     * expected-time field, no exam-wide pacing constant), so this is a flat heuristic
     * tuned for single-best-answer NEET/JEE-style MCQs, not a blueprint-specified
     * formula. Validate against real score correlation before trusting it — same
     * caveat the blueprint gives for the mastery formula as a whole.
     */
    private fun speedScore(medianTimeSeconds: Double?): Double = when {
        medianTimeSeconds == null -> 0.5 // no timing data — neutral, not penalized
        medianTimeSeconds <= 60.0 -> 1.0
        medianTimeSeconds <= 120.0 -> 0.7
        medianTimeSeconds <= 180.0 -> 0.4
        else -> 0.2
    }

    private fun median(values: List<Int>): Double? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2.0 else sorted[mid].toDouble()
    }
}
