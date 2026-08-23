package com.checkmate.learning.analytics

import com.checkmate.core.PYQWeightage

/**
 * Upgrade Blueprint Phase 2 prerequisite ("step 0.5" — flagged during the Phase 1/2
 * cross-check, before [PerformanceAnalyzer] and the ScoreGainEstimator it feeds).
 * Bridges a learning-module concept (identified by exam/subject/chapter/topic — see
 * [com.checkmate.learning.graph.KnowledgeGraph.conceptId]) to [PYQWeightage]'s
 * (exam, subject, topic)-keyed data plus a per-exam marks scale, so downstream
 * consumers get a real "marks at stake" number instead of only a 0-1 mastery score.
 *
 * WHY THIS DIDN'T ALREADY EXIST: [com.checkmate.learning.graph.KnowledgeGraph.conceptId]
 * deliberately excludes subject from concept identity (see its own doc — real
 * TestResultNormalizer imports never carry a subject), so nothing in
 * :modules:learning has ever needed to resolve a concept back to a PYQWeightage row
 * before now. ScoreGainEstimator's expectedGain formula (blueprint §2.2) needs
 * marksAtStake, which needs weightage, which needs this lookup — hence its own file
 * rather than an inline one-off inside the estimator.
 */
object ConceptWeightage {

    /**
     * Result of resolving a concept against [PYQWeightage]. `subjectResolved` is the
     * subject the weightage was actually found under — which, for a real-imported
     * concept (subject == null going in, see class doc), is the ONLY way to learn
     * which subject a topic belongs to at all, since [PYQWeightage.findTopicWeightage]
     * itself only returns a bare Float. Null only when nothing matched at any tier.
     */
    data class WeightageResolution(val subjectResolved: String?, val weightagePercent: Float)

    /**
     * Three-tier fallback, most to least specific:
     *  1. Exact (exam, subject, topic) — only reachable when `subject` is non-null,
     *     i.e. almost always a syllabus-seeded [com.checkmate.learning.model.Concept]
     *     (see [com.checkmate.learning.graph.KnowledgeGraph.seedExamSyllabus]), not a
     *     real-import one.
     *  2. Exact (exam, subject, chapter) — some [PYQWeightage] entries are actually
     *     chapter-level names (that data predates [com.checkmate.core.ExamSyllabus]'s
     *     2026 NEET rebuild into per-unit topics — see that file's own doc), so
     *     chapter often *is* the PYQWeightage "topic" key for those rows.
     *  3. A fuzzy substring scan across every subject in the exam (topic fragment,
     *     then chapter fragment) — reimplemented here rather than reusing
     *     [PYQWeightage.findTopicWeightage] directly because that helper discards
     *     which subject matched, and marksAtStake (below) needs it. The only path
     *     available for real-imported concepts, which never carry `subject`.
     *
     * `weightagePercent` is 0f (not an error) when nothing matches at any tier —
     * same "no signal still needs a value" discipline [PYQWeightage.getWeightage]
     * itself already uses. `subjectResolved` falls back to the input `subject` in
     * that case (may still be null).
     */
    fun resolveWeightage(exam: String, subject: String?, chapter: String, topic: String): WeightageResolution {
        if (subject != null) {
            val exact = PYQWeightage.getWeightage(exam, subject, topic)
            if (exact > 0f) return WeightageResolution(subject, exact)
            val chapterLevel = PYQWeightage.getWeightage(exam, subject, chapter)
            if (chapterLevel > 0f) return WeightageResolution(subject, chapterLevel)
        }

        val examData = PYQWeightage.data[exam].orEmpty()
        fun fuzzy(fragment: String): WeightageResolution? {
            if (fragment.isBlank()) return null
            for ((subj, topics) in examData) {
                val hit = topics.entries.firstOrNull { it.key.contains(fragment, ignoreCase = true) }
                if (hit != null) return WeightageResolution(subj, hit.value)
            }
            return null
        }

        return fuzzy(topic) ?: fuzzy(chapter) ?: WeightageResolution(subject, 0f)
    }

    /**
     * Per-exam total marks. HONEST GAP: only NEET and JEE Main are filled in with
     * real current-pattern numbers — SSC CGL Tier 1's marking scheme varies by
     * post/year enough that a single constant here would be more misleading than
     * useful, so it's left unmapped (returns 0, same "0 is the correct
     * unknown-value default" call as [resolveWeightage]). Revisit if/when SSC CGL
     * prep is actually being tracked, not guessed at now.
     */
    private val EXAM_TOTAL_MARKS: Map<String, Int> = mapOf(
        "NEET" to 720,
        "JEE" to 300
    )

    /**
     * Real per-subject share of the total paper — NOT an equal split.
     * NEET: Physics 180 + Chemistry 180 + Biology 360 (Botany+Zoology combined
     * under one "Biology" paper section) out of 720 = 25 / 25 / 50.
     * JEE Main: Physics/Chemistry/Mathematics ~100 marks each of 300.
     * Keyed on [PYQWeightage]'s own subject names, since that's what
     * [resolveWeightage] returns in `subjectResolved`.
     */
    private val EXAM_SUBJECT_SHARE_PERCENT: Map<String, Map<String, Float>> = mapOf(
        "NEET" to mapOf("Physics" to 25f, "Chemistry" to 25f, "Biology" to 50f),
        "JEE" to mapOf("Physics" to 33.34f, "Chemistry" to 33.33f, "Mathematics" to 33.33f)
    )

    fun totalMarks(exam: String): Int = EXAM_TOTAL_MARKS[exam] ?: 0

    fun subjectSharePercent(exam: String, subject: String?): Float {
        if (subject == null) return 0f
        return EXAM_SUBJECT_SHARE_PERCENT[exam]?.get(subject) ?: 0f
    }

    /**
     * Full marks-at-stake for a topic if the exam paper matched [PYQWeightage]'s
     * historical proportions exactly: `totalMarks(exam) * subjectShare% * topic
     * weightage%`. Returns 0.0 whenever either share or weightage is unresolved —
     * "unknown" should never silently become "zero risk" further downstream, but
     * this class only reports the number; callers (PerformanceAnalyzer) are
     * responsible for not treating a 0.0 marksAtStake as a confirmed non-issue when
     * `resolution.weightagePercent == 0f` also holds.
     */
    fun marksAtStake(exam: String, resolution: WeightageResolution): Double {
        val share = subjectSharePercent(exam, resolution.subjectResolved)
        if (share <= 0f || resolution.weightagePercent <= 0f) return 0.0
        val subjectMarks = totalMarks(exam) * (share / 100.0)
        return subjectMarks * (resolution.weightagePercent / 100.0)
    }

    /** Convenience: resolve + marksAtStake in one call. */
    fun marksAtStake(exam: String, subject: String?, chapter: String, topic: String): Double =
        marksAtStake(exam, resolveWeightage(exam, subject, chapter, topic))

    /**
     * Real per-question scoring pattern — SubjectScoreCalculator's "actual marks a
     * student would recognize" tally needs the literal +/- pattern, not the
     * proportional-marks abstraction above. Same honest-gap discipline as
     * [EXAM_TOTAL_MARKS]: only NEET/JEE Main are filled in with their real,
     * currently-stable +4/-1 pattern. An unmapped exam returns 0 for both, which is
     * deliberately indistinguishable from "no negative marking" — callers can't tell
     * the difference either, same caveat as [marksAtStake] for an unmapped exam.
     */
    private val EXAM_MARKS_PER_QUESTION: Map<String, Int> = mapOf(
        "NEET" to 4,
        "JEE" to 4
    )

    private val EXAM_NEGATIVE_MARKS_PER_WRONG: Map<String, Int> = mapOf(
        "NEET" to 1,
        "JEE" to 1
    )

    fun marksPerQuestion(exam: String): Int = EXAM_MARKS_PER_QUESTION[exam] ?: 0

    fun negativeMarksPerWrong(exam: String): Int = EXAM_NEGATIVE_MARKS_PER_WRONG[exam] ?: 0
}
