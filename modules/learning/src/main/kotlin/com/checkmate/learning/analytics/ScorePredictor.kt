package com.checkmate.learning.analytics

import com.checkmate.learning.model.RetentionDecisionSnapshot
import com.checkmate.learning.model.StudentModel

/**
 * Upgrade Blueprint Phase 2.3 ("ExpectedScore model").
 *
 * "Surface uncertainty honestly: Expected: 638, Range: 617–659, Target: 680,
 * Gap: 42, broken into bottleneck contributions (Biology retention +16, Physics
 * errors +13, Chemistry weak concepts +9, exam strategy +4). Label as 'estimated
 * performance' until validated against real results — no false precision."
 *
 * SCOPE: composes entirely on top of [PerformanceAnalyzer.TopicImpact] (evidence)
 * and [StudentModel] (already-derived intelligence) — same "reuse, don't
 * re-derive" discipline [ScoreGainEstimator] follows, and the exact composition
 * [PerformanceAnalyzer]'s own class doc anticipated when it named `ScorePredictor`
 * as a downstream consumer still to be built. Fully deterministic, no LLM call.
 *
 * BOTTOM-UP EXPECTED-SCORE MODEL: for each subject in the exam,
 * `subjectTotalMarks` (from [ConceptWeightage.totalMarks]/[ConceptWeightage.subjectSharePercent])
 * splits into `securedMarks` (mastery × marksAtStakeTotal, summed over every
 * tracked topic in that subject — marks the student's own attempt history
 * supports) plus an `estimatedUnaccountedMarks` share for the portion of the
 * subject's PYQ-weighted syllabus with no tracked concept at all, valued at that
 * subject's own attempt-weighted average mastery (or the whole-student
 * [com.checkmate.learning.model.OverallLearningState.averageMastery] when the
 * subject has no tracked topics whatsoever) — the best available signal, not
 * invented. `expected` is the sum across subjects.
 *
 * HONEST GAP: the unaccounted-portion estimate is explicitly the weakest part of
 * this model — a subject the student has barely touched contributes almost
 * entirely from this fallback, not from real evidence. `rangeLow`/`rangeHigh`
 * widen proportionally to how much of `expected` rests on that fallback versus how
 * thin the attempt data backing `securedMarks` is (mirrors [ScoreGainEstimator]'s
 * own `dataConfidence` saturation-at-10-attempts convention, kept as a local
 * constant here for the same "not worth a cross-file import for one number"
 * reason [ScoreGainEstimator] itself gives for not importing
 * `MasteryEngine.RECENT_WINDOW`). None of this has been validated against real
 * mock-result outcomes yet — that validation, not intuition, is the bar for
 * retuning [UNACCOUNTED_UNCERTAINTY]/[SECURED_UNCERTAINTY], same discipline the
 * blueprint already applies to every heuristic in this codebase.
 *
 * BOTTLENECK DECOMPOSITION: every weak topic (mastery below [WEAK_MASTERY_THRESHOLD])
 * with a positive [PerformanceAnalyzer.TopicImpact.marksAtStakeGap] is bucketed
 * into exactly one mechanism — [BottleneckMechanism.ERRORS] when its
 * [com.checkmate.learning.model.ConceptSnapshot.errorCount] is heavy,
 * [BottleneckMechanism.RETENTION] when [RetentionDecisionSnapshot.REVIEW] applies
 * (something once learned is now at risk), else [BottleneckMechanism.WEAK_CONCEPTS]
 * (never secured to begin with) — grouped by (subject, mechanism), summed, and
 * the top few shown ranked by size, matching the blueprint's own worked example
 * shape ("Biology retention +16, Physics errors +13, ..."). Whatever concept-level
 * shortfall isn't covered by the top buckets is folded into one final
 * [BottleneckMechanism.OTHER] "exam strategy & uncovered syllabus" line — this
 * codebase has no per-question guess-quality or pacing signal to decompose that
 * further (see [SubjectScoreCalculator] — it tallies real marks per test, not
 * per-attempt reasoning quality), so it stays an explicit, undecomposed residual
 * rather than a guess dressed up as a mechanism. Bottlenecks always sum to exactly
 * [ExpectedScore.gap] — scaled down proportionally (see [predictFromReport]) if
 * concept-level shortfalls alone would exceed the target gap, so the breakdown
 * never overstates what's actually blocking the target.
 */
object ScorePredictor {

    /** Kept numerically in sync with [com.checkmate.learning.engine.MasteryEngine.MASTERY_THRESHOLD]
     *  by hand, not imported — same reasoning [com.checkmate.learning.engine.RetentionEngine]
     *  gives for its own `HIGH_MASTERY_THRESHOLD` copy. "Weak enough to be worth
     *  naming in a bottleneck breakdown," not a different definition of mastery. */
    private const val WEAK_MASTERY_THRESHOLD = 0.75

    /** Attempt count at which per-topic data confidence saturates to 1.0 — mirrors
     *  [ScoreGainEstimator.FULL_CONFIDENCE_ATTEMPTS] / `MasteryEngine.RECENT_WINDOW`,
     *  kept local for the same "not worth a cross-file import for one constant"
     *  call [ScoreGainEstimator] itself already makes. */
    private const val FULL_CONFIDENCE_ATTEMPTS = 10.0

    /** Below this many errors on a single concept, treat the wrongness as "not yet
     *  secured" rather than "a specific, repeated failure mode" — mirrors
     *  [com.checkmate.learning.engine.ErrorEngine]'s own "repeated errors become
     *  first-class ErrorPattern objects" framing (blueprint §1.6): a handful of
     *  isolated misses is just low mastery, a cluster is a distinct error pattern
     *  worth naming as its own bottleneck mechanism. */
    private const val ERROR_HEAVY_THRESHOLD = 3

    /** Uncertainty weight applied to the portion of a subject's expected marks
     *  resting entirely on the fallback-mastery estimate (no tracked concept at
     *  all) — the least-evidenced part of this model, so it swings the range the
     *  most. First-pass heuristic, not calibrated against real outcomes yet. */
    private const val UNACCOUNTED_UNCERTAINTY = 0.5

    /** Uncertainty weight applied to securely-tracked marks, scaled further by
     *  each subject's own data thinness (`1 - dataConfidence`) — real attempt
     *  history is trusted far more than the unaccounted fallback, hence the much
     *  smaller weight than [UNACCOUNTED_UNCERTAINTY]. */
    private const val SECURED_UNCERTAINTY = 0.15

    /** Blueprint's own worked example shows 3 concept-driven lines plus one
     *  residual ("exam strategy") — kept as the display cap here rather than
     *  showing every bucket, so the breakdown stays a "here's what's actually
     *  moving the needle" list, not a full topic dump (that's what "Biggest
     *  opportunities" / [ScoreGainEstimator] already is). */
    private const val MAX_RANKED_BOTTLENECKS = 3

    enum class BottleneckMechanism { RETENTION, ERRORS, WEAK_CONCEPTS, OTHER }

    /**
     * One line of the "why the gap is what it is" breakdown. `subject` is null
     * only for the [BottleneckMechanism.OTHER] residual line, which isn't
     * attributable to any single subject. `marks` is this line's share of
     * [ExpectedScore.gap] — every [ExpectedScore.bottlenecks] list's `marks`
     * values sum to `gap` exactly (see class doc's scaling note).
     */
    data class BottleneckContribution(
        val subject: String?,
        val mechanism: BottleneckMechanism,
        val label: String,
        val marks: Double
    )

    /**
     * Blueprint §2.3's own shape: `expected`/`rangeLow`/`rangeHigh` are this
     * model's honest estimate of total marks on the real exam pattern (see class
     * doc), `target` is passed in by the caller (from
     * [com.checkmate.core.ConsultationProfile.targetScore] in practice — this
     * object stays DB-free and testable, same as [ScoreGainEstimator]), and `gap`
     * is `target - expected` floored at zero (already-exceeding-target has no
     * gap to explain, not a negative one). LABEL EVERYWHERE THIS IS SHOWN AS
     * "estimated performance," per the blueprint's own explicit caution — this
     * is a first-pass heuristic model, not a validated predictor.
     */
    data class ExpectedScore(
        val examType: String,
        val generatedAt: Long,
        val expected: Double,
        val rangeLow: Double,
        val rangeHigh: Double,
        val target: Int,
        val gap: Double,
        val bottlenecks: List<BottleneckContribution>
    )

    /** Runs [PerformanceAnalyzer.analyze] itself — see [ScoreGainEstimator.rank]'s
     *  own doc for why this convenience overload exists alongside [predictFromReport]. */
    fun predict(studentModel: StudentModel, examType: String, targetScore: Int): ExpectedScore {
        val report = PerformanceAnalyzer.analyze(studentModel, examType)
        return predictFromReport(report, studentModel, targetScore)
    }

    /** Same as [predict] but against an already-built report — for a caller (e.g.
     *  `TestResultsViewModel`) that already has one from the same Room snapshot,
     *  so `expected`/`scoreGainEstimates` can never silently diverge. */
    fun predictFromReport(
        report: PerformanceAnalyzer.PerformanceReport,
        studentModel: StudentModel,
        targetScore: Int
    ): ExpectedScore {
        val examType = report.examType
        val subjects = ConceptWeightage.subjectsForExam(examType)
        val totalMarks = ConceptWeightage.totalMarks(examType).toDouble()

        var expected = 0.0
        var halfWidth = 0.0

        for (subject in subjects) {
            val (subjExpected, subjUncertainty) = subjectExpectedAndUncertainty(
                subject, examType, report, studentModel
            )
            expected += subjExpected
            halfWidth += subjUncertainty
        }

        val rangeLow = (expected - halfWidth).coerceIn(0.0, totalMarks)
        val rangeHigh = (expected + halfWidth).coerceIn(0.0, totalMarks)
        val gap = (targetScore - expected).coerceAtLeast(0.0)

        val bottlenecks = if (gap > 0.0) bottlenecks(report, studentModel, gap) else emptyList()

        return ExpectedScore(
            examType = examType,
            generatedAt = System.currentTimeMillis(),
            expected = expected,
            rangeLow = rangeLow,
            rangeHigh = rangeHigh,
            target = targetScore,
            gap = gap,
            bottlenecks = bottlenecks
        )
    }

    /**
     * `subjectTotalMarks` splits into secured (real evidence) + estimated-unaccounted
     * (fallback mastery on the syllabus share nothing has been attempted for yet)
     * — see class doc. Returns 0.0/0.0 uncertainty contribution only in the
     * degenerate case of an exam with no configured share for this subject
     * ([ConceptWeightage.subjectSharePercent] returning 0f — shouldn't happen for
     * a subject [ConceptWeightage.subjectsForExam] itself returned, kept as a
     * defensive floor rather than a crash).
     */
    private fun subjectExpectedAndUncertainty(
        subject: String,
        examType: String,
        report: PerformanceAnalyzer.PerformanceReport,
        studentModel: StudentModel
    ): Pair<Double, Double> {
        val subjectTotalMarks = ConceptWeightage.totalMarks(examType) *
            (ConceptWeightage.subjectSharePercent(examType, subject) / 100.0)
        if (subjectTotalMarks <= 0.0) return 0.0 to 0.0

        val topicsInSubject = report.topicImpacts.filter { it.subject == subject }

        val securedMarks = topicsInSubject.sumOf { it.mastery * it.marksAtStakeTotal }
        val accountedMarks = topicsInSubject.sumOf { it.marksAtStakeTotal }
        val unaccountedMarks = (subjectTotalMarks - accountedMarks).coerceAtLeast(0.0)

        val fallbackMastery = if (topicsInSubject.isNotEmpty()) {
            val totalAttempts = topicsInSubject.sumOf { it.attemptCount }
            if (totalAttempts > 0) {
                topicsInSubject.sumOf { it.mastery * it.attemptCount } / totalAttempts
            } else {
                topicsInSubject.map { it.mastery }.average()
            }
        } else {
            studentModel.overall.averageMastery
        }

        val estimatedUnaccountedMarks = unaccountedMarks * fallbackMastery
        val subjectExpected = securedMarks + estimatedUnaccountedMarks

        val dataConfidence = if (topicsInSubject.isNotEmpty()) {
            val totalAttempts = topicsInSubject.sumOf { it.attemptCount }
            if (totalAttempts > 0) {
                (totalAttempts.toDouble() / FULL_CONFIDENCE_ATTEMPTS).coerceIn(0.0, 1.0)
            } else {
                0.0
            }
        } else {
            0.0
        }

        val uncertainty = unaccountedMarks * UNACCOUNTED_UNCERTAINTY +
            securedMarks * (1.0 - dataConfidence) * SECURED_UNCERTAINTY

        return subjectExpected to uncertainty
    }

    /**
     * Ranked (subject, mechanism) buckets summed from every weak topic's
     * [PerformanceAnalyzer.TopicImpact.marksAtStakeGap], top [MAX_RANKED_BOTTLENECKS]
     * shown plus one [BottleneckMechanism.OTHER] residual — see class doc.
     * Scales every bucket (including the residual) down proportionally when the
     * raw concept-level shortfall total exceeds `gap`, so the breakdown always
     * sums to exactly `gap` rather than double-counting marks the target doesn't
     * actually need recovered (e.g. a modest target that sits well inside the
     * full syllabus's total shortfall).
     */
    private fun bottlenecks(
        report: PerformanceAnalyzer.PerformanceReport,
        studentModel: StudentModel,
        gap: Double
    ): List<BottleneckContribution> {
        data class Bucket(val subject: String?, val mechanism: BottleneckMechanism, val marks: Double)

        val raw = report.topicImpacts
            .filter { it.mastery < WEAK_MASTERY_THRESHOLD && it.marksAtStakeGap > 0.0 }
            .mapNotNull { impact ->
                val snapshot = studentModel.concepts[impact.conceptId] ?: return@mapNotNull null
                val mechanism = when {
                    snapshot.errorCount >= ERROR_HEAVY_THRESHOLD -> BottleneckMechanism.ERRORS
                    snapshot.retentionDecision == RetentionDecisionSnapshot.REVIEW -> BottleneckMechanism.RETENTION
                    else -> BottleneckMechanism.WEAK_CONCEPTS
                }
                Bucket(impact.subject, mechanism, impact.marksAtStakeGap)
            }

        val grouped = raw
            .groupBy { it.subject to it.mechanism }
            .map { (key, group) -> Bucket(key.first, key.second, group.sumOf { it.marks }) }
            .sortedByDescending { it.marks }

        val topBuckets = grouped.take(MAX_RANKED_BOTTLENECKS)
        val topSum = topBuckets.sumOf { it.marks }

        fun Bucket.toContribution(marks: Double) = BottleneckContribution(
            subject = subject,
            mechanism = mechanism,
            label = if (subject != null) "$subject ${mechanismLabel(mechanism)}" else mechanismLabel(mechanism),
            marks = marks
        )

        return if (topSum >= gap) {
            // Concept-level shortfall alone already covers (or exceeds) the target
            // gap — scale down proportionally so the shown lines sum to exactly
            // `gap`, not a bigger number the target doesn't call for. No residual
            // line: there's nothing left over to attribute to it.
            val scale = if (topSum > 0.0) gap / topSum else 0.0
            topBuckets.map { it.toContribution(it.marks * scale) }
        } else {
            val residual = gap - topSum
            topBuckets.map { it.toContribution(it.marks) } +
                BottleneckContribution(
                    subject = null,
                    mechanism = BottleneckMechanism.OTHER,
                    label = "Exam strategy & uncovered syllabus",
                    marks = residual
                )
        }
    }

    /** Display label for a [BottleneckMechanism] — "Biology retention" / "Physics
     *  errors" / "Chemistry weak concepts" per the blueprint's own worked example. */
    private fun mechanismLabel(mechanism: BottleneckMechanism): String = when (mechanism) {
        BottleneckMechanism.RETENTION -> "retention"
        BottleneckMechanism.ERRORS -> "errors"
        BottleneckMechanism.WEAK_CONCEPTS -> "weak concepts"
        BottleneckMechanism.OTHER -> "exam strategy & uncovered syllabus"
    }
}
