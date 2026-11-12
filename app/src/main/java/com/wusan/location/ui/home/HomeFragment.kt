package com.wusan.location.ui.home

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
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.Toast
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
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
import com.wusan.location.databinding.FragmentHomeBinding
import com.wusan.location.ext.gcj02
import com.wusan.location.ext.mapType
import com.wusan.location.ext.rawHistoricalLocations
import com.wusan.location.ext.selectRoute
import com.wusan.location.ext.wgs84
import com.wusan.location.tmap.TencentGeo
import com.wusan.location.ui.viewmodel.HomeViewModel
import com.wusan.location.ui.viewmodel.MapViewModel
import java.math.BigDecimal
import java.util.List

class HomeFragment : Fragment() {
    private var _binding: FragmentHomeBinding? = null

    private val binding get() = _binding!!

    private val homeViewModel by viewModels<HomeViewModel>()
    private val mapViewModel by activityViewModels<MapViewModel>()

    private var locationManager: LocationManager? = null
    private val locationListener = LocationListener { loc -> onLocationChanged(loc) }

    @SuppressLint("SetTextI18n")
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        val root: View = binding.root

        homeViewModel.mFabOpened = false

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
                    markMap()
                    reverseGeoCode(loc)
                }
            })

            setOnMapLongClickListener { loc ->
                if (loc == null) return@setOnMapLongClickListener
                mapViewModel.markedLoc = loc.wgs84
                mapViewModel.showDetailView = true
                markMap()
                reverseGeoCode(loc)
            }

            binding.mapTypeGroup.check(
                when (mapType) {
                    TencentMap.MAP_TYPE_SATELLITE -> R.id.map_type_satellite
                    else -> R.id.map_type_normal
                }
            )
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
                    Log.e("HomeFragment", "Unknown location view mode: $checkedId")
                }
            }
            context?.mapType = binding.bmapView.map.mapType
        }

        binding.fab.setOnClickListener { view ->
            val subFabList = arrayOf(
                binding.fabMyLocation,
                binding.fabGoto,
                binding.fabAdd
            )

            if (!homeViewModel.mFabOpened) {
                homeViewModel.mFabOpened = true

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
                homeViewModel.mFabOpened = false

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

        binding.fabMyLocation.setOnClickListener {
            val loc = mapViewModel.currentLocation
            if (loc == null) {
                Toast.makeText(requireContext(), "尚未获取到定位", Toast.LENGTH_SHORT).show()
            } else {
                binding.bmapView.map.animateCamera(CameraUpdateFactory.newLatLng(loc.gcj02))
            }
        }

        binding.fabGoto.setOnClickListener {
            showInputCoordinatesDialog()
        }

        binding.fabAdd.setOnClickListener {
            if (!showAddLocationDialog()) {
                Toast.makeText(requireContext(), "选择位置异常", Toast.LENGTH_SHORT).show()
            }
        }

        binding.showRoute.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                requireContext().selectRoute?.route?.let {
                    previewRoute(it)
                    binding.bmapView.map.moveCamera(CameraUpdateFactory.newLatLng(it.first().gcj02))
                }
            } else {
                binding.bmapView.map.clearAllOverlays()
            }
        }

        return root
    }

    private fun reverseGeoCode(loc: LatLng) {
        lifecycleScope.launch {
            TencentGeo.reverseGeoCode(requireContext(), loc) { address ->
                mapViewModel.markName = address
            }
        }
    }

    private fun onLocationChanged(loc: Location) {
        val wgs = loc.latitude to loc.longitude
        mapViewModel.currentLocation = wgs

        if (mapViewModel.followLocation && _binding != null) {
            binding.bmapView.map.animateCamera(CameraUpdateFactory.newLatLng(wgs.gcj02))
        }
    }

    private fun startLocation() {
        val manager = requireContext()
            .getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        locationManager = manager
        runCatching {
            manager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                1000L,
                0f,
                locationListener
            )
            manager.requestLocationUpdates(
                LocationManager.NETWORK_PROVIDER,
                1000L,
                0f,
                locationListener
            )
        }.onFailure {
            Log.w("HomeFragment", "requestLocationUpdates failed", it)
        }
    }

    private fun previewRoute(points: kotlin.collections.List<Pair<Double, Double>>) {
        val map = binding.bmapView.map
        map.clearAllOverlays()

        for (i in 0 until points.size - 1) {
            map.addPolyline(
                PolylineOptions()
                    .color(Color.argb(178, 0, 78, 255))
                    .width(10f)
                    .add(points[i].gcj02, points[i + 1].gcj02)
            )
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.bmapView.onStart()
    }

    @SuppressLint("SetTextI18n", "MissingInflatedId", "MutatingSharedPrefs")
    private fun showAddLocationDialog(): Boolean {
        fun checkLatLon(lat: Double?, lon: Double?): Boolean {
            return (lat != null && lon != null) && lat in -90.0..90.0 && lon in -180.0..180.0
        }

        val inflater = LayoutInflater.from(requireContext())
        val dialogView = inflater.inflate(R.layout.dialog_add_location, null)
        val editName = dialogView.findViewById<TextInputEditText>(R.id.etLocationName)
        editName.addTextChangedListener {
            if (it.isNullOrBlank()) {
                editName.error = "名称不能为空"
            }
        }
        val editAddress = dialogView.findViewById<TextInputEditText>(R.id.etLocationAddress)
        editAddress.addTextChangedListener {
            if (it.isNullOrBlank()) {
                editAddress.error = "地址不能为空"
            }
        }
        val editLatLon = dialogView.findViewById<TextInputEditText>(R.id.etLocationLatLon)
        editLatLon.addTextChangedListener {
            if (it.isNullOrBlank()) {
                editLatLon.error = "经纬度不能为空"
            } else {
                val latLonArray = it.toString().split(",")
                if (latLonArray.size != 2) {
                    editLatLon.error = "经纬度格式错误"
                } else {
                    val lon = latLonArray[0].trim().toDoubleOrNull()
                    val lat = latLonArray[1].trim().toDoubleOrNull()
                    if (!checkLatLon(lat, lon)) {
                        editLatLon.error = "经纬度格式错误"
                    }
                }
            }
        }

        with(mapViewModel) {
            if (markedLoc == null) {
                currentLocation?.let {
                    showDetailView = false
                    TencentGeo.reverseGeoCode(
                        requireContext(),
                        it.gcj02
                    ) { address -> markName = address }
                    markedLoc = it
                }
                editName.setText("当前位置-" + System.currentTimeMillis())
            } else {
                editName.setText("标点位置-" + System.currentTimeMillis())
            }

            val lat = BigDecimal.valueOf(markedLoc?.first ?: return false)
            val lon = BigDecimal.valueOf(markedLoc?.second ?: return false)

            editAddress.setText(markName ?: "位置地址")
            editLatLon.setText("${lon.toPlainString()}, ${lat.toPlainString()}")

            val builder = MaterialAlertDialogBuilder(requireContext())
            builder.setTitle(null)
            builder
                .setCancelable(false)
                .setView(dialogView)
                .setPositiveButton("保存") { _, _ ->
                    val latLonArray = editLatLon.text.toString().split(",")
                    val newLon = latLonArray[0].trim().toDoubleOrNull()
                    val newLat = latLonArray[1].trim().toDoubleOrNull()
                    var name = editName.text?.toString()
                    val address = editAddress.text?.toString()
                    if (name.isNullOrBlank()) {
                        Toast.makeText(requireContext(), "名称不能为空", Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }
                    if (address.isNullOrBlank()) {
                        Toast.makeText(requireContext(), "地址不能为空", Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }
                    if (!checkLatLon(newLat, newLon)) {
                        Toast.makeText(requireContext(), "经纬度格式错误", Toast.LENGTH_SHORT)
                            .show()
                        return@setPositiveButton
                    }

                    fun MutableSet<String>.addLocation(
                        name: String,
                        address: String,
                        lat: Double,
                        lon: Double
                    ): Boolean {
                        if (any { it.split(",")[0] == name }) {
                            return false
                        }
                        add(
                            "$name,$address,${
                                BigDecimal.valueOf(lat).toPlainString()
                            },${BigDecimal.valueOf(lon).toPlainString()}"
                        )
                        return true
                    }

                    with(requireContext()) {
                        val locations = rawHistoricalLocations.toMutableSet()
                        var count = 0
                        while (!locations.addLocation(name!!, address, newLat!!, newLon!!)) {
                            name = "$name(${++count})"
                        }
                        rawHistoricalLocations = locations
                    }

                    Toast.makeText(requireContext(), "位置已保存", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("取消", null)
                .show()
        }

        return true
    }

    @SuppressLint("MissingInflatedId")
    private fun showInputCoordinatesDialog() {
        val inflater = LayoutInflater.from(requireContext())
        val dialogView = inflater.inflate(R.layout.dialog_coordinates, null)

        val latitudeEditText = dialogView.findViewById<TextInputEditText>(R.id.editTextLatitude)
        val longitudeEditText = dialogView.findViewById<TextInputEditText>(R.id.editTextLongitude)

        mapViewModel.currentLocation?.let {
            latitudeEditText.setText(BigDecimal.valueOf(it.first).toPlainString())
            longitudeEditText.setText(BigDecimal.valueOf(it.second).toPlainString())
        }

        latitudeEditText.addTextChangedListener {
            if (it.isNullOrBlank()) {
                latitudeEditText.error = "纬度不能为空"
            } else {
                val lat = it.toString().toDoubleOrNull()
                if (lat == null || lat !in -90.0..90.0) {
                    latitudeEditText.error = "纬度格式错误"
                }
            }
        }

        longitudeEditText.addTextChangedListener {
            if (it.isNullOrBlank()) {
                longitudeEditText.error = "经度不能为空"
            } else {
                val lon = it.toString().toDoubleOrNull()
                if (lon == null || lon !in -180.0..180.0) {
                    longitudeEditText.error = "经度格式错误"
                }
            }
        }

        val builder = MaterialAlertDialogBuilder(requireContext())
        builder.setTitle("输入经纬度(WGS84)")
            .setView(dialogView)
            .setPositiveButton("确定") { _, _ ->
                kotlin.runCatching {
                    val latitude = latitudeEditText.text.toString()
                    val longitude = longitudeEditText.text.toString()

                    if (latitude.isNotEmpty() && longitude.isNotEmpty()) with(mapViewModel) {
                        val lat = latitude.toDoubleOrNull()
                        val lon = longitude.toDoubleOrNull()
                        if (lat == null || lon == null || lat !in -90.0..90.0 || lon !in -180.0..180.0) {
                            throw IllegalArgumentException("Invalid latitude or longitude")
                        }

                        this.markedLoc = lat to lon

                        markMap(true)

                        followLocation = false

                        TencentGeo.reverseGeoCode(
                            requireContext(),
                            (lat to lon).gcj02
                        ) { address -> markName = address }
                    } else {
                        Toast.makeText(requireContext(), "请输入有效的经纬度！", Toast.LENGTH_SHORT)
                            .show()
                    }
                }.onFailure {
                    Toast.makeText(requireContext(), "请输入有效的经纬度！", Toast.LENGTH_SHORT)
                        .show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
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

    override fun onResume() {
        super.onResume()

        if (_binding != null)
            binding.bmapView.onResume()
    }

    override fun onDestroy() {
        super.onDestroy()

        mapViewModel.isExists = false
        runCatching { locationManager?.removeUpdates(locationListener) }
    }

    override fun onPause() {
        super.onPause()

        if (_binding != null) {
            binding.bmapView.onPause()
        }
    }

    override fun onStop() {
        super.onStop()
        if (_binding != null) {
            binding.bmapView.onStop()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
