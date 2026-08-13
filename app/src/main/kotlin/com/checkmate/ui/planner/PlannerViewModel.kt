package com.checkmate.ui.planner

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.checkmate.core.CheckmatePrefs
import com.checkmate.core.tts.CheckmateTTS
import com.checkmate.planner.AdaptivePlanner
import com.checkmate.planner.PlanStore
import com.checkmate.planner.PlannerState
import com.checkmate.planner.model.SubjectConfig
import com.checkmate.planner.model.StudyTask
import com.checkmate.service.ProfileSyncManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PlannerUiState(
    val examType:       String              = "NEET",
    val examDate:       String              = "",
    val studyStartTime: String              = "06:00",
    val studyEndTime:   String              = "22:00",
    val subjects:       List<SubjectConfig> = listOf(
        SubjectConfig("Biology",   3),
        SubjectConfig("Chemistry", 2),
        SubjectConfig("Physics",   2)
    ),
    val guardianNumber: String              = "",
    val ttsEnabled:     Boolean             = true,
    val isGenerating:   Boolean             = false,
    val generatedTasks: List<StudyTask>     = emptyList()
)

class PlannerViewModel : ViewModel() {

    private val _state = MutableStateFlow(PlannerUiState())
    val state: StateFlow<PlannerUiState> = _state.asStateFlow()

    init { loadSaved() }

    private fun loadSaved() {
        readIntoState()

        // Fresh install / new device with an existing sync_code and no local
        // setup yet (exam date blank): restore the setup profile (exam
        // type/date, study window, guardian number, tts, subjects) in the
        // background instead of making the user retype it, then re-read prefs
        // into state once the restore lands. No-op if local setup already
        // exists or no sync_code is configured — see
        // ProfileSyncManager.pullProfileIfLocalEmpty().
        if (ProfileSyncManager.isEnabled() && _state.value.examDate.isBlank()) {
            Thread {
                if (ProfileSyncManager.pullProfileIfLocalEmpty()) {
                    readIntoState()
                }
            }.start()
        }
    }

    private fun readIntoState() {
        _state.update { s -> s.copy(
            examType       = CheckmatePrefs.getString("exam_type",       "NEET")  ?: "NEET",
            examDate       = CheckmatePrefs.getString("exam_date",       "")      ?: "",
            studyStartTime = CheckmatePrefs.getString("study_start",     "06:00") ?: "06:00",
            studyEndTime   = CheckmatePrefs.getString("study_end",       "22:00") ?: "22:00",
            guardianNumber = CheckmatePrefs.getString("guardian_number", "")      ?: "",
            ttsEnabled     = CheckmatePrefs.getBoolean("tts_enabled",    true)
        )}
        val subjectsRaw = CheckmatePrefs.getString("subjects_config", null)
        if (!subjectsRaw.isNullOrBlank()) {
            val subjects = subjectsRaw.split(";").mapNotNull { entry ->
                val parts = entry.split(":")
                if (parts.size == 2) SubjectConfig(parts[0], parts[1].toIntOrNull() ?: 1) else null
            }
            if (subjects.isNotEmpty()) _state.update { it.copy(subjects = subjects) }
        }
    }

    fun setExam(exam: String) {
        _state.update { it.copy(examType = exam) }
        CheckmatePrefs.putString("exam_type", exam)
        backupProfile()
    }

    fun setExamDate(date: String) {
        _state.update { it.copy(examDate = date) }
        CheckmatePrefs.putString("exam_date", date)
        backupProfile()
    }

    fun setStudyStart(time: String) {
        _state.update { it.copy(studyStartTime = time) }
        CheckmatePrefs.putString("study_start", time)
        backupProfile()
    }

    fun setStudyEnd(time: String) {
        _state.update { it.copy(studyEndTime = time) }
        CheckmatePrefs.putString("study_end", time)
        backupProfile()
    }

    fun setGuardianNumber(number: String) {
        _state.update { it.copy(guardianNumber = number) }
        CheckmatePrefs.putString("guardian_number", number)
        backupProfile()
    }

    fun setTtsEnabled(enabled: Boolean) {
        _state.update { it.copy(ttsEnabled = enabled) }
        CheckmatePrefs.putBoolean("tts_enabled", enabled)
        backupProfile()
    }

    /** Fire-and-forget backup of the setup profile via ProfileSyncManager — no-op if no sync_code. */
    private fun backupProfile() {
        if (ProfileSyncManager.isEnabled()) {
            Thread { ProfileSyncManager.pushProfile() }.start()
        }
    }

    fun addSubject() {
        val updated = _state.value.subjects + SubjectConfig("", 1)
        _state.update { it.copy(subjects = updated) }
        saveSubjects(updated)
    }

    fun removeSubject(index: Int) {
        val updated = _state.value.subjects.toMutableList().also { it.removeAt(index) }
        _state.update { it.copy(subjects = updated) }
        saveSubjects(updated)
    }

    fun updateSubjectName(index: Int, name: String) {
        val updated = _state.value.subjects.toMutableList()
        updated[index] = updated[index].copy(name = name)
        _state.update { it.copy(subjects = updated) }
        saveSubjects(updated)
    }

    fun updateSubjectWeight(index: Int, weight: Int) {
        val updated = _state.value.subjects.toMutableList()
        updated[index] = updated[index].copy(weightage = weight)
        _state.update { it.copy(subjects = updated) }
        saveSubjects(updated)
    }

    private fun saveSubjects(subjects: List<SubjectConfig>) {
        val raw = subjects.joinToString(";") { "${it.name}:${it.weightage}" }
        CheckmatePrefs.putString("subjects_config", raw)
        backupProfile()
    }

    fun generatePlan(context: Context) {
        val s = _state.value
        if (s.isGenerating) return
        _state.update { it.copy(isGenerating = true, generatedTasks = emptyList()) }
        viewModelScope.launch {
            val config = PlannerState(
                examType       = s.examType,
                examDate       = s.examDate,
                subjects       = s.subjects.filter { it.name.isNotBlank() },
                studyStartTime = s.studyStartTime,
                studyEndTime   = s.studyEndTime
            )
            val tasks = try {
                AdaptivePlanner.generateDailyPlan(context, config)
            } catch (e: Exception) {
                emptyList()
            }
            _state.update { it.copy(isGenerating = false, generatedTasks = tasks) }

            // FIX 1.5: ttsEnabled existed and was wired to the UI toggle but generatePlan()
            // never actually called CheckmateTTS.speak() — plan generation was always silent.
            if (s.ttsEnabled && tasks.isNotEmpty()) {
                val firstTask = tasks.first()
                val summary = "Plan ready. ${tasks.size} task${if (tasks.size != 1) "s" else ""}. " +
                        "Starting with ${firstTask.subject}: ${firstTask.topic}."
                CheckmateTTS.speak(context, summary)
            }
        }
    }

    fun savePlan(context: Context) {
        PlanStore.saveTodayTasks(_state.value.generatedTasks)
    }
}
