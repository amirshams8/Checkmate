package com.checkmate.workmode

import java.util.concurrent.atomic.AtomicLong

/**
 * LockdownSweepRequest — one-shot "sweep the windows now" flag for a freshly opened lockdown.
 *
 * BUGFIX (post-skip lockdown, app already in front): the overdue-PENDING path
 * (`app`'s OverdueEnforcementCoordinator) can open a post-skip lockdown while the student is
 * already inside a watchlist app (Instagram, YouTube...). AppAutomationService only bounced
 * on the NEXT window change in that app, so an app that was already open and quiet stayed
 * usable until it happened to emit one. The coordinator now calls [request] right after
 * WorkModeManager.startPostSkipLockdown(); AppAutomationService calls [consume] on its next
 * accessibility event of any kind and, when it returns true, runs one forced window sweep,
 * which bounces whichever watchlist app is in front (SweepPolicy decides the targets).
 *
 * The request expires after [TTL_MS] so a stale flag (accessibility service not running at the
 * time) can never trigger a surprise Home press long after the lockdown was opened. The sweep
 * itself is a no-op when no lockdown is active or nothing offending is on screen.
 *
 * Kept free of Android and prefs access so it can be unit tested on the JVM. The caller passes
 * the clock reading, so tests do not depend on real time.
 */
object LockdownSweepRequest {

    /** How long a request stays valid. */
    const val TTL_MS = 120_000L

    private const val NONE = -1L
    private val requestedAt = AtomicLong(NONE)

    /** Records that a forced sweep is wanted, as of [nowMs]. Repeated calls keep one request. */
    fun request(nowMs: Long) {
        requestedAt.set(nowMs)
    }

    /**
     * True exactly once per [request] while it is still fresh; the request is cleared either
     * way. [nowMs] must come from the same clock as the [request] call.
     */
    fun consume(nowMs: Long): Boolean {
        val t = requestedAt.getAndSet(NONE)
        return t != NONE && nowMs - t in 0..TTL_MS
    }

    /** Clears any pending request. For tests. */
    fun reset() {
        requestedAt.set(NONE)
    }
}
