package com.checkmate.core

import com.checkmate.core.CheckmatePrefs
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

// FEATURE: Coaching-Test Countdown — structured (not string) lookup so
// PsycheEngine.getSkipReaction() can branch on "is there a test in this
// subject within N days" without re-parsing dates itself or string-matching
// upcomingContext()'s flattened output.
data class UpcomingTest(
    val subject:    String,
    val chapter:    String,
    val date:       String,
    val daysAway:   Int
)

@Serializable
data class CoachingPlannerEntry(
    val id:       String = java.util.UUID.randomUUID().toString(),
    val subject:  String,
    val chapter:  String,
    val type:     String,  // "test" | "lecture"
    val date:     String,  // "dd/MM/yyyy"
    val notes:    String   = ""
) {
    companion object {
        private const val KEY = "coaching_planner_entries"
        private val json = Json { ignoreUnknownKeys = true }

        fun loadAll(): List<CoachingPlannerEntry> {
            val raw = CheckmatePrefs.getString(KEY, null) ?: return emptyList()
            return try { json.decodeFromString(raw) } catch (_: Exception) { emptyList() }
        }

        fun saveAll(entries: List<CoachingPlannerEntry>) {
            CheckmatePrefs.putString(KEY, json.encodeToString(entries))
        }

        fun addEntry(entry: CoachingPlannerEntry) {
            saveAll(loadAll() + entry)
        }

        /** Returns coaching events in the next N days as a prompt-ready string */
        fun upcomingContext(withinDays: Int = 7): String {
            val entries = loadAll().filter { entry ->
                try {
                    val parts = entry.date.split("/")
                    val cal = java.util.Calendar.getInstance()
                    val now = cal.clone() as java.util.Calendar
                    cal.set(parts[2].toInt(), parts[1].toInt() - 1, parts[0].toInt())
                    val diffDays = ((cal.timeInMillis - now.timeInMillis) / (1000 * 60 * 60 * 24)).toInt()
                    diffDays in 0..withinDays
                } catch (_: Exception) { false }
            }
            if (entries.isEmpty()) return "No upcoming coaching tests/lectures in $withinDays days"
            return entries.joinToString("\n") {
                "${it.date}: ${it.subject} ${it.chapter} (${it.type})"
            }
        }

        /**
         * Returns the nearest upcoming TEST (not lecture) for a given subject
         * within withinDays, or null if none exists / date can't be parsed.
         * Subject match is case-insensitive to tolerate manual entry drift
         * (e.g. "biology" vs "Biology") without silently missing a real test.
         *
         * Returning null is the safe default for every failure path here —
         * callers (PsycheEngine) treat null as "no urgency signal" and fall
         * back to their existing behavior, so a stale/malformed coaching
         * entry can only ever under-trigger urgency, never fabricate it.
         */
        fun nearestUpcomingTest(subject: String, withinDays: Int = 7): UpcomingTest? {
            return loadAll()
                .filter { it.type.equals("test", ignoreCase = true) }
                .filter { it.subject.equals(subject, ignoreCase = true) }
                .mapNotNull { entry ->
                    try {
                        val parts = entry.date.split("/")
                        val cal = java.util.Calendar.getInstance()
                        val now = cal.clone() as java.util.Calendar
                        cal.set(parts[2].toInt(), parts[1].toInt() - 1, parts[0].toInt())
                        val diffDays = ((cal.timeInMillis - now.timeInMillis) / (1000 * 60 * 60 * 24)).toInt()
                        if (diffDays in 0..withinDays) {
                            UpcomingTest(
                                subject  = entry.subject,
                                chapter  = entry.chapter,
                                date     = entry.date,
                                daysAway = diffDays
                            )
                        } else null
                    } catch (_: Exception) { null }
                }
                .minByOrNull { it.daysAway }
        }
    }
}
