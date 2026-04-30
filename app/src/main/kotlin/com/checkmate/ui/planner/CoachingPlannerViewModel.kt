package com.checkmate.ui.planner

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.checkmate.core.CoachingPlannerEntry
import com.checkmate.core.llm.LlmGateway
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray

data class CoachingPlannerUiState(
    val rawInput:   String                    = "",
    val entries:    List<CoachingPlannerEntry> = CoachingPlannerEntry.loadAll(),
    val isExtracting: Boolean                 = false,
    val error:      String                    = ""
)

class CoachingPlannerViewModel : ViewModel() {

    private val _state = MutableStateFlow(CoachingPlannerUiState())
    val state: StateFlow<CoachingPlannerUiState> = _state.asStateFlow()

    fun setRawInput(text: String) = _state.update { it.copy(rawInput = text) }

    fun extractSchedule(context: Context) {
        val raw = _state.value.rawInput.trim()
        if (raw.isBlank()) return
        _state.update { it.copy(isExtracting = true, error = "") }
        viewModelScope.launch {
            try {
                val prompt = """
Extract the coaching schedule from the text below.
Respond ONLY with a JSON array, no markdown.
Format: [{"subject":"Physics","chapter":"Electrostatics","type":"test","date":"15/05/2025"},...]
type must be "test" or "lecture".
date must be dd/MM/yyyy.

TEXT:
$raw
""".trimIndent()
                val result = LlmGateway.complete(prompt)
                if (result.isBlank()) throw Exception("Empty response")
                val clean = result.trim().removePrefix("```json").removeSuffix("```").trim()
                val arr   = JSONArray(clean)
                val entries = mutableListOf<CoachingPlannerEntry>()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    entries.add(CoachingPlannerEntry(
                        subject  = obj.getString("subject"),
                        chapter  = obj.getString("chapter"),
                        type     = obj.getString("type"),
                        date     = obj.getString("date")
                    ))
                }
                CoachingPlannerEntry.saveAll(CoachingPlannerEntry.loadAll() + entries)
                _state.update { it.copy(
                    isExtracting = false,
                    entries      = CoachingPlannerEntry.loadAll(),
                    rawInput     = ""
                )}
            } catch (e: Exception) {
                _state.update { it.copy(isExtracting = false, error = "Extraction failed: ${e.message}") }
            }
        }
    }

    fun deleteEntry(entry: CoachingPlannerEntry) {
        val updated = CoachingPlannerEntry.loadAll().filter { it.id != entry.id }
        CoachingPlannerEntry.saveAll(updated)
        _state.update { it.copy(entries = updated) }
    }
}
