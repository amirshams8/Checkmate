package com.checkmate.ui.home

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.checkmate.core.CheckmatePrefs
import com.checkmate.core.StudyTask
import com.checkmate.core.TaskState
import com.checkmate.core.tts.CheckmateTTS
import com.checkmate.planner.PlanStore
import com.checkmate.service.AttentionCycleService
import com.checkmate.service.FloatingAttentionService
import com.checkmate.service.GuardianNotifier
import com.checkmate.service.ScreenCaptureManager
import com.checkmate.workmode.WorkModeManager
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import android.app.Activity

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

    // MainActivity observes this to launch the MediaProjection system dialog
    private val _requestProjection = MutableSharedFlow<StudyTask>(extraBufferCapacity = 1)
    val requestProjection: SharedFlow<StudyTask> = _requestProjection.asSharedFlow()

    private var pendingTask: StudyTask? = null

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
        if (ScreenCaptureManager.isReady()) {
            launchTask(context, task)
        } else {
            pendingTask = task
            _requestProjection.tryEmit(task)
        }
    }

    /**
     * Called by MainActivity after user approves the MediaProjection dialog.
     * We forward the raw resultCode + data into AttentionCycleService's start intent,
     * so getMediaProjection() is called only after startForeground() has run inside the service.
     * This is the correct fix for:
     *   SecurityException: Media projections require a foreground service of type
     *   ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
     */
    fun onProjectionGranted(context: Context, resultCode: Int, data: Intent?) {
        val task = pendingTask ?: return
        pendingTask = null
        // Start the FGS with the projection token baked into the intent.
        // AttentionCycleService.onStartCommand() calls startForeground() first,
        // then immediately calls ScreenCaptureManager.storeProjectionToken() — safe.
        AttentionCycleService.start(
            context,
            task.id,
            task.topic,
            task.durationMinutes.toLong(),
            projectionResultCode = resultCode,
            projectionData       = data
        )
        launchTask(context, task, serviceAlreadyStarted = true)
    }

    fun onProjectionDenied(context: Context) {
        val task = pendingTask ?: return
        pendingTask = null
        launchTask(context, task)
    }

    private fun launchTask(context: Context, task: StudyTask, serviceAlreadyStarted: Boolean = false) {
        viewModelScope.launch {
            WorkModeManager.activate(context)
            val mappedPkg = CheckmatePrefs.getString("app_map_${task.subject}", null)
            mappedPkg?.let { pkg ->
                context.packageManager.getLaunchIntentForPackage(pkg)
                    ?.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    ?.let { context.startActivity(it) }
            }
            if (!serviceAlreadyStarted) {
                AttentionCycleService.start(context, task.id, task.topic, task.durationMinutes.toLong())
            }
            FloatingAttentionService.start(context)
            CheckmateTTS.speak(context, "Starting ${task.subject}. Focus for ${task.durationMinutes} minutes.")
            PlanStore.setTaskActive(task.id)
            _state.update { it.copy(activeTaskId = task.id, consecutiveSkips = 0) }
            GuardianNotifier.notifyTaskStarted(context, task.subject, task.topic, task.durationMinutes)
        }
    }

    fun skipTask(context: Context, taskId: String) {
        viewModelScope.launch {
            PlanStore.setTaskSkipped(taskId)
            val skips = (_state.value.consecutiveSkips + 1).also {
                _state.update { s -> s.copy(consecutiveSkips = it) }
            }
            GuardianNotifier.notifySkip(context, skips)
            AttentionCycleService.stop(context)
        }
    }

    fun completeTask(context: Context, taskId: String) {
        viewModelScope.launch {
            PlanStore.setTaskDone(taskId)
            _state.update { it.copy(activeTaskId = null, consecutiveSkips = 0) }
            GuardianNotifier.notifyTaskDone(context)
            AttentionCycleService.stop(context)
        }
    }
}
