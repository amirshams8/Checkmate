package com.checkmate.learning.qbank

import android.content.Context
import android.util.Log
import com.checkmate.core.ConsultationProfile
import com.checkmate.learning.model.ConceptMastery
import com.checkmate.learning.model.DailyQuestionTarget
import com.checkmate.learning.model.LearningIds
import com.checkmate.learning.model.Question
import com.checkmate.learning.repository.LearningDatabase

/**
 * P0 Q-bank MVP. Responsibility: [DailyQuestionTarget] + StudentModel (mastery) +
 * Q-bank availability -> actual [Question] rows to serve today.
 *
 * Reads ONLY from [com.checkmate.learning.repository.QuestionDao.qbankPoolByChapter]
 * — a `source = QuestionSource.QBANK`-scoped query — never
 * [com.checkmate.learning.repository.QuestionDao.getExternalWrongOrSkipped]. That
 * boundary is the whole point of the provenance audit in chat history: a live
 * Testmate targeted-repair test is selected on Testmate's own servers, and the
 * external_report fallback is scoped to its own source value — this selector has no
 * business calling either path, and doesn't import the symbols needed to.
 *
 * Priority order actually implemented (see chat history's original 7-item list —
 * items already handled elsewhere are dropped here, not silently reordered):
 *   1. upcoming-test syllabus + chapter allocation  -> QuestionTargetEngine (caller)
 *   2. not recently attempted                       -> excluded first, below
 *   3. low mastery / high forgetting risk            -> sort key, below
 *   4. avoid already-selected IDs (cross-chapter)    -> usedIds, below
 *   5. deterministic tie-break                       -> Question.id, below
 * "high historical importance" / "prerequisite weakness" are NOT implemented in this
 * P0 slice — nothing in the current schema carries either signal at question
 * granularity yet (ConceptWeightage is chapter-level; prerequisite diagnosis is a
 * per-concept engine call, not a cheap per-question sort key). Revisit once the
 * mastery-only ranking proves too coarse in practice.
 *
 * TESTING NOTE: same as QuestionTargetEngine — touches the LearningDatabase
 * singleton directly, so it's exercised via manual/real-device verification for this
 * P0 slice rather than an instrumented test, consistent with how every other
 * context-driven engine in this module (MasteryEngine, ErrorEngine, RetentionEngine,
 * StudentModelBuilder) is already handled.
 */
object QBankSelector {
    private const val TAG = "QBankSelector"

    /** How far back "recently attempted" looks, for excluding a question from
     *  today's fresh practice set. Tunable; not derived from anything. */
    private const val RECENT_WINDOW_MILLIS = 3L * 24 * 60 * 60 * 1000 // 3 days

    /** Oversample factor so ranking + recent-attempt exclusion still leaves enough
     *  candidates to fill a chapter's allocated count. */
    private const val OVERSAMPLE_FACTOR = 4

    suspend fun selectTodayQuestions(context: Context, target: DailyQuestionTarget): List<Question> {
        if (target.chapterAllocations.isEmpty()) return emptyList()

        val db = LearningDatabase.getInstance(context)
        val studentId = LearningIds.LOCAL_STUDENT_ID
        val exam = ConsultationProfile.load().examTarget
        val recentIds = db.questionAttemptDao()
            .getRecentQuestionIds(studentId, System.currentTimeMillis() - RECENT_WINDOW_MILLIS)
            .toSet()
        // (chapter|topic) -> mastery, built once rather than per-question — see
        // StudentModelBuilder's own "don't loop DAO.get() per item" precedent.
        val masteryLookup = buildMasteryLookup(db, exam)

        val selected = mutableListOf<Question>()
        val usedIds = mutableSetOf<String>()

        for ((chapter, count) in target.chapterAllocations) {
            if (count <= 0) continue
            val candidates = db.questionDao()
                .qbankPoolByChapter(chapter, limit = count * OVERSAMPLE_FACTOR)
                .filterNot { it.id in usedIds }
            if (candidates.isEmpty()) {
                // Expected, not an error — e.g. Biology has zero source="qbank" rows
                // today (chat history: chem/physics only for now). QuestionTargetEngine
                // still allocates a target for it since readiness doesn't know a bank
                // is missing; this is where that gets absorbed silently.
                Log.d(TAG, "selectTodayQuestions: chapter=$chapter allocated=$count but qbank pool is empty")
                continue
            }
            val ranked = candidates.sortedWith(
                compareBy<Question> { if (it.id in recentIds) 1 else 0 }
                    .thenByDescending { riskScore(it, masteryLookup) }
                    .thenBy { it.id }
            )
            val picked = ranked.take(count)
            selected += picked
            usedIds += picked.map { it.id }
        }
        return selected
    }

    /** Higher = practice this first (low mastery / high forgetting risk / never
     *  attempted at all). */
    private fun riskScore(question: Question, masteryLookup: Map<String, ConceptMastery>): Double {
        val chapter = question.chapter ?: return 1.0
        val key = conceptKey(chapter, question.topic)
        val mastery = masteryLookup[key] ?: return 1.0 // no data yet -> treat as max priority
        return (1.0 - mastery.mastery).coerceIn(0.0, 1.0) + mastery.forgettingRisk
    }

    private suspend fun buildMasteryLookup(
        db: LearningDatabase,
        exam: String
    ): Map<String, ConceptMastery> {
        val concepts = db.conceptDao().getByExam(exam)
        val masteryById = db.masteryDao().getAll(LearningIds.LOCAL_STUDENT_ID).associateBy { it.conceptId }
        return concepts.mapNotNull { c ->
            val mastery = masteryById[c.id] ?: return@mapNotNull null
            conceptKey(c.chapter, c.topic) to mastery
        }.toMap()
    }

    private fun conceptKey(chapter: String, topic: String?) =
        "${chapter.trim().lowercase()}|${(topic ?: chapter).trim().lowercase()}"
}
