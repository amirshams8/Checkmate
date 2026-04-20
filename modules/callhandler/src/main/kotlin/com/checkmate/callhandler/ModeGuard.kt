package com.checkmate.callhandler

import com.checkmate.core.CheckmateState
import com.checkmate.core.StudyMode

object ModeGuard {
    fun isStudyMode(): Boolean = CheckmateState.currentMode == StudyMode.STUDY
    fun isBreakMode(): Boolean = CheckmateState.currentMode == StudyMode.BREAK
    fun isSleepMode(): Boolean = CheckmateState.currentMode == StudyMode.SLEEP
}
