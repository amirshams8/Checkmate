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
            // Chapter changed or cleared: any topic pick under the old chapter no longer applies.
            it.copy(todayTopics = next, todayTopicSets = it.todayTopicSets - subject)
        }
    }

    fun setTopicPick(subject: String, topic: String) {
        // Optional narrowing under the picked chapter; several topics can be picked. Tapping a picked topic
        // again removes it, and an empty set goes back to chapter-wide.
        _checkIn.update {
            if (it.todayTopics[subject].isNullOrBlank()) return@update it
            val current = it.todayTopicSets[subject].orEmpty()
            val updated = if (topic in current) current - topic else current + topic
            val next = if (updated.isEmpty()) it.todayTopicSets - subject else it.todayTopicSets + (subject to updated)
            it.copy(todayTopicSets = next)
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

    fun getTopicsForChapter(exam: String, subject: String, chapter: String): List<String> =
        ExamSyllabus.getTopicsForChapter(exam, subject, chapter)
}
