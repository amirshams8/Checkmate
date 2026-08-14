package com.checkmate.service

import android.util.Log
import com.checkmate.core.CheckmatePrefs
import com.checkmate.core.ConsultationProfile
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * ProfileSyncManager — backs up/restores the one-time SETUP PROFILE (exam type,
 * exam date, study window, TTS toggle, subjects config) PLUS the blocked
 * apps/websites lists, to the same Cloudflare Worker + KV that TaskSyncManager
 * already uses, keyed by the SAME "sync_code" the user sets in Settings →
 * Task Sync. The guardian WhatsApp number is deliberately EXCLUDED — it
 * stays local to each device and is never pushed or restored.
 *
 * This is deliberately NOT full multi-device account sync (see TaskSyncManager
 * for that, which mirrors today's task list live). This is a much smaller
 * "don't make me retype setup on a fresh install / new device" backup:
 *   - Every profile edit pushes the current profile up (fire-and-forget,
 *     last-write-wins, same as TaskSyncManager).
 *   - On a FRESH install (local exam_date still blank — i.e. setup never
 *     completed on this device) with a sync_code already configured, the next
 *     profile screen open pulls and restores it automatically.
 *   - Saving a sync_code in Settings also triggers an immediate pull attempt,
 *     so pairing a second/reinstalled device to an existing code restores
 *     setup right away instead of waiting for the next screen open.
 *
 * All calls are synchronous (OkHttp .execute()) — callers MUST run this off
 * the main thread (Thread{}.start(), matching how TaskSyncManager is called
 * elsewhere in this codebase).
 */
object ProfileSyncManager {

    private const val TAG = "ProfileSyncManager"
    private const val PROFILE_URL = "https://steep-band-1bd0.amirshamse8.workers.dev/profile"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private fun syncCode(): String? =
        CheckmatePrefs.getString("sync_code", null)?.trim()?.takeIf { it.isNotBlank() }

    /** True when a sync code is configured — callers can use this to show/hide sync UI state. */
    fun isEnabled(): Boolean = syncCode() != null

    /**
     * Pushes the current setup profile up under the configured sync code.
     * No-op if no sync code is set. Must be called from a background thread.
     */
    fun pushProfile() {
        val code = syncCode() ?: return
        try {
            val profile = JSONObject().apply {
                put("exam_type",       CheckmatePrefs.getString("exam_type", "NEET") ?: "NEET")
                put("exam_date",       CheckmatePrefs.getString("exam_date", "") ?: "")
                put("study_start",     CheckmatePrefs.getString("study_start", "06:00") ?: "06:00")
                put("study_end",       CheckmatePrefs.getString("study_end", "22:00") ?: "22:00")
                // guardian_number intentionally excluded — WhatsApp number stays local per device
                put("tts_enabled",     CheckmatePrefs.getBoolean("tts_enabled", true))
                put("subjects_config", CheckmatePrefs.getString("subjects_config", "") ?: "")
                put("blocked_apps",    CheckmatePrefs.getString("blocked_apps", "") ?: "")
                put("blocked_domains", CheckmatePrefs.getString("blocked_domains", "") ?: "")
                // Whole Student Profile screen (ConsultationProfile) — candidate name,
                // exam target/date, class, coaching, target/mock score, weak
                // subjects/topics, stress/sleep/study hours, blocked time slots.
                // Stored as ConsultationProfile's own serialized JSON, synced as one
                // blob rather than decomposed field-by-field.
                put("consultation_profile_json", CheckmatePrefs.getString("consultation_profile", "") ?: "")
            }
            val payload = JSONObject().apply {
                put("code", code)
                put("updatedAt", System.currentTimeMillis())
                put("profile", profile)
            }
            val body = payload.toString().toRequestBody("application/json".toMediaType())
            val response = client.newCall(Request.Builder().url(PROFILE_URL).post(body).build()).execute()
            Log.d(TAG, "pushProfile: ${response.code}")
            response.close()
        } catch (e: Exception) {
            Log.w(TAG, "pushProfile exception: ${e.message}")
        }
    }

    /**
     * Restores the synced profile into CheckmatePrefs, but ONLY if this device's
     * local setup looks unconfigured (exam_date blank) — never overwrites a setup
     * the user has actually filled in on this device. Returns true if a restore
     * happened. Must be called from a background thread.
     *
     * FIX: blocked_apps / blocked_domains are now only written when the remote
     * value is actually non-blank. Previously these were written unconditionally
     * with profile.optString(key, ""), which meant that any account whose synced
     * profile predates this field being added (or which simply never set a
     * block list on another device) would silently wipe out this device's local
     * block list on restore — killing the distraction guard, since
     * WorkModeManager.getBlockedApps()/getBlockedDomains() read straight from
     * these same prefs keys. Same guard pattern already used for
     * consultation_profile_json below is now applied here too.
     */
    fun pullProfileIfLocalEmpty(): Boolean {
        val code = syncCode() ?: return false
        val localExamDate = CheckmatePrefs.getString("exam_date", "") ?: ""
        if (localExamDate.isNotBlank()) return false // real local setup already exists — don't clobber it

        return try {
            val request = Request.Builder().url("$PROFILE_URL?code=$code").get().build()
            val response = client.newCall(request).execute()
            val bodyStr = response.body?.string()
            response.close()
            if (!response.isSuccessful || bodyStr.isNullOrBlank()) return false

            val obj = JSONObject(bodyStr)
            val profile = obj.optJSONObject("profile") ?: return false
            val remoteExamDate = profile.optString("exam_date", "")
            if (remoteExamDate.isBlank()) return false // nothing meaningful pushed yet

            CheckmatePrefs.putString("exam_type",       profile.optString("exam_type", "NEET"))
            CheckmatePrefs.putString("exam_date",       remoteExamDate)
            CheckmatePrefs.putString("study_start",     profile.optString("study_start", "06:00"))
            CheckmatePrefs.putString("study_end",       profile.optString("study_end", "22:00"))
            // guardian_number intentionally excluded — WhatsApp number stays local per device
            CheckmatePrefs.putBoolean("tts_enabled",    profile.optBoolean("tts_enabled", true))
            CheckmatePrefs.putString("subjects_config", profile.optString("subjects_config", ""))
            // Guarded: never stomp a local block list with blank just because the
            // synced copy doesn't have one yet (see fix note in kdoc above).
            profile.optString("blocked_apps", "").takeIf { it.isNotBlank() }?.let {
                CheckmatePrefs.putString("blocked_apps", it)
            }
            profile.optString("blocked_domains", "").takeIf { it.isNotBlank() }?.let {
                CheckmatePrefs.putString("blocked_domains", it)
            }
            profile.optString("consultation_profile_json", "").takeIf { it.isNotBlank() }?.let {
                CheckmatePrefs.putString("consultation_profile", it)
            }
            Log.d(TAG, "pullProfileIfLocalEmpty: restored profile from sync code")
            true
        } catch (e: Exception) {
            Log.w(TAG, "pullProfileIfLocalEmpty exception: ${e.message}")
            false
        }
    }

    /**
     * Restores the Student Profile screen (ConsultationProfile) ONLY if this
     * device has no local profile yet (ConsultationProfile.hasProfile() ==
     * false) — never overwrites a profile actually filled in on this device.
     * Returns true if a restore happened. Must be called from a background
     * thread.
     */
    fun pullConsultationProfileIfEmpty(): Boolean {
        val code = syncCode() ?: return false
        if (ConsultationProfile.hasProfile()) return false // real local profile already exists

        return try {
            val request = Request.Builder().url("$PROFILE_URL?code=$code").get().build()
            val response = client.newCall(request).execute()
            val bodyStr = response.body?.string()
            response.close()
            if (!response.isSuccessful || bodyStr.isNullOrBlank()) return false

            val obj = JSONObject(bodyStr)
            val remoteProfile = obj.optJSONObject("profile") ?: return false
            val raw = remoteProfile.optString("consultation_profile_json", "")
            if (raw.isBlank()) return false // nothing meaningful pushed yet

            CheckmatePrefs.putString("consultation_profile", raw)
            Log.d(TAG, "pullConsultationProfileIfEmpty: restored Student Profile from sync code")
            true
        } catch (e: Exception) {
            Log.w(TAG, "pullConsultationProfileIfEmpty exception: ${e.message}")
            false
        }
    }
}
