package com.appsense.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.Toast
import android.widget.TextView
import android.widget.Button
import android.widget.ScrollView
import android.os.Handler
import android.os.Looper
import android.content.pm.ServiceInfo
import java.util.Calendar
import kotlin.math.max
import kotlin.math.min

class UsageMonitorService : Service() {

    companion object {
        private const val CHANNEL_ID = "appsense_monitor"
        private const val NOTIFICATION_ID = 1001

        private const val POLL_INTERVAL = 1000L
        // Rebuild today's event history less often than the UI timer refreshes.
        private const val USAGE_TOTAL_REFRESH_INTERVAL = 30_000L

        // PAUSED(old activity) is reported a moment before RESUMED(new activity).
        // Do not treat that short gap as "left the tracked app".
        private const val FOREGROUND_GAP_TOLERANCE_MS = 2_500L

        private const val BOX_HEIGHT_DP = 36
        private const val EDGE_MARGIN_DP = 6

        private const val YELLOW_AFTER_MS = 30L * 60L * 1000L
        private const val RED_AFTER_MS = 60L * 60L * 1000L
        // Defaults used when SharedPreferences has not been configured yet.
        private const val FIRST_REMINDER_MS = 15L * 60L * 1000L
        private const val REPEAT_REMINDER_MS = 10L * 60L * 1000L
    }

    private var monitoringThread: Thread? = null
    @Volatile
    private var isMonitoring = false

    private lateinit var windowManager: WindowManager
    private var overlayView: LinearLayout? = null
    private var overlayParams: WindowManager.LayoutParams? = null

    private var currentPackage: String? = null
    private val sessionTracker = SessionTracker()

    // Monitoring-thread state for the visible timer, which resets per tracked app.
    // Last tracked package is retained during the SessionTracker grace period.
    private var floatingTimerPackage: String? = null

    private var lastForegroundPackage: String? = null
    private var lastForegroundClass: String? = null
    private var lastTrackedForegroundPackage: String? = null
    private var lastTrackedForegroundAtElapsed: Long = 0L

    // Cache today's full event-history calculation. The visible session timer still
    // refreshes every second; only the more expensive daily total is refreshed every 30s.
    @Volatile private var cachedUsagePackage: String? = null
    @Volatile private var cachedUsageDayStart: Long = 0L
    @Volatile private var cachedUsageAt: Long = 0L
    @Volatile private var cachedTodayUsage: Long = 0L

    // On service startup, reconstruct foreground state from a wider event window.
    // Subsequent polling uses the short window to avoid repeatedly scanning history.
    @Volatile
    private var isForegroundStateInitialized = false

    @Volatile
    private var isExpanded = false
    private var outsideTouchView: View? = null
    private var interventionOverlay: InterventionOverlay? = null

    // Intervention reminders are based on the current continuous tracked-app session.
    // The first prompt appears at 15 minutes; later prompts are spaced 10 minutes apart.
    private var nextReminderElapsedMs = FIRST_REMINDER_MS
    private var activeReminderType: String? = null
    private var reminderElapsedMs = 0L
    private var remindersInSession = 0
    private var interventionOutcomeLogged = false
    private var reminderContinueAtElapsedRealtimeMs = 0L
    private var reminderCountdownRunnable: Runnable? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var breathingRunnable: Runnable? = null
    private var interventionAudioManager: AudioManager? = null
    private var interventionAudioFocusRequest: AudioFocusRequest? = null
    private var interventionAudioFocusHeld = false
    private var gameCells = MutableList(9) { "" }
    private var gameFinished = false
    private var featurePanel: LinearLayout? = null
    private var featureScrollView: ScrollView? = null
    private var featureStatusView: TextView? = null
    private var featureActionButton: Button? = null
    private var continueButton: Button? = null
    private var closeAppButton: Button? = null
    private var gameButtons: MutableList<Button> = mutableListOf()

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

        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.UPSIDE_DOWN_CAKE
        ) {
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

        val screenOn =
            (getSystemService(POWER_SERVICE) as PowerManager).isInteractive

        val selectedApps =
            getSharedPreferences(
                "appsense_preferences",
                MODE_PRIVATE
            )
                .getStringSet(
                    "tracked_apps",
                    emptySet()
                )
                ?.toSet()
                ?: emptySet()

        val interventionSettings = InterventionPreferences.read(this)

        val overlayAllowed = Settings.canDrawOverlays(this)

        if (!screenOn) {
            lastForegroundPackage = null
            lastForegroundClass = null
            lastTrackedForegroundPackage = null
            isForegroundStateInitialized = false
        }

        val nowElapsedRealtime = SystemClock.elapsedRealtime()

        val rawForegroundPackage =
            if (screenOn && overlayAllowed && selectedApps.isNotEmpty()) {
                getCurrentForegroundApp()
            } else {
                null
            }

        if (rawForegroundPackage != null && selectedApps.contains(rawForegroundPackage)) {
            lastTrackedForegroundPackage = rawForegroundPackage
            lastTrackedForegroundAtElapsed = nowElapsedRealtime
        }

        // Bridge only the short "no package reported" gap of an activity change. A real switch to
        // another app reports that app's package, so it is never bridged.
        val foregroundPackage =
            if (
                rawForegroundPackage == null &&
                screenOn &&
                overlayAllowed &&
                selectedApps.isNotEmpty() &&
                lastTrackedForegroundPackage != null &&
                nowElapsedRealtime - lastTrackedForegroundAtElapsed <= FOREGROUND_GAP_TOLERANCE_MS
            ) {
                lastTrackedForegroundPackage
            } else {
                rawForegroundPackage
            }

        val trackedAppInForeground =
            foregroundPackage != null &&
                    selectedApps.contains(foregroundPackage)

        // Invalid monitoring conditions terminate the session immediately.
        val monitoringAllowed =
            screenOn &&
                    overlayAllowed &&
                    selectedApps.isNotEmpty()

        val elapsed = sessionTracker.onTick(
            nowMonotonicMs = nowElapsedRealtime,
            trackedAppInForeground = trackedAppInForeground,
            screenOn = monitoringAllowed
        )

        val newSessionStarted = sessionTracker.newSessionStarted
        val sessionEnded = sessionTracker.sessionEnded

        if (!monitoringAllowed || sessionEnded) {

            floatingTimerPackage = null

            mainHandler.post {

                currentPackage = null

                if (sessionEnded) {
                    resetReminderScheduleIfSessionEnded(
                        true,
                        interventionSettings.firstReminderMinutes * 60_000L
                    )
                    remindersInSession = 0
                }

                cachedUsageAt = 0L

                resetInterventionUi()
                hideFloatingTimer()
            }

            return
        }

        // Remember the most recently tracked app.
        // Do not clear it during a temporary untracked gap.
        if (trackedAppInForeground) {
            floatingTimerPackage = foregroundPackage
        }

        val packageName =
            foregroundPackage ?: floatingTimerPackage ?: return

        val todayTotal =
            if (trackedAppInForeground) {
                getTodayUsage(
                    packageName,
                    System.currentTimeMillis()
                )
            } else {
                if (cachedUsagePackage == packageName) {
                    cachedTodayUsage
                } else {
                    0L
                }
            }

        mainHandler.post {

            val firstReminderMs =
                interventionSettings.firstReminderMinutes * 60_000L

            val repeatReminderMs =
                interventionSettings.repeatReminderMinutes * 60_000L

            if (newSessionStarted) {
                nextReminderElapsedMs = firstReminderMs
                remindersInSession = 0
            }

            if (sessionEnded) {
                resetReminderScheduleIfSessionEnded(
                    true,
                    firstReminderMs
                )
                remindersInSession = 0
            }

            // Preserve existing intervention settings behaviour.
            if (!interventionSettings.interventionEnabled ||
                (activeReminderType == "breathing" &&
                        !interventionSettings.breathingEnabled) ||
                (activeReminderType == "game" &&
                        !interventionSettings.gameEnabled)
            ) {
                resetInterventionUi()
            }

            // Temporary untracked foreground:
            // Keep the pill and active intervention alive during grace.
            if (!trackedAppInForeground) {
                // Another app (Home, WhatsApp...) really is in front: end the intervention now.
                // A null package is only the short gap of an activity change: keep the card.
                if (foregroundPackage != null) {
                    resetInterventionUi()
                }
                hideFloatingTimer()
                return@post
            }

            // Update package without dismissing an active intervention.
            if (currentPackage != packageName) {

                currentPackage = packageName

                if (activeReminderType == null) {

                    if (isExpanded) {
                        collapseFloatingTimer()
                    }
                }
            }

            // IMPORTANT:
            // Pill and intervention now use the SAME elapsed value.
            showFloatingTimer(elapsed, todayTotal)

            if (remindersInSession == 0) {
                nextReminderElapsedMs = firstReminderMs
            } else {
                nextReminderElapsedMs =
                    reminderElapsedMs + repeatReminderMs
            }

            val anyInterventionTypeEnabled =
                interventionSettings.breathingEnabled ||
                        interventionSettings.gameEnabled

            if (
                elapsed >= nextReminderElapsedMs &&
                interventionSettings.interventionEnabled &&
                anyInterventionTypeEnabled &&
                activeReminderType == null &&
                Settings.canDrawOverlays(this) &&
                currentPackage == packageName
            ) {

                val reminderType = when {

                    interventionSettings.breathingEnabled &&
                            interventionSettings.gameEnabled ->
                        if (kotlin.random.Random.nextBoolean()) {
                            "breathing"
                        } else {
                            "game"
                        }

                    interventionSettings.breathingEnabled ->
                        "breathing"

                    else ->
                        "game"
                }

                if (isExpanded) {
                    collapseFloatingTimer()
                }

                activeReminderType = reminderType

                if (reminderType == "breathing") {
                    requestInterventionAudioFocus()
                }

                reminderElapsedMs = elapsed
                remindersInSession++

                interventionOutcomeLogged = false

                val countdownSeconds =
                    min(remindersInSession * 5, 20)

                reminderContinueAtElapsedRealtimeMs =
                    SystemClock.elapsedRealtime() +
                            countdownSeconds * 1000L

                gameCells = MutableList(9) { "" }
                gameFinished = false

                nextReminderElapsedMs =
                    elapsed + repeatReminderMs

                val appLabel = try {

                    packageManager.getApplicationLabel(
                        packageManager.getApplicationInfo(packageName, 0)
                    ).toString()

                } catch (_: Exception) {
                    packageName
                }

                val overlay = interventionOverlay ?: InterventionOverlay(
                    this,
                    windowManager,
                    mainHandler
                ).also {
                    interventionOverlay = it
                }

                overlay.show(
                    InterventionOverlay.Model(
                        appLabel = appLabel,
                        minutes = (reminderElapsedMs / 60_000L).toInt(),
                        type = reminderType,
                        continueAtElapsedRealtimeMs =
                            reminderContinueAtElapsedRealtimeMs
                    ),
                    object : InterventionOverlay.Callbacks {

                        override fun onClose() {
                            closeCurrentAppToHome()
                        }

                        override fun onContinue() {

                            logInterventionDismissedOnce()

                            activeReminderType = null

                            abandonInterventionAudioFocus()
                            cancelBreathingExercise()
                            cancelReminderCountdown()

                            gameCells = MutableList(9) { "" }
                            gameFinished = false

                            interventionOverlay?.dismiss()
                        }
                    }
                )

                if (!overlay.isShowing) {
                    // The overlay window could not be added. Do not leave a phantom active
                    // intervention that suppresses every later reminder. The next attempt is
                    // already scheduled by nextReminderElapsedMs / remindersInSession.
                    abandonInterventionAudioFocus()
                    cancelReminderCountdown()
                    activeReminderType = null
                    return@post
                }

                if (reminderType == "breathing") {

                    overlay.setContent(
                        BreathingView(this) { _ ->

                            if (
                                activeReminderType == "breathing" &&
                                interventionOverlay?.isShowing == true
                            ) {
                                logInterventionCompletedOnce()
                            }

                            abandonInterventionAudioFocus()
                        }
                    )

                } else {
                    val gameStatus = TextView(this).apply {
                        text = "Your move"
                        textSize = 16f
                        setTextColor(0xB3FFFFFF.toInt())
                        gravity = Gravity.CENTER
                        includeFontPadding = true
                    }

                    val gameView = TicTacToeView(
                        context = this,
                        onFirstMove = {
                            logInterventionCompletedOnce()
                        },
                        onStatusChanged = { message ->
                            gameStatus.text = message
                        }
                    )

                    val gameContent = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER

                        addView(
                            gameStatus,
                            LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT
                            )
                        )

                        addView(
                            gameView,
                            LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                (220 * resources.displayMetrics.density).toInt()
                            )
                        )
                    }

                    overlay.setContent(gameContent)
                }

                startReminderCountdown()
                InterventionDailyLogStore.recordShown(this)
            }
        }
    }

    private fun resetReminderScheduleIfSessionEnded(
        sessionEnded: Boolean,
        firstReminderMs: Long = FIRST_REMINDER_MS
    ) {
        // Caller must be on the main thread; do not read SessionTracker from here.
        if (sessionEnded) {
            nextReminderElapsedMs = firstReminderMs
            reminderElapsedMs = 0L
        }
    }

    private fun logInterventionCompletedOnce() {
        if (!interventionOutcomeLogged) {
            interventionOutcomeLogged = true
            InterventionDailyLogStore.recordCompleted(this)
        }
    }

    private fun logInterventionDismissedOnce() {
        if (!interventionOutcomeLogged) {
            interventionOutcomeLogged = true
            InterventionDailyLogStore.recordDismissed(this)
        }
    }
    private fun getCurrentForegroundApp(): String? {

        val usageStatsManager =
            getSystemService(USAGE_STATS_SERVICE) as UsageStatsManager

        val endTime = System.currentTimeMillis()

        val lookbackMillis =
            if (isForegroundStateInitialized) {
                60_000L
            } else {
                2L * 60L * 60L * 1000L
            }

        val startTime =
            (endTime - lookbackMillis).coerceAtLeast(0L)

        val usageEvents = usageStatsManager.queryEvents(
            startTime,
            endTime
        )

        isForegroundStateInitialized = true

        val event = UsageEvents.Event()

        var latestPackage = lastForegroundPackage
        var latestClass = lastForegroundClass
        var latestTime = 0L

        while (usageEvents.hasNextEvent()) {

            usageEvents.getNextEvent(event)

            val eventType = event.eventType
            val eventPackage = event.packageName ?: continue
            val eventClass = event.className
            val eventTime = event.timeStamp

            val isForegroundEvent =
                eventType == UsageEvents.Event.MOVE_TO_FOREGROUND ||
                        (
                                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                                        eventType == UsageEvents.Event.ACTIVITY_RESUMED
                                )

            val isActivityPausedEvent =
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                        eventType == UsageEvents.Event.ACTIVITY_PAUSED

            val isPackageBackgroundEvent =
                eventType == UsageEvents.Event.MOVE_TO_BACKGROUND

            val isSystemStopEvent =
                (
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                                (
                                        eventType == UsageEvents.Event.SCREEN_NON_INTERACTIVE ||
                                                eventType == UsageEvents.Event.KEYGUARD_SHOWN
                                        )
                        ) ||
                        (
                                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                                        eventType == UsageEvents.Event.DEVICE_SHUTDOWN
                                )

            when {

                isSystemStopEvent -> {

                    if (eventTime >= latestTime) {

                        latestTime = eventTime

                        latestPackage = null
                        latestClass = null
                    }
                }

                isForegroundEvent -> {

                    if (eventTime >= latestTime) {

                        latestTime = eventTime

                        latestPackage = eventPackage
                        latestClass = eventClass
                    }
                }

                isActivityPausedEvent -> {

                    // A paused activity must not clear a newer resumed activity
                    // belonging to the same package.
                    if (
                        eventTime >= latestTime &&
                        latestPackage == eventPackage &&
                        latestClass == eventClass
                    ) {

                        latestTime = eventTime

                        latestPackage = null
                        latestClass = null
                    }
                }

                isPackageBackgroundEvent -> {

                    if (
                        eventTime >= latestTime &&
                        latestPackage == eventPackage &&
                        latestClass == eventClass
                    ) {

                        latestTime = eventTime

                        latestPackage = null
                        latestClass = null
                    }
                }
            }
        }

        lastForegroundPackage = latestPackage
        lastForegroundClass = latestClass

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
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
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
        var isDragging = false

        val touchSlop =
            android.view.ViewConfiguration
                .get(this)
                .scaledTouchSlop

        container.setOnTouchListener { view, event ->

            when (event.actionMasked) {

                MotionEvent.ACTION_DOWN -> {
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    initialWindowX = params.x
                    initialWindowY = params.y
                    isDragging = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {

                    // In expanded mode the card is not draggable.
                    if (isExpanded) {
                        return@setOnTouchListener true
                    }

                    val deltaX =
                        event.rawX - initialTouchX

                    val deltaY =
                        event.rawY - initialTouchY

                    // Ignore tiny finger movements.
                    // Start dragging only after Android's standard
                    // touch-slop distance is crossed.
                    if (!isDragging) {

                        val distanceSquared =
                            deltaX * deltaX +
                                    deltaY * deltaY

                        val touchSlopSquared =
                            touchSlop * touchSlop

                        if (
                            distanceSquared >
                            touchSlopSquared
                        ) {
                            isDragging = true
                        }
                    }

                    if (isDragging) {

                        val deltaXInt =
                            deltaX.toInt()

                        val deltaYInt =
                            deltaY.toInt()

                        val screenWidth =
                            resources.displayMetrics.widthPixels

                        val screenHeight =
                            resources.displayMetrics.heightPixels

                        val currentWidth =
                            view.width
                                .takeIf { it > 0 }
                                ?: 1

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
                                    initialWindowX +
                                            deltaXInt,
                                    minX
                                ),
                                maxX
                            )

                        params.y =
                            min(
                                max(
                                    initialWindowY +
                                            deltaYInt,
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
                            // Keep overlay alive if the window
                            // temporarily cannot be updated.
                        }
                    }

                    true
                }

                MotionEvent.ACTION_UP -> {

                    // Expand ONLY for a tap.
                    // A real drag must never expand.
                    if (
                        !isExpanded &&
                        !isDragging
                    ) {
                        expandFloatingTimer()
                    }

                    isDragging = false

                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    isDragging = false
                    true
                }

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
                (110 * density).toInt()
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

    private fun updateReminderCountdownButton() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { updateReminderCountdownButton() }
            return
        }
        val button = continueButton ?: return
        val remainingMs = (reminderContinueAtElapsedRealtimeMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        val remainingSeconds = ((remainingMs + 999L) / 1000L).toInt()
        button.text = if (remainingSeconds > 0) "Continue ($remainingSeconds)" else "Continue"
        button.isEnabled = remainingMs == 0L
    }

    private fun startReminderCountdown() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { startReminderCountdown() }
            return
        }
        cancelReminderCountdown()
        val runnable = object : Runnable {
            override fun run() {
                if (activeReminderType == null || interventionOverlay?.isShowing != true) return
                updateReminderCountdownButton()
                interventionOverlay?.refreshCountdown()
                if (SystemClock.elapsedRealtime() < reminderContinueAtElapsedRealtimeMs) {
                    mainHandler.postDelayed(this, 250L)
                }
            }
        }
        reminderCountdownRunnable = runnable
        mainHandler.post(runnable)
    }

    private fun cancelReminderCountdown() {
        reminderCountdownRunnable?.let { mainHandler.removeCallbacks(it) }
        reminderCountdownRunnable = null
    }

    private fun closeCurrentAppToHome() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { closeCurrentAppToHome() }
            return
        }
        logInterventionDismissedOnce()
        abandonInterventionAudioFocus()
        try {
            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(homeIntent)
            activeReminderType = null
            cancelBreathingExercise()
            cancelReminderCountdown()
            collapseFloatingTimer()
        } catch (_: Exception) {
            Toast.makeText(
                applicationContext,
                "Couldn't open Home automatically. Press your phone's Home button.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun updateOutsideTouchLayerTouchability() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { updateOutsideTouchLayerTouchability() }
            return
        }
        val layer = outsideTouchView ?: return
        val params = layer.layoutParams as? WindowManager.LayoutParams ?: return
        val desiredFlags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        layer.setBackgroundColor(
            if (activeReminderType != null) Color.argb(150, 0, 0, 0) else Color.TRANSPARENT
        )
        if (params.flags != desiredFlags) {
            params.flags = desiredFlags
            try {
                windowManager.updateViewLayout(layer, params)
            } catch (_: Exception) {
            }
        }
    }

    private fun applyCardSize(widthDp: Int, heightDp: Int) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { applyCardSize(widthDp, heightDp) }
            return
        }

        val params = overlayParams ?: return
        val view = overlayView ?: return
        val density = resources.displayMetrics.density
        val margin = (EDGE_MARGIN_DP * density).toInt()

        val screenWidth: Int
        val screenHeight: Int
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            screenWidth = bounds.width()
            screenHeight = bounds.height()
        } else {
            screenWidth = resources.displayMetrics.widthPixels
            screenHeight = resources.displayMetrics.heightPixels
        }

        params.width = min((widthDp * density).toInt(), max(1, screenWidth - margin * 2))
        params.height = min((heightDp * density).toInt(), max(1, screenHeight - margin * 2))

        val maxX = max(0, screenWidth - params.width - margin)
        val minX = min(margin, maxX)
        val maxY = max(0, screenHeight - params.height - margin)
        val minY = min(margin, maxY)
        params.x = params.x.coerceIn(minX, maxX)
        params.y = params.y.coerceIn(minY, maxY)

        try {
            windowManager.updateViewLayout(view, params)
        } catch (_: Exception) {
        }
    }

    private fun requestInterventionAudioFocus() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { requestInterventionAudioFocus() }
            return
        }
        if (interventionAudioFocusHeld) return

        val audioManager = getSystemService(AUDIO_SERVICE) as? AudioManager ?: return
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAcceptsDelayedFocusGain(false)
            .setOnAudioFocusChangeListener { }
            .build()

        interventionAudioManager = audioManager
        interventionAudioFocusRequest = request
        interventionAudioFocusHeld =
            audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonInterventionAudioFocus() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { abandonInterventionAudioFocus() }
            return
        }

        val audioManager = interventionAudioManager
        val request = interventionAudioFocusRequest
        if (audioManager != null && request != null && interventionAudioFocusHeld) {
            try {
                audioManager.abandonAudioFocusRequest(request)
            } catch (_: Exception) {
                // Audio focus cleanup must not interrupt intervention cleanup.
            }
        }
        interventionAudioFocusHeld = false
        interventionAudioFocusRequest = null
        interventionAudioManager = null
    }


    private fun cancelBreathingExercise() {
        breathingRunnable?.let { mainHandler.removeCallbacks(it) }
        breathingRunnable = null
    }

    private fun resetInterventionUi() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { resetInterventionUi() }
            return
        }

        if (!isExpanded && activeReminderType == null) {
            interventionOverlay?.dismiss()
            return
        }

        cancelBreathingExercise()
        cancelReminderCountdown()
        abandonInterventionAudioFocus()
        activeReminderType = null
        interventionOverlay?.dismiss()
        updateOutsideTouchLayerTouchability()
        if (isExpanded) {
            collapseFloatingTimer()
        }
    }

    private fun startTicTacToe() {
        gameCells = MutableList(9) { "" }
        gameFinished = false
        featureStatusView?.text = "Your turn — tap a square (X)."
        featureActionButton?.text = "Restart Tic-Tac-Toe"
        featureActionButton?.setOnClickListener { startTicTacToe() }
        renderGameBoard()
    }

    private fun renderGameBoard() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { renderGameBoard() }
            return
        }

        val panel = featurePanel ?: return
        val density = resources.displayMetrics.density
        // Allow the board and intervention text to scroll inside the bounded card.
        featureScrollView?.let { scrollView ->
            val scrollParams = scrollView.layoutParams as? LinearLayout.LayoutParams
            if (scrollParams != null) {
                scrollParams.height = (260 * density).toInt()
                scrollView.layoutParams = scrollParams
            }
        }

        // Remove previous board rows while retaining status and all three action buttons.
        while (panel.childCount > 4) panel.removeViewAt(4)
        gameButtons.clear()
        repeat(3) { rowIndex ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            repeat(3) { colIndex ->
                val index = rowIndex * 3 + colIndex
                val cell = Button(this).apply {
                    text = gameCells[index].ifEmpty { "·" }
                    textSize = 16f
                    isAllCaps = false
                    setPadding(0, 0, 0, 0)
                    setOnClickListener {
                        if (gameFinished || gameCells[index].isNotEmpty()) return@setOnClickListener
                        logInterventionCompletedOnce()
                        gameCells[index] = "X"
                        if (hasWinner("X")) {
                            gameFinished = true
                            featureStatusView?.text = "You win! Nice break."
                        } else if (gameCells.none { it.isEmpty() }) {
                            gameFinished = true
                            featureStatusView?.text = "It's a draw. Take a breath."
                        } else {
                            val available = gameCells.indices.filter { gameCells[it].isEmpty() }
                            gameCells[available.random()] = "O"
                            if (hasWinner("O")) {
                                gameFinished = true
                                featureStatusView?.text = "AppSense wins this round. Try again?"
                            } else if (gameCells.none { it.isEmpty() }) {
                                gameFinished = true
                                featureStatusView?.text = "It's a draw. Try again?"
                            } else {
                                featureStatusView?.text = "Your turn — tap a square (X)."
                            }
                        }
                        renderGameBoard()
                    }
                }
                cell.minHeight = (42 * density).toInt()
                row.addView(cell, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins((2 * density).toInt(), (1 * density).toInt(), (2 * density).toInt(), (1 * density).toInt())
                })
                gameButtons.add(cell)
            }
            row.minimumHeight = (44 * density).toInt()
            panel.addView(row, LinearLayout.LayoutParams(-1, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        applyCardSize(280, 340)
    }

    private fun hasWinner(mark: String): Boolean {
        val lines = arrayOf(
            intArrayOf(0,1,2), intArrayOf(3,4,5), intArrayOf(6,7,8),
            intArrayOf(0,3,6), intArrayOf(1,4,7), intArrayOf(2,5,8),
            intArrayOf(0,4,8), intArrayOf(2,4,6)
        )
        return lines.any { line -> line.all { gameCells[it] == mark } }
    }

    private fun collapseFloatingTimer() {

        interventionOverlay?.dismiss()

        val view =
            overlayView ?: return

        val params =
            overlayParams ?: return

        if (!isExpanded) return

        isExpanded = false
        gameCells = MutableList(9) { "" }
        gameFinished = false
        cancelBreathingExercise()
        cancelReminderCountdown()
        abandonInterventionAudioFocus()
        activeReminderType = null
        continueButton = null
        closeAppButton = null
        featurePanel = null
        featureScrollView = null
        featureStatusView = null
        featureActionButton = null
        gameButtons.clear()

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

                setBackgroundColor(
                    if (activeReminderType != null) Color.argb(150, 0, 0, 0) else Color.TRANSPARENT
                )

                setOnTouchListener { view, event ->

                    if (activeReminderType != null) {
                        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        }
                        true
                    } else if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                        collapseFloatingTimer()
                        true
                    } else {
                        true
                    }
                }
            }

        val type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

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
            updateOutsideTouchLayerTouchability()

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
        val view = overlayView ?: return

        val timerText = formatDuration(elapsed)

        val timerColor =
            when {
                todayTotal < YELLOW_AFTER_MS -> Color.WHITE
                todayTotal < RED_AFTER_MS -> Color.YELLOW
                else -> Color.RED
            }

        val emojiText =
            when {
                todayTotal < YELLOW_AFTER_MS -> "🙂"
                todayTotal < RED_AFTER_MS -> "😐"
                else -> "😞"
            }

        // Resolve children only on the UI thread. Expansion/collapse
        // can replace the children between monitoring-thread polls.
        view.post {
            if (overlayView !== view) {
                return@post
            }

            if (isExpanded) {
                val emojiView =
                    expandedEmojiView
                        ?: (view.getChildAt(0) as? TextView)

                val todayView =
                    expandedTodayView
                        ?: (view.getChildAt(1) as? TextView)

                val currentView =
                    expandedCurrentView
                        ?: (view.getChildAt(2) as? TextView)

                emojiView?.text = emojiText
                todayView?.text =
                    "Today: ${formatDuration(todayTotal)}"
                currentView?.text = "Current: $timerText"

                todayView?.setTextColor(timerColor)
                currentView?.setTextColor(timerColor)
            } else {
                val emojiView =
                    view.getChildAt(0) as? TextView

                val timerView =
                    view.getChildAt(1) as? TextView

                timerView?.text = timerText
                timerView?.setTextColor(timerColor)
                emojiView?.text = emojiText
            }

            view.visibility = View.VISIBLE
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
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val dayStart = calendar.timeInMillis
        if (now <= dayStart) return 0L

        val cacheIsValid =
            cachedUsagePackage == packageName &&
                    cachedUsageDayStart == dayStart &&
                    cachedUsageAt > 0L &&
                    now - cachedUsageAt < USAGE_TOTAL_REFRESH_INTERVAL

        if (cacheIsValid) {
            // Keep the total moving while the same tracked app remains open,
            // without replaying its full event history on every 1-second UI tick.
            return cachedTodayUsage + (now - cachedUsageAt)
        }

        val calculatedUsage = AppSenseUsageCalculator.getIntervals(
            context = this,
            packageName = packageName,
            dayStart = dayStart,
            dayEnd = now
        ).sumOf { it.endTime - it.startTime }

        cachedUsagePackage = packageName
        cachedUsageDayStart = dayStart
        cachedUsageAt = now
        cachedTodayUsage = calculatedUsage
        return calculatedUsage
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
        val channel = NotificationChannel(
            CHANNEL_ID,
            "AppSense Monitoring",
            NotificationManager.IMPORTANCE_LOW
        )
        channel.description =
            "Shows that AppSense is monitoring application usage."

        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("AppSense")
            .setContentText("Floating usage timer is active")
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {

        resetInterventionUi()
        abandonInterventionAudioFocus()
        interventionOverlay?.dismiss()
        isMonitoring = false
        cancelBreathingExercise()

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
