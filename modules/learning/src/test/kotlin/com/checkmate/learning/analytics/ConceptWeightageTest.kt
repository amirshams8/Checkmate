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
}
