package com.checkmate.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.checkmate.automation.AutomationEngine
import com.checkmate.core.AppUsageTracker
import com.checkmate.core.CheckmatePrefs
import com.checkmate.core.ConsultationProfile
import com.checkmate.core.DailyChecklist
import com.checkmate.planner.PlanStore
import com.checkmate.planner.model.TaskState
import com.checkmate.psyche.PsycheEngine
import com.checkmate.workmode.UninstallGuard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.Calendar

object GuardianNotifier {

    private const val TAG = "GuardianNotifier"
    const val ACTION_EOD_SUMMARY   = "com.checkmate.EOD_SUMMARY"
    // Fires every 30 min — pushes a Telegram usage alert AND caches the same
    // report in the worker's KV so a guardian texting "usage" any time in
    // between gets an instant cached reply instead of nothing.
    const val ACTION_USAGE_REPORT  = "com.checkmate.USAGE_REPORT"
    // Fires weekly — builds PsycheEngine's weekly report and pushes it to
    // the guardian. Previously nothing ever called this: PsycheEngine.
    // getGuardianWeeklyReport() built the report string but no alarm,
    // receiver, or call site existed anywhere in the app, so the weekly
    // report never actually went out. This action + its alarm/receiver is
    // the fix.
    const val ACTION_WEEKLY_REPORT = "com.checkmate.WEEKLY_REPORT"

    // GuardianNotifier is a singleton object living for the app process's
    // lifetime, same as it being wired from CheckmateApp.onCreate — a
    // module-level scope here follows the same pattern used by long-lived
    // services elsewhere (e.g. AttentionCycleService, ReminderService).
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun notifyTaskStarted(context: Context, subject: String, topic: String, durationMinutes: Int) {
        val number = getGuardianNumber() ?: return
        val msg = "Checkmate: Started $subject — $topic (${durationMinutes}min) at ${timeNow()}"
        openWhatsAppAndSend(context, number, msg)
        Log.d(TAG, "Guardian notified: task started")
    }

    // Mentor v2 (spec 3.6): distractionApp is the label of whatever app was foregrounded
    // right before the skip (from AppUsageTracker.getMostRecentForegroundApp, captured by
    // HomeViewModel.markSkip). Null/blank means nothing was detected in the lookback window —
    // message falls back to the original skip-only wording so this never regresses when usage
    // access isn't granted.
    fun notifySkipStreak(context: Context, skipCount: Int, lastTopic: String, distractionApp: String? = null) {
        val number = getGuardianNumber() ?: return
        val distractionClause = if (!distractionApp.isNullOrBlank()) " $distractionApp was open right before." else ""
        val msg = "Checkmate Alert: $skipCount tasks skipped in a row. Last: $lastTopic.$distractionClause Time: ${timeNow()}"
        openWhatsAppAndSend(context, number, msg)
        Log.d(TAG, "Guardian notified: skip streak=$skipCount distractionApp=$distractionApp")
    }

    fun scheduleEndOfDaySummary(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = PendingIntent.getBroadcast(
            context, 9001,
            Intent(context, EodSummaryReceiver::class.java).apply { action = ACTION_EOD_SUMMARY },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 21)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis < System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
        }
        am.setRepeating(AlarmManager.RTC_WAKEUP, cal.timeInMillis, AlarmManager.INTERVAL_DAY, pi)
        Log.d(TAG, "EOD summary alarm set for 21:00 daily")
    }

    /**
     * Schedules a repeating alarm every 30 min that pushes an app-usage
     * report to the guardian via Telegram (sendAppUsageReport). Independent
     * of whether a focus session is active — this is a standalone "Digital
     * Wellbeing" style check-in, not tied to AttentionCycleService.
     */
    fun scheduleUsageReports(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = PendingIntent.getBroadcast(
            context, 9002,
            Intent(context, UsageReportReceiver::class.java).apply { action = ACTION_USAGE_REPORT },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        am.setRepeating(
            AlarmManager.RTC_WAKEUP,
            System.currentTimeMillis() + AlarmManager.INTERVAL_HALF_HOUR,
            AlarmManager.INTERVAL_HALF_HOUR,
            pi
        )
        Log.d(TAG, "Usage report alarm set for every 30 min")
    }

    /**
     * Schedules a repeating weekly alarm (every 7 days, Sunday 20:00) that
     * builds and delivers PsycheEngine's weekly report. This was previously
     * missing entirely — PsycheEngine.getGuardianWeeklyReport() existed and
     * worked, but nothing ever scheduled or invoked it, so no weekly report
     * was ever sent. Mirrors the same fixed-clock-time am.setRepeating
     * pattern as the EOD summary and usage-report alarms above, just with a
     * 7-day interval and a day-of-week anchor instead of a daily one.
     */
    fun scheduleWeeklyReport(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = PendingIntent.getBroadcast(
            context, 9003,
            Intent(context, WeeklyReportReceiver::class.java).apply { action = ACTION_WEEKLY_REPORT },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val cal = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_WEEK, Calendar.SUNDAY)
            set(Calendar.HOUR_OF_DAY, 20)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis < System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 7)
        }
        am.setRepeating(
            AlarmManager.RTC_WAKEUP,
            cal.timeInMillis,
            AlarmManager.INTERVAL_DAY * 7,
            pi
        )
        Log.d(TAG, "Weekly report alarm set for Sunday 20:00, every 7 days")
    }

    /**
     * Builds and sends the daily report to the guardian — tasks, checklist,
     * and (new) an on-device app usage breakdown for the day, Digital
     * Wellbeing-style. Sent via WhatsApp (existing path) and, if a Telegram
     * chat id is configured, also pushed straight to the guardian bot so the
     * Telegram channel carries the same usage history, not just screenshots.
     */
    fun sendEndOfDaySummary(context: Context) {
        val number = getGuardianNumber()
        val tasks   = PlanStore.getTodayTasksSnapshot_Sync()
        val done    = tasks.count { it.state == TaskState.DONE }
        val skipped = tasks.count { it.state == TaskState.SKIPPED }
        val total   = tasks.size
        val pct     = if (total > 0) done * 100 / total else 0

        val taskLines = tasks.joinToString("\n") { t ->
            val icon = when (t.state) {
                TaskState.DONE    -> "done"
                TaskState.SKIPPED -> "skip"
                else              -> "miss"
            }
            "[$icon] ${t.subject}: ${t.topic}"
        }

        val checklistSummary = DailyChecklist.getTodaySummaryText()
        val usageSummary = AppUsageTracker.buildUsageReportText(context)
        val candidateName = ConsultationProfile.candidateDisplayName()

        val msg = buildString {
            appendLine("Checkmate Daily Report — ${dateToday()}")
            appendLine("Candidate: $candidateName")
            appendLine("Tasks: $done/$total ($pct%)")
            if (skipped > 0) appendLine("Skipped: $skipped")
            appendLine()
            appendLine(taskLines)
            if (checklistSummary.isNotBlank()) {
                appendLine()
                appendLine(checklistSummary)
            }
            appendLine()
            appendLine(usageSummary)
        }.trim()

        if (number != null) {
            openWhatsAppAndSend(context, number, msg)
            Log.d(TAG, "EOD summary sent to guardian via WhatsApp")
        }

        // Push the same report straight to the Telegram bot, independent of
        // the WhatsApp path, so guardians who only use Telegram still get it.
        if (TelegramAlertBot.getChatId() != null) {
            Thread {
                TelegramAlertBot.sendAlert(context, msg)
                Log.d(TAG, "EOD summary sent to guardian via Telegram")
            }.start()
        }
    }

    /**
     * Sends the app usage breakdown to the guardian via Telegram (active
     * push, fired every 30 min by scheduleUsageReports), AND caches the same
     * report in the worker's KV so a guardian who texts "usage" any time in
     * between scheduled pushes still gets an instant on-demand reply
     * (worker.js: /usage cache + "usage" command — see Cloudflare Worker).
     * Cached data is at most ~30 min stale, never live — the worker only
     * ever replies from cache, it never wakes the phone.
     */
    fun sendAppUsageReport(context: Context) {
        if (TelegramAlertBot.getChatId() == null) {
            Log.w(TAG, "telegram_chat_id not set — skipping usage report")
            return
        }
        val report = AppUsageTracker.buildUsageReportText(context)
        val candidateName = ConsultationProfile.candidateDisplayName()
        val text = "Checkmate — ${dateToday()}\nCandidate: $candidateName\n$report"
        Thread {
            TelegramAlertBot.sendAlert(context, text)
            // Cache carries the candidate line too, since the worker's "usage"
            // command replies with exactly this cached string.
            StatusReporter.pushUsageReport(context, "Candidate: $candidateName\n$report")
            Log.d(TAG, "Usage report sent via Telegram + cached for on-demand 'usage' command")
        }.start()
    }

    /**
     * Builds PsycheEngine's weekly report (streak, tasks missed, consistency,
     * attention-check stats, week-over-week delta) and delivers it to the
     * guardian via BOTH channels — WhatsApp (existing openWhatsAppAndSend
     * path, same as the daily/EOD report) and Telegram (if a chat id is
     * configured), matching sendEndOfDaySummary's dual-delivery pattern.
     *
     * PsycheEngine.getGuardianWeeklyReport() is a suspend fun (it may call
     * out to LlmGateway), so this runs on GuardianNotifier's own IO-scoped
     * coroutine rather than a raw Thread.
     */
    fun sendWeeklyReport(context: Context) {
        val number = getGuardianNumber()
        val hasTelegram = TelegramAlertBot.getChatId() != null

        if (number == null && !hasTelegram) {
            Log.w(TAG, "No guardian number or Telegram chat id set — skipping weekly report")
            return
        }

        scope.launch {
            val report = try {
                PsycheEngine.getGuardianWeeklyReport()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to build weekly report", e)
                return@launch
            }

            if (number != null) {
                openWhatsAppAndSend(context, number, report)
                Log.d(TAG, "Weekly report sent to guardian via WhatsApp")
            }

            if (hasTelegram) {
                TelegramAlertBot.sendAlert(context, report)
                Log.d(TAG, "Weekly report sent to guardian via Telegram")
            }
        }
    }

    fun notifyDistractionAlert(
        context: Context,
        kind: String,
        target: String,
        screenshotUri: Uri? = null
    ) {
        val msg = when (kind) {
            "app"    -> "⚠️ Checkmate Alert: Student attempted to open blocked app: $target " +
                        "3 times during study session at ${timeNow()}."
            "site"   -> "⚠️ Checkmate Alert: Student attempted to open blocked website: $target " +
                        "3 times during study session at ${timeNow()}."
            "scroll" -> "⚠️ Checkmate Alert: Student hit the continuous-scroll limit in $target " +
                        "at ${timeNow()}."
            else     -> "⚠️ Checkmate Alert: Student attempted to open blocked $target " +
                        "3 times during study session at ${timeNow()}."
        }
        Log.w(TAG, "Distraction alert: $kind/$target")

        val number = getGuardianNumber()
        if (number != null) {
            openWhatsAppAndSend(context, number, msg)
            Log.d(TAG, "Distraction alert sent via WhatsApp (text)")
        }

        Thread {
            TelegramAlertBot.sendAlert(context, msg, screenshotUri)
            Log.d(TAG, "Distraction alert sent via Telegram (screenshot)")
        }.start()
    }

    /**
     * Fired by CheckmateDeviceAdminReceiver and AppAutomationService's
     * UninstallGuard watchdog whenever someone reaches a screen that could
     * uninstall Checkmate, force-stop it, disable its device admin status,
     * or disable its accessibility service. `reason` is a short machine tag
     * (e.g. "settings_screen_blocked", "device_admin_disable_requested")
     * kept out of the guardian-facing text but logged for debugging.
     */
    fun notifyUninstallAttempt(context: Context, reason: String) {
        val candidateName = ConsultationProfile.candidateDisplayName()
        val msg = "🚨 Checkmate Security Alert: $candidateName attempted to uninstall or disable " +
                  "Checkmate at ${timeNow()} on ${dateToday()}."
        Log.w(TAG, "Uninstall attempt: reason=$reason")

        val number = getGuardianNumber()
        if (number != null) {
            openWhatsAppAndSend(context, number, msg)
        }

        if (TelegramAlertBot.getChatId() != null) {
            Thread { TelegramAlertBot.sendAlert(context, msg) }.start()
        }
    }

    /**
     * Fired by BootReceiver when the device just booted into Safe Mode —
     * Safe Mode disables all 3rd-party accessibility services and can bypass
     * device-admin uninstall protection, which is the one gap this app
     * cannot close from the app side. This is a best-effort notice only.
     */
    fun notifySafeModeBoot(context: Context) {
        val candidateName = ConsultationProfile.candidateDisplayName()
        val msg = "⚠️ Checkmate Notice: $candidateName's device booted into Safe Mode at ${timeNow()} " +
                  "on ${dateToday()}. Checkmate's protections are disabled while in Safe Mode."
        Log.w(TAG, "Safe Mode boot detected")
        if (TelegramAlertBot.getChatId() != null) {
            Thread { TelegramAlertBot.sendAlert(context, msg) }.start()
        }
    }

    /**
     * Generates a fresh 6-digit guardian PIN, stores only its hash on-device
     * (UninstallGuard.storeNewPinHash), and sends the plaintext PIN to the
     * guardian's Telegram chat — the only place it's ever visible. Whoever
     * taps "Generate" on the phone (student or guardian) never sees the PIN
     * itself, so generating a new one can't be used to self-authorize.
     *
     * Returns a short status string suitable for showing directly in the UI.
     */
    fun generateAndSendGuardianPin(context: Context, onResult: (success: Boolean, message: String) -> Unit) {
        if (TelegramAlertBot.getChatId() == null) {
            onResult(false, "Set Guardian Telegram Chat ID first")
            return
        }
        if (!UninstallGuard.canRegeneratePin()) {
            onResult(false, "Wait ${UninstallGuard.regenCooldownRemainingSeconds()}s before generating another")
            return
        }
        val pin = UninstallGuard.generateRandomPin()
        UninstallGuard.storeNewPinHash(pin)
        Thread {
            TelegramAlertBot.sendAlert(
                context,
                "🔑 Checkmate Guardian PIN: $pin\n" +
                "Enter this in the app to pass the uninstall-protection watchdog for 2 minutes. " +
                "Keep it private from the student — anyone can request a new one from the phone, " +
                "but only you ever see the code."
            )
            Log.d(TAG, "Guardian PIN generated and sent via Telegram")
        }.start()
        onResult(true, "New PIN sent to guardian via Telegram")
    }

    /** Fired by the unlock screen when UninstallGuard reports a brute-force lockout. */
    fun notifyPinBruteForce(context: Context) {
        val candidateName = ConsultationProfile.candidateDisplayName()
        val msg = "🚨 Checkmate Alert: $candidateName entered the wrong guardian PIN too many times " +
                  "at ${timeNow()} on ${dateToday()}. Unlock is locked for 10 minutes."
        Log.w(TAG, "Guardian PIN brute-force lockout triggered")
        if (TelegramAlertBot.getChatId() != null) {
            Thread { TelegramAlertBot.sendAlert(context, msg) }.start()
        }
    }

    private fun openWhatsAppAndSend(context: Context, number: String, message: String) {
        val clean = number.replace(Regex("[^0-9]"), "")
        if (clean.isBlank()) { Log.e(TAG, "Empty guardian number"); return }

        AutomationEngine.queueWhatsAppMessage(message)

        val intentA = Intent(Intent.ACTION_VIEW,
            Uri.parse("whatsapp://send?phone=$clean")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (runCatching { context.startActivity(intentA) }.isSuccess) {
            Log.d(TAG, "WhatsApp opened via method A"); return
        }

        val intentB = Intent(Intent.ACTION_VIEW,
            Uri.parse("https://wa.me/$clean")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intentB) }
            .onSuccess { Log.d(TAG, "WhatsApp opened via method B") }
            .onFailure { Log.e(TAG, "Both WA methods failed: ${it.message}") }
    }

    private fun getGuardianNumber(): String? {
        val n = CheckmatePrefs.getString("guardian_number", null)
        if (n.isNullOrBlank()) { Log.w(TAG, "Guardian number not set"); return null }
        return n
    }

    private fun timeNow(): String {
        val c = Calendar.getInstance()
        return String.format("%02d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
    }

    private fun dateToday(): String {
        val c = Calendar.getInstance()
        return String.format("%02d/%02d/%04d",
            c.get(Calendar.DAY_OF_MONTH), c.get(Calendar.MONTH) + 1, c.get(Calendar.YEAR))
    }
}

class EodSummaryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == GuardianNotifier.ACTION_EOD_SUMMARY)
            GuardianNotifier.sendEndOfDaySummary(context)
    }
}

class UsageReportReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == GuardianNotifier.ACTION_USAGE_REPORT)
            GuardianNotifier.sendAppUsageReport(context)
    }
}

class WeeklyReportReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == GuardianNotifier.ACTION_WEEKLY_REPORT)
            GuardianNotifier.sendWeeklyReport(context)
    }
}
