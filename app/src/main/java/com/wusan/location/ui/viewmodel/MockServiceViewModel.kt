package com.wusan.location.ui.viewmodel

import android.app.Activity
import android.location.LocationManager
import android.util.Log
import androidx.lifecycle.ViewModel
import com.tencent.bugly.crashreport.CrashReport
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.wusan.location.android.coro.CoroutineController
import com.wusan.location.android.coro.CoroutineRouteMock
import com.wusan.location.ext.accuracy
import com.wusan.location.ext.altitude
import com.wusan.location.ext.reportDuration
import com.wusan.location.ext.speed
import com.wusan.location.service.MockServiceHelper
import com.wusan.location.ui.mock.HistoricalLocation
import com.wusan.location.ui.mock.HistoricalRoute
import com.wusan.location.ui.mock.Rocker
import moe.wusan.xposed.utils.FakeLoc
import net.sf.geographiclib.Geodesic

class MockServiceViewModel : ViewModel() {
    lateinit var rocker: Rocker

    var isRockerLocked = false
    var routeStage = 0
    val rockerCoroutineController = CoroutineController()
    val routeMockCoroutine = CoroutineRouteMock()

    var isRouteStart = false

    var locationManager: LocationManager? = null
        set(value) {
            field = value
            if (value != null)
                MockServiceHelper.tryInitService(value)
        }

    var selectedLocation: HistoricalLocation? = null
    var selectedRoute: HistoricalRoute? = null


    fun initRocker(activity: Activity): Rocker {
        if (!::rocker.isInitialized) {
            rocker = Rocker(activity)
        }

        // 这两个循环跑在 GlobalScope 里，以前只有“本地是否已经启动过”的判断。
        // 退出再进入 App（Activity 重建）时会再启动一整套，而旧的那套不会停，
        // 于是多个循环同时调用 move()，位移叠加 —— 这就是“速度设成 0.5 却跑得飞快”的原因。
        // 现在用全局引用保证任何时刻只有一套循环在跑。
        startRockerLoop(activity)
        startRouteLoop(activity)

        FakeLoc.speed = activity.speed
        FakeLoc.altitude = activity.altitude
        FakeLoc.accuracy = activity.accuracy

        return rocker
    }

    private fun startRockerLoop(activity: Activity) {
        activeRockerJob?.cancel()
        rockerCoroutineController.pause()

        val delayTime = activity.reportDuration.toLong()
        val applicationContext = activity.applicationContext

        activeRockerJob = GlobalScope.launch {
            while (isActive) {
                rockerCoroutineController.controlledCoroutine()
                delay(delayTime)

                val lm = locationManager ?: continue

                CrashReport.setUserSceneTag(applicationContext, 261773)
                if (!MockServiceHelper.move(lm, FakeLoc.speed * delayTime / 1000, FakeLoc.bearing)) {
                    Log.e("MockServiceViewModel", "Failed to move")
                }
            }
        }
    }

    private fun startRouteLoop(activity: Activity) {
        activeRouteJob?.cancel()
        routeMockCoroutine.pause()

        val delayTime = activity.reportDuration.toLong()

        activeRouteJob = GlobalScope.launch {
            while (isActive) {
                routeMockCoroutine.routeMockCoroutine()
                delay(delayTime)

                val lm = locationManager ?: continue
                val route = selectedRoute?.route
                if (route.isNullOrEmpty()) continue

                // 第 0 阶段：先定位到路线起点
                if (routeStage == 0) {
                    MockServiceHelper.setLocation(lm, route[0].first, route[0].second)
                    routeStage++
                }

                // 吃掉所有已经到达的航点（距离 <1m，或者小于这一 tick 的位移量）
                while (routeStage < route.size) {
                    val target = route[routeStage]
                    val current = MockServiceHelper.getLocation(lm) ?: break
                    val inverse = Geodesic.WGS84.Inverse(
                        current.first,
                        current.second,
                        target.first,
                        target.second
                    )
                    if (inverse.s12 < 1.0 || inverse.s12 < FakeLoc.speed * delayTime / 1000) {
                        MockServiceHelper.setLocation(lm, target.first, target.second)
                        routeStage++
                    } else {
                        break
                    }
                }

                // 全部走完，收尾
                if (routeStage >= route.size) {
                    routeMockCoroutine.pause()
                    if (::rocker.isInitialized) {
                        rocker.autoStatus = false
                    }
                    routeStage = 0
                    break
                }

                // 朝下一个航点移动
                val target = route[routeStage]
                val current = MockServiceHelper.getLocation(lm) ?: continue
                val inverse = Geodesic.WGS84.Inverse(
                    current.first,
                    current.second,
                    target.first,
                    target.second
                )
                var azimuth = inverse.azi1
                if (azimuth < 0) {
                    azimuth += 360
                }

                if (!MockServiceHelper.move(
                        lm,
                        FakeLoc.speed * delayTime / 1000,
                        azimuth
                    )
                ) {
                    Log.e("MockServiceViewModel", "移动失败")
                }
            }
        }
    }

    fun isServiceStart(): Boolean {
        val lm = locationManager ?: return false
        return MockServiceHelper.isServiceInit() && MockServiceHelper.isMockStart(lm)
    }

    companion object {
        /** 全局唯一的活动循环，避免 Activity 重建后叠加出多套移动。 */
        @Volatile
        private var activeRockerJob: Job? = null

        @Volatile
        private var activeRouteJob: Job? = null
    }
}