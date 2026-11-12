package com.wusan.location.ui.mock

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.Toast
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.alibaba.fastjson2.JSON
import com.alibaba.fastjson2.JSONArray
import com.alibaba.fastjson2.JSONObject
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.tencent.tencentmap.mapsdk.maps.CameraUpdateFactory
import com.tencent.tencentmap.mapsdk.maps.TencentMap
import com.tencent.tencentmap.mapsdk.maps.model.LatLng
import com.tencent.tencentmap.mapsdk.maps.model.MarkerOptions
import com.tencent.tencentmap.mapsdk.maps.model.PolylineOptions
import kotlinx.coroutines.launch
import com.wusan.location.MainActivity
import com.wusan.location.R
import com.wusan.location.databinding.FragmentRouteEditBinding
import com.wusan.location.ext.gcj02
import com.wusan.location.ext.jsonHistoricalRoutes
import com.wusan.location.ext.mapType
import com.wusan.location.ext.wgs84
import com.wusan.location.tmap.TencentGeo
import com.wusan.location.ui.viewmodel.HomeViewModel
import com.wusan.location.ui.viewmodel.MapViewModel
import java.math.BigDecimal


class RouteEditFragment : Fragment() {
    private var _binding: FragmentRouteEditBinding? = null
    private val binding get() = _binding!!

    private val routeEditViewModel by viewModels<HomeViewModel>()
    private val mapViewModel by activityViewModels<MapViewModel>()

    private var mPoints: ArrayList<Pair<Double, Double>> = arrayListOf()
    private var isDrawing = false
    private var lastPoint: Pair<Double, Double>? = null

    private var locationManager: LocationManager? = null
    private val locationListener = LocationListener { loc -> onLocationChanged(loc) }


    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentRouteEditBinding.inflate(inflater, container, false)

        with(mapViewModel) {
            isExists = true
            tencentMap = binding.bmapView.map
        }

        with(binding.bmapView.map) {
            moveCamera(CameraUpdateFactory.zoomTo(19f))
            setMapType(context?.mapType ?: TencentMap.MAP_TYPE_NORMAL)

            uiSettings.isCompassEnabled = true
            uiSettings.isZoomControlsEnabled = true
            uiSettings.isScaleViewEnabled = true

            setOnMapClickListener(object : TencentMap.OnMapClickListener {
                override fun onMapClick(loc: LatLng?) {
                    if (loc == null) return
                    mapViewModel.markedLoc = loc.wgs84
                    mapViewModel.showDetailView = false
                    reverseGeoCode(loc)
                    markMap()
                }
            })

            setOnMapLongClickListener { loc ->
                if (loc == null) return@setOnMapLongClickListener
                mapViewModel.markedLoc = loc.wgs84
                mapViewModel.showDetailView = true
                reverseGeoCode(loc)
                markMap()
            }

            binding.mapTypeGroup.check(
                when (mapType) {
                    TencentMap.MAP_TYPE_SATELLITE -> R.id.map_type_satellite
                    else -> R.id.map_type_normal
                }
            )
        }

        binding.fab.setOnClickListener { view ->
            val subFabList = arrayOf(
                binding.fabStart,
                binding.fabRollback,
                binding.fabComplete,
                binding.fabMyLocation
            )

            routeEditViewModel.mFabOpened = !routeEditViewModel.mFabOpened

            if (routeEditViewModel.mFabOpened) {
                val rotateMainFab = ObjectAnimator.ofFloat(view, "rotation", 0f, 90f)
                rotateMainFab.duration = 200

                val animators = arrayListOf<ObjectAnimator>()
                animators.add(rotateMainFab)
                subFabList.forEachIndexed { index, fab ->
                    fab.visibility = View.VISIBLE
                    fab.alpha = 1f
                    fab.scaleX = 1f
                    fab.scaleY = 1f
                    val translationX =
                        ObjectAnimator.ofFloat(fab, "translationX", 0f, 20f + index * 8f)
                    translationX.duration = 200
                    animators.add(translationX)
                }

                val animatorSet = AnimatorSet()
                animatorSet.playTogether(animators.toList())
                animatorSet.interpolator = DecelerateInterpolator()
                animatorSet.addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        view.isClickable = true
                    }
                })
                view.isClickable = false
                animatorSet.start()
            } else {
                val rotateMainFab = ObjectAnimator.ofFloat(view, "rotation", 90f, 0f)
                rotateMainFab.duration = 200

                val animators = arrayListOf<ObjectAnimator>()
                animators.add(rotateMainFab)
                subFabList.forEachIndexed { index, fab ->
                    val transX = ObjectAnimator.ofFloat(fab, "translationX", 0f, -20f - index * 8f)
                    transX.duration = 150
                    val scaleX = ObjectAnimator.ofFloat(fab, "scaleX", 1f, 0f)
                    scaleX.duration = 200
                    val scaleY = ObjectAnimator.ofFloat(fab, "scaleY", 1f, 0f)
                    scaleY.duration = 200
                    val alpha = ObjectAnimator.ofFloat(fab, "alpha", 1f, 0f)
                    alpha.duration = 200
                    animators.add(transX)
                    animators.add(scaleX)
                    animators.add(scaleY)
                    animators.add(alpha)
                }

                val animatorSet = AnimatorSet()
                animatorSet.playTogether(animators.toList())
                animatorSet.interpolator = DecelerateInterpolator()
                animatorSet.addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        subFabList.forEach { it.visibility = View.GONE }
                        view.isClickable = true
                    }
                })
                view.isClickable = false
                animatorSet.start()
            }
        }

        startLocation()

        binding.mapTypeGroup.setOnCheckedChangeListener { _, checkedId ->
            when (checkedId) {
                R.id.map_type_normal -> {
                    binding.bmapView.map.setMapType(TencentMap.MAP_TYPE_NORMAL)
                }

                R.id.map_type_satellite -> {
                    binding.bmapView.map.setMapType(TencentMap.MAP_TYPE_SATELLITE)
                }

                else -> {
                    Log.e("RouteEditFragment", "Unknown location view mode: $checkedId")
                }
            }
            context?.mapType = binding.bmapView.map.mapType
        }

        // 腾讯地图没有直接的原始触摸回调，这里挂在 MapView 上，返回 false 不影响地图自身手势
        binding.bmapView.setOnTouchListener { _, event ->
            if (isDrawing) {
                val currentPoint = binding.bmapView.map.cameraPosition.target.wgs84

                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        if (mPoints.size <= 0) {
                            mPoints.add(currentPoint)
                        }
                        lastPoint = currentPoint
                    }

                    MotionEvent.ACTION_MOVE -> {
                        if (lastPoint == null) {
                            lastPoint = currentPoint
                        }
                        lastPoint?.let { lp -> drawLine(lp, currentPoint) }
                    }

                    MotionEvent.ACTION_UP -> {
                        mPoints.add(currentPoint)
                        lastPoint = null
                    }
                }
            }
            false
        }

        binding.fabStart.setOnClickListener {
            isDrawing = true
            mPoints = arrayListOf()
            lastPoint = null
        }

        binding.fabRollback.setOnClickListener {
            if (mPoints.size > 0) {
                mPoints.removeAt(mPoints.size - 1)
                refresh()
            }
        }

        binding.fabComplete.setOnClickListener {
            isDrawing = false
            if (!showAddRouteDialog()) {
                Toast.makeText(requireContext(), "选择路线异常", Toast.LENGTH_SHORT).show()
            }
        }

        binding.fabMyLocation.setOnClickListener {
            val loc = mapViewModel.currentLocation
            if (loc == null) {
                Toast.makeText(requireContext(), "尚未获取到定位", Toast.LENGTH_SHORT).show()
            } else {
                binding.bmapView.map.animateCamera(CameraUpdateFactory.newLatLng(loc.gcj02))
            }
        }

        return binding.root
    }

    private fun reverseGeoCode(loc: LatLng) {
        lifecycleScope.launch {
            TencentGeo.reverseGeoCode(requireContext(), loc) { address ->
                mapViewModel.markName = address
            }
        }
    }

    private fun onLocationChanged(loc: Location) {
        mapViewModel.currentLocation = loc.latitude to loc.longitude
    }

    private fun startLocation() {
        val manager = requireContext()
            .getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        locationManager = manager
        runCatching {
            manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, locationListener)
            manager.requestLocationUpdates(
                LocationManager.NETWORK_PROVIDER,
                1000L,
                0f,
                locationListener
            )
        }.onFailure {
            Log.w("RouteEditFragment", "requestLocationUpdates failed", it)
        }
    }

    private fun refresh() {
        val map = binding.bmapView.map
        map.clearAllOverlays()

        for (i in 0 until mPoints.size - 1) {
            map.addPolyline(
                PolylineOptions()
                    .color(Color.argb(178, 0, 78, 255))
                    .width(10f)
                    .add(mPoints[i].gcj02, mPoints[i + 1].gcj02)
            )
        }
    }

    private fun drawLine(start: Pair<Double, Double>, end: Pair<Double, Double>) {
        val map = binding.bmapView.map
        map.clearAllOverlays()

        for (i in 0 until mPoints.size - 1) {
            map.addPolyline(
                PolylineOptions()
                    .color(Color.argb(178, 0, 78, 255))
                    .width(10f)
                    .add(mPoints[i].gcj02, mPoints[i + 1].gcj02)
            )
        }

        map.addPolyline(
            PolylineOptions()
                .color(Color.argb(178, 0, 78, 255))
                .width(10f)
                .add(start.gcj02, end.gcj02)
        )
    }


    private fun markMap(moveEyes: Boolean = false) = with(mapViewModel) {
        val loc = markedLoc!!.gcj02
        val map = binding.bmapView.map
        map.clearAllOverlays()
        map.addMarker(
            MarkerOptions()
                .position(loc)
                .apply { mMapIndicator?.let { icon(it) } }
        )

        if (moveEyes) {
            map.moveCamera(CameraUpdateFactory.newLatLng(loc))
        }
    }

    @SuppressLint("SetTextI18n", "MissingInflatedId", "MutatingSharedPrefs")
    private fun showAddRouteDialog(): Boolean {
        fun checkLatLon(lat: Double?, lon: Double?): Boolean {
            return (lat != null && lon != null) && lat in -90.0..90.0 && lon in -180.0..180.0
        }

        val inflater = LayoutInflater.from(requireContext())
        val dialogView = inflater.inflate(R.layout.dialog_add_route, null)
        val editName = dialogView.findViewById<TextInputEditText>(R.id.etRouteName)
        editName.addTextChangedListener {
            if (it.isNullOrBlank()) {
                editName.error = "名称不能为空"
            }
        }
        val editRoute = dialogView.findViewById<TextInputEditText>(R.id.etRouteSet)
        editRoute.addTextChangedListener {
            if (it.isNullOrBlank()) {
                editRoute.error = "路线经纬度不能为空"
            } else {
                try {
                    val json = it.toString()
                    val points = JSON.parseArray(json)
                    if (points.size < 2) {
                        editRoute.error = "路线经纬度至少需要两个点"
                    }
                    for (point in points) {
                        val jsonObject = point as JSONObject
                        val latitude = jsonObject.getDouble("first")
                        val longitude = jsonObject.getDouble("second")
                        if (!checkLatLon(latitude, longitude)) {
                            editRoute.error = "路线经纬度格式错误"
                            return@addTextChangedListener
                        }
                    }
                } catch (e: Exception) {
                    editRoute.error = "路线经纬度json格式错误"
                }
            }
        }

        editRoute.setText(JSON.toJSONString(mPoints))

        val builder = MaterialAlertDialogBuilder(requireContext())
        builder.setTitle(null)
        builder
            .setCancelable(false)
            .setView(dialogView)
            .setPositiveButton("保存") { _, _ ->
                val routeJson = editRoute.text.toString()

                var name = editName.text?.toString()
                if (name.isNullOrBlank()) {
                    Toast.makeText(requireContext(), "名称不能为空", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val points = JSON.parseArray(routeJson)
                if (points.size < 2) {
                    Toast.makeText(requireContext(), "路线经纬度至少需要两个点", Toast.LENGTH_SHORT)
                        .show()
                    return@setPositiveButton
                }

                for (point in points) {
                    val jsonObject = point as JSONObject
                    val latitude = jsonObject.getDouble("first")
                    val longitude = jsonObject.getDouble("second")
                    if (!checkLatLon(latitude, longitude)) {
                        Toast.makeText(requireContext(), "路线经纬度格式错误", Toast.LENGTH_SHORT)
                            .show()
                        return@setPositiveButton
                    }
                }

                with(requireContext()) {
                    val routes = jsonHistoricalRoutes
                    val jsonArray: JSONArray = if (routes.isNotEmpty()) {
                        JSON.parseArray(routes)
                    } else {
                        JSONArray()
                    }
                    val historicalRoute = HistoricalRoute(name, mPoints)
                    jsonArray.add(historicalRoute)
                    jsonArray.toJSONString().also {
                        jsonHistoricalRoutes = it
                    }
                }

                Toast.makeText(requireContext(), "路线已保存", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()

        return true
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.bmapView.onStart()
    }

    override fun onResume() {
        super.onResume()
        if (_binding != null) binding.bmapView.onResume()
    }

    override fun onPause() {
        super.onPause()
        if (_binding != null) binding.bmapView.onPause()
    }

    override fun onStop() {
        super.onStop()
        if (_binding != null) binding.bmapView.onStop()
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { locationManager?.removeUpdates(locationListener) }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
