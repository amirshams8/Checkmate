package com.checkmate.planner.intervention

import com.checkmate.learning.engine.LearningDecisionEngine
import com.checkmate.planner.model.TaskType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Upgrade Blueprint Phase 2.4/2.5 (P0a). Pure JVM unit tests — [LearningInterventionMapper]
 * has no Android dependency, same testability posture as PolicyValidator/ActionExecutor.
 */
class LearningInterventionMapperTest {

    private fun candidate(
        intent: LearningDecisionEngine.LearningInterventionIntent,
        conceptId: String? = "phy_rotational_inertia",
        subject: String? = "Physics",
        chapter: String? = "Rotational Motion",
        topic: String? = null,
        durationMinutes: Int = 25,
        rationale: String = "Weak prerequisite for an upcoming target concept"
    ) = LearningDecisionEngine.CandidateIntervention(
        intent = intent,
        conceptId = conceptId,
        subject = subject,
        chapter = chapter,
        topic = topic,
        durationMinutes = durationMinutes,
        expectedGain = 1.5,
        priorityScore = 1.5,
        rationale = rationale
    )

    // ── Supported intents (P0a scope) ───────────────────────────────────

    @Test
    fun `REPAIR_CONCEPT maps to a LECTURE CreateTaskRequest carrying the concept id`() {
        val request = LearningInterventionMapper.toCreateTaskRequest(
            candidate(LearningDecisionEngine.LearningInterventionIntent.REPAIR_CONCEPT)
        )
        assertTrue(request != null)
        request!!
        assertEquals("Physics", request.subject)
        assertEquals("Rotational Motion", request.topic)
        assertEquals(25, request.durationMinutes)
        assertEquals(TaskType.LECTURE, request.taskType)
        assertEquals("REPAIR_CONCEPT", request.learningIntent)
        assertEquals("phy_rotational_inertia", request.conceptId)
        assertEquals(listOf("phy_rotational_inertia"), request.targetedConceptIds)
    }

    @Test
    fun `START_DIAGNOSTIC maps to a PRACTICE CreateTaskRequest`() {
        val request = LearningInterventionMapper.toCreateTaskRequest(
            candidate(LearningDecisionEngine.LearningInterventionIntent.START_DIAGNOSTIC)
        )
        assertTrue(request != null)
        assertEquals(TaskType.PRACTICE, request!!.taskType)
        assertEquals("START_DIAGNOSTIC", request.learningIntent)
    }

    @Test
    fun `ASSIGN_TARGETED_SET maps to a PRACTICE CreateTaskRequest with no targeted concept ids`() {
        // ASSIGN_TARGETED_SET candidates are collapsed chapter-level groups — conceptId is
        // null by construction (see LearningInterventionMapper's own doc).
        val request = LearningInterventionMapper.toCreateTaskRequest(
            candidate(LearningDecisionEngine.LearningInterventionIntent.ASSIGN_TARGETED_SET, conceptId = null)
        )
        assertTrue(request != null)
        assertEquals(TaskType.PRACTICE, request!!.taskType)
        assertEquals("ASSIGN_TARGETED_SET", request.learningIntent)
        assertNull(request.conceptId)
        assertEquals(emptyList<String>(), request.targetedConceptIds)
    }

    @Test
    fun `topic falls back to topic field then conceptId when chapter is null`() {
        val request = LearningInterventionMapper.toCreateTaskRequest(
            candidate(
                LearningDecisionEngine.LearningInterventionIntent.REPAIR_CONCEPT,
                chapter = null,
                topic = "Angular Momentum"
            )
        )
        assertEquals("Angular Momentum", request!!.topic)
    }

    // ── SCHEDULE_RETENTION_TEST / START_MOCK (P0a continuation — now supported) ──

    @Test
    fun `SCHEDULE_RETENTION_TEST maps to a REVISION CreateTaskRequest carrying the concept id`() {
        val request = LearningInterventionMapper.toCreateTaskRequest(
            candidate(LearningDecisionEngine.LearningInterventionIntent.SCHEDULE_RETENTION_TEST)
        )
        assertTrue(request != null)
        request!!
        assertEquals("Physics", request.subject)
        assertEquals("Rotational Motion", request.topic)
        assertEquals(25, request.durationMinutes)
        assertEquals(TaskType.REVISION, request.taskType)
        assertEquals("SCHEDULE_RETENTION_TEST", request.learningIntent)
        assertEquals("phy_rotational_inertia", request.conceptId)
        assertEquals(listOf("phy_rotational_inertia"), request.targetedConceptIds)
    }

    @Test
    fun `a SCHEDULE_RETENTION_TEST candidate with no subject is not mapped`() {
        val request = LearningInterventionMapper.toCreateTaskRequest(
            candidate(LearningDecisionEngine.LearningInterventionIntent.SCHEDULE_RETENTION_TEST, subject = null)
        )
        assertNull(request)
    }

    @Test
    fun `START_MOCK maps to a whole-syllabus PRACTICE CreateTaskRequest regardless of subject, chapter, topic, or conceptId`() {
        // START_MOCK candidates always arrive with subject/chapter/topic/conceptId null in
        // production (LearningDecisionEngine.macroCandidates) — this is the one intent the
        // mapper's own doc says is exempt from the general subject-required decline below.
        val request = LearningInterventionMapper.toCreateTaskRequest(
            candidate(
                LearningDecisionEngine.LearningInterventionIntent.START_MOCK,
                conceptId = null,
                subject = null,
                chapter = null,
                topic = null
            )
        )
        assertTrue(request != null)
        request!!
        assertEquals("Full Syllabus", request.subject)
        assertEquals("Full-Length Mock Test", request.topic)
        assertEquals(TaskType.PRACTICE, request.taskType)
        assertEquals("START_MOCK", request.learningIntent)
        assertNull(request.conceptId)
        assertEquals(emptyList<String>(), request.targetedConceptIds)
    }

    @Test
    fun `START_MOCK carries the candidate's durationMinutes and rationale through unchanged`() {
        val request = LearningInterventionMapper.toCreateTaskRequest(
            candidate(
                LearningDecisionEngine.LearningInterventionIntent.START_MOCK,
                conceptId = null,
                subject = null,
                chapter = null,
                topic = null,
                durationMinutes = 180,
                rationale = "Full-length mock due before exam"
            )
        )
        assertEquals(180, request!!.durationMinutes)
        assertEquals("Full-length mock due before exam", request.rationale)
    }

    // ── Out-of-scope intents (deliberately not wired in P0a) ────────────

    @Test
    fun `REPLAN_DAY is not mapped`() {
        val request = LearningInterventionMapper.toCreateTaskRequest(
            candidate(LearningDecisionEngine.LearningInterventionIntent.REPLAN_DAY)
        )
        assertNull(request)
    }

    @Test
    fun `REDUCE_DIFFICULTY and INCREASE_DIFFICULTY are not mapped`() {
        listOf(
            LearningDecisionEngine.LearningInterventionIntent.REDUCE_DIFFICULTY,
            LearningDecisionEngine.LearningInterventionIntent.INCREASE_DIFFICULTY
        ).forEach { intent ->
            assertNull(LearningInterventionMapper.toCreateTaskRequest(candidate(intent)))
        }
    }

    // ── Missing data (declines rather than guesses) ──────────────────────

    @Test
    fun `a REPAIR_CONCEPT candidate with no subject is not mapped`() {
        val request = LearningInterventionMapper.toCreateTaskRequest(
            candidate(LearningDecisionEngine.LearningInterventionIntent.REPAIR_CONCEPT, subject = null)
        )
        assertNull(request)
    }

    @Test
    fun `a candidate with no chapter, topic, or conceptId is not mapped`() {
        val request = LearningInterventionMapper.toCreateTaskRequest(
            candidate(
                LearningDecisionEngine.LearningInterventionIntent.START_DIAGNOSTIC,
                chapter = null,
                topic = null,
                conceptId = null
            )
        )
        assertNull(request)
    }
}
