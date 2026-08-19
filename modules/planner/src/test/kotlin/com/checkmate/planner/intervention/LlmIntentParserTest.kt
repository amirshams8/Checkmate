package com.checkmate.planner.intervention

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmIntentParserTest {

    @Test
    fun `parses a well-formed intent with parameters`() {
        val raw = """
            {"speech": "Sure, 30 minutes it is.", "intentType": "REDUCE_DURATION",
             "targetTaskId": "task-1", "parameters": {"newDurationMinutes": "30"}}
        """.trimIndent()

        val intent = LlmIntentParser.parse(raw, fallbackTaskId = "fallback-id")

        assertEquals("Sure, 30 minutes it is.", intent?.speech)
        assertEquals(InterventionIntentType.REDUCE_DURATION, intent?.intentType)
        assertEquals("task-1", intent?.targetTaskId)
        assertEquals("30", intent?.parameters?.get("newDurationMinutes"))
    }

    @Test
    fun `strips a markdown code fence some models wrap JSON in`() {
        val raw = "```json\n{\"speech\": \"ok\", \"intentType\": \"KEEP_PLAN\"}\n```"
        val intent = LlmIntentParser.parse(raw, fallbackTaskId = "t1")
        assertEquals(InterventionIntentType.KEEP_PLAN, intent?.intentType)
    }

    @Test
    fun `missing targetTaskId falls back to the caller-supplied task id`() {
        val raw = """{"speech": "ok", "intentType": "START_TASK"}"""
        val intent = LlmIntentParser.parse(raw, fallbackTaskId = "the-only-task")
        assertEquals("the-only-task", intent?.targetTaskId)
    }

    @Test
    fun `missing speech is treated as unparseable`() {
        val raw = """{"intentType": "KEEP_PLAN"}"""
        assertNull(LlmIntentParser.parse(raw, fallbackTaskId = "t1"))
    }

    @Test
    fun `intentType outside the closed schema is unparseable, including protected actions`() {
        // Never representable — this is the "not rejected by a rule, simply not
        // representable" property LlmIntent.parseIntentType's own doc describes.
        val raw = """{"speech": "ok", "intentType": "UNLOCK_DEVICE"}"""
        assertNull(LlmIntentParser.parse(raw, fallbackTaskId = "t1"))
    }

    @Test
    fun `malformed JSON is unparseable`() {
        assertNull(LlmIntentParser.parse("not json at all", fallbackTaskId = "t1"))
        assertNull(LlmIntentParser.parse("""{"speech": "ok", """, fallbackTaskId = "t1"))
    }

    @Test
    fun `blank raw text is unparseable`() {
        assertNull(LlmIntentParser.parse("   ", fallbackTaskId = "t1"))
    }

    @Test
    fun `missing parameters object defaults to empty map`() {
        val raw = """{"speech": "ok", "intentType": "NO_ACTION"}"""
        val intent = LlmIntentParser.parse(raw, fallbackTaskId = "t1")
        assertTrue(intent?.parameters?.isEmpty() == true)
    }
}
