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
import kotlin.math.max

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
    private var cumulativeSteps = 0L
    private var lastReadAt = 0L
    private var lastErrorAt = 0L
    private var lastAccessRequestAt = 0L

    private class Registration(val queue: Any, val handle: Int, val sensor: Sensor,
                               val handler: Handler) {
        val deliveryPending = AtomicBoolean(false)
        var counterBaselineSeen = false
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
                    // Establish a physical baseline before replacing cumulative counter events.
                    val needsBaseline = registration.sensor.type == Sensor.TYPE_STEP_COUNTER &&
                        !registration.counterBaselineSeen
                    if ((!enabled || needsBaseline) && registration.sensor.type == Sensor.TYPE_STEP_COUNTER) {
                        val count = values.firstOrNull()?.toDouble() ?: 0.0
                        if (count.isFinite() && count >= 0.0) {
                            cumulativeSteps = max(cumulativeSteps, count.toLong())
                            registration.counterBaselineSeen = true
                            if (enabled && needsBaseline) values[0] = cumulativeSteps.toFloat()
                        }
                    }
                    // Synthetic delivery invokes the original method directly and bypasses this hook.
                    if (enabled && !needsBaseline) param.result = null
                }
            }
        })

        XposedBridge.hookAllMethods(manager, "registerListenerImpl", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (param.hasThrowable() || param.result != true) return
                runCatching {
                    val listener = param.args[0] as? SensorEventListener ?: return
                    val sensor = param.args[1] as? Sensor ?: return
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
            if (lastReadAt == 0L || now - lastReadAt >= 1_000_000_000L) {
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
                    if (enabled != newEnabled || cadence != newCadence) generation++
                    enabled = newEnabled && newCadence in StepSimulationContract.MIN_CADENCE..StepSimulationContract.MAX_CADENCE
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
                if (clock.poll(timestamp)) {
                    cumulativeSteps++
                    val count = cumulativeSteps.toFloat()
                    val version = generation
                    registrations.values.flatMap { it.values }.forEach { registration ->
                        // A cumulative counter must first deliver its real baseline so consumers
                        // don't interpret a synthetic session count as a negative step delta.
                        if (registration.sensor.type == Sensor.TYPE_STEP_COUNTER &&
                            !registration.counterBaselineSeen) return@forEach
                        if (registration.deliveryPending.compareAndSet(false, true)) {
                            val posted = registration.handler.post {
                                registration.deliveryPending.set(false)
                                val valid = synchronized(lock) {
                                    enabled && version == generation &&
                                        registrations[registration.queue]?.get(registration.handle) === registration
                                }
                                // Do not deliver a burst of stale events to a stalled app Looper.
                                if (valid && SystemClock.elapsedRealtimeNanos() - timestamp < 1_000_000_000L) {
                                    val values = FloatArray(16)
                                    values[0] = if (registration.sensor.type == Sensor.TYPE_STEP_COUNTER) count else 1f
                                    runCatching {
                                        XposedBridge.invokeOriginalMethod(dispatchMethod, registration.queue,
                                            arrayOf(registration.handle, values, 3, timestamp))
                                    }.onFailure { Logger.warn("Step event callback failed", it) }
                                }
                            }
                            if (!posted) registration.deliveryPending.set(false)
                        }
                    }
                }
                // A concurrent registration may have queued an immediate poll while IPC ran.
                // Keep exactly one future tick rather than creating multiple timer loops.
                worker?.removeCallbacks(this)
                if (registrations.isNotEmpty()) {
                    worker?.postDelayed(this, clock.delayMillis(SystemClock.elapsedRealtimeNanos()))
                }
            }
        }
    }
}
