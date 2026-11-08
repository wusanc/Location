package com.wusan.location.android.coro

class CoroutineController(
    onResume: () -> Unit = {},
    onPause: () -> Unit = {},
) : PauseGate(onResume, onPause) {
    suspend fun controlledCoroutine() { awaitRunning() }
}
