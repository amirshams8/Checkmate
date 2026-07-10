package com.checkmate

import android.app.Application
import android.content.Context
import com.checkmate.core.CheckmatePrefs
import com.checkmate.core.CheckmateState
import com.checkmate.core.tts.CheckmateTTS
import com.checkmate.service.GuardianNotifier
import com.checkmate.service.ScreenCaptureManager
import com.checkmate.workmode.DistractionGuard
import com.checkmate.workmode.DistractionListener
import com.checkmate.workmode.UninstallAlertListener
import com.checkmate.workmode.UninstallGuard
import com.checkmate.workmode.WorkModeManager
import com.checkmate.workmode.WorkModeScheduleReceiver

class CheckmateApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CheckmatePrefs.init(this)
        CheckmateState.init(this)
        CheckmateTTS.init(this)

        // Reconcile Work Mode with the hardcoded 19:00-02:00 daily schedule
        // and re-arm the two daily boundary alarms (AlarmManager repeating
        // alarms don't survive a reboot, hence also doing this in
        // BootReceiver). This must run after CheckmateState.init() above.
        WorkModeManager.init(this)
        WorkModeScheduleReceiver.scheduleDailyAlarms(this)
        GuardianNotifier.scheduleEndOfDaySummary(this)
        // Every 30 min: pushes an app-usage Telegram alert + caches it in the
        // worker's KV for the on-demand "usage" command (see worker.js).
        GuardianNotifier.scheduleUsageReports(this)
        // ScreenshotSharer.pruneOldScreenshots() removed — ScreenshotSharer deleted

        // Wire real screenshot capture via MediaProjection into DistractionGuard
        DistractionGuard.listener = object : DistractionListener {
            override fun onAlertThresholdReached(context: Context, kind: String, target: String) {
                Thread {
                    val uri = ScreenCaptureManager.capture(context)
                    GuardianNotifier.notifyDistractionAlert(context, kind, target, uri)
                }.start()
            }
        }

        // Wire uninstall/disable-screen alerts from :automation's AppAutomationService
        // (via :workmode's UninstallGuard) into GuardianNotifier.
        UninstallGuard.listener = object : UninstallAlertListener {
            override fun onGuardedScreenBlocked(context: Context, reason: String) {
                Thread {
                    GuardianNotifier.notifyUninstallAttempt(context, reason)
                }.start()
            }
        }
    }
}
