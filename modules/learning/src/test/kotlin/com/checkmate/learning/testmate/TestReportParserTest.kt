package com.checkmate.learning.testmate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Header (title/score/exam-suffix) fixture kept synthetic and unchanged from
 * before this update — TITLE_LINE/SCORE_LINE/TITLE_EXAM_SUFFIX parsing is
 * untouched by the Options:/Explanation: format change, so this preserves that
 * existing regression coverage rather than re-deriving it.
 *
 * Question rows below are a verbatim excerpt (Q1, Q108, Q180 — one SKIPPED, one
 * WRONG, one CORRECT) from a real Testmate export
 * (fortnightly-test-for-neet-2026-rm-p1-ft-01a-report.md), matching the new shape
 * confirmed against markdown-export.ts's `buildTestReportMarkdown`. The one
 * exception: the `Explanation:` line under Q108 is fabricated for test coverage
 * — no report we have yet contains a populated explanation, but the line's own
 * shape (`  Explanation: ${q.explanation}`, WRONG-only) is verified against the
 * exporter source, not guessed.
 */
class TestReportParserTest {

    private val sample = """
        # Test Report — FT-01B Full Test (NEET-2027)
        Score: 2/720 (33.33%) | Attempted: 3 | Correct: 1 | Wrong: 2 | Skipped: 177

        ## Weak chapters (by accuracy)
        1. Cell: The Unit of Life — 0% (0/1)

        ## Weak topics
        Not enough attempted questions to break this down.

        ## Question-by-question
        Q1 [Motion in a Straight Line/—] — SKIPPED — time 5s
          Q: Two cities X and Y are connected by a regular bus service with a bus leaving in either direction every T min. A girl is driving scooty with a speed of 60 km/h in the direction X to Y notices that a bus goes past her every 30 minutes in the direction of her motion, and every 10 minutes in the opposite direction. Choose the correct option for the period T of the bus service and the speed (assumed constant) of the buses.
          Options: a) 10 min, 90 km/h | b) 15 min, 120 km/h | c) 9 min, 40 km/h | d) 25 min, 100 km/h
          Your answer: — | Correct: b) 15 min, 120 km/h
        Q108 [Cell: The Unit of Life/—] — WRONG — time 11s
          Q: Read the following statements and choose the correct option. Assertion (A): Mitochondria can synthesise some of its own proteins. Reason (R): Both inner and outer membrane of the mitochondria have enzymes.
          Options: a) Both (A) and (R) are true and (R) is the correct explanation of (A) | b) Both (A) and (R) are true but (R) is not the correct explanation of (A) | c) Only (A) is true but (R) is false | d) Both (A) and (R) are false
          Your answer: c) Only (A) is true but (R) is false | Correct: b) Both (A) and (R) are true but (R) is not the correct explanation of (A)
          Explanation: Both membranes carry distinct enzyme sets, so (R) being true doesn't establish that it explains (A) — (R) is true but isn't the reason (A) is true.
        Q180 [Biomolecules-I (Upto polysaccharides)/—] — CORRECT — time 13s
          Q: Assertion(A): If you add iodine solution to a sample containing starch, it gives blue colour. Reason(R): Starch forms helical secondary structures in which it can hold I2 molecules. In the light of above statements, choose the correct option.
          Options: a) Both (A) and (R) are true and (R) is the correct explanation of (A) | b) Both (A) and (R) are true but (R) is not the correct explanation of (A) | c) (A) is true, (R) is false | d) (A) is false, (R) is true
          Your answer: a) Both (A) and (R) are true and (R) is the correct explanation of (A) | Correct: a) Both (A) and (R) are true and (R) is the correct explanation of (A)
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
        assertEquals(2.0, report.scoreObtained)
        assertEquals(720.0, report.scoreTotal)
        assertEquals(33.33, report.scorePercent)
        assertEquals(3, report.attempted)
        assertEquals(1, report.correct)
        assertEquals(2, report.wrong)
        assertEquals(177, report.skipped)
    }

    @Test
    fun `parses one row per question with correct status`() {
        val report = TestReportParser.parse(sample)
        assertEquals(3, report.questions.size)
        assertEquals(ParsedQuestionStatus.SKIPPED, report.questions[0].status)
        assertEquals(ParsedQuestionStatus.WRONG, report.questions[1].status)
        assertEquals(ParsedQuestionStatus.CORRECT, report.questions[2].status)
    }

    @Test
    fun `normalizes the placeholder topic dash to null`() {
        val report = TestReportParser.parse(sample)
        report.questions.forEach { assertNull(it.topic) }
    }

    @Test
    fun `captures chapter, time, and question text`() {
        val q1 = TestReportParser.parse(sample).questions[0]
        assertEquals("Motion in a Straight Line", q1.chapter)
        assertEquals(5, q1.timeSeconds)
        assertTrue(q1.questionText!!.startsWith("Two cities X and Y"))
    }

    @Test
    fun `captures selected vs correct as bare letters, even though the answer line now renders full option text`() {
        val questions = TestReportParser.parse(sample).questions
        assertNull(questions[0].selectedOption) // SKIPPED -> "—"
        assertEquals("b", questions[0].correctOption)
        assertEquals("c", questions[1].selectedOption)
        assertEquals("b", questions[1].correctOption)
        assertEquals("a", questions[2].selectedOption)
        assertEquals("a", questions[2].correctOption)
    }

    @Test
    fun `parses the full option set from the Options line`() {
        val q2 = TestReportParser.parse(sample).questions[1] // Q108
        assertEquals(4, q2.options?.size)
        assertEquals("Both (A) and (R) are false", q2.options?.get("d"))
    }

    @Test
    fun `captures explanation only for WRONG questions`() {
        val questions = TestReportParser.parse(sample).questions
        assertNull(questions[0].explanation) // SKIPPED
        assertTrue(questions[1].explanation!!.startsWith("Both membranes carry distinct enzyme sets"))
        assertNull(questions[2].explanation) // CORRECT
    }

    @Test
    fun `options and explanation are null for the numeric-subjective fallback shape (no Options line rendered)`() {
        // markdown-export.ts omits the Options: line entirely for numeric/subjective
        // question types (formatAnswer() falls back to the bare letter) — confirmed
        // against that source's `if (q.options)` guard, no real numeric/subjective
        // question in the sample report to excerpt verbatim.
        val withoutOptions = """
            # Test Report — FT-01B Full Test (NEET-2027)
            Score: 2/720 (33.33%) | Attempted: 3 | Correct: 1 | Wrong: 2 | Skipped: 177

            ## Question-by-question
            Q200 [Physics/—] — CORRECT — time 40s
              Q: Calculate the numeric value of X.
              Your answer: a | Correct: a
        """.trimIndent()

        val q = TestReportParser.parse(withoutOptions).questions[0]
        assertNull(q.options)
        assertEquals("a", q.selectedOption)
        assertEquals("a", q.correctOption)
    }

    @Test
    fun `handles a question header with no time field, as Testmate's own report sometimes emits`() {
        val withMissingTime = """
            # Test Report — FT-01B Full Test (NEET-2027)
            Score: 2/720 (33.33%) | Attempted: 3 | Correct: 1 | Wrong: 2 | Skipped: 177

            ## Question-by-question
            Q2 [Units & Measurements/—] — SKIPPED
              Q: Which of the following measurement is most precise?
              Options: a) 2.0 cm | b) 2.00 cm | c) 20.00 mm | d) 2.0 × 10⁻² m
              Your answer: — | Correct: c) 20.00 mm
            Q58 [Some Basic Concepts of Chemistry/—] — CORRECT — time 105s
              Q: 6.025 x 10^20 molecules of acetic acid are present in 500 ml of its solution.
              Options: a) 1 mol | b) 0.5 mol | c) 2 mol | d) 0.1 mol
              Your answer: a) 1 mol | Correct: a) 1 mol
        """.trimIndent()

        val report = TestReportParser.parse(withMissingTime)
        assertEquals(2, report.questions.size)
        assertEquals(2, report.questions[0].number)
        assertNull(report.questions[0].timeSeconds)
        assertNull(report.questions[0].selectedOption)
        assertEquals(105, report.questions[1].timeSeconds)
    }

    @Test
    fun `malformed input parses to an empty question list instead of throwing`() {
        val report = TestReportParser.parse("not a report at all")
        assertEquals(0, report.questions.size)
        assertNull(report.scoreObtained)
    }
}
