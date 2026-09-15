package com.checkmate.learning.repository

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.checkmate.learning.model.Question
import com.checkmate.learning.model.QuestionAttempt
import com.checkmate.learning.model.QuestionSource

/**
 * Blueprint §1.2's repository/ listing names only QuestionDao.kt (no separate
 * attempt-DAO file) — [QuestionAttemptDao] lives in this same file rather than
 * inventing an unlisted file, since QuestionAttempt has no meaning without a
 * Question to attach to.
 */
@Dao
interface QuestionDao {

    // REPLACE, not IGNORE: re-importing the same report re-sends the same
    // deterministic question id (see TestResultNormalizer) with a corrected
    // questionText/options if the source data changed — a stale row should
    // never silently win over a fresher import.
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(question: Question)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(questions: List<Question>)

    @Query("SELECT * FROM questions WHERE id = :id")
    suspend fun getById(id: String): Question?

    @Query("SELECT * FROM questions WHERE chapter = :chapter")
    suspend fun getByChapter(chapter: String): List<Question>

    // Added for MasteryEngine/ErrorEngine (Upgrade Blueprint Phase 1.5/1.6), which
    // need every Question row to resolve each QuestionAttempt to its concept.
    @Query("SELECT * FROM questions")
    suspend fun getAll(): List<Question>

    @Query("SELECT COUNT(*) FROM questions")
    suspend fun count(): Int

    // BUGFIX (topic-"null" 422 loop, one-time data repair): rows written before the
    // TestmateApi.parseResult bare-optString fix have `topic` set to the literal
    // 4-character string "null" instead of a real SQL NULL, whenever the source JSON's
    // "topic" field was itself JSON null. MasteryEngine.recomputeAll re-derives every
    // Concept's topic straight from a sample Question row (`sampleQuestion?.topic ?:
    // ...`), so a poisoned row here keeps re-poisoning Concept/GapTaskLedger on every
    // future mastery recompute even after the parsing fix ships — this repairs the
    // already-written rows so that stops. Returns the number of rows fixed, purely so
    // the one-time caller (see GapTaskManager.repairLegacyNullTopicsIfNeeded) can log
    // whether it actually did anything.
    @Query("UPDATE questions SET topic = NULL WHERE topic = 'null'")
    suspend fun repairLiteralNullTopics(): Int

    // NEW (external-report pathway): questions from a report Checkmate parsed
    // locally but that was never actually taken on Testmate (tagged with a
    // non-"testmate_report" source at import — see TestResultNormalizer's
    // [source] param) have no `responses` history on Testmate's server to look
    // up by chapter. This is the local equivalent of that lookup: every wrong or
    // skipped question this student has for the chapter/topic, scoped to
    // [source] so a genuinely Testmate-taken attempt for the same chapter never
    // gets mixed in here (that already has its own correct pathway). `topic`
    // matching mirrors the server route's own "only filter by topic when one was
    // recorded" behavior — see GapTaskManager.createTargetedTestIfNeeded's
    // topicForApi handling for the same null-vs-blank reasoning.
    @Query(
        """
        SELECT q.* FROM questions q
        INNER JOIN question_attempts qa ON qa.questionId = q.id
        WHERE q.chapter = :chapter
          AND (:topic IS NULL OR q.topic = :topic)
          AND q.source = :source
          AND qa.studentId = :studentId
          AND qa.correct = 0
        """
    )
    suspend fun getExternalWrongOrSkipped(
        chapter: String,
        topic: String?,
        source: String,
        studentId: String
    ): List<Question>

    // NEW — Q-bank MVP (see QBankSelector). Hardcodes source = QuestionSource.QBANK
    // rather than taking a `source: String` parameter — the whole point of a
    // semantically named DAO method here is that a caller structurally cannot
    // accidentally pass "external_report" (or any other source) and end up
    // re-treading [getExternalWrongOrSkipped]'s territory. `topic` is intentionally
    // NOT filtered here (unlike getExternalWrongOrSkipped) — Q-bank selection is
    // chapter-scoped per DailyQuestionTarget.chapterAllocations' own granularity;
    // per-question topic/concept priority is ranked afterward in Kotlin by
    // QBankSelector, not filtered in SQL.
    @Query(
        "SELECT * FROM questions WHERE source = '${QuestionSource.QBANK}' AND chapter = :chapter " +
            "ORDER BY id LIMIT :limit"
    )
    suspend fun qbankPoolByChapter(chapter: String, limit: Int): List<Question>

    // NEW — Q-bank MVP. How many distinct qbank questions this chapter has already
    // had a *correct* attempt on — QuestionTargetEngine subtracts this from
    // TestPlan.chapterTargets[chapter] to get the day's remaining coverage need.
    // DISTINCT on q.id, not COUNT(qa.*) — a question re-attempted correctly twice
    // must still only count once toward "covered," same reasoning as
    // getExternalWrongOrSkipped's own "don't double count a re-attempt" precedent.
    @Query(
        """
        SELECT COUNT(DISTINCT q.id) FROM questions q
        INNER JOIN question_attempts qa ON qa.questionId = q.id
        WHERE q.source = '${QuestionSource.QBANK}'
          AND q.chapter = :chapter
          AND qa.studentId = :studentId
          AND qa.correct = 1
        """
    )
    suspend fun countQbankCorrectByChapter(chapter: String, studentId: String): Int
}

@Dao
interface QuestionAttemptDao {

    @Insert
    suspend fun insert(attempt: QuestionAttempt)

    @Insert
    suspend fun insertAll(attempts: List<QuestionAttempt>)

    @Query("SELECT * FROM question_attempts WHERE questionId = :questionId ORDER BY timestamp ASC")
    suspend fun getByQuestion(questionId: String): List<QuestionAttempt>

    @Query("SELECT * FROM question_attempts WHERE studentId = :studentId ORDER BY timestamp ASC")
    suspend fun getAll(studentId: String): List<QuestionAttempt>

    // Added for ErrorEngine (Upgrade Blueprint Phase 1.6).
    @Query("SELECT * FROM question_attempts WHERE studentId = :studentId AND correct = 0 ORDER BY timestamp ASC")
    suspend fun getWrongAttempts(studentId: String): List<QuestionAttempt>

    @Query("SELECT COUNT(*) FROM question_attempts WHERE studentId = :studentId")
    suspend fun count(studentId: String): Int

    // NEW — Q-bank MVP. QBankSelector's "not recently attempted" ranking signal (see
    // chat history's priority list). studentId-scoped, not question-source-scoped: a
    // question recently seen via ANY pathway (testmate import, external report,
    // qbank) shouldn't be re-served as "fresh" qbank practice today.
    @Query("SELECT questionId FROM question_attempts WHERE studentId = :studentId AND timestamp >= :sinceMillis")
    suspend fun getRecentQuestionIds(studentId: String, sinceMillis: Long): List<String>
}
