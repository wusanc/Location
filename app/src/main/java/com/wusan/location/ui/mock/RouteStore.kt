package com.wusan.location.ui.mock

import android.content.Context
import com.alibaba.fastjson2.JSON
import com.wusan.location.ext.jsonHistoricalRoutes
import com.wusan.location.ext.sharedPrefs
import java.io.IOException

/** Uses the existing preference schema, so map editing and route playback remain compatible. */
internal object RouteStore {
    @Synchronized
    fun read(context: Context, initialize: Boolean = false): List<HistoricalRoute> {
        val json = context.jsonHistoricalRoutes
        if (json.isEmpty()) {
            if (!initialize) return emptyList()
            val defaults = listOf(HistoricalRoute("默认路线",
                listOf(39.908822 to 116.397465, 39.907951 to 116.397500)))
            save(context, defaults)
            return defaults
        }
        try {
            // An empty saved array is intentional; do not recreate the default route.
            if (json.trim() == "[]") return emptyList()
            return RouteFileCodec.decode(json.byteInputStream(Charsets.UTF_8))
        } catch (e: Exception) {
            throw IOException("已保存的路线无法读取，未修改原数据", e)
        }
    }

    @Synchronized
    fun import(context: Context, incoming: List<HistoricalRoute>): RouteFileCodec.MergeResult {
        val merged = RouteFileCodec.merge(read(context), incoming)
        if (merged.added > 0) save(context, merged.routes)
        return merged
    }

    @Synchronized
    fun delete(context: Context, route: HistoricalRoute): List<HistoricalRoute> {
        val routes = read(context).toMutableList()
        val index = routes.indexOf(route)
        if (index >= 0) {
            routes.removeAt(index)
            save(context, routes)
        }
        return routes
    }

    private fun save(context: Context, routes: List<HistoricalRoute>) {
        if (!context.sharedPrefs.edit().putString("routes", JSON.toJSONString(routes)).commit()) {
            throw IOException("路线保存失败，请检查存储空间")
        }
    }
}
