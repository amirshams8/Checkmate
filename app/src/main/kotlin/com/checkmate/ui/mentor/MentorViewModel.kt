package com.checkmate.ui.mentor

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.checkmate.core.CheckmatePrefs
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
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

private val historyJson = Json { ignoreUnknownKeys = true }
private const val PREFS_KEY_HISTORY = "mentor_chat_history"
private const val MAX_PERSISTED_MESSAGES = 40

@Serializable
data class MentorMessage(
    val role:    String,
    val content: String,
    val ts:      Long = System.currentTimeMillis()
)

data class MentorUiState(
    val messages:   List<MentorMessage> = emptyList(),
    val inputText:  String              = "",
    val isLoading:  Boolean             = false,
    val ttsEnabled: Boolean             = CheckmatePrefs.getBoolean("tts_enabled", true)
)

class MentorViewModel : ViewModel() {

    private val _state = MutableStateFlow(MentorUiState())
    val state: StateFlow<MentorUiState> = _state.asStateFlow()

    init { loadHistory() }

    private fun loadHistory() {
        val saved = CheckmatePrefs.getString(PREFS_KEY_HISTORY, null)
        val messages = if (saved != null) {
            try { historyJson.decodeFromString<List<MentorMessage>>(saved) }
            catch (_: Exception) { emptyList() }
        } else emptyList()

        val initial = if (messages.isEmpty())
            listOf(MentorMessage("assistant", "Ready. What do you need help with?"))
        else messages

        _state.update { it.copy(messages = initial) }
    }

    private fun persistHistory(messages: List<MentorMessage>) {
        val trimmed = messages.takeLast(MAX_PERSISTED_MESSAGES)
        CheckmatePrefs.putString(PREFS_KEY_HISTORY, historyJson.encodeToString(trimmed))
    }

    fun setInput(text: String) = _state.update { it.copy(inputText = text) }

    fun send(context: Context) {
        val text = _state.value.inputText.trim()
        if (text.isBlank() || _state.value.isLoading) return

        val userMsg = MentorMessage("user", text)
        val updatedMessages = _state.value.messages + userMsg
        _state.update { it.copy(messages = updatedMessages, inputText = "", isLoading = true) }
        persistHistory(updatedMessages)

        viewModelScope.launch {
            val response = try {
                val systemPrompt = buildSystemPrompt(text)
                val history      = buildHistoryForLlm()
                LlmGateway.complete(history, systemPrompt)
            } catch (e: Exception) {
                "I couldn't process that right now. Try again."
            }

            val assistantMsg = MentorMessage(
                "assistant",
                response.ifBlank { "No response. Check your API key in Settings." }
            )
            val finalMessages = _state.value.messages + assistantMsg
            _state.update { it.copy(messages = finalMessages, isLoading = false) }
            persistHistory(finalMessages)

            if (_state.value.ttsEnabled && response.isNotBlank()) {
                val firstSentence = response.split(". ", ".\n").firstOrNull()?.take(200) ?: response.take(200)
                CheckmateTTS.speak(context, firstSentence)
            }
        }
    }

    fun clearHistory() {
        val initial = listOf(MentorMessage("assistant", "Ready. What do you need help with?"))
        _state.update { it.copy(messages = initial) }
        // FIX: putString(key, null) crashes on non-null String param — use remove() instead
        CheckmatePrefs.remove(PREFS_KEY_HISTORY)
    }

    private fun buildHistoryForLlm(): String {
        val msgs = _state.value.messages.takeLast(20)
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
You also have access to the full conversation history above — refer to it naturally when relevant.
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
