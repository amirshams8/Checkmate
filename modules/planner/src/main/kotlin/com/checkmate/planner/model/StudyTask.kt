package com.checkmate.planner.model

import kotlinx.serialization.Serializable

enum class TaskState { PENDING, ACTIVE, DONE, SKIPPED }

@Serializable
data class StudyTask(
    val id:              String    = java.util.UUID.randomUUID().toString(),
    val subject:         String,
    val topic:           String,
    val durationMinutes: Int,
    val priority:        Int       = 1,
    val scheduledAt:     Long      = System.currentTimeMillis(),
    val state:           TaskState = TaskState.PENDING,
    val completedAt:     Long?     = null,
    val focusMinutes:    Int       = 0,
    val checksPassed:    Int       = 0,
    val checksMissed:    Int       = 0
)

@Serializable
data class SubjectConfig(
    val name:      String,
    val weightage: Int = 1
)
