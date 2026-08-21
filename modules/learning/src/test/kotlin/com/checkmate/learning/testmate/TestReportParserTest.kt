package com.checkmate.learning.testmate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fixture below is a trimmed excerpt of an actual Testmate-generated report
 * (ft-01b-full-test-neet-2027--report.md) — same header, score line, and three
 * representative question rows (CORRECT, WRONG, SKIPPED) verbatim, not invented.
 */
class TestReportParserTest {

    private val sample = """
        # Test Report — FT-01B Full Test (NEET-2027)
        Score: 387/720 (72.3%) | Attempted: 148 | Correct: 107 | Wrong: 41 | Skipped: 32

        ## Weak chapters (by accuracy)
        1. Some Basic Concepts of Chemistry — 67.5% (27/40)

        ## Weak topics
        Not enough attempted questions to break this down.

        ## Question-by-question
        Q1 [Units & Measurements/—] — CORRECT — time 117s
          Q: The value of two resistors are (5.0 ± 0.2) Ω and (10.0 ± 0.1) Ω. The maximum percentage error in the equivalent resistance when they are connected in series will be
          Your answer: c | Correct: c
        Q6 [Units & Measurements/—] — WRONG — time 46s
          Q: Assertion (A): work and torque have same dimensions, but the two are not physically same.
          Your answer: a | Correct: b
        Q10 [Units & Measurements/—] — SKIPPED — time 12s
          Q: A person measures the length of a rod as 10 cm, 11 cm, 10 cm, 10 cm, 9 cm. The true value of length of rod is
          Your answer: — | Correct: a
    """.trimIndent()

    @Test
    fun `parses title and exam from the header line`() {
        val report = TestReportParser.parse(sample)
        assertEquals("FT-01B Full Test", report.title)
        assertEquals("NEET-2027", report.exam)
    }

    @Test
    fun `parses the score summary line`() {
        val report = TestReportParser.parse(sample)
        assertEquals(387.0, report.scoreObtained)
        assertEquals(720.0, report.scoreTotal)
        assertEquals(72.3, report.scorePercent)
        assertEquals(148, report.attempted)
        assertEquals(107, report.correct)
        assertEquals(41, report.wrong)
        assertEquals(32, report.skipped)
    }

    @Test
    fun `parses one row per question with correct status`() {
        val report = TestReportParser.parse(sample)
        assertEquals(3, report.questions.size)
        assertEquals(ParsedQuestionStatus.CORRECT, report.questions[0].status)
        assertEquals(ParsedQuestionStatus.WRONG, report.questions[1].status)
        assertEquals(ParsedQuestionStatus.SKIPPED, report.questions[2].status)
    }

    @Test
    fun `normalizes the placeholder topic dash to null`() {
        val report = TestReportParser.parse(sample)
        report.questions.forEach { assertNull(it.topic) }
    }

    @Test
    fun `captures chapter, time, and question text`() {
        val q1 = TestReportParser.parse(sample).questions[0]
        assertEquals("Units & Measurements", q1.chapter)
        assertEquals(117, q1.timeSeconds)
        assertTrue(q1.questionText!!.startsWith("The value of two resistors"))
    }

    @Test
    fun `captures selected vs correct option, and null selection for skipped`() {
        val questions = TestReportParser.parse(sample).questions
        assertEquals("c", questions[0].selectedOption)
        assertEquals("c", questions[0].correctOption)
        assertEquals("a", questions[1].selectedOption)
        assertEquals("b", questions[1].correctOption)
        assertNull(questions[2].selectedOption)
        assertEquals("a", questions[2].correctOption)
    }

    @Test
    fun `handles a question header with no time field, as Testmate's own report sometimes emits`() {
        val withMissingTime = """
            # Test Report — FT-01B Full Test (NEET-2027)
            Score: 387/720 (72.3%) | Attempted: 148 | Correct: 107 | Wrong: 41 | Skipped: 32

            ## Question-by-question
            Q57 [Some Basic Concepts of Chemistry/—] — CORRECT
              Q: Match the column I containing certain numerical values with their respective number of significant figures given in column II.
              Your answer: d | Correct: d
            Q58 [Some Basic Concepts of Chemistry/—] — CORRECT — time 105s
              Q: 6.025 x 10^20 molecules of acetic acid are present in 500 ml of its solution.
              Your answer: a | Correct: a
        """.trimIndent()

        val report = TestReportParser.parse(withMissingTime)
        assertEquals(2, report.questions.size)
        assertEquals(57, report.questions[0].number)
        assertNull(report.questions[0].timeSeconds)
        assertEquals("d", report.questions[0].selectedOption)
        assertEquals(105, report.questions[1].timeSeconds)
    }

    @Test
    fun `malformed input parses to an empty question list instead of throwing`() {
        val report = TestReportParser.parse("not a report at all")
        assertEquals(0, report.questions.size)
        assertNull(report.scoreObtained)
    }
}
