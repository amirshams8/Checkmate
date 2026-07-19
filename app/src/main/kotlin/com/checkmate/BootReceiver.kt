package com.checkmate

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.checkmate.core.CheckmatePrefs
import com.checkmate.core.CheckmateState
import com.checkmate.service.GuardianNotifier
import com.checkmate.service.TelegramAlertBot
import com.checkmate.workmode.WorkModeManager
import com.checkmate.workmode.WorkModeScheduleReceiver

/**
 * BootReceiver — AlarmManager's repeating alarms (EOD summary, 30-min usage
 * reports, weekly report) are cancelled on reboot; this puts them back so
 * guardian reporting survives a restart instead of silently going quiet.
 *
 * Also fires a one-time "device rebooted" note to the guardian when the
 * reboot landed in Safe Mode — Safe Mode disables Checkmate's accessibility
 * watchdog and (on some OEMs) the device admin lock entirely, which is the
 * one uninstall path this app genuinely cannot block. Surfacing it is the
 * next best thing: the guardian at least finds out it happened.
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

        val inSafeMode = context.packageManager.isSafeMode
        if (inSafeMode && TelegramAlertBot.getChatId() != null) {
            Thread {
                GuardianNotifier.notifySafeModeBoot(context)
            }.start()
        }
    }
}
-e
