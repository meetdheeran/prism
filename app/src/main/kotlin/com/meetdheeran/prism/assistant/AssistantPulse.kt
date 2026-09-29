package com.meetdheeran.prism.assistant

import com.meetdheeran.prism.ui.siri.Phase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * What the assistant is doing right now, wherever it runs (the chat screen or the system session),
 * so the island can show listening / thinking without knowing who is talking.
 */
object AssistantPulse {
    data class State(val phase: Phase = Phase.Idle, val level: Float = 0f, val sessionOpen: Boolean = false)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    fun publish(phase: Phase, level: Float, sessionOpen: Boolean = false) {
        // Mic level changes every frame; only coarse steps are worth waking the island for.
        val l = (level * 10).toInt() / 10f
        val next = State(phase, l, sessionOpen)
        if (next != _state.value) _state.value = next
    }

    fun clear() { _state.value = State() }
}
