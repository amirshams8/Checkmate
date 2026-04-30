package com.checkmate.ui.home

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.checkmate.core.CheckmatePrefs
import com.checkmate.core.tts.CheckmateTTS
import com.checkmate.planner.PlanStore
import com.checkmate.planner.model.StudyTask
import com.checkmate.planner.model.TaskState
import com.checkmate.psyche.PsycheEngine
import com.checkmate.service.AttentionCycleService
import com.checkmate.service.GuardianNotifier
import com.checkmate.workmode.WorkModeManager
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class HomeState(
    val tasks:             List<StudyTask> = emptyList(),
    val activeTaskId:      String?         = null,
    val completedToday:    Int             = 0,
    val streakDays:        Int             = 0,
    val psycheMessage:     String          = "",
    val consecutiveSkips:  Int             = 0
)

class HomeViewModel : ViewModel() {

    private val _state = MutableStateFlow(HomeState())
    val state: StateFlow<HomeState> = _state.asStateFlow()

    init { loadTodayPlan(); loadStreak(); loadPsycheMessage() }

    private fun loadTodayPlan() {
        viewModelScope.launch {
            PlanStore.todayTasks.collect { tasks ->
                _state.update { it.copy(
                    tasks          = tasks,
                    completedToday = tasks.count { t -> t.state == TaskState.DONE },
                    activeTaskId   = tasks.firstOrNull { t ->
                        t.state == TaskState.ACTIVE || t.state == TaskState.PAUSED
                    }?.id
                )}
            }
        }
    }

    private fun loadStreak() {
        viewModelScope.launch { _state.update { it.copy(streakDays = PlanStore.getStreakDays()) } }
    }

    private fun loadPsycheMessage() {
        viewModelScope.launch {
            _state.update { it.copy(psycheMessage = PsycheEngine.getDailyMorningMessage()) }
        }
    }

    fun startTask(context: Context, task: StudyTask) {
        viewModelScope.launch {
            WorkModeManager.activate(context)
            val mappedPkg = CheckmatePrefs.getString("app_map_${task.subject}", null)
            mappedPkg?.let { pkg ->
                context.packageManager.getLaunchIntentForPackage(pkg)
                    ?.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    ?.let { context.startActivity(it) }
            }
            AttentionCycleService.start(context, task.id, task.topic, task.durationMinutes.toLong())
            CheckmateTTS.speak(context, "Starting ${task.subject}. Focus for ${task.durationMinutes} minutes.")
            PlanStore.setTaskActive(task.id)
            _state.update { it.copy(activeTaskId = task.id, consecutiveSkips = 0) }

            // Auto-trigger 1: notify guardian task started
            GuardianNotifier.notifyTaskStarted(context, task.subject, task.topic, task.durationMinutes)
        }
    }

    fun markDone(context: Context, task: StudyTask) {
        viewModelScope.launch {
            AttentionCycleService.stop(context)
            WorkModeManager.deactivate(context)
            PlanStore.markTask(task.id, TaskState.DONE)
            PsycheEngine.onTaskCompleted(task)
            CheckmateTTS.speak(context, "Task complete. Well done.")
            val msg = PsycheEngine.getDailyMorningMessage()
            _state.update { it.copy(activeTaskId = null, psycheMessage = msg, consecutiveSkips = 0) }
        }
    }

    fun markSkip(context: Context, task: StudyTask) {
        viewModelScope.launch {
            AttentionCycleService.stop(context)
            WorkModeManager.deactivate(context)
            PlanStore.markTask(task.id, TaskState.SKIPPED)
            PsycheEngine.onTaskSkipped(task)
            val msg = PsycheEngine.getSkipReaction(task)
            CheckmateTTS.speak(context, msg)
            val newSkips = _state.value.consecutiveSkips + 1
            _state.update { it.copy(activeTaskId = null, psycheMessage = msg, consecutiveSkips = newSkips) }

            // Auto-trigger 2: guardian alert after 3 consecutive skips
            if (newSkips >= 3) {
                GuardianNotifier.notifySkipStreak(context, newSkips, "${task.subject}: ${task.topic}")
            }
        }
    }

    fun pauseTask(context: Context, task: StudyTask) {
        viewModelScope.launch {
            PlanStore.pauseTask(task.id, System.currentTimeMillis())
            AttentionCycleService.sendPause(context)
        }
    }

    fun resumeTask(context: Context, task: StudyTask) {
        viewModelScope.launch {
            PlanStore.resumeTask(task.id, System.currentTimeMillis())
            AttentionCycleService.sendResume(context)
        }
    }
}
