package com.checkmate.learning.testmate

/**
 * Upgrade Blueprint Phase 1.3 ("Testmate → data source, not just a results screen").
 *
 * Parses the plain-text report Testmate produces (see e.g.
 * `ft-01b-full-test-neet-2027--report.md`) — a *different* surface from
 * :modules:testmate's TestmateApi/TestmateModels, which hit Testmate's JSON REST
 * endpoints (`/api/sessions/:id/results`) and only carry chapter/topic-level
 * aggregates, no per-question detail. This report format is strictly richer —
 * question text, the student's exact selected option, the correct option, and
 * per-question time — which is what actually lets [TestResultNormalizer] produce
 * real QuestionAttempt rows instead of only aggregate stats.
 *
 * Deliberately format-matched, not "smart"/fuzzy: this parses exactly the shape
 * Testmate emits today (verified against the sample report, not guessed). If
 * Testmate's report format changes, this should fail loudly (missing score/zero
 * questions) rather than silently misparse — see [TestResultNormalizer]'s
 * validation before it writes anything.
 *
 * Expected shape per question (3 lines):
 * ```
 * Q1 [Units & Measurements/—] — CORRECT — time 117s
 *   Q: <question text>
 *   Your answer: c | Correct: c
 * ```
 * `topic` of "—" means "not tracked for this question" and is normalized to null
 * (matches the report's own "Not enough attempted questions to break this down"
 * caveat under "## Weak topics" — topic-level data isn't reliably present).
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
    val correctOption: String?
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
    private val ANSWER_LINE = Regex("""^\s*Your answer:\s*(.+?)\s*\|\s*Correct:\s*(.+?)\s*$""")

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

                var j = i + 1
                var questionText: String? = null
                if (j < lines.size) {
                    QUESTION_TEXT_LINE.find(lines[j])?.let { qm ->
                        questionText = qm.groupValues[1].trim().takeIf { it.isNotBlank() }
                        j++
                    }
                }

                var selectedOption: String? = null
                var correctOption: String? = null
                if (j < lines.size) {
                    ANSWER_LINE.find(lines[j])?.let { am ->
                        val selRaw = am.groupValues[1].trim()
                        selectedOption = if (selRaw == "—" || selRaw.isBlank()) null else selRaw
                        correctOption = am.groupValues[2].trim().takeIf { it.isNotBlank() }
                        j++
                    }
                }

                questions.add(
                    ParsedQuestionResult(
                        number = headerMatch.groupValues[1].toInt(),
                        chapter = headerMatch.groupValues[2].trim(),
                        topic = if (topicRaw == "—" || topicRaw.isBlank()) null else topicRaw,
                        status = ParsedQuestionStatus.valueOf(headerMatch.groupValues[4]),
                        timeSeconds = headerMatch.groupValues[5].toIntOrNull(),
                        questionText = questionText,
                        selectedOption = selectedOption,
                        correctOption = correctOption
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
}
