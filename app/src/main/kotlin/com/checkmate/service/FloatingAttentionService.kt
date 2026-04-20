package com.checkmate.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.*
import android.view.*
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.checkmate.core.AttentionCycleManager
import com.checkmate.core.AttentionPhase
import com.checkmate.ui.theme.*
import kotlinx.coroutines.*

/**
 * Floating overlay showing phase timer + ✅ attention check button.
 * The ✅ button pulses when it's time for a check.
 */
class FloatingAttentionService : Service() {

    companion object {
        private const val CHANNEL_ID = "floating_attention_channel"
        private const val NOTIF_ID   = 99

        fun start(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                context.startForegroundService(Intent(context, FloatingAttentionService::class.java))
            else
                context.startService(Intent(context, FloatingAttentionService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FloatingAttentionService::class.java))
        }
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var tvPhase: TextView? = null
    private var tvSession: TextView? = null
    private var tvCycle: TextView? = null
    private var btnCheck: TextView? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIF_ID, buildNotification())
        setupOverlay()
        startUpdating()
    }

    private fun setupOverlay() {
        if (!android.provider.Settings.canDrawOverlays(this)) return

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 16
            y = 120
        }

        // Build a simple programmatic view (no XML needed)
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundColor(0xDD11111C.toInt())
            setPadding(24, 16, 24, 16)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xDD11111C.toInt())
                cornerRadius = 20f
            }
        }

        tvPhase = TextView(this).apply {
            setTextColor(0xFF00C896.toInt())
            textSize = 14f
            typeface = android.graphics.Typeface.MONOSPACE
            text = "FOCUS — 30:00"
        }
        tvSession = TextView(this).apply {
            setTextColor(0xFF9090A8.toInt())
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            text = "Session: 0m 0s"
        }
        tvCycle = TextView(this).apply {
            setTextColor(0xFF505068.toInt())
            textSize = 10f
            typeface = android.graphics.Typeface.MONOSPACE
            text = "Cycle 1"
        }
        btnCheck = TextView(this).apply {
            setTextColor(0xFF00C896.toInt())
            textSize = 22f
            text = "✅"
            visibility = View.GONE
            setOnClickListener {
                AttentionCycleManager.confirmAttention()
                visibility = View.GONE
            }
        }

        layout.addView(tvPhase)
        layout.addView(tvSession)
        layout.addView(tvCycle)
        layout.addView(btnCheck)
        overlayView = layout

        try { windowManager?.addView(overlayView, params) } catch (_: Exception) {}
    }

    private fun startUpdating() {
        scope.launch {
            while (isActive) {
                val cs = AttentionCycleManager.currentState()
                val m = cs.phaseSecondsLeft / 60
                val s = cs.phaseSecondsLeft % 60
                val tm = cs.totalSessionSeconds / 60
                val ts = cs.totalSessionSeconds % 60

                val phaseColor = when (cs.phase) {
                    AttentionPhase.FOCUS       -> 0xFF00C896.toInt()
                    AttentionPhase.SHORT_BREAK -> 0xFF00C896.toInt()
                    AttentionPhase.LONG_BREAK  -> 0xFFFFB347.toInt()
                    AttentionPhase.DONE        -> 0xFF9090A8.toInt()
                }
                tvPhase?.setTextColor(phaseColor)
                tvPhase?.text = when (cs.phase) {
                    AttentionPhase.FOCUS       -> "FOCUS — $m:${String.format("%02d", s)}"
                    AttentionPhase.SHORT_BREAK -> "BREAK — $m:${String.format("%02d", s)}"
                    AttentionPhase.LONG_BREAK  -> "LONG BREAK — $m:${String.format("%02d", s)}"
                    AttentionPhase.DONE        -> "DONE"
                }
                tvSession?.text = "Session: ${tm}m ${ts}s"
                tvCycle?.text   = "Cycle ${cs.cycleIndex}"
                btnCheck?.visibility = if (cs.needsAttentionCheck) View.VISIBLE else View.GONE

                if (cs.phase == AttentionPhase.DONE) stopSelf()
                delay(1_000)
            }
        }
    }

    private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("Checkmate — Session Active")
        .setSmallIcon(android.R.drawable.ic_menu_recent_history)
        .setOngoing(true)
        .setPriority(NotificationCompat.PRIORITY_MIN)
        .build()

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "Floating Timer", NotificationManager.IMPORTANCE_MIN)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        try { overlayView?.let { windowManager?.removeView(it) } } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
