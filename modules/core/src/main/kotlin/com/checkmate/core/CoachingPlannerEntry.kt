package com.checkmate.core

import com.checkmate.core.CheckmatePrefs
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

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
    }
}
