package com.checkmate

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.checkmate.core.CheckmatePrefs
import com.checkmate.service.GuardianNotifier
import com.checkmate.service.TelegramAlertBot

/**
 * BootReceiver — AlarmManager's repeating alarms (EOD summary, 30-min usage
 * reports) are cancelled on reboot; this puts them back so guardian
 * reporting survives a restart instead of silently going quiet.
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
        GuardianNotifier.scheduleEndOfDaySummary(context)
        GuardianNotifier.scheduleUsageReports(context)
        Log.d("BootReceiver", "Guardian alarms rescheduled after boot")

        val inSafeMode = context.packageManager.isSafeMode
        if (inSafeMode && TelegramAlertBot.getChatId() != null) {
            Thread {
                GuardianNotifier.notifySafeModeBoot(context)
            }.start()
        }
    }
}
