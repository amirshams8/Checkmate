package com.checkmate.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.lifecycle.*
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.checkmate.core.AttentionCycleManager
import com.checkmate.core.AttentionPhase
import com.checkmate.core.CheckmatePrefs

class FloatingAttentionService : Service(),
    LifecycleOwner,
    ViewModelStoreOwner,
    SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    private val vmStore = ViewModelStore()
    override val viewModelStore: ViewModelStore get() = vmStore

    private val savedStateController = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateController.savedStateRegistry

    private lateinit var windowManager: WindowManager
    private var overlayView: ComposeView? = null

    companion object {
        private const val CHANNEL_ID = "floating_attention_channel"
        private const val NOTIF_ID   = 99

        // Pref key for the Settings → Work Mode → "Floating Focus Bar" toggle.
        // When off, the overlay never shows; the student still gets the same
        // controls (Done / Break / Confirm / Pause) via notification buttons
        // added in AttentionCycleService, so nothing becomes un-controllable.
        const val PREF_FOCUS_BAR_ENABLED = "focus_bar_enabled"

        fun start(context: Context) {
            if (!CheckmatePrefs.getBoolean(PREF_FOCUS_BAR_ENABLED, true)) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                !android.provider.Settings.canDrawOverlays(context)) return
            val intent = Intent(context, FloatingAttentionService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                context.startForegroundService(intent)
            else
                context.startService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FloatingAttentionService::class.java))
        }
    }

    override fun onCreate() {
        savedStateController.performAttach()
        savedStateController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification())
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        showOverlay()
        return START_NOT_STICKY
    }

    private fun showOverlay() {
        if (overlayView != null) return
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP }

        val view = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@FloatingAttentionService)
            setViewTreeViewModelStoreOwner(this@FloatingAttentionService)
            setViewTreeSavedStateRegistryOwner(this@FloatingAttentionService)
            setContent { AttentionBar(onDismiss = { stop(this@FloatingAttentionService) }) }
        }
        windowManager.addView(view, params)
        overlayView = view
    }

    override fun onDestroy() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        overlayView?.let { windowManager.removeView(it) }
        overlayView = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Attention overlay active")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true).setPriority(NotificationCompat.PRIORITY_LOW).build()

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "Floating Attention Bar", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
    }
}

// ── Colors ────────────────────────────────────────────────────────────────────

private val CGreen  = Color(0xFF00C896)
private val CAmber  = Color(0xFFFFB347)
private val CGray   = Color(0xFF9090A8)
private val CRed    = Color(0xFFFF4444)
private val CPurple = Color(0xFFBB86FC)
private val CBg     = Color(0xF0111122)

// ── Bar Composable ────────────────────────────────────────────────────────────

@Composable
private fun AttentionBar(onDismiss: () -> Unit) {
    val cs by AttentionCycleManager.stateFlow.collectAsState()

    LaunchedEffect(cs.phase) {
        if (cs.phase == AttentionPhase.DONE) onDismiss()
    }

    val phaseColor = when (cs.phase) {
        AttentionPhase.FOCUS        -> CGreen
        AttentionPhase.SHORT_BREAK  -> CGreen
        AttentionPhase.LONG_BREAK   -> CAmber
        AttentionPhase.PAUSED       -> CAmber
        AttentionPhase.PROMPT_DONE  -> CPurple
        AttentionPhase.DONE         -> CGray
    }

    // FOCUS phase length depends on mode: a fixed 30-min block in Pomodoro
    // mode, or the whole task duration in plain continuous-timer mode.
    val totalPhaseSecs = when (cs.phase) {
        AttentionPhase.FOCUS       -> if (cs.pomodoroEnabled) 30 * 60L
                                       else cs.totalDurationSecs.coerceAtLeast(1L)
        AttentionPhase.SHORT_BREAK -> 5  * 60L
        AttentionPhase.LONG_BREAK  -> 10 * 60L
        else                       -> 1L
    }
    val progress = when (cs.phase) {
        AttentionPhase.PROMPT_DONE -> 1f   // bar full at prompt
        else -> (1f - cs.phaseSecondsLeft.toFloat() / totalPhaseSecs.toFloat()).coerceIn(0f, 1f)
    }

    val m = cs.phaseSecondsLeft / 60
    val s = cs.phaseSecondsLeft % 60

    val phaseLabel = when (cs.phase) {
        AttentionPhase.FOCUS        -> "FOCUS"
        AttentionPhase.SHORT_BREAK  -> "BREAK"
        AttentionPhase.LONG_BREAK   -> "LONG BREAK"
        AttentionPhase.PAUSED       -> "PAUSED"
        AttentionPhase.PROMPT_DONE  -> "MARK DONE?"
        AttentionPhase.DONE         -> "DONE"
    }

    // Pulse color when paused or prompting
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue  = 0.5f,
        targetValue   = 1f,
        animationSpec = infiniteRepeatable(
            tween(700, easing = LinearEasing), RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )
    val shouldPulse = cs.phase == AttentionPhase.PAUSED || cs.phase == AttentionPhase.PROMPT_DONE
    val displayColor = if (shouldPulse) phaseColor.copy(alpha = pulseAlpha) else phaseColor

    Column(Modifier.fillMaxWidth().background(CBg)) {

        if (cs.phase == AttentionPhase.PROMPT_DONE) {
            // ── Prompt row: task done or take a break? ────────────────────────
            Row(
                modifier              = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text       = "30m done!",
                    fontSize   = 12.sp,
                    color      = displayColor,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    // ✅ Mark task done
                    Text(
                        text       = "✅ Done",
                        fontSize   = 13.sp,
                        color      = CGreen,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        modifier   = Modifier.clickable(
                            indication        = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ) { AttentionCycleManager.confirmDone() }
                    )
                    // ➡ Take break and continue task
                    Text(
                        text       = "➡ Break",
                        fontSize   = 13.sp,
                        color      = CAmber,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        modifier   = Modifier.clickable(
                            indication        = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ) { AttentionCycleManager.dismissPromptAndBreak() }
                    )
                }
            }
        } else {
            // ── Normal row: phase label + timer + controls ────────────────────
            Row(
                modifier              = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Left: phase + timer + cycle
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text       = phaseLabel,
                        fontSize   = 11.sp,
                        color      = displayColor,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text       = "%02d:%02d".format(m, s),
                        fontSize   = 13.sp,
                        color      = displayColor,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                    if (cs.pomodoroEnabled) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text       = "· C${cs.cycleIndex}",
                            fontSize   = 10.sp,
                            color      = CGray,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                // Right: ✅ confirm attention + ⏸/▶ pause + ✕ dismiss
                Row(verticalAlignment = Alignment.CenterVertically) {

                    if (cs.needsAttentionCheck) {
                        Text(
                            text     = "✅",
                            fontSize = 18.sp,
                            modifier = Modifier
                                .padding(end = 12.dp)
                                .clickable(
                                    indication        = null,
                                    interactionSource = remember { MutableInteractionSource() }
                                ) { AttentionCycleManager.confirmAttention() }
                        )
                    }

                    if (cs.phase != AttentionPhase.DONE) {
                        val pauseIcon  = if (cs.phase == AttentionPhase.PAUSED) "▶" else "⏸"
                        val pauseColor = if (cs.phase == AttentionPhase.PAUSED) CGreen else CAmber
                        Text(
                            text     = pauseIcon,
                            fontSize = 16.sp,
                            color    = pauseColor,
                            modifier = Modifier
                                .padding(end = 12.dp)
                                .clickable(
                                    indication        = null,
                                    interactionSource = remember { MutableInteractionSource() }
                                ) {
                                    if (cs.phase == AttentionPhase.PAUSED) AttentionCycleManager.resume()
                                    else AttentionCycleManager.pause()
                                }
                        )
                    }

                    Text(
                        text       = "✕",
                        fontSize   = 13.sp,
                        color      = CRed.copy(alpha = 0.85f),
                        fontFamily = FontFamily.Monospace,
                        modifier   = Modifier.clickable(
                            indication        = null,
                            interactionSource = remember { MutableInteractionSource() },
                            onClick           = onDismiss
                        )
                    )
                }
            }
        }

        LinearProgressIndicator(
            progress   = { progress },
            modifier   = Modifier
                .fillMaxWidth()
                .height(3.dp)
                .clip(RoundedCornerShape(0.dp)),
            color      = displayColor,
            trackColor = displayColor.copy(alpha = 0.12f)
        )
    }
}
