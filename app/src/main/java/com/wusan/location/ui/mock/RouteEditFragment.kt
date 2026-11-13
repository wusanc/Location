package com.wusan.location.ui.mock

import android.graphics.Color
import android.location.Location
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.alibaba.fastjson2.JSON
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.tencent.tencentmap.mapsdk.maps.CameraUpdateFactory
import com.tencent.tencentmap.mapsdk.maps.TencentMap
import com.tencent.tencentmap.mapsdk.maps.model.CameraPosition
import com.tencent.tencentmap.mapsdk.maps.model.MyLocationStyle
import com.tencent.tencentmap.mapsdk.maps.model.TencentMapGestureListener
import com.tencent.tencentmap.mapsdk.maps.model.Polyline
import com.tencent.tencentmap.mapsdk.maps.model.Marker
import com.tencent.tencentmap.mapsdk.maps.model.MarkerOptions
import com.tencent.tencentmap.mapsdk.maps.model.PolylineOptions
import com.wusan.location.R
import com.wusan.location.databinding.FragmentRouteEditBinding
import com.wusan.location.ext.gcj02
import com.wusan.location.ext.mapType
import com.wusan.location.ext.wgs84
import com.wusan.location.tmap.MapLocationSource
import com.wusan.location.ui.viewmodel.MapViewModel
import com.wusan.location.ui.viewmodel.RouteDraftViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RouteEditFragment : Fragment() {
    private var _binding: FragmentRouteEditBinding? = null
    private val binding get() = _binding!!
    private val mapViewModel by activityViewModels<MapViewModel>()
    private val draft by viewModels<RouteDraftViewModel>()
    private var locationSource: MapLocationSource? = null
    private var gestureListener: TencentMapGestureListener? = null
    private var routeLine: Polyline? = null
    private var previewLine: Polyline? = null
    private var startMarker: Marker? = null
    private var endMarker: Marker? = null
    private var editorDialog: AlertDialog? = null
    private var centered = false
    private var menuOpen = true

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentRouteEditBinding.inflate(inflater, container, false)
        val map = binding.bmapView.map
        centered = draft.cameraPosition != null || draft.points.isNotEmpty() ||
            mapViewModel.markedLoc != null || mapViewModel.currentLocation != null
        mapViewModel.attach(map)
        map.moveCamera(CameraUpdateFactory.zoomTo(19f))
        map.setMapType(requireContext().mapType)
        map.uiSettings.isCompassEnabled = true
        map.uiSettings.isZoomControlsEnabled = true
        map.uiSettings.isScaleViewEnabled = true
        // Location updates may move the blue dot, but must never take over the camera.
        map.setMyLocationStyle(MyLocationStyle()
            .myLocationType(MyLocationStyle.LOCATION_TYPE_LOCATION_ROTATE_NO_CENTER))
        // Consume endpoint taps so the SDK does not pan back to the route start marker.
        map.setOnMarkerClickListener { marker ->
            marker?.showInfoWindow()
            true
        }
        gestureListener = object : TencentMapGestureListener {
            override fun onDown(x: Float, y: Float): Boolean {
                centered = true
                map.stopAnimation()
                return false // The SDK still handles the gesture normally.
            }
            override fun onDoubleTap(x: Float, y: Float) = false
            override fun onSingleTap(x: Float, y: Float) = false
            override fun onFling(x: Float, y: Float) = false
            override fun onScroll(x: Float, y: Float) = false
            override fun onLongPress(x: Float, y: Float) = false
            override fun onUp(x: Float, y: Float) = false
            override fun onTwoFingerMoveAgainst(
                status: TencentMapGestureListener.TwoFingerMoveAgainstStatus,
                position: CameraPosition
            ) = false
            override fun onMapStable() = Unit
        }.also { map.addTencentMapGestureListener(it) }
        // Moving the map only previews the next point. Only the add button commits it.
        map.setOnCameraChangeListener(object : TencentMap.OnCameraChangeListener {
            override fun onCameraChange(position: CameraPosition) {
                if (_binding != null) {
                    centered = true
                    refreshCandidate()
                }
            }
            override fun onCameraChangeFinished(position: CameraPosition) {
                if (_binding != null) refreshCandidate()
            }
        })
        val initial = draft.points.lastOrNull() ?: mapViewModel.markedLoc ?: mapViewModel.currentLocation
        val restoredCamera = draft.cameraPosition
        if (restoredCamera != null) {
            map.moveCamera(CameraUpdateFactory.newCameraPosition(restoredCamera))
        } else initial?.let { map.moveCamera(CameraUpdateFactory.newLatLng(it.gcj02)) }
        refresh()

        locationSource = MapLocationSource(requireContext()) { point ->
            if (_binding != null) {
                mapViewModel.currentLocation = point
                if (!centered && !draft.drawing) {
                    centered = true
                    map.moveCamera(CameraUpdateFactory.newLatLng(point.gcj02))
                }
            }
        }
        map.setLocationSource(locationSource)
        map.isMyLocationEnabled = true
        binding.mapTypeGroup.check(if (map.mapType == TencentMap.MAP_TYPE_SATELLITE)
            R.id.map_type_satellite else R.id.map_type_normal)
        binding.mapTypeGroup.setOnCheckedChangeListener { _, id ->
            val type = if (id == R.id.map_type_satellite) TencentMap.MAP_TYPE_SATELLITE else TencentMap.MAP_TYPE_NORMAL
            map.setMapType(type)
            requireContext().mapType = type
        }
        binding.fab.setOnClickListener {
            menuOpen = !menuOpen
            listOf(binding.fabStart, binding.fabRollback, binding.fabComplete, binding.fabMyLocation).forEach {
                it.visibility = if (menuOpen) View.VISIBLE else View.GONE
            }
            binding.fab.rotation = if (menuOpen) 90f else 0f
        }
        menuOpen = true
        binding.fabStart.setOnClickListener {
            if (draft.points.isEmpty()) resetDraft()
            else MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.route_edit_reset)
                .setMessage(R.string.route_edit_reset_confirm)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.route_edit_reset) { _, _ -> resetDraft() }
                .show()
        }
        binding.btnAddPoint.setOnClickListener {
            centered = true
            appendPoint(map.cameraPosition.target.wgs84)
        }
        binding.fabRollback.setOnClickListener {
            draft.points.removeLastOrNull()
            refresh()
        }
        binding.fabComplete.setOnClickListener { showRouteDialog() }
        binding.fabMyLocation.setOnClickListener {
            mapViewModel.currentLocation?.let {
                centered = true
                map.animateCamera(CameraUpdateFactory.newLatLng(it.gcj02))
            } ?: toast("尚未获取到定位")
        }
        refreshCandidate()
        return binding.root
    }

    private fun resetDraft() {
        draft.points.clear()
        draft.drawing = false
        centered = true
        mapViewModel.clearMarker()
        refresh()
    }

    private fun appendPoint(point: Pair<Double, Double>) {
        if (!point.first.isFinite() || !point.second.isFinite() ||
            point.first !in -90.0..90.0 || point.second !in -180.0..180.0) return
        draft.points.lastOrNull()?.let { last ->
            if (distance(last, point) < 0.05f) {
                toast(getString(R.string.route_edit_duplicate))
                return
            }
        }
        if (draft.points.size >= RouteFileCodec.MAX_POINTS) {
            toast("路线已达到 ${RouteFileCodec.MAX_POINTS} 个点，请完成保存")
            return
        }
        draft.drawing = true
        draft.points.add(point)
        refresh()
    }

    private fun distance(a: Pair<Double, Double>, b: Pair<Double, Double>): Float {
        val result = FloatArray(1)
        Location.distanceBetween(a.first, a.second, b.first, b.second, result)
        return result[0]
    }

    private fun refresh() {
        val map = _binding?.bmapView?.map ?: return
        val points = draft.points.map { it.gcj02 }
        if (points.size < 2) {
            routeLine?.remove()
            routeLine = null
        } else if (routeLine == null) {
            routeLine = map.addPolyline(PolylineOptions()
                .color(Color.argb(220, 0, 78, 255)).width(10f).addAll(points))
        } else routeLine?.points = points

        if (points.isEmpty()) {
            startMarker?.remove()
            startMarker = null
        } else if (startMarker == null) {
            startMarker = map.addMarker(MarkerOptions().position(points.first())
                .title(getString(R.string.route_edit_start_point)))
        } else startMarker?.position = points.first()

        if (points.size < 2) {
            endMarker?.remove()
            endMarker = null
        } else if (endMarker == null) {
            endMarker = map.addMarker(MarkerOptions().position(points.last())
                .title(getString(R.string.route_edit_last_point)))
        } else endMarker?.position = points.last()
        refreshCandidate()
    }

    private fun refreshCandidate() {
        val current = _binding ?: return
        val map = current.bmapView.map
        val candidate = map.cameraPosition.target.wgs84
        val count = draft.points.size
        current.btnAddPoint.isEnabled = count < RouteFileCodec.MAX_POINTS
        current.fabRollback.isEnabled = count > 0
        val last = draft.points.lastOrNull()
        if (last == null || distance(last, candidate) < 0.05f) {
            previewLine?.remove()
            previewLine = null
        } else {
            val segment = listOf(last.gcj02, candidate.gcj02)
            if (previewLine == null) previewLine = map.addPolyline(PolylineOptions()
                .color(Color.argb(170, 255, 145, 0)).width(6f).addAll(segment))
            else previewLine?.points = segment
        }
    }

    private fun showRouteDialog() {
        if (editorDialog?.isShowing == true) return
        if (draft.points.size < 2) { toast(getString(R.string.route_edit_need_points)); return }
        val view = layoutInflater.inflate(R.layout.dialog_add_route, null)
        val nameInput = view.findViewById<TextInputEditText>(R.id.etRouteName)
        val routeInput = view.findViewById<TextInputEditText>(R.id.etRouteSet)
        routeInput.setText(JSON.toJSONString(draft.points))
        nameInput.addTextChangedListener { nameInput.error = null }
        routeInput.addTextChangedListener { routeInput.error = null }
        val dialog = MaterialAlertDialogBuilder(requireContext()).setView(view)
            .setPositiveButton("保存", null).setNegativeButton(android.R.string.cancel, null).create()
        editorDialog = dialog
        dialog.setOnDismissListener { if (editorDialog === dialog) editorDialog = null }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = nameInput.text?.toString()?.trim().orEmpty()
                if (name.isBlank() || name.length > 120 || name.any { it.code < 32 }) {
                    nameInput.error = "名称须为 1～120 个字符，不能包含控制字符"
                    return@setOnClickListener
                }
                val text = routeInput.text?.toString().orEmpty()
                val appContext = requireContext().applicationContext
                viewLifecycleOwner.lifecycleScope.launch {
                    val saveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                    val cancelButton = dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
                    saveButton.isEnabled = false
                    cancelButton.isEnabled = false
                    dialog.setCancelable(false)
                    routeInput.isEnabled = false
                    nameInput.isEnabled = false
                    try {
                        val (savedRoute, result) = withContext(Dispatchers.IO) {
                            require(text.toByteArray(Charsets.UTF_8).size <= RouteFileCodec.MAX_BYTES) { "路线内容不能超过 ${RouteFileCodec.MAX_FILE_MB} MB" }
                            val points = try { JSON.parseArray(text) }
                                catch (e: Exception) { throw IllegalArgumentException("路线 JSON 格式错误，请检查后重试", e) }
                            require(points != null && points.size >= 2) { "路线至少需要两个坐标点" }
                            val raw = JSON.toJSONString(mapOf("name" to name, "route" to points))
                            val route = RouteFileCodec.decode(raw.byteInputStream()).single()
                            // Save the exact validated editor contents; use the same locked store as import.
                            route to RouteStore.import(appContext, listOf(route))
                        }
                        draft.drawing = false
                        draft.points.clear()
                        draft.points.addAll(savedRoute.route)
                        refresh()
                        toast(if (result.added == 0) "相同路线已存在，未重复保存" else
                            if (result.renamed > 0) "路线已保存，同名路线已自动重命名" else "路线已保存")
                        dialog.dismiss()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        routeInput.error = e.message ?: "路线保存失败，请检查坐标和存储空间"
                    } finally {
                        if (dialog.isShowing) {
                            saveButton.isEnabled = true
                            cancelButton.isEnabled = true
                            dialog.setCancelable(true)
                            routeInput.isEnabled = true
                            nameInput.isEnabled = true
                        }
                    }
                }
            }
        }
        dialog.show()
    }

    override fun onStart() {
        super.onStart()
        _binding?.bmapView?.onStart()
        locationSource?.start()
    }
    override fun onResume() { super.onResume(); _binding?.bmapView?.onResume() }
    override fun onPause() { _binding?.bmapView?.onPause(); super.onPause() }
    override fun onStop() {
        locationSource?.stop()
        _binding?.bmapView?.onStop()
        super.onStop()
    }
    override fun onDestroyView() {
        editorDialog?.dismiss()
        editorDialog = null
        locationSource?.close()
        locationSource = null
        _binding?.bmapView?.let { view ->
            draft.cameraPosition = view.map.cameraPosition
            gestureListener?.let { view.map.removeTencentMapGestureListener(it) }
            view.map.setOnCameraChangeListener(null)
            view.map.setOnMarkerClickListener(null)
            view.map.isMyLocationEnabled = false
            mapViewModel.detach(view.map)
            view.onDestroy()
        }
        gestureListener = null
        routeLine = null
        previewLine = null
        startMarker = null
        endMarker = null
        _binding = null
        super.onDestroyView()
    }
    private fun toast(text: String) { context?.let { Toast.makeText(it, text, Toast.LENGTH_SHORT).show() } }
}
