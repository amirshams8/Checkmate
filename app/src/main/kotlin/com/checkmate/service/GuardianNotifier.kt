package com.checkmate.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.checkmate.automation.AutomationEngine
import com.checkmate.core.CheckmatePrefs
import com.checkmate.core.DailyChecklist
import com.checkmate.planner.PlanStore
import com.checkmate.planner.model.TaskState
import java.util.Calendar

object GuardianNotifier {

    private const val TAG = "GuardianNotifier"
    const val ACTION_EOD_SUMMARY = "com.checkmate.EOD_SUMMARY"

    fun notifyTaskStarted(context: Context, subject: String, topic: String, durationMinutes: Int) {
        val number = getGuardianNumber() ?: return
        val msg = "Checkmate: Started $subject — $topic (${durationMinutes}min) at ${timeNow()}"
        openWhatsAppAndSend(context, number, msg)
        Log.d(TAG, "Guardian notified: task started")
    }

    fun notifySkipStreak(context: Context, skipCount: Int, lastTopic: String) {
        val number = getGuardianNumber() ?: return
        val msg = "Checkmate Alert: $skipCount tasks skipped in a row. Last: $lastTopic. Time: ${timeNow()}"
        openWhatsAppAndSend(context, number, msg)
        Log.d(TAG, "Guardian notified: skip streak=$skipCount")
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

    fun sendEndOfDaySummary(context: Context) {
        val number = getGuardianNumber() ?: return
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

        val msg = buildString {
            appendLine("Checkmate Daily Report — ${dateToday()}")
            appendLine("Tasks: $done/$total ($pct%)")
            if (skipped > 0) appendLine("Skipped: $skipped")
            appendLine()
            appendLine(taskLines)
            if (checklistSummary.isNotBlank()) {
                appendLine()
                appendLine(checklistSummary)
            }
        }.trim()

        openWhatsAppAndSend(context, number, msg)
        Log.d(TAG, "EOD summary sent to guardian")
    }

    fun notifyDistractionAlert(
        context: Context,
        kind: String,
        target: String,
        screenshotUri: Uri? = null
    ) {
        val label = if (kind == "app") "app: $target" else "website: $target"
        val msg   = "⚠️ Checkmate Alert: Student attempted to open blocked $label " +
                    "3 times during study session at ${timeNow()}."
        Log.w(TAG, "Distraction alert: $label")

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
