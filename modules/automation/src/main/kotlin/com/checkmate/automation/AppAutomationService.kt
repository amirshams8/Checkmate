package com.checkmate.automation

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.checkmate.workmode.DistractionGuard
import com.checkmate.workmode.ScrollGuard
import com.checkmate.workmode.UninstallGuard
import com.checkmate.workmode.WorkModeManager
import java.util.ArrayDeque

class AppAutomationService : AccessibilityService() {

    private val TAG      = "WA_SEND"
    private val GUARD_TAG = "UninstallGuard"
    private val WHATSAPP = "com.whatsapp"
    private val SELF_PKG get() = packageName // "com.checkmate"

    // ── Browser packages whose URL bar we scan for blocked domains ────────────
    // All major browsers expose their address bar as an accessible text node.
    private val BROWSER_PACKAGES = setOf(
        "com.android.chrome",           // Chrome (system on most devices)
        "com.chrome.beta",
        "com.chrome.dev",
        "com.chrome.canary",
        "org.mozilla.firefox",
        "org.mozilla.firefox_beta",
        "org.mozilla.focus",
        "com.microsoft.emmx",           // Edge
        "com.brave.browser",
        "com.opera.browser",
        "com.opera.mini.native",
        "com.sec.android.app.sbrowser", // Samsung Internet
        "com.UCMobile.intl",            // UC Browser
        "com.duckduckgo.mobile.android",
        "mark.via.gp",                  // Via Browser
        "com.kiwibrowser.browser"
    )

    // Address bar view IDs per browser package.
    // Tried in order; first non-null text match wins.
    private val URL_BAR_IDS = mapOf(
        "com.android.chrome"            to listOf("com.android.chrome:id/url_bar"),
        "com.chrome.beta"               to listOf("com.chrome.beta:id/url_bar"),
        "com.chrome.dev"                to listOf("com.chrome.dev:id/url_bar"),
        "com.chrome.canary"             to listOf("com.chrome.canary:id/url_bar"),
        "org.mozilla.firefox"           to listOf("org.mozilla.firefox:id/mozac_browser_toolbar_url_view",
                                                   "org.mozilla.firefox:id/url_bar_title"),
        "org.mozilla.firefox_beta"      to listOf("org.mozilla.firefox_beta:id/mozac_browser_toolbar_url_view"),
        "org.mozilla.focus"             to listOf("org.mozilla.focus:id/display_url"),
        "com.microsoft.emmx"            to listOf("com.microsoft.emmx:id/url_bar"),
        "com.brave.browser"             to listOf("com.brave.browser:id/url_bar"),
        "com.opera.browser"             to listOf("com.opera.browser:id/url_field"),
        "com.sec.android.app.sbrowser" to listOf("com.sec.android.app.sbrowser:id/location_bar_edit_text"),
        "com.duckduckgo.mobile.android" to listOf("com.duckduckgo.mobile.android:id/omnibarTextInput")
    )
    // Generic fallback content-descriptions used across unknown browsers
    private val URL_BAR_DESCRIPTIONS = listOf("Address bar", "Search or type URL", "URL", "address")

    // Track last seen URL per browser package to avoid firing on every tiny content change
    private val lastSeenUrl = mutableMapOf<String, String>()

    // WhatsApp automation state
    private var typingAttempted = false
    private var lastWindowClass = ""

    // Throttle for reconciling WorkModeManager against the hardcoded daily
    // schedule (WorkModeSchedule) — cheap timestamp check, real work only
    // happens at most once per 30s even though accessibility events can
    // fire dozens of times a second.
    private var lastScheduleCheckAt = 0L

    // Last foreground package, used only to detect when a ScrollGuard-watched
    // app leaves the foreground so its scroll session resets cleanly.
    private var lastForegroundPkg = ""

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "=== AutomationService CONNECTED ===")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString() ?: return

        // ── Hardcoded schedule reconciliation ──────────────────────────────────
        // Keeps Work Mode's forced 19:00-05:00 window (plus the extra Sun/Wed 01:00-17:30 window) enforced even if the
        // student never starts a task, or if the process restarted mid-window
        // and lost its in-memory isActive flag.
        val now = System.currentTimeMillis()
        if (now - lastScheduleCheckAt > 30_000L) {
            lastScheduleCheckAt = now
            WorkModeManager.evaluateSchedule(applicationContext)
        }

        // ── Anti-scroll: always-on doomscroll interrupt for Reels/Shorts ───────
        // Deliberately independent of Work Mode / isEnforcing() — this applies
        // at all times, not just during study sessions, per product decision.
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && pkg != lastForegroundPkg) {
            if (lastForegroundPkg in ScrollGuard.WATCHED_PACKAGES) {
                ScrollGuard.onAppBackgrounded(lastForegroundPkg)
            }
            lastForegroundPkg = pkg
        }
        if (pkg in ScrollGuard.WATCHED_PACKAGES && event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            if (ScrollGuard.recordScroll(this, pkg)) {
                performGlobalAction(GLOBAL_ACTION_HOME)
            }
            return
        }

        // ── Mentor v2 (spec 3.4): post-skip escalation lockdown ────────────────
        // Independent of, and checked before, the normal Work Mode blocklist below:
        // during the timed window opened by WorkModeManager.startPostSkipLockdown()
        // (HomeViewModel.markSkip()), any app on the escalation watchlist is bounced
        // immediately on first foreground — no 3-attempt grace period like the normal
        // DistractionGuard flow. Still records the attempt (so the guardian alert and
        // BehaviorLedger correlation data stay consistent), just doesn't wait for a
        // 3rd try before acting.
        if (WorkModeManager.isInPostSkipLockdown() &&
            event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            pkg in WorkModeManager.getEscalationWatchlist()
        ) {
            performGlobalAction(GLOBAL_ACTION_HOME)
            DistractionGuard.recordAppAttempt(this, pkg)
            return
        }

        // ── Work Mode: blocked app check ─────────────────────────────────────
        // isEnforcing() (not the raw isActive flag) so the hardcoded window
        // blocks apps even when no manual task session is running.
        if (WorkModeManager.isEnforcing() &&
            event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {

            val blockedApps = WorkModeManager.getBlockedApps()
            if (pkg in blockedApps) {
                performGlobalAction(GLOBAL_ACTION_HOME)
                // Record attempt — alert guardian on 3rd
                DistractionGuard.recordAppAttempt(this, pkg)
                return
            }
        }

        // ── Work Mode: blocked website check ─────────────────────────────────
        if (WorkModeManager.isEnforcing() && pkg in BROWSER_PACKAGES) {
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
                event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                checkAndBlockUrl(pkg)
            }
        }

        // ── Uninstall / device-admin-disable / accessibility-disable watchdog ──
        // Runs regardless of Work Mode — uninstall protection is always on.
        if (pkg in UninstallGuard.WATCHED_PACKAGES &&
            (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
             event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)) {
            checkGuardedScreen()
        }

        // ── WhatsApp automation ───────────────────────────────────────────────
        if (pkg != WHATSAPP) return
        if (!AutomationEngine.hasPendingMessage()) return

        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val cls = event.className?.toString() ?: ""
        if (cls != lastWindowClass) {
            typingAttempted = false
            lastWindowClass = cls
        }
        if (typingAttempted) return

        val root = rootInActiveWindow ?: run { Log.e(TAG, "root null"); return }
        val found = tryTypeAndSend(root)
        if (!found) {
            Handler(Looper.getMainLooper()).postDelayed({
                if (!AutomationEngine.hasPendingMessage()) return@postDelayed
                rootInActiveWindow?.let { tryTypeAndSend(it) }
            }, 1200)
        }
    }

    // ── Uninstall / disable watchdog ────────────────────────────────────────────

    /**
     * Scans the current window's visible text for a combination of "this is
     * about Checkmate" + "this is an uninstall/force-stop/disable screen".
     * If matched and no guardian PIN unlock is active, bounces to Home and
     * fires a throttled guardian alert. Deliberately conservative: requires
     * BOTH signals so we never interfere with unrelated Settings browsing.
     */
    private fun checkGuardedScreen() {
        if (UninstallGuard.isUnlocked()) return

        val root = rootInActiveWindow ?: return
        val text = collectAllText(root)
        val lower = text.lowercase()

        val targetsCheckmate = lower.contains("checkmate") || lower.contains(SELF_PKG.lowercase())
        val isNamedGuardedScreen = UninstallGuard.looksLikeGuardedScreen(text, targetsCheckmate)
        val isDeviceAdminPrompt  = UninstallGuard.isDeviceAdminPrompt(text)
        if (!isNamedGuardedScreen && !isDeviceAdminPrompt) return

        Log.w(GUARD_TAG, "Guarded screen detected — bouncing to Home")
        performGlobalAction(GLOBAL_ACTION_HOME)

        if (UninstallGuard.shouldAlert()) {
            UninstallGuard.listener?.onGuardedScreenBlocked(applicationContext, "settings_screen_blocked")
        }
    }

    /** Depth-first collection of all text + content-description on screen, space-joined. */
    private fun collectAllText(root: AccessibilityNodeInfo): String {
        val sb = StringBuilder()
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)
        var visited = 0
        while (stack.isNotEmpty() && visited < 400) { // cap traversal — Settings trees are small
            val node = stack.removeFirst()
            visited++
            node.text?.let { sb.append(it).append(' ') }
            node.contentDescription?.let { sb.append(it).append(' ') }
            for (i in 0 until node.childCount) node.getChild(i)?.let { stack.add(it) }
        }
        return sb.toString()
    }

    // ── URL scanning ──────────────────────────────────────────────────────────

    private fun checkAndBlockUrl(browserPkg: String) {
        val root = rootInActiveWindow ?: return
        val url  = extractUrl(root, browserPkg) ?: return

        // Debounce: only act when URL actually changes
        if (lastSeenUrl[browserPkg] == url) return
        lastSeenUrl[browserPkg] = url

        val hostname = parseHostname(url) ?: return
        Log.d(TAG, "Browser $browserPkg → hostname=$hostname")

        val blockedDomains = WorkModeManager.getBlockedDomains()
        val matched = blockedDomains.firstOrNull { blocked ->
            hostname == blocked || hostname.endsWith(".$blocked")
        } ?: return

        Log.w(TAG, "Blocked domain matched: $matched — going back")
        performGlobalAction(GLOBAL_ACTION_BACK)
        // Record and potentially alert
        DistractionGuard.recordDomainAttempt(this, matched)
    }

    /**
     * Tries known view IDs for the browser first, then falls back to
     * scanning all text nodes whose content-description suggests a URL bar.
     */
    private fun extractUrl(root: AccessibilityNodeInfo, pkg: String): String? {
        // Try known IDs for this browser
        val ids = URL_BAR_IDS[pkg] ?: emptyList()
        for (id in ids) {
            val node = root.findAccessibilityNodeInfosByViewId(id)?.firstOrNull()
            val text = node?.text?.toString()?.trim()
            if (!text.isNullOrBlank()) return text
        }

        // Generic fallback: find any node whose description sounds like an address bar
        // and whose text looks like a URL
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            val node = stack.removeFirst()
            val desc = node.contentDescription?.toString() ?: ""
            val text = node.text?.toString()?.trim() ?: ""
            if (URL_BAR_DESCRIPTIONS.any { desc.contains(it, ignoreCase = true) }
                && looksLikeUrl(text)) {
                return text
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let { stack.add(it) }
        }
        return null
    }

    private fun looksLikeUrl(text: String): Boolean {
        if (text.isBlank() || text.length < 4) return false
        return text.contains('.') &&
               !text.contains(' ') &&
               (text.startsWith("http") || text.startsWith("www.") || text.matches(Regex(".*\\.[a-z]{2,}.*")))
    }

    /**
     * Parses the registered hostname from a raw URL string.
     * "https://www.youtube.com/watch?v=..." → "youtube.com"
     *
     * Strips "www." so the stored domain "youtube.com" matches both
     * "youtube.com" and "www.youtube.com".
     */
    private fun parseHostname(raw: String): String? {
        return try {
            val withScheme = if (raw.startsWith("http")) raw else "https://$raw"
            val host = Uri.parse(withScheme).host ?: return null
            host.removePrefix("www.").lowercase().trim()
        } catch (_: Exception) { null }
    }

    // ── WhatsApp typing ───────────────────────────────────────────────────────

    private fun tryTypeAndSend(root: AccessibilityNodeInfo): Boolean {
        val message = AutomationEngine.peekPendingWhatsAppMessage() ?: return false
        Log.d(TAG, "tryTypeAndSend msg='${message.take(40)}'")

        val inputNode =
            findNodeById(root, "com.whatsapp:id/entry")
                ?.also { Log.d(TAG, "input via id/entry") }
            ?: findNodeById(root, "com.whatsapp:id/conversation_entry")
                ?.also { Log.d(TAG, "input via id/conversation_entry") }
            ?: findNodeByClass(root, "android.widget.EditText")
                ?.also { Log.d(TAG, "input via EditText fallback") }

        if (inputNode == null) { Log.e(TAG, "input field not found"); return false }

        typingAttempted = true
        inputNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)

        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, message)
        }
        val textSet = inputNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        Log.d(TAG, "ACTION_SET_TEXT=$textSet")

        if (!textSet) { Log.e(TAG, "SET_TEXT failed — message kept in queue"); return true }

        AutomationEngine.consumePendingWhatsAppMessage()

        Handler(Looper.getMainLooper()).postDelayed({
            val freshRoot = rootInActiveWindow ?: run { Log.e(TAG, "send: root null"); return@postDelayed }
            val sendNode =
                findNodeById(freshRoot, "com.whatsapp:id/send")
                    ?.also { Log.d(TAG, "send btn via id/send") }
                ?: findNodeByDesc(freshRoot, "Send")
                    ?.also { Log.d(TAG, "send btn via desc") }
            sendNode?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                .also { Log.d(TAG, "send clicked: $it") }
                ?: Log.e(TAG, "send button not found")
        }, 500)

        return true
    }

    // ── Node finders ──────────────────────────────────────────────────────────

    private fun findNodeById(root: AccessibilityNodeInfo, id: String): AccessibilityNodeInfo? =
        root.findAccessibilityNodeInfosByViewId(id)?.firstOrNull()

    private fun findNodeByClass(root: AccessibilityNodeInfo, className: String): AccessibilityNodeInfo? {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            val node = stack.removeFirst()
            if (node.className?.toString() == className && node.isEditable) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let { stack.add(it) }
        }
        return null
    }

    private fun findNodeByDesc(root: AccessibilityNodeInfo, desc: String): AccessibilityNodeInfo? {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            val node = stack.removeFirst()
            if (node.contentDescription?.toString()?.contains(desc, ignoreCase = true) == true
                && node.isClickable) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let { stack.add(it) }
        }
        return null
    }

    override fun onInterrupt() { Log.d(TAG, "Service interrupted") }
}
