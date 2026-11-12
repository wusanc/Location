package com.wusan.location.tmap

import android.content.Context
import com.tencent.lbssearch.TencentSearch
import com.tencent.lbssearch.httpresponse.HttpResponseListener
import com.tencent.lbssearch.`object`.param.Geo2AddressParam
import com.tencent.lbssearch.`object`.param.SuggestionParam
import com.tencent.lbssearch.`object`.result.Geo2AddressResultObject
import com.tencent.lbssearch.`object`.result.SuggestionResultObject
import com.tencent.tencentmap.mapsdk.maps.model.LatLng
import com.wusan.location.bdmap.Poi

/**
 * 腾讯地点检索封装：逆地理编码 + 关键词补全。
 *
 * Key 从 AndroidManifest 的 `TencentMapSDK` meta-data 读取。
 *
 * 注意：腾讯 SDK 的包名里带 `object` 关键字，Kotlin 中必须写成 `object`。
 */
object TencentGeo {

    private var search: TencentSearch? = null

    private fun search(context: Context): TencentSearch =
        search ?: TencentSearch(context.applicationContext).also { search = it }

    /** 逆地理编码：GCJ-02 坐标 → 文字地址 */
    fun reverseGeoCode(context: Context, location: LatLng, onResult: (String?) -> Unit) {
        search(context).geo2address(
            Geo2AddressParam(location).getPoi(false),
            object : HttpResponseListener<Geo2AddressResultObject> {
                override fun onSuccess(statusCode: Int, result: Geo2AddressResultObject?) {
                    onResult(result?.result?.address)
                }

                override fun onFailure(statusCode: Int, errorString: String?, e: Throwable?) {
                    onResult(null)
                }
            }
        )
    }

    /**
     * 关键词补全。
     *
     * @param region 城市名，可为空（为空时全国范围提示）
     */
    fun suggest(
        context: Context,
        keyword: String,
        region: String?,
        onResult: (List<Poi>) -> Unit
    ) {
        val param = SuggestionParam().keyword(keyword)
        if (!region.isNullOrBlank()) {
            param.region(region)
        }

        search(context).suggestion(
            param,
            object : HttpResponseListener<SuggestionResultObject> {
                override fun onSuccess(statusCode: Int, result: SuggestionResultObject?) {
                    val list = ArrayList<Poi>()
                    result?.data?.forEach { data ->
                        val latLng = data.latLng ?: return@forEach
                        val title = data.title ?: return@forEach
                        val (lat, lon) = GeoCoord.gcj2wgs(latLng.latitude, latLng.longitude)
                        val address = data.address
                            ?: listOf(data.city, data.district)
                                .filter { !it.isNullOrBlank() }
                                .joinToString(" ")
                        list.add(
                            Poi(
                                name = title,
                                address = address.ifBlank { "未知地址" },
                                longitude = lon,
                                latitude = lat,
                                tag = data.category ?: ""
                            )
                        )
                    }
                    onResult(list)
                }

                override fun onFailure(statusCode: Int, errorString: String?, e: Throwable?) {
                    onResult(emptyList())
                }
            }
        )
    }
}
