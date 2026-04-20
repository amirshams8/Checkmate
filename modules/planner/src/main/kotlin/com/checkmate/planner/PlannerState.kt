package com.checkmate.planner

import com.checkmate.planner.model.SubjectConfig

/**
 * Configuration passed to AdaptivePlanner to generate a daily plan.
 * Defined here in modules:planner so AdaptivePlanner can reference it
 * without a circular dependency on the app module.
 */
data class PlannerState(
    val examType:       String            = "NEET",
    val examDate:       String            = "01/01/2026",
    val subjects:       List<SubjectConfig> = emptyList(),
    val studyStartTime: String            = "09:00",
    val studyEndTime:   String            = "21:00"
)
