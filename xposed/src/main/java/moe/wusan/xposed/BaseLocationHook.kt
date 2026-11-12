package moe.wusan.xposed

import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import de.robv.android.xposed.XposedHelpers
import moe.wusan.xposed.utils.FakeLoc
import moe.wusan.xposed.utils.Logger
import moe.wusan.xposed.utils.RoutePositionJitter
import moe.wusan.xposed.utils.RouteLocationHistory
import moe.microbios.nmea.NMEA
import moe.microbios.nmea.NmeaValue
import kotlin.random.Random
import java.util.concurrent.atomic.AtomicLong

abstract class BaseLocationHook: BaseDivineService() {
    private val lastLocationLogNanos = AtomicLong()
    private val routeSampleKey = "com.wusan.location.route_sample_nanos"

    /** Cached queries ask for the newest position; callback batches preserve their sample clocks. */
    fun injectLatestLocation(originLocation: Location, realLocation: Boolean = true): Location =
        createLocation(originLocation, realLocation, currentRouteSample(), reuseSample = false)

    fun injectLocation(originLocation: Location, realLocation: Boolean = true): Location {
        // Some OEM transports strip extras. An exact accepted sample clock still identifies history.
        val historical = if (FakeLoc.motion.route)
            RouteLocationHistory.exact(originLocation.elapsedRealtimeNanos) else null
        return createLocation(originLocation, realLocation, historical ?: currentRouteSample())
    }

    fun injectLocations(origins: List<Location>, realLocation: Boolean = true): List<Location> {
        if (!FakeLoc.enable || origins.isEmpty()) return origins
        return origins.map { origin ->
            val historical = if (FakeLoc.motion.route && origin.elapsedRealtimeNanos > 0)
                RouteLocationHistory.atOrBefore(origin.elapsedRealtimeNanos) else null
            createLocation(origin, realLocation, historical ?: currentRouteSample())
        }.distinctBy { it.elapsedRealtimeNanos }.sortedBy { it.elapsedRealtimeNanos }
    }

    fun locationForSample(provider: String, sample: RouteLocationHistory.Sample): Location =
        createLocation(Location(provider), false, sample, reuseSample = false)

    private fun currentRouteSample(): RouteLocationHistory.Sample? = synchronized(FakeLoc) {
        if (FakeLoc.motion.route && FakeLoc.motion.isFresh(SystemClock.elapsedRealtimeNanos()))
            RouteLocationHistory.exact(FakeLoc.motion.atNanos) else null
    }

    private fun knownRouteSample(location: Location): Boolean {
        val stamp = location.extras?.getLong(routeSampleKey, -1L) ?: return false
        if (!FakeLoc.motion.route || stamp <= 0 || stamp != location.elapsedRealtimeNanos) return false
        val sample = RouteLocationHistory.exact(stamp) ?: return false
        val point = RoutePositionJitter.point(sample.latitude, sample.longitude,
            sample.positionClockNanos, sample.accuracy.toDouble())
        return location.time == sample.atMillis && location.latitude == point.first &&
            location.longitude == point.second && location.speed == sample.speed.toFloat()
    }

    private fun createLocation(originLocation: Location, realLocation: Boolean,
        fixedSample: RouteLocationHistory.Sample?, reuseSample: Boolean = true): Location {
        if (!FakeLoc.enable) return originLocation
        // A sample may cross several server/client hooks. Never turn historical points into latest.
        if (reuseSample && knownRouteSample(originLocation)) return originLocation
        if (realLocation) {
            if (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    originLocation.provider == LocationManager.GPS_PROVIDER && originLocation.isComplete
                } else {
                    originLocation.provider == LocationManager.GPS_PROVIDER
                }
            ) {
                FakeLoc.lastLocation = originLocation
            }
        }

        val provider = if (FakeLoc.disableNetworkLocation && originLocation.provider == LocationManager.NETWORK_PROVIDER)
            LocationManager.GPS_PROVIDER else originLocation.provider ?: LocationManager.GPS_PROVIDER
        val location = Location(provider)
        location.accuracy = fixedSample?.accuracy ?: if (FakeLoc.accuracy != 0.0f)
            FakeLoc.accuracy else originLocation.accuracy
        val sampleTime = synchronized(FakeLoc) {
            val nowNanos = SystemClock.elapsedRealtimeNanos()
            val nowMillis = System.currentTimeMillis()
            val motion = FakeLoc.motion
            // Only output carries GPS drift. Route progress and distance use canonical coordinates.
            // One motion sample always yields the same point, even through repeated hook paths.
            val point = if (fixedSample != null) RoutePositionJitter.point(
                fixedSample.latitude, fixedSample.longitude, fixedSample.positionClockNanos,
                fixedSample.accuracy.toDouble(),
            ) else if (motion.route) RoutePositionJitter.point(
                FakeLoc.latitude, FakeLoc.longitude, motion.positionClockNanos,
                FakeLoc.accuracy.toDouble(),
            ) else FakeLoc.jitterLocation()
            location.latitude = point.first
            location.longitude = point.second
            location.speed = (fixedSample?.speed ?: FakeLoc.reportedSpeed(nowNanos)).toFloat()
            val bearing = fixedSample?.bearing ?: FakeLoc.bearing
            location.bearing = ((bearing % 360.0 + 360.0) % 360.0).toFloat()
            // Repeated callbacks for one route sample keep its position and time together.
            fixedSample?.let { it.atNanos to it.atMillis } ?: motion.timestamp(nowNanos, nowMillis)
        }
        location.altitude = fixedSample?.altitude ?: FakeLoc.altitude
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            location.speedAccuracyMetersPerSecond = 0.1f
        }

        if (location.altitude == 0.0) {
            location.altitude = 80.0
        }

        // Use the simulation sample's clock, never the cached real GPS template's clock.
        location.time = sampleTime.second

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Heading and heading uncertainty are different values.
            location.bearingAccuracyDegrees = 5.0f
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (location.hasBearingAccuracy() && location.bearingAccuracyDegrees == 0.0f) {
                location.bearingAccuracyDegrees = 1.0f
            }
        }

        location.elapsedRealtimeNanos = sampleTime.first
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            location.elapsedRealtimeUncertaintyNanos = originLocation.elapsedRealtimeUncertaintyNanos
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            location.verticalAccuracyMeters = originLocation.verticalAccuracyMeters
        }
        originLocation.extras?.let {
            location.extras = Bundle(it)
        }
        if (location.extras == null) {
            location.extras = Bundle()
        }
        location.extras?.remove(routeSampleKey)
        fixedSample?.let { location.extras?.putLong(routeSampleKey, it.atNanos) }
        location.extras?.putDouble("latlon", location.latitude + location.longitude)
        location.extras?.putInt("satellites", Random.nextInt(8, 45))
        location.extras?.putInt("maxCn0", Random.nextInt(30, 50))
        location.extras?.putInt("meanCn0", Random.nextInt(20, 30))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            if (originLocation.hasMslAltitude()) {
                location.mslAltitudeMeters = FakeLoc.altitude
            }
            if (originLocation.hasVerticalAccuracy()) {
                location.mslAltitudeAccuracyMeters = FakeLoc.altitude.toFloat()
            }
        }
        if (FakeLoc.hideMock) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                location.isMock = false
            }
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                location.isMock = true
            }
            location.extras?.putBoolean("portal.enable", true)
            location.extras?.putBoolean("is_mock", true)
        }

        kotlin.runCatching {
            XposedHelpers.callMethod(location, "makeComplete")
        }.onFailure {
            Logger.error("makeComplete failed", it)
        }

        if (FakeLoc.enableDebugLog) {
            val now = SystemClock.elapsedRealtimeNanos()
            val previous = lastLocationLogNanos.get()
            if (now - previous >= 1_000_000_000L && lastLocationLogNanos.compareAndSet(previous, now))
                Logger.debug("injectLocation sample: $location")
        }

        return location
    }

    fun injectNMEA(nmeaStr: String): String? {
        if (!FakeLoc.enable) {
            return null
        }

        kotlin.runCatching {
            val nmea = NMEA.valueOf(nmeaStr)
            when(val value = nmea.value) {
                is NmeaValue.DTM -> {
                    return null
                }
                is NmeaValue.GGA -> {
                    if (value.latitude == null || value.longitude == null) {
                        return null
                    }

                    if (value.fixQuality == 0) {
                        return null
                    }

                    val latitudeHemisphere = if (FakeLoc.latitude >= 0) "N" else "S"
                    val longitudeHemisphere = if (FakeLoc.longitude >= 0) "E" else "W"

                    value.latitudeHemisphere = latitudeHemisphere
                    value.longitudeHemisphere = longitudeHemisphere

                    var degree = FakeLoc.latitude.toInt()
                    var minute = (FakeLoc.latitude - degree) * 60
                    value.latitude = degree + minute / 100

                    degree = FakeLoc.longitude.toInt()
                    minute = (FakeLoc.longitude - degree) * 60
                    value.longitude = degree + minute / 100

                    return value.toNmeaString()
                }
                is NmeaValue.GNS -> {
                    if (value.latitude == null || value.longitude == null) {
                        return null
                    }

                    if (value.mode == "N") {
                        return null
                    }

                    val latitudeHemisphere = if (FakeLoc.latitude >= 0) "N" else "S"
                    val longitudeHemisphere = if (FakeLoc.longitude >= 0) "E" else "W"

                    value.latitudeHemisphere = latitudeHemisphere
                    value.longitudeHemisphere = longitudeHemisphere

                    var degree = FakeLoc.latitude.toInt()
                    var minute = (FakeLoc.latitude - degree) * 60
                    value.latitude = degree + minute / 100

                    degree = FakeLoc.longitude.toInt()
                    minute = (FakeLoc.longitude - degree) * 60
                    value.longitude = degree + minute / 100

                    return value.toNmeaString()
                }
                is NmeaValue.GSA -> {
                    return null
                }
                is NmeaValue.GSV -> {
                    return null
                }
                is NmeaValue.RMC -> {
                    if (value.latitude == null || value.longitude == null) {
                        return null
                    }

                    if (value.status == "V") {
                        return null
                    }

                    val latitudeHemisphere = if (FakeLoc.latitude >= 0) "N" else "S"
                    val longitudeHemisphere = if (FakeLoc.longitude >= 0) "E" else "W"

                    value.latitudeHemisphere = latitudeHemisphere
                    value.longitudeHemisphere = longitudeHemisphere

                    var degree = FakeLoc.latitude.toInt()
                    var minute = (FakeLoc.latitude - degree) * 60
                    value.latitude = degree + minute / 100

                    degree = FakeLoc.longitude.toInt()
                    minute = (FakeLoc.longitude - degree) * 60
                    value.longitude = degree + minute / 100

                    return value.toNmeaString()
                }
                is NmeaValue.VTG -> {
                    return null
                }
            }
        }.onFailure {
            Logger.error("NMEA parse failed: ${it.message}, source = $nmeaStr")
            return null
        }
        return null
    }
}
