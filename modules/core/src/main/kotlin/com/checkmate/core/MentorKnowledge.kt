package com.checkmate.core

/**
 * Static knowledge context injected selectively into the Mentor AI system prompt.
 * Contains: chapter difficulty, common misconceptions, revision strategies,
 * burnout detection, and score-gap analysis logic.
 */
object MentorKnowledge {

    val chapterDifficultyRankings = mapOf(
        "NEET" to mapOf(
            "Biology"   to listOf("Genetics", "Human Physiology", "Ecology", "Cell Biology", "Reproduction"),
            "Chemistry" to listOf("Organic Mechanisms", "Coordination Compounds", "Electrochemistry", "Chemical Bonding", "Thermodynamics"),
            "Physics"   to listOf("Electrostatics", "Rotational Motion", "Modern Physics", "Optics", "Current Electricity")
        ),
        "JEE" to mapOf(
            "Mathematics" to listOf("Calculus", "3D Geometry", "Probability", "Coordinate Geometry", "Vectors"),
            "Physics"     to listOf("Rotational Dynamics", "Electrostatics", "EMI", "Modern Physics", "Waves"),
            "Chemistry"   to listOf("Organic Mechanisms", "Coordination", "p-Block", "Thermodynamics", "Electrochemistry")
        )
    )

    val commonMisconceptions = mapOf(
        "Rotational Motion" to listOf(
            "Confusing torque direction with force direction",
            "Forgetting to apply parallel axis theorem when pivot not at CM",
            "Treating moment of inertia as fixed for a body regardless of axis",
            "Not distinguishing angular velocity from tangential velocity",
            "Forgetting that rolling without slipping links v_cm and omega"
        ),
        "Genetics" to listOf(
            "Confusing genotype ratios with phenotype ratios in dihybrid cross",
            "Assuming codominance = incomplete dominance",
            "Forgetting that X-linked traits have different ratios in males vs females",
            "Mixing up transcription and translation directions",
            "Treating mutations as always harmful — many are silent"
        ),
        "Organic Mechanisms" to listOf(
            "Confusing SN1 with SN2 substrate requirements",
            "Forgetting Markovnikov vs anti-Markovnikov conditions",
            "Applying Zaitsev's rule when Hofmann product is actually favored (bulky base)",
            "Mixing up nucleophile and electrophile in addition reactions",
            "Forgetting that resonance stabilization affects carbocation stability"
        ),
        "Electrochemistry" to listOf(
            "Confusing EMF sign conventions — cathode is + in galvanic",
            "Forgetting to balance half-reactions before combining",
            "Misapplying Nernst equation units — use natural log or factor 2.303",
            "Confusing electrolytic and galvanic cell current direction",
            "Forgetting that SHE potential is defined as 0 by convention only"
        ),
        "Thermodynamics" to listOf(
            "Assuming ΔH = ΔU always — only true when Δn_gas = 0",
            "Confusing spontaneity (ΔG) with feasibility (activation energy separate)",
            "Forgetting that entropy change of surroundings = -ΔH_system / T",
            "Treating heat as a state function — it is path-dependent",
            "Confusing isothermal and adiabatic expansion work formulas"
        ),
        "Current Electricity" to listOf(
            "Applying Kirchhoff's current law without consistent sign convention",
            "Forgetting internal resistance effect on terminal voltage",
            "Confusing resistivity and resistance — resistivity is material property",
            "Misidentifying Wheatstone bridge balance condition",
            "Treating drift velocity as equal to signal propagation speed"
        ),
        "Calculus" to listOf(
            "Forgetting +C in indefinite integrals then applying in definite context",
            "Confusing dy/dx as a fraction — it's a limit, not a ratio in general",
            "Not checking domain restrictions when differentiating inverse functions",
            "Applying L'Hôpital without verifying 0/0 or ∞/∞ form",
            "Forgetting that integration by parts choice matters — LIATE rule"
        )
    )

    val revisionStrategies = """
PROVEN REVISION STRATEGIES FOR JEE/NEET:

1. PYQ-First Method:
   - For each topic, solve last 10 years PYQs before reading theory.
   - This reveals what actually gets asked, not what textbooks emphasize.
   - Mark questions you get wrong — those are your real weak spots.

2. Spaced Repetition Schedule:
   - Day 1: Learn topic. Day 3: First revision. Day 7: Second revision.
   - Day 14: Third revision. Day 30: Final consolidation.
   - Use short notes (max 1 page per chapter) for rapid cycles.

3. Active Recall over Passive Reading:
   - Close the book. Try to recall 5 key points from the chapter.
   - What you can't recall = what you haven't actually learned.
   - Use this as a diagnostic, not a self-punishment tool.

4. Error Log:
   - Maintain a single notebook of every question you got wrong.
   - Review only this notebook the week before the exam.
   - This is 10x more effective than re-reading entire chapters.

5. Subject Sequencing for NEET:
   - Biology is 50% of NEET marks. Never sacrifice it for Physics.
   - Chemistry Physical > Organic > Inorganic for NEET scoring efficiency.
   - Physics: Focus on Mechanics, Electricity, Optics — 70% of Physics marks.

6. Mock Test Protocol:
   - Full mocks: weekly. 1 subject mocks: 2-3x per week.
   - Analyze wrong answers the same day — never skip post-mock analysis.
   - Track: Was it conceptual gap? Silly mistake? Time pressure? Each needs different fix.
""".trim()

    val burnoutDetectionPatterns = """
BURNOUT DETECTION SIGNALS:
- Skip rate > 50% over 3+ consecutive days
- Session duration dropping below 20 minutes despite planning 45+
- Topic avoidance: skipping the same subject 3+ times in a week
- Attention checks missed > 60% in sessions
- Sleep score consistently < 5h

RECOVERY PROTOCOLS:
1. Drop total study hours by 30% for 2 days. Don't skip — shorten.
2. Replace hardest subject with easiest on burnout days.
3. Use 10-minute "micro sessions" instead of 45-minute blocks.
4. Physical movement for 20 minutes before study — proven to reset focus.
5. No all-nighters. Sleep consolidates memory — burning midnight oil deletes the day's work.
""".trim()

    val scoreGapAnalysis = """
SCORE GAP ANALYSIS:
- Target 650 NEET, current 450 → gap = 200 marks, ~55 questions.
- 55 questions over 90 days = 0.6 extra correct per day of preparation.
- Biology 50% marks: Getting 15 more Biology questions right = +60 marks.
- Each chapter mastered in Biology averages 2-3 more correct answers.

FASTEST MARKS PER EFFORT:
1. NEET Biology NCERT lines — 40+ questions come directly from NCERT text.
   Reading NCERT Biology 3x = ~40-50 mark improvement, lowest effort highest return.
2. Chemistry Physical: 5 formula topics (Kinetics, Thermo, Electrochem, Solutions, Equilibrium)
   = ~30 marks. All formula-based, predictable question patterns.
3. Physics: 3 chapters (Electrostatics, Current Electricity, Optics) = ~25 marks combined.
   PYQ-heavy: same question types repeat every year with number changes.
""".trim()

    /** Returns relevant knowledge blocks for a given query topic */
    fun getContextForQuery(query: String): String {
        val lower = query.lowercase()
        val blocks = mutableListOf<String>()

        if (lower.contains("burnout") || lower.contains("stress") || lower.contains("tired") || lower.contains("motivation"))
            blocks.add("BURNOUT RECOVERY:\n$burnoutDetectionPatterns")

        if (lower.contains("score") || lower.contains("marks") || lower.contains("gap") || lower.contains("target"))
            blocks.add("SCORE IMPROVEMENT:\n$scoreGapAnalysis")

        if (lower.contains("revision") || lower.contains("strategy") || lower.contains("how to study") || lower.contains("method"))
            blocks.add("REVISION STRATEGIES:\n$revisionStrategies")

        chapterDifficultyRankings.values.forEach { examMap ->
            examMap.forEach { (subj, chapters) ->
                if (lower.contains(subj.lowercase()))
                    blocks.add("$subj CHAPTER PRIORITY (hardest first): ${chapters.joinToString()}")
            }
        }

        commonMisconceptions.forEach { (topic, list) ->
            if (lower.contains(topic.lowercase().split(" ").first()))
                blocks.add("COMMON MISTAKES in $topic:\n${list.joinToString("\n") { "• $it" }}")
        }

        return if (blocks.isEmpty()) "" else blocks.joinToString("\n\n")
    }
}
