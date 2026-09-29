package com.checkmate.workmode

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.WindowManager
import java.util.concurrent.atomic.AtomicBoolean

/**
 * BootGapGuard — closes the post-reboot window where UninstallGuard has no eyes.
 *
 * BUGFIX (Force Stop reachable for up to ~30s after a restart): every check in
 * UninstallGuard is executed by AppAutomationService, an AccessibilityService. After a
 * reboot Android binds accessibility services late, so until AppAutomationService's
 * onServiceConnected() runs there is nothing watching Settings — Battery → App usage →
 * Checkmate → Force stop is fully reachable in that gap. Force-stopping the app also
 * makes Android drop it from ENABLED_ACCESSIBILITY_SERVICES, so one tap in that window
 * turns the whole watchdog off for good.
 *
 * An accessibility service can't be started earlier, and nothing else on the device can
 * read on-screen text, so this covers the gap with the only signal that exists without
 * it: which package is in the foreground (UsageStatsManager events — PACKAGE_USAGE_STATS
 * is already declared and used by AppUsageTracker). It is deliberately coarser than
 * AppAutomationService (whole Settings package, not "checkmate" + keyword) because text
 * isn't available here; that's acceptable because it only runs from BOOT_COMPLETED until
 * the accessibility service connects (hard cap [MAX_GAP_MS]).
 *
 * While the guard is running, if the foreground package is one of
 * [UninstallGuard.WATCHED_PACKAGES] and no guardian PIN unlock is active, it:
 *  1. raises a full-screen touch-swallowing overlay (same idea as AppAutomationService's
 *     touch blocker, but a TYPE_APPLICATION_OVERLAY window since there's no accessibility
 *     service yet) — this also counts as a visible window for background-activity-start
 *     rules on newer Android versions;
 *  2. starts the Home activity, which is what actually navigates away (no
 *     GLOBAL_ACTION_HOME without accessibility);
 *  3. remembers that it fired, and sends the usual guardian alert ONCE when the gap ends
 *     — deferred on purpose: GuardianNotifier's WhatsApp path relies on the accessibility
 *     service to type/send, so alerting mid-gap would just pop WhatsApp over the bounce.
 *
 * Only armed when AppAutomationService is actually enabled in system settings (i.e. it is
 * expected to connect). If the student/guardian has it switched off, there's no gap to
 * cover — BootReceiver already reports that case — and blocking Settings would only stop
 * the guardian from turning it back on.
 *
 * Requirements / limits (nothing here can lift these):
 *  - Usage Access must be granted; without it foreground detection is impossible and the
 *    guard skips itself (logged to the debug trail).
 *  - "Display over other apps" (SYSTEM_ALERT_WINDOW) should be granted for the overlay;
 *    without it the guard still tries the Home bounce alone (logged).
 *  - Runs on a plain thread and relies on the process staying alive after BOOT_COMPLETED
 *    (ReminderService, started in BootReceiver, is what keeps it alive).
 *  - Does not cover Safe Mode / adb / factory reset (see UninstallGuard's design note).
 */
object BootGapGuard {

    private const val TAG = "BootGapGuard"

    private const val ACCESSIBILITY_SERVICE_CLASS = "com.checkmate.automation.AppAutomationService"

    // The user-observed gap is ~30s; 4x that is generous margin without leaving Settings
    // blocked indefinitely if the accessibility service never connects.
    private const val MAX_GAP_MS = 120_000L
    private const val POLL_INTERVAL_MS = 300L
    private const val REFIRE_INTERVAL_MS = 1_000L
    private const val BLOCK_MS = 1_500L
    private const val HOME_DELAY_MS = 60L
    private const val INITIAL_LOOKBACK_CAP_MS = 5 * 60 * 1000L
    private const val EVENT_OVERLAP_MS = 1_000L

    // Same reason string AppAutomationService uses, so GuardianNotifier's existing
    // message applies unchanged.
    private const val ALERT_REASON = "settings_screen_blocked"

    // In-memory on purpose: a new process (i.e. a fresh boot) must start at false.
    // start() never resets it — if the accessibility service connected before
    // BootReceiver ran, the guard must see that and stand down.
    @Volatile private var accessibilityConnected = false

    private val running = AtomicBoolean(false)
    private val pendingAlert = AtomicBoolean(false)

    private val mainHandler = Handler(Looper.getMainLooper())

    // Main-thread only.
    private var overlayView: View? = null
    private var overlayWindowManager: WindowManager? = null
    private val removeOverlayRunnable = Runnable { removeOverlay() }

    /**
     * Call from BootReceiver on BOOT_COMPLETED. No-op if already running, if the
     * accessibility service has already connected, or if the guard can't work on this
     * device right now (logged via [UninstallGuard.logDebugTrail]).
     */
    fun start(context: Context) {
        val app = context.applicationContext

        if (accessibilityConnected) {
            UninstallGuard.logDebugTrail("BOOT_GAP_SKIP reason=accessibility_already_connected")
            return
        }
        if (!isWatchdogEnabledInSettings(app)) {
            UninstallGuard.logDebugTrail("BOOT_GAP_SKIP reason=watchdog_not_enabled")
            return
        }
        if (!hasUsageAccess(app)) {
            UninstallGuard.logDebugTrail("BOOT_GAP_SKIP reason=no_usage_access")
            return
        }
        if (!running.compareAndSet(false, true)) return

        UninstallGuard.logDebugTrail("BOOT_GAP_START overlayPermission=${Settings.canDrawOverlays(app)}")
        Thread({ runLoop(app) }, "BootGapGuard").apply { isDaemon = true }.start()
    }

    /**
     * Call from AppAutomationService.onServiceConnected(): the normal watchdog is live, so
     * the gap is over. Releases any overlay and flushes the deferred guardian alert.
     */
    fun onAccessibilityConnected(context: Context) {
        accessibilityConnected = true
        mainHandler.post { removeOverlay() }
        flushPendingAlert(context.applicationContext)
    }

    // ── Poll loop ────────────────────────────────────────────────────────────

    private fun runLoop(app: Context) {
        val guardStart = SystemClock.elapsedRealtime()
        val usm = app.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager

        // elapsedRealtime() is time since boot, so this looks back to boot (capped) to pick
        // up whatever was already foregrounded before BOOT_COMPLETED reached us.
        var cursor = System.currentTimeMillis() - minOf(guardStart, INITIAL_LOOKBACK_CAP_MS)
        var foregroundPkg: String? = null
        var lastFiredAt = 0L
        var endReason = "timeout"

        try {
            while (true) {
                if (accessibilityConnected) { endReason = "accessibility_connected"; break }
                if (SystemClock.elapsedRealtime() - guardStart >= MAX_GAP_MS) break

                val now = System.currentTimeMillis()
                foregroundPkg = advanceForeground(usm, cursor, now, foregroundPkg)
                cursor = now - EVENT_OVERLAP_MS

                val pkg = foregroundPkg
                if (pkg != null && pkg in UninstallGuard.WATCHED_PACKAGES && !UninstallGuard.isUnlocked()) {
                    val t = SystemClock.elapsedRealtime()
                    if (t - lastFiredAt >= REFIRE_INTERVAL_MS) {
                        lastFiredAt = t
                        fire(app, pkg)
                    }
                }
                Thread.sleep(POLL_INTERVAL_MS)
            }
        } catch (e: Exception) {
            endReason = "error:${e.javaClass.simpleName}"
            Log.w(TAG, "boot-gap loop ended by exception", e)
        } finally {
            running.set(false)
            UninstallGuard.logDebugTrail("BOOT_GAP_END reason=$endReason")
            mainHandler.post { removeOverlay() }
            // If the accessibility service connected, onAccessibilityConnected() already
            // flushed. On timeout / error nobody else will, so do it here.
            if (endReason != "accessibility_connected") flushPendingAlert(app)
        }
    }

    /**
     * Folds usage events in [from, to] into the running foreground-package state.
     * Events are chronological, so replaying the small overlap window is idempotent.
     */
    @Suppress("DEPRECATION")
    private fun advanceForeground(
        usm: UsageStatsManager,
        from: Long,
        to: Long,
        current: String?
    ): String? {
        var result = current
        try {
            val events = usm.queryEvents(from, to)
            val e = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(e)
                // MOVE_TO_FOREGROUND/MOVE_TO_BACKGROUND are the pre-API-29 names for
                // ACTIVITY_RESUMED/ACTIVITY_PAUSED and have the same values (1/2); using
                // them keeps this compiling at minSdk 26.
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

    // ── Reaction ─────────────────────────────────────────────────────────────

    private fun fire(app: Context, pkg: String) {
        pendingAlert.set(true)
        val canOverlay = Settings.canDrawOverlays(app)
        UninstallGuard.logDebugTrail("BOOT_GAP_FIRED pkg=$pkg overlay=$canOverlay")

        if (canOverlay) mainHandler.post { showOverlay(app) }
        // Small delay so the overlay window exists before the Home start (matters for
        // background-activity-start rules on newer Android versions).
        mainHandler.postDelayed({ goHome(app) }, HOME_DELAY_MS)
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
            UninstallGuard.logDebugTrail("BOOT_GAP_HOME_FAILED ${e.javaClass.simpleName}")
        }
    }

    private fun showOverlay(app: Context) {
        try {
            if (overlayView == null) {
                val wm = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                // minSdk is 26, so TYPE_APPLICATION_OVERLAY is always available.
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
            mainHandler.removeCallbacks(removeOverlayRunnable)
            mainHandler.postDelayed(removeOverlayRunnable, BLOCK_MS)
        } catch (e: Exception) {
            Log.w(TAG, "boot-gap overlay failed", e)
            UninstallGuard.logDebugTrail("BOOT_GAP_OVERLAY_FAILED ${e.javaClass.simpleName}")
        }
    }

    private fun removeOverlay() {
        mainHandler.removeCallbacks(removeOverlayRunnable)
        val view = overlayView ?: return
        val wm = overlayWindowManager
        overlayView = null
        overlayWindowManager = null
        try {
            wm?.removeView(view)
        } catch (e: Exception) {
            Log.w(TAG, "boot-gap overlay failed to remove", e)
        }
    }

    private fun flushPendingAlert(app: Context) {
        if (pendingAlert.getAndSet(false) && UninstallGuard.shouldAlert()) {
            UninstallGuard.listener?.onGuardedScreenBlocked(app, ALERT_REASON)
        }
    }

    // ── Preconditions ────────────────────────────────────────────────────────

    /** True if AppAutomationService is in the OS's enabled-accessibility-services list
     *  (same check BootReceiver uses for its own watchdog-disabled report). */
    private fun isWatchdogEnabledInSettings(app: Context): Boolean {
        val enabled = Settings.Secure.getString(
            app.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val serviceId = "${app.packageName}/$ACCESSIBILITY_SERVICE_CLASS"
        return enabled.split(':').any { it.equals(serviceId, ignoreCase = true) }
    }

    private fun hasUsageAccess(app: Context): Boolean {
        val appOps = app.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), app.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), app.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }
}
