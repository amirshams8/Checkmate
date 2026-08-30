package com.checkmate.learning.repository

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.checkmate.learning.model.Question
import com.checkmate.learning.model.QuestionAttempt

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
}
