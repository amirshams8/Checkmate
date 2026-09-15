package com.checkmate.learning.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * P0 Q-bank MVP (see chat history: "Test on the 23rd" design thread). A student-
 * facing upcoming test that the Q-bank daily-target engine schedules practice
 * against — e.g. "Sept 23 mock." Deliberately minimal: no subjects/concepts list
 * separate from [chapterTargets]'s own keys, since nothing downstream needs
 * subject-level granularity yet and chapter is already the selection granularity
 * both [com.checkmate.learning.repository.QuestionDao.qbankPoolByChapter] and the
 * existing [com.checkmate.learning.repository.QuestionDao.getExternalWrongOrSkipped]
 * fallback use.
 *
 * `examDateMillis` is a real instant (midnight local time on the exam date), not a
 * free-text field — [com.checkmate.core.ConsultationProfile.examDate] is a
 * user-entered display string and isn't reliable for day-arithmetic; this is the
 * structured field [QuestionTargetEngine] actually schedules against.
 */
@Entity(
    tableName = "test_plans",
    indices = [Index(value = ["examDateMillis"])]
)
data class TestPlan(
    @PrimaryKey val id: String,
    val name: String,
    val exam: String,
    val examDateMillis: Long,
    /** chapter (raw label, matching [Question.chapter]/Testmate's own chapter tags —
     *  NOT the canonicalized ConceptWeightage name, same reasoning as
     *  GapTaskManager.createTargetedTestIfNeeded's own chapter-string BUGFIX) ->
     *  total question count needed for full syllabus coverage of this test. */
    val chapterTargets: Map<String, Int>,
    val createdAt: Long = System.currentTimeMillis()
)
