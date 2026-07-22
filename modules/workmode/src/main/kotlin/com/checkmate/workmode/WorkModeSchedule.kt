package com.checkmate.workmode

import java.util.Calendar

/**
 * WorkModeSchedule — the hardcoded daily Block Mode window.
 *
 * Checkmate enforces Work Mode (app + website blocking, plus the Work Mode
 * settings lock in WorkModeLockGate) every day from 19:00 (7 PM) to 05:00
 * (5 AM) the following morning — on top of whatever task-based sessions the
 * student starts manually.
 *
 * UPDATE: the usual hardcoded window's end was moved from 02:00 to 05:00
 * (still starts 19:00). Sunday and Wednesday additionally get a second,
 * earlier lock window — 01:00 to 17:30 — so on those two days the student
 * is locked out almost all day with only a short 17:30-19:00 gap before the
 * usual 19:00-05:00 window takes back over ("locked twice" per day).
 *
 * This window is intentionally a compile-time constant. There is no
 * Settings screen, SharedPrefs key, or remote config that can change it —
 * changing the hours means editing this file and shipping a new build, not
 * something reachable from the student's phone. That's deliberate: a
 * schedule the student can adjust "just this once" isn't a schedule.
 */
object WorkModeSchedule {

    /** 24h clock hour the usual daily window begins (7 PM), every day. */
    const val START_HOUR = 19

    /**
     * 24h clock hour the usual daily window ends, on the next calendar day
     * (5 AM). Previously 2 AM.
     */
    const val END_HOUR = 5

    /** Days that get the extra 01:00-17:30 lock window on top of the usual one. */
    private val SPECIAL_DAYS = setOf(Calendar.SUNDAY, Calendar.WEDNESDAY)

    /** 24h clock hour the Sunday/Wednesday extra window begins (1 AM). */
    const val SPECIAL_START_HOUR = 1
    const val SPECIAL_START_MINUTE = 0

    /** 24h clock hour/minute the Sunday/Wednesday extra window ends (5:30 PM). */
    const val SPECIAL_END_HOUR = 17
    const val SPECIAL_END_MINUTE = 30

    /** Human-readable label for display in Settings only — not configurable from there. */
    const val LABEL =
        "7:00 PM \u2013 5:00 AM daily (Sun & Wed also locked 1:00 AM \u2013 5:30 PM)"

    /**
     * True if [cal] (defaults to the trusted, tamper-resistant "now" — see
     * TrustedTime — NOT the raw device clock) falls inside a locked window:
     *  - the usual 19:00-05:00 window, every day, OR
     *  - on Sunday/Wednesday only, the extra 01:00-17:30 window.
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

        // Usual window wraps past midnight: active from START_HOUR..23:59 AND
        // 00:00..(END_HOUR-1):59. Applies every day of the week.
        if (hour >= START_HOUR || hour < END_HOUR) return true

        // Extra Sunday/Wednesday window: 01:00 (inclusive) to 17:30 (exclusive).
        // Doesn't wrap past midnight, so it's a plain same-day range check.
        val day = cal.get(Calendar.DAY_OF_WEEK)
        if (day in SPECIAL_DAYS) {
            val minute = cal.get(Calendar.MINUTE)
            val afterSpecialStart = hour > SPECIAL_START_HOUR ||
                (hour == SPECIAL_START_HOUR && minute >= SPECIAL_START_MINUTE)
            val beforeSpecialEnd = hour < SPECIAL_END_HOUR ||
                (hour == SPECIAL_END_HOUR && minute < SPECIAL_END_MINUTE)
            if (afterSpecialStart && beforeSpecialEnd) return true
        }

        return false
    }

    /**
     * Milliseconds from [cal] (defaults to now) until the next boundary
     * clock-time (01:00, 05:00, 17:30, or 19:00). Used only for
     * logging/diagnostics — the actual enforcement alarms are four fixed
     * daily clock-time alarms registered by WorkModeScheduleReceiver, not a
     * self-rescheduling "next boundary" alarm, so a missed tick can't drift
     * the schedule. Firing at all four clock times every day (even on days
     * where a given boundary doesn't apply) is harmless: each firing just
     * re-evaluates [isWithinScheduledWindow] against the real current time.
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
