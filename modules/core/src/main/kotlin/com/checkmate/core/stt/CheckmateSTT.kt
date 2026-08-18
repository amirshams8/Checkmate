package com.checkmate.core.stt

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * Proactive Execution Engine — Step 10 (Blueprint Part One, §16's "Talk to Checkmate" —
 * flagged by Step 9's InterventionNotifier but left unbuilt until now).
 *
 * Thin wrapper around Android's on-device [SpeechRecognizer]. No cloud STT vendor is wired
 * here — mirrors [com.checkmate.core.tts.CheckmateTTS]'s own "free platform default, no
 * required paid dependency" posture (that file's ElevenLabs path is an opt-in upgrade on
 * top of Android TextToSpeech, not a replacement for it). RECORD_AUDIO is already declared
 * in the manifest and requested at MainActivity startup — this step needed nothing new
 * there.
 *
 * A class, not a singleton object like CheckmateTTS — a SpeechRecognizer instance is
 * single-session state (each `startListening()` either resolves via onResults/onError or is
 * cancelled), and the negotiation screen this exists for needs its own lifecycle-scoped
 * instance rather than a process-wide singleton other unrelated screens could silently
 * interfere with.
 *
 * Must be constructed and driven from the main thread — that's a hard SpeechRecognizer
 * requirement, not a choice made here. [NegotiationViewModel] only ever calls this from
 * Compose's main-thread callbacks, so this class does no threading of its own.
 */
class CheckmateSTT(private val context: Context) {

    private var recognizer: SpeechRecognizer? = null

    private val _state = MutableStateFlow(SttUiState())
    val state: StateFlow<SttUiState> = _state.asStateFlow()

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    fun startListening() {
        if (!isAvailable()) {
            _state.value = _state.value.copy(error = "Speech recognition not available on this device")
            return
        }
        // Defensive: never leave a previous session's recognizer half-torn-down before
        // starting a new one — createSpeechRecognizer() while one is already listening is
        // the most common source of silent ERROR_RECOGNIZER_BUSY reports.
        stopListening()

        val r = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Log.d(TAG, "Ready for speech")
                _state.value = _state.value.copy(isListening = true, error = null)
            }

            override fun onBeginningOfSpeech() = Unit

            override fun onRmsChanged(rmsdB: Float) {
                _state.value = _state.value.copy(rmsDb = rmsdB)
            }

            override fun onBufferReceived(buffer: ByteArray?) = Unit

            override fun onEndOfSpeech() {
                _state.value = _state.value.copy(isListening = false)
            }

            override fun onError(error: Int) {
                val message = describeError(error)
                Log.w(TAG, "STT error $error: $message")
                _state.value = _state.value.copy(isListening = false, error = message)
            }

            override fun onResults(results: Bundle?) {
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                _state.value = _state.value.copy(isListening = false, finalText = text, partialText = "")
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                if (text != null) _state.value = _state.value.copy(partialText = text)
            }

            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }
        _state.value = _state.value.copy(finalText = null, partialText = "", error = null)
        r.startListening(intent)
    }

    fun stopListening() {
        recognizer?.let {
            try { it.stopListening() } catch (e: Exception) { Log.w(TAG, "stopListening failed: ${e.message}") }
            try { it.destroy() } catch (e: Exception) { Log.w(TAG, "destroy failed: ${e.message}") }
        }
        recognizer = null
        _state.value = _state.value.copy(isListening = false)
    }

    /** Clears a consumed final transcript so it isn't re-acted-on if the state flow is
     *  re-observed (e.g. after a config change) before the next `startListening()` call. */
    fun consumeFinalText() {
        _state.value = _state.value.copy(finalText = null)
    }

    private fun describeError(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_NO_MATCH -> "Didn't catch that — try again"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission needed"
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network error"
        SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy — try again"
        SpeechRecognizer.ERROR_CLIENT -> "Speech recognition client error"
        else -> "Speech recognition error ($code)"
    }

    companion object {
        private const val TAG = "CheckmateSTT"
    }
}

data class SttUiState(
    val isListening: Boolean = false,
    val partialText: String = "",
    val finalText: String? = null,
    val rmsDb: Float = 0f,
    val error: String? = null
)
