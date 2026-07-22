package com.checkmate

import android.app.Application
import android.content.Context
import com.checkmate.core.CheckmatePrefs
import com.checkmate.core.CheckmateState
import com.checkmate.core.tts.CheckmateTTS
import com.checkmate.service.GuardianNotifier
import com.checkmate.service.ProactiveMentor
import com.checkmate.service.ScreenCaptureManager
import com.checkmate.workmode.DistractionGuard
import com.checkmate.workmode.DistractionListener
import com.checkmate.workmode.ScrollGuard
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

        // Reconcile Work Mode with the hardcoded daily schedule (usual
        // 19:00-05:00 window every day, plus an extra 01:00-17:30 window on
        // Sunday/Wednesday) and re-arm the four daily boundary alarms
        // (AlarmManager repeating alarms don't survive a reboot, hence also
        // doing this in BootReceiver). This must run after
        // CheckmateState.init() above.
        WorkModeManager.init(this)
        WorkModeScheduleReceiver.scheduleDailyAlarms(this)
        GuardianNotifier.scheduleEndOfDaySummary(this)
        // Every 30 min: pushes an app-usage Telegram alert + caches it in the
        // worker's KV for the on-demand "usage" command (see worker.js).
        GuardianNotifier.scheduleUsageReports(this)
        // Weekly (Sunday 20:00): builds and delivers PsycheEngine's weekly
        // guardian report via WhatsApp + Telegram. Previously never
        // scheduled anywhere, which is why the weekly report never sent.
        GuardianNotifier.scheduleWeeklyReport(this)
        // ScreenshotSharer.pruneOldScreenshots() removed — ScreenshotSharer deleted

        // Wire real screenshot capture via MediaProjection into DistractionGuard
        // (and ScrollGuard, which reuses the same listener/pipeline for its
        // "scroll" kind — see ScrollGuard.kt)
        val distractionListener = object : DistractionListener {
            override fun onAlertThresholdReached(context: Context, kind: String, target: String) {
                Thread {
                    val uri = ScreenCaptureManager.capture(context)
                    GuardianNotifier.notifyDistractionAlert(context, kind, target, uri)
                }.start()
                // Mentor v2 (spec 3.2): also logs into Mentor's persisted chat, independent of
                // the guardian-facing alert above — this is student-facing, not parent-facing.
                ProactiveMentor.onDistractionThreshold(this@CheckmateApp, kind, target)
            }
        }
        DistractionGuard.listener = distractionListener
        ScrollGuard.listener = distractionListener

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
