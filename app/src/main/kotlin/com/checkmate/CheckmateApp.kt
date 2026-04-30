package com.checkmate

import android.app.Application
import com.checkmate.core.CheckmatePrefs
import com.checkmate.core.CheckmateState
import com.checkmate.core.tts.CheckmateTTS
import com.checkmate.service.GuardianNotifier

class CheckmateApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CheckmatePrefs.init(this)
        CheckmateState.init(this)
        CheckmateTTS.init(this)
        // Schedules 9 PM daily EOD summary alarm. setRepeating with FLAG_UPDATE_CURRENT
        // is idempotent — safe to call on every launch.
        GuardianNotifier.scheduleEndOfDaySummary(this)
    }
}
