package com.checkmate.service

import android.content.Context
import android.net.Uri
import android.util.Log
import com.checkmate.BuildConfig
import com.checkmate.core.CheckmatePrefs
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * TelegramAlertBot — sends screenshot + caption to guardian via the Cloudflare Worker relay.
 *
 * The Telegram bot token no longer lives in the app. It is a Worker secret; the app only
 * talks to the Worker's /tg/send and /tg/photo routes, authenticated with a rotatable relay
 * key (relay_key in local.properties -> BuildConfig.RELAY_KEY). Public API is unchanged, so
 * no call site needs to change.
 *
 * Guardian onboarding:
 *   1. Guardian opens Telegram -> searches the bot -> sends /start
 *   2. Guardian messages @userinfobot to get their chat_id
 *   3. Student enters that chat_id in Settings -> Guardian Telegram Chat ID
 *
 * Call sendAlert() / uploadPhotoAndGetFileId() from a background thread — both block on network.
 */
object TelegramAlertBot {

    private const val TAG = "TelegramAlertBot"
    private const val RELAY_BASE = "https://steep-band-1bd0.amirshamse8.workers.dev"
    private val RELAY_KEY get() = BuildConfig.RELAY_KEY

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Send photo + caption to guardian.
     * Falls back to text-only if screenshotUri is null or upload fails.
     * Must be called from a background thread.
     */
    fun sendAlert(context: Context, caption: String, screenshotUri: Uri? = null) {
        if (RELAY_KEY.isBlank()) {
            Log.e(TAG, "relay_key not set in local.properties — skipping Telegram alert")
            return
        }
        val chatId = getChatId() ?: run {
            Log.w(TAG, "telegram_chat_id not set — skipping alert")
            return
        }

        if (screenshotUri != null) {
            val tmp = copyUriToTempFile(context, screenshotUri)
            if (tmp != null) {
                val sent = sendPhoto(chatId, caption, tmp) != null
                tmp.delete()
                if (sent) return
                Log.w(TAG, "sendPhoto failed — falling back to text")
            }
        }

        sendText(chatId, caption)
    }

    /**
     * Uploads a screenshot to the guardian's chat with the given caption and
     * returns Telegram's file_id for the largest photo size, or null on failure.
     * The file_id is reusable by the Worker to re-send the same image.
     *
     * Must be called from a background thread.
     */
    fun uploadPhotoAndGetFileId(context: Context, caption: String, screenshotUri: Uri): String? {
        if (RELAY_KEY.isBlank()) {
            Log.e(TAG, "relay_key not set in local.properties — skipping status photo upload")
            return null
        }
        val chatId = getChatId() ?: run {
            Log.w(TAG, "telegram_chat_id not set — skipping status photo upload")
            return null
        }

        val tmp = copyUriToTempFile(context, screenshotUri) ?: return null
        return try {
            sendPhoto(chatId, caption, tmp)
        } finally {
            tmp.delete()
        }
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    /** Returns the file_id on success (may be empty string if Worker omitted it), null on failure. */
    private fun sendPhoto(chatId: String, caption: String, photo: File): String? {
        return try {
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("chat_id", chatId)
                .addFormDataPart("caption", caption)
                .addFormDataPart("photo", photo.name, photo.asRequestBody("image/png".toMediaType()))
                .build()

            client.newCall(
                Request.Builder()
                    .url("$RELAY_BASE/tg/photo")
                    .addHeader("X-Relay-Key", RELAY_KEY)
                    .post(body)
                    .build()
            ).execute().use { response ->
                val bodyStr = response.body?.string()
                Log.d(TAG, "sendPhoto: ${response.code}")
                if (!response.isSuccessful || bodyStr.isNullOrBlank()) return null
                val json = JSONObject(bodyStr)
                if (!json.optBoolean("ok", false)) return null
                json.optString("fileId", "")
            }
        } catch (e: Exception) {
            Log.e(TAG, "sendPhoto exception: ${e.message}")
            null
        }
    }

    private fun sendText(chatId: String, text: String) {
        try {
            val payload = JSONObject().apply {
                put("chat_id", chatId)
                put("text", text)
            }
            client.newCall(
                Request.Builder()
                    .url("$RELAY_BASE/tg/send")
                    .addHeader("X-Relay-Key", RELAY_KEY)
                    .post(payload.toString().toRequestBody("application/json".toMediaType()))
                    .build()
            ).execute().use { Log.d(TAG, "sendText: ${it.code}") }
        } catch (e: Exception) {
            Log.e(TAG, "sendText exception: ${e.message}")
        }
    }

    private fun copyUriToTempFile(context: Context, uri: Uri): File? {
        return try {
            val tmp = File(context.cacheDir, "tg_${System.currentTimeMillis()}.png")
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tmp).use { output -> input.copyTo(output) }
            }
            tmp
        } catch (e: Exception) {
            Log.e(TAG, "copyUriToTempFile: ${e.message}")
            null
        }
    }

    fun getChatId(): String? {
        val id = CheckmatePrefs.getString("telegram_chat_id", null)
        return if (id.isNullOrBlank()) null else id.trim()
    }
}
