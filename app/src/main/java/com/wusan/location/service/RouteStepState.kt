package com.wusan.location.service

import android.os.SystemClock
import com.wusan.location.Portal

object RouteStepState {
    private val heartbeat = RouteStepHeartbeat()
    private val cadence = RouteCadence()

    @Synchronized
    fun record(speed: Double, seconds: Double) {
        val stride = StepSimulationSettings.read(Portal.appContext).routeStepLengthMeters
        val rate = cadence.advance(speed, stride, seconds)
        heartbeat.record(SystemClock.elapsedRealtimeNanos(), speed, rate)
    }

    @Synchronized
    fun stop() {
        cadence.reset()
        heartbeat.stop()
    }

    fun snapshot(): RouteStepHeartbeat.Snapshot = heartbeat.snapshot(SystemClock.elapsedRealtimeNanos())
}
