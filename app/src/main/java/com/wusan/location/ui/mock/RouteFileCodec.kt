package com.wusan.location.ui.mock

import com.alibaba.fastjson2.JSON
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler
import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import javax.xml.parsers.SAXParserFactory

/** Interchange files use WGS84, with latitude before longitude. */
object RouteFileCodec {
    enum class Format { JSON, GPX }
    data class MergeResult(
        val routes: List<HistoricalRoute>, val added: Int, val duplicates: Int, val renamed: Int
    )

    private const val MAX_BYTES = 10 * 1024 * 1024
    private const val MAX_ROUTES = 1000
    private const val MAX_POINTS = 100_000
    private const val MAX_NAME = 120

    fun decode(input: InputStream): List<HistoricalRoute> {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size() + count <= MAX_BYTES) { "路线文件不能超过 10 MB" }
            output.write(buffer, 0, count)
        }
        val bytes = output.toByteArray()
        val text = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF").trim()
        } catch (e: Exception) {
            throw IllegalArgumentException("请使用 UTF-8 编码的路线文件", e)
        }
        require(text.isNotEmpty()) { "路线文件为空" }
        val routes = try {
            when (text.first()) {
                '{', '[' -> decodeJson(text)
                '<' -> decodeGpx(text)
                else -> throw IllegalArgumentException("请选择 JSON 或 GPX 路线文件")
            }
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (e: Exception) {
            throw IllegalArgumentException("路线文件格式错误，请检查 JSON 或 GPX 内容", e)
        }
        return validate(routes)
    }

    fun encode(routes: List<HistoricalRoute>, format: Format): String {
        val checked = validate(routes)
        val text = when (format) {
            Format.JSON -> JSON.toJSONString(linkedMapOf(
                "format" to "location-routes", "version" to 1, "coordinateSystem" to "WGS84",
                "routes" to checked.map { route -> linkedMapOf(
                    "name" to route.name,
                    "points" to route.route.map { (lat, lon) ->
                        linkedMapOf("latitude" to lat, "longitude" to lon)
                    }
                ) }
            ))
            Format.GPX -> buildString {
                append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
                append("<gpx version=\"1.1\" creator=\"Location\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
                checked.forEach { route ->
                    append("  <trk><name>").append(escapeXml(route.name)).append("</name><trkseg>\n")
                    route.route.forEach { (lat, lon) ->
                        append("    <trkpt lat=\"").append(lat).append("\" lon=\"")
                            .append(lon).append("\"/>\n")
                    }
                    append("  </trkseg></trk>\n")
                }
                append("</gpx>\n")
            }
        }
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "导出内容超过 10 MB，请分条导出" }
        return text
    }

    fun merge(existing: List<HistoricalRoute>, incoming: List<HistoricalRoute>): MergeResult {
        val checked = validate(incoming)
        val result = existing.toMutableList()
        val geometry = existing.mapTo(HashSet()) { it.route }
        val names = existing.mapTo(HashSet()) { it.name }
        var duplicates = 0
        var renamed = 0
        checked.forEach { route ->
            if (!geometry.add(route.route)) {
                duplicates++
            } else {
                var name = route.name
                if (name in names) {
                    renamed++
                    var number = 2
                    do {
                        val suffix = " (${number++})"
                        name = route.name.take(MAX_NAME - suffix.length) + suffix
                    } while (name in names)
                }
                names.add(name)
                result.add(route.copy(name = name))
            }
        }
        require(result.size <= MAX_ROUTES && result.sumOf { it.route.size.toLong() } <= MAX_POINTS) {
            "合并后超过上限：最多 1000 条路线、共 100000 个坐标点"
        }
        return MergeResult(result, result.size - existing.size, duplicates, renamed)
    }

    private fun validate(routes: List<HistoricalRoute>): List<HistoricalRoute> {
        require(routes.isNotEmpty()) { "文件中没有路线" }
        require(routes.size <= MAX_ROUTES && routes.sumOf { it.route.size.toLong() } <= MAX_POINTS) {
            "最多支持 1000 条路线、共 100000 个坐标点"
        }
        return routes.map { route ->
            val name = route.name.trim()
            require(name.isNotEmpty() && name.length <= MAX_NAME && name.none { it.code < 32 }) {
                "路线名称须为 1～120 个字符，不能包含控制字符"
            }
            require(route.route.isNotEmpty()) { "路线“$name”没有坐标点" }
            route.route.forEach { (lat, lon) -> checkPoint(lat, lon) }
            route.copy(name = name)
        }
    }

    private fun checkPoint(lat: Double, lon: Double) {
        require(lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0) {
            "坐标超出范围：纬度 -90～90，经度 -180～180"
        }
    }

    private fun decodeJson(text: String): List<HistoricalRoute> {
        val root = JSON.parse(text)
        val items = when (root) {
            is List<*> -> root
            is Map<*, *> -> if (root.containsKey("routes")) {
                require(root["format"] == null || root["format"] == "location-routes") { "不支持此路线格式" }
                require(root["version"] is Number && (root["version"] as Number).toDouble() == 1.0) {
                    "不支持此路线文件版本"
                }
                require(root["coordinateSystem"] == "WGS84") { "仅支持 WGS84 路线坐标" }
                root["routes"] as? List<*> ?: throw IllegalArgumentException("routes 必须是路线数组")
            } else listOf(root)
            else -> throw IllegalArgumentException("JSON 内容必须是路线对象或数组")
        }
        require(items.size <= MAX_ROUTES) { "最多支持 1000 条路线" }
        var pointCount = 0
        return items.map { item ->
            val obj = item as? Map<*, *> ?: throw IllegalArgumentException("路线必须是 JSON 对象")
            val name = obj["name"] as? String ?: throw IllegalArgumentException("路线缺少名称")
            val modern = obj.containsKey("points")
            val points = obj[if (modern) "points" else "route"] as? List<*>
                ?: throw IllegalArgumentException("路线缺少坐标数组")
            pointCount += points.size
            require(pointCount <= MAX_POINTS) { "最多支持 100000 个坐标点" }
            HistoricalRoute(name, points.map { point ->
                val p = point as? Map<*, *> ?: throw IllegalArgumentException("坐标点必须是 JSON 对象")
                fun number(key: String): Double = (p[key] as? Number)?.toDouble()
                    ?: throw IllegalArgumentException("坐标字段 $key 缺失或不是数字")
                number(if (modern) "latitude" else "first") to number(if (modern) "longitude" else "second")
            })
        }
    }

    private fun decodeGpx(text: String): List<HistoricalRoute> {
        // Also reject declarations in the source: Android SAX implementations differ in feature support.
        require(!Regex("<!\\s*(DOCTYPE|ENTITY)\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)) {
            "GPX 不支持 DOCTYPE 或实体声明"
        }
        val handler = GpxHandler()
        val factory = SAXParserFactory.newInstance().apply { isNamespaceAware = true }
        val reader = factory.newSAXParser().xmlReader
        listOf("http://xml.org/sax/features/external-general-entities",
            "http://xml.org/sax/features/external-parameter-entities",
            "http://apache.org/xml/features/nonvalidating/load-external-dtd").forEach { feature ->
            try { reader.setFeature(feature, false) } catch (_: SAXException) { /* Source checked above. */ }
        }
        reader.contentHandler = handler
        reader.errorHandler = handler
        reader.setEntityResolver { _, _ -> throw SAXException("不允许外部实体") }
        reader.parse(InputSource(StringReader(text)))
        return handler.routes
    }

    private class GpxHandler : DefaultHandler() {
        val routes = mutableListOf<HistoricalRoute>()
        private val path = mutableListOf<String>()
        private var name = ""
        private var nameText: StringBuilder? = null
        private var segments = mutableListOf<List<Pair<Double, Double>>>()
        private var points: MutableList<Pair<Double, Double>>? = null
        private var totalPoints = 0

        override fun startElement(uri: String, localName: String, qName: String, attributes: Attributes) {
            val tag = localName.ifEmpty { qName }
            require(path.size < 32) { "GPX 层级过深" }
            if (path.isEmpty()) {
                require(tag == "gpx" && uri in setOf("", "http://www.topografix.com/GPX/1/1",
                    "http://www.topografix.com/GPX/1/0")) { "文件不是有效的 GPX 路线" }
            }
            // Ignore extension namespaces even when they reuse GPX element names.
            path.add(if (uri.isEmpty() || uri.startsWith("http://www.topografix.com/GPX/1/")) tag else "#extension")
            when (path.joinToString("/")) {
                "gpx/trk", "gpx/rte" -> { name = ""; segments = mutableListOf() }
                "gpx/trk/name", "gpx/rte/name" -> { nameText = StringBuilder() }
                "gpx/trk/trkseg" -> { points = mutableListOf() }
            }
            // rte starts both the route container and its point list.
            if (path.joinToString("/") == "gpx/rte") points = mutableListOf()
            if (path.joinToString("/") in setOf("gpx/trk/trkseg/trkpt", "gpx/rte/rtept")) {
                require(++totalPoints <= MAX_POINTS) { "最多支持 100000 个坐标点" }
                val lat = attributes.getValue("lat")?.toDoubleOrNull()
                val lon = attributes.getValue("lon")?.toDoubleOrNull()
                require(lat != null && lon != null) { "GPX 坐标点缺少有效的 lat 或 lon" }
                checkPoint(lat, lon)
                points!!.add(lat to lon)
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            nameText?.let {
                require(it.length + length <= 1024) { "GPX 路线名称过长" }
                it.append(ch, start, length)
            }
        }

        override fun endElement(uri: String, localName: String, qName: String) {
            when (path.joinToString("/")) {
                "gpx/trk/name", "gpx/rte/name" -> { name = nameText.toString().trim(); nameText = null }
                "gpx/trk/trkseg" -> { points?.takeIf { it.isNotEmpty() }?.let { segments.add(it) }; points = null }
                "gpx/rte" -> { points?.takeIf { it.isNotEmpty() }?.let { segments.add(it) }; finishRoute() }
                "gpx/trk" -> finishRoute()
            }
            path.removeAt(path.lastIndex)
        }

        private fun finishRoute() {
            segments.forEachIndexed { index, segment ->
                val base = name.ifBlank { "导入路线 ${routes.size + 1}" }
                val suffix = if (segments.size > 1) " (分段 ${index + 1})" else ""
                routes.add(HistoricalRoute(base.take(MAX_NAME - suffix.length) + suffix, segment))
                require(routes.size <= MAX_ROUTES) { "最多支持 1000 条路线" }
            }
            segments.clear()
            points = null
        }
    }

    private fun escapeXml(value: String) = value.replace("&", "&amp;").replace("<", "&lt;")
        .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
}
