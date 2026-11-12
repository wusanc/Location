package com.wusan.location.tmap

/**
 * WGS-84 与 GCJ-02 互转。
 *
 * 腾讯地图使用 GCJ-02（火星坐标），而本项目内部（历史位置、路线文件、模拟逻辑）
 * 统一按 WGS-84 存储，所以在两者之间必须显式转换：
 *
 * - 把坐标画到腾讯地图上：`wgs2gcj`
 * - 把腾讯地图 / 检索结果存回项目：`gcj2wgs`
 *
 * 之前这部分是借用百度 SDK 的 `Jni.coorEncrypt`，切换到腾讯地图后不再依赖百度。
 */
object GeoCoord {

    private const val A = 6378245.0
    private const val EE = 0.00669342162296594323

    /** 粗略判断是否在中国境外，境外不做偏移。 */
    fun outOfChina(lat: Double, lon: Double): Boolean =
        lon < 72.004 || lon > 137.8347 || lat < 0.8293 || lat > 55.8271

    /** WGS-84 → GCJ-02 */
    fun wgs2gcj(lat: Double, lon: Double): Pair<Double, Double> {
        if (outOfChina(lat, lon)) return Pair(lat, lon)

        val dLat = transformLat(lon - 105.0, lat - 35.0)
        val dLon = transformLon(lon - 105.0, lat - 35.0)

        val radLat = lat / 180.0 * Math.PI
        var magic = Math.sin(radLat)
        magic = 1 - EE * magic * magic
        val sqrtMagic = Math.sqrt(magic)

        val mgLat = lat + (dLat * 180.0) / ((A * (1 - EE)) / (magic * sqrtMagic) * Math.PI)
        val mgLon = lon + (dLon * 180.0) / (A / sqrtMagic * Math.cos(radLat) * Math.PI)
        return Pair(mgLat, mgLon)
    }

    /**
     * GCJ-02 → WGS-84
     *
     * 没有解析解，用迭代逼近：三次迭代后误差已小于 1e-7 度（约 1 厘米）。
     */
    fun gcj2wgs(lat: Double, lon: Double): Pair<Double, Double> {
        if (outOfChina(lat, lon)) return Pair(lat, lon)

        var wgsLat = lat
        var wgsLon = lon
        repeat(3) {
            val (gLat, gLon) = wgs2gcj(wgsLat, wgsLon)
            wgsLat -= gLat - lat
            wgsLon -= gLon - lon
        }
        return Pair(wgsLat, wgsLon)
    }

    private fun transformLat(x: Double, y: Double): Double {
        var ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * Math.sqrt(Math.abs(x))
        ret += (20.0 * Math.sin(6.0 * x * Math.PI) + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0 / 3.0
        ret += (20.0 * Math.sin(y * Math.PI) + 40.0 * Math.sin(y / 3.0 * Math.PI)) * 2.0 / 3.0
        ret += (160.0 * Math.sin(y / 12.0 * Math.PI) + 320.0 * Math.sin(y * Math.PI / 30.0)) * 2.0 / 3.0
        return ret
    }

    private fun transformLon(x: Double, y: Double): Double {
        var ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * Math.sqrt(Math.abs(x))
        ret += (20.0 * Math.sin(6.0 * x * Math.PI) + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0 / 3.0
        ret += (20.0 * Math.sin(x * Math.PI) + 40.0 * Math.sin(x / 3.0 * Math.PI)) * 2.0 / 3.0
        ret += (150.0 * Math.sin(x / 12.0 * Math.PI) + 300.0 * Math.sin(x / 30.0 * Math.PI)) * 2.0 / 3.0
        return ret
    }
}
