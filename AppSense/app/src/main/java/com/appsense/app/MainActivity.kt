package com.appsense.app

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.appsense.app.ui.theme.AppSenseTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

data class AppItem(
    val name: String,
    val packageName: String
)

data class AppUsage(
    val name: String,
    val packageName: String,
    val usageMillis: Long
)

data class UsageSession(
    val startTime: Long,
    val endTime: Long,
    val durationMillis: Long
)

/* =========================================================
   MAIN ACTIVITY
   ========================================================= */

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep Android Status Bar visible and make its
        // time / network / battery icons clearly visible.
        WindowCompat.setDecorFitsSystemWindows(window, false)

        WindowInsetsControllerCompat(
            window,
            window.decorView
        ).isAppearanceLightStatusBars = true

        setContent {
            AppSenseTheme {
                AppSenseApp()
            }
        }
    }
}

/* =========================================================
   PERMISSION
   ========================================================= */

private fun hasUsageAccess(context: Context): Boolean {

    val appOps =
        context.getSystemService(
            Context.APP_OPS_SERVICE
        ) as AppOpsManager

    val mode =
        appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            context.packageName
        )

    return mode == AppOpsManager.MODE_ALLOWED
}

private fun hasOverlayPermission(
    context: Context
): Boolean {

    return Settings.canDrawOverlays(context)
}

/* =========================================================
   INSTALLED APPS
   SYSTEM APPS INCLUDED
   ========================================================= */

private var installedAppsCache:
        List<AppItem>? = null

private fun getInstalledApps(
    context: Context,
    forceRefresh: Boolean = false
): List<AppItem> {

    if (!forceRefresh) {

        installedAppsCache?.let {
            return it
        }
    }

    val packageManager =
        context.packageManager

    val intent =
        Intent(Intent.ACTION_MAIN).apply {
            addCategory(
                Intent.CATEGORY_LAUNCHER
            )
        }

    val apps =
        packageManager
            .queryIntentActivities(
                intent,
                0
            )
            .mapNotNull { resolveInfo ->

                val packageName =
                    resolveInfo
                        .activityInfo
                        .packageName

                if (
                    packageName ==
                    context.packageName
                ) {
                    return@mapNotNull null
                }

                AppItem(
                    name =
                        resolveInfo
                            .loadLabel(
                                packageManager
                            )
                            .toString(),

                    packageName =
                        packageName
                )
            }
            .distinctBy {
                it.packageName
            }
            .sortedBy {
                it.name.lowercase()
            }

    installedAppsCache =
        apps

    return apps
}

/* =========================================================
   SELECTED APPS
   ========================================================= */

private fun loadSelectedApps(
    context: Context
): Set<String> {

    return context
        .getSharedPreferences(
            "appsense_preferences",
            Context.MODE_PRIVATE
        )
        .getStringSet(
            "tracked_apps",
            emptySet()
        )
        ?.toSet()
        ?: emptySet()
}

private fun saveSelectedApps(
    context: Context,
    selectedApps: Set<String>
) {

    context
        .getSharedPreferences(
            "appsense_preferences",
            Context.MODE_PRIVATE
        )
        .edit()
        .putStringSet(
            "tracked_apps",
            selectedApps
        )
        .apply()
}

/* =========================================================
   EVENT TYPES
   ========================================================= */

private fun foregroundEvent(): Int {

    return if (
        Build.VERSION.SDK_INT >=
        Build.VERSION_CODES.Q
    ) {

        UsageEvents.Event.ACTIVITY_RESUMED

    } else {

        UsageEvents.Event.MOVE_TO_FOREGROUND
    }
}

private fun backgroundEvent(): Int {

    return if (
        Build.VERSION.SDK_INT >=
        Build.VERSION_CODES.Q
    ) {

        UsageEvents.Event.ACTIVITY_PAUSED

    } else {

        UsageEvents.Event.MOVE_TO_BACKGROUND
    }
}

/* =========================================================
   DAY RANGE
   ========================================================= */

private fun getDayStart(
    date: Calendar
): Long {

    return Calendar.getInstance().apply {

        set(
            date.get(Calendar.YEAR),
            date.get(Calendar.MONTH),
            date.get(Calendar.DAY_OF_MONTH),
            0,
            0,
            0
        )

        set(
            Calendar.MILLISECOND,
            0
        )

    }.timeInMillis
}

private fun getDayEnd(
    date: Calendar
): Long {

    return Calendar.getInstance().apply {

        timeInMillis =
            getDayStart(date)

        add(
            Calendar.DAY_OF_MONTH,
            1
        )

    }.timeInMillis
}

/* =========================================================
   SESSION EXTRACTION
   ========================================================= */

private fun getUsageSessions(
    context: Context,
    packageName: String,
    date: Calendar
): List<UsageSession> {

    val dayStart =
        getDayStart(date)

    val dayEnd =
        getDayEnd(date)

    val now =
        System.currentTimeMillis()

    val queryEnd =
        minOf(
            dayEnd,
            now
        )

    if (
        queryEnd <= dayStart
    ) {
        return emptyList()
    }

    val usageManager =
        context.getSystemService(
            Context.USAGE_STATS_SERVICE
        ) as UsageStatsManager

    val events =
        usageManager.queryEvents(
            dayStart,
            queryEnd
        )

    val event =
        UsageEvents.Event()

    val sessions =
        mutableListOf<UsageSession>()

    var openTime:
            Long? = null

    while (
        events.hasNextEvent()
    ) {

        events.getNextEvent(event)

        if (
            event.packageName !=
            packageName
        ) {
            continue
        }

        when (
            event.eventType
        ) {

            foregroundEvent() -> {

                if (
                    openTime == null
                ) {

                    openTime =
                        event.timeStamp
                }
            }

            backgroundEvent() -> {

                val start =
                    openTime

                if (
                    start != null &&
                    event.timeStamp > start
                ) {

                    val actualStart =
                        maxOf(
                            start,
                            dayStart
                        )

                    val actualEnd =
                        minOf(
                            event.timeStamp,
                            dayEnd
                        )

                    if (
                        actualEnd >
                        actualStart
                    ) {

                        sessions.add(
                            UsageSession(
                                startTime =
                                    actualStart,

                                endTime =
                                    actualEnd,

                                durationMillis =
                                    actualEnd -
                                            actualStart
                            )
                        )
                    }

                    openTime =
                        null
                }
            }
        }
    }

    if (
        openTime != null
    ) {

        val actualStart =
            maxOf(
                openTime!!,
                dayStart
            )

        val actualEnd =
            queryEnd

        if (
            actualEnd >
            actualStart
        ) {

            sessions.add(
                UsageSession(
                    startTime =
                        actualStart,

                    endTime =
                        actualEnd,

                    durationMillis =
                        actualEnd -
                                actualStart
                )
            )
        }
    }

    return sessions.sortedBy {
        it.startTime
    }
}

/* =========================================================
   DAILY USAGE
   ========================================================= */

private fun getAppUsageForDate(
    context: Context,
    packageName: String,
    date: Calendar
): Long {

    val dayStart =
        getDayStart(date)

    val dayEnd =
        minOf(
            getDayEnd(date),
            System.currentTimeMillis()
        )

    if (
        dayEnd <= dayStart
    ) {
        return 0L
    }

    val usageManager =
        context.getSystemService(
            Context.USAGE_STATS_SERVICE
        ) as UsageStatsManager

    val queryStart =
        dayStart -
                (
                        24L *
                                60L *
                                60L *
                                1000L
                        )

    val events =
        usageManager.queryEvents(
            queryStart,
            dayEnd
        )

    val event =
        UsageEvents.Event()

    val activeActivities =
        mutableSetOf<String>()

    var sessionStart:
            Long? = null

    var totalUsage =
        0L

    while (
        events.hasNextEvent()
    ) {

        events.getNextEvent(event)

        if (
            event.packageName !=
            packageName
        ) {
            continue
        }

        val activityName =
            event.className ?: ""

        when (
            event.eventType
        ) {

            foregroundEvent() -> {

                val wasEmpty =
                    activeActivities.isEmpty()

                activeActivities.add(
                    activityName
                )

                if (
                    wasEmpty &&
                    event.timeStamp >=
                    dayStart
                ) {

                    sessionStart =
                        event.timeStamp
                }
            }

            backgroundEvent() -> {

                activeActivities.remove(
                    activityName
                )

                if (
                    activeActivities.isEmpty() &&
                    sessionStart != null
                ) {

                    val start =
                        maxOf(
                            sessionStart!!,
                            dayStart
                        )

                    val end =
                        minOf(
                            event.timeStamp,
                            dayEnd
                        )

                    if (
                        end > start
                    ) {

                        totalUsage +=
                            end - start
                    }

                    sessionStart =
                        null
                }
            }
        }

        if (
            event.timeStamp <
            dayStart &&
            activeActivities.isNotEmpty()
        ) {

            sessionStart =
                dayStart
        }
    }

    if (
        activeActivities.isNotEmpty() &&
        sessionStart != null
    ) {

        val start =
            maxOf(
                sessionStart!!,
                dayStart
            )

        val end =
            dayEnd

        if (
            end > start
        ) {

            totalUsage +=
                end - start
        }
    }

    return totalUsage
}

/* =========================================================
   HOURLY SESSION LIST
   ========================================================= */

private fun getHourlySessions(
    context: Context,
    packageName: String,
    date: Calendar
): List<UsageSession> {

    return getUsageSessions(
        context,
        packageName,
        date
    )
}

/* =========================================================
   FORMAT DURATION
   ========================================================= */

private fun formatUsageTime(
    millis: Long
): String {

    val totalSeconds =
        millis / 1000

    val hours =
        totalSeconds / 3600

    val minutes =
        (
                totalSeconds % 3600
                ) / 60

    val seconds =
        totalSeconds % 60

    return when {

        hours > 0 ->
            "${hours}h ${minutes}m"

        minutes > 0 ->
            "${minutes}m ${seconds}s"

        else ->
            "${seconds}s"
    }
}

/* =========================================================
   FORMAT CLOCK TIME
   ========================================================= */

private fun formatClockTime(
    millis: Long
): String {

    return SimpleDateFormat(
        "hh:mm:ss a",
        Locale.getDefault()
    ).format(
        Date(millis)
    )
}

/* =========================================================
   APP SENSE ROOT
   ========================================================= */

@Composable
fun AppSenseApp() {

    val context =
        androidx.compose.ui.platform
            .LocalContext.current

    var permissionGranted by remember {
        mutableStateOf(
            hasUsageAccess(context)
        )
    }

    var overlayPermissionGranted by remember {
        mutableStateOf(
            hasOverlayPermission(context)
        )
    }

    var showNameDialog by remember {
        mutableStateOf(false)
    }

    var userName by remember {
        mutableStateOf(
            context
                .getSharedPreferences(
                    "appsense_preferences",
                    Context.MODE_PRIVATE
                )
                .getString(
                    "user_name",
                    ""
                )
                ?.trim()
                .orEmpty()
        )
    }

    // Splash is shown only after the required
    // permissions are available.
    var showSplash by remember {
        mutableStateOf(false)
    }

    var splashHandled by remember {
        mutableStateOf(false)
    }

    var currentScreen by remember {
        mutableStateOf("dashboard")
    }

    var detailPackage by remember {
        mutableStateOf<String?>(null)
    }

    val lifecycleOwner =
        androidx.lifecycle.compose
            .LocalLifecycleOwner.current

    DisposableEffect(
        lifecycleOwner
    ) {

        val observer =
            LifecycleEventObserver {
                    _, event ->

                if (
                    event ==
                    Lifecycle.Event.ON_RESUME
                ) {

                    permissionGranted =
                        hasUsageAccess(context)

                    overlayPermissionGranted =
                        hasOverlayPermission(
                            context
                        )
                }
            }

        lifecycleOwner.lifecycle
            .addObserver(observer)

        onDispose {

            lifecycleOwner.lifecycle
                .removeObserver(observer)
        }
    }

    /*
     * Splash flow:
     *
     * Usage Access
     *      ↓
     * Display over other apps
     *      ↓
     * AppSense splash
     *      ↓
     * Name dialog (first setup only)
     *      ↓
     * Dashboard
     *
     * Existing permissions and onboarding are preserved.
     */
    LaunchedEffect(
        permissionGranted,
        overlayPermissionGranted
    ) {

        if (
            permissionGranted &&
            overlayPermissionGranted &&
            !splashHandled
        ) {

            splashHandled = true
            showSplash = true

            // Start the existing floating-timer monitor while
            // the splash is showing, so it is ready when the
            // dashboard/app usage flow begins.
            val serviceIntent =
                Intent(
                    context,
                    UsageMonitorService::class.java
                )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }

            // Keep the splash short and smooth.
            delay(1800L)

            showSplash = false

            val savedName =
                context
                    .getSharedPreferences(
                        "appsense_preferences",
                        Context.MODE_PRIVATE
                    )
                    .getString(
                        "user_name",
                        null
                    )

            if (
                savedName.isNullOrBlank()
            ) {

                showNameDialog = true
            }
        }
    }

    /*
     * Preload installed apps while the splash/dashboard
     * flow is running, so Add Apps remains fast.
     */
    LaunchedEffect(
        permissionGranted,
        overlayPermissionGranted
    ) {

        if (
            permissionGranted &&
            overlayPermissionGranted &&
            installedAppsCache == null
        ) {

            withContext(
                Dispatchers.IO
            ) {

                getInstalledApps(
                    context
                )
            }
        }
    }

    if (!permissionGranted || !overlayPermissionGranted) {

        PermissionSetupScreen(
            usageAccessGranted = permissionGranted,
            overlayAccessGranted = overlayPermissionGranted,

            onGrantUsageAccess = {
                context.startActivity(
                    Intent(
                        Settings
                            .ACTION_USAGE_ACCESS_SETTINGS
                    )
                )
            },

            onGrantOverlayAccess = {
                val intent =
                    Intent(
                        Settings
                            .ACTION_MANAGE_OVERLAY_PERMISSION,

                        Uri.parse(
                            "package:${context.packageName}"
                        )
                    )

                context.startActivity(
                    intent
                )
            }
        )

        return
    }

    if (showSplash) {

        AppSenseSplashScreen()

        return
    }

    if (showNameDialog) {

        AlertDialog(
            onDismissRequest = {
                // Name must be entered before continuing.
            },

            shape = RoundedCornerShape(28.dp),

            title = {
                Column {
                    Text(
                        text = "Welcome to AppSense 👋",
                        style = MaterialTheme.typography.headlineSmall
                    )

                    Spacer(
                        modifier = Modifier.height(6.dp)
                    )

                    Text(
                        text = "What should we call you?",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            },

            text = {

                Column {

                    Spacer(
                        modifier = Modifier.height(8.dp)
                    )

                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth(),

                        value = userName,

                        onValueChange = {
                            userName = it
                        },

                        singleLine = true,

                        shape = RoundedCornerShape(16.dp),

                        placeholder = {
                            Text(
                                "Enter your name"
                            )
                        },

                        leadingIcon = {
                            Text(
                                text = "👤"
                            )
                        }
                    )

                    Spacer(
                        modifier = Modifier.height(10.dp)
                    )

                    Text(
                        text = "We'll use this to personalize your AppSense dashboard.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },

            confirmButton = {

                Button(
                    modifier = Modifier.fillMaxWidth(),

                    enabled = userName.trim().isNotEmpty(),

                    shape = RoundedCornerShape(14.dp),

                    onClick = {

                        val finalName = userName.trim()

                        context
                            .getSharedPreferences(
                                "appsense_preferences",
                                Context.MODE_PRIVATE
                            )
                            .edit()
                            .putString(
                                "user_name",
                                finalName
                            )
                            .apply()

                        // Update Compose state immediately so the dashboard
                        // shows the name without requiring an app restart.
                        userName = finalName
                        showNameDialog = false
                    }
                ) {

                    Text(
                        "Continue  →"
                    )
                }
            }
        )
    }

    androidx.activity.compose
        .BackHandler(
            enabled =
                currentScreen !=
                        "dashboard"
        ) {

            when (
                currentScreen
            ) {

                "detail" -> {
                    currentScreen =
                        "stats"
                }

                "stats" -> {
                    currentScreen =
                        "dashboard"
                }

                "add_app" -> {
                    currentScreen =
                        "dashboard"
                }
            }
        }

    when (
        currentScreen
    ) {

        "dashboard" -> {

            DashboardScreen(
                context = context,
                savedName = userName,

                onAddApp = {
                    currentScreen =
                        "add_app"
                },

                onStats = {
                    currentScreen =
                        "stats"
                }
            )
        }

        "add_app" -> {

            AddAppScreen(
                context =
                    context,

                onBack = {
                    currentScreen =
                        "dashboard"
                },

                onSaved = {
                    currentScreen =
                        "dashboard"
                }
            )
        }

        "stats" -> {

            AddedAppStatsScreen(
                context =
                    context,

                onBack = {
                    currentScreen =
                        "dashboard"
                },

                onAppClick = {
                        packageName ->

                    detailPackage =
                        packageName

                    currentScreen =
                        "detail"
                }
            )
        }

        "detail" -> {

            detailPackage?.let {

                AppDetailScreen(
                    context =
                        context,

                    packageName =
                        it,

                    onBack = {
                        currentScreen =
                            "stats"
                    }
                )
            }
        }
    }
}

/* =========================================================
   SPLASH SCREEN
   ========================================================= */

@Composable
private fun AppSenseSplashScreen() {

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(
                    Color.Black
                )
    ) {

        androidx.compose.foundation.Image(
            painter =
                painterResource(
                    id =
                        R.drawable.appsense_splash
                ),

            contentDescription =
                "AppSense",

            modifier =
                Modifier.fillMaxSize(),

            contentScale =
                ContentScale.Crop
        )
    }
}

/* =========================================================
   PERMISSION SETUP
   ========================================================= */

@Composable
fun PermissionSetupScreen(
    usageAccessGranted: Boolean,
    overlayAccessGranted: Boolean,
    onGrantUsageAccess: () -> Unit,
    onGrantOverlayAccess: () -> Unit
) {

    val allGranted =
        usageAccessGranted && overlayAccessGranted

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {

        Text(
            text = "AppSense",
            style = MaterialTheme.typography.headlineLarge
        )

        Spacer(
            modifier = Modifier.height(12.dp)
        )

        Text(
            text = "Set up AppSense"
        )

        Spacer(
            modifier = Modifier.height(28.dp)
        )

        PermissionCard(
            title = "Usage Access",
            description = "Track the apps you choose and calculate your usage time.",
            granted = usageAccessGranted,
            buttonText = "Allow Usage Access",
            onClick = onGrantUsageAccess
        )

        Spacer(
            modifier = Modifier.height(16.dp)
        )

        PermissionCard(
            title = "Display over other apps",
            description = "Show the AppSense floating timer while you use your apps.",
            granted = overlayAccessGranted,
            buttonText = "Allow Display Access",
            onClick = onGrantOverlayAccess
        )

        Spacer(
            modifier = Modifier.height(28.dp)
        )

        Button(
            modifier = Modifier.fillMaxWidth(),
            enabled = allGranted,
            onClick = {
                // AppSenseApp automatically continues when both
                // permissions are detected.
            }
        ) {
            Text(
                if (allGranted) "Continue" else "Complete both accesses"
            )
        }
    }
}

@Composable
private fun PermissionCard(
    title: String,
    description: String,
    granted: Boolean,
    buttonText: String,
    onClick: () -> Unit
) {

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = Color.White,
        tonalElevation = 2.dp
    ) {

        Column(
            modifier = Modifier.padding(18.dp)
        ) {

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {

                Text(
                    text = if (granted) "✓" else "○",
                    style = MaterialTheme.typography.headlineSmall
                )

                Spacer(
                    modifier = Modifier.width(12.dp)
                )

                Column(
                    modifier = Modifier.weight(1f)
                ) {

                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium
                    )

                    Spacer(
                        modifier = Modifier.height(4.dp)
                    )

                    Text(
                        text = if (granted) {
                            "Access granted"
                        } else {
                            description
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            if (!granted) {

                Spacer(
                    modifier = Modifier.height(14.dp)
                )

                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onClick
                ) {
                    Text(buttonText)
                }
            }
        }
    }
}

/* =========================================================
   DASHBOARD
   ========================================================= */

@Composable
fun DashboardScreen(
    context: Context,
    savedName: String,
    onAddApp: () -> Unit,
    onStats: () -> Unit
) {

    val selectedApps = remember {
        loadSelectedApps(context)
    }

    val today = remember {
        Calendar.getInstance()
    }

    val totalUsage = remember(selectedApps) {
        selectedApps.sumOf { packageName ->
            getAppUsageForDate(
                context,
                packageName,
                today
            )
        }
    }

    val dateText = remember {
        SimpleDateFormat(
            "EEEE, d MMMM",
            Locale.getDefault()
        ).format(Date())
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .background(Color(0xFFF7F8FA))
    ) {

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 22.dp, vertical = 20.dp)
        ) {

            // -------------------------------------------------
            // HEADER
            // -------------------------------------------------
            Text(
                text = dateText.uppercase(Locale.getDefault()),
                style = MaterialTheme.typography.labelMedium,
                color = Color(0xFF747985)
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = if (savedName.isNotEmpty()) {
                    "Good to see you, $savedName"
                } else {
                    "Good to see you"
                },
                style = MaterialTheme.typography.headlineMedium
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "Make today intentional.",
                style = MaterialTheme.typography.bodyLarge,
                color = Color(0xFF6D717B)
            )

            Spacer(modifier = Modifier.height(24.dp))

            // -------------------------------------------------
            // PRIMARY USAGE CARD
            // -------------------------------------------------
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(28.dp),
                color = Color(0xFF16181D),
                tonalElevation = 0.dp
            ) {
                Column(
                    modifier = Modifier.padding(24.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Top
                    ) {
                        Column(
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = "TODAY'S SCREEN TIME",
                                style = MaterialTheme.typography.labelMedium,
                                color = Color(0xFFB9BDC6)
                            )

                            Spacer(modifier = Modifier.height(10.dp))

                            Text(
                                text = formatUsageTime(totalUsage),
                                style = MaterialTheme.typography.displaySmall,
                                color = Color.White
                            )
                        }

                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = Color.White.copy(alpha = 0.10f)
                        ) {
                            Text(
                                text = "TODAY",
                                modifier = Modifier.padding(
                                    horizontal = 12.dp,
                                    vertical = 8.dp
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(22.dp))

                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        color = Color.White.copy(alpha = 0.08f)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (selectedApps.isEmpty()) {
                                    "No apps are being tracked yet"
                                } else {
                                    "Tracking ${selectedApps.size} ${if (selectedApps.size == 1) "app" else "apps"}"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color(0xFFE5E7EB)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // -------------------------------------------------
            // TRACKING STATUS
            // -------------------------------------------------
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                color = Color.White,
                tonalElevation = 1.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = "TRACKED APPS",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF858993)
                        )

                        Spacer(modifier = Modifier.height(5.dp))

                        Text(
                            text = if (selectedApps.isEmpty()) {
                                "No apps added yet"
                            } else {
                                "${selectedApps.size} ${if (selectedApps.size == 1) "app" else "apps"} being tracked"
                            },
                            style = MaterialTheme.typography.titleMedium
                        )
                    }

                    if (selectedApps.isEmpty()) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color(0xFFF0F1F3)
                        ) {
                            Text(
                                text = "Get started",
                                modifier = Modifier.padding(
                                    horizontal = 11.dp,
                                    vertical = 7.dp
                                ),
                                style = MaterialTheme.typography.labelMedium,
                                color = Color(0xFF5F636D)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // -------------------------------------------------
            // PRIMARY ACTION
            // -------------------------------------------------
            Button(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(58.dp),
                shape = RoundedCornerShape(18.dp),
                enabled = selectedApps.isNotEmpty(),
                onClick = onStats
            ) {
                Text(
                    text = "View today's usage",
                    style = MaterialTheme.typography.titleMedium
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            OutlinedButton(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                shape = RoundedCornerShape(18.dp),
                onClick = onAddApp
            ) {
                Text(
                    text = "Manage tracked apps",
                    style = MaterialTheme.typography.titleMedium
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // -------------------------------------------------
            // QUIET PRODUCTIVITY MESSAGE
            // -------------------------------------------------
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFFE9EBEF)
                ) {
                    Spacer(
                        modifier = Modifier
                            .width(4.dp)
                            .height(34.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Text(
                    text = "Awareness first. Use your screen time with intention.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF737782)
                )
            }
        }
    }
}

/* =========================================================
   ADD APP
   ========================================================= */

@Composable
fun AddAppScreen(
    context: Context,
    onBack: () -> Unit,
    onSaved: () -> Unit
) {

    var allApps by remember {
        mutableStateOf<
                List<AppItem>
                >(emptyList())
    }

    var refreshKey by remember {
        mutableIntStateOf(0)
    }

    DisposableEffect(context) {

        val packageReceiver =
            object :
                BroadcastReceiver() {

                override fun onReceive(
                    context: Context?,
                    intent: Intent?
                ) {

                    when (
                        intent?.action
                    ) {

                        Intent.ACTION_PACKAGE_ADDED,
                        Intent.ACTION_PACKAGE_REMOVED,
                        Intent.ACTION_PACKAGE_CHANGED -> {

                            installedAppsCache =
                                null

                            refreshKey++
                        }
                    }
                }
            }

        val filter =
            IntentFilter().apply {

                addAction(
                    Intent.ACTION_PACKAGE_ADDED
                )

                addAction(
                    Intent.ACTION_PACKAGE_REMOVED
                )

                addAction(
                    Intent.ACTION_PACKAGE_CHANGED
                )

                addDataScheme(
                    "package"
                )
            }

        ContextCompat.registerReceiver(
            context,
            packageReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        onDispose {

            context.unregisterReceiver(
                packageReceiver
            )
        }
    }

    LaunchedEffect(
        refreshKey
    ) {

        val apps =
            withContext(
                Dispatchers.IO
            ) {

                getInstalledApps(
                    context
                )
            }

        allApps =
            apps
    }

    var searchText by remember {
        mutableStateOf("")
    }

    var selectedApps by remember {
        mutableStateOf(
            loadSelectedApps(
                context
            )
        )
    }

    val filteredApps =
        remember(
            searchText,
            allApps
        ) {

            if (
                searchText.isBlank()
            ) {

                allApps

            } else {

                allApps.filter {

                    it.name.contains(
                        searchText,
                        ignoreCase = true
                    )
                }
            }
        }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(
                start = 20.dp,
                end = 20.dp
            )
    ) {

        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(
                        top = 12.dp,
                        bottom = 10.dp
                    ),

            verticalAlignment =
                Alignment.CenterVertically
        ) {

            Button(
                onClick =
                    onBack
            ) {

                Text(
                    "←"
                )
            }

            Spacer(
                modifier =
                    Modifier.width(12.dp)
            )

            Text(
                text =
                    "Add Apps",

                style =
                    MaterialTheme.typography
                        .headlineSmall
            )
        }

        OutlinedTextField(
            modifier =
                Modifier.fillMaxWidth(),

            value =
                searchText,

            onValueChange = {
                searchText = it
            },

            singleLine = true,

            placeholder = {
                Text(
                    "🔍  Find an app..."
                )
            }
        )

        Spacer(
            modifier =
                Modifier.height(10.dp)
        )

        Text(
            text =
                "${selectedApps.size} apps selected"
        )

        LazyColumn(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
        ) {

            items(
                filteredApps,
                key = {
                    it.packageName
                }
            ) { app ->

                val selected =
                    selectedApps.contains(
                        app.packageName
                    )

                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable {

                                selectedApps =
                                    if (
                                        selected
                                    ) {

                                        selectedApps -
                                                app.packageName

                                    } else {

                                        selectedApps +
                                                app.packageName
                                    }
                            }
                            .padding(
                                vertical = 3.dp
                            ),

                    verticalAlignment =
                        Alignment.CenterVertically
                ) {

                    Checkbox(
                        checked =
                            selected,

                        onCheckedChange =
                            { checked ->

                                selectedApps =
                                    if (
                                        checked
                                    ) {

                                        selectedApps +
                                                app.packageName

                                    } else {

                                        selectedApps -
                                                app.packageName
                                    }
                            }
                    )

                    Text(
                        text =
                            app.name,

                        modifier =
                            Modifier.padding(
                                start = 6.dp
                            )
                    )
                }
            }
        }

        Button(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(
                        top = 8.dp,
                        bottom = 16.dp
                    ),

            onClick = {

                saveSelectedApps(
                    context,
                    selectedApps
                )

                onSaved()
            }
        ) {

            Text(
                "Save"
            )
        }
    }
}

/* =========================================================
   ADDED APP STATS
   ========================================================= */

@Composable
fun AddedAppStatsScreen(
    context: Context,
    onBack: () -> Unit,
    onAppClick: (String) -> Unit
) {

    var refresh by remember {
        mutableStateOf(0)
    }

    val selectedApps =
        remember(refresh) {
            loadSelectedApps(
                context
            )
        }

    val today =
        remember(refresh) {
            Calendar.getInstance()
        }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(
                start = 20.dp,
                end = 20.dp
            )
    ) {

        Row(
            modifier =
                Modifier.fillMaxWidth()
                    .padding(
                        top = 12.dp,
                        bottom = 12.dp
                    ),

            verticalAlignment =
                Alignment.CenterVertically
        ) {

            Button(
                onClick =
                    onBack
            ) {

                Text(
                    "←"
                )
            }

            Spacer(
                modifier =
                    Modifier.width(12.dp)
            )

            Text(
                text =
                    "App Stats",

                style =
                    MaterialTheme.typography
                        .headlineSmall
            )
        }

        Text(
            text =
                "Today",

            style =
                MaterialTheme.typography
                    .titleLarge
        )

        Spacer(
            modifier =
                Modifier.height(12.dp)
        )

        if (
            selectedApps.isEmpty()
        ) {

            Text(
                "No apps added yet."
            )

        } else {

            LazyColumn {

                items(
                    selectedApps.toList()
                ) { packageName ->

                    val info =
                        try {

                            context.packageManager
                                .getApplicationInfo(
                                    packageName,
                                    0
                                )

                        } catch (
                            _: Exception
                        ) {
                            null
                        }

                    if (
                        info != null
                    ) {

                        val name =
                            context.packageManager
                                .getApplicationLabel(
                                    info
                                )
                                .toString()

                        val usage =
                            getAppUsageForDate(
                                context,
                                packageName,
                                today
                            )

                        val sessionCount =
                            getUsageSessions(
                                context,
                                packageName,
                                today
                            ).size

                        AppUsageCard(
                            name =
                                name,

                            usageMillis =
                                usage,

                            sessionCount =
                                sessionCount,

                            onClick = {
                                onAppClick(
                                    packageName
                                )
                            }
                        )
                    }
                }
            }
        }
    }
}

/* =========================================================
   APP CARD
   ========================================================= */

@Composable
fun AppUsageCard(
    name: String,
    usageMillis: Long,
    sessionCount: Int,
    onClick: () -> Unit
) {

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(
                    Color.White,
                    RoundedCornerShape(22.dp)
                )
                .clickable {
                    onClick()
                }
                .padding(18.dp)
    ) {

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {

            Box(
                modifier =
                    Modifier
                        .width(52.dp)
                        .height(52.dp)
                        .background(
                            Color(0xFFE8EEFF),
                            RoundedCornerShape(16.dp)
                        ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text =
                        name.firstOrNull()
                            ?.uppercase()
                            ?: "A",
                    style =
                        MaterialTheme.typography.headlineSmall
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleMedium
                )

                Spacer(modifier = Modifier.height(3.dp))

                Text(
                    text =
                        if (sessionCount == 1) {
                            "1 session today"
                        } else {
                            "$sessionCount sessions today"
                        },
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Column(
                horizontalAlignment = Alignment.End
            ) {
                Text(
                    text = formatUsageTime(usageMillis),
                    style = MaterialTheme.typography.titleMedium
                )

                Text(
                    text = "Today",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        Text(
            text = "Tap to view detailed usage →",
            style = MaterialTheme.typography.bodySmall
        )
    }
}

/* =========================================================
   APP DETAIL
   ========================================================= */

@Composable
fun AppDetailScreen(
    context: Context,
    packageName: String,
    onBack: () -> Unit
) {

    val appName =
        remember(packageName) {
            try {
                val info = context.packageManager.getApplicationInfo(packageName, 0)
                context.packageManager.getApplicationLabel(info).toString()
            } catch (_: Exception) {
                "App"
            }
        }

    var selectedDate by remember {
        mutableStateOf(Calendar.getInstance())
    }

    var mode by remember {
        mutableStateOf("Daily")
    }

    var menuExpanded by remember {
        mutableStateOf(false)
    }

    var showCalendar by remember {
        mutableStateOf(false)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(20.dp)
    ) {

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(onClick = onBack) {
                Text("←")
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = appName,
                    style = MaterialTheme.typography.headlineSmall
                )
                Text(
                    text = "Usage insights",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Date selector — original functionality retained.
        OutlinedButton(
            modifier = Modifier.fillMaxWidth(),
            onClick = { showCalendar = true }
        ) {
            Text(
                text = "📅  " + SimpleDateFormat(
                    "EEE, d MMM yyyy",
                    Locale.getDefault()
                ).format(selectedDate.time)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Daily / Hourly selector — original functionality retained.
        Box(modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = { menuExpanded = true }
            ) {
                Text("$mode  ▼")
            }

            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false }
            ) {
                DropdownMenuItem(
                    text = { Text("Daily") },
                    onClick = {
                        mode = "Daily"
                        menuExpanded = false
                    }
                )
                DropdownMenuItem(
                    text = { Text("Hourly") },
                    onClick = {
                        mode = "Hourly"
                        menuExpanded = false
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Keep the original Daily / Hourly data views.
        if (mode == "Daily") {
            DailyDetail(
                context = context,
                packageName = packageName,
                date = selectedDate
            )
        } else {
            HourlyDetail(
                context = context,
                packageName = packageName,
                date = selectedDate
            )
        }
    }

    if (showCalendar) {
        MiniCalendar(
            selectedDate = selectedDate,
            onDateSelected = { date ->
                selectedDate = date
                showCalendar = false
            },
            onDismiss = {
                showCalendar = false
            }
        )
    }
}

/* =========================================================
   DAILY DETAIL
   ========================================================= */

@Composable
fun DailyDetail(
    context: Context,
    packageName: String,
    date: Calendar
) {

    val total = remember(packageName, date.timeInMillis) {
        getAppUsageForDate(context, packageName, date)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Color.White,
                RoundedCornerShape(22.dp)
            )
            .padding(24.dp)
    ) {
        Text(
            text = "Today's usage",
            style = MaterialTheme.typography.titleMedium
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "Total time spent in $packageName",
            style = MaterialTheme.typography.bodySmall
        )

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text = formatUsageTime(total),
            style = MaterialTheme.typography.headlineMedium
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "Focus on awareness, not just the number.",
            style = MaterialTheme.typography.bodySmall
        )
    }
}

/* =========================================================
   HOURLY DETAIL
   ========================================================= */

@Composable
fun HourlyDetail(
    context: Context,
    packageName: String,
    date: Calendar
) {

    val sessions = remember(packageName, date.timeInMillis) {
        getHourlySessions(context, packageName, date)
    }

    if (sessions.isEmpty()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Color.White,
                    RoundedCornerShape(22.dp)
                )
                .padding(24.dp)
        ) {
            Text(
                text = "No usage sessions",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "There is no recorded activity for this date.",
                style = MaterialTheme.typography.bodySmall
            )
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxWidth()
    ) {
        item {
            Text(
                text = "Activity timeline",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }

        items(sessions) { session ->
            UsageSessionCard(session)
        }
    }
}

/* =========================================================
   SESSION CARD
   ========================================================= */

@Composable
fun UsageSessionCard(
    session: UsageSession
) {

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
            .background(
                Color.White,
                RoundedCornerShape(18.dp)
            )
            .padding(18.dp)
    ) {

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = "OPEN",
                    style = MaterialTheme.typography.labelMedium
                )
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = formatClockTime(session.startTime),
                    style = MaterialTheme.typography.titleMedium
                )
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "CLOSE",
                    style = MaterialTheme.typography.labelMedium
                )
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = formatClockTime(session.endTime),
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "Used ${formatUsageTime(session.durationMillis)}",
            style = MaterialTheme.typography.titleMedium
        )
    }
}

/* =========================================================
   MINI CALENDAR
   ========================================================= */

@Composable
fun MiniCalendar(
    selectedDate: Calendar,
    onDateSelected: (Calendar) -> Unit,
    onDismiss: () -> Unit
) {

    val today =
        remember {

            Calendar.getInstance()
                .apply {

                    set(
                        Calendar.HOUR_OF_DAY,
                        0
                    )

                    set(
                        Calendar.MINUTE,
                        0
                    )

                    set(
                        Calendar.SECOND,
                        0
                    )

                    set(
                        Calendar.MILLISECOND,
                        0
                    )
                }
        }

    var visibleMonth by remember {

        mutableStateOf(
            selectedDate.clone()
                    as Calendar
        )
    }

    LaunchedEffect(
        selectedDate
    ) {

        visibleMonth =
            (
                    selectedDate.clone()
                            as Calendar
                    ).apply {

                    set(
                        Calendar.DAY_OF_MONTH,
                        1
                    )

                    set(
                        Calendar.HOUR_OF_DAY,
                        0
                    )

                    set(
                        Calendar.MINUTE,
                        0
                    )

                    set(
                        Calendar.SECOND,
                        0
                    )

                    set(
                        Calendar.MILLISECOND,
                        0
                    )
                }
    }

    val currentMonthIndex =
        today.get(
            Calendar.YEAR
        ) * 12 +
                today.get(
                    Calendar.MONTH
                )

    val visibleMonthIndex =
        visibleMonth.get(
            Calendar.YEAR
        ) * 12 +
                visibleMonth.get(
                    Calendar.MONTH
                )

    val canGoForward =
        visibleMonthIndex <
                currentMonthIndex

    Dialog(
        onDismissRequest =
            onDismiss
    ) {

        Surface(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = 20.dp
                    ),

            shape =
                RoundedCornerShape(
                    24.dp
                ),

            color =
                Color.White,

            tonalElevation =
                8.dp
        ) {

            Column(
                modifier =
                    Modifier.padding(
                        18.dp
                    )
            ) {

                Row(
                    modifier =
                        Modifier.fillMaxWidth(),

                    horizontalArrangement =
                        Arrangement.SpaceBetween,

                    verticalAlignment =
                        Alignment.CenterVertically
                ) {

                    Text(
                        text =
                            "Select Date",

                        style =
                            MaterialTheme.typography
                                .titleLarge
                    )

                    TextButton(
                        onClick =
                            onDismiss
                    ) {

                        Text(
                            "✕"
                        )
                    }
                }

                Spacer(
                    modifier =
                        Modifier.height(8.dp)
                )

                Row(
                    modifier =
                        Modifier.fillMaxWidth(),

                    horizontalArrangement =
                        Arrangement.SpaceBetween,

                    verticalAlignment =
                        Alignment.CenterVertically
                ) {

                    IconButton(
                        onClick = {

                            val previousMonth =
                                visibleMonth
                                    .clone()
                                        as Calendar

                            previousMonth.add(
                                Calendar.MONTH,
                                -1
                            )

                            previousMonth.set(
                                Calendar.DAY_OF_MONTH,
                                1
                            )

                            visibleMonth =
                                previousMonth
                        }
                    ) {

                        Text(
                            text =
                                "‹",

                            style =
                                MaterialTheme.typography
                                    .headlineMedium
                        )
                    }

                    Text(
                        text =
                            SimpleDateFormat(
                                "MMMM yyyy",
                                Locale.getDefault()
                            ).format(
                                visibleMonth.time
                            ),

                        style =
                            MaterialTheme.typography
                                .titleMedium
                    )

                    IconButton(
                        enabled =
                            canGoForward,

                        onClick = {

                            if (
                                canGoForward
                            ) {

                                val nextMonth =
                                    visibleMonth
                                        .clone()
                                            as Calendar

                                nextMonth.add(
                                    Calendar.MONTH,
                                    1
                                )

                                nextMonth.set(
                                    Calendar.DAY_OF_MONTH,
                                    1
                                )

                                visibleMonth =
                                    nextMonth
                            }
                        }
                    ) {

                        Text(
                            text =
                                "›",

                            style =
                                MaterialTheme.typography
                                    .headlineMedium
                        )
                    }
                }

                Spacer(
                    modifier =
                        Modifier.height(8.dp)
                )

                Row(
                    modifier =
                        Modifier.fillMaxWidth()
                ) {

                    listOf(
                        "S",
                        "M",
                        "T",
                        "W",
                        "T",
                        "F",
                        "S"
                    ).forEach {
                            dayName ->

                        Box(
                            modifier =
                                Modifier
                                    .weight(1f)
                                    .height(32.dp),

                            contentAlignment =
                                Alignment.Center
                        ) {

                            Text(
                                text =
                                    dayName,

                                style =
                                    MaterialTheme.typography
                                        .labelMedium
                            )
                        }
                    }
                }

                val firstDayOfMonth =
                    visibleMonth
                        .clone()
                            as Calendar

                firstDayOfMonth.set(
                    Calendar.DAY_OF_MONTH,
                    1
                )

                val daysInMonth =
                    firstDayOfMonth
                        .getActualMaximum(
                            Calendar.DAY_OF_MONTH
                        )

                val leadingEmptyDays =
                    firstDayOfMonth.get(
                        Calendar.DAY_OF_WEEK
                    ) - 1

                val totalCells =
                    leadingEmptyDays +
                            daysInMonth

                val numberOfRows =
                    (
                            totalCells + 6
                            ) / 7

                Column(
                    modifier =
                        Modifier.fillMaxWidth()
                ) {

                    repeat(
                        numberOfRows
                    ) { row ->

                        Row(
                            modifier =
                                Modifier.fillMaxWidth()
                        ) {

                            repeat(
                                7
                            ) { column ->

                                val cellIndex =
                                    row * 7 +
                                            column

                                val day =
                                    cellIndex -
                                            leadingEmptyDays +
                                            1

                                if (
                                    day < 1 ||
                                    day > daysInMonth
                                ) {

                                    Box(
                                        modifier =
                                            Modifier
                                                .weight(1f)
                                                .height(42.dp)
                                    )

                                } else {

                                    val cellDate =
                                        Calendar
                                            .getInstance()
                                            .apply {

                                                set(
                                                    Calendar.YEAR,
                                                    visibleMonth.get(
                                                        Calendar.YEAR
                                                    )
                                                )

                                                set(
                                                    Calendar.MONTH,
                                                    visibleMonth.get(
                                                        Calendar.MONTH
                                                    )
                                                )

                                                set(
                                                    Calendar.DAY_OF_MONTH,
                                                    day
                                                )

                                                set(
                                                    Calendar.HOUR_OF_DAY,
                                                    0
                                                )

                                                set(
                                                    Calendar.MINUTE,
                                                    0
                                                )

                                                set(
                                                    Calendar.SECOND,
                                                    0
                                                )

                                                set(
                                                    Calendar.MILLISECOND,
                                                    0
                                                )
                                            }

                                    val isFuture =
                                        cellDate.after(
                                            today
                                        )

                                    val isSelected =
                                        cellDate.get(
                                            Calendar.YEAR
                                        ) ==
                                                selectedDate.get(
                                                    Calendar.YEAR
                                                ) &&
                                                cellDate.get(
                                                    Calendar.MONTH
                                                ) ==
                                                selectedDate.get(
                                                    Calendar.MONTH
                                                ) &&
                                                cellDate.get(
                                                    Calendar.DAY_OF_MONTH
                                                ) ==
                                                selectedDate.get(
                                                    Calendar.DAY_OF_MONTH
                                                )

                                    Box(
                                        modifier =
                                            Modifier
                                                .weight(1f)
                                                .height(42.dp)
                                                .padding(2.dp)
                                                .background(
                                                    if (
                                                        isSelected
                                                    ) {
                                                        Color(
                                                            0xFFE1EAFF
                                                        )
                                                    } else {
                                                        Color.Transparent
                                                    },
                                                    RoundedCornerShape(
                                                        12.dp
                                                    )
                                                )
                                                .clickable(
                                                    enabled =
                                                        !isFuture
                                                ) {

                                                    val chosenDate =
                                                        cellDate
                                                            .clone()
                                                                as Calendar

                                                    onDateSelected(
                                                        chosenDate
                                                    )
                                                },

                                        contentAlignment =
                                            Alignment.Center
                                    ) {

                                        Text(
                                            text =
                                                day.toString(),

                                            color =
                                                if (
                                                    isFuture
                                                ) {
                                                    Color.LightGray
                                                } else {
                                                    Color.Unspecified
                                                },

                                            style =
                                                MaterialTheme.typography
                                                    .bodyMedium
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(
                    modifier =
                        Modifier.height(10.dp)
                )

                OutlinedButton(
                    modifier =
                        Modifier.fillMaxWidth(),

                    onClick = {

                        val todayCopy =
                            today.clone()
                                    as Calendar

                        onDateSelected(
                            todayCopy
                        )

                        onDismiss()
                    }
                ) {

                    Text(
                        "Today"
                    )
                }
            }
        }
    }
}
