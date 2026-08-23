package com.checkmate.learning.analytics

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
        assertEquals(9.0f, r.weightagePercent, 0.001f)
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
    fun `Laws of Motion resolves via alias, not fuzzy overlap`() {
        val r = ConceptWeightage.resolveWeightage(
            exam = "NEET", subject = null, chapter = "Laws of Motion", topic = "Laws of Motion"
        )
        assertEquals("Physics", r.subjectResolved)
        assertEquals("Mechanics — Newton's Laws", r.matchedKey)
        assertEquals(ConceptWeightage.ResolutionMethod.ALIAS, r.method)
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
    fun `The Living World resolves via alias`() {
        val r = ConceptWeightage.resolveWeightage(
            exam = "NEET", subject = null, chapter = "The Living World", topic = "The Living World"
        )
        assertEquals("Biology", r.subjectResolved)
        assertEquals("Diversity of Living World", r.matchedKey)
        assertEquals(ConceptWeightage.ResolutionMethod.ALIAS, r.method)
    }

    @Test
    fun `Cell Cycle and Cell Division resolves via alias`() {
        val r = ConceptWeightage.resolveWeightage(
            exam = "NEET", subject = null, chapter = "Cell Cycle & Cell Division", topic = "Cell Cycle & Cell Division"
        )
        assertEquals("Biology", r.subjectResolved)
        assertEquals("Cell Division", r.matchedKey)
        assertEquals(ConceptWeightage.ResolutionMethod.ALIAS, r.method)
    }

    @Test
    fun `Biomolecules-I chapter resolves via normalization alone, no alias needed`() {
        // Real FT-01B chapter string — normalize() strips the "(Upto polysaccharides)"
        // parenthetical and the "-I" suffix down to exactly "Biomolecules".
        val r = ConceptWeightage.resolveWeightage(
            exam = "NEET", subject = null,
            chapter = "Biomolecules-I (Upto polysaccharides)", topic = "Biomolecules-I (Upto polysaccharides)"
        )
        assertEquals("Biology", r.subjectResolved)
        assertEquals("Biomolecules", r.matchedKey)
        assertEquals(ConceptWeightage.ResolutionMethod.EXACT_CANONICAL, r.method)
    }

    @Test
    fun `Biomolecules-II long chapter needs its explicit alias, not normalization`() {
        // Real FT-02B chapter string — has trailing content beyond the
        // parenthetical/suffix ("Lipids, Nucleic acids, Enzymes, Cofactors"), so
        // normalize() alone does NOT reduce it to "Biomolecules"; the alias tier does.
        val chapter = "Biomolecules-II (Proteins, types & functions), Lipids, Nucleic acids, Enzymes, Cofactors"
        val r = ConceptWeightage.resolveWeightage(exam = "NEET", subject = null, chapter = chapter, topic = chapter)
        assertEquals("Biology", r.subjectResolved)
        assertEquals("Biomolecules", r.matchedKey)
        assertEquals(ConceptWeightage.ResolutionMethod.ALIAS, r.method)
    }

    @Test
    fun `Motion in a Plane stays unresolved — no 2D-motion PYQ entry exists`() {
        val r = ConceptWeightage.resolveWeightage(
            exam = "NEET", subject = null, chapter = "Motion in a Plane", topic = "Motion in a Plane"
        )
        assertEquals(0f, r.weightagePercent, 0.001f)
        assertEquals(ConceptWeightage.ResolutionMethod.UNRESOLVED, r.method)
    }

    @Test
    fun `Classification of Elements and Periodicity stays unresolved — no matching key`() {
        val chapter = "Classification of Elements and Periodicity in Properties"
        val r = ConceptWeightage.resolveWeightage(exam = "NEET", subject = null, chapter = chapter, topic = chapter)
        assertEquals(0f, r.weightagePercent, 0.001f)
        assertEquals(ConceptWeightage.ResolutionMethod.UNRESOLVED, r.method)
    }

    @Test
    fun `Breathing and Exchange of Gases stays unresolved — bucket too coarse to alias safely`() {
        val chapter = "Breathing & Exchange of Gases-I (Upto mechanism of breathing)"
        val r = ConceptWeightage.resolveWeightage(exam = "NEET", subject = null, chapter = chapter, topic = chapter)
        assertEquals(0f, r.weightagePercent, 0.001f)
        assertEquals(ConceptWeightage.ResolutionMethod.UNRESOLVED, r.method)
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
}
