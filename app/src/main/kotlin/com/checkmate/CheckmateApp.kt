package com.checkmate

import android.app.Application
import com.checkmate.core.CheckmatePrefs
import com.checkmate.core.CheckmateState
import com.checkmate.core.tts.CheckmateTTS

class CheckmateApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CheckmatePrefs.init(this)
        CheckmateState.init(this)
        // FIX: CheckmateTTS.init() was never called — ttsReady was always false,
        // every speak() call was a silent no-op. Confirmed by zero CheckmateTTS
        // entries in logcat.
        CheckmateTTS.init(this)
    }
}
