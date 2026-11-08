package com.checkmate.workmode

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import com.checkmate.core.CheckmatePrefs
import com.checkmate.core.TelemetryClient
import com.checkmate.workmode.KillGapPolicy.Verdict
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * KillGapGuard — notices that Checkmate was killed from outside while it should have been
 * enforcing, and reacts as soon as its process is running again.
 *
 * BUGFIX (T8b, System Booster "Boost now" loophole): "Boost now" force-stops Checkmate. That
 * kills the process, cancels every alarm and job, and makes Android drop the accessibility
 * service from ENABLED_ACCESSIBILITY_SERVICES; a force-stopped app receives no broadcast, so
 * nothing inside it can run until the user (or an external watchdog) starts it again. This
 * guard therefore cannot prevent the kill. What it adds is detection on the next process start
 * plus a proportionate reaction, so a kill is no longer a silent way out:
 *
 *  1. The accessibility service writes a heartbeat ([heartbeat], from its 30 s tick) and marks
 *     a clean shutdown from onDestroy ([onServiceDestroyed]).
 *  2. [checkOnStart] runs first thing in CheckmateApp.onCreate (before AccessibilityRepair
 *     re-enables the service, so the dropped state is still visible). It feeds
 *     [KillGapPolicy] with the heartbeat, clean flag, boot count, PIN state, enabled state,
 *     enforcement state and, on API 30+, Android's own process-exit history
 *     (REASON_USER_REQUESTED = force stop / task killer).
 *  3. On [Verdict.KILL_SUSPECTED] it logs KILL_GAP_DETECTED (debug trail + telemetry) and
 *     queues the reaction. [applyPending] runs at the END of CheckmateApp.onCreate (the guardian
 *     alert listener is wired by then): opens a post-skip lockdown, asks the service for a forced
 *     window sweep, re-arms the daily Work Mode alarms and sends the usual guardian alert.
 *
 * Not covered, by design of Android: nothing runs while the app stays force-stopped. An
 * external restorer (Termux watchdog, JarvisMerged self-heal) or the user reopening Checkmate
 * is what brings the process back; the zero-code mitigation is to exempt Checkmate in the
 * booster's protected list and from battery optimization (see [batteryExemptionIntent]).
 */
object KillGapGuard {

    private const val TAG = "KillGapGuard"

    private const val KEY_LAST_ALIVE = "kill_gap_last_alive_ms"
    private const val KEY_CLEAN = "kill_gap_clean_shutdown"
    private const val KEY_BOOT_COUNT = "kill_gap_boot_count"
    private const val KEY_HANDLED_EXIT_TS = "kill_gap_handled_exit_ts"
    private const val KEY_HANDLED_ALIVE = "kill_gap_handled_alive_ms"

    private const val HEARTBEAT_MIN_INTERVAL_MS = 30_000L
    private const val ALERT_REASON = "accessibility_killed_gap"

    @Volatile private var lastHeartbeatElapsed = 0L
    private val pending = AtomicBoolean(false)

    /** Call from AppAutomationService's periodic tick and onServiceConnected. Cheap, throttled. */
    fun heartbeat(context: Context) {
        val e = SystemClock.elapsedRealtime()
        if (lastHeartbeatElapsed != 0L && e - lastHeartbeatElapsed < HEARTBEAT_MIN_INTERVAL_MS) return
        lastHeartbeatElapsed = e
        CheckmatePrefs.putLong(KEY_LAST_ALIVE, TrustedTime.nowMillis())
        CheckmatePrefs.putBoolean(KEY_CLEAN, false)
        CheckmatePrefs.putInt(KEY_BOOT_COUNT, bootCount(context))
    }

    /** Call from AppAutomationService.onDestroy(): the service shut down in an orderly way
     *  (for example it was disabled through the guardian-PIN-gated path), not killed. */
    fun onServiceDestroyed() {
        CheckmatePrefs.putBoolean(KEY_CLEAN, true)
    }

    /**
     * Call first thing in CheckmateApp.onCreate, BEFORE AccessibilityRepair.ensureEnabled.
     * Returns the verdict (also logged). Never throws.
     */
    fun checkOnStart(context: Context): Verdict? {
        return try {
            val app = context.applicationContext
            val lastAlive = CheckmatePrefs.getLong(KEY_LAST_ALIVE, 0L)
            val now = TrustedTime.nowMillis()
            val exitTs = newestUserRequestedExit(app)
            val handledExit = CheckmatePrefs.getLong(KEY_HANDLED_EXIT_TS, 0L)
            val newExit = exitTs > handledExit
            if (newExit) CheckmatePrefs.putLong(KEY_HANDLED_EXIT_TS, exitTs)

            // Same heartbeat already reported once: don't alert again on every restart.
            if (lastAlive != 0L && lastAlive == CheckmatePrefs.getLong(KEY_HANDLED_ALIVE, 0L) && !newExit) {
                return Verdict.GAP_TOO_SHORT
            }

            val savedBoot = CheckmatePrefs.getInt(KEY_BOOT_COUNT, -1)
            val rebooted = savedBoot != -1 && bootCount(app) != savedBoot

            val verdict = KillGapPolicy.evaluate(
                lastAliveMs = lastAlive,
                nowMs = now,
                cleanShutdown = CheckmatePrefs.getBoolean(KEY_CLEAN, false),
                rebooted = rebooted,
                pinUnlocked = UninstallGuard.isUnlocked() || UninstallGuard.hasRemoteOverride(),
                userRequestedExit = newExit,
                serviceEnabled = BootGapGuard.isAccessibilityServiceEnabled(app),
                windowActive = WorkModeManager.isEnforcing() || WorkModeManager.isInPostSkipLockdown()
            )

            val gapMs = if (lastAlive > 0L) now - lastAlive else -1L
            UninstallGuard.logDebugTrail("KILL_GAP_CHECK verdict=$verdict gapMs=$gapMs userRequestedExit=$newExit")

            if (verdict == Verdict.KILL_SUSPECTED) {
                CheckmatePrefs.putLong(KEY_HANDLED_ALIVE, lastAlive)
                UninstallGuard.logDebugTrail("KILL_GAP_DETECTED gapMs=$gapMs userRequestedExit=$newExit")
                TelemetryClient.emit(
                    "KILL_GAP_DETECTED", null, status = "violation",
                    payload = JSONObject()
                        .put("gap_ms", gapMs)
                        .put("user_requested_exit", newExit)
                        .put("service_enabled", BootGapGuard.isAccessibilityServiceEnabled(app))
                )
                pending.set(true)
            }
            verdict
        } catch (e: Exception) {
            Log.w(TAG, "kill-gap check failed", e)
            null
        }
    }

    /**
     * Call at the END of CheckmateApp.onCreate (after WorkModeManager.init, the alarm re-arm and
     * the UninstallGuard listener wiring). Does nothing unless [checkOnStart] queued a reaction.
     */
    fun applyPending(context: Context) {
        if (!pending.getAndSet(false)) return
        val app = context.applicationContext
        try {
            // Re-arm the four daily boundary alarms: a force stop cancelled them.
            WorkModeScheduleReceiver.scheduleDailyAlarms(app)
            // Re-assert enforcement: open the timed lockdown and sweep whatever is in front.
            WorkModeManager.evaluateSchedule(app)
            WorkModeManager.startPostSkipLockdown(app)
            LockdownSweepRequest.request(SystemClock.elapsedRealtime())
            UninstallGuard.logDebugTrail("KILL_GAP_REACTED lockdown+alarms")
        } catch (e: Exception) {
            Log.w(TAG, "kill-gap reaction failed", e)
            UninstallGuard.logDebugTrail("KILL_GAP_REACT_FAILED ${e.javaClass.simpleName}")
        }
        try {
            if (UninstallGuard.shouldAlert()) UninstallGuard.listener?.onGuardedScreenBlocked(app, ALERT_REASON)
        } catch (e: Exception) {
            Log.w(TAG, "kill-gap alert failed", e)
        }
    }

    // ── Battery optimisation exemption (zero-code mitigation helper) ─────────

    /** True when Android is already told not to battery-optimise Checkmate. */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * Intent for the exemption: the direct per-app prompt when the manifest declares
     * REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, otherwise the system's list screen. The caller
     * starts it from a visible Activity (FLAG_ACTIVITY_NEW_TASK is set so a Context works too).
     */
    fun batteryExemptionIntent(context: Context): Intent {
        val direct = context.packageManager.checkPermission(
            "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS", context.packageName
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        return (if (direct)
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
        else
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** Timestamp of the newest "user requested" process exit (force stop / task killer), or 0. */
    private fun newestUserRequestedExit(app: Context): Long {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return 0L
        return try {
            val am = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.getHistoricalProcessExitReasons(app.packageName, 0, 10)
                .filter { it.reason == ApplicationExitInfo.REASON_USER_REQUESTED }
                .maxOfOrNull { it.timestamp } ?: 0L
        } catch (e: Exception) {
            Log.w(TAG, "exit history unavailable", e)
            0L
        }
    }

    private fun bootCount(context: Context): Int = try {
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
    } catch (e: Exception) { -1 }
}
