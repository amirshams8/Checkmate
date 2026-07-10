package com.checkmate.workmode

import java.util.Calendar

/**
 * WorkModeSchedule — the hardcoded daily Block Mode window.
 *
 * Checkmate enforces Work Mode (app + website blocking, plus the Work Mode
 * settings lock in WorkModeLockGate) every day from 19:00 (7 PM) to 02:00
 * (2 AM) the following morning — on top of whatever task-based sessions the
 * student starts manually.
 *
 * This window is intentionally a compile-time constant. There is no
 * Settings screen, SharedPrefs key, or remote config that can change it —
 * changing the hours means editing this file and shipping a new build, not
 * something reachable from the student's phone. That's deliberate: a
 * schedule the student can adjust "just this once" isn't a schedule.
 */
object WorkModeSchedule {

    /** 24h clock hour the hardcoded window begins (7 PM). */
    const val START_HOUR = 19

    /** 24h clock hour the hardcoded window ends, on the next calendar day (2 AM). */
    const val END_HOUR = 2

    /** Human-readable label for display in Settings only — not configurable from there. */
    const val LABEL = "7:00 PM \u2013 2:00 AM daily"

    /** True if [cal] (defaults to now) falls inside the 19:00-02:00 window. */
    fun isWithinScheduledWindow(cal: Calendar = Calendar.getInstance()): Boolean {
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        // Window wraps past midnight: active from START_HOUR..23:59 AND
        // 00:00..(END_HOUR-1):59.
        return hour >= START_HOUR || hour < END_HOUR
    }

    /**
     * Milliseconds from [cal] (defaults to now) until the window's next
     * start-or-end boundary. Used only for logging/diagnostics — the actual
     * enforcement alarms are two fixed daily clock-time alarms registered by
     * WorkModeScheduleReceiver, not a self-rescheduling "next boundary"
     * alarm, so a missed tick can't drift the schedule.
     */
    fun millisUntilNextBoundary(cal: Calendar = Calendar.getInstance()): Long {
        val now = cal.timeInMillis
        val target = (cal.clone() as Calendar).apply {
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            set(Calendar.MINUTE, 0)
        }
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        when {
            hour < END_HOUR -> target.set(Calendar.HOUR_OF_DAY, END_HOUR)
            hour < START_HOUR -> target.set(Calendar.HOUR_OF_DAY, START_HOUR)
            else -> {
                target.add(Calendar.DAY_OF_YEAR, 1)
                target.set(Calendar.HOUR_OF_DAY, END_HOUR)
            }
        }
        val diff = target.timeInMillis - now
        return if (diff > 0) diff else 60_000L
    }
}
