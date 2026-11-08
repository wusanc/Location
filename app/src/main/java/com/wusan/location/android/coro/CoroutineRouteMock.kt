package com.wusan.location.android.coro

class CoroutineRouteMock(
    onResume: () -> Unit = {},
    onPause: () -> Unit = {},
) : PauseGate(onResume, onPause) {
    suspend fun routeMockCoroutine() { awaitRunning() }
}
