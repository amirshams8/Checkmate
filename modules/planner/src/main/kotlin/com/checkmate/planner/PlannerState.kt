package com.checkmate.planner

import com.checkmate.planner.model.SubjectConfig

/**
 * Configuration passed to AdaptivePlanner to generate a daily plan.
 * Extended with consultation profile context and daily check-in data.
 */
data class PlannerState(
    val examType:       String              = "NEET",
    val examDate:       String              = "01/01/2026",
    val subjects:       List<SubjectConfig> = emptyList(),
    val studyStartTime: String              = "09:00",
    val studyEndTime:   String              = "21:00",
    // ── Consultation profile context (Blueprint 3.5) ──
    val currentClass:      String           = "",   // "11" | "12" | "Dropper"
    val targetScore:       Int              = 0,
    val currentMockScore:  Int              = 0,
    val weakSubjects:      List<String>     = emptyList(),
    val weakTopics:        List<String>     = emptyList(),
    val stressLevel:       Int              = 3,    // 1–5
    val sleepHours:        Float            = 7f,
    val studyHoursPerDay:  Float            = 8f,
    val blockedSlotsSummary: String         = "", // "School 08:00-14:00, Coaching 17:00-19:00"
    // ── Daily check-in context ──
    val todayTopics:       Map<String, String> = emptyMap(), // subject → topic chosen today
    val upcomingTestDates: String           = "",   // "Physics test in 2 days"
    val coachingContext:   String           = ""    // recent coaching schedule context
)
