package com.checkmate.learning.model

/**
 * Upgrade Blueprint §1.3/§2.1 — "Testmate → TestResultNormalizer → LearningEvents →
 * MasteryEngine → StudentModel → Planner." Phase 1 (Mastery/Error/Retention/
 * KnowledgeGraph) is built and already wired into TestResultNormalizer; this is the
 * shared read model sitting on top of it so Mentor/Planner/Analytics stop each
 * independently querying MasteryDao/ErrorDao/ConceptDependencyDao and risking three
 * different pictures of the same student — see
 * [com.checkmate.learning.student.StudentModelBuilder]'s class doc for the full
 * architecture reasoning (this was reviewed and refined against an external design
 * pass before being built, not assumed).
 *
 * DELIBERATELY NOT a Room @Entity / persisted table — a derived READ MODEL only.
 * Persisting this as its own table would create a second copy of state
 * (ConceptMastery/ErrorPattern/Concept already ARE the source of truth) that could
 * silently go stale the moment a new LearningEvent lands and nothing remembers to
 * rebuild it. Instead: Room source data -> [StudentModelBuilder] -> this immutable
 * snapshot -> consumers. If reality changes, build a new one; nothing here is ever
 * mutated in place.
 *
 * [modelVersion] exists for the day the mastery/retention formula's *meaning*
 * changes shape (e.g. difficultyPerformance/confidenceCalibration stop being null —
 * see MasteryEngine's HONEST GAP note) — bump it alongside any such change so a
 * caller holding an old StudentModel can tell it's semantically stale, not just
 * time-stale via [generatedAt].
 *
 * Scoped to `learning` only for now (mastery/retention/errors/prerequisites) — NOT
 * behavior (:modules:psyche) or schedule (:modules:planner) state. That's a
 * deliberate boundary, not an oversight: composing those in later should be an
 * additive change to [com.checkmate.learning.student.StudentModelBuilder] (more
 * provider inputs), not a rewrite of this shape or of Mentor/Planner/Analytics as
 * its consumers.
 */
data class StudentModel(
    val studentId: String,
    /** Wall-clock time this snapshot was built — "what did the planner actually see
     *  when it made this decision," per the reviewed design. Not a persistence
     *  timestamp; this model is never written back to Room. */
    val generatedAt: Long,
    val modelVersion: Int = 1,
    val overall: OverallLearningState,
    /** Keyed by conceptId. One entry per concept with any ConceptMastery row for
     *  this student — i.e. every concept ever attempted, whether or not
     *  [com.checkmate.learning.graph.KnowledgeGraph.seedExamSyllabus] ever ran. */
    val concepts: Map<String, ConceptSnapshot>,
    /** Unresolved [ErrorPattern]s only, already sorted by occurrences DESC (carried
     *  straight through from ErrorPatternDao.getUnresolved's own ordering). */
    val unresolvedErrors: List<ErrorPatternSnapshot>,
    /** One entry per weak concept (mastery below MasteryEngine.MASTERY_THRESHOLD)
     *  that traces back to at least one weak prerequisite. */
    val weakPrerequisites: List<PrerequisiteIssue>
)

/**
 * Coarse, whole-student rollup — the numbers a Mentor greeting or a Planner "how am
 * I doing overall" view wants without walking every [ConceptSnapshot] itself.
 * Derived entirely from the [ConceptSnapshot]/[ErrorPatternSnapshot] data already in
 * the same [StudentModel], not recomputed from Room directly.
 */
data class OverallLearningState(
    val conceptsTracked: Int,
    val conceptsMastered: Int,
    val conceptsWeak: Int,
    /** Simple mean of per-concept mastery, unweighted by attemptCount. A concept
     *  seen once and a concept seen 50 times count equally here — same "no false
     *  precision" caution the blueprint gives the ExpectedScore model (§2.3); revisit
     *  if/when confidence-interval-aware mastery (flagged separately, not part of
     *  this change) lands. */
    val averageMastery: Double,
    val totalAttempts: Int,
    /** Sum of `occurrences` across unresolved ErrorPatterns, not a distinct-pattern
     *  count — "you've made mistakes N times," matching the blueprint's own framing. */
    val unresolvedErrorCount: Int
)

/**
 * Per-concept slice of [StudentModel] — copied straight from [ConceptMastery] and
 * from [com.checkmate.learning.engine.RetentionEngine.decide]'s output, not
 * recalculated here. See [com.checkmate.learning.student.StudentModelBuilder]'s
 * class doc: this type aggregates already-derived intelligence, it does not
 * re-derive it.
 */
data class ConceptSnapshot(
    val conceptId: String,
    /** Display fields from the matching [Concept] row, when one exists. Null-safe
     *  because a ConceptMastery row can exist without ever having gone through
     *  KnowledgeGraph.seedExamSyllabus — see Concept.kt's own class doc. */
    val exam: String?,
    val subject: String?,
    val chapter: String?,
    val topic: String?,
    val mastery: Double,
    /** Always 0.0 today — see ConceptMastery.confidence's own doc. Carried through
     *  as-is, not fabricated here. */
    val masteryConfidence: Double,
    val retentionDecision: RetentionDecisionSnapshot,
    val forgettingRisk: Double,
    val attemptCount: Int,
    val recentAccuracy: Double,
    val lifetimeAccuracy: Double,
    /** Sum of occurrences across ALL ErrorPatterns for this concept (resolved +
     *  unresolved) — a lifetime error count, distinct from
     *  [StudentModel.unresolvedErrors] which is unresolved-only and student-wide. */
    val errorCount: Int,
    val lastSeen: Long?,
    /** conceptIds of weak prerequisites, when this concept's own mastery is weak and
     *  KnowledgeGraph.diagnosePrerequisiteFailure found at least one. Empty for
     *  well-mastered concepts (never diagnosed) and for weak concepts with no seeded
     *  prerequisite edges or no weak prerequisites. */
    val prerequisiteIssues: List<String>
)

/**
 * Plain-data mirror of
 * [com.checkmate.learning.engine.RetentionEngine.RetentionDecision] — kept as its
 * own enum here, rather than reusing the engine's directly, so :modules:learning's
 * model/ package (what Mentor/Planner actually depend on) never needs an import from
 * engine/ — same "consumers don't need to know where any of this came from"
 * separation the reviewed design called for.
 */
enum class RetentionDecisionSnapshot { REVIEW, TEACH, MOVE_ON }

/**
 * One unresolved [ErrorPattern] worth surfacing — "you've made this mistake N
 * times" per the blueprint. `errorType` is the enum's `.name` string rather than
 * [ErrorType] itself, same reasoning as [RetentionDecisionSnapshot]: keep this
 * model's public shape free of engine/model-internal enum imports where a plain
 * string is just as usable by a consumer.
 */
data class ErrorPatternSnapshot(
    val conceptId: String,
    val errorType: String,
    val occurrences: Int,
    val firstSeen: Long,
    val lastSeen: Long
)

/**
 * One weak concept whose failure traces back to a weak prerequisite — output of
 * [com.checkmate.learning.graph.KnowledgeGraph.diagnosePrerequisiteFailure], not
 * re-derived here. E.g. the blueprint's own "Rolling Motion failure traced back to a
 * weak Laws of Motion prerequisite" example.
 */
data class PrerequisiteIssue(
    val conceptId: String,
    val weakPrerequisiteConceptIds: List<String>
)
