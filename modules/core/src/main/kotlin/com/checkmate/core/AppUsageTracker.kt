package com.checkmate.core

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** One app's foreground time for a given window (today, by default). */
data class AppUsageEntry(
    val packageName: String,
    val label: String,
    val foregroundMillis: Long
)

/** Total foreground screen time for a single day, used for the weekly history chart. */
data class DailyScreenTime(
    val dayLabel: String,
    val totalMillis: Long
)

/**
 * AppUsageTracker — Digital-Wellbeing-style on-device usage stats reader.
 *
 * Backed by UsageStatsManager. Requires the user to grant "Usage access" in
 * Settings → Apps → Special app access → Usage access — this is a protected
 * permission (PACKAGE_USAGE_STATS is already declared in the manifest) and
 * cannot be granted via a normal runtime permission dialog, only via that
 * settings screen (see SettingsScreen's "Usage Access" tile).
 *
 * All reads are local — nothing here talks to the network. GuardianNotifier
 * uses buildUsageReportText() to push a text summary to the guardian via
 * Telegram/WhatsApp.
 */
object AppUsageTracker {

    /** True once the user has granted Usage Access for this app. */
    fun hasUsageAccess(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /**
     * Today's per-app foreground time, sorted by time spent (desc).
     * Excludes Checkmate itself and apps under 1 minute of usage.
     * Returns an empty list (never throws) if Usage Access isn't granted.
     */
    fun getTodayUsage(context: Context, limit: Int = 8): List<AppUsageEntry> {
        if (!hasUsageAccess(context)) return emptyList()

        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val startTime = startOfTodayMillis()
        val endTime = System.currentTimeMillis()

        val stats = try {
            usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, startTime, endTime)
        } catch (e: Exception) {
            null
        } ?: return emptyList()

        val pm = context.packageManager
        return stats
            .filter { it.totalTimeInForeground >= 60_000L && it.packageName != context.packageName }
            .groupBy { it.packageName }
            .map { (pkg, group) -> pkg to group.sumOf { it.totalTimeInForeground } }
            .mapNotNull { (pkg, millis) ->
                val label = resolveLabel(pm, pkg) ?: return@mapNotNull null
                AppUsageEntry(pkg, label, millis)
            }
            .sortedByDescending { it.foregroundMillis }
            .take(limit)
    }

    /** Total tracked screen time today, in millis, across all apps (not just the top [limit]). */
    fun getTodayTotalMillis(context: Context): Long =
        getTodayUsage(context, limit = Int.MAX_VALUE).sumOf { it.foregroundMillis }

    /**
     * Last [days] days of total foreground screen time, oldest first —
     * feeds a Digital-Wellbeing-style weekly history bar chart.
     */
    fun getScreenTimeHistory(context: Context, days: Int = 7): List<DailyScreenTime> {
        if (!hasUsageAccess(context)) return emptyList()

        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val dayFmt = SimpleDateFormat("EEE", Locale.getDefault())
        val result = mutableListOf<DailyScreenTime>()

        for (i in (days - 1) downTo 0) {
            val dayStart = Calendar.getInstance().apply {
                add(Calendar.DAY_OF_YEAR, -i)
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            val start = dayStart.timeInMillis
            val end = (start + 24L * 60 * 60 * 1000) - 1
            val stats = try {
                usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, end)
            } catch (e: Exception) {
                null
            } ?: emptyList()

            val total = stats
                .filter { it.packageName != context.packageName }
                .sumOf { it.totalTimeInForeground }
            result.add(DailyScreenTime(dayFmt.format(dayStart.time), total))
        }
        return result
    }

    /** Plain-text usage summary for guardian reports (Telegram / WhatsApp EOD message). */
    fun buildUsageReportText(context: Context): String {
        if (!hasUsageAccess(context)) return "App usage: permission not granted on student's device."
        val apps = getTodayUsage(context, limit = 5)
        val total = getTodayTotalMillis(context)
        if (apps.isEmpty()) return "App usage today: no significant usage recorded."

        return buildString {
            appendLine("App Usage Today (total ${formatDuration(total)})")
            apps.forEach { appendLine("  - ${it.label}: ${formatDuration(it.foregroundMillis)}") }
        }.trim()
    }

    fun formatDuration(millis: Long): String {
        val totalMinutes = millis / 60_000L
        val h = totalMinutes / 60
        val m = totalMinutes % 60
        return if (h > 0) "${h}h ${m}m" else "${m}m"
    }

    private fun startOfTodayMillis(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun resolveLabel(pm: PackageManager, pkg: String): String? = try {
        val info = pm.getApplicationInfo(pkg, 0)
        pm.getApplicationLabel(info).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }
}
