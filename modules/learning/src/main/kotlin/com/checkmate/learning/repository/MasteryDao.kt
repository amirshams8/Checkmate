package com.checkmate.learning.repository

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.checkmate.learning.model.Concept
import com.checkmate.learning.model.ConceptMastery

/**
 * Upgrade Blueprint Phase 1.4/1.5. [ConceptDao] lives in this file alongside
 * [MasteryDao] rather than its own file — the blueprint's repository/ listing for
 * this phase names MasteryDao.kt and ErrorDao.kt only; Concept rows exist almost
 * entirely to be looked up by concept id when reading/writing mastery, so this
 * follows the same "don't invent an unlisted file" precedent QuestionAttemptDao
 * already set inside QuestionDao.kt.
 */
@Dao
interface ConceptDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(concept: Concept)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(concepts: List<Concept>)

    @Query("SELECT * FROM concepts WHERE id = :id")
    suspend fun getById(id: String): Concept?

    // Added for StudentModelBuilder: batches every concept a student has a
    // ConceptMastery row for into one query instead of one getById call per concept
    // — the "don't loop DAO.get() per item" fix called out during that PR's review.
    @Query("SELECT * FROM concepts WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<String>): List<Concept>

    @Query("SELECT * FROM concepts WHERE exam = :exam")
    suspend fun getByExam(exam: String): List<Concept>

    // BUGFIX (topic-"null" 422 loop, one-time data repair): companion to
    // QuestionDao.repairLiteralNullTopics — Concept rows already rebuilt from a poisoned
    // Question row (see that query's doc) carry the same literal "null" string in their
    // own `topic` column (Concept.topic is non-null, so it was never a real SQL NULL to
    // begin with; "unknown" is this column's actual "no topic" sentinel — see Concept.kt's
    // class doc). Falls back to `chapter` on repair, matching MasteryEngine.recomputeAll's
    // own `sampleQuestion?.topic ?: sampleQuestion?.chapter ?: "unknown"` fallback chain,
    // so a repaired row reads the same as if the corruption had never happened.
    @Query("UPDATE concepts SET topic = chapter WHERE topic = 'null'")
    suspend fun repairLiteralNullTopics(): Int
}

@Dao
interface MasteryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(mastery: ConceptMastery)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(masteries: List<ConceptMastery>)

    @Query("SELECT * FROM concept_mastery WHERE studentId = :studentId AND conceptId = :conceptId")
    suspend fun getByConcept(studentId: String, conceptId: String): ConceptMastery?

    @Query("SELECT * FROM concept_mastery WHERE studentId = :studentId")
    suspend fun getAll(studentId: String): List<ConceptMastery>

    // Retention decisioning (Phase 1.7) reads this to find concepts worth flagging
    // without pulling every row for students with a large mastery table.
    @Query("SELECT * FROM concept_mastery WHERE studentId = :studentId AND mastery < :threshold")
    suspend fun getBelowMastery(studentId: String, threshold: Double): List<ConceptMastery>
}
