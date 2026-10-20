package com.checkmate

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.checkmate.workmode.UninstallGuard

/**
 * GuardianUnlockReceiver - adb/automation entry point for the SAME guardian-PIN unlock the
 * on-screen gate uses, so tooling (e.g. Claude over adb) can open the 2-minute disable window
 * only when handed the guardian's PIN. There is deliberately no PIN-free path here.
 *
 *   adb shell am broadcast -n com.checkmate/.GuardianUnlockReceiver \
 *       -a com.checkmate.action.GUARDIAN_UNLOCK --es pin <PIN>
 *
 * Reuses UninstallGuard.unlockWithPin, so the 5-wrong-tries / 10-minute lockout applies and a
 * failed or locked-out attempt is only recorded in the debug trail (the PIN is never logged).
 * The result is returned as the broadcast result data (OK / WRONG_PIN / NO_PIN / LOCKED_OUT).
 */
class GuardianUnlockReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val pin = intent.getStringExtra("pin").orEmpty()
        val result = UninstallGuard.unlockWithPin(pin)
        val label = when (result) {
            is UninstallGuard.UnlockResult.Success -> "OK"
            is UninstallGuard.UnlockResult.WrongPin -> "WRONG_PIN"
            is UninstallGuard.UnlockResult.NoPinConfigured -> "NO_PIN"
            is UninstallGuard.UnlockResult.LockedOut -> "LOCKED_OUT"
        }
        UninstallGuard.logDebugTrail("ADB_UNLOCK result=$label")
        if (result !is UninstallGuard.UnlockResult.Success &&
            result !is UninstallGuard.UnlockResult.NoPinConfigured &&
            UninstallGuard.shouldAlert()
        ) {
            UninstallGuard.listener?.onGuardedScreenBlocked(context.applicationContext, "adb_unlock_$label")
        }
        if (isOrderedBroadcast) setResultData(label)
    }

    companion object {
        const val ACTION = "com.checkmate.action.GUARDIAN_UNLOCK"
    }
}
