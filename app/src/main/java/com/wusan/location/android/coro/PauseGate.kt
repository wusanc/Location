package com.wusan.location.android.coro

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update

/** Holds the latest state; repeated pause/resume calls never accumulate commands. */
open class PauseGate(
    private val onResume: () -> Unit = {},
    private val onPause: () -> Unit = {},
    private val canResume: () -> Boolean = { true },
) {
    private data class State(val paused: Boolean = true, val revision: Long = 0)
    private val state = MutableStateFlow(State())

    val isPaused: Boolean get() = state.value.paused

    suspend fun awaitRunning(): Long = state.first { !it.paused }.revision

    fun isCurrent(revision: Long): Boolean = state.value.let {
        !it.paused && it.revision == revision
    }

    fun pause() {
        val previous = state.getAndUpdate { if (it.paused) it else State(true, it.revision + 1) }
        if (!previous.paused) onPause()
    }

    fun resume() {
        if (!canResume()) return
        onResume()
        state.update { if (!it.paused) it else State(false, it.revision + 1) }
    }
}
