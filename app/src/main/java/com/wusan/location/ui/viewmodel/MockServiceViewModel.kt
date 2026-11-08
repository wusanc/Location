package com.wusan.location.ui.viewmodel

import android.app.Activity
import android.content.Context
import android.location.LocationManager
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wusan.location.android.coro.CoroutineController
import com.wusan.location.android.coro.CoroutineRouteMock
import com.wusan.location.android.coro.PauseGate
import com.wusan.location.ext.accuracy
import com.wusan.location.ext.altitude
import com.wusan.location.ext.reportDuration
import com.wusan.location.ext.speed
import com.wusan.location.service.MockServiceHelper
import com.wusan.location.ui.mock.HistoricalLocation
import com.wusan.location.ui.mock.HistoricalRoute
import com.wusan.location.ui.mock.Rocker
import com.wusan.location.ui.mock.RouteStepper
import com.wusan.location.ui.mock.RouteProgress
import com.wusan.location.ui.mock.SpeedRamp
import com.wusan.location.ui.mock.RouteSpeedJitter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.wusan.xposed.utils.FakeLoc

class MockServiceViewModel : ViewModel() {
    lateinit var rocker: Rocker
    private var rockerJob: Job? = null
    private var routeJob: Job? = null
    private val routeProgress = RouteProgress()
    private val routeSpeedJitter = RouteSpeedJitter()
    private val movementMutex = Mutex()

    var isRockerLocked = false
    val rockerCoroutineController = CoroutineController(
        onResume = { pauseRouteForManualControl() },
        onPause = { queueStopMotion() },
    )
    val routeMockCoroutine = CoroutineRouteMock(
        onResume = { rockerCoroutineController.pause() },
        onPause = {
            routeProgress.pause()
            routeSpeedJitter.reset()
            queueStopMotion()
        },
    )

    var locationManager: LocationManager? = null
        set(value) {
            field = value
            if (value != null) MockServiceHelper.tryInitService(value)
        }

    var selectedLocation: HistoricalLocation? = null
    var selectedRoute: HistoricalRoute? = null
        set(value) {
            if (field == value) return
            stopMovement()
            field = value
        }

    fun initRocker(activity: Activity): Rocker {
        // A retained ViewModel owns its jobs; a new owner cancels the previous pair.
        if (activeRockerJob !== rockerJob) activeRockerJob?.cancel()
        if (activeRouteJob !== routeJob) activeRouteJob?.cancel()
        if (!::rocker.isInitialized) rocker = Rocker(activity)
        else rocker.refreshAppearance(activity)

        FakeLoc.speed = activity.speed
        FakeLoc.altitude = activity.altitude
        FakeLoc.accuracy = activity.accuracy
        val context = activity.applicationContext
        if (rockerJob?.isActive != true) {
            rockerJob = viewModelScope.launch {
                movementLoop(rockerCoroutineController, context) { revision, seconds, _, _ ->
                    val lm = locationManager ?: return@movementLoop
                    val speed = FakeLoc.speed
                    if (!speed.isFinite() || speed < 0) return@movementLoop
                    movementMutex.withLock {
                        withContext(Dispatchers.IO) {
                            if (!rockerCoroutineController.isCurrent(revision)) return@withContext
                            val success = if (speed == 0.0) MockServiceHelper.stopMotion(lm)
                                else MockServiceHelper.move(lm, speed * seconds, FakeLoc.bearing)
                            if (!success) Log.e(TAG, "Failed to move rocker")
                        }
                    }
                }
            }
            activeRockerJob = rockerJob
        }
        if (routeJob?.isActive != true) {
            routeJob = viewModelScope.launch {
                movementLoop(routeMockCoroutine, context) { revision, seconds, sampleNanos, sampleMillis ->
                    advanceRoute(revision, seconds, sampleNanos, sampleMillis)
                }
            }
            activeRouteJob = routeJob
        }
        return rocker
    }

    private fun pauseRouteForManualControl() {
        routeMockCoroutine.pause()
        if (::rocker.isInitialized && rocker.autoStatus) rocker.autoStatus = false
    }

    /** Call on the main thread, including when the service stops with its overlay hidden. */
    fun stopMovement() {
        rockerCoroutineController.pause()
        routeMockCoroutine.pause()
        routeProgress.reset()
        routeSpeedJitter.reset()
        queueStopMotion()
        isRockerLocked = false
        if (::rocker.isInitialized) {
            rocker.autoStatus = false
            rocker.resetInput()
        }
    }

    private suspend fun movementLoop(
        gate: PauseGate,
        context: Context,
        tick: suspend (Long, Double, Long, Long) -> Unit,
    ) {
        var previousRevision = -1L
        var previousTick = 0L
        while (currentCoroutineContext().isActive) {
            val revision = gate.awaitRunning()
            if (revision != previousRevision) {
                previousTick = SystemClock.elapsedRealtimeNanos()
                previousRevision = revision
            }
            val interval = context.reportDuration.toLong()
            delay(interval)
            if (!gate.isCurrent(revision)) continue
            val now = SystemClock.elapsedRealtimeNanos()
            // Include normal scheduling/IPC overhead, but never catch up a long background stall.
            val seconds = ((now - previousTick) / 1_000_000_000.0)
                .coerceIn(0.0, interval * 2 / 1000.0)
            previousTick = now
            try {
                tick(revision, seconds, now, System.currentTimeMillis())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Movement paused after service error", e)
                gate.pause()
                if (gate === routeMockCoroutine && ::rocker.isInitialized) rocker.autoStatus = false
            }
        }
    }

    private fun queueStopMotion() {
        viewModelScope.launch {
            movementMutex.withLock {
                // Do not let a delayed stop clear a newly resumed movement.
                if (!rockerCoroutineController.isPaused || !routeMockCoroutine.isPaused) return@withLock
                val lm = locationManager ?: return@withLock
                withContext(Dispatchers.IO) {
                    runCatching { MockServiceHelper.stopMotion(lm) }
                        .onFailure { Log.e(TAG, "Failed to report stopped motion", it) }
                }
            }
        }
    }

    /** Serialize a selected point behind any already accepted route step. */
    suspend fun placeLocation(lat: Double, lon: Double): Boolean = movementMutex.withLock {
        val lm = locationManager ?: return@withLock false
        withContext(Dispatchers.IO) {
            MockServiceHelper.setLocation(lm, lat, lon).also {
                if (it) MockServiceHelper.broadcastLocation(lm)
            }
        }
    }

    private suspend fun advanceRoute(
        revision: Long,
        seconds: Double,
        sampleNanos: Long,
        sampleMillis: Long,
    ) = movementMutex.withLock {
        if (!routeMockCoroutine.isCurrent(revision)) return@withLock
        val lm = locationManager ?: return@withLock
        val route = selectedRoute?.route
        if (route.isNullOrEmpty()) {
            stopMovement()
            return@withLock
        }
        val targetSpeed = FakeLoc.speed
        if (!targetSpeed.isFinite() || targetSpeed < 0) return@withLock
        val generation = routeProgress.generation
        val nextIndex = routeProgress.nextIndex
        // Vary the physical movement target before integrating distance. The same
        // resulting speed goes into the location packet; reporting adds no extra noise.
        val variedTarget = routeSpeedJitter.target(targetSpeed, seconds)
        val ramp = SpeedRamp.advance(routeProgress.speed, variedTarget, seconds)
        val result = withContext(Dispatchers.IO) {
            if (!routeMockCoroutine.isCurrent(revision)) return@withContext null
            if (!MockServiceHelper.isMockStart(lm)) return@withContext null
            val current = if (nextIndex == 0) route.first() else
                MockServiceHelper.getLocation(lm) ?: return@withContext null
            val step = RouteStepper.advance(route, current, if (nextIndex == 0) 1 else nextIndex, ramp.meters)
            if (!routeMockCoroutine.isCurrent(revision)) return@withContext null
            // The acknowledged command commits position, heading and actual speed together.
            if (!MockServiceHelper.routeStep(lm, step.position, ramp.speed, step.bearing, sampleNanos, sampleMillis)) {
                throw IllegalStateException("Failed to update route position; reload the module")
            }
            step
        } ?: return@withLock
        // Pause invalidates timing, not an already accepted position. Only a route reset
        // invalidates progress. Otherwise resuming at a corner can send us backwards.
        val runningSpeed = ramp.speed.takeIf { routeMockCoroutine.isCurrent(revision) }
        if (!routeProgress.commit(generation, result.nextIndex, runningSpeed)) return@withLock
        if (routeProgress.nextIndex >= route.size) {
            routeMockCoroutine.pause()
            routeProgress.reset()
            if (::rocker.isInitialized) rocker.autoStatus = false
        }
    }

    fun isServiceStart(): Boolean {
        val lm = locationManager ?: return false
        return MockServiceHelper.isServiceInit() && MockServiceHelper.isMockStart(lm)
    }

    override fun onCleared() {
        rockerJob?.cancel()
        routeJob?.cancel()
        if (activeRockerJob === rockerJob) activeRockerJob = null
        if (activeRouteJob === routeJob) activeRouteJob = null
        if (::rocker.isInitialized) rocker.dispose()
        val lm = locationManager
        val jobs = listOfNotNull(rockerJob, routeJob)
        if (lm != null) {
            // Finite cleanup: wait for any Binder call still in flight before reporting zero.
            CoroutineScope(Dispatchers.IO).launch {
                jobs.joinAll()
                runCatching { MockServiceHelper.stopMotion(lm) }
                    .onFailure { Log.e(TAG, "Failed to clear motion on exit", it) }
            }
        }
        locationManager = null
        super.onCleared()
    }

    companion object {
        private const val TAG = "MockServiceViewModel"
        private var activeRockerJob: Job? = null
        private var activeRouteJob: Job? = null
    }
}
