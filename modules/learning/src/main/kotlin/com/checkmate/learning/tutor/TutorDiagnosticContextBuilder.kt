package com.checkmate.learning.tutor

import android.content.Context
import com.checkmate.learning.repository.LearningDatabase
import com.checkmate.learning.student.StudentModelBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Upgrade Blueprint Phase 3, P3.1 — assembles [TutorDiagnosticContext] from already-derived
 * intelligence ([com.checkmate.learning.model.StudentModel] +
 * [com.checkmate.learning.model.ErrorRecord] history), same "aggregates, doesn't
 * re-derive" discipline [com.checkmate.learning.student.StudentModelBuilder] itself
 * follows one layer down. The only place in this package that touches Room/Context for
 * P3.1 — [TutorDiagnosticValidator] stays pure, matching the "engine tested pure, builder
 * wraps it with I/O" split [TutorStateMachine]/[TutorSessionLedger] already model.
 */
object TutorDiagnosticContextBuilder {

    /** Caps how many recent [com.checkmate.learning.model.ErrorRecord] rows go into a
     *  diagnosis prompt — enough for an LLM to spot a pattern without unbounded growth for
     *  a concept with a long error history. Not calibrated against real prompt-quality
     *  outcomes — first-pass constant, flagged rather than pretended-derived, same honesty
     *  [TutorStateMachine.MAX_CYCLES] and [TutorStateMachine.MIN_PRACTICE_ATTEMPTS] apply
     *  to their own first-pass constants. */
    private const val MAX_RECENT_ERRORS = 10

    /**
     * Returns null when [conceptId] has no [com.checkmate.learning.model.ConceptSnapshot]
     * at all — i.e. genuinely never attempted (the same
     * [com.checkmate.learning.engine.LearningDecisionEngine.LearningInterventionIntent.START_DIAGNOSTIC]
     * case [TutorDiagnostics.diagnose] itself treats as "no evidence exists, [UNKNOWN] is
     * the only honest answer"). Deliberately NOT building a context with zeroed-out fields
     * for that case — asking an LLM to hypothesize about a concept with literally zero
     * evidence would produce a confident-sounding guess dressed up as a diagnosis, exactly
     * what [TutorDiagnostics]'s own doc already warns against. A caller getting null here
     * should fall back to [TutorDiagnostics.diagnose] with a null snapshot, same as today.
     */
    suspend fun build(
        context: Context,
        conceptId: String,
        tutorState: TutorState,
        cycleCount: Int
    ): TutorDiagnosticContext? {
        val studentModel = withContext(Dispatchers.IO) { StudentModelBuilder.build(context) }
        val snapshot = studentModel.concepts[conceptId] ?: return null

        val recentErrors = withContext(Dispatchers.IO) {
            LearningDatabase.getInstance(context).errorRecordDao().getByConcept(conceptId)
        }
            .sortedByDescending { it.timestamp }
            .take(MAX_RECENT_ERRORS)
            .map { EvidenceErrorRef(questionId = it.questionId, errorType = it.errorType.name, timestamp = it.timestamp) }

        val weakPrerequisites = studentModel.weakPrerequisites
            .firstOrNull { it.conceptId == conceptId }
            ?.weakPrerequisites
            ?: emptyList()

        return TutorDiagnosticContext(
            conceptId = conceptId,
            exam = snapshot.exam,
            subject = snapshot.subject,
            chapter = snapshot.chapter,
            topic = snapshot.topic,
            mastery = snapshot.mastery,
            retentionDecision = snapshot.retentionDecision,
            forgettingRisk = snapshot.forgettingRisk,
            attemptCount = snapshot.attemptCount,
            recentAccuracy = snapshot.recentAccuracy,
            lifetimeAccuracy = snapshot.lifetimeAccuracy,
            errorCount = snapshot.errorCount,
            recentErrors = recentErrors,
            weakPrerequisites = weakPrerequisites,
            tutorState = tutorState,
            cycleCount = cycleCount
        )
    }
}
