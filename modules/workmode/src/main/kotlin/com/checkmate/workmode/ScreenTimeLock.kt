package com.checkmate.workmode

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.checkmate.core.AppUsageTracker
import com.checkmate.core.CheckmatePrefs

/**
 * Daily screen-time lock: once today's total foreground time reaches the limit
 * (default 3 h), only an allowlist of essential apps stays usable. Everything
 * else that has a launcher icon is sent Home. Resets at midnight with the usage stats.
 * Suspended during the daily 16:30-18:00 free window (see [WorkModeSchedule.isInFreeWindow]).
 *
 * Deliberately separate from [WorkModeManager.essentialPackages]: that set exempts apps
 * from the study-time blocklist, and widening it (WhatsApp, Apple Music, ...) would
 * weaken study blocking. This allowlist only applies once the daily limit is hit.
 *
 * Safety: only packages with a launcher activity can be blocked, so system UI, the
 * keyboard, permission/ADB dialogs and the in-call UI are never touched.
 */
object ScreenTimeLock {

    const val DEFAULT_LIMIT_MS = 3L * 60 * 60 * 1000

    // Stored as strings through CheckmatePrefs, same idiom as the rest of WorkModeManager.
    private const val KEY_ENABLED = "screen_time_lock_enabled"      // "0" disables; default on
    private const val KEY_LIMIT_MS = "screen_time_limit_ms"
    private const val KEY_EXTRA = "screen_time_allowed_extra"        // comma-separated pkgs

    private const val USAGE_CACHE_MS = 30_000L
    private const val ALLOWED_CACHE_MS = 60_000L

    /** Fixed allowlist: Phone, Gemini, Checkmate, WhatsApp, music apps, FamApp, ChatGPT, Jarvis, PW. */
    private val FIXED_ALLOWED = setOf(
        "com.google.android.dialer",
        "com.google.android.apps.bard",                 // Gemini
        "com.whatsapp",
        "com.google.android.apps.youtube.music",        // YT Music
        "com.apple.android.music",                      // Apple Music
        "com.fampay.in",                                // FamApp
        "com.openai.chatgpt",
        "com.jarvismini",                               // Jarvis
        "com.jarvismini.devtools",
        "xyz.penpencil.physicswala",                    // PW (PhysicsWallah)
        // The rest of the Music folder:
        "com.sony.songpal",                             // Music Center
        "com.sony.songpal.mdr",                         // Sound Connect
        "com.fiio.control",
        "com.pittvandewitt.wavelet",
        "com.android.musicfx"
    )

    private var cachedLocked = false
    private var cachedLockedAt = -USAGE_CACHE_MS
    private var cachedAllowed: Set<String> = emptySet()
    private var cachedAllowedAt = -ALLOWED_CACHE_MS

    fun isEnabled(): Boolean = (CheckmatePrefs.getString(KEY_ENABLED, "") ?: "") != "0"

    fun setEnabled(enabled: Boolean) {
        CheckmatePrefs.putString(KEY_ENABLED, if (enabled) "1" else "0")
        cachedLockedAt = -USAGE_CACHE_MS
    }

    fun limitMs(): Long =
        (CheckmatePrefs.getString(KEY_LIMIT_MS, "") ?: "").toLongOrNull()?.takeIf { it > 0 }
            ?: DEFAULT_LIMIT_MS

    /**
     * True once today's total screen time has reached the limit. Cached for 30 s.
     * Never locked during the daily 16:30-18:00 free window ([WorkModeSchedule.isInFreeWindow]);
     * the lock resumes at 18:00 if the limit has been hit.
     */
    @Synchronized
    fun isLocked(context: Context): Boolean {
        if (!isEnabled()) return false
        if (WorkModeSchedule.isInFreeWindow()) return false
        val now = SystemClock.elapsedRealtime()
        if (now - cachedLockedAt >= USAGE_CACHE_MS) {
            cachedLockedAt = now
            cachedLocked = try {
                AppUsageTracker.getTodayTotalMillis(context) >= limitMs()
            } catch (_: Exception) {
                cachedLocked // keep the last known state if the usage query fails
            }
        }
        return cachedLocked
    }

    /** Apps that stay usable once the limit is hit. Cached for 60 s. */
    @Synchronized
    fun allowedPackages(context: Context): Set<String> {
        val now = SystemClock.elapsedRealtime()
        if (now - cachedAllowedAt < ALLOWED_CACHE_MS && cachedAllowed.isNotEmpty()) return cachedAllowed
        val pm = context.packageManager
        val result = mutableSetOf<String>()

        // Launcher, dialer, Settings, Checkmate, ChatGPT, Gemini, PW, YT Music, ...
        result += WorkModeManager.essentialPackages(context)
        result += FIXED_ALLOWED

        // Every installed music app, however it's packaged.
        try {
            val music = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MUSIC)
            pm.queryIntentActivities(music, 0).forEach { it.activityInfo?.packageName?.let(result::add) }
        } catch (_: Exception) { }

        // Google apps, except the Play Store and YouTube (YT Music is allowed above).
        try {
            @Suppress("DEPRECATION")
            pm.getInstalledApplications(0)
                .map { it.packageName }
                .filter { it.startsWith("com.google.") && !it.contains("youtube") }
                .forEach(result::add)
        } catch (_: Exception) { }
        result -= "com.android.vending"

        // Guardian-extendable without a rebuild.
        (CheckmatePrefs.getString(KEY_EXTRA, "") ?: "")
            .split(",").map { it.trim() }.filterTo(result) { it.isNotBlank() }

        cachedAllowed = result
        cachedAllowedAt = now
        return result
    }

    /** True if [pkg] should be bounced right now: limit hit, not allowlisted, user-facing app. */
    fun shouldBlock(context: Context, pkg: String): Boolean {
        if (pkg == context.packageName) return false
        if (!isLocked(context)) return false
        if (pkg in allowedPackages(context)) return false
        return try {
            context.packageManager.getLaunchIntentForPackage(pkg) != null
        } catch (_: Exception) { false }
    }
}
