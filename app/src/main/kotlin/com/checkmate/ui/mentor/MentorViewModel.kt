package com.checkmate.ui.mentor

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.checkmate.core.ConsultationProfile
import com.checkmate.core.ConsultationProfile.Companion.toPromptContext
import com.checkmate.core.MentorKnowledge
import com.checkmate.core.llm.LlmGateway
import com.checkmate.core.tts.CheckmateTTS
import com.checkmate.psyche.BehaviorLedger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MentorMessage(
    val role:    String,  // "user" | "assistant"
    val content: String,
    val ts:      Long = System.currentTimeMillis()
)

data class MentorUiState(
    val messages:   List<MentorMessage> = listOf(
        MentorMessage("assistant", "Ready. What do you need help with?")
    ),
    val inputText:  String              = "",
    val isLoading:  Boolean             = false,
    val ttsEnabled: Boolean             = com.checkmate.core.CheckmatePrefs.getBoolean("tts_enabled", true)
)

class MentorViewModel : ViewModel() {

    private val _state = MutableStateFlow(MentorUiState())
    val state: StateFlow<MentorUiState> = _state.asStateFlow()

    fun setInput(text: String) = _state.update { it.copy(inputText = text) }

    fun send(context: Context) {
        val text = _state.value.inputText.trim()
        if (text.isBlank() || _state.value.isLoading) return

        val userMsg = MentorMessage("user", text)
        _state.update { it.copy(
            messages  = it.messages + userMsg,
            inputText = "",
            isLoading = true
        )}

        viewModelScope.launch {
            val response = try {
                val systemPrompt = buildSystemPrompt(text)
                val history      = buildHistory()
                LlmGateway.complete(history, systemPrompt)
            } catch (e: Exception) {
                "I couldn't process that right now. Try again."
            }

            val assistantMsg = MentorMessage("assistant", response.ifBlank { "No response. Check your API key in Settings." })
            _state.update { it.copy(messages = it.messages + assistantMsg, isLoading = false) }

            if (_state.value.ttsEnabled && response.isNotBlank()) {
                // Speak first sentence only to keep TTS short
                val firstSentence = response.split(". ", ".\n").firstOrNull()?.take(200) ?: response.take(200)
                CheckmateTTS.speak(context, firstSentence)
            }
        }
    }

    private fun buildHistory(): String {
        val msgs = _state.value.messages.takeLast(10)
        return msgs.joinToString("\n") { msg ->
            if (msg.role == "user") "Student: ${msg.content}"
            else "Mentor: ${msg.content}"
        }
    }

    private fun buildSystemPrompt(currentQuery: String): String {
        val profile         = ConsultationProfile.load()
        val ledger          = BehaviorLedger.getSummaryForPlanner()
        val knowledgeBlocks = MentorKnowledge.getContextForQuery(currentQuery)

        return buildString {
            appendLine("""
You are a strict but smart study mentor for a ${profile.examTarget} aspirant.
You have full context about this student. Be specific, direct, and curriculum-aware.
Keep responses under 5 lines unless a detailed breakdown is needed.
Never give generic motivation. React to actual data.
Refer to specific topics, chapters, marks gaps, and deadlines.
            """.trimIndent())
            appendLine()
            appendLine("STUDENT PROFILE:")
            appendLine(profile.toPromptContext())
            appendLine()
            appendLine("BEHAVIOR DATA: $ledger")
            appendLine()
            if (knowledgeBlocks.isNotBlank()) {
                appendLine("RELEVANT KNOWLEDGE:")
                appendLine(knowledgeBlocks)
                appendLine()
            }
            appendLine("Respond as Mentor.")
        }.trim()
    }
}
