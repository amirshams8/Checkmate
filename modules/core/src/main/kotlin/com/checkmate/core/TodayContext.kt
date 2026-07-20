package com.checkmate.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.util.Calendar

/**
 * TodayContext — Mentor v2 (spec 2.3).
 *
 * Single source of truth for "what has the student actually done today," separate from
 * DailyCheckIn (morning-filled *intent*) and DailyChecklist (Checkmate's own fixed 5 items).
 * Both MentorViewModel and AdaptivePlanner should read getSummaryText() instead of each
 * inventing their own notion of "today's context" — previously Mentor chat history
 * (mentor_chat_history) was a dead end that never reached the planner at all.
 *
 * Entries are free text (e.g. "Back from coaching — covered Thermodynamics, 2hrs") and can
 * originate from: a manual quick-log UI action, or an LLM-side extraction step run on Mentor
 * chat messages that clearly describe completed activity (left as a call site decision —
 * this object only stores/retrieves, it doesn't decide what counts as an update).
 *
 * Day-keyed exactly like DailyChecklist, so entries naturally roll over at midnight without
 * needing an explicit clear step.
 */
@Serializable
private data class TodayUpdate(
    val text: String,
    val timestamp: Long = System.currentTimeMillis()
)

object TodayContext {

    private val json = Json { ignoreUnknownKeys = true }
    private const val MAX_UPDATES_PER_DAY = 20
    private const val MAX_SUMMARY_CHARS   = 1200

    fun appendUpdate(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return

        val key      = keyForToday()
        val existing = loadUpdates(key).toMutableList()
        existing.add(TodayUpdate(clean))
        val trimmed = existing.takeLast(MAX_UPDATES_PER_DAY)
        CheckmatePrefs.putString(key, json.encodeToString(trimmed))
    }

    /** Chronological (oldest first), same-day only. */
    fun getTodayUpdates(): List<String> =
        loadUpdates(keyForToday()).map { it.text }

    /**
     * Joined, capped-length text block ready for prompt injection into Mentor's system
     * prompt or AdaptivePlanner's plan-generation prompt. Returns "" when nothing has
     * been logged today — callers should treat that as "no update," not "nothing happened."
     */
    fun getSummaryText(): String {
        val updates = getTodayUpdates()
        if (updates.isEmpty()) return ""
        val joined = updates.joinToString("\n") { "- $it" }
        return if (joined.length > MAX_SUMMARY_CHARS) joined.takeLast(MAX_SUMMARY_CHARS) else joined
    }

    private fun loadUpdates(key: String): List<TodayUpdate> {
        val saved = CheckmatePrefs.getString(key, null) ?: return emptyList()
        return try { json.decodeFromString(saved) } catch (_: Exception) { emptyList() }
    }

    private fun keyForToday(): String {
        val c = Calendar.getInstance()
        return "today_context_${c.get(Calendar.YEAR)}_${c.get(Calendar.DAY_OF_YEAR)}"
    }
}
