package com.wusan.location.ui.viewmodel

import android.app.Notification
import androidx.lifecycle.ViewModel
import com.tencent.tencentmap.mapsdk.maps.TencentMap
import com.tencent.tencentmap.mapsdk.maps.model.BitmapDescriptor
import com.tencent.tencentmap.mapsdk.maps.model.BitmapDescriptorFactory
import com.wusan.location.R

class MapViewModel : ViewModel() {
    var isExists = false
    lateinit var tencentMap: TencentMap

    /**
     * 当前定位
     * WGS-84
     */
    var currentLocation: Pair<Double, Double>? = null

    var markName: String? = null

    /**
     * 标记位置
     * WGS-84
     * first => latitude
     * second => longitude
     */
    var markedLoc: Pair<Double, Double>? = null
    var showDetailView = false

    /* Notification */
    var mNotification: Notification? = null

    /** 是否让地图跟随定位 */
    var followLocation = false

    val mMapIndicator: BitmapDescriptor? by lazy {
        BitmapDescriptorFactory.fromResource(R.drawable.icon_selected_location_16)
    }
}
