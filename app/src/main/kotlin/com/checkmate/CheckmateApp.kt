package com.checkmate

import android.app.Application
import com.checkmate.core.CheckmatePrefs
import com.checkmate.core.CheckmateState

class CheckmateApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CheckmatePrefs.init(this)
        CheckmateState.init(this)
    }
}
