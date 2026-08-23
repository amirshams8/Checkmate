package com.checkmate.learning.analytics

/**
 * Minimal per-question facts [SubjectScoreCalculator] needs — deliberately not
 * [com.checkmate.learning.testmate.ParsedQuestionResult] itself, so this stays
 * testable without a `:modules:learning.testmate` dependency and reusable for any
 * future question-result source (in-app practice sessions, not just Testmate
 * report imports).
 */
data class ScoredQuestionFact(
    val chapter: String,
    val topic: String?,
    val correct: Boolean,
    /** false = skipped/unattempted. A wrong, attempted answer is `attempted=true,
     *  correct=false` — the distinction matters because only an attempted wrong
     *  answer costs negative marks; a skip costs nothing. */
    val attempted: Boolean
)

/**
 * One subject's literal score for one test — NOT the PYQ-weightage "marks at
 * stake" [ConceptWeightage]/[PerformanceAnalyzer] compute; this is the actual
 * obtained/total tally a student would recognize from the real exam scoring
 * pattern, per [ConceptWeightage.marksPerQuestion]/[ConceptWeightage.negativeMarksPerWrong].
 * `questionsCount`/`marksTotal` are THIS TEST's own subject-question count, not
 * the exam's official full-paper count — a shorter practice test's "Physics"
 * section naturally totals less than a full mock's would.
 */
data class SubjectScore(
    val subject: String,
    val questionsCount: Int,
    val correct: Int,
    val wrong: Int,
    val skipped: Int,
    val marksObtained: Int,
    val marksTotal: Int
)

/**
 * Test-report wiring gap fill: [TestResultNormalizer] parses a report's questions
 * with chapter/topic per row but no subject (Testmate's report.md carries none —
 * see [com.checkmate.learning.model.Question]'s own doc), so nothing before this
 * could answer "how did the student do in Physics specifically" for one test. This
 * resolves each question's subject the same way [PerformanceAnalyzer] resolves a
 * concept's subject — via [ConceptWeightage]'s fuzzy PYQWeightage lookup — then
 * tallies the real scoring pattern per subject.
 *
 * Pure and DB-free by design, same as [PerformanceAnalyzer.classifyTrend] — takes
 * plain facts in, returns plain data out, callable from
 * [com.checkmate.learning.testmate.TestResultNormalizer.normalizeAndPersist] at
 * import time without a Room round-trip.
 */
object SubjectScoreCalculator {

    fun compute(examType: String, questions: List<ScoredQuestionFact>): List<SubjectScore> {
        val marksPerQuestion = ConceptWeightage.marksPerQuestion(examType)
        val negativeMarks = ConceptWeightage.negativeMarksPerWrong(examType)

        return questions
            .groupBy { q ->
                val topicKey = q.topic ?: q.chapter
                ConceptWeightage.resolveWeightage(examType, null, q.chapter, topicKey).subjectResolved
            }
            .filterKeys { it != null }
            .map { (subject, group) ->
                val correct = group.count { it.correct }
                val wrong = group.count { it.attempted && !it.correct }
                val skipped = group.count { !it.attempted }
                SubjectScore(
                    subject = subject!!,
                    questionsCount = group.size,
                    correct = correct,
                    wrong = wrong,
                    skipped = skipped,
                    marksObtained = correct * marksPerQuestion - wrong * negativeMarks,
                    marksTotal = group.size * marksPerQuestion
                )
            }
            .sortedByDescending { it.questionsCount }
    }
}
