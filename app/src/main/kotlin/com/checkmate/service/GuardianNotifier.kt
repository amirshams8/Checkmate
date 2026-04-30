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
import com.checkmate.planner.PlanStore
import com.checkmate.planner.model.TaskState
import java.util.Calendar

/**
 * GuardianNotifier — all 3 auto-triggered guardian WhatsApp messages.
 *
 * Delivery flow:
 *   1. AutomationEngine.queueWhatsAppMessage(text) — stores the message
 *   2. openWhatsAppAndSend() — launches WhatsApp to guardian chat via intent
 *   3. AppAutomationService detects WhatsApp open → calls tryTypeAndSend()
 *      → finds input field → sets text → clicks send button
 *
 * Intent method A (whatsapp://send) is tried first — opens directly to chat.
 * Method B (wa.me) is the fallback if A fails on this device.
 */
object GuardianNotifier {

    private const val TAG = "GuardianNotifier"
    const val ACTION_EOD_SUMMARY = "com.checkmate.EOD_SUMMARY"

    // ── Trigger 1: Task Started ──────────────────────────────────────────────

    fun notifyTaskStarted(context: Context, subject: String, topic: String, durationMinutes: Int) {
        val number = getGuardianNumber() ?: return
        val msg = "Checkmate: Started $subject — $topic (${durationMinutes}min) at ${timeNow()}"
        openWhatsAppAndSend(context, number, msg)
        Log.d(TAG, "Guardian notified: task started")
    }

    // ── Trigger 2: Skip Streak ───────────────────────────────────────────────

    fun notifySkipStreak(context: Context, skipCount: Int, lastTopic: String) {
        val number = getGuardianNumber() ?: return
        val msg = "Checkmate Alert: $skipCount tasks skipped in a row. Last: $lastTopic. Time: ${timeNow()}"
        openWhatsAppAndSend(context, number, msg)
        Log.d(TAG, "Guardian notified: skip streak=$skipCount")
    }

    // ── Trigger 3: End of Day Summary (9 PM alarm) ───────────────────────────

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

        val lines = tasks.joinToString("\n") { t ->
            val icon = when (t.state) {
                TaskState.DONE    -> "done"
                TaskState.SKIPPED -> "skip"
                else              -> "miss"
            }
            "[$icon] ${t.subject}: ${t.topic}"
        }

        val msg = buildString {
            appendLine("Checkmate Daily Report — ${dateToday()}")
            appendLine("Completed: $done/$total ($pct%)")
            if (skipped > 0) appendLine("Skipped: $skipped")
            appendLine()
            appendLine(lines)
        }.trim()

        openWhatsAppAndSend(context, number, msg)
        Log.d(TAG, "EOD summary sent to guardian")
    }

    // ── WhatsApp delivery (method A with B fallback) ─────────────────────────

    private fun openWhatsAppAndSend(context: Context, number: String, message: String) {
        val clean = number.replace(Regex("[^0-9]"), "")
        if (clean.isBlank()) { Log.e(TAG, "Empty guardian number"); return }

        // Queue text — AutomationEngine / AppAutomationService will type + send
        AutomationEngine.queueWhatsAppMessage(message)

        // Method A: whatsapp://send — direct to chat, no browser, no extra tap
        val intentA = Intent(Intent.ACTION_VIEW,
            Uri.parse("whatsapp://send?phone=$clean")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (runCatching { context.startActivity(intentA) }.isSuccess) {
            Log.d(TAG, "WhatsApp opened via method A")
            return
        }

        // Method B: wa.me fallback (browser redirect)
        val intentB = Intent(Intent.ACTION_VIEW,
            Uri.parse("https://wa.me/$clean")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intentB) }
            .onSuccess { Log.d(TAG, "WhatsApp opened via method B fallback") }
            .onFailure { Log.e(TAG, "Both WhatsApp methods failed: ${it.message}") }
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
