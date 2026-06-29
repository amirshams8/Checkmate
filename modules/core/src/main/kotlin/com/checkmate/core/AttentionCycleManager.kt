package com.checkmate.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// PROMPT_DONE: focus block just ended — timer frozen, waiting for user decision
enum class AttentionPhase { FOCUS, SHORT_BREAK, LONG_BREAK, PAUSED, PROMPT_DONE, DONE }

data class CycleState(
    val phase:               AttentionPhase = AttentionPhase.FOCUS,
    val phaseSecondsLeft:    Long           = 30 * 60,
    val totalSessionSeconds: Long           = 0,
    val cycleIndex:          Int            = 1,
    val needsAttentionCheck: Boolean        = false,
    val phaseJustChanged:    Boolean        = false,
    val checksPassed:        Int            = 0,
    val checksMissed:        Int            = 0,
    val taskId:              String         = "",
    val taskName:            String         = "",
    val pausedAt:            Long           = 0L,
    val totalPausedMs:       Long           = 0L,
    // Total length of the active task in seconds — used by the floating bar
    // to scale its progress indicator correctly in both Pomodoro and plain mode.
    val totalDurationSecs:   Long           = 0L,
    // Snapshot (taken at start()) of whether Pomodoro-style breaks/checks are
    // active for this session. Exposed on state so UI doesn't need prefs access.
    val pomodoroEnabled:     Boolean        = true
)

object AttentionCycleManager {

    private const val FOCUS_SECS               = 30 * 60L
    private const val SHORT_BREAK_SECS         = 5  * 60L
    private const val LONG_BREAK_SECS          = 10 * 60L
    private const val FOCUS_BLOCKS_BEFORE_LONG = 2

    // Pref key — read once per session in start() and snapshotted onto CycleState
    // and into the private var below so mid-session toggling in Settings never
    // changes the rules of an already-running session.
    const val PREF_POMODORO_ENABLED = "pomodoro_enabled"

    private val _state = MutableStateFlow(CycleState())
    val stateFlow: StateFlow<CycleState> = _state.asStateFlow()

    private var focusBlocksDone     = 0
    private var checkWindowOpen     = false
    private var checkWindowOpenedAt = 0L
    private var totalDurationSecs   = 0L
    private var elapsedTotal        = 0L
    private var phaseBeforePause:   AttentionPhase = AttentionPhase.FOCUS
    // The break phase queued up while we're showing PROMPT_DONE
    private var pendingBreakPhase:  AttentionPhase = AttentionPhase.SHORT_BREAK
    // When false: no 30-min blocks, no breaks, no PROMPT_DONE prompts, no
    // forced attention-check taps — just one continuous focus timer for the
    // whole task duration. Toggled via Settings → Work Mode → Pomodoro Breaks.
    private var pomodoroEnabled     = true

    fun start(taskId: String, taskName: String, durationMinutes: Long) {
        pomodoroEnabled   = CheckmatePrefs.getBoolean(PREF_POMODORO_ENABLED, true)
        totalDurationSecs = durationMinutes * 60
        elapsedTotal      = 0
        focusBlocksDone   = 0
        checkWindowOpen   = false
        pendingBreakPhase = AttentionPhase.SHORT_BREAK

        val initialSecondsLeft = if (pomodoroEnabled) FOCUS_SECS else totalDurationSecs

        _state.value = CycleState(
            phase             = AttentionPhase.FOCUS,
            phaseSecondsLeft  = initialSecondsLeft,
            taskId            = taskId,
            taskName          = taskName,
            totalDurationSecs = totalDurationSecs,
            pomodoroEnabled   = pomodoroEnabled
        )
    }

    /** Called from the floating bar \"✅ Mark Done\" button during PROMPT_DONE. */
    fun confirmDone() {
        val done = _state.value.copy(
            phase            = AttentionPhase.DONE,
            phaseJustChanged = true
        )
        _state.value = done
    }

    /**
     * Called from the floating bar \"➡ Continue\" button during PROMPT_DONE.
     * Advances to the queued break phase so the cycle keeps going.
     */
    fun dismissPromptAndBreak() {
        val breakSecs = if (pendingBreakPhase == AttentionPhase.LONG_BREAK)
            LONG_BREAK_SECS else SHORT_BREAK_SECS
        _state.value = _state.value.copy(
            phase               = pendingBreakPhase,
            phaseSecondsLeft    = breakSecs,
            needsAttentionCheck = false,
            phaseJustChanged    = true
        )
    }

    /** Blueprint 2.2: Pause — freezes tick loop, records pausedAt timestamp */
    fun pause() {
        val current = _state.value
        if (current.phase == AttentionPhase.PAUSED ||
            current.phase == AttentionPhase.DONE   ||
            current.phase == AttentionPhase.PROMPT_DONE) return
        phaseBeforePause = current.phase
        _state.value = current.copy(
            phase            = AttentionPhase.PAUSED,
            phaseJustChanged = true,
            pausedAt         = System.currentTimeMillis()
        )
    }

    /** Blueprint 2.2: Resume — restarts tick, accumulates totalPausedMs */
    fun resume() {
        val current = _state.value
        if (current.phase != AttentionPhase.PAUSED) return
        val pauseDuration = if (current.pausedAt > 0L)
            System.currentTimeMillis() - current.pausedAt else 0L
        _state.value = current.copy(
            phase            = phaseBeforePause,
            phaseJustChanged = true,
            pausedAt         = 0L,
            totalPausedMs    = current.totalPausedMs + pauseDuration
        )
    }

    fun tick(): CycleState {
        val current = _state.value
        // Freeze tick while paused, in prompt, or done
        if (current.phase == AttentionPhase.PAUSED     ||
            current.phase == AttentionPhase.PROMPT_DONE ||
            current.phase == AttentionPhase.DONE)
            return current

        elapsedTotal++

        if (totalDurationSecs > 0 && elapsedTotal >= totalDurationSecs) {
            val done = current.copy(
                phase               = AttentionPhase.DONE,
                phaseJustChanged    = true,
                phaseSecondsLeft    = 0,
                totalSessionSeconds = elapsedTotal
            )
            _state.value = done
            return done
        }

        val newSecondsLeft = current.phaseSecondsLeft - 1

        // ── Plain continuous timer: no breaks, no PROMPT_DONE, no forced checks ──
        if (!pomodoroEnabled) {
            val next = current.copy(
                phaseSecondsLeft    = newSecondsLeft.coerceAtLeast(0),
                totalSessionSeconds = elapsedTotal,
                needsAttentionCheck = false,
                phaseJustChanged    = false
            )
            _state.value = next
            return next
        }

        // ── Pomodoro mode: 30-min focus blocks with breaks + attention checks ──
        val needsCheck = current.phase == AttentionPhase.FOCUS
                && newSecondsLeft in 0..120 && !checkWindowOpen
        if (needsCheck) {
            checkWindowOpen     = true
            checkWindowOpenedAt = elapsedTotal
        }

        var checksMissed = current.checksMissed
        if (checkWindowOpen && elapsedTotal - checkWindowOpenedAt > 120) {
            checksMissed++
            checkWindowOpen = false
        }

        if (newSecondsLeft <= 0) {
            return when (current.phase) {
                AttentionPhase.FOCUS -> {
                    focusBlocksDone++
                    // Decide what break comes *after* the prompt
                    pendingBreakPhase = if (focusBlocksDone % FOCUS_BLOCKS_BEFORE_LONG == 0)
                        AttentionPhase.LONG_BREAK else AttentionPhase.SHORT_BREAK

                    // Enter PROMPT_DONE — timer frozen, user decides
                    val next = current.copy(
                        phase               = AttentionPhase.PROMPT_DONE,
                        phaseSecondsLeft    = 0,
                        totalSessionSeconds = elapsedTotal,
                        cycleIndex          = focusBlocksDone + 1,
                        needsAttentionCheck = false,
                        phaseJustChanged    = true,
                        checksMissed        = checksMissed
                    )
                    _state.value = next; next
                }
                AttentionPhase.SHORT_BREAK, AttentionPhase.LONG_BREAK -> {
                    checkWindowOpen = false
                    val next = current.copy(
                        phase               = AttentionPhase.FOCUS,
                        phaseSecondsLeft    = FOCUS_SECS,
                        totalSessionSeconds = elapsedTotal,
                        needsAttentionCheck = false,
                        phaseJustChanged    = true,
                        checksMissed        = checksMissed
                    )
                    _state.value = next; next
                }
                else -> current
            }
        }

        val next = current.copy(
            phaseSecondsLeft    = newSecondsLeft,
            totalSessionSeconds = elapsedTotal,
            needsAttentionCheck = checkWindowOpen,
            phaseJustChanged    = false,
            checksMissed        = checksMissed
        )
        _state.value = next
        return next
    }

    fun confirmAttention() {
        checkWindowOpen = false
        _state.value = _state.value.copy(
            checksPassed        = _state.value.checksPassed + 1,
            needsAttentionCheck = false
        )
    }

    fun currentState(): CycleState = _state.value

    fun reset() {
        focusBlocksDone     = 0
        checkWindowOpen     = false
        elapsedTotal        = 0
        totalDurationSecs   = 0
        pendingBreakPhase   = AttentionPhase.SHORT_BREAK
        pomodoroEnabled     = true
        _state.value        = CycleState()
    }
}
