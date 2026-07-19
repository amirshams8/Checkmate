package com.checkmate.ui.home

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.app.Activity
import com.checkmate.core.AttentionCycleManager
import com.checkmate.core.CheckmatePrefs
import com.checkmate.core.tts.CheckmateTTS
import com.checkmate.planner.PlanStore
import com.checkmate.planner.model.StudyTask
import com.checkmate.planner.model.TaskState
import com.checkmate.psyche.PsycheEngine
import com.checkmate.service.AttentionCycleService
import com.checkmate.service.FloatingAttentionService
import com.checkmate.service.GuardianNotifier
import com.checkmate.service.ScreenCaptureManager
import com.checkmate.workmode.WorkModeManager
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class HomeState(
    val tasks:             List<StudyTask> = emptyList(),
    val activeTaskId:      String?         = null,
    val completedToday:    Int             = 0,
    val streakDays:        Int             = 0,
    val psycheMessage:     String          = "",
    val consecutiveSkips:  Int             = 0,
    // ── Blueprint 10.1: Intention Declaration + Session Check-In ──
    // Non-null exactly while the corresponding dialog should be showing on HomeScreen.
    val intentionPromptTask:  StudyTask?   = null,
    val completionPromptTask: StudyTask?   = null
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

    /**
     * Adds a manually-created task to today's plan.
     * Uses PlanStore.addCustomTask() which is additive only — it appends onto
     * whatever is already in today's list (AI-generated or previously added custom
     * tasks) and never overwrites/clears them. isCustom = true is what later lets
     * HomeScreen show the duration-edit affordance only for tasks added this way.
     * The StudyTask is otherwise identical in shape to a generated one, so it
     * automatically renders in the same TaskCard, drives AttentionCycleService/
     * WorkModeManager the same way via startTask(), and triggers the same
     * GuardianNotifier WhatsApp "task started" ping plus the same end-of-day
     * WhatsApp/Telegram report — no extra wiring required.
     */
    fun addCustomTask(context: Context, subject: String, topic: String, durationMinutes: Int) {
        val cleanSubject  = subject.trim()
        val cleanTopic    = topic.trim()
        val cleanDuration = durationMinutes.coerceIn(5, 240)
        if (cleanSubject.isBlank() || cleanTopic.isBlank()) return

        val task = StudyTask(
            subject         = cleanSubject,
            topic           = cleanTopic,
            durationMinutes = cleanDuration,
            isCustom        = true
        )
        PlanStore.addCustomTask(task)
        CheckmateTTS.speak(context, "Custom task added. $cleanSubject, $cleanTopic, $cleanDuration minutes.")
    }

    /**
     * Edits the duration of a custom task. Only allowed for tasks created via
     * addCustomTask (isCustom = true) and only while still PENDING — once a task
     * is ACTIVE/PAUSED its timer has already started inside AttentionCycleService,
     * so changing durationMinutes underneath it would desync the running countdown.
     * Generated (AI-planned) tasks are intentionally left untouched here — their
     * duration comes from AdaptivePlanner's PYQ-weighted scheduling logic.
     */
    fun editTaskDuration(task: StudyTask, newDurationMinutes: Int) {
        if (!task.isCustom || task.state != TaskState.PENDING) return
        val clamped = newDurationMinutes.coerceIn(5, 240)
        PlanStore.updateTaskDuration(task.id, clamped)
    }

    /** Removes a task the student added by mistake. Generated tasks can be removed the same way. */
    fun removeTask(task: StudyTask) {
        if (task.state == TaskState.ACTIVE || task.state == TaskState.PAUSED) return
        PlanStore.removeTask(task.id)
    }

    /**
     * Blueprint 10.1: Intention Declaration.
     * Tapping "Start Now" no longer launches the session directly — it first
     * surfaces the "What will you study?" prompt on HomeScreen via
     * intentionPromptTask. The actual launch (media projection check, service
     * starts, WorkMode activation, etc.) is deferred to
     * confirmIntentionAndStart() once the student answers.
     */
    fun startTask(context: Context, task: StudyTask) {
        _state.update { it.copy(intentionPromptTask = task) }
    }

    fun dismissIntentionPrompt() {
        _state.update { it.copy(intentionPromptTask = null) }
    }

    /** Called from HomeScreen's IntentionDialog once the student confirms their intention. */
    fun confirmIntentionAndStart(context: Context, task: StudyTask, intentionText: String) {
        val clean = intentionText.trim().ifBlank { task.topic }
        PlanStore.setIntention(task.id, clean)
        _state.update { it.copy(intentionPromptTask = null) }
        if (ScreenCaptureManager.isReady()) {
            launchTask(context, task)
        } else {
            pendingTask = task
            _requestProjection.tryEmit(task)
        }
    }

    /**
     * Called by MainActivity after user approves the MediaProjection dialog.
     * resultCode + data are forwarded into AttentionCycleService's start intent so that
     * getMediaProjection() is called only AFTER startForeground() runs inside the service.
     * Fix for: SecurityException: Media projections require a foreground service of type
     *          ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
     */
    fun onProjectionGranted(context: Context, resultCode: Int, data: Intent?) {
        val task = pendingTask ?: return
        pendingTask = null
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

    /**
     * Blueprint 10.1: Session Check-In.
     * Replaces the old direct call to markDone() from the "Done" button —
     * surfaces the "Did you finish it?" prompt on HomeScreen via
     * completionPromptTask first. The actual completion (service stop, state
     * update, TTS) happens in confirmCompletion() once the student answers.
     */
    fun requestCompletion(context: Context, task: StudyTask) {
        _state.update { it.copy(completionPromptTask = task) }
    }

    fun dismissCompletionPrompt() {
        _state.update { it.copy(completionPromptTask = null) }
    }

    /**
     * status is one of "YES" | "PARTIAL" | "NO" — a self-report of whether the
     * declared intention was met. The task is still marked DONE either way
     * (the session ended); completedStatus is a separate accountability signal
     * (surfaced later in Stats / the weekly guardian report), not a change to
     * task state.
     */
    fun confirmCompletion(context: Context, task: StudyTask, status: String) {
        viewModelScope.launch {
            // BUGFIX (carried over): read attention-check counts BEFORE stopping the service —
            // AttentionCycleService.stop() triggers onDestroy() -> AttentionCycleManager.reset(),
            // which zeroes checksPassed/checksMissed. Read too late (or not at all) and
            // PsycheEngine.onTaskCompleted() always records 0/0 into BehaviorLedger regardless
            // of how many attention checks the student actually confirmed.
            val cycleState = AttentionCycleManager.currentState()
            AttentionCycleService.stop(context)
            FloatingAttentionService.stop(context)
            WorkModeManager.deactivate(context)
            ScreenCaptureManager.release()
            PlanStore.markTask(task.id, TaskState.DONE)
            PlanStore.setCompletionStatus(task.id, status)
            PsycheEngine.onTaskCompleted(task, cycleState.checksPassed, cycleState.checksMissed)
            val spoken = when (status) {
                "NO"      -> "Noted. Every session still counts — reset for the next one."
                "PARTIAL" -> "Task marked complete. Partial progress logged."
                else      -> "Task complete. Well done."
            }
            CheckmateTTS.speak(context, spoken)
            val msg = PsycheEngine.getDailyMorningMessage()
            _state.update { it.copy(
                activeTaskId         = null,
                psycheMessage        = msg,
                consecutiveSkips     = 0,
                completionPromptTask = null
            ) }
        }
    }

    fun markSkip(context: Context, task: StudyTask) {
        viewModelScope.launch {
            // Same fix as confirmCompletion() — capture before stop()/reset() wipes the counts.
            val cycleState = AttentionCycleManager.currentState()
            AttentionCycleService.stop(context)
            FloatingAttentionService.stop(context)
            WorkModeManager.deactivate(context)
            ScreenCaptureManager.release()
            PlanStore.markTask(task.id, TaskState.SKIPPED)
            PsycheEngine.onTaskSkipped(task, cycleState.checksPassed, cycleState.checksMissed)
            val msg = PsycheEngine.getSkipReaction(task)
            CheckmateTTS.speak(context, msg)
            val newSkips = _state.value.consecutiveSkips + 1
            _state.update { it.copy(activeTaskId = null, psycheMessage = msg, consecutiveSkips = newSkips) }
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
