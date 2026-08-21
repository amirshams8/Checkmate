package com.checkmate.learning.repository

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.checkmate.learning.model.ErrorPattern
import com.checkmate.learning.model.ErrorRecord
import com.checkmate.learning.model.ErrorType

@Dao
interface ErrorRecordDao {

    @Insert
    suspend fun insert(record: ErrorRecord)

    // Idempotency check for ErrorEngine.classifyAllUnclassified — mirrors
    // TestResultNormalizer's "already imported" guard pattern.
    @Query("SELECT * FROM error_records WHERE attemptId = :attemptId LIMIT 1")
    suspend fun getByAttempt(attemptId: String): ErrorRecord?

    @Query("SELECT * FROM error_records WHERE studentId = :studentId ORDER BY timestamp ASC")
    suspend fun getAll(studentId: String): List<ErrorRecord>

    @Query("SELECT * FROM error_records WHERE conceptId = :conceptId ORDER BY timestamp ASC")
    suspend fun getByConcept(conceptId: String): List<ErrorRecord>
}

@Dao
interface ErrorPatternDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(pattern: ErrorPattern)

    @Query(
        "SELECT * FROM error_patterns WHERE studentId = :studentId AND conceptId = :conceptId " +
            "AND errorType = :errorType LIMIT 1"
    )
    suspend fun get(studentId: String, conceptId: String, errorType: ErrorType): ErrorPattern?

    @Query("SELECT * FROM error_patterns WHERE studentId = :studentId ORDER BY occurrences DESC")
    suspend fun getAll(studentId: String): List<ErrorPattern>

    // "You've made this mistake 7 times" per the blueprint — unresolved patterns
    // with the highest occurrence count first.
    @Query(
        "SELECT * FROM error_patterns WHERE studentId = :studentId AND resolved = 0 " +
            "ORDER BY occurrences DESC"
    )
    suspend fun getUnresolved(studentId: String): List<ErrorPattern>
}
