package com.checkmate.learning.testmate

import android.content.Context
import android.util.Log
import com.checkmate.learning.model.LearningEvent
import com.checkmate.learning.model.LearningEventType
import com.checkmate.learning.model.LearningIds
import com.checkmate.learning.model.Question
import com.checkmate.learning.model.QuestionAttempt
import com.checkmate.learning.repository.LearningDatabase
import java.security.MessageDigest

/**
 * Upgrade Blueprint Phase 1.3: "Testmate → TestResultNormalizer → LearningEvents
 * → MasteryEngine → StudentModel → Planner." This is the normalizer — MasteryEngine
 * and the rest of the fold are Phase 1.5+, not built yet (see Checkmate_Upgrade_Blueprint
 * §Phase 1.5-1.7 and the suggested sequencing at the bottom of that doc).
 *
 * A mock isn't "done" at "618 marks" — it's done when Checkmate knows *why* 618.
 * This is what turns a report.md dump into that "why": one Question row per
 * question, one QuestionAttempt per attempt, and a LearningEvent per question
 * outcome (QUESTION_CORRECT/WRONG/SKIPPED) plus one MOCK_COMPLETED summary event.
 *
 * KNOWN LIMITATION: report.md carries no "test taken at" timestamp, so every
 * attempt/event from one import shares [testTimestamp], which defaults to import
 * time (now), not actual test time. Pass [testTimestamp] explicitly once there's
 * a real source for it (e.g. a file-modified date or a user-entered date) — not
 * guessed here.
 */
object TestResultNormalizer {

    private const val TAG = "TestResultNormalizer"

    data class NormalizeResult(
        val alreadyImported: Boolean,
        val questionsWritten: Int,
        val attemptsWritten: Int,
        val eventsWritten: Int,
        val warnings: List<String>
    )

    suspend fun normalizeAndPersist(
        context: Context,
        reportText: String,
        studentId: String = LearningIds.LOCAL_STUDENT_ID,
        source: String = "testmate_report",
        testTimestamp: Long = System.currentTimeMillis()
    ): NormalizeResult {
        val report = TestReportParser.parse(reportText)
        val warnings = mutableListOf<String>()

        if (report.questions.isEmpty()) {
            warnings.add(
                "No question rows parsed — report format may not match " +
                    "TestReportParser's expected shape."
            )
        }
        report.attempted?.let { expected ->
            val actualAttempted = report.questions.count { it.status != ParsedQuestionStatus.SKIPPED }
            if (actualAttempted != expected) {
                warnings.add(
                    "Header says $expected attempted, but $actualAttempted question rows " +
                        "were non-skipped after parsing."
                )
            }
        }

        val db = LearningDatabase.getInstance(context)
        val testId = deterministicTestId(report)

        // Idempotency guard: the same report re-imported (e.g. user re-shares the same
        // file) must not double-count attempts/events. Question ids are deterministic
        // from (testId, question number), so checking the first one tells us whether
        // this exact test was already normalized.
        val firstQuestionId = report.questions.firstOrNull()?.let { deterministicQuestionId(testId, it.number) }
        if (firstQuestionId != null && db.questionDao().getById(firstQuestionId) != null) {
            Log.w(TAG, "Report '${report.title}' ($testId) already imported — skipping to avoid duplicate attempts.")
            return NormalizeResult(
                alreadyImported = true,
                questionsWritten = 0,
                attemptsWritten = 0,
                eventsWritten = 0,
                warnings = warnings
            )
        }

        val questions = mutableListOf<Question>()
        val attempts = mutableListOf<QuestionAttempt>()
        val events = mutableListOf<LearningEvent>()

        events.add(
            LearningEvent(
                studentId = studentId,
                eventType = LearningEventType.MOCK_COMPLETED,
                timestamp = testTimestamp,
                source = source,
                accuracy = report.scorePercent?.let { it / 100.0 }
            )
        )

        for (q in report.questions) {
            val questionId = deterministicQuestionId(testId, q.number)

            questions.add(
                Question(
                    id = questionId,
                    source = source,
                    exam = report.exam,
                    chapter = q.chapter,
                    topic = q.topic,
                    correctOption = q.correctOption,
                    questionText = q.questionText
                )
            )

            attempts.add(
                QuestionAttempt(
                    studentId = studentId,
                    questionId = questionId,
                    timestamp = testTimestamp,
                    selectedOption = q.selectedOption,
                    correct = q.status == ParsedQuestionStatus.CORRECT,
                    timeTakenSeconds = q.timeSeconds
                )
            )

            val eventType = when (q.status) {
                ParsedQuestionStatus.CORRECT -> LearningEventType.QUESTION_CORRECT
                ParsedQuestionStatus.WRONG -> LearningEventType.QUESTION_WRONG
                ParsedQuestionStatus.SKIPPED -> LearningEventType.QUESTION_SKIPPED
            }
            events.add(
                LearningEvent(
                    studentId = studentId,
                    eventType = eventType,
                    timestamp = testTimestamp,
                    chapterId = q.chapter,
                    topicId = q.topic,
                    questionId = questionId,
                    source = source,
                    duration = q.timeSeconds
                )
            )
        }

        db.questionDao().upsertAll(questions)
        db.questionAttemptDao().insertAll(attempts)
        db.learningEventDao().insertAll(events)

        warnings.forEach { Log.w(TAG, it) }
        Log.d(
            TAG,
            "Normalized report '${report.title}': ${questions.size} questions, " +
                "${attempts.size} attempts, ${events.size} events, ${warnings.size} warning(s)"
        )

        return NormalizeResult(
            alreadyImported = false,
            questionsWritten = questions.size,
            attemptsWritten = attempts.size,
            eventsWritten = events.size,
            warnings = warnings
        )
    }

    /**
     * Stable id derived from the report's own content (exam + title), not a random
     * UUID — the same report re-imported must resolve to the same testId so the
     * idempotency guard above actually works.
     */
    private fun deterministicTestId(report: ParsedTestReport): String =
        sha256Hex("${report.exam ?: ""}|${report.title}").take(16)

    private fun deterministicQuestionId(testId: String, questionNumber: Int): String =
        "$testId-q$questionNumber"

    private fun sha256Hex(input: String): String =
        MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
