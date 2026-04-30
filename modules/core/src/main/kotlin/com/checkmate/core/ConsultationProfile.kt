package com.checkmate.core

import android.content.Context
import com.checkmate.core.CheckmatePrefs
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

@Serializable
data class TimeSlot(
    val label:     String,
    val startTime: String, // "HH:MM"
    val endTime:   String  // "HH:MM"
)

@Serializable
data class ConsultationProfile(
    val examTarget:       String         = "NEET",
    val examDate:         String         = "",
    val currentClass:     String         = "Dropper",
    val coachingName:     String         = "",
    val targetScore:      Int            = 650,
    val currentMockScore: Int            = 0,
    val weakSubjects:     List<String>   = emptyList(),
    val weakTopics:       List<String>   = emptyList(),
    val stressLevel:      Int            = 3,
    val sleepHours:       Float          = 7f,
    val studyHoursPerDay: Float          = 8f,
    val blockedSlots:     List<TimeSlot> = emptyList()
) {
    companion object {
        private const val KEY = "consultation_profile"
        private val json = Json { ignoreUnknownKeys = true }

        fun load(): ConsultationProfile {
            val raw = CheckmatePrefs.getString(KEY, null) ?: return ConsultationProfile()
            return try { json.decodeFromString(raw) } catch (_: Exception) { ConsultationProfile() }
        }

        fun save(profile: ConsultationProfile) {
            CheckmatePrefs.putString(KEY, json.encodeToString(profile))
        }

        fun hasProfile(): Boolean = !CheckmatePrefs.getString(KEY, null).isNullOrBlank()

        /** Returns a flat string for injection into the LLM planner prompt */
        fun ConsultationProfile.toPromptContext(): String {
            val score = if (currentMockScore > 0)
                "Current mock: $currentMockScore/$targetScore (gap: ${targetScore - currentMockScore})"
            else "Mock score not set"
            val blocked = if (blockedSlots.isEmpty()) "None"
            else blockedSlots.joinToString { "${it.label} ${it.startTime}–${it.endTime}" }
            val weak = if (weakTopics.isEmpty()) weakSubjects.joinToString() else weakTopics.joinToString()
            return buildString {
                appendLine("Exam: $examTarget | Date: $examDate | Class: $currentClass")
                appendLine("Coaching: ${coachingName.ifBlank { "None" }}")
                appendLine("Target: $targetScore | $score")
                appendLine("Weak areas: ${weak.ifBlank { "Not set" }}")
                appendLine("Stress: $stressLevel/5 | Sleep: ${sleepHours}h | Study: ${studyHoursPerDay}h/day")
                appendLine("Blocked: $blocked")
            }.trim()
        }
    }
}
