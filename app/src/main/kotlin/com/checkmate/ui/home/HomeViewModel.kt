package com.checkmate.ui.home

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.app.Activity
import com.checkmate.core.AppUsageTracker
import com.checkmate.core.AttentionCycleManager
import com.checkmate.core.CheckmatePrefs
import com.checkmate.core.ConsultationProfile
import com.checkmate.core.TodayContext
import com.checkmate.core.tts.CheckmateTTS
import com.checkmate.planner.FreeSlotCalculator
import com.checkmate.planner.PlanStore
import com.checkmate.planner.intervention.GapTaskLedger
import com.checkmate.planner.intervention.RetentionTaskLedger
import com.checkmate.planner.model.StudyTask
import com.checkmate.planner.model.TaskState
import com.checkmate.planner.model.TaskType
import com.checkmate.psyche.PsycheEngine
import com.checkmate.service.AttentionCycleService
import com.checkmate.service.FloatingAttentionService
import com.checkmate.service.GapTaskManager
import com.checkmate.service.GuardianNotifier
import com.checkmate.service.ProactiveMentor
import com.checkmate.service.ScreenCaptureManager
import com.checkmate.service.TaskSyncManager
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
    val completionPromptTask: StudyTask?   = null,
    // ── Task Sync (two-device) ── true only while a manual sync tap is in flight.
    val syncEnabled:        Boolean        = false,
    val syncing:            Boolean        = false,
    // ── P0b (BUGFIX: r1-result-on-r2-screen, background-loop case) ──
    // Snapshot of GapTaskLedger's active-concept P0b state, refreshed every time
    // GapTaskLedger.version changes — see that field's own doc for why reading the
    // ledger straight from inside HomeScreen's @Composable body isn't enough on its
    // own. null/null means no gap-task is currently mid-round (nothing to show a
    // repair-test button for).
    val activeGapTaskId:     String?        = null,
    val activeRepairSessionId: String?      = null,
    // Retention-check evidence loop (next-session-retention-loop.txt): unlike the single
    // active gap-repair slot above, several RETENTION CHECK tasks can have their own
    // outstanding Testmate session at once — see RetentionTaskLedger's own class doc for
    // why this is keyed by taskId instead of one active-session field. Only entries whose
    // session has actually been created are included (a task still waiting on
    // RetentionCheckManager.createRetentionTestsIfNeeded has no sessionId yet).
    val activeRetentionSessions: Map<String, String> = emptyMap()
)

class HomeViewModel : ViewModel() {

    private val _state = MutableStateFlow(HomeState())
    val state: StateFlow<HomeState> = _state.asStateFlow()

    // MainActivity observes this to launch the MediaProjection system dialog
    private val _requestProjection = MutableSharedFlow<StudyTask>(extraBufferCapacity = 1)
    val requestProjection: SharedFlow<StudyTask> = _requestProjection.asSharedFlow()

    private var pendingTask: StudyTask? = null

    init {
        _state.update { it.copy(syncEnabled = TaskSyncManager.isEnabled()) }
        loadTodayPlan(); loadStreak(); loadPsycheMessage(); loadGapTaskSession()
        loadRetentionSessions()
        pullSync()
    }

    /**
     * Every emission of PlanStore.todayTasks — i.e. every local task mutation, since
     * all of PlanStore's writes funnel through the same StateFlow — also pushes today's
     * plan to TaskSyncManager. This is the single hook point for auto-sync: add/remove/
     * edit-duration/schedule/start/pause/resume/done/skip all end up here without
     * needing a push call added at each individual call site. TaskSyncManager itself
     * no-ops instantly if no sync code is configured, so this is free on a device that
     * hasn't opted into sync. Runs on a plain background thread (not viewModelScope),
     * same convention as StatusReporter/GuardianNotifier elsewhere in the app, since
     * it's fire-and-forget and outlives any single Compose recomposition anyway.
     */
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
                if (TaskSyncManager.isEnabled()) {
                    Thread { TaskSyncManager.pushTasks(tasks) }.start()
                }
            }
        }
    }

    /**
     * Pulls the other device's copy of today's plan if it's newer, silently, once —
     * called from init so opening the app on either device picks up whatever the
     * other one last did. See syncNow() for the manual on-demand version (HomeHeader's
     * sync button), which is the only way to re-check without relaunching the screen.
     */
    private fun pullSync() {
        if (!TaskSyncManager.isEnabled()) return
        Thread {
            val remote = TaskSyncManager.pullTasksIfNewer(PlanStore.getLastUpdatedAt())
            if (remote != null) PlanStore.saveTodayTasks(remote)
        }.start()
    }

    /** Manual sync trigger for HomeHeader's sync icon — same pull as init, plus a brief spinner. */
    fun syncNow() {
        if (!TaskSyncManager.isEnabled()) return
        _state.update { it.copy(syncing = true) }
        Thread {
            val remote = TaskSyncManager.pullTasksIfNewer(PlanStore.getLastUpdatedAt())
            if (remote != null) PlanStore.saveTodayTasks(remote)
            _state.update { it.copy(syncing = false) }
        }.start()
    }

    private fun loadStreak() {
        viewModelScope.launch { _state.update { it.copy(streakDays = PlanStore.getStreakDays()) } }
    }

    /**
     * BUGFIX (r1-result-on-r2-screen, background-loop case): GapTaskLedger's P0b session
     * fields are CheckmatePrefs-backed with no observable of their own, so ReminderService's
     * background loop (resolveDoneConcept's low-mastery branch -> resetForNextRound, then
     * createTargetedTestIfNeeded -> recordTestmateSession) used to update them with nothing
     * telling Compose to notice — see GapTaskLedger.version's own doc for the full repro.
     * Collecting that version counter here and re-reading the ledger's live accessors into
     * HomeState on every emission is what makes those background writes actually reach
     * HomeScreen, the same way PlanStore.todayTasks already does for task mutations.
     * GapTaskLedger.version starts at 0 and this collect fires once immediately (StateFlow
     * always replays its current value to a new collector), so the very first snapshot is
     * populated without waiting for any actual change.
     */
    private fun loadGapTaskSession() {
        viewModelScope.launch {
            GapTaskLedger.version.collect {
                _state.update { it.copy(
                    activeGapTaskId        = GapTaskLedger.activeTaskId(),
                    activeRepairSessionId  = GapTaskLedger.activeTestmateSessionId()
                )}
            }
        }
    }

    /**
     * Retention-check evidence loop (next-session-retention-loop.txt): same "CheckmatePrefs
     * write has no observable of its own, so collect the ledger's version counter and
     * re-snapshot into Compose state" shape [loadGapTaskSession] already uses for
     * GapTaskLedger, applied to [RetentionTaskLedger] instead. RetentionCheckManager runs
     * from ReminderService's background loop, entirely outside Compose, so without this
     * collector HomeScreen would never notice a retention session becoming available to take.
     */
    private fun loadRetentionSessions() {
        viewModelScope.launch {
            RetentionTaskLedger.version.collect {
                // pendingEvidence() is exactly "has a session, evidence not imported yet" —
                // pendingSessionCreation() entries never have a sessionId by definition, so
                // there's nothing to add from that list here.
                val sessions = RetentionTaskLedger.pendingEvidence()
                    .mapNotNull { entry -> entry.testmateSessionId?.let { entry.taskId to it } }
                    .toMap()
                _state.update { it.copy(activeRetentionSessions = sessions) }
            }
        }
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
     *
     * scheduledStartTime is now resolved via findNextFreeSlot() instead of being left
     * null — previously every custom task landed in Timeline view's "Unscheduled"
     * section unconditionally, since only AdaptivePlanner's generated plan ever ran
     * assignScheduledTimes(). A custom task that genuinely doesn't fit anywhere in
     * today's free time still comes back null and still falls into "Unscheduled" —
     * that part is unchanged.
     */
    fun addCustomTask(context: Context, subject: String, topic: String, durationMinutes: Int, taskType: TaskType = TaskType.OTHER) {
        val cleanSubject  = subject.trim()
        val cleanTopic    = topic.trim()
        val cleanDuration = durationMinutes.coerceIn(5, 240)
        if (cleanSubject.isBlank() || cleanTopic.isBlank()) return

        val task = StudyTask(
            subject            = cleanSubject,
            topic              = cleanTopic,
            durationMinutes    = cleanDuration,
            isCustom           = true,
            taskType           = taskType,
            scheduledStartTime = findNextFreeSlot(cleanDuration)
        )
        PlanStore.addCustomTask(task)
        CheckmateTTS.speak(context, "Custom task added. $cleanSubject, $cleanTopic, $cleanDuration minutes.")
    }

    /**
     * Mirrors AdaptivePlanner.assignScheduledTimes() for a single manually-added task:
     * today's free slots (study window minus ConsultationProfile.blockedSlots) minus
     * whatever today's plan has already scheduled, first-fit. Reads study_start/study_end
     * straight from CheckmatePrefs — the same keys PlannerViewModel persists them under —
     * so this doesn't need the Plan screen's ViewModel in scope. Returns null when nothing
     * today has room, same as the generated-plan path.
     */
    private fun findNextFreeSlot(durationMinutes: Int): String? {
        val studyStart = CheckmatePrefs.getString("study_start", "06:00") ?: "06:00"
        val studyEnd   = CheckmatePrefs.getString("study_end", "22:00") ?: "22:00"
        val profile    = ConsultationProfile.load()

        val freeSlots = FreeSlotCalculator.computeFreeSlots(profile.blockedSlots, studyStart, studyEnd)
        val occupied  = PlanStore.todayTasks.value.mapNotNull { t ->
            val start = t.scheduledStartTime ?: return@mapNotNull null
            val startMinute = FreeSlotCalculator.parseTimeOrNull(start) ?: return@mapNotNull null
            startMinute to (startMinute + t.durationMinutes)
        }
        val remaining = FreeSlotCalculator.subtractOccupied(freeSlots, occupied)
        return FreeSlotCalculator.firstFitStart(durationMinutes, remaining)
            ?.let { FreeSlotCalculator.formatMinutes(it) }
    }

    /**
     * Manually assigns/edits a task's scheduledStartTime — the "Schedule" action on an
     * Unscheduled TaskCard in Timeline view. Available for both custom and generated
     * tasks: a generated task can end up unscheduled too when the day's plan doesn't fit
     * today's free time (see AdaptivePlanner.assignScheduledTimes), and until now there
     * was no way to fix that from HomeScreen except deleting and re-adding it. Same
     * PENDING-only guard as editTaskDuration/removeTask — once a task is running its
     * position on the timeline isn't meaningful to change.
     */
    fun scheduleTask(task: StudyTask, hhmm: String) {
        if (task.state != TaskState.PENDING) return
        PlanStore.updateTaskSchedule(task.id, hhmm)
    }

    /**
     * Sets a task's start AND end time directly — the clock-icon action on TaskCard,
     * available on both List and Timeline views for any PENDING task (scheduled or not).
     * endHHmm must be strictly after startHHmm (same-day only, no overnight wraparound);
     * durationMinutes is derived from the difference so it and scheduledStartTime can
     * never drift apart from each other. Same PENDING-only guard as scheduleTask() /
     * editTaskDuration() — a task that's already ACTIVE/PAUSED has a running timer that
     * shouldn't be resliced underneath it.
     */
    fun rescheduleTask(task: StudyTask, startHHmm: String, endHHmm: String) {
        if (task.state != TaskState.PENDING) return
        val startMinute = FreeSlotCalculator.parseTimeOrNull(startHHmm) ?: return
        val endMinute   = FreeSlotCalculator.parseTimeOrNull(endHHmm) ?: return
        val duration    = endMinute - startMinute
        if (duration <= 0) return
        PlanStore.updateTaskScheduleAndDuration(task.id, startHHmm, duration)
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
            // BUGFIX (r1-result-on-r2-screen, mark-done race): GapTaskManager's
            // resolveDoneConcept/resetForNextRound/createTargetedTestIfNeeded chain — the
            // whole thing that advances a gap concept's P0b round and swaps in a fresh
            // Testmate session — used to run ONLY from ReminderService's 15-minute
            // background loop. Nothing on this "Done" path ever called into
            // GapTaskManager, so marking a gap-concept review task DONE here left
            // GapTaskLedger's active session pointer exactly where it was (still the OLD,
            // already-completed round's session) for up to 15 minutes. HomeScreen's
            // "Take repair test" button (see HomeScreen's repairSessionFor) reads that
            // same ledger value on every recomposition with no freshness check of its
            // own, so tapping it during that window opened the stale prior-round session
            // and landed on its already-submitted result page — the identical symptom to
            // the round-2-redirects-to-round-1's-result bug, just reached via a timing
            // gap instead of the conceptId-vs-taskId matching bug that caused it
            // originally. Calling generateIfNeeded() here — the same call
            // ReminderService's loop makes — runs resolveActiveConceptState (which
            // resolves this task now that it's DONE) immediately followed by
            // createTargetedTestIfNeeded (which requests the next round's session) in the
            // same synchronous pass, so by the time this suspend call returns and the
            // screen recomposes, GapTaskLedger's active session pointer is already
            // whatever it should be — never a stale one left for the background loop to
            // catch up on later. Best-effort/non-fatal like every other call site of this
            // function (see ReminderService): a failure here just means this particular
            // gap concept waits for the next 15-minute cycle, same as before this fix,
            // not a broken completion flow.
            try { GapTaskManager.generateIfNeeded(context) } catch (_: Exception) {}
            PlanStore.setCompletionStatus(task.id, status)
            // BUGFIX (Blueprint 2.6): cycleState.totalSessionSeconds is the tick loop's real
            // elapsed time — it freezes while PAUSED (see AttentionCycleManager.tick()), so
            // this is genuinely "focus time, not wall clock." Previously nothing ever wrote it
            // onto the task, so StudyTask.focusMinutes/actualMinutes — and everything downstream
            // that reads them (BehaviorLedger's "Avg Focus" stat, Stats' "Focus Today") — always
            // saw 0 regardless of how long the student actually focused.
            val focusMin = (cycleState.totalSessionSeconds / 60).toInt().coerceAtLeast(0)
            PlanStore.setFocusMinutes(task.id, focusMin)
            val completedTask = task.copy(focusMinutes = focusMin, actualMinutes = focusMin)
            PsycheEngine.onTaskCompleted(completedTask, cycleState.checksPassed, cycleState.checksMissed)
            // Mentor v2 (spec 3.7): log the completion into TodayContext so AdaptivePlanner
            // sees what's actually been finished today, then refresh the cached summary
            // AdaptivePlanner reads (previously-dead "behavior_summary" pref — see PsycheEngine).
            TodayContext.appendUpdate("Completed ${task.subject}: ${task.topic} (${task.taskType})")
            PsycheEngine.refreshBehaviorSummaryCache()
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
            // BUGFIX (Blueprint 2.6): same focus-time fix as confirmCompletion() — a skipped
            // session still did real focus minutes before the skip, worth recording rather
            // than leaving at the StudyTask default of 0.
            val focusMin = (cycleState.totalSessionSeconds / 60).toInt().coerceAtLeast(0)
            PlanStore.setFocusMinutes(task.id, focusMin)
            val skippedTask = task.copy(focusMinutes = focusMin, actualMinutes = focusMin)
            // Mentor v2 (spec 3.6): capture whatever app was most recently foregrounded so the
            // skip event — and any guardian alert built from it — can name the actual cause.
            val distractionApp = try {
                AppUsageTracker.getMostRecentForegroundApp(context)
            } catch (_: Exception) { null }
            PsycheEngine.onTaskSkipped(skippedTask, cycleState.checksPassed, cycleState.checksMissed, distractionApp)
            // Mentor v2 (spec 3.7): keep the planner's cached behavior summary current on
            // skips too, not just completions, so a same-day regenerate reflects the skip.
            PsycheEngine.refreshBehaviorSummaryCache()
            // Mentor v2 (spec 3.4): opens the escalation-watchlist lockdown window — checked by
            // AppAutomationService alongside the normal blocklist for the configured duration.
            WorkModeManager.startPostSkipLockdown(context)
            val msg = PsycheEngine.getSkipReaction(task)
            CheckmateTTS.speak(context, msg)
            // Mentor v2 (spec 3.2): same reaction text, also logged into Mentor's persisted
            // chat so it's a running log, not just a spoken line that's gone once said.
            ProactiveMentor.onSkip(context, msg)
            val newSkips = _state.value.consecutiveSkips + 1
            _state.update { it.copy(activeTaskId = null, psycheMessage = msg, consecutiveSkips = newSkips) }
            if (newSkips >= 3) {
                GuardianNotifier.notifySkipStreak(context, newSkips, "${task.subject}: ${task.topic}", distractionApp)
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
