package com.checkmate.core.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import com.checkmate.core.CheckmatePrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit

object CheckmateTTS {

    private const val TAG = "CheckmateTTS"
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    fun init(context: Context) {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                ttsReady = true
                Log.d(TAG, "Android TTS ready")
            }
        }
    }

    fun speak(context: Context, text: String) {
        val enabled = CheckmatePrefs.getBoolean("tts_enabled", true)
        if (!enabled) return

        val elevenKey = CheckmatePrefs.getString("elevenlabs_key", null)
        if (!elevenKey.isNullOrBlank()) {
            // Fix: GlobalScope.run{launch} is wrong — .run{} is not a coroutine builder
            GlobalScope.launch(Dispatchers.IO) {
                try {
                    speakElevenLabs(context, text, elevenKey)
                } catch (e: Exception) {
                    Log.w(TAG, "ElevenLabs failed, falling back to Android TTS: ${e.message}")
                    speakAndroid(text)
                }
            }
        } else {
            speakAndroid(text)
        }
    }

    private fun speakAndroid(text: String) {
        if (!ttsReady) return
        tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "checkmate_${System.currentTimeMillis()}")
    }

    private suspend fun speakElevenLabs(context: Context, text: String, apiKey: String) {
        val voiceId = "pNInz6obpgDQGcFmaJgB"
        val url     = "https://api.elevenlabs.io/v1/text-to-speech/$voiceId"
        val body    = JSONObject().apply {
            put("text", text)
            put("model_id", "eleven_monolingual_v1")
        }.toString().toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url(url)
            .addHeader("xi-api-key", apiKey)
            .addHeader("Accept", "audio/mpeg")
            .post(body)
            .build()

        val response = withContext(Dispatchers.IO) { httpClient.newCall(request).execute() }
        if (!response.isSuccessful) throw Exception("EL HTTP ${response.code}")
        val bytes = response.body?.bytes() ?: return

        val tempFile = java.io.File(context.cacheDir, "checkmate_tts.mp3")
        tempFile.writeBytes(bytes)
        val mp = android.media.MediaPlayer()
        mp.setDataSource(tempFile.absolutePath)
        mp.prepare()
        mp.start()
        mp.setOnCompletionListener { mp.release() }
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        ttsReady = false
    }
}
