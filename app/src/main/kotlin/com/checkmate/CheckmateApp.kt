package com.checkmate

import android.app.Application
import android.content.Context
import com.checkmate.core.CheckmatePrefs
import com.checkmate.core.CheckmateState
import com.checkmate.core.tts.CheckmateTTS
import com.checkmate.planner.intervention.InterventionGuardianBridge
import com.checkmate.planner.intervention.InterventionGuardianGateway
import com.checkmate.planner.intervention.InterventionNotificationBridge
import com.checkmate.planner.intervention.InterventionReconciliation
import com.checkmate.planner.intervention.InterventionTriggerScheduler
import com.checkmate.planner.model.StudyTask
import com.checkmate.psyche.BehaviorLedger
import com.checkmate.service.GuardianNotifier
import com.checkmate.service.InterventionNotifier
import com.checkmate.service.OutcomeLedgerSyncManager
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
        // Upgrade Blueprint Phase 0 item #3 ("Confirm Room is single source of truth"):
        // opens BehaviorDatabase, imports any pre-upgrade CheckmatePrefs event history
        // into it once, and loads the in-memory read cache — see BehaviorLedger.init()'s
        // own doc for why this runs synchronously here, same as CheckmatePrefs.init()
        // right above. Must run before anything below can call BehaviorLedger or
        // PsycheEngine (WorkModeManager.init() does, via the skip-rate check).
        BehaviorLedger.init(this)

        // Reconcile Work Mode with the hardcoded daily schedule (usual
        // 19:00-05:00 window every day, plus an extra 01:00-15:10 window on
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

        // Proactive Execution Engine (step 9, Blueprint §16): wire the notification gateway
        // BEFORE scheduling the periodic trigger evaluation below, so there's no window
        // where a worker run could find InterventionNotificationBridge.gateway still null
        // and silently fall back to the deterministic path when a notification was actually
        // intended. InterventionNotifier is `app`'s implementation — the only module that
        // can see both Android's NotificationManager and psyche's ContextBuilder at once
        // (see InterventionNotificationGateway's doc for why planner can't be this itself).
        InterventionNotificationBridge.gateway = InterventionNotifier

        // Fixes a gap flagged in ActionExecutor's own comment: a resolved REQUEST_GUARDIAN
        // intent always resolved the transaction, but nothing downstream ever notified
        // anyone. Same settable-listener seam as InterventionNotificationBridge just above —
        // GuardianNotifier already knows how to reach the guardian (WhatsApp + Telegram);
        // this just gives the intervention pipeline a way to call it without planner
        // depending on `app` directly.
        InterventionGuardianBridge.gateway = object : InterventionGuardianGateway {
            override fun notifyGuardianRequested(context: Context, task: StudyTask, transactionId: String) {
                GuardianNotifier.notifyInterventionGuardianRequest(context, task.subject, task.topic)
            }
        }

        // Proactive Execution Engine (step 7): periodic deterministic trigger evaluation
        // (WorkManager, not AlarmManager — it reschedules itself after reboot, so unlike
        // the AlarmManager schedules above it needs no BootReceiver re-arming), plus a
        // one-shot sweep for any InterventionTransaction left non-terminal by a killed
        // process (Blueprint §4) — same fire-and-forget-at-startup pattern as the
        // reconciliation call added to BootReceiver below.
        InterventionTriggerScheduler.schedulePeriodicEvaluation(this)
        InterventionReconciliation.runAtStartup(this)

        // Step 12 wiring: reinstall-recovery restore for the Outcome Ledger, mirroring
        // the profile pull pattern (ProfileSyncManager.pullProfileIfLocalEmpty) but at
        // app startup rather than lazily on a specific screen open — there's no single
        // "ledger screen" whose open would naturally trigger this the way Planner/
        // ConsultationProfile do for the profile pull. No-op if the local ledger table
        // already has rows, or if no sync_code is configured — see
        // OutcomeLedgerSyncManager.pullLedgerIfLocalEmpty's own doc.
        OutcomeLedgerSyncManager.pullLedgerIfLocalEmpty(this)

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
