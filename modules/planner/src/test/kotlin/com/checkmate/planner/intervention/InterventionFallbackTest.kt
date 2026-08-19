package com.checkmate.planner.intervention

import com.checkmate.planner.model.StudyTask
import com.checkmate.planner.model.TaskState
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proactive Execution Engine — Step 6 (Blueprint Part One, §14). Nothing here touches a
 * real network or Robolectric — [InterventionFallback.attemptLlm] takes an injectable
 * llmCall precisely so timeout behavior is testable as a plain JVM test. runTest's virtual
 * time scheduler advances through delay() automatically, so the "LLM never responds in
 * time" tests below don't actually wait 3 real seconds.
 */
class InterventionFallbackTest {

    // ── attemptLlm ───────────────────────────────────────────────────────

    @Test
    fun `attemptLlm returns the response when it arrives within budget`() = runTest {
        val result = InterventionFallback.attemptLlm(
            prompt = "x",
            timeoutMillis = 3_000L,
            llmCall = { _, _ -> "structured response" }
        )
        assertEquals("structured response", result)
    }

    @Test
    fun `attemptLlm returns null when the call does not resolve within the timeout`() = runTest {
        val result = InterventionFallback.attemptLlm(
            prompt = "x",
            timeoutMillis = 3_000L,
            llmCall = { _, _ -> delay(10_000L); "too late" }
        )
        assertNull(result)
    }

    @Test
    fun `attemptLlm returns null for a blank response, matching LlmGateway's failure contract`() = runTest {
        val result = InterventionFallback.attemptLlm(
            prompt = "x",
            llmCall = { _, _ -> "" }
        )
        assertNull(result)
    }

    // ── strictReminderIntent ─────────────────────────────────────────────

    @Test
    fun `strictReminderIntent mentions lateness when late`() {
        val task = StudyTask(subject = "Physics", topic = "Electrostatics", durationMinutes = 90)
        val intent = InterventionFallback.strictReminderIntent(task, lateMinutes = 8)
        assertEquals(InterventionIntentType.START_TASK, intent.intentType)
        assertEquals(task.id, intent.targetTaskId)
        assertTrue(intent.speech.contains("8 minutes late"))
        assertTrue(intent.speech.contains("Electrostatics"))
    }

    @Test
    fun `strictReminderIntent has a plain prompt when not late`() {
        val task = StudyTask(subject = "Physics", topic = "Electrostatics", durationMinutes = 90)
        val intent = InterventionFallback.strictReminderIntent(task, lateMinutes = 0)
        assertTrue(!intent.speech.contains("late"))
        assertTrue(intent.speech.contains("Electrostatics"))
    }

    // ── InterventionDecisionMaker ────────────────────────────────────────

    private class Fixture(task: StudyTask) {
        val dao = FakeInterventionTransactionDao()
        val escrow = TaskEscrow(dao)
        val mutator = FakeTaskMutator(listOf(task))
        val executor = ActionExecutor(dao, escrow, mutator)
        val decisionMaker = InterventionDecisionMaker(escrow, executor)
        val taskId = task.id

        suspend fun acquire(now: Long = 1_000L): InterventionTransaction =
            (escrow.acquire(taskId, InterventionTriggerType.LATE_START, now = now)
                    as EscrowAcquireResult.Acquired).transaction
    }

    private fun pendingTask(state: TaskState = TaskState.PENDING) =
        StudyTask(subject = "Physics", topic = "Electrostatics", durationMinutes = 90, state = state)

    @Test
    fun `deterministic-only path (no LLM attempted) starts the task`() = runTest {
        val f = Fixture(pendingTask())
        val tx = f.acquire()

        val outcome = f.decisionMaker.decideAndExecute(
            transactionId = tx.transactionId,
            task = f.mutator.currentState(f.taskId)!!,
            lateMinutes = 8
        )

        assertTrue(outcome is DecisionOutcome.Executed)
        val executed = outcome as DecisionOutcome.Executed
        assertTrue(executed.executionOutcome is ExecutionOutcome.Applied)
        assertTrue(executed.usedFallback)
        assertEquals(TaskState.ACTIVE, f.mutator.currentState(f.taskId)?.state)
        assertEquals(InterventionState.COMPLETED, f.dao.getById(tx.transactionId)?.currentState)
    }

    @Test
    fun `LLM timeout still resolves deterministically via the fallback path`() = runTest {
        val f = Fixture(pendingTask())
        val tx = f.acquire()

        val outcome = f.decisionMaker.decideAndExecute(
            transactionId = tx.transactionId,
            task = f.mutator.currentState(f.taskId)!!,
            lateMinutes = 5,
            llmPrompt = "what should the student do?",
            llmTimeoutMillis = 3_000L,
            llmCall = { _, _ -> delay(10_000L); "never arrives in time" }
        )

        assertTrue(outcome is DecisionOutcome.Executed)
        assertTrue((outcome as DecisionOutcome.Executed).executionOutcome is ExecutionOutcome.Applied)
        assertEquals(TaskState.ACTIVE, f.mutator.currentState(f.taskId)?.state)
    }

    @Test
    fun `an LLM response missing required fields still falls back deterministically`() = runTest {
        // Step 11: LlmIntentParser now exists, but a response that doesn't match the closed
        // schema (here: no "speech") is still unparseable, so this must still fall back —
        // parse failure and "no response at all" are handled identically.
        val f = Fixture(pendingTask())
        val tx = f.acquire()

        val outcome = f.decisionMaker.decideAndExecute(
            transactionId = tx.transactionId,
            task = f.mutator.currentState(f.taskId)!!,
            lateMinutes = 5,
            llmPrompt = "what should the student do?",
            llmCall = { _, _ -> """{"intentType":"REDUCE_DURATION"}""" }
        )

        assertTrue(outcome is DecisionOutcome.Executed)
        assertTrue((outcome as DecisionOutcome.Executed).executionOutcome is ExecutionOutcome.Applied)
        assertTrue(outcome.usedFallback)
        // Still a START_TASK outcome (the deterministic reminder), not a REDUCE_DURATION —
        // proves the malformed LLM text was not acted on.
        assertEquals(TaskState.ACTIVE, f.mutator.currentState(f.taskId)?.state)
        assertEquals(90, f.mutator.currentState(f.taskId)?.durationMinutes)
    }

    @Test
    fun `a well-formed LLM response is now actually trusted through the parser`() = runTest {
        val f = Fixture(pendingTask())
        val tx = f.acquire()

        val outcome = f.decisionMaker.decideAndExecute(
            transactionId = tx.transactionId,
            task = f.mutator.currentState(f.taskId)!!,
            lateMinutes = 5,
            llmPrompt = "what should the student do?",
            llmCall = { _, _ ->
                """{"speech": "Let's do 30 minutes instead.", "intentType": "REDUCE_DURATION",
                    "parameters": {"newDurationMinutes": "30"}}"""
            }
        )

        assertTrue(outcome is DecisionOutcome.Executed)
        val executed = outcome as DecisionOutcome.Executed
        assertTrue(executed.executionOutcome is ExecutionOutcome.Applied)
        assertTrue(!executed.usedFallback)
        assertEquals("Let's do 30 minutes instead.", executed.speech)
        assertEquals(30, f.mutator.currentState(f.taskId)?.durationMinutes)
        // Not started — this was a duration reduction, not a start.
        assertEquals(TaskState.PENDING, f.mutator.currentState(f.taskId)?.state)
    }

    @Test
    fun `a well-formed but policy-rejected LLM response is not silently downgraded to fallback`() = runTest {
        // A 4-hour break parses fine but PolicyValidator rejects it (BREAK_TOO_LONG) — this
        // must surface as PolicyRejected, not quietly re-resolve via the strict reminder.
        val f = Fixture(pendingTask())
        val tx = f.acquire()

        val outcome = f.decisionMaker.decideAndExecute(
            transactionId = tx.transactionId,
            task = f.mutator.currentState(f.taskId)!!,
            lateMinutes = 5,
            llmPrompt = "what should the student do?",
            llmCall = { _, _ ->
                """{"speech": "Take four hours off.", "intentType": "TAKE_SHORT_BREAK",
                    "parameters": {"minutes": "240"}}"""
            }
        )

        assertTrue(outcome is DecisionOutcome.PolicyRejected)
        assertEquals(RejectionReason.BREAK_TOO_LONG, (outcome as DecisionOutcome.PolicyRejected).reason)
        assertTrue(!outcome.usedFallback)
        assertEquals(TaskState.PENDING, f.mutator.currentState(f.taskId)?.state)
    }

    // ── attemptConversationalTurn ───────────────────────────────────────

    @Test
    fun `conversational turn with no LLM response does not touch the transaction`() = runTest {
        val f = Fixture(pendingTask())
        val tx = f.acquire()

        val outcome = f.decisionMaker.attemptConversationalTurn(
            transactionId = tx.transactionId,
            task = f.mutator.currentState(f.taskId)!!,
            rawLlmResponse = null
        )

        assertEquals(ConversationalOutcome.LlmUnavailable, outcome)
        assertEquals(InterventionState.NEGOTIATING, f.dao.getById(tx.transactionId)?.currentState)
    }

    @Test
    fun `conversational turn with unparseable response does not touch the transaction`() = runTest {
        val f = Fixture(pendingTask())
        val tx = f.acquire()

        val outcome = f.decisionMaker.attemptConversationalTurn(
            transactionId = tx.transactionId,
            task = f.mutator.currentState(f.taskId)!!,
            rawLlmResponse = "not json"
        )

        assertEquals(ConversationalOutcome.UnparseableResponse, outcome)
        assertEquals(InterventionState.NEGOTIATING, f.dao.getById(tx.transactionId)?.currentState)
    }

    @Test
    fun `REQUEST_CLARIFICATION does not execute or resolve the transaction`() = runTest {
        val f = Fixture(pendingTask())
        val tx = f.acquire()

        val outcome = f.decisionMaker.attemptConversationalTurn(
            transactionId = tx.transactionId,
            task = f.mutator.currentState(f.taskId)!!,
            rawLlmResponse = """{"speech": "How long do you have right now?", "intentType": "REQUEST_CLARIFICATION"}"""
        )

        assertTrue(outcome is ConversationalOutcome.Continue)
        assertEquals("How long do you have right now?", (outcome as ConversationalOutcome.Continue).speech)
        // Still open — NOT committed, unlike ActionExecutor's NotApplicable branch would do.
        assertEquals(InterventionState.NEGOTIATING, f.dao.getById(tx.transactionId)?.currentState)
    }

    @Test
    fun `NO_ACTION does not execute or resolve the transaction`() = runTest {
        val f = Fixture(pendingTask())
        val tx = f.acquire()

        val outcome = f.decisionMaker.attemptConversationalTurn(
            transactionId = tx.transactionId,
            task = f.mutator.currentState(f.taskId)!!,
            rawLlmResponse = """{"speech": "Got it, take your time.", "intentType": "NO_ACTION"}"""
        )

        assertTrue(outcome is ConversationalOutcome.Continue)
        assertEquals(InterventionState.NEGOTIATING, f.dao.getById(tx.transactionId)?.currentState)
    }

    @Test
    fun `a decisive intent in conversation executes and resolves the transaction`() = runTest {
        val f = Fixture(pendingTask())
        val tx = f.acquire()

        val outcome = f.decisionMaker.attemptConversationalTurn(
            transactionId = tx.transactionId,
            task = f.mutator.currentState(f.taskId)!!,
            rawLlmResponse = """{"speech": "Starting now.", "intentType": "START_TASK"}"""
        )

        assertTrue(outcome is ConversationalOutcome.Decided)
        assertTrue((outcome as ConversationalOutcome.Decided).executionOutcome is ExecutionOutcome.Applied)
        assertEquals(TaskState.ACTIVE, f.mutator.currentState(f.taskId)?.state)
        assertEquals(InterventionState.COMPLETED, f.dao.getById(tx.transactionId)?.currentState)
    }

    @Test
    fun `a policy-rejected intent in conversation leaves the transaction open to keep negotiating`() = runTest {
        val f = Fixture(pendingTask())
        val tx = f.acquire()

        val outcome = f.decisionMaker.attemptConversationalTurn(
            transactionId = tx.transactionId,
            task = f.mutator.currentState(f.taskId)!!,
            rawLlmResponse = """{"speech": "Take four hours off.", "intentType": "TAKE_SHORT_BREAK",
                "parameters": {"minutes": "240"}}"""
        )

        assertTrue(outcome is ConversationalOutcome.Rejected)
        assertEquals(RejectionReason.BREAK_TOO_LONG, (outcome as ConversationalOutcome.Rejected).reason)
        // Unlike decideAndExecute's Rejected branch, a conversational rejection does NOT
        // resolve the transaction — the student gets to try again in the same conversation.
        assertEquals(InterventionState.NEGOTIATING, f.dao.getById(tx.transactionId)?.currentState)
    }

    @Test
    fun `a task in a non-startable state is rejected by policy and resolves POLICY_REJECTED`() = runTest {
        val f = Fixture(pendingTask(state = TaskState.ACTIVE))
        val tx = f.acquire()

        val outcome = f.decisionMaker.decideAndExecute(
            transactionId = tx.transactionId,
            task = f.mutator.currentState(f.taskId)!!,
            lateMinutes = 5
        )

        assertTrue(outcome is DecisionOutcome.PolicyRejected)
        assertEquals(RejectionReason.TASK_NOT_ACTIVE_STATE, (outcome as DecisionOutcome.PolicyRejected).reason)
        assertEquals(InterventionState.POLICY_REJECTED, f.dao.getById(tx.transactionId)?.currentState)
    }
}
