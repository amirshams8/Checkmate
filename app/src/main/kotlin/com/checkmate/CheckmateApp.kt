package com.checkmate

import android.app.Application
import com.checkmate.core.CheckmatePrefs
import com.checkmate.core.CheckmateState
import com.checkmate.core.tts.CheckmateTTS
import com.checkmate.service.GuardianNotifier
import com.checkmate.service.ScreenshotSharer

class CheckmateApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CheckmatePrefs.init(this)
        CheckmateState.init(this)
        CheckmateTTS.init(this)
        GuardianNotifier.scheduleEndOfDaySummary(this)
        // Clean up screenshots older than 24h on each cold start
        ScreenshotSharer.pruneOldScreenshots(this)
    }
}
