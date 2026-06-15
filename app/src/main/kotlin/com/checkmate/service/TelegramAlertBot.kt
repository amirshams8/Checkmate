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
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * TelegramAlertBot — sends screenshot + caption to guardian via Telegram Bot API.
 *
 * Bot token: set telegram_bot_token in local.properties (never commit that file).
 * Bakes into BuildConfig.TELEGRAM_BOT_TOKEN at compile time.
 *
 * Guardian onboarding:
 *   1. Guardian opens Telegram → searches your bot → sends /start
 *   2. Guardian messages @userinfobot to get their chat_id
 *   3. Student enters that chat_id in Settings → Guardian Telegram Chat ID
 *
 * Call sendAlert() / uploadPhotoAndGetFileId() from a background thread — both block on network.
 */
object TelegramAlertBot {

    private const val TAG = "TelegramAlertBot"
    private val BOT_TOKEN get() = BuildConfig.TELEGRAM_BOT_TOKEN
    private val BASE_URL  get() = "https://api.telegram.org/bot$BOT_TOKEN"

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
        if (BOT_TOKEN.isBlank()) {
            Log.e(TAG, "BOT_TOKEN not set in local.properties — skipping Telegram alert")
            return
        }
        val chatId = getChatId() ?: run {
            Log.w(TAG, "telegram_chat_id not set — skipping alert")
            return
        }

        if (screenshotUri != null) {
            val tmp = copyUriToTempFile(context, screenshotUri)
            if (tmp != null) {
                val sent = sendPhoto(chatId, caption, tmp)
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
     *
     * The returned file_id can be reused later (e.g. by the Cloudflare worker)
     * to re-send the same image via sendPhoto without re-uploading bytes.
     *
     * Must be called from a background thread.
     */
    fun uploadPhotoAndGetFileId(context: Context, caption: String, screenshotUri: Uri): String? {
        if (BOT_TOKEN.isBlank()) {
            Log.e(TAG, "BOT_TOKEN not set in local.properties — skipping status photo upload")
            return null
        }
        val chatId = getChatId() ?: run {
            Log.w(TAG, "telegram_chat_id not set — skipping status photo upload")
            return null
        }

        val tmp = copyUriToTempFile(context, screenshotUri) ?: return null
        return try {
            sendPhotoForFileId(chatId, caption, tmp)
        } finally {
            tmp.delete()
        }
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private fun sendPhoto(chatId: String, caption: String, photo: File): Boolean {
        return try {
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("chat_id", chatId)
                .addFormDataPart("caption", caption)
                .addFormDataPart(
                    "photo", photo.name,
                    photo.asRequestBody("image/png".toMediaType())
                )
                .build()

            val response = client.newCall(
                Request.Builder().url("$BASE_URL/sendPhoto").post(body).build()
            ).execute()
            val ok = response.isSuccessful
            Log.d(TAG, "sendPhoto: ${response.code}")
            response.close()
            ok
        } catch (e: Exception) {
            Log.e(TAG, "sendPhoto exception: ${e.message}")
            false
        }
    }

    /**
     * Same as sendPhoto, but parses the response and returns the file_id of the
     * largest photo size (last entry in the "photo" array), or null on failure.
     */
    private fun sendPhotoForFileId(chatId: String, caption: String, photo: File): String? {
        return try {
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("chat_id", chatId)
                .addFormDataPart("caption", caption)
                .addFormDataPart(
                    "photo", photo.name,
                    photo.asRequestBody("image/png".toMediaType())
                )
                .build()

            val response = client.newCall(
                Request.Builder().url("$BASE_URL/sendPhoto").post(body).build()
            ).execute()

            val bodyStr = response.body?.string()
            Log.d(TAG, "sendPhotoForFileId: ${response.code}")
            response.close()

            if (!response.isSuccessful || bodyStr.isNullOrBlank()) return null

            val json = JSONObject(bodyStr)
            if (!json.optBoolean("ok", false)) return null

            val photoArray = json.getJSONObject("result").optJSONArray("photo") ?: return null
            if (photoArray.length() == 0) return null

            // Last entry = largest resolution
            photoArray.getJSONObject(photoArray.length() - 1).optString("file_id", null)
        } catch (e: Exception) {
            Log.e(TAG, "sendPhotoForFileId exception: ${e.message}")
            null
        }
    }

    private fun sendText(chatId: String, text: String) {
        try {
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("chat_id", chatId)
                .addFormDataPart("text", text)
                .build()

            val response = client.newCall(
                Request.Builder().url("$BASE_URL/sendMessage").post(body).build()
            ).execute()
            Log.d(TAG, "sendText: ${response.code}")
            response.close()
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
