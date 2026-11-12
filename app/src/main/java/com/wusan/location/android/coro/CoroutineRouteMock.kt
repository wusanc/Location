package com.wusan.location.android.coro

class CoroutineRouteMock(
    onResume: () -> Unit = {},
    onPause: () -> Unit = {},
    canResume: () -> Boolean = { true },
) : PauseGate(onResume, onPause, canResume) {
    suspend fun routeMockCoroutine() { awaitRunning() }
}
