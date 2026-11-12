package com.wusan.location.service

import android.content.Context
import android.location.LocationManager
import android.os.Bundle
import android.os.SystemClock
import com.wusan.location.R
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Reads diagnostics only while the user has the panel open. No location data is collected. */
object RuntimeDiagnostics {
    enum class Availability { AVAILABLE, NO_MANAGER, NOT_INITIALIZED, UNSUPPORTED, FAILED }

    data class Delivery(
        val deliveredPoints: Long,
        val listeners: Int,
        val maximumSampleGapMs: Long,
        val maximumDeliveryDelayMs: Long,
        val pendingPoints: Int,
        val lastError: String?,
        val lastErrorAtMillis: Long,
        val sinceElapsedMs: Long,
        val capturedElapsedMs: Long,
    )

    data class Snapshot(
        val runtime: RuntimeState,
        val engine: EngineDiagnostics,
        val delivery: Delivery?,
        val availability: Availability,
        val readError: String?,
        val capturedNanos: Long,
    )

    suspend fun read(locationManager: LocationManager?): Snapshot = withContext(Dispatchers.IO) {
        var availability = Availability.AVAILABLE
        var delivery: Delivery? = null
        var readError: String? = null
        when {
            locationManager == null -> availability = Availability.NO_MANAGER
            !MockServiceHelper.isServiceInit() -> availability = Availability.NOT_INITIALIZED
            else -> try {
                delivery = MockServiceHelper.getDeliveryDiagnostics(locationManager)?.let(::decode)
                if (delivery == null) availability = Availability.UNSUPPORTED
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                availability = Availability.FAILED
                readError = error.message?.take(500) ?: error.javaClass.simpleName
            }
        }
        Snapshot(MockRuntime.state.value, MockRuntime.diagnostics.value, delivery,
            availability, readError, SystemClock.elapsedRealtimeNanos())
    }

    fun phaseLabel(context: Context, phase: RuntimePhase): String = context.getString(when (phase) {
        RuntimePhase.IDLE -> R.string.runtime_phase_idle
        RuntimePhase.ROUTE -> R.string.runtime_phase_route
        RuntimePhase.MANUAL -> R.string.runtime_phase_manual
        RuntimePhase.PAUSED -> R.string.runtime_phase_paused
        RuntimePhase.COMPLETED -> R.string.runtime_phase_completed
        RuntimePhase.ERROR -> R.string.runtime_phase_error
    })

    fun format(context: Context, snapshot: Snapshot): String = with(snapshot) {
        val unavailable = context.getString(R.string.runtime_diagnostic_unavailable)
        fun metric(value: Double): String = if (value.isFinite() && value >= 0.0)
            String.format(Locale.getDefault(), "%.1f ms", value) else unavailable
        val moving = runtime.phase == RuntimePhase.ROUTE || runtime.phase == RuntimePhase.MANUAL
        val age = when {
            !moving -> context.getString(R.string.runtime_diagnostic_sampling_stopped)
            engine.lastSampleNanos <= 0L -> unavailable
            else -> metric((capturedNanos - engine.lastSampleNanos).coerceAtLeast(0L) / 1_000_000.0)
        }
        buildString {
            appendLine(context.getString(R.string.runtime_diagnostic_state, phaseLabel(context, runtime.phase)))
            if (runtime.routeName.isNotBlank()) {
                appendLine(context.getString(R.string.runtime_diagnostic_route, runtime.routeName))
            }
            appendLine()
            appendLine(context.getString(R.string.runtime_diagnostic_engine))
            appendLine(context.getString(R.string.runtime_diagnostic_samples, engine.sampleCount))
            appendLine(context.getString(R.string.runtime_diagnostic_interval,
                if (engine.sampleCount > 1) metric(engine.sampleIntervalMs) else unavailable))
            appendLine(context.getString(R.string.runtime_diagnostic_command,
                if (engine.sampleCount > 0) metric(engine.commandDurationMs) else unavailable))
            appendLine(context.getString(R.string.runtime_diagnostic_sample_age, age))
            appendLine()
            appendLine(context.getString(R.string.runtime_diagnostic_system))
            if (delivery == null) {
                appendLine(context.getString(when (availability) {
                    Availability.NO_MANAGER -> R.string.runtime_diagnostic_no_manager
                    Availability.NOT_INITIALIZED -> R.string.runtime_diagnostic_not_initialized
                    Availability.UNSUPPORTED -> R.string.runtime_diagnostic_unsupported
                    else -> R.string.runtime_diagnostic_read_failed
                }))
            } else if (delivery.sinceElapsedMs <= 0L) {
                appendLine(context.getString(R.string.runtime_diagnostic_no_session))
                appendLine(context.getString(R.string.runtime_diagnostic_listeners, delivery.listeners))
            } else {
                appendLine(context.getString(R.string.runtime_diagnostic_listeners, delivery.listeners))
                appendLine(context.getString(R.string.runtime_diagnostic_delivered, delivery.deliveredPoints))
                appendLine(context.getString(R.string.runtime_diagnostic_pending, delivery.pendingPoints))
                appendLine(context.getString(R.string.runtime_diagnostic_max_gap, delivery.maximumSampleGapMs))
                appendLine(context.getString(R.string.runtime_diagnostic_max_delay, delivery.maximumDeliveryDelayMs))
                if (delivery.sinceElapsedMs > 0L) {
                    val seconds = (delivery.capturedElapsedMs - delivery.sinceElapsedMs).coerceAtLeast(0L) / 1000
                    appendLine(context.getString(R.string.runtime_diagnostic_session_seconds, seconds))
                }
            }
            appendLine()
            val errors = listOfNotNull(runtime.error, engine.lastError, delivery?.lastError, readError)
                .filter { it.isNotBlank() }.distinct().joinToString("\n") { it.take(1000) }
            appendLine(context.getString(R.string.runtime_diagnostic_error,
                errors.ifBlank { context.getString(R.string.runtime_diagnostic_no_error) }))
            delivery?.lastErrorAtMillis?.takeIf { it > 0L }?.let {
                appendLine(context.getString(R.string.runtime_diagnostic_error_time,
                    DateFormat.getDateTimeInstance().format(Date(it))))
            }
            appendLine()
            append(context.getString(R.string.runtime_diagnostic_explanation))
        }
    }

    private fun decode(bundle: Bundle): Delivery? {
        if (bundle.getInt("diag_version", 0) != 1) return null
        val required = listOf("diag_delivered_points", "diag_listener_count", "diag_max_sample_gap_ms",
            "diag_max_delivery_delay_ms", "diag_pending_points", "diag_last_error_at_millis",
            "diag_since_elapsed_ms", "diag_captured_elapsed_ms")
        if (required.any { !bundle.containsKey(it) }) return null
        val delivery = Delivery(bundle.getLong("diag_delivered_points"), bundle.getInt("diag_listener_count"),
            bundle.getLong("diag_max_sample_gap_ms"), bundle.getLong("diag_max_delivery_delay_ms"),
            bundle.getInt("diag_pending_points"), bundle.getString("diag_last_error"),
            bundle.getLong("diag_last_error_at_millis"), bundle.getLong("diag_since_elapsed_ms"),
            bundle.getLong("diag_captured_elapsed_ms"))
        if (delivery.deliveredPoints < 0 || delivery.listeners < 0 || delivery.pendingPoints < 0 ||
            delivery.maximumSampleGapMs < 0 || delivery.maximumDeliveryDelayMs < 0) return null
        return delivery
    }
}
