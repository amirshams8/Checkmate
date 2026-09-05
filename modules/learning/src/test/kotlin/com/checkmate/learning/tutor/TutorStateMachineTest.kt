package com.checkmate.learning.tutor

import com.checkmate.learning.engine.MasteryEngine
import com.checkmate.learning.model.ConceptSnapshot
import com.checkmate.learning.model.RetentionDecisionSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TutorStateMachineTest {

    private val conceptId = "concept-1"

    // Same fixture shape as LearningDecisionEngineTest's own `concept()` helper —
    // reused deliberately so a snapshot built here can only fail this test on
    // TutorStateMachine's own transition logic, not a fresh fixture-shape question.
    private fun snapshot(
        mastery: Double,
        retentionDecision: RetentionDecisionSnapshot = RetentionDecisionSnapshot.MOVE_ON,
        id: String = conceptId
    ) = ConceptSnapshot(
        conceptId = id,
        exam = "NEET",
        subject = "Biology",
        chapter = "Biomolecules",
        topic = "Proteins",
        mastery = mastery,
        masteryConfidence = 0.0,
        retentionDecision = retentionDecision,
        forgettingRisk = 0.0,
        attemptCount = 10,
        recentAccuracy = mastery,
        lifetimeAccuracy = mastery,
        errorCount = 0,
        lastSeen = 1_000L,
        prerequisiteIssues = emptyList()
    )

    private val above = MasteryEngine.MASTERY_THRESHOLD + 0.1
    private val below = MasteryEngine.MASTERY_THRESHOLD - 0.1

    // ── start() ──────────────────────────────────────────────────────────────

    @Test
    fun `start always begins in DIAGNOSE with zero cycles and empty history`() {
        val session = TutorStateMachine.start(conceptId, now = 1L)
        assertEquals(TutorState.DIAGNOSE, session.state)
        assertEquals(0, session.cycleCount)
        assertTrue(session.history.isEmpty())
    }

    @Test
    fun `start rejects blank conceptId`() {
        assertThrows(IllegalArgumentException::class.java) { TutorStateMachine.start("", now = 1L) }
    }

    // ── DIAGNOSE ─────────────────────────────────────────────────────────────

    @Test
    fun `DIAGNOSE plus KNOWN skips straight to VERIFY`() {
        val session = TutorStateMachine.start(conceptId, 1L)
        val result = TutorStateMachine.transition(session, TutorEvidence.Diagnostic(DiagnosticFinding.KNOWN), 2L)
        assertAdvancedTo(result, TutorState.VERIFY)
    }

    @Test
    fun `DIAGNOSE plus UNKNOWN MISUNDERSTOOD or FORGOTTEN all route to EXPLAIN`() {
        listOf(DiagnosticFinding.UNKNOWN, DiagnosticFinding.MISUNDERSTOOD, DiagnosticFinding.FORGOTTEN).forEach { finding ->
            val session = TutorStateMachine.start(conceptId, 1L)
            val result = TutorStateMachine.transition(session, TutorEvidence.Diagnostic(finding), 2L)
            assertAdvancedTo(result, TutorState.EXPLAIN)
        }
    }

    @Test
    fun `DIAGNOSE rejects non-Diagnostic evidence`() {
        val session = TutorStateMachine.start(conceptId, 1L)
        val result = TutorStateMachine.transition(session, TutorEvidence.ExplanationDelivered, 2L)
        assertInvalid(result)
    }

    // ── EXPLAIN ──────────────────────────────────────────────────────────────

    @Test
    fun `EXPLAIN plus ExplanationDelivered advances to PRACTICE`() {
        val explaining = inState(TutorState.EXPLAIN)
        val result = TutorStateMachine.transition(explaining, TutorEvidence.ExplanationDelivered, 2L)
        assertAdvancedTo(result, TutorState.PRACTICE)
    }

    @Test
    fun `EXPLAIN rejects non-ExplanationDelivered evidence`() {
        val explaining = inState(TutorState.EXPLAIN)
        val result = TutorStateMachine.transition(
            explaining, TutorEvidence.Diagnostic(DiagnosticFinding.KNOWN), 2L
        )
        assertInvalid(result)
    }

    // ── PRACTICE ─────────────────────────────────────────────────────────────

    @Test
    fun `PRACTICE with enough attempts advances to VERIFY`() {
        val practicing = inState(TutorState.PRACTICE)
        val evidence = TutorEvidence.PracticeAttempts(
            attemptCount = TutorStateMachine.MIN_PRACTICE_ATTEMPTS, correctCount = 2
        )
        val result = TutorStateMachine.transition(practicing, evidence, 2L)
        assertAdvancedTo(result, TutorState.VERIFY)
    }

    @Test
    fun `PRACTICE with insufficient attempts is rejected, never advances to VERIFY`() {
        val practicing = inState(TutorState.PRACTICE)
        val evidence = TutorEvidence.PracticeAttempts(
            attemptCount = TutorStateMachine.MIN_PRACTICE_ATTEMPTS - 1, correctCount = 1
        )
        val result = TutorStateMachine.transition(practicing, evidence, 2L)
        assertInvalid(result)
        // The core "no evidence, no advance" invariant: state must not have moved.
        assertEquals(TutorState.PRACTICE, (result as TutorTransitionResult.Invalid).session.state)
    }

    @Test
    fun `PRACTICE rejects correctCount greater than attemptCount`() {
        val practicing = inState(TutorState.PRACTICE)
        assertThrows(IllegalArgumentException::class.java) {
            TutorStateMachine.transition(
                practicing, TutorEvidence.PracticeAttempts(attemptCount = 3, correctCount = 5), 2L
            )
        }
    }

    @Test
    fun `PRACTICE rejects non-PracticeAttempts evidence`() {
        val practicing = inState(TutorState.PRACTICE)
        val result = TutorStateMachine.transition(practicing, TutorEvidence.CloseOut, 2L)
        assertInvalid(result)
    }

    // ── VERIFY ───────────────────────────────────────────────────────────────

    @Test
    fun `VERIFY with mastery above threshold and no retention risk advances to MASTERED`() {
        val verifying = inState(TutorState.VERIFY)
        val evidence = TutorEvidence.Verification(snapshot(above, RetentionDecisionSnapshot.MOVE_ON), null)
        val result = TutorStateMachine.transition(verifying, evidence, 2L)
        assertAdvancedTo(result, TutorState.MASTERED)
    }

    @Test
    fun `VERIFY with high mastery but retention REVIEW does not count as mastered`() {
        // Core invariant check: a task being "done" is never enough — here even a
        // real, above-threshold mastery number must ALSO clear retention risk.
        val verifying = inState(TutorState.VERIFY)
        val evidence = TutorEvidence.Verification(snapshot(above, RetentionDecisionSnapshot.REVIEW), null)
        val result = TutorStateMachine.transition(verifying, evidence, 2L)
        assertAdvancedTo(result, TutorState.PRACTICE) // null error type defaults to PROCEDURAL
        assertEquals(1, (result as TutorTransitionResult.Advanced).session.cycleCount)
    }

    @Test
    fun `VERIFY failure with conceptual error routes to EXPLAIN and increments cycle`() {
        val verifying = inState(TutorState.VERIFY)
        val evidence = TutorEvidence.Verification(snapshot(below), "MISCONCEPTION")
        val result = TutorStateMachine.transition(verifying, evidence, 2L)
        assertAdvancedTo(result, TutorState.EXPLAIN)
        assertEquals(1, (result as TutorTransitionResult.Advanced).session.cycleCount)
    }

    @Test
    fun `VERIFY failure with procedural error routes to PRACTICE and increments cycle`() {
        val verifying = inState(TutorState.VERIFY)
        val evidence = TutorEvidence.Verification(snapshot(below), "CARELESS")
        val result = TutorStateMachine.transition(verifying, evidence, 2L)
        assertAdvancedTo(result, TutorState.PRACTICE)
        assertEquals(1, (result as TutorTransitionResult.Advanced).session.cycleCount)
    }

    @Test
    fun `VERIFY failure with null error type defaults to PROCEDURAL`() {
        val verifying = inState(TutorState.VERIFY)
        val evidence = TutorEvidence.Verification(snapshot(below), null)
        val result = TutorStateMachine.transition(verifying, evidence, 2L)
        assertAdvancedTo(result, TutorState.PRACTICE)
    }

    @Test
    fun `VERIFY escalates instead of looping once MAX_CYCLES is reached`() {
        var session = inState(TutorState.VERIFY, cycleCount = TutorStateMachine.MAX_CYCLES)
        val evidence = TutorEvidence.Verification(snapshot(below), "CARELESS")
        val result = TutorStateMachine.transition(session, evidence, 2L)
        assertAdvancedTo(result, TutorState.ESCALATED)
        // Escalation itself must not be counted as another retry cycle.
        assertEquals(
            TutorStateMachine.MAX_CYCLES,
            (result as TutorTransitionResult.Advanced).session.cycleCount
        )
    }

    @Test
    fun `VERIFY below MAX_CYCLES still loops rather than escalating`() {
        val session = inState(TutorState.VERIFY, cycleCount = TutorStateMachine.MAX_CYCLES - 1)
        val evidence = TutorEvidence.Verification(snapshot(below), "CARELESS")
        val result = TutorStateMachine.transition(session, evidence, 2L)
        assertAdvancedTo(result, TutorState.PRACTICE)
    }

    @Test
    fun `VERIFY rejects a snapshot for a different concept`() {
        val verifying = inState(TutorState.VERIFY)
        val evidence = TutorEvidence.Verification(snapshot(above, id = "some-other-concept"), null)
        assertThrows(IllegalArgumentException::class.java) {
            TutorStateMachine.transition(verifying, evidence, 2L)
        }
    }

    @Test
    fun `VERIFY rejects non-Verification evidence`() {
        val verifying = inState(TutorState.VERIFY)
        val result = TutorStateMachine.transition(verifying, TutorEvidence.ExplanationDelivered, 2L)
        assertInvalid(result)
    }

    @Test
    fun `classifyFailure buckets error types into CONCEPTUAL vs PROCEDURAL`() {
        listOf("UNKNOWN_CONCEPT", "MISCONCEPTION", "FORMULA_RECALL", "FORMULA_SELECTION").forEach {
            assertEquals(VerifyFailureShape.CONCEPTUAL, TutorStateMachine.classifyFailure(it))
        }
        listOf("CALCULATION", "UNIT_ERROR", "SIGN_ERROR", "QUESTION_MISREAD", "CARELESS", "TIME_PRESSURE", "BAD_GUESS", null)
            .forEach { assertEquals(VerifyFailureShape.PROCEDURAL, TutorStateMachine.classifyFailure(it)) }
    }

    // ── MASTERED ─────────────────────────────────────────────────────────────

    @Test
    fun `MASTERED plus CloseOut advances to MOVE_ON`() {
        val mastered = inState(TutorState.MASTERED)
        val result = TutorStateMachine.transition(mastered, TutorEvidence.CloseOut, 2L)
        assertAdvancedTo(result, TutorState.MOVE_ON)
    }

    @Test
    fun `MASTERED rejects non-CloseOut evidence`() {
        val mastered = inState(TutorState.MASTERED)
        val result = TutorStateMachine.transition(mastered, TutorEvidence.ExplanationDelivered, 2L)
        assertInvalid(result)
    }

    // ── Terminal states reject everything ───────────────────────────────────

    @Test
    fun `MOVE_ON is terminal and rejects every evidence type`() {
        val done = inState(TutorState.MOVE_ON)
        allEvidenceSamples().forEach { evidence ->
            assertInvalid(TutorStateMachine.transition(done, evidence, 2L))
        }
    }

    @Test
    fun `ESCALATED is terminal and rejects every evidence type`() {
        val escalated = inState(TutorState.ESCALATED)
        allEvidenceSamples().forEach { evidence ->
            assertInvalid(TutorStateMachine.transition(escalated, evidence, 2L))
        }
    }

    @Test
    fun `TutorState_isTerminal is true only for MOVE_ON and ESCALATED`() {
        TutorState.values().forEach { state ->
            val expected = state == TutorState.MOVE_ON || state == TutorState.ESCALATED
            assertEquals("isTerminal mismatch for $state", expected, state.isTerminal)
        }
    }

    // ── Full happy-path walk ─────────────────────────────────────────────────

    @Test
    fun `full cycle from DIAGNOSE to MOVE_ON accumulates one history record per transition`() {
        var session = TutorStateMachine.start(conceptId, 1L)
        session = advanceOrFail(session, TutorEvidence.Diagnostic(DiagnosticFinding.UNKNOWN), 2L)
        assertEquals(TutorState.EXPLAIN, session.state)

        session = advanceOrFail(session, TutorEvidence.ExplanationDelivered, 3L)
        assertEquals(TutorState.PRACTICE, session.state)

        session = advanceOrFail(
            session, TutorEvidence.PracticeAttempts(TutorStateMachine.MIN_PRACTICE_ATTEMPTS, 3), 4L
        )
        assertEquals(TutorState.VERIFY, session.state)

        session = advanceOrFail(session, TutorEvidence.Verification(snapshot(above), null), 5L)
        assertEquals(TutorState.MASTERED, session.state)

        session = advanceOrFail(session, TutorEvidence.CloseOut, 6L)
        assertEquals(TutorState.MOVE_ON, session.state)

        assertEquals(5, session.history.size)
        assertEquals(0, session.cycleCount) // never failed a VERIFY, so no retry cycle burned
        assertTrue(session.state.isTerminal)
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun inState(state: TutorState, cycleCount: Int = 0) = TutorSession(
        conceptId = conceptId,
        state = state,
        cycleCount = cycleCount,
        startedAt = 1L,
        updatedAt = 1L,
        history = emptyList()
    )

    private fun advanceOrFail(session: TutorSession, evidence: TutorEvidence, now: Long): TutorSession {
        val result = TutorStateMachine.transition(session, evidence, now)
        assertTrue("expected Advanced, got $result", result is TutorTransitionResult.Advanced)
        return (result as TutorTransitionResult.Advanced).session
    }

    private fun assertAdvancedTo(result: TutorTransitionResult, expected: TutorState) {
        assertTrue("expected Advanced, got $result", result is TutorTransitionResult.Advanced)
        assertEquals(expected, (result as TutorTransitionResult.Advanced).session.state)
    }

    private fun assertInvalid(result: TutorTransitionResult) {
        assertTrue("expected Invalid, got $result", result is TutorTransitionResult.Invalid)
        assertFalse((result as TutorTransitionResult.Invalid).reason.isBlank())
    }

    private fun allEvidenceSamples(): List<TutorEvidence> = listOf(
        TutorEvidence.Diagnostic(DiagnosticFinding.KNOWN),
        TutorEvidence.ExplanationDelivered,
        TutorEvidence.PracticeAttempts(TutorStateMachine.MIN_PRACTICE_ATTEMPTS, 1),
        TutorEvidence.Verification(snapshot(above), null),
        TutorEvidence.CloseOut
    )
}
