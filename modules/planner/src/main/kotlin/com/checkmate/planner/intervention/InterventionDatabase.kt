package com.checkmate.planner.intervention

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Proactive Execution Engine — Step 3 (Blueprint Part One, §4), amended in Step 12 (§22,
 * §25 principle 6) to add the Outcome Ledger table.
 *
 * Standalone singleton, same shape as a typical Room setup — deliberately not wired into
 * CheckmateApp.onCreate() yet. Nothing calls InterventionDatabase.getInstance() until the
 * Task Escrow (step 4) and ActionExecutor (step 5) exist to actually create/update rows,
 * so wiring it into app startup now would be speculative.
 *
 * Step 12: version bumped 1 -> 2 to add [OutcomeLedgerEntry]. Originally shipped with
 * `fallbackToDestructiveMigration()` as a deliberate stopgap (safe pre-pilot, since no
 * student data existed anywhere yet — see that method's own since-removed doc). Now
 * replaced with a real [MIGRATION_1_2] before any pilot student's device can carry
 * `intervention_transactions` rows a destructive wipe would actually lose. The migration
 * only ever needs to ADD `outcome_ledger_entries` — v1's `intervention_transactions` table
 * is untouched by this bump, so there's nothing to alter or backfill.
 *
 * `fallbackToDestructiveMigrationOnDowngrade()` is kept (not the blanket
 * `fallbackToDestructiveMigration()`) — a *downgrade* only happens by someone reinstalling
 * an older build over a newer one (e.g. switching branches on a dev device), which isn't a
 * scenario a real migration path can meaningfully support, whereas every forward *upgrade*
 * now has to go through [MIGRATION_1_2] or the app won't compile against a mismatched
 * version — Room throws IllegalStateException on an unhandled upgrade rather than silently
 * wiping data, which is exactly the safety property destructive-fallback gave up.
 */
@Database(
    entities = [InterventionTransaction::class, OutcomeLedgerEntry::class],
    version = 2,
    exportSchema = false
)
abstract class InterventionDatabase : RoomDatabase() {

    abstract fun interventionTransactionDao(): InterventionTransactionDao
    abstract fun outcomeLedgerDao(): OutcomeLedgerDao

    companion object {
        @Volatile
        private var INSTANCE: InterventionDatabase? = null

        /**
         * Column types/converters mirror [OutcomeLedgerEntry] and [OutcomeLedgerConverters]/
         * [InterventionConverters] exactly: `triggerType`/`terminalState`/`provenance` are
         * all stored as the enum's `.name` (TEXT), same convention [InterventionTransaction]'s
         * own `intervention_transactions` table already uses for `currentState`/`triggerType`
         * — nothing here introduces a new storage convention, just applies the existing one
         * to a second table.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `outcome_ledger_entries` (
                        `transactionId` TEXT NOT NULL,
                        `taskId` TEXT NOT NULL,
                        `triggerType` TEXT NOT NULL,
                        `terminalState` TEXT NOT NULL,
                        `provenance` TEXT NOT NULL,
                        `attemptCount` INTEGER NOT NULL,
                        `resolvedAt` INTEGER NOT NULL,
                        `outcome` TEXT,
                        `failureReason` TEXT,
                        PRIMARY KEY(`transactionId`)
                    )
                    """.trimIndent()
                )
            }
        }

        fun getInstance(context: Context): InterventionDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    InterventionDatabase::class.java,
                    "checkmate_intervention.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .fallbackToDestructiveMigrationOnDowngrade()
                    .build().also { INSTANCE = it }
            }
    }
}
