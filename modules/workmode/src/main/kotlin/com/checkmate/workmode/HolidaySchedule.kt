package com.checkmate.workmode

import com.checkmate.core.CheckmatePrefs
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * HolidaySchedule — the one guardian-controlled exception to WorkModeSchedule's otherwise
 * hardcoded, student-unreachable lock window (see the design note atop WorkModeSchedule).
 *
 * A "holiday" here is a specific calendar date a guardian has explicitly marked exempt — on
 * that date, both the usual 19:00-07:00 window and the Sunday/Wednesday extra 01:00-17:30
 * window are skipped entirely (see WorkModeSchedule.isWithinScheduledWindow).
 *
 * This is intentionally NOT reachable without a guardian PIN unlock. SettingsScreen's
 * HolidaySettings wraps add/remove behind the same WorkModeLockGate every other Work Mode
 * setting uses. Without that gate, "mark today a holiday" would just be a self-service
 * schedule bypass with extra steps — exactly what WorkModeSchedule's own doc comment argues
 * a compile-time-constant schedule is meant to prevent. This object itself has no PIN check
 * of its own (same division of responsibility as UninstallGuard.grantRemoteOverride()) — the
 * caller is responsible for gating writes.
 *
 * Dates are stored as "yyyy_dayOfYear" keys, matching PlanStore.keyForDay's convention, so a
 * holiday list here lines up with the same day boundaries the rest of the app already uses.
 */
object HolidaySchedule {

    private const val KEY_HOLIDAYS = "workmode_holiday_dates"

    private fun keyFor(cal: Calendar) = "${cal.get(Calendar.YEAR)}_${cal.get(Calendar.DAY_OF_YEAR)}"

    /** True if [cal]'s calendar date has been marked a holiday by the guardian. */
    fun isHoliday(cal: Calendar = Calendar.getInstance()): Boolean = keyFor(cal) in getHolidayKeys()

    /** Raw "yyyy_dayOfYear" keys, unsorted. Prefer [getSortedHolidays] for display. */
    fun getHolidayKeys(): Set<String> {
        val saved = CheckmatePrefs.getString(KEY_HOLIDAYS, "") ?: ""
        return saved.split(",").map { it.trim() }.filter { it.isNotBlank() }.toSet()
    }

    /** Holiday keys paired with a human-readable label (e.g. "Aug 15, 2026"), soonest first. */
    fun getSortedHolidays(): List<Pair<String, String>> =
        getHolidayKeys()
            .mapNotNull { key -> parseKey(key)?.let { key to it } }
            .sortedBy { it.second.timeInMillis }
            .map { (key, cal) -> key to SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(cal.time) }

    /**
     * Adds [cal]'s calendar date to the holiday list. Caller (SettingsScreen's
     * HolidaySettings) is responsible for gating this behind a guardian PIN unlock.
     */
    fun addHoliday(cal: Calendar) {
        CheckmatePrefs.putString(KEY_HOLIDAYS, (getHolidayKeys() + keyFor(cal)).joinToString(","))
    }

    /** Removes a holiday by its raw "yyyy_dayOfYear" key. Same PIN-gating responsibility as [addHoliday]. */
    fun removeHoliday(key: String) {
        CheckmatePrefs.putString(KEY_HOLIDAYS, (getHolidayKeys() - key).joinToString(","))
    }

    private fun parseKey(key: String): Calendar? {
        val parts = key.split("_")
        if (parts.size != 2) return null
        val year = parts[0].toIntOrNull() ?: return null
        val doy = parts[1].toIntOrNull() ?: return null
        return Calendar.getInstance().apply {
            set(Calendar.YEAR, year)
            set(Calendar.DAY_OF_YEAR, doy)
        }
    }
}
