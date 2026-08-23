package com.checkmate.learning.analytics

import com.checkmate.core.PYQWeightage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConceptWeightageTest {

    @Test
    fun `exact subject+topic match resolves without falling back`() {
        val r = ConceptWeightage.resolveWeightage(
            exam = "NEET", subject = "Physics", chapter = "Electrostatics", topic = "Electrostatics"
        )
        assertEquals("Physics", r.subjectResolved)
        // Real 2026-paper-grounded value post canonical rebuild (see PYQWeightage doc).
        assertEquals(8.9f, r.weightagePercent, 0.001f)
        assertEquals(PYQWeightage.Confidence.HIGH, r.confidence)
    }

    @Test
    fun `null subject falls back to fuzzy match and infers the subject`() {
        // Mirrors a real TestResultNormalizer import: subject is never set.
        val r = ConceptWeightage.resolveWeightage(
            exam = "NEET", subject = null, chapter = "Current Electricity", topic = "Ohm's Law"
        )
        assertEquals("Physics", r.subjectResolved)
        assertTrue(r.weightagePercent > 0f)
    }

    @Test
    fun `unresolvable topic returns zero weightage, not a crash`() {
        val r = ConceptWeightage.resolveWeightage(
            exam = "NEET", subject = null, chapter = "Not A Real Chapter", topic = "Not A Real Topic"
        )
        assertEquals(0f, r.weightagePercent, 0.001f)
        assertEquals(PYQWeightage.Confidence.ESTIMATED, r.confidence)
    }

    @Test
    fun `marksAtStake scales total exam marks by subject share and topic weightage`() {
        // NEET Biology is 50% of 720 = 360; Human Physiology is 12% of that subject.
        val marks = ConceptWeightage.marksAtStake(
            exam = "NEET", subject = "Biology", chapter = "Human Physiology", topic = "Human Physiology"
        )
        assertEquals(360.0 * 0.12, marks, 0.5)
    }

    @Test
    fun `unresolved subject share yields zero marksAtStake instead of a wrong guess`() {
        val marks = ConceptWeightage.marksAtStake(
            exam = "SSC CGL", subject = "Quantitative Aptitude", chapter = "Algebra", topic = "Algebra"
        )
        assertEquals(0.0, marks, 0.001)
    }

    // ---- Real FT-02B / FT-01B report.md fixtures (see ai-output history) ----
    // Testmate's own report never carries a topic, so every real call site passes
    // the same string as both `chapter` and `topic` (see TestResultNormalizer /
    // PerformanceAnalyzer.resolveAgainstWeightage) — mirrored here rather than
    // testing chapter/topic independently.

    @Test
    fun `Laws of Motion now resolves via canonical normalization, alias no longer needed`() {
        // PYQWeightage's NEET key is now the exact ExamSyllabus chapter string
        // "Laws Of Motion" (see PYQWeightage's canonical-chapter-rebuild doc), so
        // a case-only difference clears tier 3 directly — no ALIAS tier required
        // the way "Mechanics — Newton's Laws" used to require one.
        val r = ConceptWeightage.resolveWeightage(
            exam = "NEET", subject = null, chapter = "Laws of Motion", topic = "Laws of Motion"
        )
        assertEquals("Physics", r.subjectResolved)
        assertEquals("Laws Of Motion", r.matchedKey)
        assertEquals(ConceptWeightage.ResolutionMethod.EXACT_CANONICAL, r.method)
        assertEquals(7.0f, r.weightagePercent, 0.001f)
    }

    @Test
    fun `Structure of Atom resolves via alias, not fuzzy overlap`() {
        val r = ConceptWeightage.resolveWeightage(
            exam = "NEET", subject = null, chapter = "Structure of Atom", topic = "Structure of Atom"
        )
        assertEquals("Chemistry", r.subjectResolved)
        assertEquals("Atomic Structure", r.matchedKey)
        assertEquals(ConceptWeightage.ResolutionMethod.ALIAS, r.method)
    }

    @Test
    fun `The Living World resolves via alias to the canonical ExamSyllabus chapter name`() {
        val r = ConceptWeightage.resolveWeightage(
            exam = "NEET", subject = null, chapter = "The Living World", topic = "The Living World"
        )
        assertEquals("Biology", r.subjectResolved)
        assertEquals("Diversity In Living World", r.matchedKey)
        assertEquals(ConceptWeightage.ResolutionMethod.ALIAS, r.method)
    }

    @Test
    fun `Cell Cycle and Cell Division resolves via alias into the merged canonical chapter`() {
        // "Cell Division" no longer exists as its own PYQWeightage chapter — its
        // weight was folded into "Cell Structure And Function" during the
        // canonical rebuild (see PYQWeightage doc), so the alias target moved too.
        val r = ConceptWeightage.resolveWeightage(
            exam = "NEET", subject = null, chapter = "Cell Cycle & Cell Division", topic = "Cell Cycle & Cell Division"
        )
        assertEquals("Biology", r.subjectResolved)
        assertEquals("Cell Structure And Function", r.matchedKey)
        assertEquals(ConceptWeightage.ResolutionMethod.ALIAS, r.method)
    }

    @Test
    fun `bare Biomolecules-I fragment now deterministically resolves to Chemistry`() {
        // BEHAVIOR CHANGE from before the canonical rebuild: the old table had an
        // identical "Biomolecules" key duplicated under BOTH NEET Chemistry and
        // NEET Biology, so which subject a bare "Biomolecules" fragment landed on
        // depended on Map iteration order — undefined, not a real resolution.
        // Biology's biomolecules content now lives under "Cell Structure And
        // Function" (a uniquely-keyed chapter), so the only remaining literal
        // "Biomolecules" key is Chemistry's — a short fragment with no
        // distinguishing content (no "Lipids"/"Enzymes"/etc., see the -II test
        // below) is genuinely ambiguous and now resolves deterministically to
        // Chemistry instead of accidentally to Biology.
        val r = ConceptWeightage.resolveWeightage(
            exam = "NEET", subject = null,
            chapter = "Biomolecules-I (Upto polysaccharides)", topic = "Biomolecules-I (Upto polysaccharides)"
        )
        assertEquals("Chemistry", r.subjectResolved)
        assertEquals("Biomolecules", r.matchedKey)
        assertEquals(ConceptWeightage.ResolutionMethod.EXACT_CANONICAL, r.method)
    }

    @Test
    fun `Biomolecules-II long chapter still needs its explicit alias into Biology`() {
        // Real FT-02B chapter string — has trailing content beyond the
        // parenthetical/suffix ("Lipids, Nucleic acids, Enzymes, Cofactors"), so
        // normalize() alone does NOT reduce it to bare "Biomolecules"; only the
        // explicit alias (which routes it to Biology's "Cell Structure And
        // Function") catches it.
        val chapter = "Biomolecules-II (Proteins, types & functions), Lipids, Nucleic acids, Enzymes, Cofactors"
        val r = ConceptWeightage.resolveWeightage(exam = "NEET", subject = null, chapter = chapter, topic = chapter)
        assertEquals("Biology", r.subjectResolved)
        assertEquals("Cell Structure And Function", r.matchedKey)
        assertEquals(ConceptWeightage.ResolutionMethod.ALIAS, r.method)
    }

    @Test
    fun `Motion in a Plane now resolves — Kinematics legitimately covers 2D motion`() {
        // Previously left UNRESOLVED (session-report gap #1): the old PYQWeightage
        // "Kinematics" entry was effectively 1D-only. The canonical-chapter rebuild
        // keys Kinematics at the real NTA-unit level, which genuinely includes
        // Motion in a Plane / Projectile Motion / Relative Velocity as topics
        // (see ExamSyllabus) — aliasing to it is no longer overstating confidence.
        val r = ConceptWeightage.resolveWeightage(
            exam = "NEET", subject = null, chapter = "Motion in a Plane", topic = "Motion in a Plane"
        )
        assertEquals("Physics", r.subjectResolved)
        assertEquals("Kinematics", r.matchedKey)
        assertEquals(ConceptWeightage.ResolutionMethod.ALIAS, r.method)
        assertTrue(r.weightagePercent > 0f)
    }

    @Test
    fun `Classification of Elements and Periodicity now resolves — the missing entry was added`() {
        // Previously left UNRESOLVED (session-report gap #2): the chapter was
        // never missing from ExamSyllabus, only from PYQWeightage. Now has its
        // own canonical entry, so this clears tier 3 directly.
        val chapter = "Classification of Elements and Periodicity in Properties"
        val r = ConceptWeightage.resolveWeightage(exam = "NEET", subject = null, chapter = chapter, topic = chapter)
        assertEquals("Chemistry", r.subjectResolved)
        assertEquals("Classification Of Elements And Periodicity In Properties", r.matchedKey)
        assertEquals(ConceptWeightage.ResolutionMethod.EXACT_CANONICAL, r.method)
        assertTrue(r.weightagePercent > 0f)
    }

    @Test
    fun `Breathing and Exchange of Gases now resolves via a deliberate coarse-bucket alias`() {
        // Previously left UNRESOLVED (session-report gap #3). Still a genuine
        // coarse-bucket attribution (Human Physiology also covers circulation,
        // excretion, neural, endocrine content) — the resolved entry's confidence
        // is deliberately NOT HIGH, so a caller can tell this isn't a precise
        // respiration-only figure. See ALIASES' own doc for the reasoning.
        val chapter = "Breathing & Exchange of Gases-I (Upto mechanism of breathing)"
        val r = ConceptWeightage.resolveWeightage(exam = "NEET", subject = null, chapter = chapter, topic = chapter)
        assertEquals("Biology", r.subjectResolved)
        assertEquals("Human Physiology", r.matchedKey)
        assertEquals(ConceptWeightage.ResolutionMethod.ALIAS, r.method)
        assertTrue(r.weightagePercent > 0f)
        assertTrue(
            "coarse-bucket alias must not silently claim HIGH confidence",
            r.confidence != PYQWeightage.Confidence.HIGH
        )
    }

    @Test
    fun `conservative fuzzy match never lets an unrelated Electric topic bleed into Current Electricity`() {
        // Guards the exact false-positive risk the fuzzy tier was designed against:
        // whole-word overlap only, no stemming, so "Electricity" never matches
        // "Electric" and this stays UNRESOLVED rather than guessing "Current Electricity".
        val r = ConceptWeightage.resolveWeightage(
            exam = "NEET", subject = null, chapter = "Electric Potential Only", topic = "Electric Potential Only"
        )
        assertEquals(0f, r.weightagePercent, 0.001f)
        assertEquals(ConceptWeightage.ResolutionMethod.UNRESOLVED, r.method)
    }

    @Test
    fun `a real single-year observed count carries HIGH confidence through resolution`() {
        val r = ConceptWeightage.resolveWeightage(
            exam = "NEET", subject = "Physics", chapter = "Rotational Motion", topic = "Rotational Motion"
        )
        assertEquals(PYQWeightage.Confidence.HIGH, r.confidence)
    }

    @Test
    fun `an estimated legacy figure carries ESTIMATED confidence through resolution, not a false HIGH`() {
        val r = ConceptWeightage.resolveWeightage(
            exam = "NEET", subject = "Physics", chapter = "Gravitation", topic = "Gravitation"
        )
        assertEquals(PYQWeightage.Confidence.ESTIMATED, r.confidence)
    }
}
