package com.checkmate

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.checkmate.core.CheckmatePrefs
import com.checkmate.core.CheckmateState
import com.checkmate.planner.intervention.InterventionReconciliation
import com.checkmate.service.AttentionCycleService
import com.checkmate.service.GuardianNotifier
import com.checkmate.service.ReminderService
import com.checkmate.service.TelegramAlertBot
import com.checkmate.workmode.WorkModeManager
import com.checkmate.workmode.WorkModeScheduleReceiver

/**
 * BootReceiver — AlarmManager's repeating alarms (EOD summary, 30-min usage
 * reports, weekly report) are cancelled on reboot; this puts them back so
 * guardian reporting survives a restart instead of silently going quiet.
 * ReminderService (plain foreground Service, not WorkManager) and Work
 * Mode's schedule get the same re-arming for the same reason — this is
 * the self-heal pass: everything on-device that a reboot silently clears
 * gets put back the moment BOOT_COMPLETED fires.
 *
 * Also fires a one-time "device rebooted" note to the guardian when the
 * reboot landed in Safe Mode — Safe Mode disables Checkmate's accessibility
 * watchdog and (on some OEMs) the device admin lock entirely, which is the
 * one uninstall path this app genuinely cannot block. Surfacing it is the
 * next best thing: the guardian at least finds out it happened. The same
 * applies, independent of Safe Mode, to the accessibility watchdog itself:
 * see the ENABLED_ACCESSIBILITY_SERVICES check below — Android never lets
 * an app re-enable its own accessibility service, so if it was off before
 * the reboot it's still off after, and that can't be fixed from code, only
 * reported.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return

        CheckmatePrefs.init(context)
        CheckmateState.init(context)
        GuardianNotifier.scheduleEndOfDaySummary(context)
        GuardianNotifier.scheduleUsageReports(context)
        // Previously missing — the weekly report alarm was never scheduled
        // anywhere, including here, which was part of why it never fired.
        GuardianNotifier.scheduleWeeklyReport(context)

        // Re-arm Work Mode's hardcoded schedule: reconcile immediately (in
        // case the reboot landed mid-window) and re-register the four daily
        // boundary alarms, since AlarmManager repeating alarms are cleared
        // on reboot just like the guardian-reporting ones above.
        WorkModeManager.init(context)
        WorkModeScheduleReceiver.scheduleDailyAlarms(context)
        Log.d("BootReceiver", "Guardian alarms + Work Mode schedule rescheduled after boot")

        // Previously missing — ReminderService is a plain foreground Service, not
        // WorkManager, so (like the AlarmManager schedules above, unlike
        // InterventionTriggerScheduler's periodic work) it does not survive a reboot
        // on its own. Nothing was restarting it here, which meant the entire 15-min
        // loop (checkPendingTasks, ProactiveMentor's idle/consistency/holiday checks,
        // GapTaskManager's daily generation + escalation) silently stopped firing
        // after every reboot.
        ReminderService.start(context)

        // Same problem as ReminderService above, for the focus-session timer: if a
        // reboot lands mid-session, AttentionCycleService.KEY_ACTIVE_TASK_ID is still
        // set (only cleared on a normal end — see that service's onDestroy), so this
        // relaunches the same task fresh rather than leaving the student's session
        // silently gone with no timer and no notification. No-op if no session was
        // running when the device went down.
        AttentionCycleService.resumeAfterRebootIfNeeded(context)

        // Proactive Execution Engine (step 7): sweep any InterventionTransaction left
        // non-terminal by the process that just died (Blueprint §4). No equivalent call
        // is needed for InterventionTriggerScheduler's periodic work here — WorkManager
        // persists and reschedules that itself after reboot, unlike the AlarmManager
        // schedules above.
        InterventionReconciliation.runAtStartup(context)

        val inSafeMode = context.packageManager.isSafeMode
        if (inSafeMode && TelegramAlertBot.getChatId() != null) {
            Thread {
                GuardianNotifier.notifySafeModeBoot(context)
            }.start()
        }

        // Self-heal check for the accessibility watchdog: this can only ever be a
        // *report*, not a fix — see the class doc above for why no app, this one
        // included, can flip its own accessibility service back on after a reboot.
        // Reading it here still closes most of the gap in practice, since on real
        // devices ENABLED_ACCESSIBILITY_SERVICES already reflects the post-reboot
        // state by the time BOOT_COMPLETED fires (accessibility services are core
        // services the OS starts before third-party BOOT_COMPLETED receivers run).
        if (!isAccessibilityWatchdogEnabled(context) && TelegramAlertBot.getChatId() != null) {
            Thread {
                GuardianNotifier.notifyAccessibilityWatchdogDisabled(context)
            }.start()
        }
    }

    /** True if AppAutomationService is present in the OS's enabled-accessibility-services list. */
    private fun isAccessibilityWatchdogEnabled(context: Context): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val serviceId = "${context.packageName}/com.checkmate.automation.AppAutomationService"
        return enabled.split(':').any { it.equals(serviceId, ignoreCase = true) }
    }
}
