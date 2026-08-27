package com.checkmate.service

import android.content.Context
import android.util.Log
import com.checkmate.learning.engine.ErrorEngine
import com.checkmate.learning.engine.MasteryEngine
import com.checkmate.learning.model.LearningEvent
import com.checkmate.learning.model.LearningEventType
import com.checkmate.learning.model.LearningIds
import com.checkmate.learning.model.Question
import com.checkmate.learning.model.QuestionAttempt
import com.checkmate.learning.repository.LearningDatabase
import com.checkmate.testmate.TestmateResult

/**
 * P0b evidence loop — the "return arrow" the roadmap flagged as the biggest remaining
 * gap: a [com.checkmate.planner.model.StudyTask] reaching DONE is behavior evidence only
 * (the student pressed the button). This is what turns a Testmate targeted-repair test
 * the student actually took into real LEARNING evidence — the same
 * [Question]/[QuestionAttempt]/[LearningEvent] shape
 * [com.checkmate.learning.testmate.TestResultNormalizer] already produces for a full mock
 * import, reusing the identical downstream pipeline (ErrorEngine -> MasteryEngine) rather
 * than inventing a second one.
 *
 * Deliberately a separate object from TestResultNormalizer, not a shared code path — that
 * one parses report.md TEXT; this one consumes an already-structured [TestmateResult]
 * fetched via [com.checkmate.testmate.TestmateApi.fetchResult]. Lives in `app` (not
 * `modules/learning`) because it needs [TestmateResult] from `:modules:testmate` alongside
 * the learning-module pieces — same reasoning as [com.checkmate.testmate.TestmateApi]
 * itself living where it does.
 *
 * IMPORTANT (per the P0b design doc): this object NEVER moves mastery on its own — it only
 * writes attempts/events; [MasteryEngine.recomputeAll] is what actually changes
 * ConceptMastery, and only in proportion to what was answered correctly/incorrectly. A
 * `StudyTask` reaching DONE with zero evidence imported must never, on its own, look like
 * a mastery gain — that's exactly the anti-pattern the doc called out.
 */
object TargetedTestEvidenceImporter {

    private const val TAG = "TargetedTestEvidence"

    data class ImportResult(
        val alreadyImported: Boolean,
        val questionsWritten: Int,
        val attemptsWritten: Int,
        val eventsWritten: Int
    )

    /**
     * [exam]/[chapter]/[topic] should be the SAME values
     * [com.checkmate.planner.intervention.GapTaskLedger] recorded when the gap-task was
     * served (`activeChapter()`/`activeTopic()`) — [MasteryEngine] regroups attempts by
     * [com.checkmate.learning.graph.KnowledgeGraph.conceptId] computed from a Question's
     * OWN exam/chapter/topic fields, not from anything passed to this function directly,
     * so a mismatch here would silently recompute mastery for the wrong concept bucket.
     * Falls back to each [TestmateBreakdownRow]'s own chapter/topic first, since that's
     * Testmate's authoritative tag for that specific question — [chapter]/[topic] here are
     * only the fallback for a row that came back without one.
     */
    suspend fun import(
        context: Context,
        sessionId: String,
        exam: String?,
        chapter: String?,
        topic: String?,
        result: TestmateResult,
        studentId: String = LearningIds.LOCAL_STUDENT_ID,
        source: String = "testmate_targeted"
    ): ImportResult {
        if (result.breakdown.isEmpty()) {
            Log.w(TAG, "import called with an empty breakdown (session=$sessionId) — nothing to write")
            return ImportResult(alreadyImported = false, questionsWritten = 0, attemptsWritten = 0, eventsWritten = 0)
        }

        val db = LearningDatabase.getInstance(context)

        // Idempotency guard, same reasoning/shape as TestResultNormalizer's: this session's
        // question ids are deterministic from (sessionId, question number), so checking the
        // first one tells us whether this exact session was already imported. Belt-and-
        // suspenders alongside GapTaskLedger.isActiveEvidenceImported(), which is the guard
        // that actually stops GapTaskManager from calling this repeatedly.
        val firstQuestionId = deterministicQuestionId(sessionId, result.breakdown.first().questionNumber)
        if (db.questionDao().getById(firstQuestionId) != null) {
            Log.w(TAG, "Targeted test session $sessionId already imported — skipping to avoid duplicate attempts.")
            return ImportResult(alreadyImported = true, questionsWritten = 0, attemptsWritten = 0, eventsWritten = 0)
        }

        val questions = mutableListOf<Question>()
        val attempts = mutableListOf<QuestionAttempt>()
        val events = mutableListOf<LearningEvent>()
        val now = System.currentTimeMillis()

        // One summary event for the whole targeted test, same role MOCK_COMPLETED plays for
        // a full mock import — REVISION_COMPLETED rather than MOCK_COMPLETED since this is a
        // small student-specific repair set, not a full paper.
        events.add(
            LearningEvent(
                studentId = studentId,
                eventType = LearningEventType.REVISION_COMPLETED,
                timestamp = now,
                chapterId = chapter,
                topicId = topic,
                source = source,
                accuracy = if (result.attemptedCount > 0) result.correctCount.toDouble() / result.attemptedCount else null
            )
        )

        for (row in result.breakdown) {
            val questionId = deterministicQuestionId(sessionId, row.questionNumber)

            questions.add(
                Question(
                    id = questionId,
                    source = source,
                    exam = exam,
                    chapter = row.chapter ?: chapter,
                    topic = row.topic ?: topic,
                    correctOption = row.correctAnswer,
                    questionText = row.questionText,
                    explanation = row.explanation,
                    options = row.options
                )
            )

            // Same reasoning as TestResultNormalizer: a genuinely skipped question
            // (selectedAnswer == null) gets no QuestionAttempt row — only Question +
            // LearningEvent — so ErrorEngine's wrong-attempt query never conflates a skip
            // with an actual wrong answer.
            if (row.selectedAnswer != null) {
                attempts.add(
                    QuestionAttempt(
                        studentId = studentId,
                        questionId = questionId,
                        timestamp = now,
                        selectedOption = row.selectedAnswer,
                        correct = row.isCorrect == true,
                        timeTakenSeconds = row.timeSpentSeconds
                    )
                )
            }

            val eventType = when {
                row.selectedAnswer == null -> LearningEventType.QUESTION_SKIPPED
                row.isCorrect == true -> LearningEventType.QUESTION_CORRECT
                else -> LearningEventType.QUESTION_WRONG
            }
            events.add(
                LearningEvent(
                    studentId = studentId,
                    eventType = eventType,
                    timestamp = now,
                    chapterId = row.chapter ?: chapter,
                    topicId = row.topic ?: topic,
                    questionId = questionId,
                    source = source,
                    duration = row.timeSpentSeconds
                )
            )
        }

        db.questionDao().upsertAll(questions)
        db.questionAttemptDao().insertAll(attempts)
        db.learningEventDao().insertAll(events)

        val errorRecords = ErrorEngine.classifyAllUnclassified(context, studentId)
        val recomputedMastery = MasteryEngine.recomputeAll(context, studentId)

        Log.d(
            TAG,
            "Imported targeted test session $sessionId: ${questions.size} questions, " +
                "${attempts.size} attempts, ${events.size} events, ${errorRecords.size} error(s) " +
                "classified, ${recomputedMastery.size} concept(s) recomputed"
        )

        return ImportResult(
            alreadyImported = false,
            questionsWritten = questions.size,
            attemptsWritten = attempts.size,
            eventsWritten = events.size
        )
    }

    /** `testmate_targeted-{sessionId}-q{n}` — namespaced separately from
     *  TestResultNormalizer's `{testId}-q{n}` scheme so a targeted-test question id can
     *  never collide with a full-mock-import question id even if both happened to touch
     *  the same underlying Testmate question. */
    private fun deterministicQuestionId(sessionId: String, questionNumber: Int): String =
        "testmate_targeted-$sessionId-q$questionNumber"
}
