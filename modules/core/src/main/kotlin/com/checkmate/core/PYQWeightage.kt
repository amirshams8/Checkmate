package com.checkmate.core

/**
 * PYQ (Previous Year Question) chapter-level weightage for JEE / NEET / SSC CGL.
 * Based on coaching-institute PYQ analysis (Allen/Physics Wallah/Aakash) and, where
 * noted, real observed question counts from an actual paper — see [Confidence].
 * Values represent approximate % of that subject's questions coming from each
 * chapter — not % of the whole paper.
 *
 * CANONICAL-CHAPTER REBUILD (session: PYQWeightage rebuild + gap-fill, see
 * Checkmate session report "FT-02B import pipeline debugging"): NEET's three
 * subject maps are now keyed on the EXACT chapter strings from
 * [com.checkmate.core.ExamSyllabus.data]["NEET"] — this is the fix for the root
 * cause the previous session traced: the old table's topic keys ("Mechanics —
 * Newton's Laws", "Cell Division", a standalone "Magnetism") were a hand-typed,
 * uncoordinated vocabulary that never matched ExamSyllabus's own 2026 NTA-unit
 * chapter names, so [com.checkmate.learning.analytics.ConceptWeightage]'s fuzzy
 * resolver had to bridge two disagreeing naming schemes for every real import.
 * Keying PYQWeightage directly on ExamSyllabus's chapter names means a real
 * Testmate chapter that already uses NTA's own wording (or a name close enough for
 * [ConceptWeightage]'s canonical-normalize tier) resolves with NO alias needed at
 * all. Two structural side effects worth knowing about:
 *   - This also fixes a latent ambiguity bug: the old table had an identical
 *     "Biomolecules" key under BOTH NEET Chemistry and NEET Biology, so
 *     ConceptWeightage.findCanonical's subject-map iteration order silently
 *     decided which subject a plain "Biomolecules" resolved to. Biology's share
 *     of that content now lives under the (uniquely-keyed) "Cell Structure And
 *     Function" chapter instead, per ExamSyllabus's own 2026 topic placement —
 *     no more same-string collision across subjects.
 *   - JEE and SSC CGL are UNCHANGED in content this session (still hand-typed,
 *     old-style keys) — only wrapped in the new [WeightageEntry] envelope so the
 *     public API has one shape. Revisit them the same way if/when they matter.
 *
 * COVERAGE GAPS FILLED (previously UNRESOLVED against the real FT-02B report):
 *   - "Classification Of Elements And Periodicity In Properties" now has its own
 *     entry — it was never missing from the syllabus, only from this table.
 *   - "Kinematics" already covers 2D motion (Motion in a Plane / Projectile
 *     Motion / Relative Velocity are real topics *within* that NTA unit, per
 *     ExamSyllabus) — aliasing a "Motion in a Plane" chapter fragment to it is no
 *     longer overstating confidence in a topic-level number that didn't exist;
 *     the canonical chapter-level number legitimately covers it now.
 *   - "Breathing & Exchange of Gases" is a real topic *within* "Human Physiology",
 *     not a chapter of its own under the current syllabus. Left as an explicit,
 *     documented COARSE-BUCKET alias in [com.checkmate.learning.analytics.ConceptWeightage]
 *     rather than given its own entry here — see that file's ALIASES doc for why.
 */
object PYQWeightage {

    /** How much to trust a [WeightageEntry]'s `percent` — surfaced instead of
     *  discarded so a downstream consumer (ScoreGainEstimator, eventually) can
     *  tell "well-evidenced" apart from "single hand-typed guess" per the
     *  Upgrade Blueprint's Phase-1-gate item on enriching this table's shape. */
    enum class Confidence {
        /** Grounded in a real observed question count from an actual paper. */
        HIGH,
        /** Consistent across multiple coaching-institute PYQ analyses, or a
         *  single real paper's count where only one year of data exists. */
        MEDIUM,
        /** Hand-typed estimate, no raw count backing it — treat as a rough prior. */
        ESTIMATED
    }

    /**
     * `questionCount`/`yearsCovered` are non-null only when `confidence == HIGH`
     * or a real single-year count backs a `MEDIUM` entry — null otherwise, same
     * "0/null is the correct unknown-value default" discipline used throughout
     * this codebase (see [com.checkmate.learning.analytics.ConceptWeightage]).
     */
    data class WeightageEntry(
        val percent: Float,
        val questionCount: Int? = null,
        val yearsCovered: Int? = null,
        val confidence: Confidence = Confidence.ESTIMATED
    )

    private fun w(
        percent: Float,
        confidence: Confidence = Confidence.ESTIMATED,
        questionCount: Int? = null,
        yearsCovered: Int? = null
    ) = WeightageEntry(percent, questionCount, yearsCovered, confidence)

    // Map<Exam, Map<Subject, Map<CanonicalChapter, WeightageEntry>>>
    val data: Map<String, Map<String, Map<String, WeightageEntry>>> = mapOf(

        "NEET" to mapOf(
            // Keys are the EXACT chapter strings from ExamSyllabus.data["NEET"]["Physics"].
            "Physics" to mapOf(
                "Physics And Measurement" to w(2.0f),
                // Covers Motion in a Straight Line AND Motion in a Plane/Projectile
                // Motion/Relative Velocity — all real topics under this one NTA unit.
                "Kinematics" to w(7.0f, Confidence.MEDIUM),
                "Laws Of Motion" to w(7.0f, Confidence.MEDIUM),
                "Work Energy And Power" to w(3.5f),
                // "Motion of System of Particles and Rigid Body" — real 2026 paper count.
                "Rotational Motion" to w(8.9f, Confidence.HIGH, questionCount = 4, yearsCovered = 1),
                "Gravitation" to w(4.5f),
                "Properties Of Solids And Liquids" to w(3.5f),
                "Thermodynamics" to w(6.0f, Confidence.MEDIUM),
                "Kinetic Theory Of Gases" to w(2.5f),
                // Merges the old separate SHM (4.5) + Waves and Sound (6.0) legacy
                // entries into the single NTA unit that now covers both.
                "Oscillations And Waves" to w(9.0f, Confidence.MEDIUM),
                "Electrostatics" to w(8.9f, Confidence.HIGH, questionCount = 4, yearsCovered = 1),
                "Current Electricity" to w(6.0f, Confidence.MEDIUM),
                // Merges old standalone "Magnetism" into the unit that now covers
                // both magnetic-effects-of-current and magnetism-and-matter content.
                "Magnetic Effects Of Current And Magnetism" to w(8.9f, Confidence.HIGH, questionCount = 4, yearsCovered = 1),
                "Electromagnetic Induction And Alternating Currents" to w(8.9f, Confidence.HIGH, questionCount = 4, yearsCovered = 1),
                "Electromagnetic Waves" to w(1.5f),
                "Optics" to w(8.9f, Confidence.HIGH, questionCount = 4, yearsCovered = 1),
                "Dual Nature Of Matter And Radiation" to w(3.5f),
                "Atoms And Nuclei" to w(4.5f, Confidence.MEDIUM),
                "Electronic Devices" to w(4.0f, Confidence.MEDIUM),
                "Experimental Skills" to w(2.0f)
            ),
            // Keys are the EXACT chapter strings from ExamSyllabus.data["NEET"]["Chemistry"].
            "Chemistry" to mapOf(
                "Some Basic Concepts In Chemistry" to w(3.0f),
                "Atomic Structure" to w(6.5f),
                "Chemical Bonding And Molecular Structure" to w(8.9f, Confidence.HIGH, questionCount = 4, yearsCovered = 1),
                "Chemical Thermodynamics" to w(7.5f),
                "Solutions" to w(5.5f),
                "Equilibrium" to w(6.0f, Confidence.MEDIUM),
                "Redox Reactions And Electrochemistry" to w(6.0f),
                "Chemical Kinetics" to w(4.5f),
                // Previously missing entirely — session report gap #2.
                "Classification Of Elements And Periodicity In Properties" to w(3.0f, Confidence.MEDIUM),
                "P-Block Elements" to w(6.0f),
                "d And f Block Elements" to w(3.5f),
                "Co-Ordination Compounds" to w(5.0f),
                "Purification And Characterisation Of Organic Compounds" to w(2.0f),
                "Some Basic Principles Of Organic Chemistry" to w(3.5f),
                "Hydrocarbons" to w(4.0f),
                "Organic Compounds Containing Halogens" to w(3.5f),
                // Merges old Aldehydes Ketones Acids (4.0) + Alcohols Phenols Ethers (3.5).
                "Organic Compounds Containing Oxygen" to w(7.0f, Confidence.MEDIUM),
                "Organic Compounds Containing Nitrogen" to w(3.0f),
                "Biomolecules" to w(4.5f, Confidence.MEDIUM),
                "Principles Related To Practical Chemistry" to w(2.0f)
            ),
            // Keys are the EXACT chapter strings from ExamSyllabus.data["NEET"]["Biology"].
            "Biology" to mapOf(
                // Merges old Diversity of Living World (5.0) + Animal Kingdom (3.5) +
                // Plant Kingdom (3.0) + Biological Classification (2.5).
                "Diversity In Living World" to w(9.0f, Confidence.MEDIUM),
                "Structural Organisation In Animals And Plants" to w(4.5f),
                // Merges old Cell Biology (9.0) + Cell Division (5.5) + Biology's own
                // Biomolecules (4.0) — Biomolecules is a topic here now, not its own
                // chapter (also removes the old cross-subject key collision with
                // Chemistry's separate "Biomolecules" entry above).
                "Cell Structure And Function" to w(15.0f, Confidence.MEDIUM),
                "Plant Physiology" to w(7.5f),
                "Human Physiology" to w(12.0f),
                "Reproduction" to w(9.0f),
                "Genetics And Evolution" to w(11.5f, Confidence.MEDIUM),
                // Merges old Microbes in Human Welfare (2.0) + Strategies for
                // Enhancement (2.0).
                "Biology And Human Welfare" to w(4.0f),
                "Biotechnology And Its Applications" to w(6.0f),
                // Merges old Ecology (8.5) + Environmental Issues (2.0) + Biodiversity (2.0).
                "Ecology And Environment" to w(9.5f, Confidence.MEDIUM)
            )
        ),

        // JEE / SSC CGL — unchanged content this session, only wrapped in the new
        // WeightageEntry envelope. Still old-style, not-yet-canonical-chapter keys.
        "JEE" to mapOf(
            "Mathematics" to mapOf(
                "Calculus — Integrals" to w(11.0f),
                "Calculus — Differentials" to w(10.0f),
                "Coordinate Geometry" to w(9.5f),
                "Algebra" to w(9.0f),
                "Vectors and 3D" to w(8.5f),
                "Trigonometry" to w(7.0f),
                "Probability" to w(6.5f),
                "Matrices and Determinants" to w(6.0f),
                "Differential Equations" to w(5.5f),
                "Sequences and Series" to w(5.0f),
                "Complex Numbers" to w(4.5f),
                "Permutations and Combinations" to w(4.0f),
                "Binomial Theorem" to w(3.5f),
                "Statistics" to w(3.0f),
                "Mathematical Reasoning" to w(2.5f)
            ),
            "Physics" to mapOf(
                "Electrostatics" to w(9.5f),
                "Current Electricity" to w(8.5f),
                "Mechanics — Newton's Laws" to w(8.0f),
                "Rotational Dynamics" to w(7.5f),
                "Wave Optics" to w(7.0f),
                "Modern Physics" to w(7.0f),
                "Electromagnetic Induction" to w(6.5f),
                "Thermodynamics" to w(6.0f),
                "Kinematics" to w(5.5f),
                "Simple Harmonic Motion" to w(5.0f),
                "Ray Optics" to w(5.0f),
                "Magnetism" to w(4.5f),
                "Gravitation" to w(4.0f),
                "AC Circuits" to w(4.0f),
                "Fluid Mechanics" to w(3.5f),
                "Semiconductors" to w(3.5f),
                "Waves and Sound" to w(3.0f)
            ),
            "Chemistry" to mapOf(
                "Organic Mechanisms" to w(10.0f),
                "Chemical Bonding" to w(8.5f),
                "Thermodynamics" to w(8.0f),
                "Electrochemistry" to w(7.5f),
                "p-Block Elements" to w(7.0f),
                "Coordination Compounds" to w(6.5f),
                "Chemical Kinetics" to w(6.0f),
                "Equilibrium" to w(6.0f),
                "Atomic Structure" to w(5.5f),
                "Solutions" to w(5.0f),
                "d and f Block" to w(5.0f),
                "Aldehydes Ketones Acids" to w(4.5f),
                "Solid State" to w(4.0f),
                "Haloalkanes" to w(3.5f),
                "Alcohol Phenols" to w(3.5f),
                "Amines" to w(3.0f),
                "Biomolecules" to w(3.0f)
            )
        ),

        "SSC CGL" to mapOf(
            "Quantitative Aptitude" to mapOf(
                "Arithmetic — Percentage Profit Loss Interest" to w(16.0f),
                "Data Interpretation" to w(10.0f),
                "Geometry" to w(10.0f),
                "Time Speed and Distance" to w(9.0f),
                "Algebra" to w(9.0f),
                "Trigonometry" to w(9.0f),
                "Mensuration" to w(9.0f),
                "Time and Work" to w(8.0f),
                "Number System" to w(8.0f),
                "Average and Ratio" to w(7.0f),
                "Mixture and Alligation" to w(5.0f)
            ),
            "General Intelligence & Reasoning" to mapOf(
                "Puzzles and Seating Arrangement" to w(14.0f),
                "Analogies and Classification" to w(13.0f),
                "Series Completion" to w(12.0f),
                "Coding-Decoding" to w(10.0f),
                "Syllogism" to w(10.0f),
                "Non-Verbal Reasoning — Mirror and Water Images" to w(9.0f),
                "Blood Relations" to w(8.0f),
                "Matrix Based Reasoning" to w(8.0f),
                "Direction Sense" to w(7.0f),
                "Statement and Conclusion" to w(6.0f),
                "Paper Folding and Cutting" to w(3.0f)
            ),
            "English Language" to mapOf(
                "Reading Comprehension" to w(18.0f),
                "Spotting Errors" to w(14.0f),
                "Cloze Test" to w(12.0f),
                "Sentence Improvement" to w(12.0f),
                "Para Jumbles" to w(10.0f),
                "Synonyms and Antonyms" to w(10.0f),
                "One Word Substitution" to w(8.0f),
                "Idioms and Phrases" to w(8.0f),
                "Fill in the Blanks" to w(8.0f)
            ),
            "General Awareness" to mapOf(
                "Current Affairs — National and International" to w(20.0f),
                "Science and Technology" to w(14.0f),
                "Indian History" to w(12.0f),
                "Indian Polity and Constitution" to w(12.0f),
                "Indian Geography" to w(10.0f),
                "Indian Economy" to w(10.0f),
                "Static Awareness — Books Awards Days" to w(8.0f),
                "Government Schemes" to w(8.0f),
                "Sports and Honours" to w(6.0f)
            )
        )
    )

    fun getWeightage(exam: String, subject: String, topic: String): Float =
        data[exam]?.get(subject)?.get(topic)?.percent ?: 0f

    /** Full entry (percent + evidence metadata), for callers that care about
     *  confidence rather than just the bare number — e.g. [com.checkmate.learning.analytics.ConceptWeightage]. */
    fun getEntry(exam: String, subject: String, topic: String): WeightageEntry? =
        data[exam]?.get(subject)?.get(topic)

    /** Returns top N topics by PYQ weight for a given exam+subject */
    fun getTopTopics(exam: String, subject: String, n: Int = 5): List<Pair<String, Float>> {
        return data[exam]?.get(subject)
            ?.entries
            ?.sortedByDescending { it.value.percent }
            ?.take(n)
            ?.map { Pair(it.key, it.value.percent) }
            ?: emptyList()
    }

    /** Finds weightage across all subjects for a given topic (fuzzy match) */
    fun findTopicWeightage(exam: String, topicFragment: String): Float {
        return data[exam]?.values?.flatMap { it.entries }
            ?.firstOrNull { it.key.contains(topicFragment, ignoreCase = true) }
            ?.value?.percent ?: 0f
    }
}
