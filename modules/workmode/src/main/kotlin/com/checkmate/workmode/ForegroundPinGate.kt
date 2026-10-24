package com.checkmate.workmode

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ForegroundPinGate — an always-on, accessibility-independent "app lock" for the screens
 * that can force-stop or disable Checkmate.
 *
 * Why: UninstallGuard (via AppAutomationService) only works while the accessibility service
 * is alive, and a Force stop drops that service for good. BootGapGuard covered only the
 * first ~2 minutes after boot and had no PIN. This gate polls the foreground package via
 * UsageStats (same signal BootGapGuard uses) from process start onward and, when a gated
 * screen comes up without an active guardian unlock, puts PinGateActivity on top of it.
 *
 * Gated:
 *  - [ALWAYS_GATED] (the OnePlus/ColorOS battery screen that hosts Force stop): always.
 *  - everything in [UninstallGuard.WATCHED_PACKAGES] (Settings and OEM forks): only while
 *    the accessibility watchdog is NOT live, since the precise text-based guard handles
 *    Settings otherwise and a blanket Settings lock would be needlessly harsh.
 *
 *  - [UNINSTALL_SURFACES] (system package-installer / uninstall-confirmation dialog, which is
 *    where launcher long-press "Uninstall" and Settings "Uninstall" both end up): only while
 *    the accessibility watchdog is NOT live.
 *  - Work Mode blocked apps (and the post-skip escalation watchlist): only while the
 *    accessibility watchdog is NOT live. AppAutomationService normally bounces these with
 *    GLOBAL_ACTION_HOME; when that service is unbound/stopped the same blocklist used to be
 *    wide open. This is the accessibility-free "app lock" for that case.
 *
 * Correct guardian PIN -> UninstallGuard's normal 2-minute unlock window, gate stands down.
 * If no PIN has ever been generated there is nothing to type, so the battery screen is
 * bounced Home and Settings is left alone (no self-lockout).
 *
 * Limits: needs Usage Access (already required by BootGapGuard) and, for the background
 * activity start, "Display over other apps". If the activity start fails or the gated
 * screen is still in front on the next poll, it falls back to a Home bounce.
 */
object ForegroundPinGate {

    private const val TAG = "ForegroundPinGate"
    private const val GATE_ACTIVITY = "com.checkmate.ui.gate.PinGateActivity"

    private const val POLL_MS = 150L
    private const val REFIRE_MS = 700L
    private const val ESCALATE_WITHIN_MS = 3_000L
    private const val LOOKBACK_MS = 60_000L
    private const val REPAIR_EVERY_MS = 30_000L
    private const val NO_ACCESS_RETRY_MS = 10_000L

    /** Gated regardless of accessibility state. */
    val ALWAYS_GATED = setOf("com.oplus.battery")

    /** Hosts of the uninstall-confirmation dialog. Gated only while accessibility is down. */
    val UNINSTALL_SURFACES = setOf(
        "com.google.android.packageinstaller",
        "com.android.packageinstaller",
        "com.oplus.appdetail",
        "com.coloros.packageinstaller"
    )

    const val REASON_GUARD = "guard"
    const val REASON_BLOCKED_APP = "blocked_app"

    private const val ESSENTIAL_CACHE_MS = 30_000L
    @Volatile private var essentialCache: Set<String> = emptySet()
    @Volatile private var essentialCacheAt = 0L

    private val running = AtomicBoolean(false)

    /** Idempotent. Safe to call from Application.onCreate / BootReceiver / services. */
    fun start(context: Context) {
        if (!running.compareAndSet(false, true)) return
        val app = context.applicationContext
        Thread({ loop(app) }, "ForegroundPinGate").apply { isDaemon = true }.start()
    }

    private fun isGated(pkg: String): Boolean =
        pkg in ALWAYS_GATED ||
            (!BootGapGuard.isAccessibilityLive() &&
                (pkg in UninstallGuard.WATCHED_PACKAGES || pkg in UNINSTALL_SURFACES))

    private fun essential(app: Context): Set<String> {
        val now = SystemClock.elapsedRealtime()
        if (now - essentialCacheAt > ESSENTIAL_CACHE_MS || essentialCache.isEmpty()) {
            essentialCache = try { WorkModeManager.essentialPackages(app) } catch (_: Exception) { essentialCache }
            essentialCacheAt = now
        }
        return essentialCache
    }

    /** Accessibility-free app lock: a Work Mode blocked app (or post-skip watchlist app) is in
     *  front while the accessibility watchdog that would normally bounce it is not live. */
    private fun isBlockedAppGated(app: Context, pkg: String): Boolean {
        if (BootGapGuard.isAccessibilityLive()) return false
        if (pkg in essential(app)) return false
        if (WorkModeManager.isEnforcing() && pkg in WorkModeManager.getBlockedApps()) return true
        return WorkModeManager.isInPostSkipLockdown() && pkg in WorkModeManager.getEscalationWatchlist()
    }

    private fun loop(app: Context) {
        val usm = app.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        var cursor = System.currentTimeMillis() - LOOKBACK_MS
        var foregroundPkg: String? = null
        var lastFiredAt = 0L
        var lastRepairAt = 0L
        var fireStreak = 0
        UninstallGuard.logDebugTrail("PIN_GATE_START")
        try {
            while (true) {
                try {
                    val t = SystemClock.elapsedRealtime()
                    if (t - lastRepairAt >= REPAIR_EVERY_MS) {
                        lastRepairAt = t
                        AccessibilityRepair.ensureEnabled(app)
                    }
                    if (!BootGapGuard.hasUsageAccess(app)) {
                        Thread.sleep(NO_ACCESS_RETRY_MS)
                        continue
                    }
                    val now = System.currentTimeMillis()
                    foregroundPkg = advanceForeground(usm, cursor, now, foregroundPkg)
                    cursor = now - 1_000L

                    val pkg = foregroundPkg
                    val blockedApp = pkg != null && !isGated(pkg) && isBlockedAppGated(app, pkg)
                    if (pkg != null && (isGated(pkg) || blockedApp) && !UninstallGuard.isUnlocked() && !UninstallGuard.hasRemoteOverride()) {
                        if (t - lastFiredAt >= REFIRE_MS) {
                            fireStreak = if (t - lastFiredAt <= ESCALATE_WITHIN_MS) fireStreak + 1 else 1
                            lastFiredAt = t
                            if (blockedApp) DistractionGuard.recordAppAttempt(app, pkg)
                            fire(app, pkg, escalate = fireStreak >= 2, blockedApp = blockedApp)
                        }
                    } else {
                        fireStreak = 0
                    }
                    Thread.sleep(POLL_MS)
                } catch (ie: InterruptedException) {
                    throw ie
                } catch (e: Exception) {
                    Log.w(TAG, "gate loop error", e)
                    Thread.sleep(1_000L)
                }
            }
        } catch (_: InterruptedException) {
            // fall through
        } finally {
            running.set(false)
            UninstallGuard.logDebugTrail("PIN_GATE_END")
        }
    }

    @Suppress("DEPRECATION")
    private fun advanceForeground(usm: UsageStatsManager, from: Long, to: Long, current: String?): String? {
        var result = current
        try {
            val events = usm.queryEvents(from, to)
            val e = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(e)
                when (e.eventType) {
                    UsageEvents.Event.MOVE_TO_FOREGROUND -> result = e.packageName
                    UsageEvents.Event.MOVE_TO_BACKGROUND -> if (result == e.packageName) result = null
                }
            }
        } catch (se: SecurityException) {
            Log.w(TAG, "usage events unavailable", se)
        }
        return result
    }

    private fun fire(app: Context, pkg: String, escalate: Boolean, blockedApp: Boolean = false) {
        val hasPin = UninstallGuard.hasPinConfigured()
        UninstallGuard.logDebugTrail("PIN_GATE_FIRED pkg=$pkg hasPin=$hasPin escalate=$escalate blockedApp=$blockedApp")

        if (!hasPin) {
            // Nothing to type: bounce the battery screen and blocked apps, leave blanket-gated
            // Settings / installer alone (no self-lockout).
            if (pkg in ALWAYS_GATED || blockedApp) goHome(app)
            return
        }
        try {
            app.startActivity(
                Intent()
                    .setClassName(app.packageName, GATE_ACTIVITY)
                    .addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_NO_ANIMATION or
                            Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                    )
                    .putExtra("gated_pkg", pkg)
                    .putExtra("reason", if (blockedApp) REASON_BLOCKED_APP else REASON_GUARD)
            )
        } catch (e: Exception) {
            Log.w(TAG, "gate activity start failed", e)
            UninstallGuard.logDebugTrail("PIN_GATE_START_FAILED ${e.javaClass.simpleName}")
            goHome(app)
        }
        // The gated screen was still in front shortly after the last attempt, i.e. the gate
        // activity never made it on screen: don't leave the screen exposed.
        if (escalate) goHome(app)

        if (!blockedApp && UninstallGuard.shouldAlert()) {
            UninstallGuard.listener?.onGuardedScreenBlocked(app, "pin_gate_opened")
        }
    }

    private fun goHome(app: Context) {
        try {
            app.startActivity(
                Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            Log.w(TAG, "home bounce failed", e)
        }
    }
}
