package com.wusan.location.ui.mock

/** Main-thread state: an acknowledged step survives Pause, but cannot overwrite Reset. */
class RouteProgress {
    var generation = 0L
        private set
    var nextIndex = 0
        private set
    var speed = 0.0
        private set

    fun pause() { speed = 0.0 }

    fun reset() {
        generation++
        nextIndex = 0
        speed = 0.0
    }

    fun commit(generation: Long, nextIndex: Int, runningSpeed: Double?): Boolean {
        if (generation != this.generation) return false
        this.nextIndex = nextIndex
        if (runningSpeed != null) speed = runningSpeed
        return true
    }
}
