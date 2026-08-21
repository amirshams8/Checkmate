package com.checkmate.learning.repository

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.checkmate.learning.model.LearningEvent
import com.checkmate.learning.model.Question
import com.checkmate.learning.model.QuestionAttempt

/**
 * Upgrade Blueprint Phase 1.1-1.2. Own .db file ("checkmate_learning.db"),
 * separate from BehaviorDatabase ("checkmate_behavior.db") and
 * InterventionDatabase ("checkmate_intervention.db") — same reasoning
 * BehaviorDatabase's own doc gives: unrelated lifecycles, no foreign-key
 * relationship across the three, so no reason to merge them into one file.
 */
@Database(
    entities = [LearningEvent::class, Question::class, QuestionAttempt::class],
    version = 1,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class LearningDatabase : RoomDatabase() {

    abstract fun learningEventDao(): LearningEventDao
    abstract fun questionDao(): QuestionDao
    abstract fun questionAttemptDao(): QuestionAttemptDao

    companion object {
        @Volatile
        private var INSTANCE: LearningDatabase? = null

        fun getInstance(context: Context): LearningDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    LearningDatabase::class.java,
                    "checkmate_learning.db"
                )
                    // Version 1, no upgrade path needed yet — same downgrade-only-destructive
                    // posture as BehaviorDatabase/InterventionDatabase.
                    .fallbackToDestructiveMigrationOnDowngrade()
                    .build().also { INSTANCE = it }
            }
    }
}
