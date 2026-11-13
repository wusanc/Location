package com.wusan.location.ui.viewmodel

import android.app.Notification
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.ViewModel
import com.tencent.tencentmap.mapsdk.maps.TencentMap
import com.tencent.tencentmap.mapsdk.maps.model.BitmapDescriptorFactory
import com.tencent.tencentmap.mapsdk.maps.model.Marker
import com.tencent.tencentmap.mapsdk.maps.model.MarkerOptions
import com.wusan.location.R
import com.wusan.location.ext.gcj02
import com.wusan.location.tmap.TencentGeo
import java.util.Locale

class MapViewModel : ViewModel() {
    var tencentMap: TencentMap? = null
        private set
    val isExists get() = tencentMap != null
    var currentLocation: Pair<Double, Double>? = null
    var markedLoc: Pair<Double, Double>? = null
        private set
    var markName: String? = null
        private set
    var showDetailView = false
    var mNotification: Notification? = null
    var followLocation = false
    var viewGeneration = 0L
        private set
    private var addressRequest = 0L
    private var marker: Marker? = null
    private val main = Handler(Looper.getMainLooper())

    fun attach(map: TencentMap) {
        addressRequest++
        viewGeneration++
        marker = null
        tencentMap = map
    }

    fun detach(map: TencentMap) {
        if (tencentMap !== map) return
        addressRequest++
        viewGeneration++
        marker = null
        tencentMap = null
    }

    fun select(point: Pair<Double, Double>, name: String? = null) {
        addressRequest++
        markedLoc = point
        markName = name
        followLocation = false
    }

    fun resolveAddress(context: Context) {
        val point = markedLoc ?: return
        val map = tencentMap ?: return
        val request = ++addressRequest
        TencentGeo.reverseGeoCode(context.applicationContext, point.gcj02) { address ->
            main.post {
                if (request == addressRequest && tencentMap === map && markedLoc == point) {
                    markName = address
                    marker?.title = address ?: "未知地址"
                    if (showDetailView) marker?.showInfoWindow()
                }
            }
        }
    }

    fun clearMarker() {
        marker?.remove()
        marker = null
    }

    fun drawMarker() {
        val map = tencentMap ?: return
        val point = markedLoc ?: return
        clearMarker()
        marker = map.addMarker(MarkerOptions().position(point.gcj02)
            .icon(BitmapDescriptorFactory.fromResource(R.drawable.icon_selected_location_16))
            .title(markName ?: "所选位置")
            .snippet(String.format(Locale.ROOT, "WGS84: %.6f, %.6f", point.first, point.second)))
        if (showDetailView) marker?.showInfoWindow()
    }
}
