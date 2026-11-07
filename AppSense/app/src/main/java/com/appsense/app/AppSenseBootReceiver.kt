package com.appsense.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log

class AppSenseBootReceiver : BroadcastReceiver() {

    override fun onReceive(
        context: Context,
        intent: Intent
    ) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) {
            return
        }

        // Overlay permission is required for AppSense monitoring.
        if (!Settings.canDrawOverlays(context)) {
            Log.i("AppSenseBootReceiver", "Overlay permission not granted. Skipping startup.")
            return
        }

        // Start monitoring only when at least one app is selected.
        val trackedApps = context.getSharedPreferences(
            "appsense_preferences",
            Context.MODE_PRIVATE
        ).getStringSet("tracked_apps", emptySet())

        if (trackedApps.isNullOrEmpty()) {
            Log.i("AppSenseBootReceiver", "No tracked apps selected. Skipping startup.")
            return
        }

        val serviceIntent = Intent(
            context,
            UsageMonitorService::class.java
        )

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (e: Exception) {
            Log.e(
                "AppSenseBootReceiver",
                "Failed to start UsageMonitorService after boot.",
                e
            )
        }
    }
}
