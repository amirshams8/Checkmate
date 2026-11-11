package com.checkmate.workmode

import java.util.Calendar

/**
 * WorkModeSchedule — the hardcoded daily Block Mode window.
 *
 * Checkmate enforces Work Mode (app + website blocking, plus the Work Mode
 * settings lock in WorkModeLockGate) every day from 18:00 (6 PM) to 07:00
 * (7 AM) the following morning — on top of whatever task-based sessions the
 * student starts manually.
 *
 * UPDATE: the usual hardcoded window's end was moved from 02:00 to 05:00,
 * and then to 07:00. Per Sir (msg 5958) the free window is now exactly
 * 16:30-18:00: the usual window starts at 18:00 and the extra lock window
 * runs 01:00 to 16:30, so every non-holiday day the student is locked out
 * almost all day with only a short 16:30-18:00 gap before the usual
 * 18:00-07:00 window takes back over ("locked twice" per day). Previously
 * the gap was 15:10-19:00.
 *
 * This window is intentionally a compile-time constant. There is no
 * Settings screen, SharedPrefs key, or remote config that can change it —
 * changing the hours means editing this file and shipping a new build, not
 * something reachable from the student's phone. That's deliberate: a
 * schedule the student can adjust "just this once" isn't a schedule.
 *
 * The ONE exception is [HolidaySchedule]: specific calendar dates a guardian
 * has explicitly marked exempt (see that file's doc for why it's gated
 * behind a guardian PIN unlock rather than being freely editable). On a
 * marked holiday, the usual 18:00-07:00 window is replaced by a single
 * reduced 01:00-16:30 lock instead — a holiday eases the schedule for the
 * day, it doesn't remove it. Every other day, this schedule behaves exactly
 * as before.
 */
object WorkModeSchedule {

    /** 24h clock hour the usual daily window begins (6 PM), every day. */
    const val START_HOUR = 18

    /**
     * 24h clock hour the usual daily window ends, on the next calendar day
     * (7 AM). Previously 2 AM, then 5 AM.
     */
    const val END_HOUR = 7

    /** 24h clock hour the extra lock window begins (1 AM), every day. */
    const val SPECIAL_START_HOUR = 1
    const val SPECIAL_START_MINUTE = 0

    /** 24h clock hour/minute the extra lock window ends (4:30 PM), every day. */
    const val SPECIAL_END_HOUR = 16
    const val SPECIAL_END_MINUTE = 30

    /** Human-readable label for display in Settings only — not configurable from there. */
    const val LABEL =
        "6:00 PM \u2013 4:30 PM daily, with a 4:30 PM \u2013 6:00 PM gap"

    /**
     * True if [cal] (defaults to the trusted, tamper-resistant "now" — see
     * TrustedTime — NOT the raw device clock) falls inside a locked window:
     *  - the usual 18:00-07:00 window, every day, OR
     *  - the extra 01:00-16:30 window, every day.
     *  - EXCEPT on a guardian-marked holiday (HolidaySchedule.isHoliday): the usual
     *    18:00-07:00 window is replaced by a single reduced 01:00-16:30 lock, same
     *    hours as the regular extra window. A holiday eases the schedule for the
     *    day — it doesn't remove structure from it entirely.
     *
     * Deliberately does NOT default to Calendar.getInstance() (raw device
     * time) — that was spoofable by simply changing Settings > Date & time,
     * which made evaluateSchedule() see "outside the window" and turn
     * blocking off for real. TrustedTime.nowMillis() is anchored to a
     * network-verified timestamp plus elapsedRealtime (monotonic, unaffected
     * by clock changes), so this now only trusts the device's own clock
     * before any network sync has ever completed.
     */
    fun isWithinScheduledWindow(
        cal: Calendar = Calendar.getInstance().apply { timeInMillis = TrustedTime.nowMillis() }
    ): Boolean {
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val minute = cal.get(Calendar.MINUTE)

        fun withinSpecialWindow(): Boolean {
            val afterSpecialStart = hour > SPECIAL_START_HOUR ||
                (hour == SPECIAL_START_HOUR && minute >= SPECIAL_START_MINUTE)
            val beforeSpecialEnd = hour < SPECIAL_END_HOUR ||
                (hour == SPECIAL_END_HOUR && minute < SPECIAL_END_MINUTE)
            return afterSpecialStart && beforeSpecialEnd
        }

        // Guardian-marked holiday: the usual 18:00-07:00 window is replaced by just
        // the reduced 01:00-16:30 window — see HolidaySchedule's doc for why this can
        // only be set behind a guardian PIN unlock.
        if (HolidaySchedule.isHoliday(cal)) return withinSpecialWindow()

        // Usual window wraps past midnight: active from START_HOUR..23:59 AND
        // 00:00..(END_HOUR-1):59. Applies every day of the week.
        if (hour >= START_HOUR || hour < END_HOUR) return true

        // Extra window: 01:00 (inclusive) to 16:30 (exclusive), every non-holiday day
        // — leaves the 16:30-18:00 gap free before the usual window resumes.
        if (withinSpecialWindow()) return true

        return false
    }

    /**
     * True once the free period is over: inside the usual 18:00-07:00 window on a
     * non-holiday day. Used for apps that must stay reachable during the daytime lock and
     * the 16:30-18:00 free gap but are blocked from 18:00 onward (e.g. Phone Manager).
     * On a guardian-marked holiday the usual window doesn't apply, so this is false.
     * Uses the trusted clock, same as [isWithinScheduledWindow].
     */
    fun isAfterFreePeriod(
        cal: Calendar = Calendar.getInstance().apply { timeInMillis = TrustedTime.nowMillis() }
    ): Boolean {
        if (HolidaySchedule.isHoliday(cal)) return false
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        return hour >= START_HOUR || hour < END_HOUR
    }

    /**
     * Milliseconds from [cal] (defaults to now) until the next boundary
     * clock-time (01:00, 07:00, 16:30, or 18:00). Used only for
     * logging/diagnostics — the actual enforcement alarms are four fixed
     * daily clock-time alarms registered by WorkModeScheduleReceiver, not a
     * self-rescheduling "next boundary" alarm, so a missed tick can't drift
     * the schedule.
     */
    fun millisUntilNextBoundary(cal: Calendar = Calendar.getInstance()): Long {
        val now = cal.timeInMillis
        val boundaryHours = intArrayOf(
            SPECIAL_START_HOUR, END_HOUR, SPECIAL_END_HOUR, START_HOUR
        )

        var best: Long = -1L
        for (h in boundaryHours) {
            val target = (cal.clone() as Calendar).apply {
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                set(Calendar.HOUR_OF_DAY, h)
                set(Calendar.MINUTE, if (h == SPECIAL_END_HOUR) SPECIAL_END_MINUTE else 0)
            }
            if (target.timeInMillis <= now) target.add(Calendar.DAY_OF_YEAR, 1)
            val diff = target.timeInMillis - now
            if (best < 0 || diff < best) best = diff
        }
        // Defensive fallback — boundaryHours is never empty, but keep the
        // same "never return non-positive" contract the old code had.
        return if (best > 0) best else 60_000L
    }
}
