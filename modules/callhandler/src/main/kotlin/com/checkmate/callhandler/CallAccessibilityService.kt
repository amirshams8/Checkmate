package com.checkmate.callhandler

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * Watches for incoming call screen during study mode
 * and shows a heads-up: "In study session — will call back."
 */
class CallAccessibilityService : AccessibilityService() {

    private const val TAG = "CallAccessibility"

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (!ModeGuard.isStudyMode()) return

        val pkg = event.packageName?.toString() ?: return
        if (pkg.contains("dialer") || pkg.contains("incall") || pkg == "com.android.server.telecom") {
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                Log.d(TAG, "Call screen detected during study — no action, call is silenced by screening")
            }
        }
    }

    override fun onInterrupt() {}
}
