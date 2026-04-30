package com.checkmate.core

/**
 * PYQ (Previous Year Question) topic-level weightage for JEE / NEET.
 * Based on NTA official analysis + last 10 year data.
 * Values represent approximate % of total paper for that topic.
 */
object PYQWeightage {

    // Map<Exam, Map<Subject, Map<Topic, weightage%>>>
    val data: Map<String, Map<String, Map<String, Float>>> = mapOf(

        "NEET" to mapOf(
            "Biology" to mapOf(
                "Human Physiology" to 12.0f,
                "Genetics and Evolution" to 11.5f,
                "Cell Biology" to 9.0f,
                "Reproduction" to 9.0f,
                "Ecology" to 8.5f,
                "Plant Physiology" to 7.5f,
                "Biotechnology" to 6.0f,
                "Cell Division" to 5.5f,
                "Diversity of Living World" to 5.0f,
                "Structural Organisation" to 4.5f,
                "Biomolecules" to 4.0f,
                "Animal Kingdom" to 3.5f,
                "Plant Kingdom" to 3.0f,
                "Biological Classification" to 2.5f,
                "Microbes in Human Welfare" to 2.0f,
                "Strategies for Enhancement" to 2.0f,
                "Environmental Issues" to 2.0f,
                "Biodiversity" to 2.0f
            ),
            "Chemistry" to mapOf(
                "Organic Chemistry — Functional Groups" to 10.0f,
                "Chemical Bonding" to 8.0f,
                "Thermodynamics" to 7.5f,
                "Equilibrium" to 7.0f,
                "Atomic Structure" to 6.5f,
                "Electrochemistry" to 6.0f,
                "p-Block Elements" to 6.0f,
                "Solutions" to 5.5f,
                "Coordination Compounds" to 5.0f,
                "Chemical Kinetics" to 4.5f,
                "Biomolecules and Polymers" to 4.5f,
                "Hydrocarbons" to 4.0f,
                "s-Block Elements" to 3.5f,
                "d and f Block" to 3.5f,
                "Solid State" to 3.0f,
                "Surface Chemistry" to 3.0f,
                "Aldehydes Ketones Acids" to 4.0f,
                "Alcohols Phenols Ethers" to 3.5f,
                "Haloalkanes" to 3.5f,
                "Amines" to 3.0f
            ),
            "Physics" to mapOf(
                "Electrostatics" to 9.0f,
                "Current Electricity" to 8.5f,
                "Optics" to 8.0f,
                "Electromagnetic Induction" to 7.5f,
                "Modern Physics" to 7.5f,
                "Mechanics — Newton's Laws" to 7.0f,
                "Rotational Motion" to 6.5f,
                "Waves and Sound" to 6.0f,
                "Thermodynamics" to 5.5f,
                "Kinematics" to 5.0f,
                "Gravitation" to 4.5f,
                "Simple Harmonic Motion" to 4.5f,
                "Magnetism" to 4.0f,
                "Semiconductors" to 4.0f,
                "AC Circuits" to 3.5f,
                "Work Energy Power" to 3.5f,
                "Properties of Matter" to 3.0f
            )
        ),

        "JEE" to mapOf(
            "Mathematics" to mapOf(
                "Calculus — Integrals" to 11.0f,
                "Calculus — Differentials" to 10.0f,
                "Coordinate Geometry" to 9.5f,
                "Algebra" to 9.0f,
                "Vectors and 3D" to 8.5f,
                "Trigonometry" to 7.0f,
                "Probability" to 6.5f,
                "Matrices and Determinants" to 6.0f,
                "Differential Equations" to 5.5f,
                "Sequences and Series" to 5.0f,
                "Complex Numbers" to 4.5f,
                "Permutations and Combinations" to 4.0f,
                "Binomial Theorem" to 3.5f,
                "Statistics" to 3.0f,
                "Mathematical Reasoning" to 2.5f
            ),
            "Physics" to mapOf(
                "Electrostatics" to 9.5f,
                "Current Electricity" to 8.5f,
                "Mechanics — Newton's Laws" to 8.0f,
                "Rotational Dynamics" to 7.5f,
                "Wave Optics" to 7.0f,
                "Modern Physics" to 7.0f,
                "Electromagnetic Induction" to 6.5f,
                "Thermodynamics" to 6.0f,
                "Kinematics" to 5.5f,
                "Simple Harmonic Motion" to 5.0f,
                "Ray Optics" to 5.0f,
                "Magnetism" to 4.5f,
                "Gravitation" to 4.0f,
                "AC Circuits" to 4.0f,
                "Fluid Mechanics" to 3.5f,
                "Semiconductors" to 3.5f,
                "Waves and Sound" to 3.0f
            ),
            "Chemistry" to mapOf(
                "Organic Mechanisms" to 10.0f,
                "Chemical Bonding" to 8.5f,
                "Thermodynamics" to 8.0f,
                "Electrochemistry" to 7.5f,
                "p-Block Elements" to 7.0f,
                "Coordination Compounds" to 6.5f,
                "Chemical Kinetics" to 6.0f,
                "Equilibrium" to 6.0f,
                "Atomic Structure" to 5.5f,
                "Solutions" to 5.0f,
                "d and f Block" to 5.0f,
                "Aldehydes Ketones Acids" to 4.5f,
                "Solid State" to 4.0f,
                "Haloalkanes" to 3.5f,
                "Alcohol Phenols" to 3.5f,
                "Amines" to 3.0f,
                "Biomolecules" to 3.0f
            )
        )
    )

    fun getWeightage(exam: String, subject: String, topic: String): Float {
        return data[exam]?.get(subject)?.get(topic) ?: 0f
    }

    /** Returns top N topics by PYQ weight for a given exam+subject */
    fun getTopTopics(exam: String, subject: String, n: Int = 5): List<Pair<String, Float>> {
        return data[exam]?.get(subject)
            ?.entries
            ?.sortedByDescending { it.value }
            ?.take(n)
            ?.map { Pair(it.key, it.value) }
            ?: emptyList()
    }

    /** Finds weightage across all subjects for a given topic (fuzzy match) */
    fun findTopicWeightage(exam: String, topicFragment: String): Float {
        return data[exam]?.values?.flatMap { it.entries }
            ?.firstOrNull { it.key.contains(topicFragment, ignoreCase = true) }
            ?.value ?: 0f
    }
}
