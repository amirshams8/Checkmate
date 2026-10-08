package com.checkmate.core

import com.checkmate.core.CheckmatePrefs
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.util.Calendar

@Serializable
data class DailyCheckIn(
    val date:           String              = "",  // "YYYY_DDD"
    val todayTopics:    Map<String, String> = emptyMap(), // subject → topic
    // subject → syllabus topics under that chapter (optional narrowing, several allowed). Renamed from the old
    // single-topic `todayTopicPicks` map so an already-saved check-in still decodes (the old key is ignored).
    val todayTopicSets: Map<String, List<String>> = emptyMap(),
    val yesterdayRatings: Map<String, Int>  = emptyMap(), // subject → 1..5
    val stressLevel:    Int                 = 3,
    val sleepHours:     Float               = 7f,
    val completedAt:    Long                = 0L
) {
    companion object {
        private const val KEY_PREFIX = "daily_checkin_"
        private val json = Json { ignoreUnknownKeys = true }

        private fun todayKey(): String {
            val cal = Calendar.getInstance()
            return "${cal.get(Calendar.YEAR)}_${cal.get(Calendar.DAY_OF_YEAR)}"
        }

        fun loadToday(): DailyCheckIn? {
            val raw = CheckmatePrefs.getString(KEY_PREFIX + todayKey(), null) ?: return null
            return try { json.decodeFromString(raw) } catch (_: Exception) { null }
        }

        fun saveToday(checkIn: DailyCheckIn) {
            val key = todayKey()
            CheckmatePrefs.putString(KEY_PREFIX + key, json.encodeToString(checkIn.copy(date = key)))
        }

        fun hasDoneCheckInToday(): Boolean = loadToday()?.completedAt != null && loadToday()!!.completedAt > 0L
    }
}
