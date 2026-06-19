package com.checkmate.core

/**
 * Official full syllabus for JEE / NEET / CUET (NTA) and SSC CGL (Staff Selection Commission).
 * SSC CGL block reflects the 2026 notification syllabus — Tier 1 (Quantitative Aptitude,
 * General Intelligence & Reasoning, English Language, General Awareness) plus the
 * Tier 2 Paper 1 additions (Computer Knowledge, Statistics for JSO posts).
 * Structure: Exam → Subject → Chapter → List<Topic>
 */
object ExamSyllabus {

    val data: Map<String, Map<String, Map<String, List<String>>>> = mapOf(

        "NEET" to mapOf(
            "Biology" to mapOf(
                "Cell Biology" to listOf(
                    "Cell Theory", "Prokaryotic vs Eukaryotic Cells", "Cell Organelles",
                    "Cell Membrane Structure", "Endoplasmic Reticulum", "Golgi Apparatus",
                    "Mitochondria", "Chloroplasts", "Nucleus and Nucleolus", "Lysosomes and Vacuoles"
                ),
                "Cell Division" to listOf(
                    "Cell Cycle Phases", "Mitosis Stages", "Meiosis I and II",
                    "Significance of Meiosis", "Comparison of Mitosis and Meiosis",
                    "Checkpoints in Cell Cycle"
                ),
                "Genetics and Evolution" to listOf(
                    "Mendelian Genetics", "Laws of Inheritance", "Incomplete Dominance",
                    "Codominance", "Multiple Alleles", "Polygenic Inheritance",
                    "Chromosomal Theory of Inheritance", "Sex Determination",
                    "Mutations", "DNA Structure and Replication",
                    "RNA Types and Functions", "Protein Synthesis", "Gene Regulation",
                    "Human Genome Project", "DNA Fingerprinting",
                    "Darwin's Theory", "Natural Selection", "Hardy-Weinberg Equilibrium",
                    "Speciation", "Evolution Evidence"
                ),
                "Human Physiology" to listOf(
                    "Digestion and Absorption", "Breathing and Gas Exchange",
                    "Body Fluids and Circulation", "Excretory Products and Elimination",
                    "Locomotion and Movement", "Neural Control and Coordination",
                    "Chemical Coordination — Hormones", "Reproduction in Humans"
                ),
                "Plant Physiology" to listOf(
                    "Transport in Plants", "Mineral Nutrition", "Photosynthesis",
                    "Respiration in Plants", "Plant Growth and Development",
                    "Plant Hormones"
                ),
                "Reproduction" to listOf(
                    "Asexual Reproduction", "Sexual Reproduction in Flowering Plants",
                    "Human Reproduction", "Reproductive Health",
                    "Female Reproductive System", "Male Reproductive System",
                    "Fertilisation and Embryo Development", "Contraception and MTP"
                ),
                "Ecology" to listOf(
                    "Organisms and Populations", "Ecosystems",
                    "Biodiversity and Conservation", "Environmental Issues",
                    "Food Chains and Webs", "Energy Flow", "Nutrient Cycles",
                    "Ecological Succession", "Population Interactions"
                ),
                "Biotechnology" to listOf(
                    "Principles of Biotechnology", "Recombinant DNA Technology",
                    "Biotechnology Applications in Agriculture",
                    "Biotechnology Applications in Medicine",
                    "PCR", "Gel Electrophoresis", "ELISA"
                )
            ),
            "Chemistry" to mapOf(
                "Physical Chemistry" to listOf(
                    "Some Basic Concepts", "Atomic Structure", "Chemical Bonding",
                    "States of Matter", "Thermodynamics", "Equilibrium",
                    "Redox Reactions", "Solutions", "Electrochemistry",
                    "Chemical Kinetics", "Surface Chemistry"
                ),
                "Organic Chemistry" to listOf(
                    "Basic Organic Chemistry", "Hydrocarbons",
                    "Haloalkanes and Haloarenes", "Alcohols Phenols Ethers",
                    "Aldehydes Ketones Carboxylic Acids",
                    "Amines", "Biomolecules", "Polymers",
                    "Chemistry in Everyday Life",
                    "Reaction Mechanisms — SN1 SN2 E1 E2",
                    "Named Reactions"
                ),
                "Inorganic Chemistry" to listOf(
                    "s-Block Elements", "p-Block Elements", "d and f Block Elements",
                    "Coordination Compounds", "Qualitative Analysis",
                    "Metallurgy", "Hydrogen", "Environmental Chemistry"
                )
            ),
            "Physics" to mapOf(
                "Mechanics" to listOf(
                    "Units and Measurements", "Kinematics",
                    "Laws of Motion", "Work Energy Power",
                    "Rotational Motion", "Gravitation",
                    "Properties of Solids and Liquids",
                    "Oscillations", "Waves"
                ),
                "Thermodynamics" to listOf(
                    "Thermal Properties of Matter", "Thermodynamics Laws",
                    "Kinetic Theory of Gases", "Heat Transfer"
                ),
                "Electromagnetism" to listOf(
                    "Electric Charges and Fields", "Electrostatic Potential",
                    "Current Electricity", "Magnetic Effects of Current",
                    "Magnetism and Matter",
                    "Electromagnetic Induction", "Alternating Current",
                    "Electromagnetic Waves"
                ),
                "Optics" to listOf(
                    "Ray Optics", "Wave Optics"
                ),
                "Modern Physics" to listOf(
                    "Dual Nature of Matter", "Atoms and Nuclei",
                    "Electronic Devices", "Communication Systems"
                )
            )
        ),

        "JEE" to mapOf(
            "Mathematics" to mapOf(
                "Algebra" to listOf(
                    "Complex Numbers", "Quadratic Equations", "Sequences and Series",
                    "Permutations and Combinations", "Binomial Theorem",
                    "Matrices and Determinants", "Mathematical Induction"
                ),
                "Trigonometry" to listOf(
                    "Trigonometric Ratios", "Trigonometric Equations",
                    "Inverse Trigonometry", "Properties of Triangles"
                ),
                "Calculus" to listOf(
                    "Limits and Continuity", "Differentiation", "Applications of Derivatives",
                    "Integration", "Definite Integrals", "Differential Equations",
                    "Area Under Curves"
                ),
                "Coordinate Geometry" to listOf(
                    "Straight Lines", "Circles", "Parabola", "Ellipse", "Hyperbola"
                ),
                "Vectors and 3D" to listOf(
                    "Vectors", "3D Geometry", "Planes and Lines in 3D"
                ),
                "Probability and Statistics" to listOf(
                    "Probability", "Bayes Theorem", "Statistics", "Random Variables"
                )
            ),
            "Physics" to mapOf(
                "Mechanics" to listOf(
                    "Kinematics", "Newton's Laws", "Work Energy Theorem",
                    "Rotational Dynamics", "Gravitation", "Simple Harmonic Motion",
                    "Waves and Sound", "Fluid Mechanics"
                ),
                "Thermodynamics" to listOf(
                    "Laws of Thermodynamics", "Kinetic Theory", "Calorimetry"
                ),
                "Electromagnetism" to listOf(
                    "Electrostatics", "Current Electricity", "Magnetism",
                    "Electromagnetic Induction", "AC Circuits", "EM Waves"
                ),
                "Optics and Modern Physics" to listOf(
                    "Ray Optics", "Wave Optics", "Photoelectric Effect",
                    "Atomic Models", "Nuclear Physics", "Semiconductors"
                )
            ),
            "Chemistry" to mapOf(
                "Physical Chemistry" to listOf(
                    "Atomic Structure", "Chemical Bonding", "Thermodynamics",
                    "Chemical Equilibrium", "Electrochemistry", "Chemical Kinetics",
                    "Solutions", "Solid State", "Surface Chemistry"
                ),
                "Organic Chemistry" to listOf(
                    "IUPAC Nomenclature", "Reaction Mechanisms",
                    "Hydrocarbons", "Functional Group Chemistry",
                    "Biomolecules", "Polymers", "Practical Organic Chemistry"
                ),
                "Inorganic Chemistry" to listOf(
                    "Periodic Table Trends", "Chemical Bonding Advanced",
                    "s p d f Block Elements", "Coordination Chemistry",
                    "Analytical Chemistry", "Metallurgy"
                )
            )
        ),

        "CUET" to mapOf(
            "Biology" to mapOf(
                "Class 11 Biology" to listOf(
                    "Diversity of Living World", "Structural Organisation in Animals and Plants",
                    "Cell Structure and Function", "Plant Physiology", "Human Physiology"
                ),
                "Class 12 Biology" to listOf(
                    "Reproduction", "Genetics and Evolution",
                    "Biology and Human Welfare", "Biotechnology", "Ecology"
                )
            ),
            "Chemistry" to mapOf(
                "Class 11 Chemistry" to listOf(
                    "Basic Chemistry", "States of Matter", "Atomic Structure",
                    "Thermodynamics", "Equilibrium", "Redox", "Organic Basics"
                ),
                "Class 12 Chemistry" to listOf(
                    "Solid State", "Solutions", "Electrochemistry", "Kinetics",
                    "Surface Chemistry", "p d f Blocks", "Coordination",
                    "Haloalkanes", "Alcohols Aldehydes Acids", "Amines", "Biomolecules"
                )
            ),
            "Physics" to mapOf(
                "Class 11 Physics" to listOf(
                    "Motion", "Laws of Motion", "Work Energy", "Rotation",
                    "Gravitation", "Solids and Fluids", "Thermal Physics", "Oscillations", "Waves"
                ),
                "Class 12 Physics" to listOf(
                    "Electrostatics", "Current Electricity", "Magnetism",
                    "EMI", "AC", "EM Waves", "Optics", "Modern Physics", "Semiconductors"
                )
            )
        ),

        // SSC CGL 2026 — Tier 1 (qualifying) + Tier 2 Paper 1 (final merit) syllabus.
        // Tier 2 Paper 2 (Statistics) is JSO-specific; Paper 3 (Finance & Economics) is AAO-specific —
        // both are folded in as subjects so students targeting those posts can track them separately.
        "SSC CGL" to mapOf(
            "Quantitative Aptitude" to mapOf(
                "Number System" to listOf(
                    "LCM and HCF", "Simplification", "Number Series",
                    "Surds and Indices", "Divisibility Rules", "Decimal and Fractions"
                ),
                "Arithmetic" to listOf(
                    "Percentage", "Profit and Loss", "Simple Interest", "Compound Interest",
                    "Ratio and Proportion", "Average", "Time and Work",
                    "Time Speed and Distance", "Partnership", "Mixture and Alligation",
                    "Boats and Streams", "Pipes and Cisterns"
                ),
                "Algebra" to listOf(
                    "Linear Equations", "Quadratic Equations", "Algebraic Identities", "Polynomials"
                ),
                "Geometry" to listOf(
                    "Triangles", "Circles and Chords and Tangents", "Quadrilaterals",
                    "Lines and Angles", "Congruence and Similarity", "Coordinate Geometry Basics"
                ),
                "Trigonometry" to listOf(
                    "Trigonometric Ratios", "Heights and Distances", "Trigonometric Identities",
                    "Maxima and Minima in Trigonometry"
                ),
                "Mensuration" to listOf(
                    "2D Mensuration — Area and Perimeter", "3D Mensuration — Volume and Surface Area",
                    "Mensuration of Combined Solids"
                ),
                "Data Interpretation" to listOf(
                    "Bar Graphs", "Pie Charts", "Line Graphs", "Tabulation", "Caselet DI"
                )
            ),
            "General Intelligence & Reasoning" to mapOf(
                "Verbal Reasoning" to listOf(
                    "Analogies", "Classification", "Coding-Decoding", "Blood Relations",
                    "Direction Sense", "Series Completion", "Syllogism",
                    "Statement and Conclusion", "Word Formation", "Alphabet and Number Test"
                ),
                "Non-Verbal Reasoning" to listOf(
                    "Mirror Images", "Water Images", "Paper Folding and Cutting",
                    "Embedded Figures", "Figure Series", "Pattern Completion", "Figure Counting"
                ),
                "Analytical Reasoning" to listOf(
                    "Puzzles", "Seating Arrangement", "Logical Venn Diagrams",
                    "Matrix Based Reasoning", "Input-Output", "Critical Thinking"
                )
            ),
            "English Language" to mapOf(
                "Grammar" to listOf(
                    "Spotting Errors", "Sentence Improvement", "Active and Passive Voice",
                    "Direct and Indirect Speech", "Parts of Speech", "Tenses",
                    "Subject-Verb Agreement"
                ),
                "Vocabulary" to listOf(
                    "Synonyms and Antonyms", "One Word Substitution",
                    "Idioms and Phrases", "Spelling Correction", "Homonyms"
                ),
                "Reading Comprehension" to listOf(
                    "Passage Based Questions", "Cloze Test", "Para Jumbles",
                    "Fill in the Blanks", "Sentence Rearrangement"
                )
            ),
            "General Awareness" to mapOf(
                "Static GK" to listOf(
                    "Indian History", "Indian Geography", "Indian Polity and Constitution",
                    "Indian Economy", "Books Awards and Important Days", "Art and Culture"
                ),
                "Current Affairs" to listOf(
                    "National and International Current Affairs", "Government Schemes",
                    "Sports", "Awards and Honours", "Appointments and Obituaries"
                ),
                "Science" to listOf(
                    "Physics Basics", "Chemistry Basics", "Biology Basics",
                    "Science and Technology Current Developments"
                )
            ),
            "Computer Knowledge" to mapOf(
                "Computer Fundamentals" to listOf(
                    "Computer Basics and Generations", "MS Office — Word Excel PowerPoint",
                    "Internet and Networking Basics", "Computer Security and Viruses",
                    "Operating Systems Basics"
                )
            ),
            "Statistics (JSO Paper 2)" to mapOf(
                "Descriptive Statistics" to listOf(
                    "Collection and Classification of Data", "Tabulation and Presentation",
                    "Measures of Central Tendency", "Measures of Dispersion",
                    "Correlation and Regression"
                ),
                "Probability and Sampling" to listOf(
                    "Probability Theory", "Random Variables and Distributions",
                    "Sampling Theory", "Statistical Inference"
                )
            ),
            "Finance & Economics (AAO Paper 3)" to mapOf(
                "Finance" to listOf(
                    "Financial Accounting", "Basic Concepts of Accounting",
                    "Self-Balancing Ledgers", "Depreciation Accounting"
                ),
                "Economics and Governance" to listOf(
                    "Comptroller and Auditor General of India", "Finance Commission",
                    "Theory of Demand and Supply", "Indian Economy Basics", "Government Budgeting"
                )
            )
        )
    )

    fun getTopicsForExam(exam: String): List<String> {
        return data[exam]?.values?.flatMap { it.values.flatten() } ?: emptyList()
    }

    fun getChaptersForSubject(exam: String, subject: String): List<String> {
        return data[exam]?.get(subject)?.keys?.toList() ?: emptyList()
    }

    fun getTopicsForChapter(exam: String, subject: String, chapter: String): List<String> {
        return data[exam]?.get(subject)?.get(chapter) ?: emptyList()
    }

    fun getAllSubjectsForExam(exam: String): List<String> {
        return data[exam]?.keys?.toList() ?: emptyList()
    }
}
