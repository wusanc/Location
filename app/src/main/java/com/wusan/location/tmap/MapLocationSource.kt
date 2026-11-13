package com.wusan.location.tmap

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.tencent.tencentmap.mapsdk.maps.LocationSource
import com.wusan.location.ext.gcj02

/** Only the copy delivered to Tencent's blue dot uses GCJ02; stored coordinates remain WGS84. */
class MapLocationSource(context: Context, private val onLocation: (Pair<Double, Double>) -> Unit) : LocationSource {
    private val context = context.applicationContext
    private val manager = this.context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    private var callback: LocationSource.OnLocationChangedListener? = null
    private var running = false
    private var last: Location? = null
    private val listener = LocationListener { location -> deliver(location) }

    override fun activate(listener: LocationSource.OnLocationChangedListener) {
        callback = listener
        last?.let(::deliver)
    }

    override fun deactivate() {
        callback = null
        stop()
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        val manager = manager ?: return
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) return
        running = true
        val providers = if (fine) listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            else listOf(LocationManager.NETWORK_PROVIDER)
        val cached = mutableListOf<Location>()
        providers.forEach { provider ->
            // Failure of one provider must not suppress the other provider.
            runCatching {
                if (provider in manager.allProviders) {
                    manager.requestLocationUpdates(provider, 1000L, 0f, listener, Looper.getMainLooper())
                    manager.getLastKnownLocation(provider)?.let { cached.add(it) }
                }
            }.onFailure { Log.w("MapLocationSource", "Cannot subscribe to $provider", it) }
        }
        cached.filter { SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos in 0L..120_000_000_000L }
            .maxByOrNull { it.elapsedRealtimeNanos }?.let(::deliver)
    }

    fun stop() {
        running = false
        runCatching { manager?.removeUpdates(listener) }
    }

    fun close() {
        deactivate()
        last = null
    }

    private fun deliver(location: Location) {
        if (!running) return
        if (location.latitude !in -90.0..90.0 || location.longitude !in -180.0..180.0) return
        if (last != null && location.elapsedRealtimeNanos < last!!.elapsedRealtimeNanos) return
        last = location
        val wgs = location.latitude to location.longitude
        onLocation(wgs)
        val gcj = wgs.gcj02
        callback?.onLocationChanged(Location(location).apply {
            latitude = gcj.latitude
            longitude = gcj.longitude
        })
    }
}
