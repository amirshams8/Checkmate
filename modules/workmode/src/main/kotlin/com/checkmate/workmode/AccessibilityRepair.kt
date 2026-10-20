package com.checkmate.workmode

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log

/**
 * AccessibilityRepair — lets Checkmate put its own accessibility watchdog
 * (AppAutomationService) back into ENABLED_ACCESSIBILITY_SERVICES when Android drops it
 * (force stop, reinstall, OEM cleanup) or it was never on.
 *
 * Android only lets an app write that setting if it holds WRITE_SECURE_SETTINGS, which
 * can't be requested at runtime. Grant it once from a computer / Termux adb session:
 *
 *   adb shell pm grant com.checkmate android.permission.WRITE_SECURE_SETTINGS
 *
 * The grant survives reboots and force stops. Without it [ensureEnabled] is a harmless
 * no-op that just reports false, and the guardian alert path (WatchdogMonitor) takes over.
 */
object AccessibilityRepair {

    private const val TAG = "AccessibilityRepair"
    private const val SERVICE_CLASS = "com.checkmate.automation.AppAutomationService"

    fun canRepair(context: Context): Boolean =
        context.applicationContext.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED

    /** True if the watchdog is (now) enabled in system settings. Never throws. */
    fun ensureEnabled(context: Context): Boolean {
        val app = context.applicationContext
        try {
            if (BootGapGuard.isAccessibilityServiceEnabled(app)) return true
            if (!canRepair(app)) {
                UninstallGuard.logDebugTrail("A11Y_REPAIR_SKIP reason=no_write_secure_settings")
                return false
            }
            val cr = app.contentResolver
            val ours = ComponentName(app.packageName, SERVICE_CLASS).flattenToString()
            val current = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            val merged = (current.split(':').map { it.trim() }.filter { it.isNotEmpty() } + ours)
                .distinct()
                .joinToString(":")
            Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, merged)
            Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            val ok = BootGapGuard.isAccessibilityServiceEnabled(app)
            UninstallGuard.logDebugTrail("A11Y_REPAIR_DONE ok=$ok")
            return ok
        } catch (e: Exception) {
            Log.w(TAG, "repair failed", e)
            UninstallGuard.logDebugTrail("A11Y_REPAIR_FAILED ${e.javaClass.simpleName}")
            return false
        }
    }
}
