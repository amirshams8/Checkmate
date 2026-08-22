package com.checkmate.learning.testmate

/**
 * Upgrade Blueprint Phase 1.3 ("Testmate → data source, not just a results screen").
 *
 * Parses the plain-text report Testmate produces (see e.g.
 * `fortnightly-test-for-neet-2026-rm-p1-ft-01a-report.md`) — a *different* surface
 * from :modules:testmate's TestmateApi/TestmateModels, which hit Testmate's JSON REST
 * endpoints (`/api/sessions/:id/results`) and only carry chapter/topic-level
 * aggregates, no per-question detail. This report format is strictly richer —
 * question text, full option text, the student's exact selected option, the correct
 * option, a worked solution on wrong questions, and per-question time — which is
 * what actually lets [TestResultNormalizer] produce real QuestionAttempt rows
 * (and, as of this update, evidence an LLM ErrorEngine classifier can reason over)
 * instead of only aggregate stats.
 *
 * Deliberately format-matched, not "smart"/fuzzy: this parses exactly the shape
 * Testmate's `markdown-export.ts` (`buildTestReportMarkdown`) emits today, verified
 * against both that source and a real report export — not guessed. If Testmate's
 * report format changes, this should fail loudly (missing score/zero questions)
 * rather than silently misparse — see [TestResultNormalizer]'s validation before
 * it writes anything.
 *
 * Expected shape per question (3 or 4 lines — Options/Explanation are conditional):
 * ```
 * Q108 [Cell: The Unit of Life/—] — WRONG — time 11s
 *   Q: <question text>
 *   Options: a) <option a text> | b) <option b text> | c) <option c text> | d) <option d text>
 *   Your answer: c) <option c text> | Correct: b) <option b text>
 *   Explanation: <worked solution — WRONG questions only, and only when non-null>
 * ```
 * `Options:` is omitted entirely for numeric/subjective questions (no fixed option
 * set) — in that case `Your answer`/`Correct` fall back to a bare letter (or "—"),
 * same as before this update. `topic` of "—" means "not tracked for this question"
 * and is normalized to null (matches the report's own "Not enough attempted
 * questions to break this down" caveat under "## Weak topics" — topic-level data
 * isn't reliably present).
 *
 * BREAKING CHANGE from the previous format: `Your answer`/`Correct` used to carry
 * bare letters only ("Your answer: c | Correct: c"). They now carry
 * "<letter>) <option text>" when the option set is available (formatAnswer() in
 * markdown-export.ts). [ParsedQuestionResult.selectedOption]/[correctOption] are
 * still bare letters — the option *text* moves to the new [ParsedQuestionResult.options]
 * map instead — so nothing downstream of this parser (Question/QuestionAttempt,
 * ErrorEngine's existing letter comparisons) needs to change to keep working.
 */
enum class ParsedQuestionStatus { CORRECT, WRONG, SKIPPED }

data class ParsedQuestionResult(
    val number: Int,
    val chapter: String,
    val topic: String?,
    val status: ParsedQuestionStatus,
    val timeSeconds: Int?,
    val questionText: String?,
    val selectedOption: String?,
    val correctOption: String?,
    /** Null for numeric/subjective questions (no fixed option set) or when the Options: line is absent/unparseable. */
    val options: Map<String, String>? = null,
    /** Worked solution. Testmate only ever renders this for WRONG questions — null for CORRECT/SKIPPED and for WRONG questions with no explanation on file. */
    val explanation: String? = null
)

data class ParsedTestReport(
    val title: String,
    val exam: String?,
    val scoreObtained: Double?,
    val scoreTotal: Double?,
    val scorePercent: Double?,
    val attempted: Int?,
    val correct: Int?,
    val wrong: Int?,
    val skipped: Int?,
    val questions: List<ParsedQuestionResult>
)

object TestReportParser {

    private val TITLE_LINE = Regex("""^#\s*Test Report\s*—\s*(.+)$""")
    private val TITLE_EXAM_SUFFIX = Regex("""\(([^()]+)\)\s*$""")
    private val SCORE_LINE = Regex(
        """^Score:\s*([\d.]+)\s*/\s*([\d.]+)\s*\(([\d.]+)%\)\s*\|\s*Attempted:\s*(\d+)\s*\|\s*""" +
            """Correct:\s*(\d+)\s*\|\s*Wrong:\s*(\d+)\s*\|\s*Skipped:\s*(\d+)\s*$"""
    )
    // "— time Ns" is optional: verified against the actual sample report that Testmate
    // sometimes emits a question row with no time field at all (e.g. its own Q57 —
    // "Q57 [...] — CORRECT" with nothing after). Treating it as required silently
    // dropped that entire question (and both lines under it) during parsing —
    // caught by cross-checking parsed question count against the report's own
    // header totals, not assumed.
    private val QUESTION_HEADER = Regex(
        """^Q(\d+)\s*\[(.+?)/(.+?)]\s*—\s*(CORRECT|WRONG|SKIPPED)(?:\s*—\s*time\s*(\d+)s)?\s*$"""
    )
    private val QUESTION_TEXT_LINE = Regex("""^\s*Q:\s*(.*)$""")
    // Matches markdown-export.ts's `${label}) ${text}` rendering per option, joined with " | ".
    private val OPTIONS_LINE = Regex("""^\s*Options:\s*(.+)$""")
    private val OPTION_ENTRY = Regex("""^([A-Za-z])\)\s*(.*)$""")
    private val ANSWER_LINE = Regex("""^\s*Your answer:\s*(.+?)\s*\|\s*Correct:\s*(.+?)\s*$""")
    private val EXPLANATION_LINE = Regex("""^\s*Explanation:\s*(.*)$""")
    // Extracts just the leading option letter from formatAnswer()'s "c) <text>" rendering.
    // Falls through to the raw (already-trimmed) value for the pre-existing bare-letter
    // fallback (numeric/subjective questions, which have no options to render).
    private val ANSWER_LETTER_PREFIX = Regex("""^([A-Za-z])\)""")

    fun parse(raw: String): ParsedTestReport {
        val lines = raw.lines()
        var title = "Untitled Test"
        var exam: String? = null
        var scoreObtained: Double? = null
        var scoreTotal: Double? = null
        var scorePercent: Double? = null
        var attempted: Int? = null
        var correct: Int? = null
        var wrong: Int? = null
        var skipped: Int? = null
        val questions = mutableListOf<ParsedQuestionResult>()

        var i = 0
        while (i < lines.size) {
            val line = lines[i]

            TITLE_LINE.find(line)?.let { m ->
                val full = m.groupValues[1].trim()
                val examMatch = TITLE_EXAM_SUFFIX.find(full)
                if (examMatch != null) {
                    exam = examMatch.groupValues[1].trim()
                    title = full.removeRange(examMatch.range).trim()
                } else {
                    title = full
                }
            }

            SCORE_LINE.find(line)?.let { m ->
                scoreObtained = m.groupValues[1].toDoubleOrNull()
                scoreTotal = m.groupValues[2].toDoubleOrNull()
                scorePercent = m.groupValues[3].toDoubleOrNull()
                attempted = m.groupValues[4].toIntOrNull()
                correct = m.groupValues[5].toIntOrNull()
                wrong = m.groupValues[6].toIntOrNull()
                skipped = m.groupValues[7].toIntOrNull()
            }

            val headerMatch = QUESTION_HEADER.find(line)
            if (headerMatch != null) {
                val topicRaw = headerMatch.groupValues[3].trim()
                val status = ParsedQuestionStatus.valueOf(headerMatch.groupValues[4])

                var j = i + 1
                var questionText: String? = null
                if (j < lines.size) {
                    QUESTION_TEXT_LINE.find(lines[j])?.let { qm ->
                        questionText = qm.groupValues[1].trim().takeIf { it.isNotBlank() }
                        j++
                    }
                }

                var options: Map<String, String>? = null
                if (j < lines.size) {
                    OPTIONS_LINE.find(lines[j])?.let { om ->
                        val parsed = om.groupValues[1].split(" | ")
                            .mapNotNull { entry -> OPTION_ENTRY.find(entry.trim())?.let { e -> e.groupValues[1] to e.groupValues[2] } }
                            .toMap()
                        options = parsed.takeIf { it.isNotEmpty() }
                        j++
                    }
                }

                var selectedOption: String? = null
                var correctOption: String? = null
                if (j < lines.size) {
                    ANSWER_LINE.find(lines[j])?.let { am ->
                        selectedOption = extractOptionLetter(am.groupValues[1].trim())
                        correctOption = extractOptionLetter(am.groupValues[2].trim())
                        j++
                    }
                }

                var explanation: String? = null
                if (j < lines.size) {
                    EXPLANATION_LINE.find(lines[j])?.let { em ->
                        explanation = em.groupValues[1].trim().takeIf { it.isNotBlank() }
                        j++
                    }
                }

                questions.add(
                    ParsedQuestionResult(
                        number = headerMatch.groupValues[1].toInt(),
                        chapter = headerMatch.groupValues[2].trim(),
                        topic = if (topicRaw == "—" || topicRaw.isBlank()) null else topicRaw,
                        status = status,
                        timeSeconds = headerMatch.groupValues[5].toIntOrNull(),
                        questionText = questionText,
                        selectedOption = selectedOption,
                        correctOption = correctOption,
                        options = options,
                        explanation = explanation
                    )
                )
                i = j
                continue
            }

            i++
        }

        return ParsedTestReport(
            title = title,
            exam = exam,
            scoreObtained = scoreObtained,
            scoreTotal = scoreTotal,
            scorePercent = scorePercent,
            attempted = attempted,
            correct = correct,
            wrong = wrong,
            skipped = skipped,
            questions = questions
        )
    }

    /**
     * "c) Only (A) is true but (R) is false" -> "c"; bare "c" (numeric/subjective
     * fallback, no options rendered) -> "c"; "—" (skipped) -> null.
     */
    private fun extractOptionLetter(rendered: String): String? {
        if (rendered.isEmpty() || rendered == "—") return null
        val m = ANSWER_LETTER_PREFIX.find(rendered)
        return if (m != null) m.groupValues[1] else rendered
    }
}
