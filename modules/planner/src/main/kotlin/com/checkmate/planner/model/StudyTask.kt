package com.checkmate.planner.model

import kotlinx.serialization.Serializable

enum class TaskState { PENDING, ACTIVE, PAUSED, DONE, SKIPPED }

@Serializable
data class StudyTask(
    val id:              String     = java.util.UUID.randomUUID().toString(),
    val subject:         String,
    val topic:           String,
    val durationMinutes: Int,
    val priority:        Int        = 1,
    val scheduledAt:     Long       = System.currentTimeMillis(),
    val state:           TaskState  = TaskState.PENDING,
    val completedAt:     Long?      = null,
    val focusMinutes:    Int        = 0,
    val checksPassed:    Int        = 0,
    val checksMissed:    Int        = 0,
    // ── Pause/Resume fields (Blueprint 2.1) ──
    val pausedAt:        Long?      = null,   // epoch ms when task was paused
    val totalPausedMs:   Long       = 0L,     // accumulated pause time across all pauses
    val actualMinutes:   Int        = 0,      // real focus time excluding paused periods
    // ── Manual task tracking ──
    val isCustom:        Boolean    = false,  // true only for tasks added via HomeViewModel.addCustomTask;
                                               // gates the duration-edit affordance in HomeScreen
    // ── Accountability Core: Intention Declaration + Session Check-In (Blueprint 10.1) ──
    val intentionText:   String     = "",     // free-text answer to "What will you study?" (pre-session)
    val completedStatus: String?    = null    // "YES" | "PARTIAL" | "NO" — self-report from "Did you finish it?" (post-session)
)

@Serializable
data class SubjectConfig(
    val name:      String,
    val weightage: Int = 1
)
