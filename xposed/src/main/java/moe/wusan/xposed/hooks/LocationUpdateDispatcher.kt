package moe.wusan.xposed.hooks

import android.location.Location
import android.os.Binder
import android.os.DeadObjectException
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.IInterface
import android.os.SystemClock
import de.robv.android.xposed.XposedBridge
import moe.wusan.xposed.utils.FakeLoc
import moe.wusan.xposed.utils.Logger
import moe.wusan.xposed.utils.LocationDeliveryDiagnostics
import moe.wusan.xposed.utils.RouteLocationHistory
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean

/** Keeps callback IPC off the simulation command thread and retains each listener's own cursor. */
internal class LocationUpdateDispatcher(
    private val currentLocation: (String) -> Location,
    private val sampleLocation: (String, RouteLocationHistory.Sample) -> Location,
) {
    private data class Key(val provider: String, val binder: IBinder)
    private class Registration(val key: Key, val listener: IInterface, val ownerUid: Int, var cursor: Long) {
        lateinit var deathRecipient: IBinder.DeathRecipient
        var methodsResolved = false
        var batchMethod: Method? = null
        var singleMethod: Method? = null
        var lastFailureLogMillis = 0L
        var lastSampleNanos = 0L
    }

    private val lock = Any()
    private val registrations = LinkedHashMap<Key, Registration>()
    private val worker = Handler(HandlerThread("RouteLocationDelivery").apply { start() }.looper)
    private val dirty = AtomicBoolean()
    private val scheduled = AtomicBoolean()
    private var lastWorkerFailureLogMillis = 0L
    private var summarySinceMillis = SystemClock.elapsedRealtime()
    private var deliveredSamples = 0L
    private var maxSampleGapNanos = 0L
    private var maxDeliveryDelayNanos = 0L

    val listeners: List<Pair<String, IInterface>>
        get() = synchronized(lock) { registrations.values.map { it.key.provider to it.listener } }

    fun diagnostics(): LocationDeliveryDiagnostics.Snapshot {
        val cursors = synchronized(lock) { registrations.values.map { it.cursor } }
        val backlog = if (FakeLoc.enable && FakeLoc.motion.route) {
            cursors.distinct().maxOfOrNull { RouteLocationHistory.countAfter(it) } ?: 0
        } else 0
        return LocationDeliveryDiagnostics.snapshot(cursors.size, backlog)
    }

    fun add(provider: String, listener: IInterface) {
        val binder = listener.asBinder()
        val key = Key(provider, binder)
        val registration = Registration(key, listener, Binder.getCallingUid(), RouteLocationHistory.latest()?.atNanos?.minus(1L) ?: (SystemClock.elapsedRealtimeNanos() - 1L))
        registration.deathRecipient = object : IBinder.DeathRecipient {
            override fun binderDied() = remove(binder)
            override fun binderDied(who: IBinder) = remove(who)
        }
        synchronized(lock) {
            if (registrations.containsKey(key)) return
            registrations[key] = registration
        }
        try {
            binder.linkToDeath(registration.deathRecipient, 0)
            // Removal may race with linkToDeath; do not leave an orphaned death recipient behind.
            if (!isCurrent(registration)) unlink(registration)
        } catch (failure: Throwable) {
            removeRegistration(registration)
            if (!isDead(failure)) logFailure(registration, failure)
            return
        }
        request()
    }

    fun remove(binder: IBinder) {
        val removed = synchronized(lock) {
            val entries = registrations.values.filter { it.key.binder == binder }
            entries.forEach { registrations.remove(it.key) }
            entries
        }
        removed.forEach(::unlink)
    }

    fun removeProvider(provider: String, ownerUid: Int) {
        val removed = synchronized(lock) {
            val entries = registrations.values.filter {
                it.key.provider == provider && it.ownerUid == ownerUid
            }
            entries.forEach { registrations.remove(it.key) }
            entries
        }
        removed.forEach(::unlink)
    }

    fun request() {
        dirty.set(true)
        schedule()
    }

    private fun schedule() {
        if (!scheduled.compareAndSet(false, true)) return
        if (!worker.post {
            try {
                dirty.set(false)
                dispatch()
            } catch (failure: Throwable) {
                LocationDeliveryDiagnostics.recordFailure("定位分发线程异常", failure)
                val now = SystemClock.elapsedRealtime()
                if (lastWorkerFailureLogMillis == 0L || now - lastWorkerFailureLogMillis >= 5_000L) {
                    lastWorkerFailureLogMillis = now
                    Logger.warn("Location delivery worker failed", failure)
                }
            } finally {
                runCatching { logSummary() }
                scheduled.set(false)
                if (dirty.get()) schedule()
            }
        }) {
            scheduled.set(false)
            LocationDeliveryDiagnostics.recordFailure("定位分发线程无法接受任务")
        }
    }

    private fun dispatch() {
        if (!FakeLoc.enable) return
        val snapshot = synchronized(lock) { registrations.values.toList() }
        for (registration in snapshot) {
            if (!FakeLoc.enable) return
            if (!isCurrent(registration)) continue
            try {
                dispatchRegistration(registration)
            } catch (failure: Throwable) {
                if (isDead(failure)) {
                    LocationDeliveryDiagnostics.recordFailure("定位监听已断开", failure)
                    remove(registration.key.binder)
                } else logFailure(registration, failure)
            }
        }
    }

    private fun dispatchRegistration(registration: Registration) {
        val diagnosticSession = LocationDeliveryDiagnostics.generation()
        resolveMethods(registration)
        val generation = RouteLocationHistory.generation()
        val route = FakeLoc.motion.route
        val provider = if (registration.key.provider == "GnssBatch") "gps" else registration.key.provider
        if (!canDeliver(registration, generation, route)) return
        if (route) {
            val cursor = synchronized(lock) { registration.cursor }
            val samples = RouteLocationHistory.after(cursor).take(MAX_BATCH_SIZE)
            if (!canDeliver(registration, generation, route) || samples.isEmpty()) return
            val locations = ArrayList<Location>(samples.size)
            for (sample in samples) {
                if (!canDeliver(registration, generation, route)) return
                locations.add(sampleLocation(provider, sample))
            }
            if (registration.batchMethod != null) {
                if (!canDeliver(registration, generation, route)) return
                invokeBatch(registration, locations)
                recordDelivery(registration, locations, diagnosticSession)
                if (!canDeliver(registration, generation, route)) return
                commit(registration, samples.last().atNanos)
            } else {
                for (index in samples.indices) {
                    if (!canDeliver(registration, generation, route)) return
                    invokeSingle(registration, locations[index])
                    recordDelivery(registration, listOf(locations[index]), diagnosticSession)
                    if (!canDeliver(registration, generation, route)) return
                    commit(registration, samples[index].atNanos)
                }
            }
            val delivered = synchronized(lock) { registration.cursor }
            if (canDeliver(registration, generation, route) &&
                (RouteLocationHistory.latest()?.atNanos ?: 0L) > delivered
            ) request()
        } else {
            if (!canDeliver(registration, generation, route)) return
            val location = currentLocation(provider)
            if (!canDeliver(registration, generation, route)) return
            if (registration.batchMethod != null) invokeBatch(registration, listOf(location))
            else invokeSingle(registration, location)
            recordDelivery(registration, listOf(location), diagnosticSession)
        }
    }

    private fun canDeliver(registration: Registration, generation: Long, route: Boolean): Boolean {
        if (!FakeLoc.enable || !isCurrent(registration)) return false
        if (RouteLocationHistory.generation() != generation || FakeLoc.motion.route != route) {
            request()
            return false
        }
        return true
    }

    private fun resolveMethods(registration: Registration) {
        if (registration.methodsResolved) return
        val listenerClass: Class<*> = registration.listener.javaClass
        val methods = listenerClass.methods.toList() +
            generateSequence(listenerClass) { it.superclass }
                .flatMap { it.declaredMethods.asSequence() }.toList()
        registration.batchMethod = methods.firstOrNull {
            it.name == "onLocationChanged" && it.parameterTypes.size == 2 &&
                List::class.java.isAssignableFrom(it.parameterTypes[0]) &&
                it.parameterTypes[1].name == "android.os.IRemoteCallback"
        }?.apply { isAccessible = true }
        registration.singleMethod = methods.firstOrNull {
            it.name == "onLocationChanged" && it.parameterTypes.size == 1 &&
                it.parameterTypes[0] == Location::class.java
        }?.apply { isAccessible = true }
        registration.methodsResolved = true
        if (registration.batchMethod == null && registration.singleMethod == null) {
            throw NoSuchMethodException("No supported onLocationChanged callback")
        }
    }

    private fun invokeBatch(registration: Registration, locations: List<Location>) {
        XposedBridge.invokeOriginalMethod(
            registration.batchMethod ?: throw NoSuchMethodException("Missing batch callback"),
            registration.listener, arrayOf(locations, null),
        )
    }

    private fun invokeSingle(registration: Registration, location: Location) {
        XposedBridge.invokeOriginalMethod(
            registration.singleMethod ?: throw NoSuchMethodException("Missing single callback"),
            registration.listener, arrayOf(location),
        )
    }

    private fun commit(registration: Registration, atNanos: Long) = synchronized(lock) {
        if (registrations[registration.key] === registration) {
            registration.cursor = maxOf(registration.cursor, atNanos)
        }
    }

    private fun isCurrent(registration: Registration): Boolean = synchronized(lock) {
        registrations[registration.key] === registration
    }

    private fun removeRegistration(registration: Registration) {
        val removed = synchronized(lock) {
            if (registrations[registration.key] !== registration) false
            else {
                registrations.remove(registration.key)
                true
            }
        }
        if (removed) unlink(registration)
    }

    private fun unlink(registration: Registration) {
        LocationDeliveryDiagnostics.forgetListener(registration)
        runCatching { registration.key.binder.unlinkToDeath(registration.deathRecipient, 0) }
    }

    private fun logFailure(registration: Registration, failure: Throwable) {
        LocationDeliveryDiagnostics.recordFailure("${registration.key.provider} 定位投递失败", failure)
        val now = SystemClock.elapsedRealtime()
        synchronized(registration) {
            if (registration.lastFailureLogMillis != 0L && now - registration.lastFailureLogMillis < 5_000L) return
            registration.lastFailureLogMillis = now
        }
        Logger.warn("Location callback failed for ${registration.key.provider}", failure)
    }

    private fun recordDelivery(registration: Registration, locations: List<Location>, session: Long) {
        val nowNanos = SystemClock.elapsedRealtimeNanos()
        LocationDeliveryDiagnostics.recordDelivery(
            session, registration, locations.map { it.elapsedRealtimeNanos }, nowNanos,
        )
        for (location in locations) {
            val atNanos = location.elapsedRealtimeNanos
            if (registration.lastSampleNanos > 0L && atNanos > registration.lastSampleNanos) {
                maxSampleGapNanos = maxOf(maxSampleGapNanos, atNanos - registration.lastSampleNanos)
            }
            registration.lastSampleNanos = maxOf(registration.lastSampleNanos, atNanos)
            maxDeliveryDelayNanos = maxOf(maxDeliveryDelayNanos, (nowNanos - atNanos).coerceAtLeast(0L))
            deliveredSamples++
        }
    }

    private fun logSummary() {
        val nowMillis = SystemClock.elapsedRealtime()
        if (nowMillis - summarySinceMillis < 5_000L) return
        if (FakeLoc.enableDebugLog) {
            val count = synchronized(lock) { registrations.size }
            Logger.debug(
                "Module location callback delivery (not target App storage): listeners=$count, " +
                    "samples=$deliveredSamples, maxSampleGapMs=${maxSampleGapNanos / 1_000_000L}, " +
                    "maxDeliveryDelayMs=${maxDeliveryDelayNanos / 1_000_000L}",
            )
        }
        summarySinceMillis = nowMillis
        deliveredSamples = 0L
        maxSampleGapNanos = 0L
        maxDeliveryDelayNanos = 0L
    }

    private fun isDead(failure: Throwable): Boolean {
        var current: Throwable? = failure
        repeat(16) {
            if (current is DeadObjectException) return true
            current = current?.cause ?: return false
        }
        return false
    }

    private companion object {
        const val MAX_BATCH_SIZE = 128
    }
}




