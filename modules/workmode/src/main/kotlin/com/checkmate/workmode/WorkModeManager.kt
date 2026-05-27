package com.checkmate.workmode

import android.content.Context
import android.content.Intent
import android.os.Build
import com.checkmate.core.CheckmatePrefs
import com.checkmate.core.CheckmateState
import com.checkmate.core.StudyMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object WorkModeManager {

    private val _isActive = MutableStateFlow(false)
    val isActive: StateFlow<Boolean> = _isActive.asStateFlow()

    fun activate(context: Context) {
        CheckmateState.setMode(context, StudyMode.STUDY)
        _isActive.value = true
        startService(context)
    }

    fun deactivate(context: Context) {
        CheckmateState.setMode(context, StudyMode.NORMAL)
        _isActive.value = false
        DistractionGuard.reset()
        context.stopService(Intent(context, WorkModeService::class.java))
    }

    fun toggle(context: Context) {
        if (_isActive.value) deactivate(context) else activate(context)
    }

    /** Returns package names of apps to block. */
    fun getBlockedApps(): Set<String> {
        val saved = CheckmatePrefs.getString("blocked_apps", "") ?: ""
        return saved.split(",").filter { it.isNotBlank() }.toSet()
    }

    /**
     * Returns hostnames of websites to block (e.g. "youtube.com", "instagram.com").
     * Stored as comma-separated values under "blocked_domains".
     */
    fun getBlockedDomains(): Set<String> {
        val saved = CheckmatePrefs.getString("blocked_domains", "") ?: ""
        return saved.split(",").filter { it.isNotBlank() }.toSet()
    }

    private fun startService(context: Context) {
        val intent = Intent(context, WorkModeService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            context.startForegroundService(intent)
        else
            context.startService(intent)
    }
}
