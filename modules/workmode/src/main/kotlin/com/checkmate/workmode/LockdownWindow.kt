package com.checkmate.workmode

/**
 * LockdownWindow — pure time math for the post-skip escalation lockdown window.
 *
 * BUGFIX (post-skip lockdown clock tamper): the window used to be stored and compared in
 * raw System.currentTimeMillis(), so moving the device clock forward (Settings > Date &
 * time, auto time off) by the window length ended the lockdown at once. WorkModeManager now
 * stamps and compares in TrustedTime.nowMillis() (network-anchored, extrapolated with
 * elapsedRealtime), the same clock WorkModeSchedule already uses.
 *
 * Kept free of Android and prefs access so it can be unit tested on the JVM.
 */
object LockdownWindow {

    /** End of a lockdown window of [minutes] opened at [nowMs]. */
    fun untilMs(nowMs: Long, minutes: Int): Long = nowMs + minutes * 60 * 1000L

    /** True while [nowMs] is before [untilMs]. A never-set window (0) is never active. */
    fun isActive(nowMs: Long, untilMs: Long): Boolean = nowMs < untilMs
}
