package com.checkmate.learning.model

import androidx.room.Entity
import androidx.room.Index

/**
 * P0 Q-bank MVP. One row per (dayKey, testPlanId) — [QuestionTargetEngine] computes
 * this once per calendar day and persists it, same "compute once, read many" shape
 * as GapTaskLedger's own once-per-day generation gate, so the day's allocation stays
 * stable across app restarts / 15-min ReminderService ticks instead of silently
 * recomputing (and potentially drifting, e.g. if a mastery recompute lands mid-day)
 * on every read.
 *
 * `dayKey` uses the same "YYYY_dayOfYear" format GapTaskLedger/PlanStore already use
 * (see GapTaskLedger.todayKey's own comment) rather than java.time, for consistency
 * with the rest of the day-keyed state in this codebase.
 */
@Entity(
    tableName = "daily_question_targets",
    primaryKeys = ["dayKey", "testPlanId"],
    indices = [Index(value = ["testPlanId"])]
)
data class DailyQuestionTarget(
    val dayKey: String,
    val testPlanId: String,
    val totalQuestions: Int,
    /** chapter -> question count allocated today. Empty map is a valid, expected
     *  result (final-review window before the exam, exam already passed, or every
     *  chapter already fully covered) — callers must not treat empty as an error. */
    val chapterAllocations: Map<String, Int>,
    val generatedAt: Long = System.currentTimeMillis()
)
