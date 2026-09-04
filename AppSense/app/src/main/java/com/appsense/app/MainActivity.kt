

package com.appsense.app

import androidx.compose.material3.TextButton
import androidx.compose.material3.IconButton
import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.net.Uri
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
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.appsense.app.ui.theme.AppSenseTheme
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import androidx.compose.ui.window.Dialog
import androidx.compose.material3.Surface
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.content.BroadcastReceiver
import android.content.IntentFilter

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

    val mode = appOps.unsafeCheckOpNoThrow(
        AppOpsManager.OPSTR_GET_USAGE_STATS,
        android.os.Process.myUid(),
        context.packageName
    )

    return mode == AppOpsManager.MODE_ALLOWED
}

private fun hasOverlayPermission(context: Context): Boolean {
    return Settings.canDrawOverlays(context)
}

/* =========================================================
   INSTALLED APPS
   SYSTEM APPS INCLUDED
   ========================================================= */
private var installedAppsCache: List<AppItem>? = null
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
            addCategory(Intent.CATEGORY_LAUNCHER)
        }

    val apps = packageManager
        .queryIntentActivities(intent, 0)
        .mapNotNull { resolveInfo ->

            val packageName =
                resolveInfo.activityInfo.packageName

            if (packageName == context.packageName) {
                return@mapNotNull null
            }

            AppItem(
                name = resolveInfo
                    .loadLabel(packageManager)
                    .toString(),
                packageName = packageName
            )
        }
        .distinctBy {
            it.packageName
        }
        .sortedBy {
            it.name.lowercase()
        }

    installedAppsCache = apps

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
        minOf(dayEnd, now)

    if (queryEnd <= dayStart) {
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

    var openTime: Long? = null

    while (events.hasNextEvent()) {

        events.getNextEvent(event)

        if (
            event.packageName != packageName
        ) {
            continue
        }

        when (event.eventType) {

            foregroundEvent() -> {

                /*
                 * Start a new session only if
                 * there isn't already an open one.
                 */
                if (openTime == null) {

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
                        actualEnd > actualStart
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

                    openTime = null
                }
            }
        }
    }

    /*
     * If the selected app is currently open,
     * close the session at NOW.
     */
    if (openTime != null) {

        val actualStart =
            maxOf(
                openTime!!,
                dayStart
            )

        val actualEnd =
            queryEnd

        if (actualEnd > actualStart) {

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

    val dayStart = getDayStart(date)
    val dayEnd = minOf(
        getDayEnd(date),
        System.currentTimeMillis()
    )

    if (dayEnd <= dayStart) {
        return 0L
    }

    val usageManager =
        context.getSystemService(
            Context.USAGE_STATS_SERVICE
        ) as UsageStatsManager

    // Previous day events are needed only to know
    // whether an Activity was already active at midnight.
    val queryStart =
        dayStart - (24L * 60L * 60L * 1000L)

    val events = usageManager.queryEvents(
        queryStart,
        dayEnd
    )

    val event = UsageEvents.Event()

    // Track actual Activity names instead of simply counting
    // RESUMED events.
    val activeActivities = mutableSetOf<String>()

    var sessionStart: Long? = null
    var totalUsage = 0L

    while (events.hasNextEvent()) {

        events.getNextEvent(event)

        if (event.packageName != packageName) {
            continue
        }

        val activityName =
            event.className ?: "__unknown_activity__"

        when (event.eventType) {

            foregroundEvent() -> {

                val wasEmpty = activeActivities.isEmpty()

                activeActivities.add(activityName)

                // App became active.
                if (
                    wasEmpty &&
                    event.timeStamp >= dayStart
                ) {
                    sessionStart = event.timeStamp
                }
            }

            backgroundEvent() -> {

                activeActivities.remove(activityName)

                // App completely left foreground.
                if (
                    activeActivities.isEmpty() &&
                    sessionStart != null
                ) {

                    val start = maxOf(
                        sessionStart!!,
                        dayStart
                    )

                    val end = minOf(
                        event.timeStamp,
                        dayEnd
                    )

                    if (end > start) {
                        totalUsage += end - start
                    }

                    sessionStart = null
                }
            }
        }

        // If the app was already active before midnight,
        // start counting from midnight.
        if (
            event.timeStamp < dayStart &&
            activeActivities.isNotEmpty()
        ) {
            sessionStart = dayStart
        }
    }

    // App is still active now.
    if (
        activeActivities.isNotEmpty() &&
        sessionStart != null
    ) {

        val start = maxOf(
            sessionStart!!,
            dayStart
        )

        val end = dayEnd

        if (end > start) {
            totalUsage += end - start
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
        (totalSeconds % 3600) / 60

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
        androidx.compose.ui.platform.LocalContext.current

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
        mutableStateOf("")
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
                        hasOverlayPermission(context)
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
     * Ask for the user's name only after Usage Access
     * permission has been granted, and only if no name
     * has been saved before.
     */
    LaunchedEffect(
        permissionGranted,
        overlayPermissionGranted
    ) {

        if (
            permissionGranted &&
            overlayPermissionGranted
        ) {

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

            if (savedName.isNullOrBlank()) {
                showNameDialog = true
            }
        }
    }

    if (!permissionGranted) {

        PermissionScreen(
            onGrantPermission = {

                context.startActivity(
                    Intent(
                        Settings
                            .ACTION_USAGE_ACCESS_SETTINGS
                    )
                )
            }
        )

        return
    }

    if (!overlayPermissionGranted) {

        OverlayPermissionScreen(
            onGrantPermission = {

                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse(
                        "package:${context.packageName}"
                    )
                )

                context.startActivity(intent)
            }
        )

        return
    }

    /*
     * Start the floating timer monitor only after both
     * required permissions are available.
     */
    LaunchedEffect(permissionGranted, overlayPermissionGranted) {
        if (permissionGranted && overlayPermissionGranted) {
            val serviceIntent = Intent(
                context,
                UsageMonitorService::class.java
            )

            ContextCompat.startForegroundService(
                context,
                serviceIntent
            )
        }
    }

    if (showNameDialog) {

        AlertDialog(
            onDismissRequest = {
                // Name must be entered before continuing.
            },

            title = {
                Text("Enter your name:")
            },

            text = {
                Column {

                    OutlinedTextField(
                        modifier =
                            Modifier.fillMaxWidth(),

                        value = userName,

                        onValueChange = {
                            userName = it
                        },

                        singleLine = true,

                        placeholder = {
                            Text("Your name")
                        }
                    )

                    Spacer(
                        modifier =
                            Modifier.height(8.dp)
                    )

                    Text(
                        text =
                            "This name is not editable. Enter carefully.",

                        style =
                            MaterialTheme.typography
                                .bodySmall
                    )
                }
            },

            confirmButton = {

                TextButton(
                    enabled =
                        userName.trim().isNotEmpty(),

                    onClick = {

                        context
                            .getSharedPreferences(
                                "appsense_preferences",
                                Context.MODE_PRIVATE
                            )
                            .edit()
                            .putString(
                                "user_name",
                                userName.trim()
                            )
                            .apply()

                        showNameDialog = false
                    }
                ) {
                    Text("Continue")
                }
            }
        )
    }

    androidx.activity.compose.BackHandler(
        enabled = currentScreen != "dashboard"
    ) {
        when (currentScreen) {

            "detail" -> {
                currentScreen = "stats"
            }

            "stats" -> {
                currentScreen = "dashboard"
            }

            "add_app" -> {
                currentScreen = "dashboard"
            }
        }
    }
    when (currentScreen) {

        "dashboard" -> {

            DashboardScreen(
                onAddApp = {
                    currentScreen = "add_app"
                },

                onStats = {
                    currentScreen = "stats"
                }
            )
        }

        "add_app" -> {

            AddAppScreen(
                context = context,

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
                context = context,

                onBack = {
                    currentScreen =
                        "dashboard"
                },

                onAppClick = { packageName ->

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
                    context = context,
                    packageName = it,

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
   PERMISSION SCREEN
   ========================================================= */

@Composable
fun PermissionScreen(
    onGrantPermission: () -> Unit
) {

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(24.dp),

        horizontalAlignment =
            Alignment.CenterHorizontally,

        verticalArrangement =
            Arrangement.Center
    ) {

        Text(
            text = "AppSense",
            style =
                MaterialTheme.typography
                    .headlineLarge
        )

        Spacer(
            modifier =
                Modifier.height(20.dp)
        )

        Text(
            text =
                "Allow Usage Access to let AppSense track the apps you choose."
        )

        Spacer(
            modifier =
                Modifier.height(24.dp)
        )

        Button(
            onClick =
                onGrantPermission
        ) {
            Text(
                "Allow Usage Access"
            )
        }
    }
}

/* =========================================================
   OVERLAY PERMISSION SCREEN
   ========================================================= */

@Composable
fun OverlayPermissionScreen(
    onGrantPermission: () -> Unit
) {

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(24.dp),

        horizontalAlignment =
            Alignment.CenterHorizontally,

        verticalArrangement =
            Arrangement.Center
    ) {

        Text(
            text = "Display over other apps",
            style =
                MaterialTheme.typography
                    .headlineLarge
        )

        Spacer(
            modifier =
                Modifier.height(20.dp)
        )

        Text(
            text =
                "Allow AppSense to display the floating timer over the apps you choose."
        )

        Spacer(
            modifier =
                Modifier.height(24.dp)
        )

        Button(
            onClick =
                onGrantPermission
        ) {
            Text(
                "Allow Display Access"
            )
        }
    }
}

/* =========================================================
   DASHBOARD
   ========================================================= */

@Composable
fun DashboardScreen(
    onAddApp: () -> Unit,
    onStats: () -> Unit
) {

    val context =
        androidx.compose.ui.platform.LocalContext.current

    val savedName =
        context.getSharedPreferences(
            "appsense_preferences",
            Context.MODE_PRIVATE
        ).getString("user_name", "")?.trim().orEmpty()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color(0xFFEAF2FF),
                        Color(0xFFF3EEFF),
                        Color(0xFFF8F9FC)
                    )
                )
            )
    ) {

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),

            horizontalAlignment =
                Alignment.CenterHorizontally
        ) {

            Spacer(
                modifier =
                    Modifier.height(50.dp)
            )

            Text(
                text =
                    if (savedName.isNotEmpty()) {
                        "Hello, $savedName! 👋"
                    } else {
                        "Hello! 👋"
                    },
                style =
                    MaterialTheme.typography
                        .headlineLarge
            )

            Spacer(
                modifier =
                    Modifier.height(8.dp)
            )

            Text(
                text =
                    "Your app usage, all in one place."
            )

            Spacer(
                modifier =
                    Modifier.height(50.dp)
            )

            Button(
                modifier =
                    Modifier.fillMaxWidth(),

                onClick =
                    onAddApp
            ) {

                Text(
                    "＋  Add App"
                )
            }

            Spacer(
                modifier =
                    Modifier.height(20.dp)
            )

            Button(
                modifier =
                    Modifier.fillMaxWidth(),

                onClick =
                    onStats
            ) {

                Text(
                    "📊  Added App Stats"
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
        mutableStateOf<List<AppItem>>(emptyList())
    }

    var refreshKey by remember {
        mutableIntStateOf(0)
    }

    /*
     * Detect newly installed / uninstalled apps.
     * Cache is cleared only when the package list changes.
     */
    DisposableEffect(context) {

        val packageReceiver =
            object : BroadcastReceiver() {

                override fun onReceive(
                    context: Context?,
                    intent: Intent?
                ) {

                    when (intent?.action) {

                        Intent.ACTION_PACKAGE_ADDED,
                        Intent.ACTION_PACKAGE_REMOVED,
                        Intent.ACTION_PACKAGE_CHANGED -> {

                            installedAppsCache = null
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

                addDataScheme("package")
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

    /*
     * Load apps in background.
     * If cache exists, this returns immediately.
     */
    LaunchedEffect(refreshKey) {

        val apps =
            withContext(Dispatchers.IO) {
                getInstalledApps(context)
            }

        allApps = apps
    }

    var searchText by remember {
        mutableStateOf("")
    }

    var selectedApps by remember {
        mutableStateOf(
            loadSelectedApps(context)
        )
    }

    val filteredApps =
        remember(
            searchText,
            allApps
        ) {

            if (searchText.isBlank()) {

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
                onClick = onBack
            ) {
                Text("←")
            }

            Spacer(
                modifier =
                    Modifier.width(12.dp)
            )

            Text(
                text = "Add Apps",
                style =
                    MaterialTheme.typography
                        .headlineSmall
            )
        }

        OutlinedTextField(
            modifier =
                Modifier.fillMaxWidth(),

            value = searchText,

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
            modifier = Modifier
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
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {

                            selectedApps =
                                if (selected) {

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
                                    if (checked) {

                                        selectedApps +
                                                app.packageName

                                    } else {

                                        selectedApps -
                                                app.packageName
                                    }
                            }
                    )

                    Text(
                        text = app.name,
                        modifier =
                            Modifier.padding(
                                start = 6.dp
                            )
                    )
                }
            }
        }

        Button(
            modifier = Modifier
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

            Text("Save")
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
            loadSelectedApps(context)
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
                onClick = onBack
            ) {
                Text("←")
            }

            Spacer(
                modifier =
                    Modifier.width(12.dp)
            )

            Text(
                text = "App Stats",
                style =
                    MaterialTheme.typography
                        .headlineSmall
            )
        }

        Text(
            text = "Today",
            style =
                MaterialTheme.typography
                    .titleLarge
        )

        Spacer(
            modifier =
                Modifier.height(12.dp)
        )

        if (selectedApps.isEmpty()) {

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
                            e: Exception
                        ) {
                            null
                        }

                    if (info != null) {

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

                        AppUsageCard(
                            name = name,
                            usageMillis = usage,

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
    onClick: () -> Unit
) {

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                vertical = 6.dp
            )
            .background(
                Color.White,
                RoundedCornerShape(20.dp)
            )
            .clickable {
                onClick()
            }
            .padding(18.dp),

        verticalAlignment =
            Alignment.CenterVertically
    ) {

        Box(
            modifier = Modifier
                .width(52.dp)
                .height(52.dp)
                .background(
                    Color(0xFFE8EEFF),
                    RoundedCornerShape(16.dp)
                ),

            contentAlignment =
                Alignment.Center
        ) {

            Text(
                text =
                    name.firstOrNull()
                        ?.uppercase()
                        ?: "A",

                style =
                    MaterialTheme.typography
                        .headlineSmall
            )
        }

        Spacer(
            modifier =
                Modifier.width(16.dp)
        )

        Column(
            modifier =
                Modifier.weight(1f)
        ) {

            Text(
                text = name,
                style =
                    MaterialTheme.typography
                        .titleLarge
            )

            Text(
                text = "Today"
            )
        }

        Text(
            text =
                formatUsageTime(
                    usageMillis
                ),

            style =
                MaterialTheme.typography
                    .titleLarge
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

                val info =
                    context.packageManager
                        .getApplicationInfo(
                            packageName,
                            0
                        )

                context.packageManager
                    .getApplicationLabel(info)
                    .toString()

            } catch (
                e: Exception
            ) {
                "App"
            }
        }

    var selectedDate by remember {
        mutableStateOf(
            Calendar.getInstance()
        )
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
            modifier =
                Modifier.fillMaxWidth(),

            verticalAlignment =
                Alignment.CenterVertically
        ) {

            Button(
                onClick = onBack
            ) {
                Text("←")
            }

            Spacer(
                modifier =
                    Modifier.width(12.dp)
            )

            Text(
                text = appName,
                style =
                    MaterialTheme.typography
                        .headlineSmall
            )
        }

        Spacer(
            modifier =
                Modifier.height(16.dp)
        )

        /* MINI CALENDAR BUTTON */

        OutlinedButton(
            modifier =
                Modifier.fillMaxWidth(),

            onClick = {
                showCalendar = true
            }
        ) {

            Text(
                text =
                    "📅  " +
                            SimpleDateFormat(
                                "EEE, d MMM yyyy",
                                Locale.getDefault()
                            ).format(
                                selectedDate.time
                            )
            )
        }

        Spacer(
            modifier =
                Modifier.height(12.dp)
        )

        /* DAILY / HOURLY */

        Box {

            Button(
                onClick = {
                    menuExpanded = true
                }
            ) {

                Text(
                    "$mode  ▼"
                )
            }

            DropdownMenu(
                expanded =
                    menuExpanded,

                onDismissRequest = {
                    menuExpanded = false
                }
            ) {

                DropdownMenuItem(
                    text = {
                        Text("Daily")
                    },

                    onClick = {

                        mode = "Daily"
                        menuExpanded = false
                    }
                )

                DropdownMenuItem(
                    text = {
                        Text("Hourly")
                    },

                    onClick = {

                        mode = "Hourly"
                        menuExpanded = false
                    }
                )
            }
        }

        Spacer(
            modifier =
                Modifier.height(20.dp)
        )

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

                selectedDate =
                    date

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

    val total =
        remember(
            packageName,
            date.timeInMillis
        ) {

            getAppUsageForDate(
                context,
                packageName,
                date
            )
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
            text = "Total Usage",
            style =
                MaterialTheme.typography
                    .titleMedium
        )

        Spacer(
            modifier =
                Modifier.height(8.dp)
        )

        Text(
            text =
                formatUsageTime(total),

            style =
                MaterialTheme.typography
                    .headlineMedium
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

    val sessions =
        remember(
            packageName,
            date.timeInMillis
        ) {

            getHourlySessions(
                context,
                packageName,
                date
            )
        }

    if (sessions.isEmpty()) {

        Text(
            "No usage sessions on this date."
        )

        return
    }

    LazyColumn(
        modifier =
            Modifier.fillMaxSize()
    ) {

        items(
            sessions
        ) { session ->

            UsageSessionCard(
                session
            )
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
            .padding(
                vertical = 5.dp
            )
            .background(
                Color.White,
                RoundedCornerShape(18.dp)
            )
            .padding(18.dp)
    ) {

        Row(
            modifier =
                Modifier.fillMaxWidth(),

            horizontalArrangement =
                Arrangement.SpaceBetween
        ) {

            Column {

                Text(
                    text = "OPEN",
                    style =
                        MaterialTheme.typography
                            .labelMedium
                )

                Spacer(
                    modifier =
                        Modifier.height(3.dp)
                )

                Text(
                    text =
                        formatClockTime(
                            session.startTime
                        ),

                    style =
                        MaterialTheme.typography
                            .titleMedium
                )
            }

            Column(
                horizontalAlignment =
                    Alignment.End
            ) {

                Text(
                    text = "CLOSE",
                    style =
                        MaterialTheme.typography
                            .labelMedium
                )

                Spacer(
                    modifier =
                        Modifier.height(3.dp)
                )

                Text(
                    text =
                        formatClockTime(
                            session.endTime
                        ),

                    style =
                        MaterialTheme.typography
                            .titleMedium
                )
            }
        }

        Spacer(
            modifier =
                Modifier.height(12.dp)
        )

        Text(
            text =
                "Used ${formatUsageTime(session.durationMillis)}",

            style =
                MaterialTheme.typography
                    .titleMedium
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
    val today = remember {
        Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
    }

    var visibleMonth by remember {
        mutableStateOf(
            selectedDate.clone() as Calendar
        )
    }

    // Keep visible month on the 1st day
    LaunchedEffect(selectedDate) {
        visibleMonth = (selectedDate.clone() as Calendar).apply {
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
    }

    val currentMonthIndex =
        today.get(Calendar.YEAR) * 12 + today.get(Calendar.MONTH)

    val visibleMonthIndex =
        visibleMonth.get(Calendar.YEAR) * 12 +
                visibleMonth.get(Calendar.MONTH)

    val canGoForward =
        visibleMonthIndex < currentMonthIndex

    Dialog(
        onDismissRequest = onDismiss
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            shape = RoundedCornerShape(24.dp),
            color = Color.White,
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier.padding(18.dp)
            ) {

                // HEADER
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Select Date",
                        style = MaterialTheme.typography.titleLarge
                    )

                    TextButton(
                        onClick = onDismiss
                    ) {
                        Text("✕")
                    }
                }

                Spacer(
                    modifier = Modifier.height(8.dp)
                )

                // MONTH NAVIGATION
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {

                    // PREVIOUS MONTH
                    IconButton(
                        onClick = {
                            val previousMonth =
                                visibleMonth.clone() as Calendar

                            previousMonth.add(
                                Calendar.MONTH,
                                -1
                            )

                            previousMonth.set(
                                Calendar.DAY_OF_MONTH,
                                1
                            )

                            visibleMonth = previousMonth
                        }
                    ) {
                        Text(
                            text = "‹",
                            style = MaterialTheme.typography.headlineMedium
                        )
                    }

                    Text(
                        text = SimpleDateFormat(
                            "MMMM yyyy",
                            Locale.getDefault()
                        ).format(visibleMonth.time),
                        style = MaterialTheme.typography.titleMedium
                    )

                    // NEXT MONTH
                    IconButton(
                        enabled = canGoForward,
                        onClick = {
                            if (canGoForward) {
                                val nextMonth =
                                    visibleMonth.clone() as Calendar

                                nextMonth.add(
                                    Calendar.MONTH,
                                    1
                                )

                                nextMonth.set(
                                    Calendar.DAY_OF_MONTH,
                                    1
                                )

                                visibleMonth = nextMonth
                            }
                        }
                    ) {
                        Text(
                            text = "›",
                            style = MaterialTheme.typography.headlineMedium
                        )
                    }
                }

                Spacer(
                    modifier = Modifier.height(8.dp)
                )

                // WEEK DAYS
                Row(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    listOf(
                        "S", "M", "T", "W", "T", "F", "S"
                    ).forEach { dayName ->

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = dayName,
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                }

                // MONTH DETAILS
                val firstDayOfMonth =
                    visibleMonth.clone() as Calendar

                firstDayOfMonth.set(
                    Calendar.DAY_OF_MONTH,
                    1
                )

                val daysInMonth =
                    firstDayOfMonth.getActualMaximum(
                        Calendar.DAY_OF_MONTH
                    )

                val leadingEmptyDays =
                    firstDayOfMonth.get(
                        Calendar.DAY_OF_WEEK
                    ) - 1

                val totalCells =
                    leadingEmptyDays + daysInMonth

                val numberOfRows =
                    (totalCells + 6) / 7

                // DATE GRID
                Column(
                    modifier = Modifier.fillMaxWidth()
                ) {

                    repeat(numberOfRows) { row ->

                        Row(
                            modifier = Modifier.fillMaxWidth()
                        ) {

                            repeat(7) { column ->

                                val cellIndex =
                                    row * 7 + column

                                val day =
                                    cellIndex -
                                            leadingEmptyDays +
                                            1

                                if (
                                    day < 1 ||
                                    day > daysInMonth
                                ) {

                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(42.dp)
                                    )

                                } else {

                                    val cellDate =
                                        Calendar.getInstance().apply {

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
                                        cellDate.after(today)

                                    val isSelected =
                                        cellDate.get(Calendar.YEAR) ==
                                                selectedDate.get(Calendar.YEAR) &&
                                                cellDate.get(Calendar.MONTH) ==
                                                selectedDate.get(Calendar.MONTH) &&
                                                cellDate.get(Calendar.DAY_OF_MONTH) ==
                                                selectedDate.get(Calendar.DAY_OF_MONTH)

                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(42.dp)
                                            .padding(2.dp)
                                            .background(
                                                if (isSelected) {
                                                    Color(0xFFE1EAFF)
                                                } else {
                                                    Color.Transparent
                                                },
                                                RoundedCornerShape(12.dp)
                                            )
                                            .clickable(
                                                enabled = !isFuture
                                            ) {

                                                val chosenDate =
                                                    cellDate.clone()
                                                            as Calendar

                                                onDateSelected(
                                                    chosenDate
                                                )

                                                onDismiss()
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {

                                        Text(
                                            text = day.toString(),
                                            color =
                                                if (isFuture) {
                                                    Color.LightGray
                                                } else {
                                                    Color.Unspecified
                                                },
                                            style = MaterialTheme
                                                .typography
                                                .bodyMedium
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(
                    modifier = Modifier.height(10.dp)
                )

                // TODAY
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        val todayCopy =
                            today.clone() as Calendar

                        onDateSelected(todayCopy)
                        onDismiss()
                    }
                ) {
                    Text("Today")
                }
            }
        }
    }
}


