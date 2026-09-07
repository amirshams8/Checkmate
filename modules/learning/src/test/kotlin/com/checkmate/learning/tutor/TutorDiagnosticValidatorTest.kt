package com.checkmate.learning.tutor

import com.checkmate.learning.model.RetentionDecisionSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HypothesisTypeTest {

    @Test
    fun `parses an exact member name`() {
        assertEquals(HypothesisType.CONCEPTUAL_MISUNDERSTANDING, HypothesisType.parse("CONCEPTUAL_MISUNDERSTANDING"))
    }

    @Test
    fun `unknown label falls back to UNKNOWN, not a crash`() {
        assertEquals(HypothesisType.UNKNOWN, HypothesisType.parse("SOMETHING_THE_LLM_MADE_UP"))
    }

    @Test
    fun `null falls back to UNKNOWN`() {
        assertEquals(HypothesisType.UNKNOWN, HypothesisType.parse(null))
    }
}

class LlmDiagnosticResponseParseTest {

    private val validJson = """
        {
          "hypothesis": "Sign error when substituting into the formula.",
          "hypothesisType": "SIGN_DIRECTION_ERROR",
          "suspectedSubConcept": "Vector subtraction",
          "reasoningSummary": "Three of four wrong answers flipped a sign.",
          "evidenceQuestionIds": ["q1", "q2"],
          "confidence": 0.75,
          "recommendedProbe": "Ask them to redo q1 narrating each sign choice."
        }
    """.trimIndent()

    @Test
    fun `parses a well-formed response`() {
        val response = LlmDiagnosticResponse.parse(validJson)
        assertEquals("Sign error when substituting into the formula.", response?.hypothesis)
        assertEquals("SIGN_DIRECTION_ERROR", response?.hypothesisType)
        assertEquals(listOf("q1", "q2"), response?.evidenceQuestionIds)
        assertEquals(0.75, response?.confidence ?: -1.0, 0.0001)
    }

    @Test
    fun `tolerates a markdown code fence`() {
        val fenced = "```json\n$validJson\n```"
        assertEquals("Sign error when substituting into the formula.", LlmDiagnosticResponse.parse(fenced)?.hypothesis)
    }

    @Test
    fun `malformed JSON returns null, never throws`() {
        assertNull(LlmDiagnosticResponse.parse("not json at all"))
    }

    @Test
    fun `missing hypothesis returns null`() {
        val noHypothesis = """{"confidence": 0.5, "evidenceQuestionIds": []}"""
        assertNull(LlmDiagnosticResponse.parse(noHypothesis))
    }

    @Test
    fun `missing confidence returns null`() {
        val noConfidence = """{"hypothesis": "x", "evidenceQuestionIds": []}"""
        assertNull(LlmDiagnosticResponse.parse(noConfidence))
    }

    @Test
    fun `optional fields default sensibly when absent`() {
        val minimal = """{"hypothesis": "x", "confidence": 0.2}"""
        val response = LlmDiagnosticResponse.parse(minimal)
        assertEquals(emptyList<String>(), response?.evidenceQuestionIds)
        assertNull(response?.hypothesisType)
        assertNull(response?.suspectedSubConcept)
    }
}

class LlmExplanationResponseParseTest {

    @Test
    fun `parses a well-formed response`() {
        val json = """
            {
              "explanation": "Vectors subtract tip-to-tail in the opposite direction.",
              "keyIdea": "Subtraction is addition of the negative vector.",
              "hintLadder": ["Try drawing it.", "Flip the second vector.", "Add tip-to-tail."],
              "groundingRefs": ["q1"]
            }
        """.trimIndent()
        val response = LlmExplanationResponse.parse(json)
        assertEquals("Vectors subtract tip-to-tail in the opposite direction.", response?.explanation)
        assertEquals(3, response?.hintLadder?.size)
    }

    @Test
    fun `missing explanation returns null`() {
        assertNull(LlmExplanationResponse.parse("""{"keyIdea": "x"}"""))
    }

    @Test
    fun `malformed JSON returns null`() {
        assertNull(LlmExplanationResponse.parse("{{{"))
    }
}

class TutorDiagnosticValidatorTest {

    private fun context(
        conceptId: String = "concept-1",
        knownIds: List<String> = listOf("q1", "q2")
    ) = TutorDiagnosticContext(
        conceptId = conceptId,
        exam = "NEET",
        subject = "Physics",
        chapter = "Vectors",
        topic = "Vector subtraction",
        mastery = 0.4,
        retentionDecision = RetentionDecisionSnapshot.TEACH,
        forgettingRisk = 0.0,
        attemptCount = 8,
        recentAccuracy = 0.4,
        lifetimeAccuracy = 0.4,
        errorCount = 4,
        recentErrors = knownIds.map { EvidenceErrorRef(questionId = it, errorType = "SIGN_ERROR", timestamp = 1_000L) },
        weakPrerequisites = emptyList(),
        tutorState = TutorState.DIAGNOSE,
        cycleCount = 0
    )

    private fun response(
        confidence: Double = 0.7,
        hypothesis: String = "Sign error on subtraction.",
        evidenceQuestionIds: List<String> = listOf("q1")
    ) = LlmDiagnosticResponse(
        hypothesis = hypothesis,
        hypothesisType = "SIGN_DIRECTION_ERROR",
        suspectedSubConcept = "Vector subtraction",
        reasoningSummary = "reasons",
        evidenceQuestionIds = evidenceQuestionIds,
        confidence = confidence,
        recommendedProbe = null
    )

    @Test
    fun `valid response produces an LLM-sourced finding`() {
        val result = TutorDiagnosticValidator.validate(response(), context(), now = 5_000L)
        val valid = result as TutorDiagnosticValidator.ValidationResult.Valid
        assertEquals(FindingSource.LLM, valid.finding.source)
        assertEquals(HypothesisType.SIGN_DIRECTION_ERROR, valid.finding.hypothesisType)
        assertEquals("concept-1", valid.finding.conceptId)
        assertEquals(5_000L, valid.finding.createdAt)
    }

    @Test
    fun `evidence ids not in context are dropped, not rejected`() {
        val result = TutorDiagnosticValidator.validate(
            response(evidenceQuestionIds = listOf("q1", "q999-hallucinated")),
            context(),
            now = 1L
        )
        val valid = result as TutorDiagnosticValidator.ValidationResult.Valid
        assertEquals(listOf("q1"), valid.finding.validatedEvidenceQuestionIds)
    }

    @Test
    fun `blank hypothesis is rejected`() {
        val result = TutorDiagnosticValidator.validate(response(hypothesis = "   "), context(), now = 1L)
        assertTrue(result is TutorDiagnosticValidator.ValidationResult.Rejected)
    }

    @Test
    fun `confidence above 1 is rejected`() {
        val result = TutorDiagnosticValidator.validate(response(confidence = 1.5), context(), now = 1L)
        assertTrue(result is TutorDiagnosticValidator.ValidationResult.Rejected)
    }

    @Test
    fun `confidence below 0 is rejected`() {
        val result = TutorDiagnosticValidator.validate(response(confidence = -0.1), context(), now = 1L)
        assertTrue(result is TutorDiagnosticValidator.ValidationResult.Rejected)
    }

    @Test
    fun `blank concept id is rejected`() {
        val result = TutorDiagnosticValidator.validate(response(), context(conceptId = ""), now = 1L)
        assertTrue(result is TutorDiagnosticValidator.ValidationResult.Rejected)
    }

    @Test
    fun `unparseable hypothesisType soft-falls-back to UNKNOWN, does not reject`() {
        val weirdResponse = response().copy(hypothesisType = "NOT_A_REAL_TYPE")
        val result = TutorDiagnosticValidator.validate(weirdResponse, context(), now = 1L)
        val valid = result as TutorDiagnosticValidator.ValidationResult.Valid
        assertEquals(HypothesisType.UNKNOWN, valid.finding.hypothesisType)
    }

    @Test
    fun `deterministic fallback carries DETERMINISTIC source and zero confidence`() {
        val finding = TutorDiagnosticValidator.deterministicFallback(context(), now = 42L)
        assertEquals(FindingSource.DETERMINISTIC, finding.source)
        assertEquals(0.0, finding.confidence, 0.0001)
        assertEquals(42L, finding.createdAt)
    }
}
