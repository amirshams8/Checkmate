package com.checkmate.planner.intervention

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Proactive Execution Engine — Step 3 (Blueprint Part One, §4).
 *
 * Standalone singleton, same shape as a typical Room setup — deliberately not wired into
 * CheckmateApp.onCreate() yet. Nothing calls InterventionDatabase.getInstance() until the
 * Task Escrow (step 4) and ActionExecutor (step 5) exist to actually create/update rows,
 * so wiring it into app startup now would be speculative.
 */
@Database(entities = [InterventionTransaction::class], version = 1, exportSchema = false)
abstract class InterventionDatabase : RoomDatabase() {

    abstract fun interventionTransactionDao(): InterventionTransactionDao

    companion object {
        @Volatile
        private var INSTANCE: InterventionDatabase? = null

        fun getInstance(context: Context): InterventionDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    InterventionDatabase::class.java,
                    "checkmate_intervention.db"
                ).build().also { INSTANCE = it }
            }
    }
}
