package com.wusan.location.service

enum class RuntimePhase { IDLE, ROUTE, MANUAL, PAUSED, COMPLETED, ERROR }

data class RuntimeState(
    val phase: RuntimePhase = RuntimePhase.IDLE,
    val routeName: String = "",
    val error: String? = null,
    val mockEnabled: Boolean = false,
    val serviceRunning: Boolean = false,
)

data class EngineDiagnostics(
    val sampleCount: Long = 0,
    val sampleIntervalMs: Double = Double.NaN,
    val commandDurationMs: Double = Double.NaN,
    val lastSampleNanos: Long = 0,
    val lastError: String? = null,
)
