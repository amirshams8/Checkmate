package com.checkmate.workmode

import android.content.Context
import android.util.Log

/**
 * ScrollGuard — interrupts continuous scrolling in short-form-video feeds
 * (Instagram, YouTube — Reels/Shorts) before it turns into unbounded
 * doomscrolling.
 *
 * Unlike DistractionGuard, this runs ALWAYS, independent of Work Mode or any
 * task session — per product decision, it is not gated on
 * WorkModeManager.isEnforcing() and its counters are NOT wiped by
 * WorkModeManager.deactivate(). Instead each package's count self-resets
 * whenever scrolling pauses for more than [SCROLL_PAUSE_RESET_MS] — i.e. it
 * tracks one continuous scrolling session, not a lifetime total.
 *
 * Counters live in memory only; process death naturally resets them, which
 * is fine here — this is a soft nudge against binge-scrolling, not a
 * security boundary like UninstallGuard.
 *
 * Thread-safety: all mutations are @Synchronized, same convention as
 * DistractionGuard.
 */
object ScrollGuard {

    private const val TAG = "ScrollGuard"

    /** Packages this watches. Not user/guardian-configurable — always on, per product decision. */
    val WATCHED_PACKAGES = setOf(
        "com.instagram.android",
        "com.google.android.youtube"
    )

    /** Consecutive scrolls (each no more than [SCROLL_PAUSE_RESET_MS] apart) before bouncing home. */
    const val SCROLL_THRESHOLD = 15

    /** A gap this long between scroll events counts as "stopped scrolling" — counter resets. */
    private const val SCROLL_PAUSE_RESET_MS = 4_000L

    private data class Session(var count: Int, var lastScrollAt: Long)

    // packageName → in-progress scroll session
    private val sessions = mutableMapOf<String, Session>()

    /**
     * Injected by CheckmateApp.onCreate, same DistractionListener used by
     * DistractionGuard — kind = "scroll" — so guardian alert delivery
     * (WhatsApp/Telegram + Mentor chat) is reused rather than duplicated.
     */
    var listener: DistractionListener? = null

    /**
     * Called by AppAutomationService on every TYPE_VIEW_SCROLLED event for a
     * watched package. Returns true if the threshold was just hit (caller
     * should bounce to Home) — the counter is reset internally either way
     * once it fires.
     */
    @Synchronized
    fun recordScroll(context: Context, packageName: String): Boolean {
        val now = System.currentTimeMillis()
        val session = sessions.getOrPut(packageName) { Session(0, now) }

        if (now - session.lastScrollAt > SCROLL_PAUSE_RESET_MS) {
            session.count = 0
        }
        session.count += 1
        session.lastScrollAt = now

        Log.d(TAG, "Scroll: $packageName -> ${session.count}")

        if (session.count >= SCROLL_THRESHOLD) {
            session.count = 0
            listener?.onAlertThresholdReached(context, "scroll", packageName)
                ?: Log.w(TAG, "No DistractionListener set — scroll alert suppressed")
            return true
        }
        return false
    }

    /** Call when a watched package leaves the foreground, so switching away and back later
     *  starts a fresh scrolling session instead of resuming a stale count. */
    @Synchronized
    fun onAppBackgrounded(packageName: String) {
        sessions.remove(packageName)
    }
}
