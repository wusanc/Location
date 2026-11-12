package moe.wusan.xposed.hooks.sensor

/** Process-wide counter shared by all listeners; callers serialize access. */
internal class StepCounterState {
    private var physicalCount: Long? = null
    private var physicalTimestamp: Long? = null
    private var logicalCount = 0L
    private var simulationStarted = false

    /** Idempotent first event: a consumer can establish its delta without a hardware event. */
    fun baseline(): Float {
        simulationStarted = true
        return logicalCount.toFloat()
    }

    fun step(): Float {
        simulationStarted = true
        add(1L)
        return logicalCount.toFloat()
    }

    /**
     * Preserve original events before simulation. Once synthetic events have been published,
     * ignore physical changes during simulation and map later physical deltas onto that counter.
     * Timestamps deduplicate one hardware event delivered to multiple listeners.
     */
    fun observePhysical(count: Float, timestampNanos: Long, enabled: Boolean): Float {
        require(count.isFinite() && count >= 0f)
        require(timestampNanos >= 0L)
        val previousTimestamp = physicalTimestamp
        if (previousTimestamp == null || timestampNanos > previousTimestamp) {
            val raw = count.toLong()
            val previousCount = physicalCount
            physicalCount = raw
            physicalTimestamp = timestampNanos
            if (!simulationStarted) {
                logicalCount = raw
            } else if (!enabled && previousCount != null && raw >= previousCount) {
                add(raw - previousCount)
            }
            // A late first value or a hardware reset establishes an anchor, never a jump.
        }
        return if (simulationStarted) logicalCount.toFloat() else count
    }

    private fun add(delta: Long) {
        logicalCount = if (delta > Long.MAX_VALUE - logicalCount) Long.MAX_VALUE else logicalCount + delta
    }
}
