package com.checkmate.workmode

/**
 * KillGapPolicy — pure decision helper for [KillGapGuard].
 *
 * Question it answers: "was Checkmate's accessibility watchdog just killed from outside
 * (System Booster "Boost now", Force Stop, a task killer) while enforcement mattered?"
 *
 * Why this exists (T8b, "Boost now" loophole): a Force Stop makes Android drop the
 * accessibility service from ENABLED_ACCESSIBILITY_SERVICES and cancels every alarm and job,
 * and a force-stopped app receives nothing until the user opens it again. So Checkmate cannot
 * stop the kill; it can only notice it the next time its process starts and then re-assert
 * enforcement and tell the guardian. This policy decides whether a process start counts as
 * "after a kill". It is free of Android and prefs access so it is unit tested on the JVM.
 *
 * Two independent kill signals:
 *  - Route A, [userRequestedExit]: Android's own process-exit history (API 30+) says the last
 *    exit was "user requested" (force stop / task killer). Precise, and it still fires when a
 *    watchdog re-enabled the service before the process came back.
 *  - Route B, heartbeat gap: the service stopped heartbeating for at least [GAP_THRESHOLD_MS],
 *    was never shut down cleanly, and is no longer in the enabled-services list. Used on
 *    devices without exit history.
 *
 * Neither route fires for: no heartbeat ever written, a clean service shutdown (the service's
 * onDestroy ran, e.g. disabled with the guardian PIN), a reboot (BootGapGuard's job), an active
 * guardian PIN unlock, or when no enforcement window / lockdown is active.
 */
object KillGapPolicy {

    /** Heartbeat silence that counts as "the service stopped running" on route B. */
    const val GAP_THRESHOLD_MS = 120_000L

    enum class Verdict {
        KILL_SUSPECTED,
        NO_HEARTBEAT,
        CLEAN_SHUTDOWN,
        REBOOTED,
        PIN_UNLOCKED,
        GAP_TOO_SHORT,
        STILL_ENABLED,
        NO_ENFORCEMENT_WINDOW
    }

    /**
     * @param lastAliveMs   last heartbeat the service wrote (0 = never)
     * @param nowMs         same clock as [lastAliveMs]
     * @param cleanShutdown the service's onDestroy ran after that heartbeat
     * @param rebooted      the device rebooted since that heartbeat
     * @param pinUnlocked   a guardian PIN unlock (or remote override) is active now
     * @param userRequestedExit Android's exit history shows a new "user requested" exit
     * @param serviceEnabled the service is currently in the enabled-accessibility list
     * @param windowActive  Work Mode enforcement or a post-skip lockdown is active now
     */
    fun evaluate(
        lastAliveMs: Long,
        nowMs: Long,
        cleanShutdown: Boolean,
        rebooted: Boolean,
        pinUnlocked: Boolean,
        userRequestedExit: Boolean,
        serviceEnabled: Boolean,
        windowActive: Boolean,
        thresholdMs: Long = GAP_THRESHOLD_MS
    ): Verdict {
        if (lastAliveMs <= 0L) return Verdict.NO_HEARTBEAT
        if (cleanShutdown) return Verdict.CLEAN_SHUTDOWN
        if (rebooted) return Verdict.REBOOTED
        if (pinUnlocked) return Verdict.PIN_UNLOCKED
        if (!userRequestedExit) {
            if (nowMs - lastAliveMs < thresholdMs) return Verdict.GAP_TOO_SHORT
            if (serviceEnabled) return Verdict.STILL_ENABLED
        }
        if (!windowActive) return Verdict.NO_ENFORCEMENT_WINDOW
        return Verdict.KILL_SUSPECTED
    }
}
