package com.checkmate.ui.testresults

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.checkmate.learning.testmate.TestResultNormalizer
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
    val importError: String? = null
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
     * (or the "already imported" idempotency result) in [TestResultsState].
     * File I/O + normalization both happen off the main thread.
     */
    fun importReport(context: Context, uri: Uri) {
        viewModelScope.launch {
            _state.update { it.copy(importing = true, importError = null, importResult = null) }
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
                _state.update { it.copy(importing = false, importResult = result, importError = null) }
            } catch (e: Exception) {
                Log.e(TAG, "Report import failed: ${e.message}", e)
                _state.update {
                    it.copy(importing = false, importError = "Import failed: ${e.message ?: "unknown error"}")
                }
            }
        }
    }

    fun dismissImportResult() {
        _state.update { it.copy(importResult = null, importError = null) }
    }
}
