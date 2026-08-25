package com.checkmate.ui.testresults

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.checkmate.core.ConsultationProfile
import com.checkmate.learning.analytics.PerformanceAnalyzer
import com.checkmate.learning.analytics.ScoreGainEstimator
import com.checkmate.learning.analytics.ScorePredictor
import com.checkmate.learning.engine.LearningDecisionEngine
import com.checkmate.learning.student.StudentModelBuilder
import com.checkmate.learning.testmate.TestResultNormalizer
import com.checkmate.planner.intervention.LearningInterventionOrchestrator
import com.checkmate.testmate.TestmateApi
import com.checkmate.testmate.TestmateResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "TestResultsViewModel"

data class TestResultsState(
    val sessionId: String = "",
    val loading: Boolean = false,
    val result: TestmateResult? = null,
    val error: String? = null,
    // ── Upgrade Blueprint wiring gap fix ──
    // Phase 1 (MasteryEngine/ErrorEngine/RetentionEngine/KnowledgeGraph/
    // TestResultNormalizer) was fully built but nothing ever called
    // TestResultNormalizer — TestmateApi's /results endpoint only returns
    // chapter/topic aggregates, never per-question detail, so there was no
    // data shape TestResultNormalizer could consume. Rather than add a new
    // testmate2.com endpoint, this reuses the report.md TestResultNormalizer
    // already parses (TestReportParser) via a manual file picker (see
    // TestResultsScreen's "Import report.md" section) — the student downloads
    // the report from TestmateWebScreen's WebView (already wired to
    // DownloadManager) and picks it here. No backend change, no
    // DownloadManager-watching/polling, no push/pull sync layer to maintain
    // across app updates — just a Storage Access Framework file read.
    val importing: Boolean = false,
    val importResult: TestResultNormalizer.NormalizeResult? = null,
    val importError: String? = null,
    // ── Test-report wiring pass ──
    // Closes normalizeAndPersist() -> StudentModelBuilder.build() ->
    // PerformanceAnalyzer.analyze() -> PerformanceReport, the second half of the
    // loop the import above only started. Deliberately separate loading/error
    // state from importing/importError above: a successful import that commits
    // to Room is real and final the moment importResult is set, regardless of
    // whether analysis afterward succeeds — see importReport's own doc on why
    // analysis failure never rolls back or hides importResult.
    val analyzing: Boolean = false,
    val performanceReport: PerformanceAnalyzer.PerformanceReport? = null,
    val analysisError: String? = null,
    // ── ScoreGainEstimator wiring pass ──
    // Blueprint §2.2. Built from the SAME PerformanceReport + StudentModel this
    // buildPerformanceReport call already produced — never recomputed
    // separately, so this can never drift out of sync with performanceReport
    // (same "one source of truth per Room read" discipline StudentModel's own
    // doc requires). Ranked highest-expectedGain first; empty until analysis
    // has run at least once. Kept as its own field (not folded into
    // performanceReport) because it's a downstream DECISION-shaped view over
    // PerformanceAnalyzer's EVIDENCE-shaped output — see ScoreGainEstimator's
    // own class doc on that boundary.
    val scoreGainEstimates: List<ScoreGainEstimator.ScoreGainEstimate> = emptyList(),
    // ── ScorePredictor wiring pass ──
    // Blueprint §2.3. Built from the SAME PerformanceReport + StudentModel as
    // scoreGainEstimates above — never a separate StudentModelBuilder.build/
    // PerformanceAnalyzer.analyze call, same "one Room read, three derived views"
    // discipline. targetScore comes from ConsultationProfile (the Student Profile
    // screen's own "Target Score" field), not invented here. Null until analysis
    // has run at least once.
    val expectedScore: ScorePredictor.ExpectedScore? = null,
    // ── LearningDecisionEngine wiring pass (Blueprint §2.4) ──
    // The frontier piece: everything above this line is EVIDENCE (PerformanceReport)
    // or a DECISION-shaped ranking over one dimension at a time (scoreGainEstimates
    // ranks by marks, expectedScore explains a gap) — this is the first field that's
    // an actual ranked, typed ACTION ("what should this student do next"), built
    // from the SAME performanceReport/scoreGainEstimates/expectedScore this call
    // already produced (see LearningDecisionEngine.decideFromReport's own "one Room
    // read, N derived views" doc). Null until analysis has run at least once.
    val decisionReport: LearningDecisionEngine.DecisionReport? = null,
    // ── LearningInterventionOrchestrator wiring pass (Blueprint §2.5, P0a's last seam) ──
    // The orchestrator's own class doc named this exact gap: it consumes an
    // already-built DecisionReport but "does not itself decide when to run." A
    // fresh test import IS that trigger — see buildPerformanceReport's doc on why
    // this only fires when `!alreadyImported`. Built from the SAME decisionReport
    // this call already produced — sixth derived view over one Room read, same
    // discipline as every field above it. Null until an orchestration run has
    // actually happened (i.e. never set on the `alreadyImported` replay path).
    val orchestrationResult: LearningInterventionOrchestrator.OrchestrationResult? = null,
    val orchestrationError: String? = null
)

class TestResultsViewModel : ViewModel() {
    private val _state = MutableStateFlow(TestResultsState())
    val state: StateFlow<TestResultsState> = _state.asStateFlow()

    fun onSessionIdChange(value: String) {
        _state.update { it.copy(sessionId = value, error = null) }
    }

    fun fetch() {
        val id = _state.value.sessionId
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            when (val outcome = TestmateApi.fetchResult(id)) {
                is com.checkmate.testmate.TestmateResultOutcome.Success ->
                    _state.update { it.copy(loading = false, result = outcome.result, error = null) }
                is com.checkmate.testmate.TestmateResultOutcome.Error ->
                    _state.update { it.copy(loading = false, error = outcome.message) }
            }
        }
    }

    /**
     * Reads the picked report.md (or .txt — Testmate's export mime type varies by
     * provider) via SAF, hands the raw text straight to
     * [TestResultNormalizer.normalizeAndPersist], and surfaces the write counts
     * (or the "already imported" idempotency result) in [TestResultsState]. File
     * I/O + normalization both happen off the main thread.
     *
     * Test-report wiring pass: once (and only once) normalizeAndPersist has
     * actually committed — success or already-imported, both mean Room state is
     * current — this chains into [buildPerformanceReport], closing
     * normalizeAndPersist -> StudentModelBuilder.build -> PerformanceAnalyzer.analyze
     * -> PerformanceReport -> ScoreGainEstimator.rankFromReport. The stages update
     * state independently: `importResult` is set and `importing` flips false as
     * soon as persistence succeeds, full stop; analysis (and the ranking built on
     * top of it) runs after that as a separate concern, and if it fails, only
     * `analysisError` is set — the already-committed import is never rolled back
     * or hidden because a derived-view step afterward broke.
     */
    fun importReport(context: Context, uri: Uri) {
        viewModelScope.launch {
            _state.update {
                it.copy(
                    importing = true, importError = null, importResult = null,
                    performanceReport = null, analysisError = null, scoreGainEstimates = emptyList(),
                    expectedScore = null, decisionReport = null,
                    orchestrationResult = null, orchestrationError = null
                )
            }
            try {
                val text = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                } ?: throw IllegalStateException("Couldn't open the selected file.")

                if (text.isBlank()) {
                    _state.update {
                        it.copy(importing = false, importError = "Selected file is empty.")
                    }
                    return@launch
                }

                val result = withContext(Dispatchers.IO) {
                    TestResultNormalizer.normalizeAndPersist(context, text)
                }
                // Persistence has committed — this is final regardless of what
                // buildPerformanceReport below does with it.
                _state.update { it.copy(importing = false, importResult = result, importError = null) }
                buildPerformanceReport(context, result.examType, result.alreadyImported)
            } catch (e: Exception) {
                Log.e(TAG, "Report import failed: ${e.message}", e)
                _state.update {
                    it.copy(importing = false, importError = "Import failed: ${e.message ?: "unknown error"}")
                }
            }
        }
    }

    /**
     * Runs after a committed import (fresh or already-imported — both mean Room
     * reflects this test). Reads current Room state fresh via
     * [StudentModelBuilder.build] rather than anything cached from the import
     * step, per [com.checkmate.learning.model.StudentModel]'s own "never hold a
     * built StudentModel across a Room write" rule. `examType` comes straight from
     * [TestResultNormalizer.NormalizeResult] so this never re-parses the report or
     * guesses an exam identifier itself.
     *
     * ScoreGainEstimator wiring pass: [ScoreGainEstimator.rankFromReport] runs
     * against the SAME `studentModel`/`report` this function just built — not a
     * second `StudentModelBuilder.build`/`PerformanceAnalyzer.analyze` call — so
     * `scoreGainEstimates` can never reflect a different Room snapshot than
     * `performanceReport` does. Both are set together in one state update.
     *
     * ScorePredictor wiring pass (Blueprint §2.3): [ScorePredictor.predictFromReport]
     * runs against that same `studentModel`/`report` too — third derived view over
     * one Room read, same discipline as the ScoreGainEstimator call right above it.
     * `targetScore` comes from [ConsultationProfile.load] (the Student Profile
     * screen's own field, defaults to 650 if never set) — read fresh here, not
     * cached, so a target the student just edited is reflected on the very next
     * analysis run.
     *
     * LearningDecisionEngine wiring pass (Blueprint §2.4): [LearningDecisionEngine.decideFromReport]
     * runs against the SAME `report`/`studentModel`/`estimates`/`expectedScore` this
     * call already built — fourth derived view over one Room read, same "never
     * recompute what's already in hand" discipline as the two calls above it.
     *
     * LearningInterventionOrchestrator wiring pass (Blueprint §2.5, P0a's last seam):
     * once `decisionReport` above is built, [executeTopIntervention] runs it through
     * the orchestrator — the "post-test event" trigger the orchestrator's own class
     * doc said was still missing. `alreadyImported` is threaded all the way down
     * from [importReport] because this function also runs on the idempotent replay
     * path (re-picking an already-imported report re-triggers analysis so the
     * screen has something to show), and PolicyValidator has no duplicate-task
     * guard of its own — see [executeTopIntervention]'s doc for why that path must
     * not also autonomously create a task.
     *
     * Launched as its own coroutine (not inline in importReport's try block) so an
     * analysis failure can only ever set [TestResultsState.analysisError] — it has
     * no path back to importReport's own try/catch and can't turn a successful
     * import into a failed one.
     */
    private fun buildPerformanceReport(context: Context, examType: String?, alreadyImported: Boolean) {
        if (examType == null) {
            _state.update {
                it.copy(analysisError = "Couldn't determine this report's exam type — performance analysis skipped.")
            }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(analyzing = true, analysisError = null) }
            try {
                val studentModel = withContext(Dispatchers.IO) { StudentModelBuilder.build(context) }
                val report = PerformanceAnalyzer.analyze(studentModel, examType)
                val estimates = ScoreGainEstimator.rankFromReport(report, studentModel)
                val targetScore = ConsultationProfile.load().targetScore
                val expectedScore = ScorePredictor.predictFromReport(report, studentModel, targetScore)
                val decisionReport = LearningDecisionEngine.decideFromReport(
                    report, studentModel, estimates, expectedScore
                )
                _state.update {
                    it.copy(
                        analyzing = false, performanceReport = report,
                        scoreGainEstimates = estimates, expectedScore = expectedScore,
                        decisionReport = decisionReport
                    )
                }
                executeTopIntervention(context, decisionReport, alreadyImported)
            } catch (e: Exception) {
                Log.e(TAG, "Performance analysis failed: ${e.message}", e)
                _state.update {
                    it.copy(
                        analyzing = false,
                        analysisError = "Couldn't build performance report: ${e.message ?: "unknown error"}"
                    )
                }
            }
        }
    }

    /**
     * LearningInterventionOrchestrator wiring pass (Blueprint §2.5) — the trigger
     * [LearningInterventionOrchestrator]'s own class doc said was still missing:
     * "a schedule, a button, a WorkManager job." This is the schedule: every fresh
     * test import is itself the natural "post-test event" trigger, so this fires
     * right after `decisionReport` is built in [buildPerformanceReport], using
     * that SAME DecisionReport.
     *
     * Gated on `!alreadyImported`: [buildPerformanceReport] also runs on the
     * idempotent "already imported" replay path, and [PolicyValidator] has no
     * duplicate-task guard of its own — without this gate, re-opening this screen
     * and re-picking a report already in Room would rank the same top candidate
     * again and autonomously create a fresh duplicate StudyTask every time. A
     * fresh import is the only case that should actually cause a new autonomous
     * action.
     *
     * Isolated in its own try/catch for the same reason analysis is isolated from
     * import: a failure here can only ever set [TestResultsState.orchestrationError]
     * — it has no path back to [buildPerformanceReport]'s try block and can't turn
     * an already-successful decisionReport into a failed one.
     */
    private suspend fun executeTopIntervention(
        context: Context,
        decisionReport: LearningDecisionEngine.DecisionReport,
        alreadyImported: Boolean
    ) {
        if (alreadyImported) return
        try {
            val result = withContext(Dispatchers.IO) {
                LearningInterventionOrchestrator.from(context).executeTopCandidate(decisionReport)
            }
            _state.update { it.copy(orchestrationResult = result, orchestrationError = null) }
        } catch (e: Exception) {
            Log.e(TAG, "Intervention orchestration failed: ${e.message}", e)
            _state.update {
                it.copy(orchestrationError = "Couldn't create a study task from this analysis: ${e.message ?: "unknown error"}")
            }
        }
    }

    fun dismissImportResult() {
        _state.update {
            it.copy(
                importResult = null, importError = null,
                performanceReport = null, analysisError = null, scoreGainEstimates = emptyList(),
                expectedScore = null, decisionReport = null,
                orchestrationResult = null, orchestrationError = null
            )
        }
    }
}
