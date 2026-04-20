package com.checkmate.core

import android.content.Context

enum class StudyMode { NORMAL, STUDY, BREAK, SLEEP }

object CheckmateState {
    private const val PREFS = "checkmate_state"
    private const val KEY_MODE = "mode"

    var currentMode: StudyMode = StudyMode.NORMAL
        private set

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_MODE, StudyMode.NORMAL.name)
        currentMode = StudyMode.valueOf(saved ?: StudyMode.NORMAL.name)
    }

    fun setMode(context: Context, mode: StudyMode) {
        currentMode = mode
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_MODE, mode.name).apply()
    }
}
