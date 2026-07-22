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
 * Fires at the four hardcoded WorkModeSchedule boundary clock-times — 01:00,
 * 05:00, 17:30, and 19:00, every day — so the forced block window is
 * reconciled even while the phone is idle and Checkmate's UI/accessibility
 * events aren't firing. The accessibility-service-side check in
 * AppAutomationService is the fast path during active use; this is the
 * fallback that survives the screen being off.
 *
 * UPDATE: previously two alarms (19:00 start / 02:00 end). Now four, because
 * the usual window end moved to 05:00 and Sunday/Wednesday get an extra
 * 01:00-17:30 lock window. All four alarms fire every day regardless of
 * weekday — each one just calls WorkModeManager.evaluateSchedule(), which
 * re-reads WorkModeSchedule.isWithinScheduledWindow() against the live day
 * and hour, so a boundary firing on a day it doesn't apply to (e.g. the
 * 01:00 alarm on a Monday) is a harmless no-op.
 *
 * Mirrors the exact alarm pattern GuardianNotifier already uses for the EOD
 * summary / usage / weekly reports (daily am.setRepeating at a fixed clock
 * time), so this needs no extra permissions beyond what's already declared.
 */
class WorkModeScheduleReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        // Guard against a cold process (e.g. alarm firing before the app
        // process has been started this boot) — both are idempotent no-ops
        // if already initialized.
        CheckmatePrefs.init(context)
        CheckmateState.init(context)
        TrustedTime.refreshIfNeeded(context)
        WorkModeManager.evaluateSchedule(context)
    }

    companion object {
        private const val REQUEST_CODE_SPECIAL_START = 4471
        private const val REQUEST_CODE_END           = 4472
        private const val REQUEST_CODE_SPECIAL_END    = 4473
        private const val REQUEST_CODE_START          = 4474

        /** Registers (or re-registers) the four daily boundary alarms. Safe to call repeatedly. */
        fun scheduleDailyAlarms(context: Context) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

            scheduleDailyAlarmAt(
                context, am, REQUEST_CODE_START,
                hour = WorkModeSchedule.START_HOUR, minute = 0
            )
            scheduleDailyAlarmAt(
                context, am, REQUEST_CODE_END,
                hour = WorkModeSchedule.END_HOUR, minute = 0
            )
            scheduleDailyAlarmAt(
                context, am, REQUEST_CODE_SPECIAL_START,
                hour = WorkModeSchedule.SPECIAL_START_HOUR,
                minute = WorkModeSchedule.SPECIAL_START_MINUTE
            )
            scheduleDailyAlarmAt(
                context, am, REQUEST_CODE_SPECIAL_END,
                hour = WorkModeSchedule.SPECIAL_END_HOUR,
                minute = WorkModeSchedule.SPECIAL_END_MINUTE
            )
        }

        private fun scheduleDailyAlarmAt(
            context: Context,
            am: AlarmManager,
            requestCode: Int,
            hour: Int,
            minute: Int
        ) {
            val pi = PendingIntent.getBroadcast(
                context, requestCode,
                Intent(context, WorkModeScheduleReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val cal = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                if (timeInMillis < System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
            }
            am.setRepeating(AlarmManager.RTC_WAKEUP, cal.timeInMillis, AlarmManager.INTERVAL_DAY, pi)
        }
    }
}
