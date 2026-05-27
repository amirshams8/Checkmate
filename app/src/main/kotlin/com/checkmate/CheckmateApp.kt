package com.checkmate

import android.app.Application
import android.content.Context
import android.net.Uri
import com.checkmate.core.CheckmatePrefs
import com.checkmate.core.CheckmateState
import com.checkmate.core.tts.CheckmateTTS
import com.checkmate.service.GuardianNotifier
import com.checkmate.service.ScreenshotCapture
import com.checkmate.service.ScreenshotSharer
import com.checkmate.workmode.DistractionGuard
import com.checkmate.workmode.DistractionListener

class CheckmateApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CheckmatePrefs.init(this)
        CheckmateState.init(this)
        CheckmateTTS.init(this)
        GuardianNotifier.scheduleEndOfDaySummary(this)
        // Clean up screenshots older than 24h on each cold start
        ScreenshotSharer.pruneOldScreenshots(this)

        // Wire the distraction alert callback — keeps :workmode free of :app imports
        DistractionGuard.listener = object : DistractionListener {
            override fun onAlertThresholdReached(context: Context, kind: String, target: String) {
                val uri: Uri? = ScreenshotCapture.captureOverlay(context)
                GuardianNotifier.notifyDistractionAlert(context, kind, target, uri)
            }
        }
    }
}
