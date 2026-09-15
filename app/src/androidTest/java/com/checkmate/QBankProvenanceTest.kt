package com.checkmate

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.checkmate.learning.model.DailyQuestionTarget
import com.checkmate.learning.model.LearningIds
import com.checkmate.learning.model.Question
import com.checkmate.learning.model.QuestionAttempt
import com.checkmate.learning.model.QuestionSource
import com.checkmate.learning.model.TestPlan
import com.checkmate.learning.repository.LearningDatabase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression coverage for the provenance boundary the chat history's audit turned
 * up: a Q-bank question (source = "qbank") must never surface via the
 * external-report repair fallback ([QuestionDao.getExternalWrongOrSkipped]), and an
 * external_report question must never surface via the Q-bank pool
 * ([QuestionDao.qbankPoolByChapter]) — same source-scoped DAO boundary
 * getExternalWrongOrSkipped already enforced before Q-bank existed.
 *
 * Deliberately DAO-only, no QuestionTargetEngine/QBankSelector call — those two
 * touch LearningDatabase.getInstance(context)'s real on-disk singleton internally
 * (see their own TESTING NOTE), which this in-memory db is not. The
 * dailyQuestionTargetDaoRoundTrip test below still exercises the new
 * Map<String, Int> TypeConverter and the DailyQuestionTargetDao/TestPlanDao schema
 * those two engines depend on, just without going through the engines themselves.
 *
 * Same in-memory-Room, AndroidJUnit4, allowMainThreadQueries() pattern as
 * RoomSmokeTest — this repo's one existing Room instrumented-test precedent.
 */
@RunWith(AndroidJUnit4::class)
class QBankProvenanceTest {

    private lateinit var db: LearningDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, LearningDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun question(id: String, source: String, chapter: String, topic: String? = null) = Question(
        id = id,
        source = source,
        chapter = chapter,
        topic = topic
    )

    @Test
    fun qbankQuestionNeverSurfacesViaExternalReportFallback() = runTest {
        val studentId = LearningIds.LOCAL_STUDENT_ID
        val qbankQ = question("q-qbank-1", QuestionSource.QBANK, chapter = "NLM")
        val externalQ = question("q-ext-1", QuestionSource.EXTERNAL_REPORT, chapter = "NLM")
        db.questionDao().upsertAll(listOf(qbankQ, externalQ))
        db.questionAttemptDao().insertAll(listOf(
            QuestionAttempt(studentId = studentId, questionId = qbankQ.id, timestamp = 1L, correct = false),
            QuestionAttempt(studentId = studentId, questionId = externalQ.id, timestamp = 1L, correct = false)
        ))

        val fallbackResult = db.questionDao().getExternalWrongOrSkipped(
            chapter = "NLM", topic = null, source = QuestionSource.EXTERNAL_REPORT, studentId = studentId
        )

        assertEquals(listOf(externalQ.id), fallbackResult.map { it.id })
        assertTrue("qbank question leaked into external_report fallback", fallbackResult.none { it.id == qbankQ.id })
    }

    @Test
    fun externalReportQuestionNeverSurfacesViaQbankPool() = runTest {
        val qbankQ = question("q-qbank-2", QuestionSource.QBANK, chapter = "NLM")
        val externalQ = question("q-ext-2", QuestionSource.EXTERNAL_REPORT, chapter = "NLM")
        db.questionDao().upsertAll(listOf(qbankQ, externalQ))

        val qbankResult = db.questionDao().qbankPoolByChapter(chapter = "NLM", limit = 10)

        assertEquals(listOf(qbankQ.id), qbankResult.map { it.id })
        assertTrue("external_report question leaked into qbank pool", qbankResult.none { it.id == externalQ.id })
    }

    @Test
    fun countQbankCorrectByChapterIgnoresOtherSourcesAndWrongAttempts() = runTest {
        val studentId = LearningIds.LOCAL_STUDENT_ID
        val qbankCorrect = question("q-qbank-3", QuestionSource.QBANK, chapter = "NLM")
        val qbankWrong = question("q-qbank-4", QuestionSource.QBANK, chapter = "NLM")
        val externalCorrect = question("q-ext-3", QuestionSource.EXTERNAL_REPORT, chapter = "NLM")
        db.questionDao().upsertAll(listOf(qbankCorrect, qbankWrong, externalCorrect))
        db.questionAttemptDao().insertAll(listOf(
            QuestionAttempt(studentId = studentId, questionId = qbankCorrect.id, timestamp = 1L, correct = true),
            QuestionAttempt(studentId = studentId, questionId = qbankWrong.id, timestamp = 1L, correct = false),
            QuestionAttempt(studentId = studentId, questionId = externalCorrect.id, timestamp = 1L, correct = true)
        ))

        val count = db.questionDao().countQbankCorrectByChapter(chapter = "NLM", studentId = studentId)

        assertEquals(1, count)
    }

    @Test
    fun dailyQuestionTargetDaoRoundTrip() = runTest {
        val testPlan = TestPlan(
            id = "sept23",
            name = "Sept 23 mock",
            exam = "NEET",
            examDateMillis = System.currentTimeMillis() + 8L * 24 * 60 * 60 * 1000,
            chapterTargets = mapOf("NLM" to 40)
        )
        db.testPlanDao().upsert(testPlan)
        assertEquals(testPlan, db.testPlanDao().getById(testPlan.id))

        val target = DailyQuestionTarget(
            dayKey = "2026_258",
            testPlanId = testPlan.id,
            totalQuestions = 15,
            chapterAllocations = mapOf("NLM" to 15)
        )
        db.dailyQuestionTargetDao().upsert(target)

        assertEquals(target, db.dailyQuestionTargetDao().getByDay("2026_258", testPlan.id))
        assertNull(db.dailyQuestionTargetDao().getByDay("2026_259", testPlan.id))
    }
}
