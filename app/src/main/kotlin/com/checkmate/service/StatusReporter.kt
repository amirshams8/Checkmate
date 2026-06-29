package com.checkmate.service

import android.content.Context
import android.util.Log
import com.checkmate.core.CycleState
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * StatusReporter — pushes a combined "status + screenshot" snapshot to the
 * Cloudflare Worker every ~5 minutes during an active focus session.
 *
 * Flow:
 *   1. Capture a screenshot via ScreenCaptureManager (existing MediaProjection pipeline)
 *   2. Upload it to the guardian's Telegram chat via TelegramAlertBot to obtain a file_id
 *   3. POST status fields + file_id + timestamps to the worker's /status endpoint
 *
 * The worker caches this in KV keyed by chat_id. When the guardian messages the
 * bot with "status", the bot replies instantly using the cached text + file_id —
 * no need to wake the phone.
 *
 * NOTE: step 2 sends the screenshot to the guardian's chat as a normal message
 * (captioned "🔄 Routine check-in"), since Telegram file_ids are only obtainable
 * by actually sending the photo somewhere. This means the guardian will see a
 * routine check-in photo roughly every 5 minutes during focus sessions, in
 * addition to any distraction alerts. If this becomes too noisy, increase
 * STATUS_INTERVAL_SECONDS.
 *
 * Must be called from a background thread.
 */
object StatusReporter {

    private const val TAG = "StatusReporter"

    // How often (in elapsed session seconds) to push a status+screenshot update.
    const val STATUS_INTERVAL_SECONDS = 300L // 5 minutes

    // Your Cloudflare Worker URL
    private const val WORKER_URL = "https://steep-band-1bd0.amirshamse8.workers.dev/status"
    // Same worker, /usage route — caches the latest app-usage report so the
    // guardian can text "usage" any time and get an instant reply (up to
    // ~30 min stale) without needing to wake the phone.
    private const val USAGE_URL  = "https://steep-band-1bd0.amirshamse8.workers.dev/usage"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    /**
     * Captures a screenshot, uploads it for a file_id, and pushes the combined
     * status snapshot to the worker. Safe to call repeatedly; failures are
     * logged and swallowed so they never crash the cycle loop.
     */
    fun pushStatus(context: Context, cs: CycleState) {
        try {
            val chatId = TelegramAlertBot.getChatId()
            if (chatId.isNullOrBlank()) {
                Log.w(TAG, "telegram_chat_id not set — skipping status push")
                return
            }

            val now = System.currentTimeMillis()

            // 1. Capture screenshot (returns content:// Uri or null)
            val screenshotUri = ScreenCaptureManager.capture(context)

            // 2. Upload to guardian's chat to obtain a file_id (best-effort)
            var fileId: String? = null
            if (screenshotUri != null) {
                fileId = TelegramAlertBot.uploadPhotoAndGetFileId(
                    context,
                    caption = "🔄 Routine check-in",
                    screenshotUri = screenshotUri
                )
                if (fileId == null) {
                    Log.w(TAG, "Screenshot upload failed — pushing status without screenshot")
                }
            } else {
                Log.w(TAG, "Screenshot capture returned null — pushing status without screenshot")
            }

            // 3. Build payload
            val payload = JSONObject().apply {
                put("chatId", chatId)
                put("phase", cs.phase.name)
                put("secondsLeft", cs.phaseSecondsLeft)
                put("cycleIndex", cs.cycleIndex)
                put("checksMissed", cs.checksMissed)
                put("totalSessionSeconds", cs.totalSessionSeconds)
                put("timestamp", now)
                if (fileId != null) {
                    put("fileId", fileId)
                    put("screenshotTimestamp", now)
                }
            }

            // 4. POST to worker
            val body = payload.toString().toRequestBody("application/json".toMediaType())
            val response = client.newCall(
                Request.Builder().url(WORKER_URL).post(body).build()
            ).execute()
            Log.d(TAG, "pushStatus: ${response.code}")
            response.close()
        } catch (e: Exception) {
            Log.e(TAG, "pushStatus exception: ${e.message}")
        }
    }

    /**
     * Caches the latest app-usage report text in the worker's KV under
     * usage:<chatId>. Call this alongside (or instead of) a direct Telegram
     * push so a guardian texting "usage" between scheduled pushes still gets
     * an instant reply from the worker, rather than nothing.
     * Must be called from a background thread.
     */
    fun pushUsageReport(context: Context, report: String) {
        try {
            val chatId = TelegramAlertBot.getChatId()
            if (chatId.isNullOrBlank()) {
                Log.w(TAG, "telegram_chat_id not set — skipping usage cache push")
                return
            }
            val payload = JSONObject().apply {
                put("chatId", chatId)
                put("report", report)
                put("timestamp", System.currentTimeMillis())
            }
            val body = payload.toString().toRequestBody("application/json".toMediaType())
            val response = client.newCall(
                Request.Builder().url(USAGE_URL).post(body).build()
            ).execute()
            Log.d(TAG, "pushUsageReport: ${response.code}")
            response.close()
        } catch (e: Exception) {
            Log.e(TAG, "pushUsageReport exception: ${e.message}")
        }
    }
}
