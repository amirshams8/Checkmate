package com.checkmate.workmode

import android.content.Context

/**
 * DistractionListener — callback interface injected into DistractionGuard by the
 * app layer (CheckmateApp.onCreate).
 *
 * This keeps the :workmode module free of any :app-layer dependencies
 * (ScreenshotCapture, GuardianNotifier) which would create a circular dependency.
 */
interface DistractionListener {
    /**
     * Called by DistractionGuard when a student hits the attempt threshold.
     * @param context  application context
     * @param kind     "app" or "site"
     * @param target   package name or hostname
     */
    fun onAlertThresholdReached(context: Context, kind: String, target: String)
}
