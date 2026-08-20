package com.checkmate.psyche.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface BehaviorEventDao {

    @Insert
    suspend fun insert(event: BehaviorEventEntity)

    // Oldest-first, matching the ordering contract the old CheckmatePrefs JSON-blob
    // list had (BehaviorLedger appended to the end) — callers that take the last N
    // for "most recent" still work unchanged against this ordering.
    @Query("SELECT * FROM behavior_events ORDER BY timestamp ASC")
    suspend fun getAll(): List<BehaviorEventEntity>

    @Query("SELECT COUNT(*) FROM behavior_events")
    suspend fun count(): Int

    // Same 200-event cap the old blob enforced by construction (list.takeLast(200)
    // before every write) — enforced here instead as an explicit trim after insert,
    // since Room has no "keep only the newest N rows" primitive of its own.
    @Query(
        """
        DELETE FROM behavior_events WHERE id NOT IN (
            SELECT id FROM behavior_events ORDER BY timestamp DESC LIMIT :keep
        )
        """
    )
    suspend fun trimToNewest(keep: Int)
}
