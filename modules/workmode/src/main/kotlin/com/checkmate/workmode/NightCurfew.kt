package com.checkmate.workmode

import java.util.Calendar

/**
 * NightCurfew — OnePlus build only: a fixed 23:00-06:00 daily full-screen touch freeze.
 *
 * Same compile-time-constant idea as [WorkModeSchedule]: no Settings screen, pref or remote
 * config changes these hours. The clock is [TrustedTime] (network-anchored + elapsedRealtime),
 * NOT the raw device clock, so moving Settings > Date & time cannot end the curfew early.
 *
 * Only a guardian remote override ([UninstallGuard.hasRemoteOverride]) lifts it. The PIN-entry
 * UI cannot be used during the freeze (the overlay swallows every touch, Checkmate's own UI
 * included), so the Telegram "unlock" command is the real way out.
 */
object NightCurfew {

    /** 24h hour the curfew begins (11 PM). */
    const val START_HOUR = 23

    /** 24h hour the curfew ends on the next calendar day (6 AM). */
    const val END_HOUR = 6

    /** True while the trusted clock is inside 23:00-06:00. */
    fun isWithinCurfew(
        cal: Calendar = Calendar.getInstance().apply { timeInMillis = TrustedTime.nowMillis() }
    ): Boolean {
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        return hour >= START_HOUR || hour < END_HOUR
    }

    /** True when the screen must be frozen right now: in curfew and no guardian override. */
    fun shouldFreeze(): Boolean = isWithinCurfew() && !UninstallGuard.hasRemoteOverride()
}
