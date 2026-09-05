package com.checkmate.learning.graph

import android.content.Context
import com.checkmate.core.ExamSyllabus
import com.checkmate.learning.model.Concept
import com.checkmate.learning.repository.LearningDatabase
import java.security.MessageDigest

/**
 * Upgrade Blueprint Phase 1.4 ("Concept-level knowledge graph, not chapter-level
 * weakness"). See [Concept]'s class doc for the honest limitation on granularity —
 * one Concept here = one [ExamSyllabus] topic, not a true sub-topic concept, since
 * nothing in this codebase defines concepts finer than that.
 */
object KnowledgeGraph {

    // BUG CAUGHT AGAINST REAL DATA: Testmate's own report.md carries exam strings
    // like "NEET-2027" (title-embedded year — see the sample
    // ft-01b-full-test-neet-2027--report.md), but ExamSyllabus's top-level keys are
    // bare exam names ("NEET", "JEE", ...). Without normalizing, every real import
    // would key concepts under "neet-2027-..." while seedExamSyllabus("NEET") keys
    // them under "neet-...", and the two would never join — mastery computed from
    // real attempts would sit in a completely different set of rows than the
    // syllabus-seeded prerequisite graph. Stripping a trailing "-YYYY" fixes the
    // common case. An exam string that doesn't end in a year, or an ExamSyllabus
    // key that later grows its own year suffix, would need this revisited.
    private val YEAR_SUFFIX = Regex("""-(19|20)\d{2}$""")
    // Was private — widened to internal so the one real call site for
    // seedExamSyllabus (TestResultNormalizer, wiring it in for the first time) can
    // normalize a report's raw exam string ("NEET-2027") to an ExamSyllabus key
    // ("NEET") before calling it, without duplicating this regex there.
    internal fun normalizeExam(exam: String): String = YEAR_SUFFIX.replace(exam.trim(), "")

    /**
     * Deterministic id from (exam, chapter, topic) — deliberately NOT subject. See
     * [com.checkmate.learning.engine.MasteryEngine]'s class doc: real
     * TestResultNormalizer-imported Questions never carry a subject (report.md has
     * no subject field), so keying on subject would split a seeded syllabus concept
     * and its real-import attempts into two rows that never join. `topic` falls
     * back to `chapter` when null, matching TestReportParser's own dash-to-null
     * normalization (chapter-level granularity when Testmate reports no topic).
     */
    fun conceptId(exam: String, chapter: String, topic: String?): String {
        val key = "${normalizeExam(exam).lowercase()}|${chapter.trim().lowercase()}|" +
            "${(topic ?: chapter).trim().lowercase()}"
        return slugify(key)
    }

    private fun slugify(raw: String): String {
        val slug = raw.replace(Regex("[^a-z0-9]+"), "-").trim('-')
        // Hash suffix guards against two different raw keys slugifying to the same
        // truncated prefix — cheap insurance, not expected to matter at this data scale.
        val hash = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(8)
        return "${slug.take(60)}-$hash"
    }

    /**
     * Topic-level prerequisite graph for NEET and JEE — every (chapter, topic) pair
     * here is checked at generation time to match an actual leaf in
     * [ExamSyllabus.data] (no free-floating/typo'd concept names), and the combined
     * graph is verified acyclic (no topic can trace back to itself through its own
     * prerequisite chain) before being committed here.
     *
     * GRANULARITY MISMATCH BETWEEN NEET AND JEE, BY NECESSITY: NEET chapters now
     * match [ExamSyllabus]'s real per-unit granularity (rebuilt off the actual
     * NTA/NMC NEET(UG)-2026 notified syllabus — see that file's doc comment), so
     * NEET edges here are correspondingly fine-grained but NOT exhaustive: they
     * cover the between-unit dependency chain (roughly NTA's own unit ordering,
     * e.g. Kinematics -> Laws Of Motion -> Work Energy And Power) plus a few
     * additional real cross-links (e.g. Optics also needs Oscillations And Waves,
     * not just the linear chain), rather than every possible topic-to-topic edge
     * within 400+ NEET topics — that density wouldn't add real diagnostic signal
     * over the same unit-level chain. JEE chapters are still the earlier coarser
     * grouping (e.g. "Mechanics" as one chapter covering many NTA-JEE units) and
     * haven't had the same real-syllabus rebuild yet — same treatment applies to
     * JEE if/when needed, matching how [ExamSyllabus]'s own doc comment flags it.
     *
     * SCOPE CALL, not an oversight: CUET and SSC CGL are deliberately NOT covered.
     * CUET's Biology/Chemistry/Physics blocks are the same NEET/JEE content
     * re-labelled "Class 11 X / Class 12 X" — a real prerequisite tree for it would
     * just be this same NEET/JEE graph reprojected onto renamed chapters, which
     * isn't worth a second maintained copy. SSC CGL's chapters (verbal/non-verbal
     * reasoning, vocabulary, current affairs, static GK) don't have a genuine
     * prerequisite structure the way STEM concepts do — "Idioms and Phrases" isn't
     * downstream of "Synonyms and Antonyms" in any real pedagogical sense — so
     * inventing edges there would be fabricated structure, not curriculum accuracy.
     * Revisit if CUET/SSC CGL ever need real diagnostic depth of their own.
     *
     * [seedExamSyllabus] applies whatever's here; edges for an exam this list
     * doesn't cover simply produce no ConceptDependency rows for that exam (existing
     * fail-open behavior, unchanged).
     */
    private data class PrerequisiteSeed(
        val exam: String, val chapter: String, val topic: String,
        val prerequisiteChapter: String, val prerequisiteTopic: String
    )

    private val SEED_PREREQUISITES = listOf(
        // JEE Mathematics — Algebra
        PrerequisiteSeed("JEE", "Algebra", "Quadratic Equations", "Algebra", "Complex Numbers"),
        PrerequisiteSeed("JEE", "Algebra", "Sequences and Series", "Algebra", "Quadratic Equations"),
        PrerequisiteSeed("JEE", "Algebra", "Permutations and Combinations", "Algebra", "Sequences and Series"),
        PrerequisiteSeed("JEE", "Algebra", "Binomial Theorem", "Algebra", "Permutations and Combinations"),
        PrerequisiteSeed("JEE", "Algebra", "Matrices and Determinants", "Algebra", "Quadratic Equations"),
        PrerequisiteSeed("JEE", "Algebra", "Mathematical Induction", "Algebra", "Sequences and Series"),
        // JEE Mathematics — Trigonometry
        PrerequisiteSeed("JEE", "Trigonometry", "Trigonometric Equations", "Trigonometry", "Trigonometric Ratios"),
        PrerequisiteSeed("JEE", "Trigonometry", "Inverse Trigonometry", "Trigonometry", "Trigonometric Ratios"),
        PrerequisiteSeed("JEE", "Trigonometry", "Properties of Triangles", "Trigonometry", "Trigonometric Ratios"),
        PrerequisiteSeed("JEE", "Trigonometry", "Properties of Triangles", "Trigonometry", "Inverse Trigonometry"),
        // JEE Mathematics — Calculus
        PrerequisiteSeed("JEE", "Calculus", "Differentiation", "Calculus", "Limits and Continuity"),
        PrerequisiteSeed("JEE", "Calculus", "Applications of Derivatives", "Calculus", "Differentiation"),
        PrerequisiteSeed("JEE", "Calculus", "Integration", "Calculus", "Differentiation"),
        PrerequisiteSeed("JEE", "Calculus", "Definite Integrals", "Calculus", "Integration"),
        PrerequisiteSeed("JEE", "Calculus", "Area Under Curves", "Calculus", "Definite Integrals"),
        PrerequisiteSeed("JEE", "Calculus", "Differential Equations", "Calculus", "Integration"),
        // JEE Mathematics — Coordinate Geometry
        PrerequisiteSeed("JEE", "Coordinate Geometry", "Circles", "Coordinate Geometry", "Straight Lines"),
        PrerequisiteSeed("JEE", "Coordinate Geometry", "Parabola", "Coordinate Geometry", "Straight Lines"),
        PrerequisiteSeed("JEE", "Coordinate Geometry", "Ellipse", "Coordinate Geometry", "Parabola"),
        PrerequisiteSeed("JEE", "Coordinate Geometry", "Hyperbola", "Coordinate Geometry", "Ellipse"),
        // JEE Mathematics — Vectors and 3D
        PrerequisiteSeed("JEE", "Vectors and 3D", "3D Geometry", "Vectors and 3D", "Vectors"),
        PrerequisiteSeed("JEE", "Vectors and 3D", "Planes and Lines in 3D", "Vectors and 3D", "3D Geometry"),
        // JEE Mathematics — Probability and Statistics
        PrerequisiteSeed("JEE", "Probability and Statistics", "Bayes Theorem", "Probability and Statistics", "Probability"),
        PrerequisiteSeed("JEE", "Probability and Statistics", "Random Variables", "Probability and Statistics", "Probability"),
        PrerequisiteSeed("JEE", "Probability and Statistics", "Statistics", "Probability and Statistics", "Random Variables"),
        // JEE Physics — Mechanics
        PrerequisiteSeed("JEE", "Mechanics", "Newton's Laws", "Mechanics", "Kinematics"),
        PrerequisiteSeed("JEE", "Mechanics", "Work Energy Theorem", "Mechanics", "Newton's Laws"),
        PrerequisiteSeed("JEE", "Mechanics", "Rotational Dynamics", "Mechanics", "Newton's Laws"),
        PrerequisiteSeed("JEE", "Mechanics", "Rotational Dynamics", "Mechanics", "Work Energy Theorem"),
        PrerequisiteSeed("JEE", "Mechanics", "Gravitation", "Mechanics", "Newton's Laws"),
        PrerequisiteSeed("JEE", "Mechanics", "Gravitation", "Mechanics", "Work Energy Theorem"),
        PrerequisiteSeed("JEE", "Mechanics", "Fluid Mechanics", "Mechanics", "Work Energy Theorem"),
        PrerequisiteSeed("JEE", "Mechanics", "Simple Harmonic Motion", "Mechanics", "Newton's Laws"),
        PrerequisiteSeed("JEE", "Mechanics", "Waves and Sound", "Mechanics", "Simple Harmonic Motion"),
        // JEE Physics — Thermodynamics
        PrerequisiteSeed("JEE", "Thermodynamics", "Kinetic Theory", "Mechanics", "Newton's Laws"),
        PrerequisiteSeed("JEE", "Thermodynamics", "Laws of Thermodynamics", "Thermodynamics", "Kinetic Theory"),
        PrerequisiteSeed("JEE", "Thermodynamics", "Calorimetry", "Thermodynamics", "Laws of Thermodynamics"),
        // JEE Physics — Electromagnetism
        PrerequisiteSeed("JEE", "Electromagnetism", "Current Electricity", "Electromagnetism", "Electrostatics"),
        PrerequisiteSeed("JEE", "Electromagnetism", "Magnetism", "Electromagnetism", "Current Electricity"),
        PrerequisiteSeed("JEE", "Electromagnetism", "Electromagnetic Induction", "Electromagnetism", "Magnetism"),
        PrerequisiteSeed("JEE", "Electromagnetism", "AC Circuits", "Electromagnetism", "Electromagnetic Induction"),
        PrerequisiteSeed("JEE", "Electromagnetism", "EM Waves", "Electromagnetism", "AC Circuits"),
        // JEE Physics — Optics and Modern Physics
        PrerequisiteSeed("JEE", "Optics and Modern Physics", "Wave Optics", "Optics and Modern Physics", "Ray Optics"),
        PrerequisiteSeed("JEE", "Optics and Modern Physics", "Photoelectric Effect", "Optics and Modern Physics", "Wave Optics"),
        PrerequisiteSeed("JEE", "Optics and Modern Physics", "Photoelectric Effect", "Electromagnetism", "EM Waves"),
        PrerequisiteSeed("JEE", "Optics and Modern Physics", "Atomic Models", "Optics and Modern Physics", "Photoelectric Effect"),
        PrerequisiteSeed("JEE", "Optics and Modern Physics", "Nuclear Physics", "Optics and Modern Physics", "Atomic Models"),
        PrerequisiteSeed("JEE", "Optics and Modern Physics", "Semiconductors", "Electromagnetism", "Current Electricity"),
        PrerequisiteSeed("JEE", "Optics and Modern Physics", "Semiconductors", "Optics and Modern Physics", "Atomic Models"),
        // JEE Chemistry — Physical Chemistry
        PrerequisiteSeed("JEE", "Physical Chemistry", "Chemical Bonding", "Physical Chemistry", "Atomic Structure"),
        PrerequisiteSeed("JEE", "Physical Chemistry", "Chemical Equilibrium", "Physical Chemistry", "Thermodynamics"),
        PrerequisiteSeed("JEE", "Physical Chemistry", "Thermodynamics", "Physical Chemistry", "Chemical Bonding"),
        PrerequisiteSeed("JEE", "Physical Chemistry", "Electrochemistry", "Physical Chemistry", "Chemical Equilibrium"),
        PrerequisiteSeed("JEE", "Physical Chemistry", "Chemical Kinetics", "Physical Chemistry", "Chemical Equilibrium"),
        PrerequisiteSeed("JEE", "Physical Chemistry", "Solutions", "Physical Chemistry", "Chemical Equilibrium"),
        PrerequisiteSeed("JEE", "Physical Chemistry", "Solid State", "Physical Chemistry", "Chemical Bonding"),
        PrerequisiteSeed("JEE", "Physical Chemistry", "Surface Chemistry", "Physical Chemistry", "Chemical Kinetics"),
        // JEE Chemistry — Organic Chemistry
        PrerequisiteSeed("JEE", "Organic Chemistry", "Reaction Mechanisms", "Organic Chemistry", "IUPAC Nomenclature"),
        PrerequisiteSeed("JEE", "Organic Chemistry", "Hydrocarbons", "Organic Chemistry", "IUPAC Nomenclature"),
        PrerequisiteSeed("JEE", "Organic Chemistry", "Functional Group Chemistry", "Organic Chemistry", "Hydrocarbons"),
        PrerequisiteSeed("JEE", "Organic Chemistry", "Functional Group Chemistry", "Organic Chemistry", "Reaction Mechanisms"),
        PrerequisiteSeed("JEE", "Organic Chemistry", "Biomolecules", "Organic Chemistry", "Functional Group Chemistry"),
        PrerequisiteSeed("JEE", "Organic Chemistry", "Polymers", "Organic Chemistry", "Functional Group Chemistry"),
        PrerequisiteSeed("JEE", "Organic Chemistry", "Practical Organic Chemistry", "Organic Chemistry", "Functional Group Chemistry"),
        // JEE Chemistry — Inorganic Chemistry
        PrerequisiteSeed("JEE", "Inorganic Chemistry", "Chemical Bonding Advanced", "Inorganic Chemistry", "Periodic Table Trends"),
        PrerequisiteSeed("JEE", "Inorganic Chemistry", "Chemical Bonding Advanced", "Physical Chemistry", "Chemical Bonding"),
        PrerequisiteSeed("JEE", "Inorganic Chemistry", "s p d f Block Elements", "Inorganic Chemistry", "Periodic Table Trends"),
        PrerequisiteSeed("JEE", "Inorganic Chemistry", "Coordination Chemistry", "Inorganic Chemistry", "s p d f Block Elements"),
        PrerequisiteSeed("JEE", "Inorganic Chemistry", "Coordination Chemistry", "Inorganic Chemistry", "Chemical Bonding Advanced"),
        PrerequisiteSeed("JEE", "Inorganic Chemistry", "Analytical Chemistry", "Inorganic Chemistry", "Coordination Chemistry"),
        PrerequisiteSeed("JEE", "Inorganic Chemistry", "Metallurgy", "Inorganic Chemistry", "s p d f Block Elements"),
        // NEET — Kinematics
        PrerequisiteSeed("NEET", "Kinematics", "Frame of Reference", "Physics And Measurement", "Dimensional Analysis and Applications"),
        // NEET — Laws Of Motion
        PrerequisiteSeed("NEET", "Laws Of Motion", "Force and Inertia", "Kinematics", "Uniformly Accelerated Motion"),
        // NEET — Work Energy And Power
        PrerequisiteSeed("NEET", "Work Energy And Power", "Work Done by Constant and Variable Force", "Laws Of Motion", "Newton's Second Law of Motion"),
        // NEET — Rotational Motion
        PrerequisiteSeed("NEET", "Rotational Motion", "Centre of Mass", "Work Energy And Power", "Work-Energy Theorem"),
        // NEET — Gravitation
        PrerequisiteSeed("NEET", "Gravitation", "Universal Law of Gravitation", "Rotational Motion", "Moment of Inertia"),
        // NEET — Properties Of Solids And Liquids
        PrerequisiteSeed("NEET", "Properties Of Solids And Liquids", "Elastic Behaviour", "Gravitation", "Universal Law of Gravitation"),
        // NEET — Thermodynamics
        PrerequisiteSeed("NEET", "Thermodynamics", "Thermal Equilibrium", "Properties Of Solids And Liquids", "Bernoulli's Principle"),
        // NEET — Kinetic Theory Of Gases
        PrerequisiteSeed("NEET", "Kinetic Theory Of Gases", "Equation of State of a Perfect Gas", "Thermodynamics", "First Law of Thermodynamics"),
        // NEET — Oscillations And Waves
        PrerequisiteSeed("NEET", "Oscillations And Waves", "Periodic Motion", "Kinetic Theory Of Gases", "Kinetic Interpretation of Temperature"),
        // NEET — Electrostatics
        PrerequisiteSeed("NEET", "Electrostatics", "Electric Charges and Conservation of Charge", "Oscillations And Waves", "Simple Harmonic Motion"),
        // NEET — Current Electricity
        PrerequisiteSeed("NEET", "Current Electricity", "Electric Current", "Electrostatics", "Gauss's Law and Applications"),
        // NEET — Magnetic Effects Of Current And Magnetism
        PrerequisiteSeed("NEET", "Magnetic Effects Of Current And Magnetism", "Biot-Savart Law", "Current Electricity", "Ohm's Law"),
        // NEET — Electromagnetic Induction And Alternating Currents
        PrerequisiteSeed("NEET", "Electromagnetic Induction And Alternating Currents", "Faraday's Law", "Magnetic Effects Of Current And Magnetism", "Biot-Savart Law"),
        // NEET — Electromagnetic Waves
        PrerequisiteSeed("NEET", "Electromagnetic Waves", "Displacement Current", "Electromagnetic Induction And Alternating Currents", "Faraday's Law"),
        // NEET — Optics
        PrerequisiteSeed("NEET", "Optics", "Reflection of Light", "Electromagnetic Waves", "Characteristics of Electromagnetic Waves"),
        // NEET — Dual Nature Of Matter And Radiation
        PrerequisiteSeed("NEET", "Dual Nature Of Matter And Radiation", "Photoelectric Effect", "Optics", "Refraction of Light"),
        // NEET — Atoms And Nuclei
        PrerequisiteSeed("NEET", "Atoms And Nuclei", "Alpha-Particle Scattering Experiment", "Dual Nature Of Matter And Radiation", "Photoelectric Effect"),
        // NEET — Electronic Devices
        PrerequisiteSeed("NEET", "Electronic Devices", "Semiconductors", "Atoms And Nuclei", "Bohr Model and Energy Levels"),
        // NEET — Optics
        PrerequisiteSeed("NEET", "Optics", "Wavefront and Huygens' Principle", "Oscillations And Waves", "Wave Motion"),
        // NEET — Electronic Devices
        PrerequisiteSeed("NEET", "Electronic Devices", "Semiconductors", "Current Electricity", "Ohm's Law"),
        // NEET — Atomic Structure
        PrerequisiteSeed("NEET", "Atomic Structure", "Electromagnetic Radiation and Photoelectric Effect", "Some Basic Concepts In Chemistry", "Mole Concept and Molar Mass"),
        // NEET — Chemical Bonding And Molecular Structure
        PrerequisiteSeed("NEET", "Chemical Bonding And Molecular Structure", "Kossel-Lewis Approach", "Atomic Structure", "Quantum Mechanical Model of Atom"),
        // NEET — Chemical Thermodynamics
        PrerequisiteSeed("NEET", "Chemical Thermodynamics", "System and Surroundings", "Chemical Bonding And Molecular Structure", "Valence Bond Theory"),
        // NEET — Equilibrium
        PrerequisiteSeed("NEET", "Equilibrium", "Dynamic Equilibrium", "Chemical Thermodynamics", "First Law of Thermodynamics"),
        // NEET — Solutions
        PrerequisiteSeed("NEET", "Solutions", "Methods of Expressing Concentration", "Equilibrium", "Law of Chemical Equilibrium"),
        // NEET — Redox Reactions And Electrochemistry
        PrerequisiteSeed("NEET", "Redox Reactions And Electrochemistry", "Oxidation and Reduction", "Solutions", "Colligative Properties"),
        // NEET — Chemical Kinetics
        PrerequisiteSeed("NEET", "Chemical Kinetics", "Rate of a Chemical Reaction", "Redox Reactions And Electrochemistry", "Oxidation Number"),
        // NEET — Classification Of Elements And Periodicity In Properties
        PrerequisiteSeed("NEET", "Classification Of Elements And Periodicity In Properties", "Modern Periodic Law", "Chemical Kinetics", "Rate Law and Rate Constant"),
        // NEET — P-Block Elements
        PrerequisiteSeed("NEET", "P-Block Elements", "Group 13 to Group 18 Elements", "Classification Of Elements And Periodicity In Properties", "Periodic Trends — Atomic and Ionic Radii"),
        // NEET — d And f Block Elements
        PrerequisiteSeed("NEET", "d And f Block Elements", "Transition Elements — Electronic Configuration and Occurrence", "P-Block Elements", "Electronic Configuration Trends"),
        // NEET — Co-Ordination Compounds
        PrerequisiteSeed("NEET", "Co-Ordination Compounds", "Werner's Theory", "d And f Block Elements", "Transition Elements — Electronic Configuration and Occurrence"),
        // NEET — Solutions
        PrerequisiteSeed("NEET", "Solutions", "Colligative Properties", "Equilibrium", "Law of Chemical Equilibrium"),
        // NEET — Some Basic Principles Of Organic Chemistry
        PrerequisiteSeed("NEET", "Some Basic Principles Of Organic Chemistry", "Hybridization and Shapes of Molecules", "Chemical Bonding And Molecular Structure", "Hybridization"),
        PrerequisiteSeed("NEET", "Some Basic Principles Of Organic Chemistry", "Tetravalency of Carbon", "Purification And Characterisation Of Organic Compounds", "Qualitative Analysis — Detection of N S P Halogens"),
        // NEET — Hydrocarbons
        PrerequisiteSeed("NEET", "Hydrocarbons", "Classification Isomerism and Nomenclature of Hydrocarbons", "Some Basic Principles Of Organic Chemistry", "Inductive Effect"),
        // NEET — Organic Compounds Containing Halogens
        PrerequisiteSeed("NEET", "Organic Compounds Containing Halogens", "Preparation Properties and Reactions of Haloalkanes and Haloarenes", "Hydrocarbons", "Electrophilic Addition Mechanism"),
        // NEET — Organic Compounds Containing Oxygen
        PrerequisiteSeed("NEET", "Organic Compounds Containing Oxygen", "Alcohols — Primary Secondary Tertiary and Dehydration Mechanism", "Organic Compounds Containing Halogens", "Mechanisms of Substitution Reactions"),
        // NEET — Organic Compounds Containing Nitrogen
        PrerequisiteSeed("NEET", "Organic Compounds Containing Nitrogen", "Amines — Nomenclature Classification and Basic Character", "Organic Compounds Containing Oxygen", "Aldehydes and Ketones — Nucleophilic Addition"),
        // NEET — Biomolecules
        PrerequisiteSeed("NEET", "Biomolecules", "Carbohydrates — Classification Aldoses and Ketoses", "Organic Compounds Containing Nitrogen", "Amines — Nomenclature Classification and Basic Character"),
        // NEET — Purification And Characterisation Of Organic Compounds
        PrerequisiteSeed("NEET", "Purification And Characterisation Of Organic Compounds", "Crystallization Sublimation Distillation", "Some Basic Principles Of Organic Chemistry", "Classification by Functional Groups"),
        // NEET — Co-Ordination Compounds
        PrerequisiteSeed("NEET", "Co-Ordination Compounds", "IUPAC Nomenclature of Coordination Compounds", "Chemical Bonding And Molecular Structure", "Valence Bond Theory"),
        // NEET — Structural Organisation In Animals And Plants
        PrerequisiteSeed("NEET", "Structural Organisation In Animals And Plants", "Morphology and Modifications of Flowering Plants", "Diversity In Living World", "Five Kingdom Classification"),
        // NEET — Cell Structure And Function
        PrerequisiteSeed("NEET", "Cell Structure And Function", "Cell Theory", "Structural Organisation In Animals And Plants", "Plant Tissues"),
        // NEET — Plant Physiology
        PrerequisiteSeed("NEET", "Plant Physiology", "Photosynthesis as Autotrophic Nutrition", "Cell Structure And Function", "Cell Cycle Mitosis and Meiosis"),
        // NEET — Human Physiology
        PrerequisiteSeed("NEET", "Human Physiology", "Respiratory System and Mechanism of Breathing", "Plant Physiology", "Photosynthesis as Autotrophic Nutrition"),
        // NEET — Reproduction
        PrerequisiteSeed("NEET", "Reproduction", "Flower Structure and Gametophyte Development", "Human Physiology", "Human Heart and Blood Vessels"),
        // NEET — Genetics And Evolution
        PrerequisiteSeed("NEET", "Genetics And Evolution", "Mendelian Inheritance", "Reproduction", "Fertilisation and Embryo Development to Blastocyst"),
        // NEET — Biology And Human Welfare
        PrerequisiteSeed("NEET", "Biology And Human Welfare", "Pathogens and Parasites Causing Human Diseases", "Genetics And Evolution", "Structure of DNA and RNA"),
        // NEET — Biotechnology And Its Applications
        PrerequisiteSeed("NEET", "Biotechnology And Its Applications", "Principles of Biotechnology", "Biology And Human Welfare", "Basic Concepts of Immunology and Vaccines"),
        PrerequisiteSeed("NEET", "Biotechnology And Its Applications", "Genetic Engineering — Recombinant DNA Technology", "Genetics And Evolution", "Structure of DNA and RNA"),
        // NEET — Reproduction
        PrerequisiteSeed("NEET", "Reproduction", "Gametogenesis — Spermatogenesis and Oogenesis", "Cell Structure And Function", "Cell Cycle Mitosis and Meiosis"),
        // NEET — Genetics And Evolution
        PrerequisiteSeed("NEET", "Genetics And Evolution", "Chromosome Theory of Inheritance", "Cell Structure And Function", "Cell Cycle Mitosis and Meiosis"),
        // NEET — Ecology And Environment
        PrerequisiteSeed("NEET", "Ecology And Environment", "Ecosystem Patterns and Components", "Structural Organisation In Animals And Plants", "Plant Tissues")
    )

    /**
     * Inserts one [Concept] row per (subject, chapter, topic) leaf of
     * ExamSyllabus.data[exam], plus whatever [SEED_PREREQUISITES] edges apply to
     * that exam. `exam` here must be an ExamSyllabus key ("NEET"), not a
     * Testmate-report exam string ("NEET-2027") — [conceptId] normalizes both to
     * the same slug internally, so this still joins correctly with real imported
     * attempts either way.
     *
     * WIRING: called from [com.checkmate.learning.testmate.TestResultNormalizer.normalizeAndPersist]
     * on every import, using `report.exam` (normalized via [normalizeExam]) as the
     * exam key — no separate onboarding flow exists to declare a student's exam
     * ahead of time, but every real import already carries its own exam string, so
     * seeding opportunistically off that is the actual first real signal available,
     * not a guess. Safe to call repeatedly for the same exam: [Concept] upsertAll
     * overwrites identically and [ConceptDependencyDao.insertAll] is
     * `OnConflictStrategy.IGNORE`, so a re-imported report re-seeding the same exam
     * is a no-op past the first time, not a duplicate/corruption risk.
     */
    suspend fun seedExamSyllabus(context: Context, exam: String) {
        val subjects = ExamSyllabus.data[exam] ?: return
        val db = LearningDatabase.getInstance(context)

        val concepts = subjects.flatMap { (subject, chapters) ->
            chapters.flatMap { (chapter, topics) ->
                topics.map { topic ->
                    Concept(
                        id = conceptId(exam, chapter, topic),
                        exam = exam,
                        subject = subject,
                        chapter = chapter,
                        topic = topic
                    )
                }
            }
        }
        db.conceptDao().upsertAll(concepts)

        val edges = SEED_PREREQUISITES
            .filter { it.exam == exam }
            .map {
                ConceptDependency(
                    conceptId = conceptId(exam, it.chapter, it.topic),
                    prerequisiteConceptId = conceptId(exam, it.prerequisiteChapter, it.prerequisiteTopic)
                )
            }
        db.conceptDependencyDao().insertAll(edges)
    }

    /**
     * Diagnoses a weak concept as a prerequisite failure where possible, per the
     * blueprint's "rolling motion failure diagnosed as prerequisite failure"
     * example. Returns the prerequisite [Concept]s whose mastery is below
     * [masteryThreshold] — empty if [conceptId] has no seeded prerequisite edges
     * (e.g. [seedExamSyllabus] was never run for its exam) or none are actually weak.
     */
    suspend fun diagnosePrerequisiteFailure(
        context: Context,
        studentId: String,
        conceptId: String,
        // Kept numerically in sync with MasteryEngine.MASTERY_THRESHOLD by hand, not
        // imported directly — avoids a graph<->engine circular reference for one constant.
        masteryThreshold: Double = 0.83
    ): List<Concept> {
        val db = LearningDatabase.getInstance(context)
        val prerequisiteIds = db.conceptDependencyDao().getPrerequisites(conceptId)
        if (prerequisiteIds.isEmpty()) return emptyList()

        return prerequisiteIds.mapNotNull { prereqId ->
            val mastery = db.masteryDao().getByConcept(studentId, prereqId)
            val isWeak = mastery == null || mastery.mastery < masteryThreshold
            if (isWeak) db.conceptDao().getById(prereqId) else null
        }
    }
}
