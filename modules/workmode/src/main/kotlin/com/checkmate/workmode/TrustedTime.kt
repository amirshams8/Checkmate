package com.checkmate.workmode

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.checkmate.core.CheckmatePrefs
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * TrustedTime — closes the "change Settings > Date & time to escape the
 * hardcoded schedule" hole in WorkModeSchedule. Previously
 * isWithinScheduledWindow() read Calendar.getInstance(), i.e. the device's
 * own wall clock — trivially spoofable by the student, which then made
 * WorkModeManager.evaluateSchedule() see "outside the window" and turn
 * blocking off for real.
 *
 * Anchors a network-verified wall-clock time to
 * SystemClock.elapsedRealtime() — monotonic since boot, unaffected by the
 * user changing the device clock — so "now" can be recomputed from that
 * anchor without trusting the device's own clock at all. Re-synced
 * periodically over the network; between syncs (e.g. no internet) the last
 * anchor keeps extrapolating via elapsedRealtime, which stays accurate for
 * as long as the phone hasn't rebooted since the last sync.
 *
 * Deliberately uses plain HttpURLConnection (no new module dependency) — a
 * HEAD request to any of a few always-on HTTPS hosts just to read the
 * response's standard `Date` header. No app-specific backend needed.
 */
object TrustedTime {

    private const val TAG = "TrustedTime"
    private const val KEY_ANCHOR_TRUSTED_MS = "trusted_time_anchor_ms"
    private const val KEY_ANCHOR_ELAPSED_MS = "trusted_time_anchor_elapsed_ms"

    /** Re-sync at most this often — plenty to catch a clock change within one window. */
    private const val REFRESH_INTERVAL_MS = 15 * 60 * 1000L

    // Independent, always-on HTTPS hosts already reachable by this app (Telegram is
    // already a hard network dependency for guardian alerts) — tried in order so one
    // being unreachable doesn't block the sync.
    private val TIME_HOSTS = listOf(
        "https://api.telegram.org",
        "https://www.google.com",
        "https://cloudflare.com"
    )

    private var lastRefreshAttemptAt = 0L
    private val executor = Executors.newSingleThreadExecutor()

    /**
     * Best-effort "true now", immune to device-clock changes made after the
     * last successful sync. Falls back to the raw device clock only when no
     * sync has ever succeeded (first run before any network is seen) —
     * there's nothing else to anchor to at that point.
     */
    fun nowMillis(): Long {
        val anchorTrusted = CheckmatePrefs.getLong(KEY_ANCHOR_TRUSTED_MS, -1L)
        val anchorElapsed = CheckmatePrefs.getLong(KEY_ANCHOR_ELAPSED_MS, -1L)
        if (anchorTrusted <= 0L || anchorElapsed <= 0L) {
            return System.currentTimeMillis()
        }
        val elapsedSinceAnchor = SystemClock.elapsedRealtime() - anchorElapsed
        // Negative only means the device rebooted since the anchor was taken
        // (elapsedRealtime resets on reboot) — the anchor is stale, fall back
        // to the raw clock until the next sync succeeds.
        if (elapsedSinceAnchor < 0) return System.currentTimeMillis()
        return anchorTrusted + elapsedSinceAnchor
    }

    /**
     * Kicks off a background sync if the last attempt was more than
     * REFRESH_INTERVAL_MS ago. Safe to call frequently (e.g. every 30s from
     * AppAutomationService's existing tick) — it no-ops until actually due.
     */
    fun refreshIfNeeded(context: Context) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastRefreshAttemptAt < REFRESH_INTERVAL_MS) return
        lastRefreshAttemptAt = now
        executor.execute { refreshBlocking() }
    }

    private fun refreshBlocking() {
        for (host in TIME_HOSTS) {
            val trustedMs = fetchDateHeader(host) ?: continue
            val elapsedNow = SystemClock.elapsedRealtime()
            CheckmatePrefs.putLong(KEY_ANCHOR_TRUSTED_MS, trustedMs)
            CheckmatePrefs.putLong(KEY_ANCHOR_ELAPSED_MS, elapsedNow)
            Log.d(TAG, "Trusted time synced from $host")
            return
        }
        Log.w(TAG, "Trusted time sync failed — all hosts unreachable")
    }

    private fun fetchDateHeader(urlStr: String): Long? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "HEAD"
                connectTimeout = 8_000
                readTimeout = 8_000
                instanceFollowRedirects = false
            }
            conn.connect()
            val dateHeader = conn.getHeaderField("Date") ?: return null
            val fmt = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("GMT")
            }
            fmt.parse(dateHeader)?.time
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * True if the device's own wall clock currently disagrees with
     * [nowMillis] by more than a few minutes — i.e. someone changed
     * Settings > Date & time (or the device clock is just wrong). Exposed
     * for optional guardian-facing alerting; not required for the schedule
     * fix itself, since WorkModeSchedule now reads [nowMillis] directly
     * rather than the raw device clock.
     */
    fun isDeviceClockSuspicious(toleranceMs: Long = 5 * 60 * 1000L): Boolean {
        val anchorTrusted = CheckmatePrefs.getLong(KEY_ANCHOR_TRUSTED_MS, -1L)
        if (anchorTrusted <= 0L) return false // no anchor synced yet — nothing to compare against
        return abs(System.currentTimeMillis() - nowMillis()) > toleranceMs
    }
}
