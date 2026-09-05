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
import java.util.Calendar
import kotlin.math.max
import kotlin.math.min

class UsageMonitorService : Service() {

    companion object {
        private const val CHANNEL_ID = "appsense_monitor"
        private const val NOTIFICATION_ID = 1001

        private const val POLL_INTERVAL = 1000L

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

    private var isExpanded = false
    private var outsideTouchView: View? = null

    // Cached expanded-view children.
    // They are created once and reused to avoid rebuilding the UI
    // every time the floating timer is expanded/collapsed.
    private var expandedEmojiView: TextView? = null
    private var expandedTodayView: TextView? = null
    private var expandedCurrentView: TextView? = null

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

            val now =
                System.currentTimeMillis()

            val elapsed =
                now - sessionStartTime

            // Emoji + color are based on TODAY'S TOTAL usage.
            // The visible timer remains the current session duration.
            val todayTotal =
                getTodayUsage(
                    foregroundPackage,
                    now
                )

            showFloatingTimer(
                elapsed,
                todayTotal
            )

        } else {

            currentPackage = null
            sessionStartTime = 0L

            if (isExpanded) {
                collapseFloatingTimer()
            }

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
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER_VERTICAL
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
                LinearLayout.LayoutParams.WRAP_CONTENT,
                -1
            )
        )

        val params =
            WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
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

                gravity =
                    Gravity.TOP or Gravity.START

                x = edgeMargin
                y =
                    resources.displayMetrics
                        .heightPixels / 3
            }

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

                    // In expanded mode the card is not draggable.
                    if (isExpanded) {
                        return@setOnTouchListener true
                    }

                    val deltaX =
                        (event.rawX - initialTouchX).toInt()

                    val deltaY =
                        (event.rawY - initialTouchY).toInt()

                    val screenWidth =
                        resources.displayMetrics.widthPixels

                    val screenHeight =
                        resources.displayMetrics.heightPixels

                    val currentWidth =
                        view.width.takeIf { it > 0 } ?: 1

                    val minX = edgeMargin
                    val maxX =
                        max(
                            edgeMargin,
                            screenWidth -
                                    currentWidth -
                                    edgeMargin
                        )

                    val minY = edgeMargin
                    val maxY =
                        max(
                            edgeMargin,
                            screenHeight -
                                    height -
                                    edgeMargin
                        )

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

                MotionEvent.ACTION_UP -> {

                    if (!isExpanded) {
                        expandFloatingTimer()
                    }

                    true
                }

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

    private fun expandFloatingTimer() {

        val view =
            overlayView ?: return

        val params =
            overlayParams ?: return

        if (isExpanded) return

        isExpanded = true

        val density =
            resources.displayMetrics.density

        val edgeMargin =
            (EDGE_MARGIN_DP * density).toInt()

        val oldWidth =
            view.width

        val oldHeight =
            view.height

        val expandedWidth =
            max(
                oldWidth * 2,
                (210 * density).toInt()
            )

        val expandedHeight =
            max(
                oldHeight * 2,
                (96 * density).toInt()
            )

        // The expanded card uses three compact rows:
        // emoji, today's total, current session.
        // Create them only once and reuse them.
        val emoji =
            expandedEmojiView
                ?: TextView(this).apply {
                    text = "🙂"
                    textSize = 23f
                    gravity = Gravity.CENTER
                    includeFontPadding = true
                    setSingleLine(true)
                }.also {
                    expandedEmojiView = it
                }

        val today =
            expandedTodayView
                ?: TextView(this).apply {
                    text = "Today: 0m"
                    textSize = 13f
                    setTextColor(Color.WHITE)
                    gravity = Gravity.CENTER
                    includeFontPadding = false
                    setSingleLine(true)
                }.also {
                    expandedTodayView = it
                }

        val current =
            expandedCurrentView
                ?: TextView(this).apply {
                    text = "Current: 0s"
                    textSize = 12f
                    setTextColor(Color.WHITE)
                    gravity = Gravity.CENTER
                    includeFontPadding = false
                    setSingleLine(true)
                }.also {
                    expandedCurrentView = it
                }

        view.removeAllViews()

        view.orientation =
            LinearLayout.VERTICAL

        view.gravity =
            Gravity.CENTER

        view.setPadding(
            (10 * density).toInt(),
            (5 * density).toInt(),
            (10 * density).toInt(),
            (5 * density).toInt()
        )

        view.addView(
            emoji,
            LinearLayout.LayoutParams(
                -1,
                0,
                1f
            )
        )

        view.addView(
            today,
            LinearLayout.LayoutParams(
                -1,
                0,
                1f
            )
        )

        view.addView(
            current,
            LinearLayout.LayoutParams(
                -1,
                0,
                1f
            )
        )

        params.width = expandedWidth
        params.height = expandedHeight

        val screenWidth =
            resources.displayMetrics.widthPixels

        val screenHeight =
            resources.displayMetrics.heightPixels

        params.x =
            min(
                max(
                    params.x -
                            (expandedWidth - oldWidth) / 2,
                    edgeMargin
                ),
                max(
                    edgeMargin,
                    screenWidth -
                            expandedWidth -
                            edgeMargin
                )
            )

        params.y =
            min(
                max(
                    params.y -
                            (expandedHeight - oldHeight) / 2,
                    edgeMargin
                ),
                max(
                    edgeMargin,
                    screenHeight -
                            expandedHeight -
                            edgeMargin
                )
            )

        try {
            windowManager.updateViewLayout(
                view,
                params
            )
        } catch (_: Exception) {
        }

        createOutsideTouchLayer()
    }

    private fun collapseFloatingTimer() {

        val view =
            overlayView ?: return

        val params =
            overlayParams ?: return

        if (!isExpanded) return

        isExpanded = false

        val density =
            resources.displayMetrics.density

        view.removeAllViews()

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
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER_VERTICAL
                setSingleLine(true)
            }

        view.orientation =
            LinearLayout.HORIZONTAL

        view.gravity =
            Gravity.CENTER_VERTICAL

        view.setPadding(
            (8 * density).toInt(),
            0,
            (8 * density).toInt(),
            0
        )

        view.addView(
            emoji,
            LinearLayout.LayoutParams(
                (30 * density).toInt(),
                -1
            )
        )

        view.addView(
            timer,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                -1
            )
        )

        params.width =
            WindowManager.LayoutParams.WRAP_CONTENT

        params.height =
            (BOX_HEIGHT_DP * density).toInt()

        try {
            windowManager.updateViewLayout(
                view,
                params
            )
        } catch (_: Exception) {
        }

        removeOutsideTouchLayer()
    }

    private fun createOutsideTouchLayer() {

        if (outsideTouchView != null) return

        val layer =
            View(this).apply {

                setBackgroundColor(Color.TRANSPARENT)

                setOnTouchListener { _, event ->

                    if (
                        event.actionMasked ==
                        MotionEvent.ACTION_DOWN
                    ) {
                        collapseFloatingTimer()
                        true
                    } else {
                        true
                    }
                }
            }

        val type =
            if (
                Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.O
            ) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

        val layerParams =
            WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity =
                    Gravity.TOP or Gravity.START
            }

        try {

            windowManager.addView(
                layer,
                layerParams
            )

            outsideTouchView = layer

            // Put the expanded card above the transparent
            // outside-touch layer.
            overlayView?.let { pill ->
                windowManager.removeView(pill)
                windowManager.addView(
                    pill,
                    overlayParams
                )
            }

        } catch (_: Exception) {

            outsideTouchView = null
        }
    }

    private fun removeOutsideTouchLayer() {

        outsideTouchView?.let { layer ->

            try {
                windowManager.removeView(layer)
            } catch (_: Exception) {
            }
        }

        outsideTouchView = null
    }

    private fun showFloatingTimer(
        elapsed: Long,
        todayTotal: Long
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
                todayTotal < YELLOW_AFTER_MS ->
                    Color.WHITE

                todayTotal < RED_AFTER_MS ->
                    Color.YELLOW

                else ->
                    Color.RED
            }

        val emojiText =
            when {
                todayTotal < YELLOW_AFTER_MS ->
                    "🙂"

                todayTotal < RED_AFTER_MS ->
                    "😐"

                else ->
                    "😞"
            }

        view.post {

            if (isExpanded) {

                val todayView =
                    view.getChildAt(1)
                            as? TextView

                val currentView =
                    view.getChildAt(2)
                            as? TextView

                emoji?.text =
                    emojiText

                todayView?.text =
                    "Today: ${formatDuration(todayTotal)}"

                currentView?.text =
                    "Current: $timerText"

                todayView?.setTextColor(
                    timerColor
                )

                currentView?.setTextColor(
                    timerColor
                )

            } else {

                timer?.text =
                    timerText

                timer?.setTextColor(
                    timerColor
                )

                emoji?.text =
                    emojiText
            }

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

    private fun getTodayUsage(
        packageName: String,
        now: Long
    ): Long {

        val calendar =
            Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }

        val dayStart =
            calendar.timeInMillis

        val usageStatsManager =
            getSystemService(
                Context.USAGE_STATS_SERVICE
            ) as UsageStatsManager

        val events =
            usageStatsManager.queryEvents(
                dayStart,
                now
            )

        val event =
            UsageEvents.Event()

        var isForeground = false
        var sessionStart = 0L
        var total = 0L

        while (events.hasNextEvent()) {

            events.getNextEvent(event)

            if (event.packageName != packageName) {
                continue
            }

            when (event.eventType) {

                UsageEvents.Event.MOVE_TO_FOREGROUND,
                UsageEvents.Event.ACTIVITY_RESUMED -> {

                    if (!isForeground) {
                        isForeground = true
                        sessionStart = event.timeStamp
                    }
                }

                UsageEvents.Event.MOVE_TO_BACKGROUND,
                UsageEvents.Event.ACTIVITY_PAUSED -> {

                    if (isForeground) {

                        val sessionEnd =
                            event.timeStamp

                        val start =
                            max(sessionStart, dayStart)

                        val end =
                            min(sessionEnd, now)

                        if (end > start) {
                            total += end - start
                        }

                        isForeground = false
                        sessionStart = 0L
                    }
                }
            }
        }

        // Include the currently open session up to "now".
        if (isForeground && sessionStart > 0L) {

            val start =
                max(sessionStart, dayStart)

            if (now > start) {
                total += now - start
            }
        }

        return max(0L, total)
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

        removeOutsideTouchLayer()
        isExpanded = false

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

        expandedEmojiView = null
        expandedTodayView = null
        expandedCurrentView = null

        super.onDestroy()
    }

    override fun onBind(
        intent: Intent?
    ): IBinder? {
        return null
    }
}

