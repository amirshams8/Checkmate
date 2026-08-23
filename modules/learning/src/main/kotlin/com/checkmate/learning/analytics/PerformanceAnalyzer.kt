package com.checkmate.learning.analytics

import com.checkmate.core.PYQWeightage
import com.checkmate.learning.model.ConceptSnapshot
import com.checkmate.learning.model.StudentModel

/**
 * Upgrade Blueprint Phase 1 tree gap (`analytics/PerformanceAnalyzer.kt`), built as
 * step 1 of Phase 2 per the reviewed sequencing: "Finish analytics/ ... otherwise
 * ExpectedScore becomes a number floating above the rest of the system."
 *
 * Turns a [StudentModel] snapshot — already-derived mastery/retention/error
 * intelligence, see that class's own doc on why consumers build on it rather than
 * re-folding raw [com.checkmate.learning.model.LearningEvent]/
 * [com.checkmate.learning.model.QuestionAttempt] history themselves — into the
 * accuracy/trend/topic-impact evidence [ScoreGainEstimator] and [ScorePredictor]
 * consume next.
 *
 * DELIBERATE SCOPE LIMIT: this module produces EVIDENCE, not DECISIONS. No
 * expectedGain, no probability-of-improvement, no ranked "study this next" list —
 * that reasoning belongs to `LearningDecisionEngine`/[ScoreGainEstimator], per the
 * same "don't let this layer decide what the next layer should decide" boundary
 * [StudentModel] already draws around itself.
 *
 * SUBJECT-RESOLUTION FIX (test-report wiring pass): [subjectAccuracy] used to
 * group on [ConceptSnapshot.subject] directly, which is null for essentially
 * every real-imported concept (see that field's own doc — TestResultNormalizer
 * never sets it). [toTopicImpact] already resolves a real subject via
 * [ConceptWeightage.resolveWeightage]'s fuzzy fallback; [subjectAccuracy] just
 * wasn't using it, so a real import's Physics/Chemistry/Biology questions could
 * be correctly attributed in [PerformanceReport.topicImpacts] and simultaneously
 * invisible in [PerformanceReport.subjectAccuracy] — the one place a student
 * actually wants to see "how did I do in each subject." Both now call the same
 * [ConceptSnapshot.resolvedSubject] helper, so a concept is excluded from
 * subjectAccuracy only when fuzzy resolution genuinely can't place it anywhere —
 * not merely because the raw import never carried a subject to begin with.
 *
 * WEIGHTAGE-CONFIDENCE PASS (this session — gates ScoreGainEstimator): [TopicImpact]
 * used to carry `weightagePercent` but silently drop the [PYQWeightage.Confidence]
 * that [ConceptWeightage.WeightageResolution] already resolves alongside it —
 * PYQWeightage's own class doc flagged this exact gap ("surfaced instead of
 * discarded so a downstream consumer (ScoreGainEstimator, eventually) can tell
 * 'well-evidenced' apart from 'single hand-typed guess'"). [toTopicImpact] now
 * carries `resolution.confidence` straight through instead of discarding it, so
 * [ScoreGainEstimator] can factor real evidence strength into its own confidence
 * label instead of re-deriving or guessing at it.
 */
object PerformanceAnalyzer {

    /**
     * Below this many attempts, `recentAccuracy` is too small a sample to trust as
     * a trend signal distinct from `lifetimeAccuracy` — same discipline
     * [com.checkmate.learning.engine.MasteryEngine]'s `RECENT_WINDOW = 10` already
     * implies, made explicit here for a caller that only wants "is this improving
     * or declining."
     */
    private const val MIN_ATTEMPTS_FOR_TREND = 4

    /**
     * `recentAccuracy - lifetimeAccuracy` deltas smaller than this are noise, not a
     * real trend — chosen as roughly one wrong-vs-right flip within a 10-question
     * recent window (`MasteryEngine.RECENT_WINDOW`), not derived from any external
     * source. Revisit once real trend-vs-actual-mock-outcome data exists to
     * calibrate against — same caveat the blueprint gives every heuristic in this
     * codebase.
     */
    private const val TREND_THRESHOLD = 0.08

    enum class PerformanceTrend { IMPROVING, DECLINING, STABLE, INSUFFICIENT_DATA }

    /**
     * One concept's exam-relevance picture. `marksAtStakeTotal` is the full
     * potential marks this topic represents if the paper matched PYQ history
     * exactly (see [ConceptWeightage.marksAtStake]); `marksAtStakeGap` is the
     * portion of that NOT yet secured (`marksAtStakeTotal * (1 - mastery)`) — the
     * number [ScoreGainEstimator] will rank candidates against, sorted descending
     * here so the highest-value gaps are already first. `weightageConfidence`
     * mirrors the [PYQWeightage.WeightageEntry] that `weightagePercent` came from
     * (see class doc's WEIGHTAGE-CONFIDENCE PASS note) — [PYQWeightage.Confidence.ESTIMATED]
     * (the safe default) when the underlying chapter never resolved at all.
     */
    data class TopicImpact(
        val conceptId: String,
        val subject: String?,
        val chapter: String?,
        val topic: String?,
        val mastery: Double,
        val attemptCount: Int,
        val weightagePercent: Float,
        val weightageConfidence: PYQWeightage.Confidence,
        val marksAtStakeTotal: Double,
        val marksAtStakeGap: Double,
        val trend: PerformanceTrend,
        val trendDelta: Double
    )

    data class SubjectAccuracy(
        val subject: String,
        val recentAccuracy: Double,
        val lifetimeAccuracy: Double,
        val attemptCount: Int,
        val trend: PerformanceTrend
    )

    data class PerformanceReport(
        val studentId: String,
        val examType: String,
        val generatedAt: Long,
        /** Attempt-count-weighted per subject, sorted by attemptCount descending.
         *  A concept is excluded from this rollup only when
         *  [ConceptWeightage.resolveWeightage]'s fuzzy fallback can't place it
         *  under any subject at all — NOT merely because the raw import never
         *  carried a subject (see this class's own doc on the subject-resolution
         *  fix). It still appears in [topicImpacts] either way. */
        val subjectAccuracy: List<SubjectAccuracy>,
        /** Every attempted concept, sorted by [TopicImpact.marksAtStakeGap] descending —
         *  highest exam-value weakness first. */
        val topicImpacts: List<TopicImpact>,
        val overallTrend: PerformanceTrend,
        val overallTrendDelta: Double,
        val totalAttempts: Int
    )

    fun analyze(studentModel: StudentModel, examType: String): PerformanceReport {
        val concepts = studentModel.concepts.values.toList()

        val topicImpacts = concepts
            .map { it.toTopicImpact(examType) }
            .sortedByDescending { it.marksAtStakeGap }

        // Group by the SAME fuzzy-resolved subject toTopicImpact uses above, not
        // the raw ConceptSnapshot.subject — see class doc.
        val subjectAccuracy = concepts
            .filter { it.attemptCount > 0 }
            .mapNotNull { c -> c.resolvedSubject(examType)?.let { subject -> subject to c } }
            .groupBy({ it.first }, { it.second })
            .map { (subject, group) -> group.toSubjectAccuracy(subject) }
            .sortedByDescending { it.attemptCount }

        val totalAttempts = concepts.sumOf { it.attemptCount }
        val recentMean = weightedMean(concepts.map { it.recentAccuracy to it.attemptCount })
        val lifetimeMean = weightedMean(concepts.map { it.lifetimeAccuracy to it.attemptCount })
        val overallTrend = classifyTrend(recentMean, lifetimeMean, totalAttempts)

        return PerformanceReport(
            studentId = studentModel.studentId,
            examType = examType,
            generatedAt = System.currentTimeMillis(),
            subjectAccuracy = subjectAccuracy,
            topicImpacts = topicImpacts,
            overallTrend = overallTrend,
            overallTrendDelta = recentMean - lifetimeMean,
            totalAttempts = totalAttempts
        )
    }

    /**
     * Shared resolution step for both [toTopicImpact] and [analyze]'s
     * subjectAccuracy grouping — factored out so the two can never again silently
     * diverge on which subject a concept belongs to (that divergence was exactly
     * the bug this pass fixes).
     */
    private fun ConceptSnapshot.resolveAgainstWeightage(examType: String): ConceptWeightage.WeightageResolution {
        val chapterKey = chapter ?: topic ?: ""
        val topicKey = topic ?: chapter ?: ""
        return ConceptWeightage.resolveWeightage(examType, subject, chapterKey, topicKey)
    }

    private fun ConceptSnapshot.resolvedSubject(examType: String): String? =
        resolveAgainstWeightage(examType).subjectResolved

    private fun ConceptSnapshot.toTopicImpact(examType: String): TopicImpact {
        val resolution = resolveAgainstWeightage(examType)
        val stakeTotal = ConceptWeightage.marksAtStake(examType, resolution)
        val gap = stakeTotal * (1.0 - mastery)

        return TopicImpact(
            conceptId = conceptId,
            subject = resolution.subjectResolved ?: subject,
            chapter = chapter,
            topic = topic,
            mastery = mastery,
            attemptCount = attemptCount,
            weightagePercent = resolution.weightagePercent,
            weightageConfidence = resolution.confidence,
            marksAtStakeTotal = stakeTotal,
            marksAtStakeGap = gap,
            trend = classifyTrend(recentAccuracy, lifetimeAccuracy, attemptCount),
            trendDelta = recentAccuracy - lifetimeAccuracy
        )
    }

    private fun List<ConceptSnapshot>.toSubjectAccuracy(subject: String): SubjectAccuracy {
        val recent = weightedMean(map { it.recentAccuracy to it.attemptCount })
        val lifetime = weightedMean(map { it.lifetimeAccuracy to it.attemptCount })
        val attempts = sumOf { it.attemptCount }
        return SubjectAccuracy(
            subject = subject,
            recentAccuracy = recent,
            lifetimeAccuracy = lifetime,
            attemptCount = attempts,
            trend = classifyTrend(recent, lifetime, attempts)
        )
    }

    /** Pure, testable — no DB/context. Exposed for PerformanceAnalyzerTest. */
    fun classifyTrend(recentAccuracy: Double, lifetimeAccuracy: Double, attemptCount: Int): PerformanceTrend {
        if (attemptCount < MIN_ATTEMPTS_FOR_TREND) return PerformanceTrend.INSUFFICIENT_DATA
        val delta = recentAccuracy - lifetimeAccuracy
        return when {
            delta >= TREND_THRESHOLD -> PerformanceTrend.IMPROVING
            delta <= -TREND_THRESHOLD -> PerformanceTrend.DECLINING
            else -> PerformanceTrend.STABLE
        }
    }

    /** Attempt-count-weighted mean; 0.0 when total weight is 0 (never divides by zero). */
    private fun weightedMean(values: List<Pair<Double, Int>>): Double {
        val totalWeight = values.sumOf { it.second }
        if (totalWeight <= 0) return 0.0
        return values.sumOf { it.first * it.second } / totalWeight
    }
}
