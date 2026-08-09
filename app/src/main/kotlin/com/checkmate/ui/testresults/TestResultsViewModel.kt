package com.checkmate.ui.testresults

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.checkmate.testmate.TestmateApi
import com.checkmate.testmate.TestmateResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TestResultsState(
    val sessionId: String = "",
    val loading: Boolean = false,
    val result: TestmateResult? = null,
    val error: String? = null
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
}
