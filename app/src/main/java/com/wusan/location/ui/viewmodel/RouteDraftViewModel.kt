package com.wusan.location.ui.viewmodel

import androidx.lifecycle.ViewModel
import com.tencent.tencentmap.mapsdk.maps.model.CameraPosition

/** Retain a route draft when a navigation entry's view is recreated. */
class RouteDraftViewModel : ViewModel() {
    val points = arrayListOf<Pair<Double, Double>>()
    var drawing = false
    var cameraPosition: CameraPosition? = null
}
