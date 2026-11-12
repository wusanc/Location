package com.wusan.location.ui.viewmodel

import android.app.Activity
import android.app.Application
import android.location.LocationManager
import androidx.lifecycle.AndroidViewModel
import com.wusan.location.service.MockRuntime
import com.wusan.location.ui.mock.HistoricalLocation
import com.wusan.location.ui.mock.HistoricalRoute
import com.wusan.location.ui.mock.Rocker

/** UI facade only. Closing an Activity cannot cancel the service-owned movement session. */
class MockServiceViewModel(application: Application) : AndroidViewModel(application) {
    init { MockRuntime.configure(application) }

    val rocker: Rocker get() = MockRuntime.rocker
    val rockerCoroutineController get() = MockRuntime.rockerCoroutineController
    val routeMockCoroutine get() = MockRuntime.routeMockCoroutine
    var isRockerLocked: Boolean
        get() = MockRuntime.isRockerLocked
        set(value) { MockRuntime.isRockerLocked = value }
    var locationManager: LocationManager?
        get() = MockRuntime.locationManager
        set(value) { MockRuntime.locationManager = value }
    var selectedLocation: HistoricalLocation?
        get() = MockRuntime.selectedLocation
        set(value) { MockRuntime.selectedLocation = value }
    var selectedRoute: HistoricalRoute?
        get() = MockRuntime.selectedRoute
        set(value) { MockRuntime.selectedRoute = value }

    fun initRocker(activity: Activity): Rocker = MockRuntime.initRocker(activity)
    fun stopMovement() = MockRuntime.stopMovement()
    suspend fun placeLocation(lat: Double, lon: Double): Boolean = MockRuntime.placeLocation(lat, lon)
    fun isServiceStart(): Boolean = MockRuntime.isServiceStart()
}
