package com.checkmate.learning.analytics

import com.checkmate.core.PYQWeightage
import com.checkmate.learning.model.ConceptSnapshot
import com.checkmate.learning.model.RetentionDecisionSnapshot
import com.checkmate.learning.model.StudentModel

/**
 * Upgrade Blueprint Phase 2.2 ("ScoreGainEstimator").
 *
 * expectedGain = probabilityOfImprovement × marksAtStake × examRelevance
 *              × retentionBenefit ÷ timeCost
 *
 * SCOPE: the first layer downstream of [PerformanceAnalyzer] allowed to produce a
 * DECISION-shaped number — PerformanceAnalyzer's own class doc draws that boundary
 * deliberately ("this module produces EVIDENCE, not DECISIONS... that reasoning
 * belongs to LearningDecisionEngine/ScoreGainEstimator"). Still fully deterministic:
 * no LLM call anywhere in this file, and every sub-term below is derived only from
 * data [StudentModel]/[PerformanceAnalyzer.TopicImpact] already carry. Nothing here
 * is invented or asked of an LLM — if a future pass wants an LLM-proposed
 * adjustment to any term, it goes through the same "LLM proposes, deterministic
 * layer validates" discipline the blueprint applies everywhere else (Phase 0 item
 * 2), not a blind multiply-in.
 *
 * TIME-COST / SESSION CONVENTION: [timeCostMinutes] is an estimated number of
 * focused-study minutes to meaningfully move a concept's mastery — a deterministic
 * heuristic (see its own doc), not a scheduler output. `expectedGain` is expressed
 * as "marks gained per [SESSION_MINUTES]-minute study block" at this concept's own
 * per-minute efficiency, so it reads the way the blueprint's own §2.1 example does
 * ("Repair rolling motion 45m +4.2 marks") rather than as a bare, unitless
 * marks-per-minute rate.
 *
 * HONEST GAP: every sub-term below is a first-pass heuristic, flagged as such in
 * its own doc — same discipline the blueprint already requires of MasteryEngine/
 * RetentionEngine. None have been validated against real mock-result outcomes yet;
 * that validation, not intuition, is the actual bar for retuning any weight here.
 * What matters right now is ranking behavior — does the most valuable concept
 * usually land near the top — not any single number's absolute precision.
 */
object ScoreGainEstimator {

    /** Blueprint §2.1's own worked example uses a 45-minute block; 30 minutes is
     *  kept here as a self-contained presentational constant rather than imported
     *  from `:modules:planner` (which `:modules:learning` doesn't depend on today)
     *  — a single display convention isn't worth introducing that module edge. */
    private const val SESSION_MINUTES = 30.0

    /** Below this many attempts, treat the concept's own mastery reading as too
     *  thin to be confident a repair session will move it — mirrors
     *  [PerformanceAnalyzer]'s `MIN_ATTEMPTS_FOR_TREND`, same "4 is the floor for
     *  any signal at all" convention used there. */
    private const val MIN_ATTEMPTS_FOR_CONFIDENCE = 4

    /** Attempt count at which data-confidence saturates to 1.0 — numerically
     *  matches [com.checkmate.learning.engine.MasteryEngine]'s `RECENT_WINDOW = 10`,
     *  kept as a local literal rather than an import (same reasoning
     *  [com.checkmate.learning.engine.RetentionEngine] gives for not depending on
     *  MasteryEngine over a single constant). */
    private const val FULL_CONFIDENCE_ATTEMPTS = 10.0

    private const val BASE_REPAIR_MINUTES = 20.0
    private const val MINUTES_PER_UNRESOLVED_ERROR = 3.0
    private const val MINUTES_PER_WEAK_PREREQUISITE = 15.0
    private const val MAX_TIME_COST_MINUTES = 120.0

    enum class EstimateConfidence { LOW, MEDIUM, HIGH }

    /**
     * One concept's fully-costed opportunity — [PerformanceAnalyzer.TopicImpact]
     * plus the four blueprint multipliers and the resulting rank. `expectedGain` is
     * what [rank]/[rankFromReport] sort on; every other field is kept so a caller
     * (UI, or later `LearningDecisionEngine`) can show its own reasoning instead of
     * a bare number — same "surface evidence, don't hide it behind one score"
     * discipline [PerformanceAnalyzer.TopicImpact] already follows.
     */
    data class ScoreGainEstimate(
        val conceptId: String,
        val subject: String?,
        val chapter: String?,
        val topic: String?,
        val mastery: Double,
        val marksAtStake: Double,
        val probabilityOfImprovement: Double,
        val examRelevance: Double,
        val retentionBenefit: Double,
        val timeCostMinutes: Double,
        /** Marks gained per [SESSION_MINUTES]-minute study block, at this
         *  concept's own [timeCostMinutes] efficiency — see class doc. */
        val expectedGain: Double,
        val confidence: EstimateConfidence
    )

    /**
     * Ranked, highest-`expectedGain` first. Runs [PerformanceAnalyzer.analyze]
     * itself since every input this needs (`marksAtStakeGap`, `weightageConfidence`)
     * lives on [PerformanceAnalyzer.TopicImpact]. A caller that already has a
     * [PerformanceAnalyzer.PerformanceReport] (e.g. to avoid recomputing it twice
     * in one screen) should call [rankFromReport] directly instead.
     */
    fun rank(studentModel: StudentModel, examType: String): List<ScoreGainEstimate> {
        val report = PerformanceAnalyzer.analyze(studentModel, examType)
        return rankFromReport(report, studentModel)
    }

    /** Same as [rank] but against an already-built report — see [rank]'s own doc. */
    fun rankFromReport(
        report: PerformanceAnalyzer.PerformanceReport,
        studentModel: StudentModel
    ): List<ScoreGainEstimate> {
        return report.topicImpacts
            .mapNotNull { impact -> studentModel.concepts[impact.conceptId]?.let { snapshot -> estimate(impact, snapshot) } }
            .sortedByDescending { it.expectedGain }
    }

    private fun estimate(
        impact: PerformanceAnalyzer.TopicImpact,
        snapshot: ConceptSnapshot
    ): ScoreGainEstimate {
        val marksAtStake = impact.marksAtStakeGap
        val probabilityOfImprovement = probabilityOfImprovement(snapshot)
        val examRelevance = examRelevance(impact)
        val retentionBenefit = retentionBenefit(snapshot)
        val timeCost = timeCostMinutes(snapshot)

        val rawValue = probabilityOfImprovement * marksAtStake * examRelevance * retentionBenefit
        val expectedGain = if (timeCost <= 0.0) 0.0 else (rawValue / timeCost) * SESSION_MINUTES

        return ScoreGainEstimate(
            conceptId = impact.conceptId,
            subject = impact.subject,
            chapter = impact.chapter,
            topic = impact.topic,
            mastery = impact.mastery,
            marksAtStake = marksAtStake,
            probabilityOfImprovement = probabilityOfImprovement,
            examRelevance = examRelevance,
            retentionBenefit = retentionBenefit,
            timeCostMinutes = timeCost,
            expectedGain = expectedGain,
            confidence = confidence(snapshot, impact)
        )
    }

    /**
     * `headroom × dataConfidence`. Headroom (`1 - mastery`) is how much room there
     * is left to gain; dataConfidence (attempt count scaled against
     * [FULL_CONFIDENCE_ATTEMPTS]) is how much that headroom reading itself should
     * be trusted — a concept seen twice at 20% mastery has identical headroom to
     * one seen twenty times at 20%, but far less evidence that 20% is real rather
     * than noise. Below [MIN_ATTEMPTS_FOR_CONFIDENCE] attempts, dataConfidence
     * floors at 0.2 rather than 0 — a barely-attempted-but-genuinely-weak concept
     * should be heavily discounted, not erased from ranking outright; [confidence]
     * is what surfaces the "barely attempted" caveat to a caller, not this term.
     */
    private fun probabilityOfImprovement(snapshot: ConceptSnapshot): Double {
        val headroom = (1.0 - snapshot.mastery).coerceIn(0.0, 1.0)
        val rawDataConfidence = (snapshot.attemptCount / FULL_CONFIDENCE_ATTEMPTS).coerceIn(0.0, 1.0)
        val dataConfidence = if (snapshot.attemptCount < MIN_ATTEMPTS_FOR_CONFIDENCE) {
            rawDataConfidence.coerceAtLeast(0.2)
        } else {
            rawDataConfidence
        }
        return (headroom * dataConfidence).coerceIn(0.0, 1.0)
    }

    /**
     * [PerformanceAnalyzer.TopicImpact.weightagePercent] scaled to 0..1 — already 0
     * for a [ConceptWeightage.ResolutionMethod.UNRESOLVED] chapter (see that
     * class's "no signal still needs a value, not a guess" contract), which
     * correctly zeroes `expectedGain` for a concept this codebase has no PYQ
     * evidence for, rather than guessing at its exam relevance.
     */
    private fun examRelevance(impact: PerformanceAnalyzer.TopicImpact): Double =
        (impact.weightagePercent / 100.0).coerceIn(0.0, 1.0)

    /**
     * Reuses Blueprint §1.7's REVIEW/TEACH/MOVE_ON split rather than re-deriving
     * it: a concept still needing TEACH gets full retention benefit (nothing to
     * decay yet — it was never learned, so "is it still known" doesn't apply); a
     * concept flagged for REVIEW gets benefit scaled by its own `forgettingRisk`
     * (higher risk = more value in acting now, before it's lost); MOVE_ON gets a
     * low floor rather than zero — some marginal value remains in over-learning an
     * already-secure concept, just far less than repairing or reviewing one.
     */
    private fun retentionBenefit(snapshot: ConceptSnapshot): Double = when (snapshot.retentionDecision) {
        RetentionDecisionSnapshot.TEACH -> 1.0
        RetentionDecisionSnapshot.REVIEW -> (0.6 + 0.4 * snapshot.forgettingRisk).coerceIn(0.0, 1.0)
        RetentionDecisionSnapshot.MOVE_ON -> 0.25
    }

    /**
     * Minutes of focused study estimated to meaningfully move this concept — NOT a
     * scheduler output, a rough deterministic proxy: a flat base (re-reading/
     * re-practicing the core idea) plus more time per unresolved error (each wrong
     * answer this concept has produced is a distinct failure mode that may need its
     * own attention — see [com.checkmate.learning.engine.ErrorEngine]) plus a
     * larger per-item cost for each weak prerequisite (fixing a prerequisite gap is
     * itself a sub-lesson, not just more practice on the target concept). Capped at
     * [MAX_TIME_COST_MINUTES] so one badly-tangled concept can't drag its own
     * expectedGain toward zero and vanish from a ranked list entirely.
     */
    private fun timeCostMinutes(snapshot: ConceptSnapshot): Double {
        val raw = BASE_REPAIR_MINUTES +
            snapshot.errorCount * MINUTES_PER_UNRESOLVED_ERROR +
            snapshot.prerequisiteIssues.size * MINUTES_PER_WEAK_PREREQUISITE
        return raw.coerceIn(BASE_REPAIR_MINUTES, MAX_TIME_COST_MINUTES)
    }

    /**
     * Rolls up the same two honesty signals already tracked elsewhere in this file
     * — how much attempt data backs the mastery reading, and how much the
     * underlying PYQ weightage itself should be trusted (see
     * [PerformanceAnalyzer.TopicImpact.weightageConfidence]'s own doc) — into one
     * label a UI can show next to the number: "well-evidenced" apart from
     * "single hand-typed guess," per [PYQWeightage.Confidence]'s own framing.
     */
    private fun confidence(snapshot: ConceptSnapshot, impact: PerformanceAnalyzer.TopicImpact): EstimateConfidence {
        val dataThin = snapshot.attemptCount < MIN_ATTEMPTS_FOR_CONFIDENCE
        val weightageWeak = impact.weightageConfidence == PYQWeightage.Confidence.ESTIMATED
        val weightageStrong = impact.weightageConfidence == PYQWeightage.Confidence.HIGH
        return when {
            dataThin -> EstimateConfidence.LOW
            weightageWeak -> EstimateConfidence.MEDIUM
            weightageStrong -> EstimateConfidence.HIGH
            else -> EstimateConfidence.MEDIUM
        }
    }
}
