package com.checkmate.learning.analytics

import com.checkmate.core.PYQWeightage

/**
 * Upgrade Blueprint Phase 2 prerequisite ("step 0.5" — flagged during the Phase 1/2
 * cross-check, before [PerformanceAnalyzer] and the ScoreGainEstimator it feeds).
 * Bridges a learning-module concept (identified by exam/subject/chapter/topic — see
 * [com.checkmate.learning.graph.KnowledgeGraph.conceptId]) to [PYQWeightage]'s
 * (exam, subject, chapter)-keyed data plus a per-exam marks scale, so downstream
 * consumers get a real "marks at stake" number instead of only a 0-1 mastery score.
 *
 * WHY THIS DIDN'T ALREADY EXIST: [com.checkmate.learning.graph.KnowledgeGraph.conceptId]
 * deliberately excludes subject from concept identity (see its own doc — real
 * TestResultNormalizer imports never carry a subject), so nothing in
 * :modules:learning has ever needed to resolve a concept back to a PYQWeightage row
 * before now. ScoreGainEstimator's expectedGain formula (blueprint §2.2) needs
 * marksAtStake, which needs weightage, which needs this lookup — hence its own file
 * rather than an inline one-off inside the estimator.
 *
 * CANONICAL-CHAPTER REBUILD (this session — see PYQWeightage's own doc for the full
 * rationale): [PYQWeightage]'s NEET tables are now keyed on the exact chapter
 * strings from [com.checkmate.core.ExamSyllabus], not a hand-typed, independently-
 * evolved vocabulary. That means most of what used to require an [ALIASES] entry
 * now resolves at tier 2 (exact chapter) or tier 3 (canonical normalize) instead —
 * [ALIASES] below is smaller and every remaining entry is one [tokenOverlapScore]
 * genuinely can't bridge on its own, exactly the bar the tier was always meant to
 * hold to.
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
     * when unresolved. `confidence` mirrors the matched [PYQWeightage.WeightageEntry]'s
     * own confidence — [PYQWeightage.Confidence.ESTIMATED] (the safe default) when
     * unresolved, since there's no entry to inherit confidence from.
     */
    data class WeightageResolution(
        val subjectResolved: String?,
        val weightagePercent: Float,
        val matchedKey: String? = null,
        val method: ResolutionMethod = ResolutionMethod.UNRESOLVED,
        val confidence: PYQWeightage.Confidence = PYQWeightage.Confidence.ESTIMATED
    )

    /**
     * Real (and real-adjacent, coaching-institute-style) chapter names that map to a
     * [PYQWeightage] key but share too little vocabulary for [tokenOverlapScore] to
     * catch on its own. Each entry is manually verified against the actual NEET
     * syllabus, not inferred — see resolveWeightage's KDoc for why this tier exists
     * and what it deliberately does NOT cover. Keys are pre-[normalize]d fragments;
     * values are the exact PYQWeightage key to resolve to (looked up across every
     * subject in the exam via [findCanonical]).
     *
     * Grouped by why the alias exists:
     *  1. Pre-2026-syllabus / coaching-institute names for a chapter that WAS
     *     renamed or merged in ExamSyllabus's 2026 rebuild.
     *  2. A real topic that lives INSIDE a broader canonical chapter, reported as
     *     if it were its own chapter — see the "Breathing & Exchange of Gases"
     *     entry's own comment for the coarse-bucket caveat that applies to it
     *     specifically.
     *  3. Legacy PYQWeightage topic names from BEFORE this session's canonical
     *     rebuild, kept resolvable here so a report using them doesn't regress
     *     from "resolved" to "unresolved" just because the underlying table
     *     changed shape.
     */
    private val ALIASES: Map<String, Map<String, String>> = mapOf(
        "NEET" to mapOf(
            // --- Group 1: pre-2026 / alternate chapter names -------------------
            normalize("Motion in a Straight Line") to "Kinematics",
            normalize("Structure of Atom") to "Atomic Structure",
            normalize("The Living World") to "Diversity In Living World",
            normalize("Structural Organisation") to "Structural Organisation In Animals And Plants",
            normalize("Cell Cycle & Cell Division") to "Cell Structure And Function",
            // Extra trailing content beyond what normalize()'s parenthetical/suffix
            // stripping handles alone — see Biomolecules-I vs -II note lower down.
            normalize("Biomolecules-II (Proteins, types & functions), Lipids, Nucleic acids, Enzymes, Cofactors") to "Cell Structure And Function",

            // --- Group 2: real topic reported as its own chapter ----------------
            // Motion in a Plane / Projectile Motion / Relative Velocity are real
            // topics WITHIN the canonical "Kinematics" NTA unit (see ExamSyllabus).
            // No longer a coverage gap now that PYQWeightage's Kinematics entry is
            // keyed at the (correct, 2D-inclusive) chapter level.
            normalize("Motion in a Plane") to "Kinematics",
            // "Breathing & Exchange of Gases" is a real topic WITHIN the canonical
            // "Human Physiology" NTA unit, not a chapter of its own. DELIBERATE
            // COARSE-BUCKET ALIAS: Human Physiology also covers circulation,
            // excretion, neural and endocrine content, so this over-attributes that
            // whole chapter's weight to one respiration sub-topic. A caller reading
            // `confidence` off the resolved entry sees MEDIUM/ESTIMATED rather than
            // HIGH for exactly this reason — treat the resulting weightage as a
            // rough upper bound, not a respiration-only figure. Revisit if/when
            // PYQWeightage ever gets topic-level (not just chapter-level) granularity.
            normalize("Breathing & Exchange of Gases") to "Human Physiology",

            // --- Group 3: legacy pre-rebuild PYQWeightage topic names -----------
            normalize("Biodiversity") to "Ecology And Environment",
            normalize("Environmental Issues") to "Ecology And Environment",
            normalize("Animal Kingdom") to "Diversity In Living World",
            normalize("Plant Kingdom") to "Diversity In Living World",
            normalize("Biological Classification") to "Diversity In Living World",
            normalize("Microbes in Human Welfare") to "Biology And Human Welfare",
            normalize("Strategies for Enhancement") to "Biology And Human Welfare",
            normalize("Cell Biology") to "Cell Structure And Function",
            normalize("Cell Division") to "Cell Structure And Function",
            normalize("Properties of Matter") to "Properties Of Solids And Liquids",
            normalize("Magnetism") to "Magnetic Effects Of Current And Magnetism",
            normalize("AC Circuits") to "Electromagnetic Induction And Alternating Currents",
            normalize("Waves and Sound") to "Oscillations And Waves",
            normalize("Simple Harmonic Motion") to "Oscillations And Waves",
            normalize("Semiconductors") to "Electronic Devices",
            // Old table's single "Modern Physics" bucket spanned two canonical
            // chapters (Dual Nature Of Matter And Radiation + Atoms And Nuclei).
            // A one-to-many legacy key can't alias cleanly to both — Atoms And
            // Nuclei picked as the larger/more central of the two.
            normalize("Modern Physics") to "Atoms And Nuclei",
            normalize("Chemical Bonding") to "Chemical Bonding And Molecular Structure",
            normalize("Coordination Compounds") to "Co-Ordination Compounds",
            normalize("Aldehydes Ketones Acids") to "Organic Compounds Containing Oxygen",
            normalize("Alcohols Phenols Ethers") to "Organic Compounds Containing Oxygen",
            normalize("Haloalkanes") to "Organic Compounds Containing Halogens",
            normalize("Amines") to "Organic Compounds Containing Nitrogen"
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

    private fun findCanonical(
        examData: Map<String, Map<String, PYQWeightage.WeightageEntry>>,
        key: String
    ): WeightageResolution? {
        for ((subj, topics) in examData) {
            val hit = topics[key]
            if (hit != null) {
                return WeightageResolution(subj, hit.percent, key, ResolutionMethod.EXACT_CANONICAL, hit.confidence)
            }
        }
        return null
    }

    /**
     * Five-tier fallback, most to least specific:
     *  1. Exact (exam, subject, topic) — only reachable when `subject` is non-null,
     *     i.e. almost always a syllabus-seeded [com.checkmate.learning.model.Concept]
     *     (see [com.checkmate.learning.graph.KnowledgeGraph.seedExamSyllabus]), not a
     *     real-import one.
     *  2. Exact (exam, subject, chapter) — now the common case for a real Testmate
     *     import that already uses NTA's own chapter wording, since PYQWeightage's
     *     NEET keys are the exact ExamSyllabus chapter strings (see PYQWeightage's
     *     own doc on the canonical-chapter rebuild this tier now benefits from).
     *  3. Exact canonical match — [normalize] both the report fragment and every
     *     PYQWeightage key in the exam, compare for equality. Catches naming noise
     *     (case, punctuation, a trailing "-I"/"-II" chapter-part suffix) without
     *     guessing at word-level semantics at all.
     *  4. Explicit [ALIASES] lookup — hand-verified chapter-name mappings that
     *     don't share enough vocabulary for tier 5 to find on its own. Deliberately
     *     outranks tier 5: a curated mapping should never be second-guessed by an
     *     automatic one.
     *  5. Conservative token-overlap fuzzy match — real word-set Jaccard overlap
     *     (see [tokenOverlapScore]) across every subject in the exam, topic
     *     fragment first, then chapter fragment.
     *
     * `weightagePercent` is 0f (not an error) when nothing matches at any tier —
     * same "no signal still needs a value" discipline [PYQWeightage.getWeightage]
     * itself already uses. `subjectResolved` falls back to the input `subject` in
     * that case (may still be null). See [ALIASES]'s KDoc for the aliases kept
     * deliberately coarse rather than force-matched to a precise figure.
     */
    fun resolveWeightage(exam: String, subject: String?, chapter: String, topic: String): WeightageResolution {
        if (subject != null) {
            val exactTopic = PYQWeightage.getEntry(exam, subject, topic)
            if (exactTopic != null && exactTopic.percent > 0f) {
                return WeightageResolution(subject, exactTopic.percent, topic, ResolutionMethod.EXACT_SUBJECT_TOPIC, exactTopic.confidence)
            }
            val exactChapter = PYQWeightage.getEntry(exam, subject, chapter)
            if (exactChapter != null && exactChapter.percent > 0f) {
                return WeightageResolution(subject, exactChapter.percent, chapter, ResolutionMethod.EXACT_SUBJECT_CHAPTER, exactChapter.confidence)
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
            var best: Triple<String, String, PYQWeightage.WeightageEntry>? = null
            var bestScore = FUZZY_MIN_JACCARD
            for ((subj, topicsMap) in examData) {
                for ((key, entry) in topicsMap) {
                    val score = tokenOverlapScore(fragTokens, tokens(normalize(key)))
                    if (score >= bestScore) {
                        bestScore = score
                        best = Triple(subj, key, entry)
                    }
                }
            }
            return best?.let { (subj, key, entry) ->
                WeightageResolution(subj, entry.percent, key, ResolutionMethod.TOKEN_OVERLAP, entry.confidence)
            }
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
     * ScorePredictor wiring pass (Blueprint §2.3): a subject-by-subject expected
     * score needs the FULL set of subjects an exam is scored on — including ones
     * a given [com.checkmate.learning.model.StudentModel] has zero tracked
     * concepts for — not just whatever subjects happen to show up in one
     * student's [PerformanceAnalyzer.TopicImpact] list. [EXAM_SUBJECT_SHARE_PERCENT]
     * already has this; it just had no public accessor before now. One function,
     * no behavior change to any existing caller.
     */
    fun subjectsForExam(exam: String): Set<String> = EXAM_SUBJECT_SHARE_PERCENT[exam]?.keys.orEmpty()

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
