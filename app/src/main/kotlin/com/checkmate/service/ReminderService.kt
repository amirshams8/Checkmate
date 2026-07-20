package com.checkmate.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.checkmate.core.tts.CheckmateTTS
import com.checkmate.planner.PlanStore
import com.checkmate.planner.model.TaskState
import com.checkmate.workmode.UninstallGuard
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class ReminderService : Service() {
    companion object {
        private const val CHANNEL_ID = "reminder_channel"
        private const val NOTIF_ID   = 55
        // Mentor v2 (spec 3.5): same Cloudflare Worker StatusReporter already pushes to.
        // NOTE: the worker-side route that lets a guardian issue an "unlock"/"override"
        // command via Telegram and have it show up here isn't part of this Android repo —
        // this only polls a /override-status endpoint and expects {"override": true|false}
        // back. Add the matching route to worker.js to make this live end-to-end; until then
        // this poll just always sees false and grantRemoteOverride() is never called, so it's
        // a safe no-op rather than a broken dependency.
        private const val OVERRIDE_URL = "https://steep-band-1bd0.amirshamse8.workers.dev/override-status"

        fun start(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                context.startForegroundService(Intent(context, ReminderService::class.java))
            else
                context.startService(Intent(context, ReminderService::class.java))
        }
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIF_ID, buildNotification("Monitoring tasks…"))
        scope.launch {
            while (isActive) {
                checkPendingTasks()
                // Mentor v2 (spec 3.2): idle check-in — appends to Mentor chat + notifies if
                // nothing's been started by the configured hour. No-ops after the first fire
                // each day (see ProactiveMentor.idleCheckIfNeeded's day-key guard).
                try { ProactiveMentor.idleCheckIfNeeded(applicationContext) } catch (_: Exception) {}
                // Mentor v2 (spec 3.5): best-effort remote-override poll — see OVERRIDE_URL note.
                try { pollRemoteOverride() } catch (_: Exception) {}
                delay(15 * 60 * 1000L) // check every 15 min
            }
        }
    }

    private suspend fun checkPendingTasks() {
        val tasks = PlanStore.getTodayTasksSnapshot()
        val pending = tasks.filter { it.state == TaskState.PENDING }
        if (pending.isNotEmpty()) {
            val next = pending.first()
            CheckmateTTS.speak(this, "Reminder: ${next.subject} — ${next.topic} is pending.")
        }
    }

    /** See OVERRIDE_URL note above — safe no-op until the worker-side route exists. */
    private suspend fun pollRemoteOverride() = withContext(Dispatchers.IO) {
        val chatId = TelegramAlertBot.getChatId() ?: return@withContext
        val request = Request.Builder()
            .url("$OVERRIDE_URL?chatId=$chatId")
            .get()
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext
            val body = response.body?.string() ?: return@withContext
            val overridden = JSONObject(body).optBoolean("override", false)
            if (overridden) UninstallGuard.grantRemoteOverride()
        }
    }

    private fun buildNotification(text: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("Checkmate")
        .setContentText(text)
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setOngoing(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .build()

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "Reminders", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
}
