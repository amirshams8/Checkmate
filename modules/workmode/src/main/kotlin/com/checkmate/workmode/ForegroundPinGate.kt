package com.checkmate.workmode

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.WindowManager
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

    // ── Floating-window cover (accessibility-off only) ──────────────────────
    // FIX (floating window renders above PinGateActivity): on affected OEMs a
    // Work Mode blocked app opened as a floating/freeform window is drawn in a
    // Z-layer above a normal activity, so PinGateActivity used to open underneath
    // it — technically "on top" per the activity stack, invisible and unusable in
    // practice. A TYPE_APPLICATION_OVERLAY sits above normal app windows
    // (including floating ones), same approach BootGapGuard already uses for the
    // boot-gap case. Only raised for the blockedApp (floating-window) path, not
    // for ALWAYS_GATED/UNINSTALL_SURFACES, which aren't floating-window windows.
    private const val OVERLAY_BLOCK_MS = 1_500L
    @Volatile private var uiHandler: Handler? = null
    private var overlayView: View? = null
    private var overlayWindowManager: WindowManager? = null
    private val removeOverlayRunnable = Runnable { removeOverlay() }

    private fun ensureUiHandler(): Handler {
        uiHandler?.let { return it }
        synchronized(this) {
            uiHandler?.let { return it }
            val t = HandlerThread("ForegroundPinGate-ui").apply { isDaemon = true; start() }
            return Handler(t.looper).also { uiHandler = it }
        }
    }

    private fun showOverlay(app: Context) {
        try {
            if (!Settings.canDrawOverlays(app)) return
            val ui = ensureUiHandler()
            ui.post {
                try {
                    if (overlayView == null) {
                        val wm = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                        val params = WindowManager.LayoutParams(
                            WindowManager.LayoutParams.MATCH_PARENT,
                            WindowManager.LayoutParams.MATCH_PARENT,
                            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                            PixelFormat.TRANSLUCENT
                        )
                        val view = View(app)
                        wm.addView(view, params)
                        overlayView = view
                        overlayWindowManager = wm
                    }
                    ui.removeCallbacks(removeOverlayRunnable)
                    ui.postDelayed(removeOverlayRunnable, OVERLAY_BLOCK_MS)
                } catch (e: Exception) {
                    Log.w(TAG, "pin-gate overlay failed", e)
                    UninstallGuard.logDebugTrail("PIN_GATE_OVERLAY_FAILED ${e.javaClass.simpleName}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "pin-gate overlay post failed", e)
        }
    }

    private fun removeOverlay() {
        uiHandler?.removeCallbacks(removeOverlayRunnable)
        val view = overlayView ?: return
        val wm = overlayWindowManager
        overlayView = null
        overlayWindowManager = null
        try {
            wm?.removeView(view)
        } catch (e: Exception) {
            Log.w(TAG, "pin-gate overlay failed to remove", e)
        }
    }

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
        // LOOPHOLE FIX (floating windows): ONE "foreground package" is not enough. A blocked app
        // opened as a floating/freeform window emits no MOVE_TO_BACKGROUND, and the moment the
        // student touches the app underneath, that app's MOVE_TO_FOREGROUND overwrote the single
        // value, so the gate never saw the (still visible, still usable) blocked window again.
        // Track every currently-resumed activity (key = package/class -> package) instead.
        val resumed = LinkedHashMap<String, String>()
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
                    advanceForeground(usm, cursor, now, resumed)
                    cursor = now - 1_000L

                    // Any resumed package that is gated, or a locked blocked app, triggers the
                    // gate (not just the most recent one) — see the `resumed` note above.
                    var pkg: String? = null
                    var blockedApp = false
                    for (p in resumed.values.toSet()) {
                        if (isGated(p)) { pkg = p; blockedApp = false; break }
                        if (pkg == null && isBlockedAppGated(app, p)) { pkg = p; blockedApp = true }
                    }
                    if (pkg != null && (isGated(pkg) || blockedApp) && !UninstallGuard.isUnlocked() && !UninstallGuard.hasRemoteOverride()) {
                        if (t - lastFiredAt >= REFIRE_MS) {
                            fireStreak = if (t - lastFiredAt <= ESCALATE_WITHIN_MS) fireStreak + 1 else 1
                            lastFiredAt = t
                            if (blockedApp) DistractionGuard.recordAppAttempt(app, pkg)
                            fire(app, pkg, escalate = fireStreak >= 2, blockedApp = blockedApp)
                        }
                    } else {
                        fireStreak = 0
                        removeOverlay()
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
            uiHandler?.post { removeOverlay() }
            UninstallGuard.logDebugTrail("PIN_GATE_END")
        }
    }

    @Suppress("DEPRECATION")
    private fun advanceForeground(usm: UsageStatsManager, from: Long, to: Long, resumed: MutableMap<String, String>) {
        try {
            val events = usm.queryEvents(from, to)
            val e = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(e)
                val pkg = e.packageName ?: continue
                val key = pkg + "/" + (e.className ?: "")
                when (e.eventType) {
                    UsageEvents.Event.MOVE_TO_FOREGROUND -> resumed[key] = pkg
                    // BACKGROUND (=ACTIVITY_PAUSED), plus ACTIVITY_STOPPED (23) / ACTIVITY_DESTROYED (24)
                    // where the OS emits them (API 29+; raw values keep this compiling at minSdk 26).
                    UsageEvents.Event.MOVE_TO_BACKGROUND, 23, 24 -> resumed.remove(key)
                    // SCREEN_NON_INTERACTIVE (16), DEVICE_SHUTDOWN (26), DEVICE_STARTUP (27): nothing
                    // is on screen any more / the process view is stale — never leave a stuck entry.
                    16, 26, 27 -> resumed.clear()
                }
            }
        } catch (se: SecurityException) {
            Log.w(TAG, "usage events unavailable", se)
        }
    }

    private fun fire(app: Context, pkg: String, escalate: Boolean, blockedApp: Boolean = false) {
        val hasPin = UninstallGuard.hasPinConfigured()
        UninstallGuard.logDebugTrail("PIN_GATE_FIRED pkg=$pkg hasPin=$hasPin escalate=$escalate blockedApp=$blockedApp")

        // Cover the floating window itself before anything else: a floating/freeform
        // window can render above PinGateActivity on affected OEMs, so starting the gate
        // activity alone isn't enough. Re-raised (and its auto-remove timer extended) on
        // every refire while still gated; removeOverlay() runs once the loop sees pkg no
        // longer gated.
        if (blockedApp) showOverlay(app)

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
