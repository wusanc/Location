package com.wusan.location.ext

import com.tencent.tencentmap.mapsdk.maps.model.LatLng
import com.wusan.location.tmap.GeoCoord

/**
 * 腾讯地图 LatLng（GCJ-02）→ 项目内部坐标（WGS-84）
 */
val LatLng.wgs84: Pair<Double, Double>
    get() = GeoCoord.gcj2wgs(latitude, longitude)

/**
 * 项目内部坐标（WGS-84）→ 腾讯地图 LatLng（GCJ-02）
 */
val Pair<Double, Double>.gcj02: LatLng
    get() = GeoCoord.wgs2gcj(first, second).let { LatLng(it.first, it.second) }

/**
 * 保留旧调用名，内部改为自有实现，不再依赖百度 JNI。
 */
object Loc4j {
    fun gcj2wgs(lat: Double, lon: Double): Pair<Double, Double> = GeoCoord.gcj2wgs(lat, lon)

    fun wgs2gcj(lat: Double, lon: Double): Pair<Double, Double> = GeoCoord.wgs2gcj(lat, lon)
}
