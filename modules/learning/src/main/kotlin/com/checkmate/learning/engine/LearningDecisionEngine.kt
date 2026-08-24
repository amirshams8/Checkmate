package com.checkmate.learning.engine

import com.checkmate.learning.analytics.PerformanceAnalyzer
import com.checkmate.learning.analytics.ScoreGainEstimator
import com.checkmate.learning.analytics.ScorePredictor
import com.checkmate.learning.model.ConceptSnapshot
import com.checkmate.learning.model.RetentionDecisionSnapshot
import com.checkmate.learning.model.StudentModel

/**
 * Upgrade Blueprint Phase 2.4 ("Extend the existing intervention pipeline, don't
 * replace it") — the actual decision layer everything upstream of this file has
 * been building evidence for.
 *
 * The question this file answers is deliberately narrow, per the blueprint's own
 * framing: "What should this student do NEXT?" — not "what's weak" (already
 * answered by [PerformanceAnalyzer]) and not "what's worth marks" (already
 * answered by [ScoreGainEstimator]/[ScorePredictor]). Those stay upstream,
 * EVIDENCE-shaped; this is the first (and, deliberately, only) file allowed to
 * turn that evidence into a ranked, typed action — same "don't let this layer
 * decide what the next layer should decide" boundary [PerformanceAnalyzer]'s own
 * class doc already draws for itself.
 *
 * DETERMINISTIC BY DESIGN — no LLM call anywhere in this file, and none should
 * ever be added. Per the blueprint's own architecture:
 * ```
 * deterministic engines -> CandidateInterventions -> LearningDecisionEngine
 *     -> ranked action -> LLM -> explanation / tutoring / wording
 * ```
 * The LLM becomes the teacher, not the decision-maker — it explains and delivers
 * whatever [CandidateIntervention] this engine already ranked first; it never
 * proposes or reorders candidates itself. If a future pass wants an LLM-proposed
 * adjustment to any ranking term below, that goes through the same "LLM proposes,
 * deterministic layer validates" discipline the blueprint applies everywhere else
 * (Phase 0 item 2), not a blind hand-off of the ranking itself.
 *
 * SCOPE OF THIS PASS: produces the ranked [CandidateIntervention] list only — the
 * "ranked action" box in the diagram above. Wiring these into the existing
 * behavior-intervention pipeline (`InterventionIntent -> ActionExecutor ->
 * PolicyValidator -> TaskEscrow -> TaskMutator -> TriggerEvaluator ->
 * InterventionTransaction -> Reconciliation`) as first-class, executable
 * `StudyTask`-mutating actions is Blueprint §2.5 ("make plan changes
 * transactional") — a separate, larger integration into `:modules:planner`'s
 * escrow/commit/rollback machinery, deliberately not started here. That pipeline
 * today only knows how to execute five narrow, already-audited behavior actions
 * (see [com.checkmate.planner.intervention.PermittedAction]); teaching it to
 * safely mutate a `StudyTask` into a `REPAIR_CONCEPT` session (create a task?
 * retarget an existing one? at what priority relative to what's already
 * scheduled?) is its own design pass with its own PolicyValidator rules, not an
 * incidental addition to this one. What's built here is the missing input that
 * pass will eventually consume — surfaced to the student directly in the
 * meantime (see `TestResultsViewModel`/`TestResultsScreen` wiring).
 *
 * INTENT TAXONOMY: exactly the eight intents Blueprint §2.4 names —
 * [LearningInterventionIntent.REPAIR_CONCEPT], [LearningInterventionIntent.START_DIAGNOSTIC],
 * [LearningInterventionIntent.ASSIGN_TARGETED_SET], [LearningInterventionIntent.SCHEDULE_RETENTION_TEST],
 * [LearningInterventionIntent.START_MOCK], [LearningInterventionIntent.REPLAN_DAY],
 * [LearningInterventionIntent.REDUCE_DIFFICULTY], [LearningInterventionIntent.INCREASE_DIFFICULTY].
 * No new intent invented here, none dropped.
 *
 * HONEST GAP: every classification rule below (which intent fits which signal
 * shape) is a first-pass heuristic, same as every other engine in this codebase —
 * flagged the same way [ScoreGainEstimator]/[ScorePredictor] flag their own
 * weights. Priority scores mix two genuinely different units on one axis: concept-
 * level candidates rank by [ScoreGainEstimator.ScoreGainEstimate.expectedGain]
 * (marks per [SESSION_MINUTES]-minute block — a real, if uncalibrated, rate), while
 * whole-student candidates ([LearningInterventionIntent.START_MOCK],
 * [LearningInterventionIntent.REPLAN_DAY]) have no natural per-session mark rate at
 * all and are scaled onto the same axis relative to the strongest concept-level
 * candidate in the same report (see [macroCandidates]) purely so ranking has
 * something to sort against — not because the two numbers mean the same thing.
 * Not validated against real outcomes; that validation, not intuition, is the bar
 * for retuning any constant here.
 */
object LearningDecisionEngine {

    /** How many ranked candidates a caller sees — enough for a "here's what's next
     *  and what's after that" view without dumping every weak topic as a
     *  duplicate-shaped action. */
    private const val MAX_CANDIDATES = 5

    /** How many of [ScoreGainEstimator]'s top-ranked estimates get turned into
     *  candidates at all, before targeted-set collapsing and final ranking trim
     *  this down further — wider than [MAX_CANDIDATES] so a chapter with several
     *  weak topics still has enough raw material to collapse into one
     *  [LearningInterventionIntent.ASSIGN_TARGETED_SET] instead of losing topics
     *  to the pool cutoff before grouping even runs. */
    private const val CONCEPT_CANDIDATE_POOL = 10

    /** Kept numerically in sync with [RetentionEngine.HIGH_MASTERY_THRESHOLD] /
     *  [com.checkmate.learning.engine.MasteryEngine.MASTERY_THRESHOLD] by hand, not
     *  imported — same "not worth a cross-file dependency for one constant"
     *  convention [ScorePredictor.WEAK_MASTERY_THRESHOLD] already establishes. */
    private const val HIGH_MASTERY_THRESHOLD = 0.75

    /** Kept in sync with [ScorePredictor.ERROR_HEAVY_THRESHOLD] by hand, same
     *  "repeated errors become a distinct signal, not just low mastery" framing. */
    private const val ERROR_HEAVY_THRESHOLD = 3

    /** Fraction of attempts on a concept that must have produced an error before
     *  the failure mode looks like "the difficulty tier itself is miscalibrated"
     *  rather than ordinary not-yet-mastered weakness — the extra bar
     *  [LearningInterventionIntent.REDUCE_DIFFICULTY] needs beyond plain
     *  [ERROR_HEAVY_THRESHOLD], since most weak concepts should stay
     *  [LearningInterventionIntent.REPAIR_CONCEPT] rather than being read as a
     *  difficulty problem. */
    private const val REDUCE_DIFFICULTY_ERROR_RATE = 0.6

    /** Below this many total attempts across the whole student model, every
     *  ranked estimate above is running on evidence too thin to trust — a fresh
     *  mock generates more real signal per hour than another repair guess.
     *  First-pass threshold, not calibrated against real outcomes. */
    private const val THIN_DATA_ATTEMPT_THRESHOLD = 20

    /** Minimum weak topics sharing a (subject, chapter) before they're collapsed
     *  into one [LearningInterventionIntent.ASSIGN_TARGETED_SET] candidate instead
     *  of appearing as that many separate [LearningInterventionIntent.REPAIR_CONCEPT]
     *  candidates — three near-duplicate "repair X" rows for the same chapter is
     *  noise, not three distinct decisions. */
    private const val MIN_TOPICS_FOR_TARGETED_SET = 3

    private const val DIAGNOSTIC_MINUTES = 15
    private const val RETENTION_TEST_MINUTES = 10
    private const val MOCK_MINUTES = 60
    private const val MAX_TARGETED_SET_MINUTES = 90

    /** What share of the strongest concept-level candidate's priority a macro
     *  (whole-student) candidate is scaled to when its own trigger condition is
     *  only barely met — see [macroCandidates] and this class's own HONEST GAP
     *  note on mixing units. */
    private const val MIN_MACRO_PRIORITY_SHARE = 0.3

    enum class LearningInterventionIntent {
        REPAIR_CONCEPT,
        START_DIAGNOSTIC,
        ASSIGN_TARGETED_SET,
        SCHEDULE_RETENTION_TEST,
        START_MOCK,
        REPLAN_DAY,
        REDUCE_DIFFICULTY,
        INCREASE_DIFFICULTY
    }

    /**
     * One ranked, typed thing the student could do next. `conceptId` is null for
     * candidates that aren't about one specific concept ([ASSIGN_TARGETED_SET]
     * names a chapter instead; [START_MOCK]/[REPLAN_DAY] are whole-student).
     * `expectedGain` is the real marks-per-session number for concept-level
     * candidates and `0.0` for ones that don't have a comparable per-session mark
     * rate (see class doc) — `priorityScore` is what [decideFromReport] actually
     * sorts on, kept separate from `expectedGain` so a caller can always show the
     * real evidence-backed number alongside the rank, never a synthetic one
     * dressed up as marks.
     */
    data class CandidateIntervention(
        val intent: LearningInterventionIntent,
        val conceptId: String?,
        val subject: String?,
        val chapter: String?,
        val topic: String?,
        val durationMinutes: Int,
        val expectedGain: Double,
        val priorityScore: Double,
        val rationale: String
    )

    data class DecisionReport(
        val studentId: String,
        val examType: String,
        val generatedAt: Long,
        /** Ranked highest-[CandidateIntervention.priorityScore] first, capped at
         *  [MAX_CANDIDATES]. */
        val candidates: List<CandidateIntervention>
    )

    /** Runs [PerformanceAnalyzer.analyze]/[ScoreGainEstimator.rank]/[ScorePredictor.predict]
     *  itself — see [ScoreGainEstimator.rank]'s own doc for why this convenience
     *  overload exists alongside [decideFromReport]. */
    fun decide(studentModel: StudentModel, examType: String, targetScore: Int): DecisionReport {
        val report = PerformanceAnalyzer.analyze(studentModel, examType)
        val estimates = ScoreGainEstimator.rankFromReport(report, studentModel)
        val expectedScore = ScorePredictor.predictFromReport(report, studentModel, targetScore)
        return decideFromReport(report, studentModel, estimates, expectedScore)
    }

    /**
     * Same as [decide] but against an already-built [PerformanceAnalyzer.PerformanceReport]/
     * [ScoreGainEstimator.ScoreGainEstimate] list/[ScorePredictor.ExpectedScore] —
     * for a caller (e.g. `TestResultsViewModel`) that already built all three off
     * one Room read, so this can never silently diverge from what's already on
     * screen. Same "one Room read, N derived views" discipline
     * [ScorePredictor.predictFromReport] itself follows.
     */
    fun decideFromReport(
        report: PerformanceAnalyzer.PerformanceReport,
        studentModel: StudentModel,
        estimates: List<ScoreGainEstimator.ScoreGainEstimate>,
        expectedScore: ScorePredictor.ExpectedScore
    ): DecisionReport {
        val diagnostics = diagnosticCandidates(studentModel, report)
        val concepts = collapseIntoTargetedSets(conceptCandidates(studentModel, estimates))
        val conceptLevel = diagnostics + concepts
        val ceiling = conceptLevel.maxOfOrNull { it.priorityScore } ?: 0.0
        val macro = macroCandidates(studentModel, report, ceiling)

        val ranked = (conceptLevel + macro)
            .sortedByDescending { it.priorityScore }
            .take(MAX_CANDIDATES)

        return DecisionReport(
            studentId = studentModel.studentId,
            examType = report.examType,
            generatedAt = System.currentTimeMillis(),
            candidates = ranked
        )
    }

    /**
     * [LearningInterventionIntent.START_DIAGNOSTIC] candidates — one per weak
     * prerequisite the student has never independently attempted (no entry in
     * [StudentModel.concepts] at all). A prerequisite the student HAS attempted
     * already has its own [ScoreGainEstimator] ranking and belongs to
     * [conceptCandidates] as an ordinary [LearningInterventionIntent.REPAIR_CONCEPT]
     * — diagnosing something already directly evidenced would just repeat work
     * [ScoreGainEstimator] already did. `priorityScore` sums the
     * [PerformanceAnalyzer.TopicImpact.marksAtStakeGap] of every downstream weak
     * concept that traces back to this prerequisite (Blueprint §1.4's own
     * "Rolling Motion failure traced back to a weak Laws of Motion prerequisite"
     * example) — same unit as concept-level `expectedGain` in spirit (marks at
     * stake), but NOT the same calculation (no time-cost division, no
     * probability-of-improvement term — there's no attempt data yet to derive
     * either from). Flagged per class doc as a first-pass approximation, not a
     * true apples-to-apples comparison.
     */
    private fun diagnosticCandidates(
        studentModel: StudentModel,
        report: PerformanceAnalyzer.PerformanceReport
    ): List<CandidateIntervention> {
        val gapByConceptId = report.topicImpacts.associate { it.conceptId to it.marksAtStakeGap }
        val downstreamGaps = mutableMapOf<String, MutableList<Double>>()
        val refByPrereqId = mutableMapOf<String, com.checkmate.learning.model.PrerequisiteRef>()

        for (issue in studentModel.weakPrerequisites) {
            val downstreamGap = gapByConceptId[issue.conceptId] ?: 0.0
            for (ref in issue.weakPrerequisites) {
                if (studentModel.concepts.containsKey(ref.conceptId)) continue
                downstreamGaps.getOrPut(ref.conceptId) { mutableListOf() }.add(downstreamGap)
                refByPrereqId.putIfAbsent(ref.conceptId, ref)
            }
        }

        return downstreamGaps.map { (prereqConceptId, gaps) ->
            val ref = refByPrereqId.getValue(prereqConceptId)
            val totalDownstreamValue = gaps.sum()
            val label = ref.topic ?: ref.chapter ?: "this prerequisite"
            CandidateIntervention(
                intent = LearningInterventionIntent.START_DIAGNOSTIC,
                conceptId = prereqConceptId,
                subject = ref.subject,
                chapter = ref.chapter,
                topic = ref.topic,
                durationMinutes = DIAGNOSTIC_MINUTES,
                expectedGain = 0.0,
                priorityScore = totalDownstreamValue,
                rationale = "Untested prerequisite ($label) behind ${gaps.size} weak concept(s) worth " +
                    "~%.1f marks — diagnose it before repairing those directly.".format(totalDownstreamValue)
            )
        }
    }

    /**
     * One candidate per top-[CONCEPT_CANDIDATE_POOL] [ScoreGainEstimator] estimate
     * with positive `expectedGain` — [classifyIntent] picks which of the six
     * concept-scoped intents fits each concept's own mastery/retention/error
     * signal shape (see that function's own doc), everything else about the
     * candidate (`expectedGain`, `priorityScore`) is the estimate's own number,
     * unmodified — this function decides WHICH action, [ScoreGainEstimator]
     * already decided HOW MUCH it's worth.
     */
    private fun conceptCandidates(
        studentModel: StudentModel,
        estimates: List<ScoreGainEstimator.ScoreGainEstimate>
    ): List<CandidateIntervention> {
        val dominantErrorByConceptId = studentModel.unresolvedErrors
            .groupBy { it.conceptId }
            .mapValues { (_, patterns) -> patterns.maxByOrNull { it.occurrences }?.errorType }

        return estimates
            .filter { it.expectedGain > 0.0 }
            .take(CONCEPT_CANDIDATE_POOL)
            .mapNotNull { estimate ->
                val snapshot = studentModel.concepts[estimate.conceptId] ?: return@mapNotNull null
                val dominantError = dominantErrorByConceptId[estimate.conceptId]
                val intent = classifyIntent(snapshot, dominantError)
                CandidateIntervention(
                    intent = intent,
                    conceptId = estimate.conceptId,
                    subject = estimate.subject,
                    chapter = estimate.chapter,
                    topic = estimate.topic,
                    durationMinutes = durationFor(intent, estimate),
                    expectedGain = estimate.expectedGain,
                    priorityScore = estimate.expectedGain,
                    rationale = rationaleFor(intent, estimate, snapshot, dominantError)
                )
            }
    }

    /**
     * Six-way split over a concept's own already-derived signals — no new
     * heuristic invented, every branch reuses a decision another engine already
     * made:
     * - [RetentionDecisionSnapshot.REVIEW] at high mastery (something once known,
     *   now at risk — [com.checkmate.learning.engine.RetentionEngine]'s own
     *   truth table) -> [LearningInterventionIntent.SCHEDULE_RETENTION_TEST], not
     *   a full repair — it doesn't need re-teaching, it needs a recall check.
     * - High mastery with a dominant CARELESS/TIME_PRESSURE error (the two error
     *   types [com.checkmate.learning.engine.ErrorEngine]'s current heuristic can
     *   actually distinguish) -> [LearningInterventionIntent.INCREASE_DIFFICULTY]:
     *   the knowledge is there, the failure mode is pace/attention under the
     *   current difficulty tier, not the concept itself.
     * - Heavy, high-rate errors (see [REDUCE_DIFFICULTY_ERROR_RATE]) at low
     *   mastery -> [LearningInterventionIntent.REDUCE_DIFFICULTY]: nearly every
     *   attempt is failing, which reads as "this tier is currently miscalibrated
     *   for this student," not just "needs more repair time at the same level."
     * - Everything else weak -> [LearningInterventionIntent.REPAIR_CONCEPT], the
     *   default and by far the most common outcome.
     */
    private fun classifyIntent(
        snapshot: ConceptSnapshot,
        dominantError: String?
    ): LearningInterventionIntent {
        val errorRate = if (snapshot.attemptCount > 0) {
            snapshot.errorCount.toDouble() / snapshot.attemptCount
        } else {
            0.0
        }
        return when {
            snapshot.retentionDecision == RetentionDecisionSnapshot.REVIEW &&
                snapshot.mastery >= HIGH_MASTERY_THRESHOLD ->
                LearningInterventionIntent.SCHEDULE_RETENTION_TEST

            snapshot.mastery >= HIGH_MASTERY_THRESHOLD &&
                (dominantError == "CARELESS" || dominantError == "TIME_PRESSURE") ->
                LearningInterventionIntent.INCREASE_DIFFICULTY

            snapshot.errorCount >= ERROR_HEAVY_THRESHOLD &&
                errorRate >= REDUCE_DIFFICULTY_ERROR_RATE &&
                snapshot.mastery < HIGH_MASTERY_THRESHOLD ->
                LearningInterventionIntent.REDUCE_DIFFICULTY

            else -> LearningInterventionIntent.REPAIR_CONCEPT
        }
    }

    private fun durationFor(
        intent: LearningInterventionIntent,
        estimate: ScoreGainEstimator.ScoreGainEstimate
    ): Int = when (intent) {
        LearningInterventionIntent.SCHEDULE_RETENTION_TEST -> RETENTION_TEST_MINUTES
        else -> estimate.timeCostMinutes.toInt()
    }

    private fun rationaleFor(
        intent: LearningInterventionIntent,
        estimate: ScoreGainEstimator.ScoreGainEstimate,
        snapshot: ConceptSnapshot,
        dominantError: String?
    ): String {
        val label = estimate.topic ?: estimate.chapter ?: "this concept"
        return when (intent) {
            LearningInterventionIntent.SCHEDULE_RETENTION_TEST ->
                "$label is well-mastered (%.0f%%) but at forgetting risk — a short recall check, not a re-teach."
                    .format(snapshot.mastery * 100)
            LearningInterventionIntent.INCREASE_DIFFICULTY ->
                "$label is well-mastered (%.0f%%) but errors skew $dominantError — push harder practice to convert accuracy under pressure."
                    .format(snapshot.mastery * 100)
            LearningInterventionIntent.REDUCE_DIFFICULTY ->
                "$label: ${snapshot.errorCount} errors across ${snapshot.attemptCount} attempts at %.0f%% mastery — current difficulty tier looks miscalibrated; step down before more repair time."
                    .format(snapshot.mastery * 100)
            else ->
                "$label at %.0f%% mastery, ~%.1f marks still at stake.".format(snapshot.mastery * 100, estimate.marksAtStake)
        }
    }

    /**
     * Collapses [LearningInterventionIntent.REPAIR_CONCEPT] candidates sharing a
     * (subject, chapter) into one [LearningInterventionIntent.ASSIGN_TARGETED_SET]
     * once there are at least [MIN_TOPICS_FOR_TARGETED_SET] of them — every other
     * intent passes through untouched, since collapsing a
     * [LearningInterventionIntent.SCHEDULE_RETENTION_TEST] or
     * [LearningInterventionIntent.REDUCE_DIFFICULTY] into a generic practice set
     * would lose exactly the distinction [classifyIntent] just made. The
     * collapsed candidate's `expectedGain`/`priorityScore` sum the group's own
     * (already-real) numbers — not re-estimated — and `durationMinutes` sums the
     * group's own time costs, capped at [MAX_TARGETED_SET_MINUTES] so one chapter
     * with many weak topics doesn't imply an unrealistically long single session.
     */
    private fun collapseIntoTargetedSets(candidates: List<CandidateIntervention>): List<CandidateIntervention> {
        val repairable = candidates.filter { it.intent == LearningInterventionIntent.REPAIR_CONCEPT }
        val rest = candidates.filter { it.intent != LearningInterventionIntent.REPAIR_CONCEPT }
        val groups = repairable.groupBy { it.subject to it.chapter }

        val kept = mutableListOf<CandidateIntervention>()
        val collapsed = mutableListOf<CandidateIntervention>()

        for ((key, group) in groups) {
            if (group.size >= MIN_TOPICS_FOR_TARGETED_SET) {
                val totalGain = group.sumOf { it.expectedGain }
                val totalMinutes = group.sumOf { it.durationMinutes }.coerceAtMost(MAX_TARGETED_SET_MINUTES)
                val label = key.second ?: key.first ?: "this chapter"
                collapsed += CandidateIntervention(
                    intent = LearningInterventionIntent.ASSIGN_TARGETED_SET,
                    conceptId = null,
                    subject = key.first,
                    chapter = key.second,
                    topic = null,
                    durationMinutes = totalMinutes,
                    expectedGain = totalGain,
                    priorityScore = totalGain,
                    rationale = "${group.size} weak topics in $label — one mixed practice set covers them " +
                        "together instead of ${group.size} separate repair sessions."
                )
            } else {
                kept += group
            }
        }

        return kept + collapsed + rest
    }

    /**
     * Whole-student candidates that aren't about any single concept —
     * [LearningInterventionIntent.START_MOCK] when the entire report is running on
     * too few attempts to trust ([THIN_DATA_ATTEMPT_THRESHOLD]), and
     * [LearningInterventionIntent.REPLAN_DAY] when recent accuracy is genuinely
     * declining ([PerformanceAnalyzer.PerformanceTrend.DECLINING] — not merely
     * stable-but-imperfect). Both conditions can fire in the same report. Neither
     * has a natural per-session mark rate the way concept-level candidates do
     * (see class doc's HONEST GAP note), so `priorityScore` is scaled relative to
     * `conceptCeiling` — the strongest concept-level candidate's own priority in
     * this same report — floored at [MIN_MACRO_PRIORITY_SHARE] of it so a macro
     * candidate can never be silently sorted to the bottom by being on the wrong
     * numeric scale, but also never guaranteed to outrank a genuinely strong
     * concept-level opportunity just for existing.
     */
    private fun macroCandidates(
        studentModel: StudentModel,
        report: PerformanceAnalyzer.PerformanceReport,
        conceptCeiling: Double
    ): List<CandidateIntervention> {
        val floor = (conceptCeiling * MIN_MACRO_PRIORITY_SHARE).coerceAtLeast(1.0)
        val out = mutableListOf<CandidateIntervention>()

        if (studentModel.overall.totalAttempts < THIN_DATA_ATTEMPT_THRESHOLD) {
            out += CandidateIntervention(
                intent = LearningInterventionIntent.START_MOCK,
                conceptId = null,
                subject = null,
                chapter = null,
                topic = null,
                durationMinutes = MOCK_MINUTES,
                expectedGain = 0.0,
                priorityScore = floor,
                rationale = "Only ${studentModel.overall.totalAttempts} attempts tracked so far — every " +
                    "estimate above is running on thin evidence. A fresh mock generates more real signal " +
                    "than another guess at what to repair."
            )
        }

        if (report.overallTrend == PerformanceAnalyzer.PerformanceTrend.DECLINING) {
            val severity = (-report.overallTrendDelta).coerceIn(0.0, 1.0)
            out += CandidateIntervention(
                intent = LearningInterventionIntent.REPLAN_DAY,
                conceptId = null,
                subject = null,
                chapter = null,
                topic = null,
                durationMinutes = 0,
                expectedGain = 0.0,
                priorityScore = (conceptCeiling * severity).coerceAtLeast(floor),
                rationale = "Recent accuracy is trending down (%.0f%% below your overall average) — the " +
                    "current plan itself may need restructuring, not just more of the same sessions."
                    .format(severity * 100)
            )
        }

        return out
    }
}
