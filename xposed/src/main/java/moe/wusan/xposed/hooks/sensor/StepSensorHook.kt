package moe.wusan.xposed.hooks.sensor

import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEventListener
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import moe.wusan.xposed.utils.Logger
import java.lang.reflect.Method
import java.util.IdentityHashMap
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Client-side only. LSPosed controls which app processes load this hook. */
object StepSensorHook {
    private val lock = Any()
    private val registrations = IdentityHashMap<Any, MutableMap<Int, Registration>>()
    private val queueHandlers = WeakHashMap<Any, Handler>()
    private val clock = StepClock()
    private var installed = false
    private lateinit var dispatchMethod: Method
    private var worker: Handler? = null
    private var context: Context? = null
    @Volatile private var enabled = false
    private var cadence = StepSimulationContract.DEFAULT_CADENCE
    private var generation = 0L
    private val counterState = StepCounterState()
    private var lastReadAt = 0L
    private var lastErrorAt = 0L
    private var lastAccessRequestAt = 0L

    private class Registration(val queue: Any, val handle: Int, val sensor: Sensor,
                               val handler: Handler) {
        val deliveryPending = AtomicBoolean(false)
        var counterBaselineDelivered = false
    }

    @Synchronized
    fun install(classLoader: ClassLoader) {
        if (installed) return
        val manager = XposedHelpers.findClass("android.hardware.SystemSensorManager", classLoader)
        val queue = XposedHelpers.findClass("android.hardware.SystemSensorManager\$SensorEventQueue", classLoader)
        dispatchMethod = XposedHelpers.findMethodExact(queue, "dispatchSensorEvent",
            Int::class.javaPrimitiveType, FloatArray::class.java,
            Int::class.javaPrimitiveType, Long::class.javaPrimitiveType)

        XposedBridge.hookMethod(dispatchMethod, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                synchronized(lock) {
                    val registration = registrations[param.thisObject]?.get(param.args[0] as Int) ?: return
                    val values = param.args[1] as FloatArray
                    if (registration.sensor.type == Sensor.TYPE_STEP_COUNTER) {
                        val count = values.firstOrNull()
                        if (count != null && count.isFinite() && count >= 0f) {
                            val mapped = counterState.observePhysical(count, param.args[3] as Long, enabled)
                            if (!enabled) {
                                // Before the first simulation this is the untouched physical value.
                                // Afterwards retain the simulated offset so toggling cannot roll back.
                                values[0] = mapped
                                registration.counterBaselineDelivered = true
                            }
                        }
                    }
                    // No physical event is needed to start simulation. A late hardware baseline
                    // must not replace the baseline already delivered by the synthetic stream.
                    if (enabled) param.result = null
                }
            }
        })

        XposedBridge.hookAllMethods(manager, "registerListenerImpl", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val sensor = param.args.getOrNull(1) as? Sensor ?: return
                if (param.hasThrowable() || param.result != true) {
                    if (sensor.type != Sensor.TYPE_STEP_COUNTER && sensor.type != Sensor.TYPE_STEP_DETECTOR) return
                    val granted = runCatching {
                        val appContext = XposedHelpers.getObjectField(param.thisObject, "mContext") as Context
                        android.os.Build.VERSION.SDK_INT < 29 || appContext.checkSelfPermission(
                            "android.permission.ACTIVITY_RECOGNITION") == android.content.pm.PackageManager.PERMISSION_GRANTED
                    }.getOrNull()
                    val message = "Step sensor registration rejected: type=${sensor.type}, " +
                        "activityRecognitionGranted=$granted, result=${param.result}; no synthetic events registered"
                    if (param.hasThrowable()) Logger.warn(message, param.throwable) else Logger.warn(message)
                    return
                }
                runCatching {
                    val listener = param.args[0] as? SensorEventListener ?: return
                    @Suppress("UNCHECKED_CAST")
                    val queues = XposedHelpers.getObjectField(param.thisObject, "mSensorListeners") as Map<Any, Any>
                    val eventQueue = synchronized(queues) { queues[listener] } ?: return
                    val handler = synchronized(lock) {
                        // Android keeps the first registration's Looper for this listener.
                        queueHandlers.getOrPut(eventQueue) {
                            param.args.filterIsInstance<Handler>().firstOrNull()
                                ?: Handler(XposedHelpers.getObjectField(param.thisObject, "mMainLooper") as Looper)
                        }
                    }
                    if (sensor.type != Sensor.TYPE_STEP_COUNTER && sensor.type != Sensor.TYPE_STEP_DETECTOR) return
                    val handle = XposedHelpers.callMethod(sensor, "getHandle") as Int
                    synchronized(lock) {
                        registrations.getOrPut(eventQueue) { mutableMapOf() }.getOrPut(handle) {
                            Registration(eventQueue, handle, sensor, handler)
                        }
                        context = (XposedHelpers.getObjectField(param.thisObject, "mContext") as Context)
                            .let { it.applicationContext ?: it }
                        if (worker == null) {
                            val thread = HandlerThread("LocationStepSimulation").apply { start() }
                            worker = Handler(thread.looper)
                        }
                        worker!!.removeCallbacks(tick)
                        worker!!.post(tick)
                    }
                }.onFailure { Logger.warn("Step sensor registration could not be tracked", it) }
            }
        })

        XposedBridge.hookAllMethods(manager, "unregisterListenerImpl", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                runCatching {
                    val listener = param.args[0] ?: return
                    @Suppress("UNCHECKED_CAST")
                    val queues = XposedHelpers.getObjectField(param.thisObject, "mSensorListeners") as Map<Any, Any>
                    val queue = synchronized(queues) { queues[listener] } ?: return
                    val sensor = param.args.getOrNull(1) as? Sensor
                    val handle = sensor?.let { XposedHelpers.callMethod(it, "getHandle") as Int }
                    synchronized(lock) {
                        if (handle == null) {
                            registrations.remove(queue)
                            queueHandlers.remove(queue)
                        } else {
                            registrations[queue]?.remove(handle)
                            if (registrations[queue]?.isEmpty() == true) registrations.remove(queue)
                        }
                        if (registrations.isEmpty()) {
                            enabled = false
                            generation++
                            clock.reset()
                            lastReadAt = 0L
                            worker?.removeCallbacks(tick)
                        }
                    }
                }.onFailure { Logger.warn("Step sensor unregister failed", it) }
            }
        })
        installed = true
        Logger.info("Step sensor hook ready; only registered step sensors are handled")
    }

    private val tick = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtimeNanos()
            val appContext = synchronized(lock) {
                if (registrations.isEmpty()) return
                context
            } ?: return

            // Query outside the lock: provider startup / IPC must never block sensor callbacks.
            if (lastReadAt == 0L || now - lastReadAt >= 250_000_000L) {
                val config = runCatching {
                    appContext.contentResolver.query(Uri.parse(StepSimulationContract.URI),
                        arrayOf(StepSimulationContract.ENABLED, StepSimulationContract.CADENCE), null, null, null)?.use {
                        check(it.moveToFirst())
                        (it.getInt(0) != 0) to it.getInt(1)
                    } ?: error("Step configuration provider is unavailable")
                }
                synchronized(lock) {
                    if (registrations.isEmpty()) return
                    val (newEnabled, newCadence) = config.getOrDefault(false to cadence)
                    val validEnabled = newEnabled && newCadence in
                        StepSimulationContract.MIN_CADENCE..StepSimulationContract.MAX_CADENCE
                    if (enabled != validEnabled) generation++
                    enabled = validEnabled
                    cadence = newCadence
                    clock.configure(SystemClock.elapsedRealtimeNanos(), enabled, cadence)
                    lastReadAt = now
                }
                config.exceptionOrNull()?.let {
                    // Explicit broadcasts can reach the module even when package visibility
                    // filters the provider. A read URI grant makes the provider visible.
                    if (lastAccessRequestAt == 0L || now - lastAccessRequestAt >= 5_000_000_000L) {
                        lastAccessRequestAt = now
                        runCatching {
                            appContext.sendBroadcast(Intent().apply {
                                component = ComponentName(StepSimulationContract.MODULE_PACKAGE,
                                    StepSimulationContract.ACCESS_RECEIVER)
                                putExtra(StepSimulationContract.PACKAGE_EXTRA, appContext.packageName)
                            })
                        }.onFailure { error -> Logger.warn("Step configuration access request failed", error) }
                    }
                    if (lastErrorAt == 0L || now - lastErrorAt >= 60_000_000_000L) {
                        lastErrorAt = now
                        Logger.warn("Step configuration unavailable; original sensor events retained", it)
                    }
                }
            }

            val timestamp = SystemClock.elapsedRealtimeNanos()
            synchronized(lock) {
                if (enabled) {
                    val currentRegistrations = registrations.values.flatMap { it.values }
                    // Establish a session baseline even when the handset is motionless and the
                    // hardware counter never sends its first on-change event.
                    val baseline = counterState.baseline()
                    currentRegistrations.filter {
                        it.sensor.type == Sensor.TYPE_STEP_COUNTER && !it.counterBaselineDelivered
                    }.forEach { postEvent(it, baseline, timestamp, generation) }
                    if (clock.poll(timestamp)) {
                        val count = counterState.step()
                        currentRegistrations.forEach { registration ->
                            if (registration.sensor.type == Sensor.TYPE_STEP_COUNTER &&
                                !registration.counterBaselineDelivered) return@forEach
                            val value = if (registration.sensor.type == Sensor.TYPE_STEP_COUNTER) count else 1f
                            postEvent(registration, value, timestamp, generation)
                        }
                    }
                }
                // A concurrent registration may have queued an immediate poll while IPC ran.
                // Keep exactly one future tick rather than creating multiple timer loops.
                worker?.removeCallbacks(this)
                if (registrations.isNotEmpty()) {
                    worker?.postDelayed(this, clock.delayMillis(SystemClock.elapsedRealtimeNanos()).coerceAtMost(250L))
                }
            }
        }
    }

    private fun postEvent(registration: Registration, value: Float, timestamp: Long, version: Long) {
        if (!registration.deliveryPending.compareAndSet(false, true)) return
        val posted = registration.handler.post {
            try {
                val valid = synchronized(lock) {
                    enabled && version == generation &&
                        registrations[registration.queue]?.get(registration.handle) === registration
                }
                // Keep the original listener Looper and discard stale work after a long stall.
                if (!valid || SystemClock.elapsedRealtimeNanos() - timestamp >= 1_000_000_000L) return@post
                val values = FloatArray(16).apply { this[0] = value }
                val delivered = runCatching {
                    XposedBridge.invokeOriginalMethod(dispatchMethod, registration.queue,
                        arrayOf(registration.handle, values, 3, timestamp))
                }.onFailure { Logger.warn("Step event callback failed", it) }.isSuccess
                if (delivered && registration.sensor.type == Sensor.TYPE_STEP_COUNTER) synchronized(lock) {
                    if (registrations[registration.queue]?.get(registration.handle) === registration) {
                        registration.counterBaselineDelivered = true
                    }
                }
            } finally {
                // Do not queue a second baseline while the first callback is still executing.
                registration.deliveryPending.set(false)
            }
        }
        if (!posted) registration.deliveryPending.set(false)
    }
}
