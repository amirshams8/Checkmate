package com.checkmate.learning.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubjectScoreCalculatorTest {

    private fun fact(chapter: String, topic: String?, correct: Boolean, attempted: Boolean = true) =
        ScoredQuestionFact(chapter = chapter, topic = topic, correct = correct, attempted = attempted)

    @Test
    fun `marks are tallied with real NEET plus-4 minus-1 scoring`() {
        val questions = listOf(
            fact("Electrostatics", "Electrostatics", correct = true),
            fact("Electrostatics", "Electrostatics", correct = true),
            fact("Electrostatics", "Electrostatics", correct = false),
            fact("Electrostatics", "Electrostatics", correct = false, attempted = false)
        )

        val scores = SubjectScoreCalculator.compute("NEET", questions)

        assertEquals(1, scores.size)
        val physics = scores.first()
        assertEquals("Physics", physics.subject)
        assertEquals(4, physics.questionsCount)
        assertEquals(2, physics.correct)
        assertEquals(1, physics.wrong)
        assertEquals(1, physics.skipped)
        // 2*4 - 1*1 = 7
        assertEquals(7, physics.marksObtained)
        assertEquals(16, physics.marksTotal)
    }

    @Test
    fun `questions across multiple subjects are grouped separately`() {
        val questions = listOf(
            fact("Human Physiology", "Human Physiology", correct = true),
            fact("Human Physiology", "Human Physiology", correct = true),
            fact("Equilibrium", "Equilibrium", correct = false)
        )

        val scores = SubjectScoreCalculator.compute("NEET", questions)

        val subjects = scores.map { it.subject }.toSet()
        assertTrue(subjects.contains("Biology"))
        assertTrue(subjects.contains("Chemistry"))
    }

    @Test
    fun `a question that resolves to no subject is dropped, not crashed on`() {
        val questions = listOf(fact("Not A Real Chapter", "Not A Real Topic", correct = true))

        val scores = SubjectScoreCalculator.compute("NEET", questions)

        assertTrue(scores.isEmpty())
    }
}
