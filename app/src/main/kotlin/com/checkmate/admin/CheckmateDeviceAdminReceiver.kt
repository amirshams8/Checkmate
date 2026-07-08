package com.checkmate.admin

import android.app.admin.DeviceAdminReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import com.checkmate.service.GuardianNotifier

/**
 * CheckmateDeviceAdminReceiver — makes Checkmate a Device Admin app.
 *
 * Effect: once activated (Settings → Security → Device admin apps → Checkmate),
 * Android hides the plain "Uninstall" button on Checkmate's App Info page —
 * the user must first deactivate device admin, which routes back through
 * Android's own "Deactivate this device admin app?" screen. AppAutomationService
 * watches for that screen (and the App Info uninstall screen) via UninstallGuard
 * and kicks back to home unless a guardian PIN was entered first — see
 * UninstallGuard.kt in :modules:workmode.
 *
 * This receiver itself just reacts to the two admin lifecycle events that
 * matter for guardian visibility: someone requesting deactivation, and
 * deactivation actually completing (e.g. via `adb shell dpm remove-active-admin`,
 * which UninstallGuard's accessibility watchdog cannot intercept).
 */
class CheckmateDeviceAdminReceiver : DeviceAdminReceiver() {

    companion object {
        private const val TAG = "DeviceAdminReceiver"

        fun componentName(context: Context): ComponentName =
            ComponentName(context.applicationContext, CheckmateDeviceAdminReceiver::class.java)
    }

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Log.d(TAG, "Device admin enabled")
    }

    /**
     * Called when the user reaches Android's built-in disable-confirmation
     * screen. We cannot block this screen (it's owned by the OS), but we can
     * make sure the guardian knows immediately — the accessibility watchdog
     * in UninstallGuard fires the same alert the moment it detects the screen,
     * so this is a second, guaranteed-delivery signal in case the watchdog
     * was killed by an OEM battery optimizer.
     */
    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        Log.w(TAG, "Device admin disable REQUESTED")
        Thread { GuardianNotifier.notifyUninstallAttempt(context, "device_admin_disable_requested") }.start()
        return "Deactivating this will allow Checkmate to be uninstalled. Your guardian will be notified."
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Log.w(TAG, "Device admin DISABLED")
        Thread { GuardianNotifier.notifyUninstallAttempt(context, "device_admin_disabled") }.start()
    }
}
