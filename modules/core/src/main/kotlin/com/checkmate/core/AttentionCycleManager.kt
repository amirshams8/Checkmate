package com.checkmate.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AttentionPhase { FOCUS, SHORT_BREAK, LONG_BREAK, DONE }

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
    val taskName:            String         = ""
)

object AttentionCycleManager {

    // Configurable (could be exposed in settings later)
    private const val FOCUS_SECS       = 30 * 60L
    private const val SHORT_BREAK_SECS = 5  * 60L
    private const val LONG_BREAK_SECS  = 10 * 60L
    private const val FOCUS_BLOCKS_BEFORE_LONG = 2

    private val _state = MutableStateFlow(CycleState())
    val stateFlow: StateFlow<CycleState> = _state.asStateFlow()

    private var focusBlocksDone = 0
    private var checkWindowOpen = false
    private var checkWindowOpenedAt = 0L
    private var totalDurationSecs = 0L
    private var elapsedTotal = 0L

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

    /** Called every second by AttentionCycleService */
    fun tick(): CycleState {
        val current = _state.value
        elapsedTotal++

        // Total session done
        if (totalDurationSecs > 0 && elapsedTotal >= totalDurationSecs) {
            val done = current.copy(phase = AttentionPhase.DONE, phaseJustChanged = true)
            _state.value = done
            return done
        }

        val newSecondsLeft = current.phaseSecondsLeft - 1

        // Open attention check window at end of focus blocks (last 2 min)
        val needsCheck = current.phase == AttentionPhase.FOCUS
                && newSecondsLeft in 0..120 && !checkWindowOpen

        if (needsCheck) {
            checkWindowOpen     = true
            checkWindowOpenedAt = elapsedTotal
        }

        // Auto-miss if no tap within 2 min of check window
        var checksMissed = current.checksMissed
        if (checkWindowOpen && elapsedTotal - checkWindowOpenedAt > 120) {
            checksMissed++
            checkWindowOpen = false
        }

        // Phase transition
        if (newSecondsLeft <= 0) {
            return when (current.phase) {
                AttentionPhase.FOCUS -> {
                    focusBlocksDone++
                    val nextPhase = if (focusBlocksDone % FOCUS_BLOCKS_BEFORE_LONG == 0)
                        AttentionPhase.LONG_BREAK else AttentionPhase.SHORT_BREAK
                    val nextSecs = if (nextPhase == AttentionPhase.LONG_BREAK) LONG_BREAK_SECS else SHORT_BREAK_SECS
                    val next = current.copy(
                        phase            = nextPhase,
                        phaseSecondsLeft = nextSecs,
                        totalSessionSeconds = elapsedTotal,
                        cycleIndex       = focusBlocksDone + 1,
                        needsAttentionCheck = false,
                        phaseJustChanged = true,
                        checksMissed     = checksMissed
                    )
                    _state.value = next
                    next
                }
                AttentionPhase.SHORT_BREAK, AttentionPhase.LONG_BREAK -> {
                    checkWindowOpen = false
                    val next = current.copy(
                        phase            = AttentionPhase.FOCUS,
                        phaseSecondsLeft = FOCUS_SECS,
                        totalSessionSeconds = elapsedTotal,
                        needsAttentionCheck = false,
                        phaseJustChanged = true,
                        checksMissed     = checksMissed
                    )
                    _state.value = next
                    next
                }
                AttentionPhase.DONE -> current
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
        _state.update { it.copy(checksPassed = it.checksPassed + 1, needsAttentionCheck = false) }
    }

    fun currentState(): CycleState = _state.value

    fun reset() {
        focusBlocksDone   = 0
        checkWindowOpen   = false
        elapsedTotal      = 0
        totalDurationSecs = 0
        _state.value = CycleState()
    }

    private fun MutableStateFlow<CycleState>.update(block: (CycleState) -> CycleState) {
        value = block(value)
    }
}
