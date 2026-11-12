package com.wusan.location.ui.mock

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.os.Bundle
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AlertDialog
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.slider.Slider
import com.wusan.location.databinding.DialogRoutePaceBinding
import com.wusan.location.databinding.DialogInputBinding
import com.wusan.location.service.StepSimulationSettings
import com.wusan.location.service.RouteCadence
import kotlin.math.roundToInt
import java.util.Locale
import android.view.animation.DecelerateInterpolator
import android.widget.CheckedTextView
import android.widget.Toast
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.Navigation
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.wusan.location.R
import com.wusan.location.android.root.ShellUtils
import com.wusan.location.android.window.OverlayUtils
import com.wusan.location.databinding.FragmentRouteMockBinding
import com.wusan.location.ext.altitude
import com.wusan.location.ext.drawOverOtherAppsEnabled
import com.wusan.location.ext.hookSensor
import com.wusan.location.ext.needOpenSELinux
import com.wusan.location.ext.selectRoute
import com.wusan.location.ext.speed
import com.wusan.location.service.MockServiceHelper
import com.wusan.location.service.MockRuntime
import com.wusan.location.service.RuntimeDiagnostics
import com.wusan.location.ui.viewmodel.HomeViewModel
import com.wusan.location.ui.viewmodel.MockServiceViewModel
import moe.wusan.xposed.utils.FakeLoc
import androidx.navigation.findNavController

class RouteMockFragment : Fragment() {
    private var _binding: FragmentRouteMockBinding? = null
    private val binding get() = _binding!!
    private var paceDialog: AlertDialog? = null
    private var cadenceDialog: AlertDialog? = null
    private var routeActionsDialog: AlertDialog? = null
    private var diagnosticsDialog: AlertDialog? = null
    private var diagnosticsJob: Job? = null
    private var diagnosticsText: String? = null
    private var routeAdapter: HistoricalRouteAdapter? = null
    private var displayedRoutes: List<HistoricalRoute> = emptyList()
    private val transferViewModel by viewModels<RouteTransferViewModel>()
    private val importDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) {
        transferViewModel.importDocument(it)
    }
    private val exportJsonDocument = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) {
        transferViewModel.exportDocument(it)
    }
    private val exportGpxDocument = registerForActivityResult(ActivityResultContracts.CreateDocument("application/gpx+xml")) {
        transferViewModel.exportDocument(it)
    }

    private val routeMockViewModel by viewModels<HomeViewModel>()
    private val mockServiceViewModel by activityViewModels<MockServiceViewModel>()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentRouteMockBinding.inflate(inflater, container, false)

        if (mockServiceViewModel.isServiceStart()) {
            binding.switchMock.text = "停止模拟"
            ContextCompat.getDrawable(requireContext(), R.drawable.rounded_play_disabled_24)?.let {
                binding.switchMock.icon = it
            }
        }

        binding.switchMock.setOnClickListener {
            if (mockServiceViewModel.isServiceStart()) {
                tryCloseService(it as MaterialButton)
            } else {
                tryOpenService(it as MaterialButton)
            }
        }

        with(mockServiceViewModel) {
            if (rocker.isStart) {
                binding.rocker.toggle()
            }
            binding.rocker.setOnClickListener {
                if (locationManager == null) {
                    Toast.makeText(requireContext(), "定位服务加载异常", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (!isServiceStart()) {
                    Toast.makeText(requireContext(), "请先启动模拟", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val checkedTextView = it as CheckedTextView
                checkedTextView.toggle()

                if (!requireContext().drawOverOtherAppsEnabled()) {
                    Toast.makeText(requireContext(), "请授权悬浮窗权限", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                lifecycleScope.launch(Dispatchers.Main) {
                    if (checkedTextView.isChecked) {
                        rocker.show()
                    } else {
                        stopMovement()
                        rocker.hide()
                    }
                }
            }

        }

        requireContext().selectRoute?.let {
            binding.mockRouteName.text = it.name
            mockServiceViewModel.selectedRoute = it
        }

        setupRouteSteps()
        refreshPaceControls()
        binding.routePaceSlider.setLabelFormatter { RoutePace.format(it.toInt()) }
        var draggingPace = false
        binding.routePaceSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                binding.routePaceValue.text = getString(R.string.route_pace_value, RoutePace.format(value.toInt()))
                // Keyboard and accessibility actions do not have a touch-release callback.
                if (!draggingPace) savePace(value.toInt())
            }
        }
        binding.routePaceSlider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) { draggingPace = true }
            override fun onStopTrackingTouch(slider: Slider) {
                draggingPace = false
                savePace(slider.value.toInt())
            }
        })
        binding.routePaceValue.setOnClickListener { showPaceDialog() }

        binding.fab.setOnClickListener { view ->
            val subFabList = arrayOf(
                binding.fabAddRoute
            )

            if (!routeMockViewModel.mFabOpened) {
                routeMockViewModel.mFabOpened = true

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
                routeMockViewModel.mFabOpened = false

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

        binding.fabAddRoute.setOnClickListener {
            activity?.findNavController(R.id.nav_host_fragment_content_main)?.navigate(R.id.nav_route_edit)
        }

        setupRouteList()
        binding.routeImport.setOnClickListener {
            if (transferViewModel.beginImport()) {
                try {
                    importDocument.launch(arrayOf("*/*"))
                } catch (_: Exception) {
                    transferViewModel.fail("无法打开文件选择器，请检查系统文件应用")
                }
            }
        }
        binding.routeExport.setOnClickListener { showExportOptions() }
        binding.runtimeDiagnostics.setOnClickListener { showRuntimeDiagnostics() }

        return binding.root
    }


    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                transferViewModel.state.collect { state ->
                    val currentBinding = _binding ?: return@collect
                    currentBinding.routeImport.isEnabled = !state.busy
                    currentBinding.routeExport.isEnabled = !state.busy
                    currentBinding.fabAddRoute.isEnabled = !state.busy
                    currentBinding.routeTransferProgress.visibility = if (state.busy) View.VISIBLE else View.GONE
                    if (displayedRoutes != state.routes) {
                        displayedRoutes = state.routes
                        routeAdapter?.replaceItems(state.routes)
                    }
                    state.message?.let {
                        Toast.makeText(requireContext(), it, Toast.LENGTH_LONG).show()
                        transferViewModel.acknowledgeMessage()
                    }
                    state.saveRequest?.let { request ->
                        transferViewModel.consumeSaveRequest()
                        try {
                            when (request.format) {
                                RouteFileCodec.Format.JSON -> exportJsonDocument.launch(request.fileName)
                                RouteFileCodec.Format.GPX -> exportGpxDocument.launch(request.fileName)
                            }
                        } catch (_: Exception) {
                            transferViewModel.fail("无法打开文件保存界面，请检查系统文件应用")
                        }
                    }
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                var lastMockEnabled: Boolean? = null
                MockRuntime.state.collect { state ->
                    val currentBinding = _binding ?: return@collect
                    currentBinding.runtimeStatus.text = getString(R.string.runtime_status_inline,
                        RuntimeDiagnostics.phaseLabel(requireContext(), state.phase))
                    if (lastMockEnabled != state.mockEnabled) {
                        lastMockEnabled = state.mockEnabled
                        currentBinding.switchMock.setText(if (state.mockEnabled)
                            R.string.runtime_stop_mock else R.string.runtime_start_mock)
                        currentBinding.switchMock.icon = ContextCompat.getDrawable(requireContext(),
                            if (state.mockEnabled) R.drawable.rounded_play_disabled_24
                            else R.drawable.rounded_play_arrow_24)
                    }
                    currentBinding.rocker.isChecked = MockRuntime.rockerOrNull?.isStart == true
                }
            }
        }
    }

    private fun showRuntimeDiagnostics() {
        if (diagnosticsDialog?.isShowing == true) return
        val panelContext = requireContext()
        val inset = (20 * resources.displayMetrics.density).roundToInt()
        val text = TextView(panelContext).apply {
            setTextIsSelectable(true)
            setTextAppearance(com.google.android.material.R.style.TextAppearance_MaterialComponents_Body2)
            setTextColor(ContextCompat.getColor(panelContext, R.color.wechat_text_primary))
            setPadding(inset, inset / 2, inset, inset / 2)
            setText(R.string.runtime_diagnostic_loading)
        }
        val scroll = ScrollView(panelContext).apply { addView(text) }
        val dialog = MaterialAlertDialogBuilder(panelContext)
            .setTitle(R.string.runtime_diagnostic_title)
            .setView(scroll)
            .setNeutralButton(R.string.runtime_diagnostic_copy, null)
            .setNegativeButton(R.string.runtime_diagnostic_close, null)
            .create()
        diagnosticsDialog = dialog
        diagnosticsText = null
        dialog.setOnDismissListener {
            diagnosticsJob?.cancel()
            diagnosticsJob = null
            diagnosticsText = null
            if (diagnosticsDialog === dialog) diagnosticsDialog = null
        }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val report = diagnosticsText ?: return@setOnClickListener
                val clipboard = panelContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                if (clipboard != null) {
                    clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.runtime_diagnostic_title), report))
                    Toast.makeText(panelContext, R.string.runtime_diagnostic_copied, Toast.LENGTH_SHORT).show()
                }
            }
        }
        dialog.show()
        val owner = viewLifecycleOwner
        diagnosticsJob = owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive && dialog.isShowing) {
                    val snapshot = RuntimeDiagnostics.read(mockServiceViewModel.locationManager)
                    val report = RuntimeDiagnostics.format(panelContext, snapshot)
                    diagnosticsText = report
                    text.text = report
                    delay(1_000)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        transferViewModel.refresh()
    }

    private fun setupRouteList() {
        val adapter = HistoricalRouteAdapter(mutableListOf()) { route, longClick ->
            if (!transferViewModel.state.value.busy) {
                if (longClick) showExportOptions(route) else selectRoute(route)
            }
        }
        routeAdapter = adapter
        displayedRoutes = emptyList()
        binding.historicalRouteList.layoutManager = LinearLayoutManager(requireContext())
        binding.historicalRouteList.adapter = adapter
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
            override fun onMove(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder) = false

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val position = viewHolder.adapterPosition
                if (position == RecyclerView.NO_POSITION) return
                val route = adapter[position]
                // Reset the swiped row even if the dialog is dismissed by Back or rotation.
                adapter.notifyItemChanged(position)
                if (transferViewModel.state.value.busy) return
                routeActionsDialog?.dismiss()
                val appContext = requireContext().applicationContext
                routeActionsDialog = MaterialAlertDialogBuilder(requireContext())
                    .setTitle("删除路线")
                    .setMessage("确定要删除路线（${route.name}）吗？")
                    .setPositiveButton("删除") { _, _ ->
                        transferViewModel.delete(route) { remaining ->
                            if (mockServiceViewModel.selectedRoute == route && route !in remaining) {
                                mockServiceViewModel.stopMovement()
                                mockServiceViewModel.selectedRoute = null
                                appContext.selectRoute = null
                                _binding?.mockRouteName?.setText(R.string.none_route)
                            }
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }).attachToRecyclerView(binding.historicalRouteList)
    }

    private fun selectRoute(route: HistoricalRoute) {
        if (!RouteStepper.isValid(route.route)) {
            showToast("路线为空或坐标不合法，请重新选择")
            return
        }
        mockServiceViewModel.stopMovement()
        binding.mockRouteName.text = route.name
        mockServiceViewModel.selectedRoute = route
        requireContext().selectRoute = route
        val lm = mockServiceViewModel.locationManager
        if (lm != null && MockServiceHelper.isMockStart(lm)) {
            val first = route.route.first()
            lifecycleScope.launch {
                val success = mockServiceViewModel.placeLocation(first.first, first.second)
                showToast(if (success) "位置更新成功" else "更新位置失败")
            }
        }
    }

    private fun showExportOptions(route: HistoricalRoute? = null) {
        if (transferViewModel.state.value.busy) return
        val choices = if (route == null) arrayOf("导出全部路线（JSON）", "导出当前路线（JSON）",
            "导出全部路线（GPX）", "导出当前路线（GPX）")
            else arrayOf("导出此路线（JSON）", "导出此路线（GPX）")
        routeActionsDialog?.dismiss()
        routeActionsDialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.route_export)
            .setItems(choices) { _, index ->
                val selected = route ?: if (index % 2 == 1) mockServiceViewModel.selectedRoute else null
                if (route == null && index % 2 == 1 && selected == null) {
                    showToast("请先选择一条路线")
                } else {
                    val gpx = if (route == null) index >= 2 else index == 1
                    transferViewModel.prepareExport(selected,
                        if (gpx) RouteFileCodec.Format.GPX else RouteFileCodec.Format.JSON)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun tryOpenService(button: MaterialButton) {
        if (!OverlayUtils.hasOverlayPermissions(requireContext())) {
            showToast("请授权悬浮窗权限")
            return
        }

        val selectedRoute = mockServiceViewModel.selectedRoute ?: run {
            showToast("请选择一个路线")
            return
        }
        if (!RouteStepper.isValid(selectedRoute.route)) {
            showToast("路线为空或坐标不合法，请重新选择")
            return
        }
        mockServiceViewModel.stopMovement()

        if (mockServiceViewModel.locationManager == null) {
            showToast("定位服务加载异常")
            return
        }

        if (!MockServiceHelper.isServiceInit()) {
            showToast("系统服务注入失败")
            return
        }

        lifecycleScope.launch {
            val context = requireContext()
            val speed = context.speed
            val altitude = context.altitude
            val accuracy = FakeLoc.accuracy

            button.isClickable = false
            try {
                withContext(Dispatchers.IO) {
                    if (MockServiceHelper.tryOpenMock(
                            mockServiceViewModel.locationManager!!,
                            speed,
                            altitude,
                            accuracy
                        )
                    ) {
                        updateMockButtonState(
                            button,
                            "停止模拟",
                            R.drawable.rounded_play_disabled_24
                        )
                    } else {
                        showToast("模拟服务启动失败")
                        return@withContext
                    }

                    val first = selectedRoute.route[0]
                    if (MockServiceHelper.setLocation(
                            mockServiceViewModel.locationManager!!,
                            first.first,
                            first.second
                        )
                    ) {
                        showToast("更新路线起点位置成功")
                    } else {
                        showToast("更新位置失败")
                    }
                }
            } finally {
                button.isClickable = true
            }
        }


    }

    private fun tryCloseService(button: MaterialButton) {
        if (mockServiceViewModel.locationManager == null) {
            showToast("定位服务加载异常")
            return
        }

        if (!MockServiceHelper.isServiceInit()) {
            showToast("系统服务注入失败")
            return
        }

        lifecycleScope.launch {
            button.isClickable = false
            try {
                val isClosed = withContext(Dispatchers.IO) {
                    if (!MockServiceHelper.isMockStart(mockServiceViewModel.locationManager!!)) {
                        showToast("模拟服务未启动")
                        return@withContext false
                    }

                    if (MockServiceHelper.tryCloseMock(mockServiceViewModel.locationManager!!)) {
                        updateMockButtonState(button, "开始模拟", R.drawable.rounded_play_arrow_24)
                        return@withContext true
                    } else {
                        showToast("模拟服务停止失败")
                        return@withContext false
                    }
                }
                if (isClosed) mockServiceViewModel.stopMovement()
                if (isClosed && mockServiceViewModel.rocker.isStart) {
                    binding.rocker.isClickable = false
                    binding.rocker.toggle()
                    mockServiceViewModel.rocker.hide()
                    mockServiceViewModel.rockerCoroutineController.pause()
                    binding.rocker.isClickable = true
                }
            } finally {
                button.isClickable = true
            }
        }
    }


    private fun setupRouteSteps() {
        val config = StepSimulationSettings.read(requireContext())
        binding.routeStepsSwitch.isChecked = config.routeEnabled
        refreshRouteStride()
        var reverting = false
        binding.routeStepsSwitch.setOnCheckedChangeListener { button, enabled ->
            if (reverting) return@setOnCheckedChangeListener
            val current = StepSimulationSettings.read(requireContext())
            if (!StepSimulationSettings.save(requireContext(), current.copy(routeEnabled = enabled))) {
                reverting = true
                button.isChecked = current.routeEnabled
                reverting = false
                showToast(getString(R.string.step_settings_failed))
            }
        }
        binding.routeStrideValue.setOnClickListener {
            if (cadenceDialog?.isShowing == true) return@setOnClickListener
            val input = DialogInputBinding.inflate(layoutInflater)
            input.title.setText(R.string.route_stride_input)
            input.value.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            input.value.setText(String.format(Locale.ROOT, "%.2f",
                StepSimulationSettings.read(requireContext()).routeStepLengthMeters))
            val dialog = MaterialAlertDialogBuilder(requireContext())
                .setView(input.root)
                .setPositiveButton(R.string.route_pace_save, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create()
            cadenceDialog = dialog
            dialog.setOnDismissListener { cadenceDialog = null }
            dialog.setOnShowListener {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val stride = input.value.text?.toString()?.replace(',', '.')?.toDoubleOrNull()
                    if (stride == null || !RouteCadence.validStepLength(stride)) {
                        input.value.error = getString(R.string.route_stride_invalid)
                    } else {
                        val current = StepSimulationSettings.read(requireContext())
                        if (StepSimulationSettings.save(requireContext(), current.copy(routeStepLengthMeters = stride))) {
                            refreshRouteStride()
                            dialog.dismiss()
                        } else {
                            showToast(getString(R.string.step_settings_failed))
                        }
                    }
                }
            }
            dialog.show()
        }
    }

    private fun refreshRouteStride() {
        val stride = StepSimulationSettings.read(requireContext()).routeStepLengthMeters
        binding.routeStrideValue.text = getString(R.string.route_stride_value,
            String.format(Locale.ROOT, "%.2f", stride))
        val speed = requireContext().speed
        val estimate = RouteCadence.target(speed, stride).roundToInt()
        val limited = speed.isFinite() && speed > 0.0 && speed * 60.0 / stride > RouteCadence.MAX_CADENCE
        binding.routeCadencePreview.text = getString(if (limited) R.string.route_cadence_limited
            else R.string.route_cadence_estimate, estimate)
    }

    private fun refreshPaceControls() {
        val pace = RoutePace.fromSpeed(requireContext().speed)
        binding.routePaceSlider.value = (pace ?: RoutePace.DEFAULT_SECONDS)
            .coerceIn(RoutePace.MIN_SECONDS, RoutePace.MAX_SECONDS).toFloat()
        binding.routePaceValue.text = pace?.let {
            getString(R.string.route_pace_value, RoutePace.format(it))
        } ?: getString(R.string.route_pace_unset)
        binding.routePaceHint.setText(if (pace != null && pace in RoutePace.MIN_SECONDS..RoutePace.MAX_SECONDS)
            R.string.route_pace_hint else R.string.route_pace_unsupported)
        refreshRouteStride()
    }

    private fun savePace(seconds: Int) {
        val speed = RoutePace.toSpeed(seconds)
        requireContext().speed = speed
        refreshPaceControls()
        val lm = mockServiceViewModel.locationManager
        if (lm != null && MockServiceHelper.isServiceInit() &&
            !MockServiceHelper.setSpeed(lm, speed.toFloat())) {
            showToast(getString(R.string.route_pace_sync_failed))
        }
    }

    private fun showPaceDialog() {
        if (paceDialog?.isShowing == true) return
        val input = DialogRoutePaceBinding.inflate(layoutInflater)
        val pace = (RoutePace.fromSpeed(requireContext().speed) ?: RoutePace.DEFAULT_SECONDS)
            .coerceIn(RoutePace.MIN_SECONDS, RoutePace.MAX_SECONDS)
        input.minutes.setText((pace / 60).toString())
        input.seconds.setText(String.format(Locale.ROOT, "%02d", pace % 60))
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.route_pace_edit)
            .setView(input.root)
            .setPositiveButton(R.string.route_pace_save, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        paceDialog = dialog
        dialog.setOnDismissListener { paceDialog = null }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                input.minutesLayout.error = null
                input.secondsLayout.error = null
                val minutes = input.minutes.text?.toString()?.toIntOrNull()
                val seconds = input.seconds.text?.toString()?.toIntOrNull()
                when {
                    minutes == null || minutes < 0 -> {
                        input.minutesLayout.error = getString(R.string.route_pace_minutes_invalid)
                        input.minutes.requestFocus()
                    }
                    seconds == null || seconds !in 0..59 -> {
                        input.secondsLayout.error = getString(R.string.route_pace_seconds_invalid)
                        input.seconds.requestFocus()
                    }
                    minutes * 60 + seconds !in RoutePace.MIN_SECONDS..RoutePace.MAX_SECONDS -> {
                        input.minutesLayout.error = getString(R.string.route_pace_range_invalid)
                    }
                    else -> {
                        savePace(minutes * 60 + seconds)
                        dialog.dismiss()
                    }
                }
            }
            input.seconds.setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_DONE) {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                    true
                } else false
            }
        }
        dialog.show()
    }

    override fun onDestroyView() {
        diagnosticsDialog?.dismiss()
        diagnosticsDialog = null
        diagnosticsJob?.cancel()
        diagnosticsJob = null
        diagnosticsText = null
        routeActionsDialog?.dismiss()
        routeActionsDialog = null
        _binding?.historicalRouteList?.adapter = null
        routeAdapter = null
        displayedRoutes = emptyList()
        cadenceDialog?.dismiss()
        cadenceDialog = null
        paceDialog?.dismiss()
        paceDialog = null
        _binding = null
        super.onDestroyView()
    }

    private fun showToast(message: String) = lifecycleScope.launch(Dispatchers.Main) {
        context?.let { Toast.makeText(it, message, Toast.LENGTH_SHORT).show() }
    }

    private fun updateMockButtonState(button: MaterialButton, text: String, iconRes: Int) =
        lifecycleScope.launch(Dispatchers.Main) {
            button.text = text
            ContextCompat.getDrawable(requireContext(), iconRes)?.let {
                button.icon = it
            }
        }

}
