package com.wusan.location.android.coro

class CoroutineController(
    onResume: () -> Unit = {},
    onPause: () -> Unit = {},
    canResume: () -> Boolean = { true },
) : PauseGate(onResume, onPause, canResume) {
    suspend fun controlledCoroutine() { awaitRunning() }
}
