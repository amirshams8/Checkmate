package com.checkmate.psyche.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Upgrade Blueprint Phase 0 item #3 ("Confirm Room is single source of truth").
 * Room-backed mirror of BehaviorLedger.TaskEvent — see BehaviorDatabase's doc for
 * why this replaces the old single-blob CheckmatePrefs storage. Field-for-field
 * match with TaskEvent (BehaviorLedger.kt) so the entity<->model mapping there is
 * a straight copy, not a re-derivation.
 */
@Entity(
    tableName = "behavior_events",
    indices = [Index(value = ["timestamp"]), Index(value = ["subject", "taskType"])]
)
data class BehaviorEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val subject: String,
    val topic: String,
    val state: String,          // DONE / SKIPPED — mirrors TaskState.name
    val timestamp: Long,
    val focusMinutes: Int = 0,
    val checksPassed: Int = 0,
    val checksMissed: Int = 0,
    val taskType: String = "OTHER",
    val distractionApp: String? = null
)
