package com.checkmate.ui.planner

import androidx.lifecycle.ViewModel
import com.checkmate.core.DailyCheckIn
import com.checkmate.core.ExamSyllabus
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
        _checkIn.update { it.copy(todayTopics = it.todayTopics + (subject to topic)) }
    }

    fun setYesterdayRating(subject: String, rating: Int) {
        _checkIn.update { it.copy(yesterdayRatings = it.yesterdayRatings + (subject to rating)) }
    }

    fun setStress(level: Int) = _checkIn.update { it.copy(stressLevel = level) }
    fun setSleep(hours: Float) = _checkIn.update { it.copy(sleepHours = hours) }

    fun submit() {
        val done = _checkIn.value.copy(completedAt = System.currentTimeMillis())
        DailyCheckIn.saveToday(done)
        _submitted.value = true
    }

    fun getChaptersForSubject(exam: String, subject: String): List<String> =
        ExamSyllabus.getChaptersForSubject(exam, subject)
}
