package com.checkmate.learning.student

import android.content.Context
import android.util.Log
import com.checkmate.learning.engine.MasteryEngine
import com.checkmate.learning.engine.RetentionEngine
import com.checkmate.learning.graph.KnowledgeGraph
import com.checkmate.learning.model.ConceptSnapshot
import com.checkmate.learning.model.ErrorPatternSnapshot
import com.checkmate.learning.model.LearningIds
import com.checkmate.learning.model.OverallLearningState
import com.checkmate.learning.model.PrerequisiteIssue
import com.checkmate.learning.model.RetentionDecisionSnapshot
import com.checkmate.learning.model.StudentModel
import com.checkmate.learning.repository.LearningDatabase

/**
 * Builds a [StudentModel] snapshot from current Room state.
 *
 * ARCHITECTURE (this shape was reviewed and refined against an external design pass
 * before being built, not assumed from scratch):
 * ```
 * MasteryEngine   -> ConceptMastery      \
 * ErrorEngine     -> ErrorPattern         |
 * RetentionEngine -> RetentionDecision    +-> StudentModelBuilder -> StudentModel
 * KnowledgeGraph  -> PrerequisiteIssue    /
 * ```
 * This object AGGREGATES already-derived intelligence; it does NOT become another
 * intelligence engine. Concretely, it never:
 * - computes mastery itself (that's [MasteryEngine])
 * - decides REVIEW/TEACH/MOVE_ON itself (that's [RetentionEngine.decide])
 * - diagnoses prerequisite failures itself (that's
 *   [KnowledgeGraph.diagnosePrerequisiteFailure])
 * It only reads their already-persisted/computed output and reshapes it into
 * [StudentModel]'s flatter, engine-agnostic shape, so Mentor/Planner/Analytics can
 * all read the SAME student state instead of each independently querying
 * MasteryDao/ErrorDao and drifting apart.
 *
 * DELIBERATELY NOT cached/persisted here — see [StudentModel]'s own class doc for
 * why. Call [build] fresh whenever a consumer needs current state; don't hold a
 * built [StudentModel] across a write to LearningDatabase and expect it to still be
 * accurate.
 *
 * SCALE NOTE: batches concept lookups via [com.checkmate.learning.repository.ConceptDao.getByIds]
 * and error-pattern lookups via a single ErrorPatternDao.getAll call, rather than
 * one query per concept — the "don't load the entire knowledge graph for every
 * StudentModel" caution from the reviewed design. Prerequisite diagnosis is the one
 * exception: it still costs one [KnowledgeGraph.diagnosePrerequisiteFailure] call per
 * WEAK concept, matching that function's own existing per-concept query shape (it
 * wasn't changed by this PR). Fine at today's data scale (tens of weak concepts, not
 * thousands) — batch that path too if/when a student's weak-concept count grows
 * large enough for it to matter.
 *
 * TESTING NOTE: like MasteryEngine/ErrorEngine/RetentionEngine, this repo has no
 * Robolectric/instrumented-test setup (confirmed against the existing test suite),
 * so [build] itself — which touches [LearningDatabase.getInstance] directly, same as
 * every other engine's suspend entry point — isn't unit-tested here, consistent with
 * those engines' own precedent of only unit-testing their pure/DB-free functions.
 * Add an instrumented test (or inject the DAOs) if/when this repo grows that infra.
 */
object StudentModelBuilder {

    private const val TAG = "StudentModelBuilder"

    suspend fun build(
        context: Context,
        studentId: String = LearningIds.LOCAL_STUDENT_ID
    ): StudentModel {
        val db = LearningDatabase.getInstance(context)
        val generatedAt = System.currentTimeMillis()

        val masteries = db.masteryDao().getAll(studentId)
        if (masteries.isEmpty()) {
            return StudentModel(
                studentId = studentId,
                generatedAt = generatedAt,
                overall = OverallLearningState(
                    conceptsTracked = 0,
                    conceptsMastered = 0,
                    conceptsWeak = 0,
                    averageMastery = 0.0,
                    totalAttempts = 0,
                    unresolvedErrorCount = 0
                ),
                concepts = emptyMap(),
                unresolvedErrors = emptyList(),
                weakPrerequisites = emptyList()
            )
        }

        // Batched: one query for every concept's display fields, not one per row.
        val conceptIds = masteries.map { it.conceptId }
        val conceptsById = db.conceptDao().getByIds(conceptIds).associateBy { it.id }

        // Batched: one query for every ErrorPattern this student has (resolved +
        // unresolved), folded two ways — a per-concept lifetime errorCount, and the
        // unresolved subset for StudentModel.unresolvedErrors.
        val allPatterns = db.errorPatternDao().getAll(studentId)
        val errorCountByConcept: Map<String, Int> = allPatterns
            .groupBy { it.conceptId }
            .mapValues { (_, patterns) -> patterns.sumOf { it.occurrences } }
        val unresolvedPatterns = allPatterns.filter { !it.resolved }

        val weakConceptIds = masteries
            .filter { it.mastery < MasteryEngine.MASTERY_THRESHOLD }
            .map { it.conceptId }

        val prerequisiteIssuesByConcept = mutableMapOf<String, List<String>>()
        for (conceptId in weakConceptIds) {
            val weakPrereqs = KnowledgeGraph.diagnosePrerequisiteFailure(
                context = context,
                studentId = studentId,
                conceptId = conceptId
            )
            if (weakPrereqs.isNotEmpty()) {
                prerequisiteIssuesByConcept[conceptId] = weakPrereqs.map { it.id }
            }
        }

        val concepts = masteries.associate { mastery ->
            val concept = conceptsById[mastery.conceptId]
            mastery.conceptId to ConceptSnapshot(
                conceptId = mastery.conceptId,
                exam = concept?.exam,
                subject = concept?.subject,
                chapter = concept?.chapter,
                topic = concept?.topic,
                mastery = mastery.mastery,
                masteryConfidence = mastery.confidence,
                retentionDecision = RetentionEngine
                    .decide(mastery.mastery, mastery.forgettingRisk)
                    .toSnapshot(),
                forgettingRisk = mastery.forgettingRisk,
                attemptCount = mastery.attemptCount,
                recentAccuracy = mastery.recentAccuracy,
                lifetimeAccuracy = mastery.lifetimeAccuracy,
                errorCount = errorCountByConcept[mastery.conceptId] ?: 0,
                lastSeen = mastery.lastSeen,
                prerequisiteIssues = prerequisiteIssuesByConcept[mastery.conceptId] ?: emptyList()
            )
        }

        val overall = OverallLearningState(
            conceptsTracked = masteries.size,
            conceptsMastered = masteries.count { it.mastery >= MasteryEngine.MASTERY_THRESHOLD },
            conceptsWeak = weakConceptIds.size,
            averageMastery = masteries.sumOf { it.mastery } / masteries.size,
            totalAttempts = masteries.sumOf { it.attemptCount },
            unresolvedErrorCount = unresolvedPatterns.sumOf { it.occurrences }
        )

        val model = StudentModel(
            studentId = studentId,
            generatedAt = generatedAt,
            overall = overall,
            concepts = concepts,
            unresolvedErrors = unresolvedPatterns.map {
                ErrorPatternSnapshot(
                    conceptId = it.conceptId,
                    errorType = it.errorType.name,
                    occurrences = it.occurrences,
                    firstSeen = it.firstSeen,
                    lastSeen = it.lastSeen
                )
            },
            weakPrerequisites = prerequisiteIssuesByConcept.map { (conceptId, weakIds) ->
                PrerequisiteIssue(conceptId, weakIds)
            }
        )

        Log.d(
            TAG,
            "Built StudentModel for student=$studentId: ${model.concepts.size} concept(s), " +
                "${model.unresolvedErrors.size} unresolved error pattern(s), " +
                "${model.weakPrerequisites.size} prerequisite issue(s)"
        )
        return model
    }

    private fun RetentionEngine.RetentionDecision.toSnapshot(): RetentionDecisionSnapshot = when (this) {
        RetentionEngine.RetentionDecision.REVIEW -> RetentionDecisionSnapshot.REVIEW
        RetentionEngine.RetentionDecision.TEACH -> RetentionDecisionSnapshot.TEACH
        RetentionEngine.RetentionDecision.MOVE_ON -> RetentionDecisionSnapshot.MOVE_ON
    }
}
