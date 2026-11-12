package moe.wusan.xposed

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.os.SystemClock
import moe.wusan.dobby.Dobby
import moe.wusan.xposed.hooks.LocationServiceHook
import moe.wusan.xposed.utils.FakeLoc
import moe.wusan.xposed.utils.RouteSampleClock
import moe.wusan.xposed.utils.RoutePositionJitter
import moe.wusan.xposed.utils.RouteLocationHistory
import moe.wusan.xposed.utils.BinderUtils
import moe.wusan.xposed.utils.Logger
import moe.wusan.xposed.utils.LocationDeliveryDiagnostics
import java.util.Collections
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

object RemoteCommandHandler {
    private val proxyBinders by lazy { Collections.synchronizedList(arrayListOf<IBinder>()) }
    private val needProxyCmd = arrayOf("start", "stop", "set_speed_amp", "set_altitude", "set_speed", "update_location", "set_bearing", "move", "put_config", "route_step", "stop_motion")
    internal val randomKey by lazy { "portal_" + Random.nextDouble() }
    private var isLoadedLibrary = false
    private val lastMovementLogNanos = AtomicLong()

    @SuppressLint("UnsafeDynamicallyLoadedCode")
    fun handleInstruction(command: String, rely: Bundle): Boolean {
        // Exchange key -> returns a random key -> is used to verify that it is the PortalManager
        if (command == "exchange_key") {
            val userId = BinderUtils.getCallerUid()
            if (BinderUtils.isLocationProviderEnabled(userId)) {
                rely.putString("key", randomKey)
                return true
            }
            // Go back and see if the instruction has been processed to prevent it from being detected by others
        } else if (command != randomKey) {
            return false
        }
        val commandId = rely.getString("command_id") ?: return false

        kotlin.runCatching {
            if (proxyBinders.isNotEmpty() && needProxyCmd.any { it == commandId }) {
                proxyBinders.removeIf {
                    if (it.isBinderAlive && it.pingBinder()) {
                        val data = Parcel.obtain()
                        data.writeBundle(rely)
                        it.transact(1, data, null, 0)
                        data.recycle()
                        false
                    } else true
                }
            }
        }.onFailure {
            Logger.error("Failed to transact with proxyBinder", it)
        }

        if (FakeLoc.enableDebugLog) {
            val frequent = commandId in arrayOf("route_step", "move", "get_location", "is_start", "broadcast_location", "get_delivery_diagnostics")
            val now = SystemClock.elapsedRealtimeNanos()
            val previous = lastMovementLogNanos.get()
            if (!frequent || (now - previous >= 1_000_000_000L &&
                    lastMovementLogNanos.compareAndSet(previous, now)))
                Logger.debug("commandId=$commandId, rely=$rely")
        }

        when (commandId) {
            "set_proxy" -> {
                Logger.info("SubProxyBinder: ${rely.getBinder("proxy")} from ${BinderUtils.getUidPackageNames()}!")
                rely.getBinder("proxy")?.let {
                    proxyBinders.add(it)
                }
                return true
            }
            "start" -> {
                val speed = rely.getDouble("speed", FakeLoc.speed)
                val altitude = rely.getDouble("altitude", FakeLoc.altitude)
                val accuracy = rely.getFloat("accuracy", FakeLoc.accuracy)

                synchronized(FakeLoc) {
                    RouteLocationHistory.clear()
                    LocationDeliveryDiagnostics.reset()
                    FakeLoc.motion = FakeLoc.Motion()
                    FakeLoc.enable = true
                }
                if (isLoadedLibrary) {
                    Dobby.setStatus(true)
                }

                FakeLoc.speed = speed
                FakeLoc.altitude = altitude
                FakeLoc.accuracy = accuracy

                return true
            }
            "stop" -> {
                synchronized(FakeLoc) {
                    FakeLoc.enable = false
                    FakeLoc.motion = FakeLoc.Motion()
                    RouteLocationHistory.clear()
                }
                FakeLoc.hasBearings = false
                if (isLoadedLibrary) {
                    Dobby.setStatus(false)
                }
                return true
            }
            "is_start" -> {
                rely.putBoolean("is_start", FakeLoc.enable)
                return true
            }
            "start_gnss_mock" -> {
                FakeLoc.enableMockGnss = true
                return true
            }
            "stop_gnss_mock" -> {
                FakeLoc.enableMockGnss = false
                return true
            }
            "is_gnss_start" -> {
                rely.putBoolean("is_gnss_start", FakeLoc.enableMockGnss)
                return true
            }
            "is_wifi_mock_start" -> {
                rely.putBoolean("is_wifi_mock_start", FakeLoc.enableMockWifi)
                return true
            }
            "start_wifi_mock" -> {
                FakeLoc.enableMockWifi = true
                return true
            }
            "stop_wifi_mock" -> {
                FakeLoc.enableMockWifi = false
                return true
            }
            "get_location" -> {
                synchronized(FakeLoc) {
                    rely.putDouble("lat", FakeLoc.latitude)
                    rely.putDouble("lon", FakeLoc.longitude)
                }
                return true
            }
            "get_delivery_diagnostics" -> {
                val diagnostics = LocationServiceHook.deliveryDiagnostics()
                rely.putInt("diag_version", 1)
                rely.putLong("diag_delivered_points", diagnostics.deliveredPoints)
                rely.putInt("diag_listener_count", diagnostics.listenerCount)
                rely.putLong("diag_max_sample_gap_ms", diagnostics.maxSampleGapMs)
                rely.putLong("diag_max_delivery_delay_ms", diagnostics.maxDeliveryDelayMs)
                rely.putInt("diag_pending_points", diagnostics.pendingPoints)
                rely.putString("diag_last_error", diagnostics.lastError)
                rely.putLong("diag_last_error_at_millis", diagnostics.lastErrorAtMillis)
                rely.putLong("diag_since_elapsed_ms", diagnostics.sinceElapsedMs)
                rely.putLong("diag_captured_elapsed_ms", diagnostics.capturedElapsedMs)
                return true
            }
            "get_listener_size" -> {
                rely.putInt("size", LocationServiceHook.locationListeners.size)
                return true
            }
            "get_speed" -> {
                rely.putDouble("speed", FakeLoc.speed)
                return true
            }
            "get_bearing" -> {
                rely.putDouble("bearing", FakeLoc.bearing)
                return true
            }
            "get_altitude" -> {
                rely.putDouble("altitude", FakeLoc.altitude)
                return true
            }
            "set_speed_amp" -> {
                val speedAmplitude = rely.getDouble("speed_amplitude", 1.0)
                FakeLoc.speedAmplitude = speedAmplitude
                return true
            }
            "set_altitude" -> {
                val altitude = rely.getDouble("altitude", 0.0)
                FakeLoc.altitude = altitude
                return true
            }
            "set_speed" -> {
                val speed = rely.getDouble("speed", 0.0)
                FakeLoc.speed = speed
                return true
            }
            "set_bearing" -> {
                val bearing = rely.getDouble("bearing", 0.0)
                FakeLoc.bearing = bearing
                FakeLoc.hasBearings = true
                return true
            }
            "move" -> {
                val distance = rely.getDouble("n", 0.0)
                if (!FakeLoc.enable || !distance.isFinite() || distance < 0) return false
                if (distance == 0.0) {
                    FakeLoc.motion = FakeLoc.motion.copy(speed = 0.0)
                    return true
                }
                val bearing = rely.getDouble("bearing", 0.0)
                val newLoc = FakeLoc.moveLocation(
                    n = distance,
                    angle = bearing
                )
                if (FakeLoc.enableDebugLog) {
                    Logger.debug("move: distance=$distance, bearing=$bearing, newLoc=$newLoc")
                }
                FakeLoc.bearing = bearing
                FakeLoc.hasBearings = true
                return updateCoordinate(newLoc.first, newLoc.second,
                    FakeLoc.speed.takeIf { it.isFinite() && it >= 0 } ?: 0.0).also {
                    if (FakeLoc.isSystemServerProcess) LocationServiceHook.callOnLocationChanged()
                }
            }
            "route_step" -> {
                if (!FakeLoc.enable) return false
                val lat = rely.getDouble("lat", Double.NaN)
                val lon = rely.getDouble("lon", Double.NaN)
                val speed = rely.getDouble("actual_speed", Double.NaN)
                val bearing = if (rely.containsKey("bearing")) rely.getDouble("bearing") else null
                if (lat !in -90.0..90.0 || lon !in -180.0..180.0 ||
                    !speed.isFinite() || speed < 0 || bearing?.isFinite() == false) return false
                val sampleTime = RouteSampleClock.resolve(
                    rely.getLong("sample_nanos"), rely.getLong("sample_millis"),
                    SystemClock.elapsedRealtimeNanos(), System.currentTimeMillis(),
                )
                val updated = synchronized(FakeLoc) {
                    bearing?.let {
                        FakeLoc.bearing = it
                        FakeLoc.hasBearings = true
                    }
                    updateCoordinate(lat, lon, speed, route = true,
                        atNanos = sampleTime.nanos, atMillis = sampleTime.millis)
                }
                if (!updated) return false
                if (FakeLoc.isSystemServerProcess) LocationServiceHook.callOnLocationChanged()
                return true
            }
            "stop_motion" -> {
                synchronized(FakeLoc) {
                    FakeLoc.motion = FakeLoc.motion.copy(
                        speed = 0.0,
                        atNanos = SystemClock.elapsedRealtimeNanos(),
                        atMillis = System.currentTimeMillis(),
                    )
                    recordRouteSample()
                }
                if (FakeLoc.enable && FakeLoc.isSystemServerProcess) LocationServiceHook.callOnLocationChanged()
                return true
            }
            "update_location" -> {
                val mode = rely.getString("mode")
                var newLat = rely.getDouble("lat", 0.0)
                var newLon = rely.getDouble("lon", 0.0)
                when(mode) {
                    "+" -> {
                        newLat += FakeLoc.latitude
                        newLon += FakeLoc.longitude
                        return updateCoordinate(newLat, newLon)
                    }
                    "-" -> {
                        newLat = FakeLoc.latitude - newLat
                        newLon = FakeLoc.longitude - newLon
                        return updateCoordinate(newLat, newLon)
                    }
                    "*" -> {
                        newLat *= FakeLoc.latitude
                        newLon *= FakeLoc.longitude
                        return updateCoordinate(newLat, newLon)
                    }
                    "/" -> {
                        if (newLat == 0.0 || newLon == 0.0) {
                            return false
                        }
                        newLat /= FakeLoc.latitude
                        newLon /= FakeLoc.longitude
                        return updateCoordinate(newLat, newLon)
                    }
                    "=" -> {
                        return updateCoordinate(newLat, newLon)
                    }
                    "random" -> {
                        return updateCoordinate(Random.nextDouble(-90.0, 90.0), Random.nextDouble(-180.0, 180.0))
                    }
                }
                return true
            }
            "put_config" -> {
                val enable = rely.getBoolean("enable", FakeLoc.enable)
                val speed = rely.getDouble("speed", FakeLoc.speed)
                val altitude = rely.getDouble("altitude", FakeLoc.altitude)
                val accuracy = rely.getFloat("accuracy", FakeLoc.accuracy)
                val enableDebugLog = rely.getBoolean("enable_debug_log", FakeLoc.enableDebugLog)
                val disableGetCurrentLocation = rely.getBoolean("disable_get_current_location", FakeLoc.disableGetCurrentLocation)
                val disableRegisterLocationListener = rely.getBoolean("disable_register_location_listener", FakeLoc.disableRegisterLocationListener)
                val disableFusedLocation = rely.getBoolean("disable_fused_location", FakeLoc.disableFusedLocation)
                val needDowngradeToCdma = rely.getBoolean("need_downgrade_to_2g", FakeLoc.needDowngradeToCdma)
                var minSatellites = rely.getInt("min_satellites", 12)
                if (minSatellites < 0) {
                    minSatellites = 12
                }

                val enableAGPS = rely.getBoolean("enable_agps", FakeLoc.enableAGPS)
                val enableNMEA = rely.getBoolean("enable_nmea", FakeLoc.enableNMEA)
                val disableRequestGeofence = rely.getBoolean("disable_request_geofence", FakeLoc.disableRequestGeofence)
                val disableGetFromLocation = rely.getBoolean("disable_get_from_location", FakeLoc.disableGetFromLocation)

                FakeLoc.enable = enable
                FakeLoc.speed = speed
                FakeLoc.altitude = altitude
                FakeLoc.accuracy = accuracy
                FakeLoc.enableDebugLog = enableDebugLog
                FakeLoc.disableGetCurrentLocation = disableGetCurrentLocation
                FakeLoc.disableRegisterLocationListener = disableRegisterLocationListener
                FakeLoc.disableFusedLocation = disableFusedLocation
                FakeLoc.needDowngradeToCdma = needDowngradeToCdma
                FakeLoc.minSatellites = minSatellites
                FakeLoc.enableAGPS = enableAGPS
                FakeLoc.enableNMEA = enableNMEA
                FakeLoc.disableRequestGeofence = disableRequestGeofence
                FakeLoc.disableGetFromLocation = disableGetFromLocation
                return true
            }
            "sync_config" -> {
                rely.putBoolean("enable", FakeLoc.enable)
                rely.putDouble("latitude", FakeLoc.latitude)
                rely.putDouble("longitude", FakeLoc.longitude)
                rely.putDouble("altitude", FakeLoc.altitude)
                rely.putDouble("speed", FakeLoc.speed)
                rely.putDouble("speed_amplitude", FakeLoc.speedAmplitude)
                val motion = FakeLoc.motion
                rely.putDouble("actual_speed", motion.speed)
                rely.putLong("motion_at_nanos", motion.atNanos)
                rely.putLong("motion_at_millis", motion.atMillis)
                rely.putLong("position_clock_nanos", motion.positionClockNanos)
                rely.putBoolean("route_motion", motion.route)
                rely.putBoolean("has_bearings", FakeLoc.hasBearings)
                rely.putDouble("bearing", FakeLoc.bearing)
                rely.putParcelable("last_location", FakeLoc.lastLocation)
                rely.putBoolean("enable_log", FakeLoc.enableLog)
                rely.putBoolean("enable_debug_log", FakeLoc.enableDebugLog)
                rely.putBoolean("disable_get_current_location", FakeLoc.disableGetCurrentLocation)
                rely.putBoolean("disable_register_location_listener", FakeLoc.disableRegisterLocationListener)
                rely.putBoolean("disable_fused_location", FakeLoc.disableFusedLocation)
                rely.putBoolean("enable_agps", FakeLoc.enableAGPS)
                rely.putBoolean("enable_nmea", FakeLoc.enableNMEA)
                rely.putBoolean("hide_mock", FakeLoc.hideMock)
                rely.putBoolean("hook_wifi", FakeLoc.hookWifi)
                rely.putBoolean("need_downgrade_to_2g", FakeLoc.needDowngradeToCdma)
                return true
            }
            "broadcast_location" -> {
                LocationServiceHook.callOnLocationChanged()
                return true
            }
            "load_library" -> {
                val path = rely.getString("path") ?: return false

                if (isLoadedLibrary && path.endsWith("libportal.so")) {
                    rely.putString("result", "success")
                    return true
                }
                runCatching {
                    System.load(path)
                }.onSuccess {
                    rely.putString("result", "success")
                    isLoadedLibrary = true
                }.onFailure {
                    rely.putString("result", it.stackTraceToString())
                }

                if (isLoadedLibrary) {
                    Dobby.setStatus(FakeLoc.enable)
                }

                return true
            }
            else -> return false
        }
    }

//    private var hasHookSensor = false
//
//    private fun tryHookSensor(classLoader: ClassLoader = FakeLoc::class.java.classLoader!!) {
//        if (hasHookSensor || proxyBinders.isNullOrEmpty()) return
//
//
//
//        hasHookSensor = true
//    }

//    private fun generateLocation(): Location {
//        val (location, realLocation) = if (FakeLocationConfig.lastLocation != null) {
//            (FakeLocationConfig.lastLocation!! to true)
//        } else {
//            (Location(LocationManager.GPS_PROVIDER) to false)
//        }
//
//        return LocationServiceProxyHook.injectLocation(location, realLocation)
//    }

    private fun updateCoordinate(
        newLat: Double,
        newLon: Double,
        speed: Double = 0.0,
        route: Boolean = false,
        atNanos: Long = SystemClock.elapsedRealtimeNanos(),
        atMillis: Long = System.currentTimeMillis(),
    ): Boolean {
        if (newLat !in -90.0..90.0 || newLon !in -180.0..180.0) {
            Logger.error("Invalid latitude or longitude: $newLat, $newLon")
            return false
        }
        synchronized(FakeLoc) {
            val previous = FakeLoc.motion
            if (route && previous.route && atNanos <= previous.atNanos) return false
            val positionClock = if (route) RoutePositionJitter.advanceClock(
                previous.positionClockNanos, previous.atNanos, atNanos,
                previous.route, previous.speed,
            ) else 0L
            FakeLoc.latitude = newLat
            FakeLoc.longitude = newLon
            FakeLoc.motion = FakeLoc.Motion(
                speed, atNanos, route, atMillis, positionClock,
            )
            if (route) recordRouteSample() else RouteLocationHistory.clear()
        }
        return true
    }

    /** Caller holds FakeLoc: commit canonical geometry and the matching clocks as one snapshot. */
    private fun recordRouteSample() {
        val motion = FakeLoc.motion
        if (!motion.route) return
        RouteLocationHistory.record(RouteLocationHistory.Sample(
            latitude = FakeLoc.latitude,
            longitude = FakeLoc.longitude,
            altitude = FakeLoc.altitude.takeIf { it.isFinite() } ?: 80.0,
            accuracy = FakeLoc.accuracy.takeIf { it.isFinite() && it >= 0f } ?: 5.0f,
            speed = motion.speed,
            bearing = (FakeLoc.bearing % 360.0 + 360.0) % 360.0,
            atNanos = motion.atNanos,
            atMillis = motion.atMillis,
            positionClockNanos = motion.positionClockNanos,
        ))
    }
}
