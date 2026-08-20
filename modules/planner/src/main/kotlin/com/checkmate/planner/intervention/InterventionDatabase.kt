package com.checkmate.planner.intervention

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Proactive Execution Engine — Step 3 (Blueprint Part One, §4), amended in Step 12 (§22,
 * §25 principle 6) to add the Outcome Ledger table.
 *
 * Standalone singleton, same shape as a typical Room setup — deliberately not wired into
 * CheckmateApp.onCreate() yet. Nothing calls InterventionDatabase.getInstance() until the
 * Task Escrow (step 4) and ActionExecutor (step 5) exist to actually create/update rows,
 * so wiring it into app startup now would be speculative.
 *
 * Step 12: version bumped 1 -> 2 to add [OutcomeLedgerEntry]. No real migration is written
 * — [fallbackToDestructiveMigration] is safe here specifically because this database is
 * still pre-pilot (Blueprint §26's build sequence puts "Real-student pilot" at step 13,
 * *after* this step), so there is no student data anywhere for a destructive wipe to lose.
 * This should be revisited (a real Migration) before step 13 ships.
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

        fun getInstance(context: Context): InterventionDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    InterventionDatabase::class.java,
                    "checkmate_intervention.db"
                )
                    .fallbackToDestructiveMigration()
                    .build().also { INSTANCE = it }
            }
    }
}
