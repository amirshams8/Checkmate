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
                    completedToday = tasks.count { t -> t.state == TaskState.DONE },
                    activeTaskId   = tasks.firstOrNull { t -> t.state == TaskState.ACTIVE || t.state == TaskState.PAUSED }?.id
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
            WorkModeManager.activate(context)
            val mappedPkg = CheckmatePrefs.getString("app_map_${task.subject}", null)
            mappedPkg?.let { pkg ->
                val launchIntent = context.packageManager.getLaunchIntentForPackage(pkg)
                launchIntent?.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                launchIntent?.let { context.startActivity(it) }
            }
            AttentionCycleService.start(context, task.id, task.topic, task.durationMinutes.toLong())
            CheckmateTTS.speak(context, "Starting ${task.subject}. Focus for ${task.durationMinutes} minutes.")
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

    /** Blueprint 2.5: Pause the active task */
    fun pauseTask(context: Context, task: StudyTask) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            PlanStore.pauseTask(task.id, now)
            AttentionCycleService.sendPause(context)
            _state.update { it.copy(activeTaskId = task.id) } // keep it active but paused
        }
    }

    /** Blueprint 2.5: Resume a paused task */
    fun resumeTask(context: Context, task: StudyTask) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            PlanStore.resumeTask(task.id, now)
            AttentionCycleService.sendResume(context)
        }
    }
}
