package com.checkmate.core

/**
 * NTA-official full syllabus for JEE / NEET / CUET.
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
