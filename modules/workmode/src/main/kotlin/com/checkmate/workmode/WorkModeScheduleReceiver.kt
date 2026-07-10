package com.checkmate.workmode

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.checkmate.core.CheckmatePrefs
import com.checkmate.core.CheckmateState
import java.util.Calendar

/**
 * Fires at the two hardcoded WorkModeSchedule boundaries (19:00 and 02:00,
 * every day) so the forced block window is reconciled even while the phone
 * is idle and Checkmate's UI/accessibility events aren't firing — the
 * accessibility-service-side check in AppAutomationService is the fast path
 * during active use, this is the fallback that survives the screen being off.
 *
 * Mirrors the exact alarm pattern GuardianNotifier already uses for the EOD
 * summary / usage reports (daily am.setRepeating at a fixed clock time), so
 * this needs no extra permissions beyond what's already declared.
 */
class WorkModeScheduleReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        // Guard against a cold process (e.g. alarm firing before the app
        // process has been started this boot) — both are idempotent no-ops
        // if already initialized.
        CheckmatePrefs.init(context)
        CheckmateState.init(context)
        WorkModeManager.evaluateSchedule(context)
    }

    companion object {
        private const val REQUEST_CODE_START = 4471
        private const val REQUEST_CODE_END   = 4472

        /** Registers (or re-registers) the two daily boundary alarms. Safe to call repeatedly. */
        fun scheduleDailyAlarms(context: Context) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

            val startPi = PendingIntent.getBroadcast(
                context, REQUEST_CODE_START,
                Intent(context, WorkModeScheduleReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val startCal = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, WorkModeSchedule.START_HOUR)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                if (timeInMillis < System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
            }
            am.setRepeating(AlarmManager.RTC_WAKEUP, startCal.timeInMillis, AlarmManager.INTERVAL_DAY, startPi)

            val endPi = PendingIntent.getBroadcast(
                context, REQUEST_CODE_END,
                Intent(context, WorkModeScheduleReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val endCal = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, WorkModeSchedule.END_HOUR)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                if (timeInMillis < System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
            }
            am.setRepeating(AlarmManager.RTC_WAKEUP, endCal.timeInMillis, AlarmManager.INTERVAL_DAY, endPi)
        }
    }
}
