package com.checkmate.workmode

/**
 * SweepPolicy — pure decision helper for AppAutomationService's all-windows sweep.
 *
 * BUGFIX (post-skip lockdown floating-window bypass): the window sweep (the layer that
 * catches a blocked app opened or pulled up as a floating window, where a single Home
 * does not close it) was gated on WorkModeManager.isEnforcing() and only looked at
 * getBlockedApps(). A post-skip lockdown (WorkModeManager.startPostSkipLockdown, opened
 * by an explicit Skip or by the overdue-PENDING enforcement) outside the scheduled window
 * therefore bounced a watchlist app once, but a floating copy of it survived and was fully
 * usable. The sweep now also runs, and also targets the escalation watchlist, while a
 * lockdown is active.
 *
 * Kept free of Android and prefs access so it can be unit tested on the JVM.
 */
object SweepPolicy {

    /** True when the window sweep must run: Work Mode enforcement OR a post-skip lockdown. */
    fun sweepActive(enforcing: Boolean, inLockdown: Boolean): Boolean = enforcing || inLockdown

    /**
     * Packages the sweep treats as offenders: the blocked list while enforcing, plus the
     * escalation watchlist while a post-skip lockdown is active. Empty when neither applies.
     * Essential packages (launcher, dialer, Settings, Checkmate...) are exempted by the
     * caller, same as before.
     */
    fun sweepTargets(
        enforcing: Boolean,
        inLockdown: Boolean,
        blocked: Set<String>,
        watchlist: Set<String>
    ): Set<String> {
        val result = mutableSetOf<String>()
        if (enforcing) result += blocked
        if (inLockdown) result += watchlist
        return result
    }
}
