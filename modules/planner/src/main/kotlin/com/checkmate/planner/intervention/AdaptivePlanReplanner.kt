package com.checkmate.planner.intervention

import android.content.Context
import com.checkmate.core.CheckmatePrefs
import com.checkmate.planner.AdaptivePlanner
import com.checkmate.planner.PlanStore
import com.checkmate.planner.PlannerState
import com.checkmate.planner.model.SubjectConfig

/**
 * P0a continuation (REPLAN_DAY) — Upgrade Blueprint Phase 2.4/2.5. Production
 * [PlanReplanner], wired into [LearningInterventionOrchestrator.from]'s factory alongside
 * [LedgerDifficultyMutator].
 *
 * [com.checkmate.planner.AdaptivePlanner.generateDailyPlan] takes a
 * [com.checkmate.planner.PlannerState] built by a caller — every existing caller
 * (`PlannerViewModel.generatePlan`, see that class's own `readIntoState`/`generatePlan`)
 * is a ViewModel that already holds that configuration as observed UI state. This class
 * has no ViewModel or UI to read from — it runs from [ActionExecutor], which itself runs
 * from [LearningInterventionOrchestrator], which the daily
 * [com.checkmate.service.GapTaskManager] cadence and
 * [com.checkmate.ui.testresults.TestResultsViewModel] both call directly, with no
 * PlannerViewModel in that call chain at all. Since exam type/date, subjects, and the
 * study window are already persisted directly to [CheckmatePrefs] the moment the student
 * sets them (see `PlannerViewModel.setExam`/`setExamDate`/`setStudyStart`/`setStudyEnd`/
 * `saveSubjects`), [readPlannerStateFromPrefs] reconstructs the exact same
 * [PlannerState] a fresh `PlannerViewModel.readIntoState()` would, straight from
 * [CheckmatePrefs] — no new dependency on `:app`'s ViewModel layer needed.
 *
 * Deliberately does NOT reuse [PlannerState]'s consultation-profile / daily-check-in
 * fields ([PlannerState.currentClass], `.targetScore`, `.todayTopics`, etc.) —
 * [AdaptivePlanner.generateDailyPlan] itself never reads them off [PlannerState] either
 * (it loads [com.checkmate.core.ConsultationProfile]/[com.checkmate.core.DailyCheckIn]
 * directly), so leaving them at [PlannerState]'s own defaults here matches exactly what
 * `PlannerViewModel.generatePlan`'s own `PlannerState(...)` construction already does —
 * see that function's call site in the repo dump.
 */
class AdaptivePlanReplanner(private val context: Context) : PlanReplanner {

    override suspend fun replanToday() {
        val config = readPlannerStateFromPrefs()
        val tasks = AdaptivePlanner.generateDailyPlan(context, config)
        PlanStore.saveTodayTasks(tasks)
    }

    /**
     * Mirrors `PlannerViewModel.readIntoState()` field-for-field, including its exact
     * CheckmatePrefs keys/defaults ("study_start"/"study_end", defaulting to 06:00/22:00 —
     * NOT [PlannerState]'s own 09:00/21:00 constructor defaults) and its
     * "name:weightage" semicolon-joined encoding for "subjects_config" — so a REPLAN_DAY
     * regenerates from the exact same configuration the student would see if they opened
     * the Planner screen right now, never a silently different set of defaults.
     */
    private fun readPlannerStateFromPrefs(): PlannerState {
        val examType = CheckmatePrefs.getString("exam_type", "NEET") ?: "NEET"
        val examDate = CheckmatePrefs.getString("exam_date", "") ?: ""
        val studyStart = CheckmatePrefs.getString("study_start", "06:00") ?: "06:00"
        val studyEnd = CheckmatePrefs.getString("study_end", "22:00") ?: "22:00"

        val subjectsRaw = CheckmatePrefs.getString("subjects_config", null)
        val subjects = subjectsRaw
            ?.split(";")
            ?.mapNotNull { entry ->
                val parts = entry.split(":")
                if (parts.size == 2) SubjectConfig(parts[0], parts[1].toIntOrNull() ?: 1) else null
            }
            ?: emptyList()

        return PlannerState(
            examType = examType,
            examDate = examDate,
            subjects = subjects,
            studyStartTime = studyStart,
            studyEndTime = studyEnd
        )
    }
}
