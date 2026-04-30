package com.checkmate.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// Added PAUSED phase for Blueprint 2.2
enum class AttentionPhase { FOCUS, SHORT_BREAK, LONG_BREAK, PAUSED, DONE }

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
    val totalPausedMs:       Long           = 0L
)

object AttentionCycleManager {

    private const val FOCUS_SECS               = 30 * 60L
    private const val SHORT_BREAK_SECS         = 5  * 60L
    private const val LONG_BREAK_SECS          = 10 * 60L
    private const val FOCUS_BLOCKS_BEFORE_LONG = 2

    private val _state = MutableStateFlow(CycleState())
    val stateFlow: StateFlow<CycleState> = _state.asStateFlow()

    private var focusBlocksDone     = 0
    private var checkWindowOpen     = false
    private var checkWindowOpenedAt = 0L
    private var totalDurationSecs   = 0L
    private var elapsedTotal        = 0L
    private var phaseBeforePause:   AttentionPhase = AttentionPhase.FOCUS

    fun start(taskId: String, taskName: String, durationMinutes: Long) {
        totalDurationSecs = durationMinutes * 60
        elapsedTotal      = 0
        focusBlocksDone   = 0
        _state.value = CycleState(
            phase            = AttentionPhase.FOCUS,
            phaseSecondsLeft = FOCUS_SECS,
            taskId           = taskId,
            taskName         = taskName
        )
    }

    /** Blueprint 2.2: Pause — freezes tick loop, records pausedAt timestamp */
    fun pause() {
        val current = _state.value
        if (current.phase == AttentionPhase.PAUSED || current.phase == AttentionPhase.DONE) return
        phaseBeforePause = current.phase
        _state.value = current.copy(
            phase          = AttentionPhase.PAUSED,
            phaseJustChanged = true,
            pausedAt       = System.currentTimeMillis()
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
        // Don't tick while paused or done
        if (current.phase == AttentionPhase.PAUSED || current.phase == AttentionPhase.DONE)
            return current

        elapsedTotal++

        if (totalDurationSecs > 0 && elapsedTotal >= totalDurationSecs) {
            val done = current.copy(phase = AttentionPhase.DONE, phaseJustChanged = true)
            _state.value = done
            return done
        }

        val newSecondsLeft = current.phaseSecondsLeft - 1

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
                    val nextPhase = if (focusBlocksDone % FOCUS_BLOCKS_BEFORE_LONG == 0)
                        AttentionPhase.LONG_BREAK else AttentionPhase.SHORT_BREAK
                    val nextSecs = if (nextPhase == AttentionPhase.LONG_BREAK) LONG_BREAK_SECS else SHORT_BREAK_SECS
                    val next = current.copy(
                        phase               = nextPhase,
                        phaseSecondsLeft    = nextSecs,
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
        _state.value = _state.value.copy(checksPassed = _state.value.checksPassed + 1, needsAttentionCheck = false)
    }

    fun currentState(): CycleState = _state.value

    fun reset() {
        focusBlocksDone     = 0
        checkWindowOpen     = false
        elapsedTotal        = 0
        totalDurationSecs   = 0
        _state.value        = CycleState()
    }
}
