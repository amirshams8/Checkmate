package com.checkmate.workmode

import android.content.Context

/**
 * UninstallAlertListener — callback interface injected into UninstallGuard by
 * the app layer (CheckmateApp.onCreate).
 *
 * Mirrors DistractionListener: keeps :workmode (and :automation, which calls
 * UninstallGuard) free of any :app-layer dependency on GuardianNotifier,
 * which would create a circular module dependency.
 */
interface UninstallAlertListener {
    /**
     * Called by UninstallGuard when AppAutomationService detects and blocks
     * a guarded screen (uninstall / force-stop / disable device admin / etc).
     * @param context application context
     * @param reason  short machine-readable reason string, e.g. "settings_screen_blocked"
     */
    fun onGuardedScreenBlocked(context: Context, reason: String)
}
