package com.checkmate.service

import android.content.Context
import com.checkmate.planner.PlanStore
import com.checkmate.planner.model.TaskState
import com.checkmate.workmode.WorkModeManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach

/**
 * BUGFIX: notification-panel "Start" and cross-device task sync never activated
 * WorkMode.
 *
 * [WorkModeManager.activate] used to be called from exactly one place —
 * HomeViewModel.launchTask(), which only runs when the student taps Start inside
 * the app UI. Two other paths flip a task to [TaskState.ACTIVE] without ever going
 * through launchTask():
 *   1. The notification "Start" action — InterventionActionReceiver.handleStart()
 *      -> ActionExecutor -> PlanStoreTaskMutator.startTask() -> PlanStore.setTaskActive(),
 *      a pure data write with no side effects.
 *   2. Cross-device sync — HomeViewModel.pullSync()/syncNow() -> TaskSyncManager
 *      .pullTasksIfNewer() -> PlanStore.saveTodayTasks(remote), which can silently
 *      repaint a task as ACTIVE (started on the other device) without this device's
 *      WorkModeManager ever hearing about it.
 *
 * Rather than patching WorkModeManager.activate() calls into each of those call
 * sites individually (and risk missing the next one), this makes WorkMode a
 * function of task state: every task mutation, from any origin, already funnels
 * through PlanStore.todayTasks (see HomeViewModel.loadTodayPlan's own doc on that
 * guarantee), so subscribing here once covers all current and future start paths.
 *
 * Deliberately lives in `app`, not in :modules:workmode or :modules:planner — those
 * two modules don't depend on each other (:modules:workmode only depends on
 * :modules:core; :modules:planner never references workmode), and `app` is the only
 * module that sees both. Same cross-module wiring pattern as
 * InterventionNotificationBridge.gateway, set from CheckmateApp.onCreate.
 *
 * Deliberately additive, not a replacement for the existing direct
 * WorkModeManager.activate()/deactivate() calls in HomeViewModel's in-app Start/
 * Done/Skip flow: those calls run synchronously before the app-launch/service-start
 * work that follows them in the same function, and moving that onto this reactive
 * subscriber's timing would risk the target app or AttentionCycleService starting
 * before Work Mode is actually enforcing. This reconciler is the single source of
 * truth for the two paths that had no coverage at all; on the in-app path it's a
 * harmless, idempotent no-op alongside the direct call that already works.
 */
object WorkModeTaskReconciler {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    /** Call once from CheckmateApp.onCreate, after WorkModeManager.init(this). */
    fun start(context: Context) {
        PlanStore.todayTasks
            .map { tasks -> tasks.any { it.state == TaskState.ACTIVE || it.state == TaskState.PAUSED } }
            .distinctUntilChanged()
            .onEach { hasLiveTask -> reconcile(context, hasLiveTask) }
            .launchIn(scope)
    }

    private fun reconcile(context: Context, hasLiveTask: Boolean) {
        if (hasLiveTask) {
            if (!WorkModeManager.isActive.value) {
                WorkModeManager.activate(context, source = WorkModeManager.SOURCE_TASK)
            }
            return
        }

        // No live task. Only release Work Mode if WE were the one holding it open —
        // never stomp on a MANUAL guardian toggle or the hardcoded SCHEDULE window,
        // both of which are legitimately independent of any single task's state.
        // deactivate() itself still re-checks WorkModeSchedule and refuses to actually
        // turn off if the 19:00-02:00 window is live, same guard markDone/markSkip
        // already rely on.
        if (WorkModeManager.isActive.value && WorkModeManager.activeSource() == WorkModeManager.SOURCE_TASK) {
            WorkModeManager.deactivate(context)
        }
    }
}
