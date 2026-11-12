package com.wusan.location.ui.mock

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/** Holds document content across rotation; no route lists are placed in a Bundle. */
class RouteTransferViewModel(application: Application) : AndroidViewModel(application) {
    data class SaveRequest(val format: RouteFileCodec.Format, val fileName: String)
    data class State(
        val busy: Boolean = false,
        val routes: List<HistoricalRoute> = emptyList(),
        val message: String? = null,
        val saveRequest: SaveRequest? = null
    )
    private val context get() = getApplication<Application>()
    private val mutableState = MutableStateFlow(State())
    val state = mutableState.asStateFlow()
    private var pendingExport: String? = null

    fun refresh() {
        if (mutableState.value.busy) return
        runOperation {
            val routes = withContext(Dispatchers.IO) { RouteStore.read(context, initialize = true) }
            mutableState.value = mutableState.value.copy(routes = routes.sortedBy { it.name })
        }
    }

    fun beginImport(): Boolean {
        if (mutableState.value.busy) return false
        mutableState.value = mutableState.value.copy(busy = true)
        return true
    }

    fun importDocument(uri: Uri?) {
        if (uri == null) { finish(); return }
        runOperation {
            val result = withContext(Dispatchers.IO) {
                val routes = (context.contentResolver.openInputStream(uri)
                    ?: throw IOException("无法打开路线文件")).use { RouteFileCodec.decode(it) }
                RouteStore.import(context, routes)
            }
            mutableState.value = mutableState.value.copy(
                routes = result.routes.sortedBy { it.name },
                message = "已导入 ${result.added} 条路线，跳过 ${result.duplicates} 条重复路线，重命名 ${result.renamed} 条"
            )
        }
    }

    fun prepareExport(route: HistoricalRoute?, format: RouteFileCodec.Format) {
        if (mutableState.value.busy) return
        // Snapshot the selected route before opening the system document picker.
        val selected = route?.copy(route = route.route.toList())
        runOperation(keepBusy = true) {
            pendingExport = withContext(Dispatchers.IO) {
                val routes = selected?.let { listOf(it) } ?: RouteStore.read(context)
                require(routes.isNotEmpty()) { "没有可导出的路线" }
                RouteFileCodec.encode(routes, format)
            }
            val base = selected?.name?.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
                ?.trim()?.take(60)?.takeIf { it.isNotEmpty() } ?: "location-routes"
            val extension = if (format == RouteFileCodec.Format.JSON) "json" else "gpx"
            mutableState.value = mutableState.value.copy(saveRequest = SaveRequest(format, "$base.$extension"))
        }
    }

    fun consumeSaveRequest() {
        mutableState.value = mutableState.value.copy(saveRequest = null)
    }

    fun exportDocument(uri: Uri?) {
        if (uri == null) { pendingExport = null; finish(); return }
        val content = pendingExport
        if (content == null) { fail("导出状态已失效，请重新导出"); return }
        runOperation {
            withContext(Dispatchers.IO) {
                (context.contentResolver.openOutputStream(uri, "wt")
                    ?: throw IOException("无法写入所选文件")).use { stream ->
                    stream.write(content.toByteArray(Charsets.UTF_8))
                    stream.flush()
                }
            }
            pendingExport = null
            mutableState.value = mutableState.value.copy(message = "路线导出成功")
        }
    }

    fun delete(route: HistoricalRoute, onDeleted: (List<HistoricalRoute>) -> Unit) {
        if (mutableState.value.busy) return
        runOperation {
            val routes = withContext(Dispatchers.IO) { RouteStore.delete(context, route) }
            mutableState.value = mutableState.value.copy(routes = routes.sortedBy { it.name }, message = "已删除路线")
            onDeleted(routes)
        }
    }

    fun acknowledgeMessage() { mutableState.value = mutableState.value.copy(message = null) }

    fun fail(message: String) {
        pendingExport = null
        mutableState.value = mutableState.value.copy(busy = false, saveRequest = null, message = message)
    }

    private fun finish() { mutableState.value = mutableState.value.copy(busy = false) }

    private fun runOperation(keepBusy: Boolean = false, block: suspend () -> Unit) {
        mutableState.value = mutableState.value.copy(busy = true)
        viewModelScope.launch {
            var succeeded = false
            try {
                block()
                succeeded = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(when (e) {
                    is IllegalArgumentException, is IOException -> e.message ?: "路线文件处理失败"
                    is SecurityException -> "无法访问文件，请重新选择有读写权限的文件"
                    else -> "路线文件处理失败，请检查文件内容与存储空间"
                })
            } finally {
                if (!keepBusy || !succeeded) finish()
            }
        }
    }
}
