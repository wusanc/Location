package com.wusan.location.service

import android.annotation.SuppressLint
import android.content.Context
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import com.wusan.location.Portal
import com.wusan.location.android.root.ShellUtils
import com.wusan.location.ext.altitude
import com.wusan.location.ext.debug
import com.wusan.location.ext.disableFusedProvider
import com.wusan.location.ext.disableGetCurrentLocation
import com.wusan.location.ext.disableRegisterLocationListener
import com.wusan.location.ext.enableAGPS
import com.wusan.location.ext.enableGetFromLocation
import com.wusan.location.ext.enableNMEA
import com.wusan.location.ext.enableRequestGeofence
import com.wusan.location.ext.minSatelliteCount
import com.wusan.location.ext.needDowngradeToCdma
import com.wusan.location.ext.speed
import com.wusan.location.ext.reportDuration
import com.wusan.location.ext.loopBroadcastlocation
import moe.wusan.xposed.utils.FakeLoc
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

object MockServiceHelper {
    const val PROVIDER_NAME = "portal"
    private lateinit var randomKey: String

    @Volatile private var loopThread: Thread? = null

    fun tryInitService(locationManager: LocationManager) {
        val rely = Bundle()
        Log.d("MockServiceHelper", "Try to init service")
        if(locationManager.sendExtraCommand(PROVIDER_NAME, "exchange_key", rely)) {
            rely.getString("key")?.let {
                randomKey = it
                Log.d("MockServiceHelper", "Service init success")
            }
        } else {
            Log.e("MockServiceHelper", "Failed to init service")
        }
    }

    fun isMockStart(locationManager: LocationManager): Boolean {
        if (!::randomKey.isInitialized) {
            return false
        }
        val rely = Bundle()
        rely.putString("command_id", "is_start")
        if(locationManager.sendExtraCommand(PROVIDER_NAME, randomKey, rely)) {
            return rely.getBoolean("is_start")
        }
        return false
    }

    fun isGnssMockStart(locationManager: LocationManager): Boolean {
        if (!::randomKey.isInitialized) {
            return false
        }
        val rely = Bundle()
        rely.putString("command_id", "is_gnss_start")
        if(locationManager.sendExtraCommand(PROVIDER_NAME, randomKey, rely)) {
            return rely.getBoolean("is_gnss_start")
        }
        return false
    }

    fun startGnssMock(locationManager: LocationManager): Boolean {
        if (!::randomKey.isInitialized) {
            return false
        }
        val rely = Bundle()
        rely.putString("command_id", "start_gnss_mock")
        return locationManager.sendExtraCommand(PROVIDER_NAME, randomKey, rely)
    }

    fun stopGnssMock(locationManager: LocationManager): Boolean {
        if (!::randomKey.isInitialized) {
            return false
        }
        val rely = Bundle()
        rely.putString("command_id", "stop_gnss_mock")
        return locationManager.sendExtraCommand(PROVIDER_NAME, randomKey, rely)
    }

    fun isWifiMockStart(locationManager: LocationManager): Boolean {
        if (!::randomKey.isInitialized) {
            return false
        }
        val rely = Bundle()
        rely.putString("command_id", "is_wifi_mock_start")
        if(locationManager.sendExtraCommand(PROVIDER_NAME, randomKey, rely)) {
            return rely.getBoolean("is_wifi_mock_start")
        }
        return false
    }

    fun startWifiMock(locationManager: LocationManager): Boolean {
        if (!::randomKey.isInitialized) {
            return false
        }
        val rely = Bundle()
        rely.putString("command_id", "start_wifi_mock")
        return locationManager.sendExtraCommand(PROVIDER_NAME, randomKey, rely)
    }

    fun stopWifiMock(locationManager: LocationManager): Boolean {
        if (!::randomKey.isInitialized) {
            return false
        }
        val rely = Bundle()
        rely.putString("command_id", "stop_wifi_mock")
        return locationManager.sendExtraCommand(PROVIDER_NAME, randomKey, rely)
    }

    suspend fun tryOpenMock(
        locationManager: LocationManager,
        speed: Double,
        altitude: Double,
        accuracy: Float,
    ): Boolean {
        if (!::randomKey.isInitialized) {
            return false
        }
        val context = Portal.appContext
        withContext(Dispatchers.Main.immediate) { MockRuntime.prepareSession() }
        if (!MockRuntimeService.ensureStarted(context)) return false
        val ready = withTimeoutOrNull(5_000L) {
            MockRuntime.state.first { it.serviceRunning || it.phase == RuntimePhase.ERROR }
        }
        if (ready?.serviceRunning != true) {
            MockRuntime.startFailed(ready?.error ?: "前台运行服务启动超时")
            MockRuntimeService.requestStop(context)
            return false
        }
        val rely = Bundle()
        rely.putString("command_id", "start")
        rely.putDouble("speed", speed)
        rely.putDouble("altitude", altitude)
        rely.putFloat("accuracy", accuracy)
        val started = locationManager.sendExtraCommand(PROVIDER_NAME, randomKey, rely) &&
            isMockStart(locationManager)
        if (started) {
            startLoopBroadcastLocation(locationManager)
            MockRuntime.onMockOpened()
        } else {
            MockRuntime.startFailed("系统模拟服务启动失败")
            MockRuntimeService.requestStop(context)
        }
        return started
    }

    fun tryCloseMock(locationManager: LocationManager): Boolean {
        if (!::randomKey.isInitialized) {
            return false
        }
        val rely = Bundle()
        rely.putString("command_id", "stop")
        stopLoopBroadcastLocation()
        if (locationManager.sendExtraCommand(PROVIDER_NAME, randomKey, rely)) {
            val stopped = !isMockStart(locationManager)
            if (stopped) MockRuntime.onMockClosed()
            return stopped
        }
        return false
    }

    /** Read-only system-side callback diagnostics. Null also supports older installed modules. */
    fun getDeliveryDiagnostics(locationManager: LocationManager): Bundle? {
        if (!::randomKey.isInitialized) return null
        val packet = Bundle().apply { putString("command_id", "get_delivery_diagnostics") }
        return if (locationManager.sendExtraCommand(PROVIDER_NAME, randomKey, packet)) packet else null
    }

    fun getLocation(locationManager: LocationManager): Pair<Double, Double>? {
        if (!::randomKey.isInitialized) {
            return null
        }
        val rely = Bundle()
        rely.putString("command_id", "get_location")
        if(locationManager.sendExtraCommand(PROVIDER_NAME, randomKey, rely)) {
            return Pair(rely.getDouble("lat"), rely.getDouble("lon"))
        }
        return null
    }

    fun getLocationListenerSize(locationManager: LocationManager): Int? {
        if (!::randomKey.isInitialized) {
            return null
        }
        val rely = Bundle()
        rely.putString("command_id", "get_listener_size")
        if(locationManager.sendExtraCommand(PROVIDER_NAME, randomKey, rely)) {
            return rely.getInt("size")
        }
        return null
    }

    fun broadcastLocation(locationManager: LocationManager): Boolean {
        if (!::randomKey.isInitialized) {
            return false
        }
        val rely = Bundle()
        rely.putString("command_id", "broadcast_location")
        return locationManager.sendExtraCommand(PROVIDER_NAME, randomKey, rely)
    }

    fun setBearing(locationManager: LocationManager, bearing: Double): Boolean {
        if (!::randomKey.isInitialized) {
            return false
        }
        val rely = Bundle()
        rely.putString("command_id", "set_bearing")
        rely.putDouble("bearing", bearing)
        return locationManager.sendExtraCommand(PROVIDER_NAME, randomKey, rely)
    }

    fun setSpeed(locationManager: LocationManager, speed: Float): Boolean {
        if (!::randomKey.isInitialized) {
            return false
        }
        val rely = Bundle()
        rely.putString("command_id", "set_speed")
        rely.putDouble("speed", speed.toDouble())
        return locationManager.sendExtraCommand(PROVIDER_NAME, randomKey, rely)
    }

    fun setAltitude(locationManager: LocationManager, altitude: Double): Boolean {
        if (!::randomKey.isInitialized) {
            return false
        }
        val rely = Bundle()
        rely.putString("command_id", "set_altitude")
        rely.putDouble("altitude", altitude)
        return locationManager.sendExtraCommand(PROVIDER_NAME, randomKey, rely)
    }

    fun setSpeedAmplitude(locationManager: LocationManager, speedAmplitude: Double): Boolean {
        if (!::randomKey.isInitialized) {
            return false
        }
        val rely = Bundle()
        rely.putString("command_id", "set_speed_amp")
        rely.putDouble("speed_amplitude", speedAmplitude)
        return locationManager.sendExtraCommand(PROVIDER_NAME, randomKey, rely)
    }

    fun getSpeed(locationManager: LocationManager): Float? {
        if (!::randomKey.isInitialized) {
            return null
        }
        val rely = Bundle()
        rely.putString("command_id", "get_speed")
        if(locationManager.sendExtraCommand(PROVIDER_NAME, randomKey, rely)) {
            return rely.getDouble("speed").toFloat()
        }
        return null
    }

    fun getBearing(locationManager: LocationManager): Float? {
        if (!::randomKey.isInitialized) {
            return null
        }
        val rely = Bundle()
        rely.putString("command_id", "get_bearing")
        if(locationManager.sendExtraCommand(PROVIDER_NAME, randomKey, rely)) {
            return rely.getDouble("bearing").toFloat()
        }
        return null
    }

    fun getAltitude(locationManager: LocationManager): Double? {
        if (!::randomKey.isInitialized) {
            return null
        }
        val rely = Bundle()
        rely.putString("command_id", "get_altitude")
        if(locationManager.sendExtraCommand(PROVIDER_NAME, randomKey, rely)) {
            return rely.getDouble("altitude")
        }
        return null
    }

    fun move(locationManager: LocationManager, distance: Double, bearing: Double): Boolean {
        if (!::randomKey.isInitialized) {
            return false
        }
        val rely = Bundle()
        rely.putString("command_id", "move")
        rely.putDouble("n", distance)
        rely.putDouble("bearing", bearing)

        if (FakeLoc.enableDebugLog) {
            Log.d("MockServiceHelper", "move: distance=$distance, bearing=$bearing")
        }

        return locationManager.sendExtraCommand(PROVIDER_NAME, randomKey, rely)
    }

    fun routeStep(
        locationManager: LocationManager,
        position: Pair<Double, Double>,
        speed: Double,
        bearing: Double?,
        sampleNanos: Long,
        sampleMillis: Long,
    ): Boolean {
        if (!::randomKey.isInitialized) return false
        val packet = Bundle().apply {
            putString("command_id", "route_step")
            putDouble("lat", position.first)
            putDouble("lon", position.second)
            putDouble("actual_speed", speed)
            putLong("sample_nanos", sampleNanos)
            putLong("sample_millis", sampleMillis)
            bearing?.let { putDouble("bearing", it) }
        }
        return locationManager.sendExtraCommand(PROVIDER_NAME, randomKey, packet)
    }

    fun stopMotion(locationManager: LocationManager): Boolean {
        if (!::randomKey.isInitialized) return false
        val packet = Bundle().apply { putString("command_id", "stop_motion") }
        return locationManager.sendExtraCommand(PROVIDER_NAME, randomKey, packet)
    }

    fun setLocation(locationManager: LocationManager, lat: Double, lon: Double): Boolean {
        return updateLocation(locationManager, lat, lon, "=")
    }

    fun updateLocation(locationManager: LocationManager, lat: Double, lon: Double, mode: String): Boolean {
        if (!::randomKey.isInitialized) {
            return false
        }
        val rely = Bundle()
        rely.putString("command_id", "update_location")
        rely.putDouble("lat", lat)
        rely.putDouble("lon", lon)
        rely.putString("mode", mode)
        return locationManager.sendExtraCommand(PROVIDER_NAME, randomKey, rely)
    }

    fun loadLibrary(locationManager: LocationManager, path: String): String? {
        if (!::randomKey.isInitialized) {
            return null
        }
        val rely = Bundle()
        rely.putString("command_id", "load_library")
        rely.putString("path", path)
        if(locationManager.sendExtraCommand(PROVIDER_NAME, randomKey, rely)) {
            return rely.getString("result")
        }
        return null
    }

    fun putConfig(locationManager: LocationManager, context: Context): Boolean {
        if (!::randomKey.isInitialized) {
            return false
        }

        FakeLoc.altitude = context.altitude
        FakeLoc.speed = context.speed
        FakeLoc.enableDebugLog = context.debug
        FakeLoc.disableGetCurrentLocation = context.disableGetCurrentLocation
        FakeLoc.disableRegisterLocationListener = context.disableRegisterLocationListener
        FakeLoc.disableFusedLocation = context.disableFusedProvider
        FakeLoc.needDowngradeToCdma = context.needDowngradeToCdma
        FakeLoc.minSatellites = context.minSatelliteCount
        FakeLoc.enableAGPS = context.enableAGPS
        FakeLoc.enableNMEA = context.enableNMEA
        FakeLoc.disableRequestGeofence = !context.enableRequestGeofence
        FakeLoc.disableGetFromLocation = !context.enableGetFromLocation

        val rely = Bundle()
        rely.putString("command_id", "put_config")
        rely.putBoolean("enable", FakeLoc.enable)
        rely.putDouble("altitude", FakeLoc.altitude)
        rely.putDouble("speed", FakeLoc.speed)
        rely.putBoolean("enable_debug_log", FakeLoc.enableDebugLog)
        rely.putBoolean("disable_get_current_location", FakeLoc.disableGetCurrentLocation)
        rely.putBoolean("disable_register_location_listener", FakeLoc.disableRegisterLocationListener)
        rely.putBoolean("disable_fused_location", FakeLoc.disableFusedLocation)
        rely.putBoolean("need_downgrade_to_2g", FakeLoc.needDowngradeToCdma)
        rely.putInt("min_satellites", FakeLoc.minSatellites)
        rely.putBoolean("enable_agps", FakeLoc.enableAGPS)
        rely.putBoolean("enable_nmea", FakeLoc.enableNMEA)
        rely.putBoolean("disable_request_geofence", FakeLoc.disableRequestGeofence)
        rely.putBoolean("disable_get_from_location", FakeLoc.disableGetFromLocation)

        return locationManager.sendExtraCommand(PROVIDER_NAME, randomKey, rely)
    }

    fun isServiceInit(): Boolean {
        return ::randomKey.isInitialized
    }


    @Synchronized
    private fun startLoopBroadcastLocation(locationManager: LocationManager) {
        val appContext = Portal.appContext
        if (loopThread?.isAlive == true || !appContext.loopBroadcastlocation) return
        val worker = Thread({
            val self = Thread.currentThread()
            try {
                while (loopThread === self && !self.isInterrupted) {
                    broadcastLocation(locationManager)
                    Thread.sleep(appContext.reportDuration.toLong())
                }
            } catch (_: InterruptedException) {
                self.interrupt()
            } catch (e: Exception) {
                Log.e("MockServiceHelper", "Location broadcast stopped", e)
            } finally {
                synchronized(this) {
                    if (loopThread === self) loopThread = null
                }
            }
        }, "location-broadcast")
        loopThread = worker
        worker.start()
    }

    @Synchronized
    private fun stopLoopBroadcastLocation() {
        val worker = loopThread
        loopThread = null
        worker?.interrupt()
    }

    @SuppressLint("DiscouragedPrivateApi")
    fun loadPortalLibrary(context: Context): Boolean {
        if (!ShellUtils.hasRoot()) return false

        val isX86: Boolean = runCatching {
            if (Build.SUPPORTED_ABIS.any { it.contains("x86") }) {
                return@runCatching true
            }
            val clazz = Class.forName("dalvik.system.VMRuntime")
            val method = clazz.getDeclaredMethod("getRuntime")
            val runtime = method.invoke(null)
            val field = clazz.getDeclaredField("vmInstructionSet")
            field.isAccessible = true
            val instructionSet = field.get(runtime) as String
            if (instructionSet.contains("x86") ) {
                true
            } else false
        }.getOrElse { false }
        // todo: support x86

        val soDir = File("/data/local/portal-lib")
        if (!soDir.exists()) {
            ShellUtils.executeCommand("mkdir ${soDir.absolutePath}")
        }
        val soFile = File(soDir, "libportal.so")
        runCatching {
            val tmpSoFile = File(soDir, "libportal.so.tmp").also { file ->
                var nativeDir = context.applicationInfo.nativeLibraryDir
                val apkSoFile = File(nativeDir, "libportal.so")
                if (apkSoFile.exists()) {
                    ShellUtils.executeCommand("cp ${apkSoFile.absolutePath} ${file.absolutePath}")
                } else {
                    Log.e("MockServiceHelper", "Failed to copy portal library: ${apkSoFile.absolutePath}")
                    return@runCatching
                }
            }
            if (soFile.exists()) {
                val originalHash = ShellUtils.executeCommandToBytes("head -c 4096 ${soFile.absolutePath}")
                val newHash = ShellUtils.executeCommandToBytes("head -c 4096 ${tmpSoFile.absolutePath}")
                if (originalHash.contentEquals(newHash)) {
                    ShellUtils.executeCommand("rm ${soFile.absolutePath}")
                    ShellUtils.executeCommand("mv ${tmpSoFile.absolutePath} ${soFile.absolutePath}")
                }
            } else if (tmpSoFile.exists()) {
                ShellUtils.executeCommand("mv ${tmpSoFile.absolutePath} ${soFile.absolutePath}")
            }
        }.onFailure {
            Log.w("MockServiceHelper", "Failed to copy portal library", it)
        }

        ShellUtils.executeCommand("chmod 777 ${soFile.absolutePath}")

        val result = loadLibrary(context.getSystemService(Context.LOCATION_SERVICE) as LocationManager, soFile.absolutePath)

        Log.d("MockServiceHelper", "load portal library result: $result")

        return result == "success"
    }
}
