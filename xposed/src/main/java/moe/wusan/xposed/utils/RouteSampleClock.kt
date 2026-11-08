package moe.wusan.xposed.utils

object RouteSampleClock {
    data class Time(val nanos: Long, val millis: Long)

    fun resolve(sampleNanos: Long, sampleMillis: Long, receivedNanos: Long, receivedMillis: Long): Time =
        if (sampleNanos in 1L..receivedNanos && sampleMillis > 0) Time(sampleNanos, sampleMillis)
        else Time(receivedNanos, receivedMillis)
}
