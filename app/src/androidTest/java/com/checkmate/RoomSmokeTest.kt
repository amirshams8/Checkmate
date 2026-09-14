package com.checkmate

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.checkmate.learning.repository.LearningDatabase
import com.checkmate.planner.intervention.InterventionDatabase
import com.checkmate.psyche.db.BehaviorDatabase
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Testing backlog item 1 (baseline smoke test) — "verify Room opens".
 *
 * Checkmate has three independent Room databases (LearningDatabase,
 * InterventionDatabase, BehaviorDatabase — see each class's own doc for why
 * they're separate files rather than one schema). This is the first test
 * that ever opens all three together: each one previously only had proof it
 * compiles, via the module-level DAO tests (e.g. LearningInterventionOrchestratorIntegrationTest
 * exercises InterventionDatabase indirectly, but nothing asserted the schema
 * itself builds clean under Room's schema-hash validation).
 *
 * Deliberately uses Room.inMemoryDatabaseBuilder rather than
 * LearningDatabase.getInstance()/InterventionDatabase.getInstance()/
 * BehaviorDatabase.getInstance() — those are on-disk file-backed singletons
 * (checkmate_learning.db / checkmate_intervention.db / checkmate_behavior.db)
 * and this test must not touch or pollute real app data, nor trip over the
 * process-wide `INSTANCE` singleton being already set by a prior test class
 * in the same instrumentation process.
 */
@RunWith(AndroidJUnit4::class)
class RoomSmokeTest {

    @Test
    fun learningDatabaseOpensAndAcceptsAQuery() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = Room.inMemoryDatabaseBuilder(context, LearningDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            // Trivial round-trip against every DAO Room exposes off this database —
            // if the schema failed to build, this throws before the assertion runs.
            val count = db.learningEventDao().count(studentId = "smoke_test_student")
            org.junit.Assert.assertEquals(0, count)
        } finally {
            db.close()
        }
    }

    @Test
    fun interventionDatabaseOpensAndAcceptsAQuery() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = Room.inMemoryDatabaseBuilder(context, InterventionDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val rows = db.outcomeLedgerDao().getAll()
            org.junit.Assert.assertTrue(rows.isEmpty())
        } finally {
            db.close()
        }
    }

    @Test
    fun behaviorDatabaseOpensAndAcceptsAQuery() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = Room.inMemoryDatabaseBuilder(context, BehaviorDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val count = db.behaviorEventDao().count()
            org.junit.Assert.assertEquals(0, count)
        } finally {
            db.close()
        }
    }
}
