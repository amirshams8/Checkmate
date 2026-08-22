package com.checkmate.core

import android.content.Context
import android.content.SharedPreferences

object CheckmatePrefs {
    private const val PREFS_NAME = "checkmate_prefs"
    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun ready() = ::prefs.isInitialized

    fun putString(key: String, value: String)   { if (ready()) prefs.edit().putString(key, value).apply() }
    fun getString(key: String, def: String? = null): String? = if (ready()) prefs.getString(key, def) else def
    fun putBoolean(key: String, value: Boolean) { if (ready()) prefs.edit().putBoolean(key, value).apply() }
    fun getBoolean(key: String, def: Boolean = false): Boolean = if (ready()) prefs.getBoolean(key, def) else def
    fun putInt(key: String, value: Int)         { if (ready()) prefs.edit().putInt(key, value).apply() }
    fun getInt(key: String, def: Int = 0): Int  = if (ready()) prefs.getInt(key, def) else def
    fun putLong(key: String, value: Long)       { if (ready()) prefs.edit().putLong(key, value).apply() }
    fun getLong(key: String, def: Long = 0L): Long = if (ready()) prefs.getLong(key, def) else def
    fun remove(key: String)                     { if (ready()) prefs.edit().remove(key).apply() }

    /**
     * All stored keys starting with [prefix] — e.g. "plan_" -> every day that has a
     * saved plan. Added for DayHistorySyncManager, which has no other way to discover
     * which day keys exist locally: PlanStore/DailyChecklist/DailyCheckIn each derive
     * a single day's key on demand (todayKey()/keyForDay()) but keep no index of which
     * days were ever written, so syncing "all history" requires reading SharedPreferences'
     * own key set directly rather than asking those objects for a list they don't have.
     */
    fun allKeysWithPrefix(prefix: String): Set<String> =
        if (ready()) prefs.all.keys.filter { it.startsWith(prefix) }.toSet() else emptySet()
}
