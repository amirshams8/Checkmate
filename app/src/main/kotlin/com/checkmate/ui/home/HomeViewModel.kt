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
import com.checkmate.workmode.WorkModeManager
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class HomeState(
    val tasks:          List<StudyTask> = emptyList(),
    val activeTaskId:   String?         = null,
    val completedToday: Int             = 0,
    val streakDays:     Int             = 0,
    val psycheMessage:  String          = ""
)

class HomeViewModel : ViewModel() {

    private val _state = MutableStateFlow(HomeState())
    val state: StateFlow<HomeState> = _state.asStateFlow()

    init {
        loadTodayPlan()
        loadStreak()
        loadPsycheMessage()
    }

    private fun loadTodayPlan() {
        viewModelScope.launch {
            PlanStore.todayTasks.collect { tasks ->
                _state.update { it.copy(
                    tasks          = tasks,
                    completedToday = tasks.count { t -> t.state == TaskState.DONE }
                )}
            }
        }
    }

    private fun loadStreak() {
        viewModelScope.launch {
            val streak = PlanStore.getStreakDays()
            _state.update { it.copy(streakDays = streak) }
        }
    }

    private fun loadPsycheMessage() {
        viewModelScope.launch {
            val msg = PsycheEngine.getDailyMorningMessage()
            _state.update { it.copy(psycheMessage = msg) }
        }
    }

    fun startTask(context: Context, task: StudyTask) {
        viewModelScope.launch {
            // Activate Work Mode
            WorkModeManager.activate(context)

            // Open mapped app if set
            val mappedPkg = CheckmatePrefs.getString("app_map_${task.subject}", null)
            mappedPkg?.let { pkg ->
                val launchIntent = context.packageManager.getLaunchIntentForPackage(pkg)
                launchIntent?.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                launchIntent?.let { context.startActivity(it) }
            }

            // Start attention cycle service
            AttentionCycleService.start(context, task.id, task.topic, task.durationMinutes.toLong())

            // TTS
            CheckmateTTS.speak(context, "Starting ${task.subject}. Focus for ${task.durationMinutes} minutes.")

            // Update state
            PlanStore.setTaskActive(task.id)
            _state.update { it.copy(activeTaskId = task.id) }
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
            _state.update { it.copy(activeTaskId = null, psycheMessage = msg) }
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
            _state.update { it.copy(activeTaskId = null, psycheMessage = msg) }
        }
    }
}
