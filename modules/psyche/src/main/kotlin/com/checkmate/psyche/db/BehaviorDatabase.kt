package com.checkmate.psyche.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Upgrade Blueprint Phase 0 item #3 ("Confirm Room is single source of truth").
 *
 * BehaviorLedger previously persisted its entire event log as one JSON-blob
 * string inside CheckmatePrefs (plain SharedPreferences, key "behavior_events"),
 * rewriting the whole blob on every single record() call and offering no query
 * surface beyond "deserialize everything, filter in Kotlin." That's exactly the
 * anti-pattern this item calls out — state downstream decisions (streaks, skip
 * rate, pattern detection) depend on wasn't living in the durable, queryable
 * store the rest of the intervention pipeline already uses
 * (:modules:planner's InterventionDatabase). This mirrors that same
 * Room-singleton shape, scoped to behavior events specifically, since psyche
 * can't depend on planner's InterventionDatabase without pulling in the whole
 * intervention-pipeline dependency graph for an unrelated table.
 *
 * Separate .db file from InterventionDatabase's "checkmate_intervention.db" —
 * deliberately not merged into one giant database, since behavior events and
 * intervention transactions have unrelated lifecycles and no foreign-key
 * relationship between them.
 *
 * See BehaviorLedger.migrateLegacyPrefsIfEmpty() for the one-time import of any
 * pre-existing CheckmatePrefs blob so students upgrading don't lose history.
 */
@Database(entities = [BehaviorEventEntity::class], version = 1, exportSchema = false)
abstract class BehaviorDatabase : RoomDatabase() {

    abstract fun behaviorEventDao(): BehaviorEventDao

    companion object {
        @Volatile
        private var INSTANCE: BehaviorDatabase? = null

        fun getInstance(context: Context): BehaviorDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    BehaviorDatabase::class.java,
                    "checkmate_behavior.db"
                )
                    // Version 1, no upgrade path needed yet — same
                    // downgrade-only-destructive posture as InterventionDatabase
                    // (see its own doc on why a genuine forward migration is kept
                    // strict instead of falling back to a silent wipe).
                    .fallbackToDestructiveMigrationOnDowngrade()
                    .build().also { INSTANCE = it }
            }
    }
}
