package com.checkmate.ui.planner

import androidx.lifecycle.ViewModel
import com.checkmate.core.DailyCheckIn
import com.checkmate.core.ExamSyllabus
import com.checkmate.service.QbankDailyTaskManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class DailyCheckInViewModel : ViewModel() {

    private val _checkIn = MutableStateFlow(
        DailyCheckIn.loadToday() ?: DailyCheckIn()
    )
    val checkIn: StateFlow<DailyCheckIn> = _checkIn.asStateFlow()

    private val _submitted = MutableStateFlow(false)
    val submitted: StateFlow<Boolean> = _submitted.asStateFlow()

    fun setTopicForSubject(subject: String, topic: String) {
        // Tapping the selected chapter again clears it, so a student can opt a subject out of the Q-bank flood.
        _checkIn.update {
            val next = if (it.todayTopics[subject] == topic) it.todayTopics - subject else it.todayTopics + (subject to topic)
            it.copy(todayTopics = next)
        }
    }

    fun setYesterdayRating(subject: String, rating: Int) {
        _checkIn.update { it.copy(yesterdayRatings = it.yesterdayRatings + (subject to rating)) }
    }

    fun setStress(level: Int) = _checkIn.update { it.copy(stressLevel = level) }
    fun setSleep(hours: Float) = _checkIn.update { it.copy(sleepHours = hours) }

    fun submit() {
        val done = _checkIn.value.copy(completedAt = System.currentTimeMillis())
        DailyCheckIn.saveToday(done)
        // Flood each picked chapter into today's Q-bank (own scope: survives leaving this screen).
        QbankDailyTaskManager.applyCheckInTopicsAsync()
        _submitted.value = true
    }

    fun getChaptersForSubject(exam: String, subject: String): List<String> =
        ExamSyllabus.getChaptersForSubject(exam, subject)
}
