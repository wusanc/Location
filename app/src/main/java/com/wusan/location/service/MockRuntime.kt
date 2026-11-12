package com.wusan.location.service

import android.app.Activity
import android.content.Context
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import com.wusan.location.android.coro.CoroutineController
import com.wusan.location.android.coro.CoroutineRouteMock
import com.wusan.location.android.coro.PauseGate
import com.wusan.location.android.widget.RockerView
import com.wusan.location.ext.accuracy
import com.wusan.location.ext.altitude
import com.wusan.location.ext.reportDuration
import com.wusan.location.ext.selectRoute
import com.wusan.location.ext.speed
import com.wusan.location.ui.mock.HistoricalLocation
import com.wusan.location.ui.mock.HistoricalRoute
import com.wusan.location.ui.mock.Rocker
import com.wusan.location.ui.mock.RouteProgress
import com.wusan.location.ui.mock.RouteSpeedJitter
import com.wusan.location.ui.mock.RouteStepper
import com.wusan.location.ui.mock.SpeedRamp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import moe.wusan.xposed.utils.FakeLoc

/** The foreground service owns movement jobs; Activity/ViewModel lifetimes only own UI. */
object MockRuntime {
    private const val TAG = "MockRuntime"
    private var appContext: Context? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val controls = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var movementScope: CoroutineScope? = null
    private var rockerJob: Job? = null
    private var routeJob: Job? = null
    private var wakeJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lifetime = 0L
    private val movementMutex = Mutex()
    private val progress = RouteProgress()
    private val speedJitter = RouteSpeedJitter()
    private val mutableState = MutableStateFlow(RuntimeState())
    val state = mutableState.asStateFlow()
    private val mutableDiagnostics = MutableStateFlow(EngineDiagnostics())
    val diagnostics = mutableDiagnostics.asStateFlow()

    var rockerOrNull: Rocker? = null
        private set
    val rocker: Rocker get() = checkNotNull(rockerOrNull) { "摇杆尚未初始化" }
    var isRockerLocked = false
    var selectedLocation: HistoricalLocation? = null
    var selectedRoute: HistoricalRoute? = null
        set(value) {
            if (field == value) return
            stopMovement()
            field = value?.copy(route = value.route.toList())
            mutableState.value = mutableState.value.copy(routeName = value?.name.orEmpty())
        }

    var locationManager: LocationManager? = null
        set(value) {
            field = value
            if (value != null && !MockServiceHelper.isServiceInit()) MockServiceHelper.tryInitService(value)
        }

    val rockerCoroutineController: CoroutineController = CoroutineController(
        canResume = { canRun(route = false) },
        onResume = {
            routeMockCoroutine.pause()
            rockerOrNull?.syncAutoStatus(false)
            updatePhase(RuntimePhase.MANUAL)
            renewWakeLock()
        },
        onPause = {
            if (state.value.phase == RuntimePhase.MANUAL) updatePhase(RuntimePhase.IDLE)
            queueStopMotion()
            releaseWakeLockIfIdle()
        },
    )
    val routeMockCoroutine: CoroutineRouteMock = CoroutineRouteMock(
        canResume = { canRun(route = true) },
        onResume = {
            rockerCoroutineController.pause()
            rockerOrNull?.syncAutoStatus(true)
            updatePhase(RuntimePhase.ROUTE)
            renewWakeLock()
        },
        onPause = {
            progress.pause()
            RouteStepState.stop()
            speedJitter.reset()
            rockerOrNull?.syncAutoStatus(false)
            if (state.value.phase == RuntimePhase.ROUTE) updatePhase(RuntimePhase.PAUSED)
            queueStopMotion()
            releaseWakeLockIfIdle()
        },
    )

    fun configure(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        FakeLoc.speed = context.speed
        FakeLoc.altitude = context.altitude
        FakeLoc.accuracy = context.accuracy
        locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        selectedRoute = context.selectRoute
        val enabled = runCatching { locationManager?.let(MockServiceHelper::isMockStart) == true }.getOrDefault(false)
        mutableState.value = mutableState.value.copy(mockEnabled = enabled)
    }

    fun initRocker(activity: Activity): Rocker {
        configure(activity.applicationContext)
        val existing = rockerOrNull
        if (existing != null) {
            existing.refreshAppearance(activity)
            return existing
        }
        return Rocker(activity.applicationContext).also { overlay ->
            rockerOrNull = overlay
            overlay.refreshAppearance(activity)
            overlay.setRockerListener(object : RockerView.Companion.OnMoveListener {
                override fun onAngle(angle: Double) { FakeLoc.bearing = angle; FakeLoc.hasBearings = true }
                override fun onLockChanged(isLocked: Boolean) { isRockerLocked = isLocked }
                override fun onFinished() { if (!isRockerLocked) rockerCoroutineController.pause() }
                override fun onStarted() { rockerCoroutineController.resume() }
            })
            overlay.setRockerAutoListener(object : Rocker.Companion.OnAutoListener {
                override fun onAutoPlay(isPlay: Boolean) {
                    if (isPlay) resumeRoute() else routeMockCoroutine.pause()
                    overlay.syncAutoStatus(!routeMockCoroutine.isPaused)
                }
                override fun onAutoLock(isLock: Boolean) = Unit
            })
        }
    }

    /** Called only after Android accepted startForeground. Existing gates retain pending intent. */
    fun attachService(context: Context) {
        configure(context)
        if (movementScope != null) return
        lifetime++
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        movementScope = owner
        mutableState.value = state.value.copy(serviceRunning = true)
        rockerJob = owner.launch {
            movementLoop(rockerCoroutineController) { revision, seconds, sampleNanos, _ ->
                val manager = locationManager ?: error("定位服务未连接")
                val speed = FakeLoc.speed
                require(speed.isFinite() && speed >= 0) { "目标速度无效" }
                movementMutex.withLock {
                    if (!rockerCoroutineController.isCurrent(revision)) return@withLock
                    val started = SystemClock.elapsedRealtimeNanos()
                    val accepted = withContext(Dispatchers.IO) {
                        if (!rockerCoroutineController.isCurrent(revision)) return@withContext false
                        if (speed == 0.0) MockServiceHelper.stopMotion(manager)
                        else MockServiceHelper.move(manager, speed * seconds, FakeLoc.bearing)
                    }
                    if (!accepted && rockerCoroutineController.isCurrent(revision)) error("摇杆位置更新失败")
                    if (accepted) recordSample(sampleNanos, started)
                }
            }
        }
        routeJob = owner.launch { movementLoop(routeMockCoroutine, ::advanceRoute) }
        wakeJob = owner.launch {
            while (isActive) {
                if (moving()) renewWakeLock() else releaseWakeLock()
                delay(15_000L)
            }
        }
    }

    fun detachService() {
        lifetime++
        val endingLifetime = lifetime
        val previousState = state.value
        stopMovement()
        val jobs = listOfNotNull(rockerJob, routeJob)
        movementScope?.cancel()
        movementScope = null
        rockerJob = null
        routeJob = null
        wakeJob = null
        releaseWakeLock()
        rockerOrNull?.hide()
        mutableState.value = state.value.copy(serviceRunning = false,
            phase = if (previousState.phase == RuntimePhase.ERROR) RuntimePhase.ERROR else state.value.phase,
            error = previousState.error)
        controls.launch {
            jobs.joinAll()
            movementMutex.withLock {
                if (lifetime != endingLifetime || movementScope != null || moving()) return@withLock
                val manager = locationManager ?: return@withLock
                withContext(Dispatchers.IO) { runCatching { MockServiceHelper.stopMotion(manager) } }
            }
        }
    }

    fun prepareSession() {
        stopMovement()
        mutableDiagnostics.value = EngineDiagnostics()
        mutableState.value = state.value.copy(error = null)
    }

    fun onMockOpened() = onMain {
        mutableDiagnostics.value = EngineDiagnostics()
        mutableState.value = state.value.copy(mockEnabled = true, error = null)
    }

    fun onMockClosed() = onMain {
        stopMovement()
        rockerOrNull?.hide()
        mutableState.value = state.value.copy(mockEnabled = false)
        appContext?.let(MockRuntimeService::requestStop)
    }

    fun pause() {
        rockerCoroutineController.pause()
        routeMockCoroutine.pause()
        rockerOrNull?.syncAutoStatus(false)
        releaseWakeLock()
    }

    fun resumeRoute() { routeMockCoroutine.resume() }

    fun stopMovement() {
        rockerCoroutineController.pause()
        routeMockCoroutine.pause()
        progress.reset()
        RouteStepState.stop()
        speedJitter.reset()
        isRockerLocked = false
        rockerOrNull?.let { it.syncAutoStatus(false); it.resetInput() }
        updatePhase(RuntimePhase.IDLE)
        releaseWakeLock()
        queueStopMotion()
    }

    /** Notification stop ends the mock session as well as route motion. */
    fun stop() {
        stopMovement()
        rockerOrNull?.hide()
        controls.launch {
            movementMutex.withLock {
                val manager = locationManager ?: return@withLock
                val stopped = withContext(Dispatchers.IO) { runCatching { MockServiceHelper.tryCloseMock(manager) }.getOrDefault(false) }
                if (stopped) mutableState.value = state.value.copy(mockEnabled = false)
                else reportError("停止模拟失败，请在应用内重试")
            }
        }
    }

    fun startFailed(message: String) = onMain {
        pause()
        reportError(message)
    }

    private fun canRun(route: Boolean): Boolean {
        val context = appContext ?: return false
        if (route && selectedRoute?.route?.let(RouteStepper::isValid) != true) {
            startFailed("请先选择有效路线")
            Toast.makeText(context, "请先选择有效路线", Toast.LENGTH_SHORT).show()
            return false
        }
        if (!isServiceStart()) {
            startFailed("请先启动模拟")
            Toast.makeText(context, "请先启动模拟", Toast.LENGTH_SHORT).show()
            return false
        }
        return MockRuntimeService.ensureStarted(context)
    }

    fun isServiceStart(): Boolean = runCatching {
        locationManager?.let { MockServiceHelper.isServiceInit() && MockServiceHelper.isMockStart(it) } == true
    }.getOrDefault(false)

    private fun updatePhase(phase: RuntimePhase) {
        mutableState.value = state.value.copy(phase = phase, routeName = selectedRoute?.name.orEmpty(), error = null)
    }

    private fun reportError(message: String) {
        mutableState.value = state.value.copy(phase = RuntimePhase.ERROR, error = message)
        mutableDiagnostics.value = diagnostics.value.copy(lastError = message)
        Log.e(TAG, message)
    }

    private suspend fun movementLoop(gate: PauseGate, tick: suspend (Long, Double, Long, Long) -> Unit) {
        var previousRevision = -1L
        var previousTick = 0L
        while (currentCoroutineContext().isActive) {
            val revision = gate.awaitRunning()
            if (revision != previousRevision) {
                previousTick = SystemClock.elapsedRealtimeNanos()
                previousRevision = revision
            }
            val interval = checkNotNull(appContext).reportDuration.toLong()
            delay(interval)
            if (!gate.isCurrent(revision)) continue
            val now = SystemClock.elapsedRealtimeNanos()
            val elapsed = (now - previousTick) / 1_000_000_000.0
            previousTick = now
            try {
                // Long stalls are visible pauses, never a distance/clock mismatch or invented catch-up.
                check(elapsed <= maxOf(2.0, interval * 5 / 1000.0)) { "采样中断，已暂停，请检查诊断后继续" }
                tick(revision, elapsed.coerceAtLeast(0.0), now, System.currentTimeMillis())
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                gate.pause()
                rockerOrNull?.syncAutoStatus(false)
                releaseWakeLock()
                reportError(error.message ?: "位置模拟已暂停")
            }
        }
    }

    private fun queueStopMotion() {
        controls.launch {
            movementMutex.withLock {
                if (moving()) return@withLock
                val manager = locationManager ?: return@withLock
                withContext(Dispatchers.IO) {
                    runCatching { MockServiceHelper.stopMotion(manager) }
                        .onFailure { Log.e(TAG, "Failed to report stopped motion", it) }
                }
            }
        }
    }

    suspend fun placeLocation(lat: Double, lon: Double): Boolean = movementMutex.withLock {
        val manager = locationManager ?: return@withLock false
        withContext(Dispatchers.IO) {
            MockServiceHelper.setLocation(manager, lat, lon).also { if (it) MockServiceHelper.broadcastLocation(manager) }
        }
    }

    private suspend fun advanceRoute(revision: Long, seconds: Double, sampleNanos: Long, sampleMillis: Long) = movementMutex.withLock {
        if (!routeMockCoroutine.isCurrent(revision)) return@withLock
        val manager = locationManager ?: error("定位服务未连接")
        val route = selectedRoute?.route ?: error("请选择路线")
        require(RouteStepper.isValid(route)) { "路线坐标无效" }
        val targetSpeed = FakeLoc.speed
        require(targetSpeed.isFinite() && targetSpeed >= 0) { "目标速度无效" }
        val generation = progress.generation
        val nextIndex = progress.nextIndex
        val ramp = SpeedRamp.advance(progress.speed, speedJitter.target(targetSpeed, seconds), seconds)
        val started = SystemClock.elapsedRealtimeNanos()
        val result = withContext(Dispatchers.IO) {
            if (!routeMockCoroutine.isCurrent(revision)) return@withContext null
            check(MockServiceHelper.isMockStart(manager)) { "系统模拟已停止" }
            val current = if (nextIndex == 0) route.first() else MockServiceHelper.getLocation(manager)
                ?: error("无法读取当前模拟位置")
            val step = RouteStepper.advance(route, current, if (nextIndex == 0) 1 else nextIndex, ramp.meters)
            if (!routeMockCoroutine.isCurrent(revision)) return@withContext null
            check(MockServiceHelper.routeStep(manager, step.position, ramp.speed, step.bearing, sampleNanos, sampleMillis)) {
                "路线位置更新失败，请检查模块状态"
            }
            step
        } ?: return@withLock
        recordSample(sampleNanos, started)
        val runningSpeed = ramp.speed.takeIf { routeMockCoroutine.isCurrent(revision) }
        if (!progress.commit(generation, result.nextIndex, runningSpeed)) return@withLock
        if (runningSpeed != null) RouteStepState.record(runningSpeed, seconds)
        if (progress.nextIndex >= route.size) {
            routeMockCoroutine.pause()
            progress.reset()
            updatePhase(RuntimePhase.COMPLETED)
        }
    }

    private fun recordSample(sampleNanos: Long, commandStarted: Long) {
        val previous = diagnostics.value
        mutableDiagnostics.value = previous.copy(
            sampleCount = previous.sampleCount + 1,
            sampleIntervalMs = if (previous.lastSampleNanos > 0) (sampleNanos - previous.lastSampleNanos) / 1_000_000.0 else Double.NaN,
            commandDurationMs = (SystemClock.elapsedRealtimeNanos() - commandStarted) / 1_000_000.0,
            lastSampleNanos = sampleNanos,
        )
    }

    private fun moving() = !rockerCoroutineController.isPaused || !routeMockCoroutine.isPaused

    private fun releaseWakeLockIfIdle() {
        if (state.value.phase != RuntimePhase.ROUTE && state.value.phase != RuntimePhase.MANUAL) releaseWakeLock()
    }

    private fun renewWakeLock() {
        if (movementScope == null) return
        runCatching {
            val context = checkNotNull(appContext)
            val lock = wakeLock ?: (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "location:route_runtime").also {
                    it.setReferenceCounted(false)
                    wakeLock = it
                }
            lock.acquire(60_000L)
        }.onFailure { mutableDiagnostics.value = diagnostics.value.copy(lastError = "无法保持后台运行：${it.message}") }
    }

    private fun releaseWakeLock() { runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() } }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }
}
