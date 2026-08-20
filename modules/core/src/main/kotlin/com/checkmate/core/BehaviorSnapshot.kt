package com.checkmate.core

import kotlinx.serialization.Serializable

/**
 * Upgrade Blueprint Phase 0 item #2 ("Stop treating the LLM as source of truth").
 *
 * This is the single structured payload that crosses the :modules:psyche ->
 * :modules:planner boundary to feed AdaptivePlanner's LLM prompt. Every field is a
 * plain number or list computed deterministically from BehaviorLedger's stored
 * events (see BehaviorLedger.getSnapshot()) — nothing here is LLM-generated and
 * nothing is pre-rendered into prose. AdaptivePlanner embeds this as literal JSON
 * in the prompt instead of the old hand-built "Streak: Xd, skip rate: Y%..."
 * string, so the model reasons over auditable numbers instead of a summary that
 * was already someone's (or something's) interpretation of the numbers.
 *
 * Lives in :modules:core (not :modules:psyche, which produces it, or
 * :modules:planner, which consumes it) because both of those already depend on
 * core, and core depends on neither — planner can't depend on psyche directly
 * (psyche depends on planner for StudyTask/TaskState, so the reverse would be
 * circular; see PsycheEngine's own doc on the CheckmatePrefs bridge this rides
 * across). Putting the shared shape in core lets both sides decode/encode the
 * same concrete type instead of hand-parsing raw JSON on either end.
 */
@Serializable
data class BehaviorSnapshot(
    val streakDays: Int = 0,
    val recentSkipRatePercent: Int = 0,
    val totalSkips7d: Int = 0,
    val attentionChecksPassed: Int = 0,
    val attentionChecksMissed: Int = 0,
    val avgFocusMinutes: Int = 0,
    // Same-day DONE tasks, most recent first — structured replacement for the old
    // BehaviorLedger.getTodayCompletedSummary() prose string (that method is kept
    // as-is for its other caller, the intervention-context pipeline, which is
    // outside item #2's scope — this is a parallel structured view for planning).
    val todayCompleted: List<CompletedItem> = emptyList(),
    // Deterministic pattern flags: (subject, taskType) pairs with skip counts at or
    // above BehaviorLedger's threshold in the last 7 days. Same "you've made this
    // mistake N times" framing the Upgrade Blueprint calls out for ErrorEngine
    // (Phase 1.6), applied here to skip behavior a phase early since the data
    // already existed in BehaviorLedger.
    val subjectPatterns: List<SkipPattern> = emptyList(),
    val generatedAt: Long = System.currentTimeMillis()
) {
    @Serializable
    data class CompletedItem(
        val subject: String,
        val topic: String,
        val taskType: String
    )

    @Serializable
    data class SkipPattern(
        val subject: String,
        val taskType: String,
        val occurrences: Int
    )

    companion object {
        // Returned by AdaptivePlanner when no cached snapshot exists yet (e.g. very
        // first plan ever generated) — the LLM gets the same JSON shape either way,
        // all zeros/empty, rather than a special-cased sentence like "No behavior
        // data yet" it would have to treat as a different kind of input.
        val EMPTY = BehaviorSnapshot()
    }
}
