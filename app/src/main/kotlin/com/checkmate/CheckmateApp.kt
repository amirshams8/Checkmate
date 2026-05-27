package com.checkmate

import android.app.Application
import android.content.Context
import com.checkmate.core.CheckmatePrefs
import com.checkmate.core.CheckmateState
import com.checkmate.core.tts.CheckmateTTS
import com.checkmate.service.GuardianNotifier
import com.checkmate.service.ScreenCaptureManager
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
        ScreenshotSharer.pruneOldScreenshots(this)

        // Wire real screenshot capture via MediaProjection into DistractionGuard
        DistractionGuard.listener = object : DistractionListener {
            override fun onAlertThresholdReached(context: Context, kind: String, target: String) {
                // capture() is non-blocking internally (uses VirtualDisplay + ImageReader)
                // but we call it on the accessibility service thread via a bg thread to avoid ANR
                Thread {
                    val uri = ScreenCaptureManager.capture(context)
                    GuardianNotifier.notifyDistractionAlert(context, kind, target, uri)
                }.start()
            }
        }
    }
}
