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
}
