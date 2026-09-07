package com.checkmate.learning.tutor

/**
 * Upgrade Blueprint Phase 3, P3.1 — the gateway seam, introduced now even with only one
 * implementation ([DirectLlmGateway]), so a future `RemoteLlmGateway` (Worker-fronted,
 * personal-convenience only per the roadmap's own "deferred, not blocking" note) can slot
 * in later without touching [TutorDiagnosticContext]/[LlmDiagnosticResponse]/
 * [LlmExplanationResponse] or any caller of this interface.
 *
 * NAMED DELIBERATELY DIFFERENT FROM [com.checkmate.core.llm.LlmGateway]: that `object`
 * already exists as the concrete multi-provider transport (Groq/OpenRouter/Claude/Gemini/
 * VibeBuild, chosen via CheckmatePrefs). This interface does not replace or wrap over it at
 * the transport level — [DirectLlmGateway] is a NEW consumer of the existing
 * `com.checkmate.core.llm.LlmGateway.complete()` call, one layer up:
 * ```
 * TutorLlmGateway (this file)
 *        |
 *        v
 * DirectLlmGateway  ── prompt building + response parsing (tutor-specific)
 *        |
 *        v
 * com.checkmate.core.llm.LlmGateway.complete()  ── unchanged, provider transport only
 *        |
 *        v
 * Groq / OpenRouter / Claude / Gemini / VibeBuild
 * ```
 * Both `diagnose`/`explain` return null (never throw) on a blank/unparseable LLM response —
 * same "no API key configured -> empty string -> caller falls back" contract
 * `com.checkmate.core.llm.LlmGateway.complete`'s own doc already establishes; a caller here
 * feeds a null straight into [TutorDiagnosticValidator.deterministicFallback] the same way
 * a null [LlmIntentParser][com.checkmate.planner.intervention.LlmIntentParser] result
 * already triggers `InterventionFallback` elsewhere in this codebase.
 */
interface TutorLlmGateway {
    suspend fun diagnose(context: TutorDiagnosticContext): LlmDiagnosticResponse?
    suspend fun explain(context: TutorExplanationContext): LlmExplanationResponse?
}

object DirectLlmGateway : TutorLlmGateway {

    override suspend fun diagnose(context: TutorDiagnosticContext): LlmDiagnosticResponse? {
        val raw = com.checkmate.core.llm.LlmGateway.complete(
            prompt = buildDiagnosePrompt(context),
            systemPrompt = DIAGNOSE_SYSTEM_PROMPT
        )
        if (raw.isBlank()) return null
        return LlmDiagnosticResponse.parse(raw)
    }

    override suspend fun explain(context: TutorExplanationContext): LlmExplanationResponse? {
        val raw = com.checkmate.core.llm.LlmGateway.complete(
            prompt = buildExplainPrompt(context),
            systemPrompt = EXPLAIN_SYSTEM_PROMPT
        )
        if (raw.isBlank()) return null
        return LlmExplanationResponse.parse(raw)
    }

    // ── DIAGNOSE prompt ──────────────────────────────────────────────────────

    /** Lists the closed [HypothesisType] vocabulary verbatim so the model classifies into
     *  it rather than inventing labels — same "closed schema, LLM doesn't invent" framing
     *  [com.checkmate.planner.intervention.LlmIntentParser]'s own expected-shape doc uses
     *  for `intentType`. */
    private val DIAGNOSE_SYSTEM_PROMPT = """
        You are Checkmate's diagnostic reasoning step for one exam-prep concept. You do not
        decide anything — a separate deterministic validator decides what happens next. Your
        only job is to propose a hypothesis for why the student is stuck, grounded in the
        evidence given.

        Respond with ONLY a single JSON object, no markdown, no commentary:
        {
          "hypothesis": "<one or two sentences, specific to the evidence>",
          "hypothesisType": "<one of: ${HypothesisType.entries.filter { it != HypothesisType.UNKNOWN }.joinToString(", ") { it.name }}>",
          "suspectedSubConcept": "<a narrower sub-topic than the given concept, or null>",
          "reasoningSummary": "<why this hypothesis, one sentence>",
          "evidenceQuestionIds": ["<question ids from the given error history that support this>"],
          "confidence": <0.0 to 1.0>,
          "recommendedProbe": "<a short diagnostic question to confirm/refute this, or null>"
        }

        Only cite question ids that appear in the evidence you were given — never invent one.
        If the evidence doesn't clearly support one hypothesis, say so honestly with a lower
        confidence rather than guessing.
    """.trimIndent()

    private fun buildDiagnosePrompt(context: TutorDiagnosticContext): String = buildString {
        appendLine("Concept: ${context.subject ?: "?"} / ${context.chapter ?: "?"} / ${context.topic ?: context.conceptId}")
        appendLine("Mastery: ${context.mastery} (attempts=${context.attemptCount}, recentAccuracy=${context.recentAccuracy}, lifetimeAccuracy=${context.lifetimeAccuracy})")
        appendLine("Retention: ${context.retentionDecision}, forgettingRisk=${context.forgettingRisk}")
        appendLine("Tutor state: ${context.tutorState}, cycleCount=${context.cycleCount}")
        if (context.weakPrerequisites.isNotEmpty()) {
            appendLine("Weak prerequisites: " + context.weakPrerequisites.joinToString { it.topic ?: it.chapter ?: it.conceptId })
        }
        appendLine("Recent wrong-answer evidence (id: errorType @ timestamp):")
        if (context.recentErrors.isEmpty()) {
            appendLine("  (none)")
        } else {
            context.recentErrors.forEach { appendLine("  ${it.questionId}: ${it.errorType} @ ${it.timestamp}") }
        }
    }

    // ── EXPLAIN prompt ───────────────────────────────────────────────────────

    private val EXPLAIN_SYSTEM_PROMPT = """
        You are Checkmate's explanation-generation step for one exam-prep concept, following
        a diagnosis that already identified why the student is stuck. Do not free-lecture —
        produce short, targeted pieces the app can compose from, not one long essay.

        Respond with ONLY a single JSON object, no markdown, no commentary:
        {
          "explanation": "<short, targeted micro-explanation, 2-4 sentences>",
          "keyIdea": "<the single core idea in one sentence, or null>",
          "workedExample": "<one worked example, or null>",
          "commonTrap": "<the specific mistake this student is making, or null>",
          "checkQuestion": "<a quick question to check understanding, or null>",
          "hintLadder": ["<hint 1, smallest nudge>", "<hint 2>", "<hint 3, most direct>"],
          "groundingRefs": ["<what this is grounded in — concept/question ids, syllabus refs>"]
        }
    """.trimIndent()

    private fun buildExplainPrompt(context: TutorExplanationContext): String = buildString {
        val d = context.diagnostic
        val f = context.finding
        appendLine("Concept: ${d.subject ?: "?"} / ${d.chapter ?: "?"} / ${d.topic ?: d.conceptId}")
        appendLine("Diagnosis: ${f.hypothesisType} (confidence=${f.confidence}, source=${f.source})")
        appendLine("Hypothesis: ${f.hypothesis}")
        f.suspectedSubConcept?.let { appendLine("Suspected sub-concept: $it") }
        appendLine("Student mastery: ${d.mastery}, recentAccuracy=${d.recentAccuracy}")
    }
}
