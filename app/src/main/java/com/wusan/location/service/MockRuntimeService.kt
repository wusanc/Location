package com.wusan.location.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.wusan.location.MainActivity
import com.wusan.location.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Owns the visible Android foreground-service lifetime. The runtime keeps its own
 * application context and movement jobs, so replacing an Activity cannot stop a route.
 */
class MockRuntimeService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var attached = false

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "位置模拟运行", NotificationManager.IMPORTANCE_LOW).apply {
                description = "路线模拟与摇杆运行状态，可随时暂停或停止"
                setShowBadge(false)
            },
        )
        try {
            // Android requires this before any asynchronous initialization.
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification(MockRuntime.state.value),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
            attached = true
            MockRuntime.attachService(applicationContext)
            isActive = true
        } catch (error: RuntimeException) {
            isActive = false
            if (attached) {
                attached = false
                runCatching { MockRuntime.detachService() }
            }
            MockRuntime.startFailed("无法启动前台运行服务：${error.message ?: error.javaClass.simpleName}")
            stopSelf()
            return
        }

        serviceScope.launch {
            MockRuntime.state.collect { state ->
                // Notification permission denial must not interrupt an existing FGS.
                runCatching { manager.notify(NOTIFICATION_ID, notification(state)) }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!attached) return START_NOT_STICKY
        when (intent?.action) {
            ACTION_PAUSE -> {
                if (MockRuntime.state.value.phase == RuntimePhase.ROUTE) MockRuntime.pause()
            }
            ACTION_RESUME -> {
                if (MockRuntime.state.value.phase == RuntimePhase.PAUSED) MockRuntime.resumeRoute()
            }
            ACTION_STOP -> {
                MockRuntime.stop()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        // A killed process must not silently restart an old simulation.
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isActive = false
        serviceScope.cancel()
        if (attached) {
            attached = false
            MockRuntime.detachService()
        }
        super.onDestroy()
    }

    private fun notification(state: RuntimeState): Notification {
        val title = when (state.phase) {
            RuntimePhase.ROUTE -> "路线模拟正在运行"
            RuntimePhase.MANUAL -> "摇杆模拟正在运行"
            RuntimePhase.PAUSED -> "路线模拟已暂停"
            RuntimePhase.COMPLETED -> "路线模拟已完成"
            RuntimePhase.ERROR -> "位置模拟运行异常"
            RuntimePhase.IDLE -> "位置模拟已就绪"
        }
        val detail = state.error?.takeIf { it.isNotBlank() }
            ?: state.routeName.takeIf { it.isNotBlank() }
            ?: "点按返回应用，使用停止按钮结束运行"
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
            ),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.baseline_route_24)
            .setContentTitle(title)
            .setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setContentIntent(openApp)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .apply {
                when (state.phase) {
                    RuntimePhase.ROUTE -> addAction(
                        android.R.drawable.ic_media_pause,
                        "暂停",
                        actionIntent(ACTION_PAUSE, 1),
                    )
                    RuntimePhase.PAUSED -> addAction(
                        R.drawable.rounded_play_arrow_24,
                        "继续",
                        actionIntent(ACTION_RESUME, 2),
                    )
                    else -> Unit
                }
                addAction(
                    R.drawable.baseline_stop_24,
                    "停止",
                    actionIntent(ACTION_STOP, 3),
                )
            }
            .build()
    }

    private fun actionIntent(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            this,
            requestCode,
            Intent(this, MockRuntimeService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    companion object {
        private const val CHANNEL_ID = "mock_runtime"
        private const val NOTIFICATION_ID = 3101
        private const val ACTION_START = "com.wusan.location.runtime.START"
        private const val ACTION_PAUSE = "com.wusan.location.runtime.PAUSE"
        private const val ACTION_RESUME = "com.wusan.location.runtime.RESUME"
        private const val ACTION_STOP = "com.wusan.location.runtime.STOP"

        @Volatile
        var isActive: Boolean = false
            private set

        /**
         * Call from a visible user action after granting foreground location access.
         * true means Android accepted the request; attachService signals readiness.
         * POST_NOTIFICATIONS is optional for starting an Android foreground service.
         */
        fun ensureStarted(context: Context): Boolean {
            val appContext = context.applicationContext
            val hasFine = ContextCompat.checkSelfPermission(
                appContext, Manifest.permission.ACCESS_FINE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED
            val hasCoarse = ContextCompat.checkSelfPermission(
                appContext, Manifest.permission.ACCESS_COARSE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasFine && !hasCoarse) {
                MockRuntime.startFailed("请先授予位置权限，再启动位置模拟")
                return false
            }
            if (isActive) return true
            return try {
                ContextCompat.startForegroundService(
                    appContext,
                    Intent(appContext, MockRuntimeService::class.java).setAction(ACTION_START),
                )
                true
            } catch (error: RuntimeException) {
                MockRuntime.startFailed("无法启动前台运行服务：${error.message ?: error.javaClass.simpleName}")
                false
            }
        }

        /** stopService never creates a service, including while start is pending. */
        fun requestStop(context: Context) {
            runCatching {
                context.applicationContext.stopService(
                    Intent(context.applicationContext, MockRuntimeService::class.java),
                )
            }.onFailure { error ->
                MockRuntime.startFailed("无法停止前台运行服务：${error.message ?: error.javaClass.simpleName}")
            }
        }
    }
}