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

    /** How a [WeightageResolution] was reached — surfaced for debugging rather than
     *  discarded, so "why did this resolve/not resolve" is never a re-derivation. */
    enum class ResolutionMethod {
        EXACT_SUBJECT_TOPIC, EXACT_SUBJECT_CHAPTER, EXACT_CANONICAL, ALIAS, TOKEN_OVERLAP, UNRESOLVED
    }

    /**
     * Result of resolving a concept against [PYQWeightage]. `subjectResolved` is the
     * subject the weightage was actually found under — which, for a real-imported
     * concept (subject == null going in, see class doc), is the ONLY way to learn
     * which subject a topic belongs to at all, since [PYQWeightage.findTopicWeightage]
     * itself only returns a bare Float. `weightagePercent` is 0f and `method` is
     * [ResolutionMethod.UNRESOLVED] when nothing matched at any tier —
     * `subjectResolved` falls back to the input `subject` in that case (may still be
     * null). `matchedKey` is the literal PYQWeightage key that was matched, null only
     * when unresolved.
     */
    data class WeightageResolution(
        val subjectResolved: String?,
        val weightagePercent: Float,
        val matchedKey: String? = null,
        val method: ResolutionMethod = ResolutionMethod.UNRESOLVED
    )

    /**
     * Real Testmate chapter names that map to a PYQWeightage key but share too
     * little vocabulary for [tokenOverlapScore] to catch on its own (e.g. "Laws of
     * Motion" vs "Mechanics — Newton's Laws" — one shared word, jaccard 0.25).
     * Each entry is manually verified against the actual NEET syllabus, not
     * inferred — see resolveWeightage's KDoc for why this tier exists and what it
     * deliberately does NOT cover. Keys are pre-[normalize]d fragments; values are
     * the exact PYQWeightage key to resolve to (looked up across every subject in
     * the exam via [findCanonical]).
     *
     * KNOWN GAP, LEFT DELIBERATELY UNMAPPED (see [resolveWeightage] tier order —
     * these fall through to UNRESOLVED rather than guessing):
     *  - "Motion in a Plane" — NEET's PYQWeightage Physics table only has a 1D
     *    "Kinematics" entry; aliasing 2D motion to it would overstate confidence
     *    in a topic-level weightage that doesn't actually exist for it.
     *  - "Classification of Elements and Periodicity in Properties" — no
     *    Chemistry key represents this chapter; the nearest keys (p/s/d-Block
     *    Elements) are compound-specific, not periodicity-specific.
     *  - "Breathing & Exchange of Gases-I (...)" — arguably part of the
     *    "Human Physiology" PYQ bucket, but that bucket is undifferentiated
     *    enough (it also covers circulation, excretion, neural control, etc.)
     *    that aliasing this one respiration sub-chapter to it would attribute
     *    that whole bucket's weight to a fraction of what it actually covers.
     *  Add real PYQ evidence before aliasing any of these three, per the same
     *  "0 is the correct unknown-value default" discipline used everywhere else
     *  in this file.
     */
    private val ALIASES: Map<String, Map<String, String>> = mapOf(
        "NEET" to mapOf(
            normalize("Motion in a Straight Line") to "Kinematics",
            normalize("Laws of Motion") to "Mechanics — Newton's Laws",
            normalize("Structure of Atom") to "Atomic Structure",
            normalize("The Living World") to "Diversity of Living World",
            normalize("Cell Cycle & Cell Division") to "Cell Division",
            // The real Testmate chapter carries extra trailing content
            // ("Lipids, Nucleic acids, Enzymes, Cofactors") beyond the
            // parenthetical/roman-numeral suffix that [normalize] strips, so this
            // needs its own entry — "Biomolecules-I (Upto polysaccharides)" does
            // NOT need one, since normalize alone reduces it to "biomolecules"
            // and matches PYQWeightage's "Biomolecules" key exactly (tier 3).
            normalize("Biomolecules-II (Proteins, types & functions), Lipids, Nucleic acids, Enzymes, Cofactors") to "Biomolecules"
        )
    )

    /** Generic connective words stripped before token-overlap comparison — NOT
     *  overfit to this app's chapter names, so it stays safe to reuse for JEE/SSC
     *  chapters later. */
    private val STOPWORDS = setOf("of", "in", "a", "an", "the", "and", "to", "for", "on", "at", "or")

    /** Lowercase, drop parentheticals and a trailing roman/arabic chapter-part
     *  suffix ("-I", "-II", "-2"), fold "&" to "and", strip remaining punctuation,
     *  collapse whitespace. Deliberately does NOT stem words (see [tokenOverlapScore]
     *  doc on why "Electricity" must never match "Electric"). */
    private fun normalize(raw: String): String {
        if (raw.isBlank()) return ""
        var s = raw.lowercase()
        s = s.replace(Regex("\\([^)]*\\)"), " ")
        s = s.replace(Regex("-(i{1,3}|iv|v|[0-9]+)\\b"), " ")
        s = s.replace("&", " and ")
        s = s.replace(Regex("[^a-z0-9\\s]"), " ")
        s = s.replace(Regex("\\s+"), " ").trim()
        return s
    }

    private fun tokens(normalized: String): Set<String> =
        normalized.split(" ").filter { it.isNotBlank() && it.length > 2 && it !in STOPWORDS }.toSet()

    /**
     * Plain word-set Jaccard overlap — deliberately NOT stemmed or substring-based.
     * A substring test (the original bug) or a stemmed match would let
     * "Electricity" match "Electric" and "Current Electricity" bleed into
     * "Electric Potential" — two genuinely different NEET topics. Whole-word
     * comparison only, so a match means the fragments actually share concepts,
     * not just a common prefix.
     */
    private fun tokenOverlapScore(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val intersection = a.intersect(b).size
        val union = a.union(b).size
        return intersection.toDouble() / union
    }

    /** Conservative on purpose — chosen so real overlapping-but-distinct NEET
     *  topics (e.g. "Current Electricity" vs "Electric Potential") score 0, while
     *  genuine paraphrases (e.g. "Cell Cycle & Cell Division" vs "Cell Division",
     *  jaccard 0.67) still pass. See ConceptWeightageTest for the boundary cases
     *  this threshold was picked against. */
    private const val FUZZY_MIN_JACCARD = 0.6

    private fun findCanonical(examData: Map<String, Map<String, Float>>, key: String): WeightageResolution? {
        for ((subj, topics) in examData) {
            val hit = topics[key]
            if (hit != null) return WeightageResolution(subj, hit, key, ResolutionMethod.EXACT_CANONICAL)
        }
        return null
    }

    /**
     * Five-tier fallback, most to least specific:
     *  1. Exact (exam, subject, topic) — only reachable when `subject` is non-null,
     *     i.e. almost always a syllabus-seeded [com.checkmate.learning.model.Concept]
     *     (see [com.checkmate.learning.graph.KnowledgeGraph.seedExamSyllabus]), not a
     *     real-import one.
     *  2. Exact (exam, subject, chapter) — some [PYQWeightage] entries are actually
     *     chapter-level names (that data predates [com.checkmate.core.ExamSyllabus]'s
     *     2026 NEET rebuild into per-unit topics — see that file's own doc), so
     *     chapter often *is* the PYQWeightage "topic" key for those rows.
     *  3. Exact canonical match — [normalize] both the report fragment and every
     *     PYQWeightage key in the exam, compare for equality. Catches naming noise
     *     (case, punctuation, a trailing "-I"/"-II" chapter-part suffix) without
     *     guessing at word-level semantics at all.
     *  4. Explicit [ALIASES] lookup — hand-verified chapter-name mappings that
     *     don't share enough vocabulary for tier 5 to find on its own (e.g. "Laws
     *     of Motion" → "Mechanics — Newton's Laws"). Deliberately outranks tier 5:
     *     a curated mapping should never be second-guessed by an automatic one.
     *  5. Conservative token-overlap fuzzy match — real word-set Jaccard overlap
     *     (see [tokenOverlapScore]) across every subject in the exam, topic
     *     fragment first, then chapter fragment. Replaces the old bidirectional
     *     substring scan, which required the (short) PYQWeightage key to contain
     *     the (long, specific) report chapter string as a literal substring —
     *     structurally near-impossible whenever the report string was longer than
     *     its PYQWeightage counterpart, which was every real chapter name.
     *
     * `weightagePercent` is 0f (not an error) when nothing matches at any tier —
     * same "no signal still needs a value" discipline [PYQWeightage.getWeightage]
     * itself already uses. `subjectResolved` falls back to the input `subject` in
     * that case (may still be null). See [ALIASES]'s KDoc for the three real NEET
     * chapters left deliberately unresolved rather than force-matched.
     */
    fun resolveWeightage(exam: String, subject: String?, chapter: String, topic: String): WeightageResolution {
        if (subject != null) {
            val exact = PYQWeightage.getWeightage(exam, subject, topic)
            if (exact > 0f) return WeightageResolution(subject, exact, topic, ResolutionMethod.EXACT_SUBJECT_TOPIC)
            val chapterLevel = PYQWeightage.getWeightage(exam, subject, chapter)
            if (chapterLevel > 0f) {
                return WeightageResolution(subject, chapterLevel, chapter, ResolutionMethod.EXACT_SUBJECT_CHAPTER)
            }
        }

        val examData = PYQWeightage.data[exam].orEmpty()
        val normTopic = normalize(topic)
        val normChapter = normalize(chapter)
        val allKeys = examData.values.flatMap { it.keys }

        // normalize() is a no-op on an already-clean key, so comparing normalized
        // forms subsumes a literal exact match too — no separate literal check needed.
        if (normTopic.isNotBlank()) {
            allKeys.firstOrNull { normalize(it) == normTopic }?.let { key -> findCanonical(examData, key)?.let { return it } }
        }
        if (normChapter.isNotBlank()) {
            allKeys.firstOrNull { normalize(it) == normChapter }?.let { key -> findCanonical(examData, key)?.let { return it } }
        }

        val examAliases = ALIASES[exam].orEmpty()
        (examAliases[normTopic] ?: examAliases[normChapter])?.let { canonicalKey ->
            findCanonical(examData, canonicalKey)
                ?.let { return it.copy(method = ResolutionMethod.ALIAS) }
        }

        fun fuzzy(fragment: String): WeightageResolution? {
            val fragTokens = tokens(normalize(fragment))
            if (fragTokens.isEmpty()) return null
            var best: Triple<String, String, Float>? = null // subject, key, value
            var bestScore = FUZZY_MIN_JACCARD
            for ((subj, topicsMap) in examData) {
                for ((key, value) in topicsMap) {
                    val score = tokenOverlapScore(fragTokens, tokens(normalize(key)))
                    if (score >= bestScore) {
                        bestScore = score
                        best = Triple(subj, key, value)
                    }
                }
            }
            return best?.let { (subj, key, value) -> WeightageResolution(subj, value, key, ResolutionMethod.TOKEN_OVERLAP) }
        }

        return fuzzy(topic) ?: fuzzy(chapter) ?: WeightageResolution(subject, 0f, null, ResolutionMethod.UNRESOLVED)
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
