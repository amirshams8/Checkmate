package com.checkmate.learning.repository

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.checkmate.learning.model.LearningEvent

@Dao
interface LearningEventDao {

    @Insert
    suspend fun insert(event: LearningEvent)

    @Insert
    suspend fun insertAll(events: List<LearningEvent>)

    @Query("SELECT * FROM learning_events WHERE studentId = :studentId ORDER BY timestamp ASC")
    suspend fun getAll(studentId: String): List<LearningEvent>

    @Query(
        "SELECT * FROM learning_events WHERE studentId = :studentId AND subjectId = :subjectId " +
            "ORDER BY timestamp ASC"
    )
    suspend fun getBySubject(studentId: String, subjectId: String): List<LearningEvent>

    @Query("SELECT * FROM learning_events WHERE questionId = :questionId ORDER BY timestamp ASC")
    suspend fun getByQuestion(questionId: String): List<LearningEvent>

    @Query("SELECT COUNT(*) FROM learning_events WHERE studentId = :studentId")
    suspend fun count(studentId: String): Int
}
