package com.appsense.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.content.pm.ServiceInfo
import kotlin.math.max
import kotlin.math.min

class UsageMonitorService : Service() {

    companion object {
        private const val CHANNEL_ID = "appsense_monitor"
        private const val NOTIFICATION_ID = 1001

        private const val POLL_INTERVAL = 1000L

        private const val BOX_WIDTH_DP = 118
        private const val BOX_HEIGHT_DP = 36
        private const val EDGE_MARGIN_DP = 6

        private const val YELLOW_AFTER_MS = 30L * 60L * 1000L
        private const val RED_AFTER_MS = 60L * 60L * 1000L
    }

    private var monitoringThread: Thread? = null
    @Volatile
    private var isMonitoring = false

    private lateinit var windowManager: WindowManager
    private var overlayView: LinearLayout? = null
    private var overlayParams: WindowManager.LayoutParams? = null

    private var currentPackage: String? = null
    private var sessionStartTime = 0L

    private var lastForegroundPackage: String? = null

    override fun onCreate() {
        super.onCreate()

        createNotificationChannel()

        val notification = createNotification()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(
                NOTIFICATION_ID,
                notification
            )
        }

        windowManager =
            getSystemService(WINDOW_SERVICE) as WindowManager

        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }

        createFloatingTimer()
        startMonitoring()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        return START_STICKY
    }

    private fun startMonitoring() {

        if (isMonitoring) return

        isMonitoring = true

        monitoringThread = Thread {

            while (isMonitoring) {

                try {

                    updateFloatingTimer()

                    Thread.sleep(POLL_INTERVAL)

                } catch (_: InterruptedException) {
                    break
                } catch (_: Exception) {
                    // Keep monitoring alive if a single usage/overlay
                    // update fails.
                }
            }
        }.apply {
            name = "AppSenseUsageMonitor"
            start()
        }
    }

    private fun updateFloatingTimer() {

        if (!Settings.canDrawOverlays(this)) {
            hideFloatingTimer()
            return
        }

        val powerManager =
            getSystemService(Context.POWER_SERVICE) as PowerManager

        if (!powerManager.isInteractive) {
            currentPackage = null
            sessionStartTime = 0L
            hideFloatingTimer()
            return
        }

        val selectedApps =
            getSharedPreferences(
                "appsense_preferences",
                Context.MODE_PRIVATE
            )
                .getStringSet(
                    "tracked_apps",
                    emptySet()
                )
                ?.toSet()
                ?: emptySet()

        if (selectedApps.isEmpty()) {
            currentPackage = null
            sessionStartTime = 0L
            hideFloatingTimer()
            return
        }

        val foregroundPackage =
            getCurrentForegroundApp()

        if (
            foregroundPackage != null &&
            selectedApps.contains(foregroundPackage)
        ) {

            if (currentPackage != foregroundPackage) {
                currentPackage = foregroundPackage
                sessionStartTime = System.currentTimeMillis()
            }

            val elapsed =
                System.currentTimeMillis() -
                        sessionStartTime

            showFloatingTimer(elapsed)

        } else {

            currentPackage = null
            sessionStartTime = 0L
            hideFloatingTimer()
        }
    }

    private fun getCurrentForegroundApp(): String? {

        val usageStatsManager =
            getSystemService(
                Context.USAGE_STATS_SERVICE
            ) as UsageStatsManager

        val endTime =
            System.currentTimeMillis()

        val startTime =
            endTime - 10_000L

        val usageEvents =
            usageStatsManager.queryEvents(
                startTime,
                endTime
            )

        val event =
            UsageEvents.Event()

        var latestPackage =
            lastForegroundPackage

        var latestTime = 0L

        while (usageEvents.hasNextEvent()) {

            usageEvents.getNextEvent(event)

            val isForegroundEvent =
                event.eventType ==
                        UsageEvents.Event.MOVE_TO_FOREGROUND ||
                        (
                                Build.VERSION.SDK_INT >=
                                        Build.VERSION_CODES.Q &&
                                        event.eventType ==
                                        UsageEvents.Event.ACTIVITY_RESUMED
                                )

            val isBackgroundEvent =
                event.eventType ==
                        UsageEvents.Event.MOVE_TO_BACKGROUND ||
                        (
                                Build.VERSION.SDK_INT >=
                                        Build.VERSION_CODES.Q &&
                                        event.eventType ==
                                        UsageEvents.Event.ACTIVITY_PAUSED
                                )

            if (
                isForegroundEvent &&
                event.timeStamp >= latestTime
            ) {
                latestTime = event.timeStamp
                latestPackage = event.packageName
            }

            if (
                isBackgroundEvent &&
                event.timeStamp >= latestTime
            ) {
                latestTime = event.timeStamp
                latestPackage = null
            }
        }

        lastForegroundPackage = latestPackage

        return latestPackage
    }

    private fun createFloatingTimer() {

        if (overlayView != null) return

        val density =
            resources.displayMetrics.density

        val width =
            (BOX_WIDTH_DP * density).toInt()

        val height =
            (BOX_HEIGHT_DP * density).toInt()

        val edgeMargin =
            (EDGE_MARGIN_DP * density).toInt()

        val container =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.HORIZONTAL

                gravity =
                    Gravity.CENTER_VERTICAL

                setPadding(
                    (8 * density).toInt(),
                    0,
                    (8 * density).toInt(),
                    0
                )

                background =
                    android.graphics.drawable.GradientDrawable().apply {
                        shape =
                            android.graphics.drawable.GradientDrawable.RECTANGLE
                        cornerRadius =
                            BOX_HEIGHT_DP * density / 2f
                        setColor(
                            Color.argb(
                                175,
                                25,
                                25,
                                25
                            )
                        )
                    }

                visibility =
                    View.GONE
            }

        val emoji =
            TextView(this).apply {

                text = "🙂"
                textSize = 17f
                gravity = Gravity.CENTER
            }

        val timer =
            TextView(this).apply {

                text = "0s"
                textSize = 13f

                setTextColor(
                    Color.WHITE
                )

                gravity =
                    Gravity.CENTER_VERTICAL

                setSingleLine(true)
            }

        container.addView(
            emoji,
            LinearLayout.LayoutParams(
                (30 * density).toInt(),
                -1
            )
        )

        container.addView(
            timer,
            LinearLayout.LayoutParams(
                0,
                -1,
                1f
            )
        )

        val params =
            WindowManager.LayoutParams(
                width,
                height,

                if (
                    Build.VERSION.SDK_INT >=
                    Build.VERSION_CODES.O
                ) {
                    WindowManager.LayoutParams
                        .TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams
                        .TYPE_PHONE
                },

                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,

                PixelFormat.TRANSLUCENT
            ).apply {

                // Start on the right, but remain freely movable.
                gravity =
                    Gravity.TOP or Gravity.START

                x =
                    resources.displayMetrics
                        .widthPixels -
                            width -
                            edgeMargin

                y =
                    resources.displayMetrics
                        .heightPixels / 3
            }

        // The whole pill can be dragged anywhere on screen.
        var initialTouchX = 0f
        var initialTouchY = 0f
        var initialWindowX = 0
        var initialWindowY = 0

        container.setOnTouchListener { view, event ->

            when (event.actionMasked) {

                MotionEvent.ACTION_DOWN -> {
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    initialWindowX = params.x
                    initialWindowY = params.y
                    true
                }

                MotionEvent.ACTION_MOVE -> {

                    val deltaX =
                        (event.rawX - initialTouchX).toInt()

                    val deltaY =
                        (event.rawY - initialTouchY).toInt()

                    val screenWidth =
                        resources.displayMetrics.widthPixels

                    val screenHeight =
                        resources.displayMetrics.heightPixels

                    val minX = edgeMargin
                    val maxX =
                        screenWidth - width - edgeMargin

                    val minY = edgeMargin
                    val maxY =
                        screenHeight - height - edgeMargin

                    params.x =
                        min(
                            max(
                                initialWindowX + deltaX,
                                minX
                            ),
                            maxX
                        )

                    params.y =
                        min(
                            max(
                                initialWindowY + deltaY,
                                minY
                            ),
                            maxY
                        )

                    try {
                        windowManager.updateViewLayout(
                            view,
                            params
                        )
                    } catch (_: Exception) {
                    }

                    true
                }

                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> true

                else -> false
            }
        }

        try {

            windowManager.addView(
                container,
                params
            )

            overlayView =
                container

            overlayParams =
                params

        } catch (_: Exception) {

            overlayView = null
            overlayParams = null
        }
    }

    private fun showFloatingTimer(
        elapsed: Long
    ) {

        val view =
            overlayView ?: return

        val timer =
            view.getChildAt(1)
                    as? TextView

        val emoji =
            view.getChildAt(0)
                    as? TextView

        val timerText =
            formatDuration(elapsed)

        val timerColor =
            when {
                elapsed < YELLOW_AFTER_MS ->
                    Color.WHITE

                elapsed < RED_AFTER_MS ->
                    Color.YELLOW

                else ->
                    Color.RED
            }

        val emojiText =
            when {
                elapsed < YELLOW_AFTER_MS ->
                    "🙂"

                elapsed < RED_AFTER_MS ->
                    "😐"

                else ->
                    "😞"
            }

        view.post {

            timer?.text =
                timerText

            timer?.setTextColor(
                timerColor
            )

            emoji?.text =
                emojiText

            view.visibility =
                View.VISIBLE
        }
    }

    private fun hideFloatingTimer() {

        val view =
            overlayView ?: return

        view.post {
            view.visibility =
                View.GONE
        }
    }

    private fun formatDuration(
        millis: Long
    ): String {

        val totalSeconds =
            millis / 1000L

        val hours =
            totalSeconds / 3600L

        val minutes =
            (totalSeconds % 3600L) / 60L

        val seconds =
            totalSeconds % 60L

        return when {

            hours > 0 ->
                "${hours}h ${minutes}m ${seconds}s"

            minutes > 0 ->
                "${minutes}m ${seconds}s"

            else ->
                "${seconds}s"
        }
    }

    private fun createNotificationChannel() {

        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.O
        ) {

            val channel =
                NotificationChannel(
                    CHANNEL_ID,
                    "AppSense Monitoring",
                    NotificationManager
                        .IMPORTANCE_LOW
                )

            channel.description =
                "Shows that AppSense is monitoring application usage."

            val manager =
                getSystemService(
                    NotificationManager::class.java
                )

            manager.createNotificationChannel(
                channel
            )
        }
    }

    private fun createNotification(): Notification {

        return if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.O
        ) {

            Notification.Builder(
                this,
                CHANNEL_ID
            )
                .setContentTitle(
                    "AppSense"
                )
                .setContentText(
                    "Floating usage timer is active"
                )
                .setSmallIcon(
                    android.R.drawable
                        .ic_menu_recent_history
                )
                .setOngoing(true)
                .build()

        } else {

            Notification.Builder(this)
                .setContentTitle(
                    "AppSense"
                )
                .setContentText(
                    "Floating usage timer is active"
                )
                .setSmallIcon(
                    android.R.drawable
                        .ic_menu_recent_history
                )
                .setOngoing(true)
                .build()
        }
    }

    override fun onDestroy() {

        isMonitoring = false

        monitoringThread?.interrupt()
        monitoringThread = null

        val view = overlayView

        if (view != null) {

            try {
                windowManager.removeView(
                    view
                )
            } catch (_: Exception) {
            }
        }

        overlayView = null
        overlayParams = null

        super.onDestroy()
    }

    override fun onBind(
        intent: Intent?
    ): IBinder? {
        return null
    }
}
