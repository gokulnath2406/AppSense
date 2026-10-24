
package com.appsense.app

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build

/** A single source of truth for AppSense foreground usage intervals. */
data class AppUsageInterval(
    val startTime: Long,
    val endTime: Long
)

object AppSenseUsageCalculator {

    // Reconstruct foreground state from before midnight.
    // A 48-hour lookback provides context for sessions crossing midnight.
    private const val LOOKBACK_MILLIS = 48L * 60L * 60L * 1000L

    // Prevent orphaned foreground events from inflating usage indefinitely.
    private const val MAX_INTERVAL_MILLIS = 6L * 60L * 60L * 1000L

    /**
     * Returns non-overlapping package-foreground intervals clipped to [dayStart, dayEnd].
     *
     * Activity events are tracked independently, so pausing one Activity does not close
     * the package session while another Activity from the same package remains resumed.
     *
     * System events are processed before filtering by package name because those events
     * may belong to another package.
     */
    fun getIntervals(
        context: Context,
        packageName: String,
        dayStart: Long,
        dayEnd: Long
    ): List<AppUsageInterval> {

        if (dayEnd <= dayStart) return emptyList()

        val queryStart = (dayStart - LOOKBACK_MILLIS).coerceAtLeast(0L)

        val manager =
            context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
                ?: return emptyList()

        val events = manager.queryEvents(queryStart, dayEnd) ?: return emptyList()

        val event = UsageEvents.Event()
        val activeActivities = mutableSetOf<String>()
        val intervals = mutableListOf<AppUsageInterval>()

        var packageStart: Long? = null

        fun isForeground(type: Int): Boolean =
            type == UsageEvents.Event.MOVE_TO_FOREGROUND ||
                    (
                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                                    type == UsageEvents.Event.ACTIVITY_RESUMED
                            )

        fun isBackground(type: Int): Boolean =
            type == UsageEvents.Event.MOVE_TO_BACKGROUND ||
                    (
                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                                    (
                                            type == UsageEvents.Event.ACTIVITY_PAUSED ||
                                                    type == UsageEvents.Event.ACTIVITY_STOPPED
                                            )
                            )

        fun isSystemStopEvent(type: Int): Boolean {

            val screenOrKeyguardStop =
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                        (
                                type == UsageEvents.Event.SCREEN_NON_INTERACTIVE ||
                                        type == UsageEvents.Event.KEYGUARD_SHOWN
                                )

            val shutdownStop =
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                        type == UsageEvents.Event.DEVICE_SHUTDOWN

            return screenOrKeyguardStop || shutdownStop
        }

        /**
         * Close the current package session.
         *
         * The interval is clipped to the requested day and capped to the configured
         * maximum duration.
         */
        fun closeActiveActivities(timestamp: Long) {

            val sessionStart = packageStart ?: return

            val start = maxOf(sessionStart, dayStart)

            val cappedEnd = minOf(
                timestamp,
                sessionStart + MAX_INTERVAL_MILLIS
            )

            val end = minOf(cappedEnd, dayEnd)

            if (end > start) {
                intervals.add(
                    AppUsageInterval(
                        startTime = start,
                        endTime = end
                    )
                )
            }

            activeActivities.clear()
            packageStart = null
        }

        while (events.hasNextEvent()) {

            events.getNextEvent(event)

            val timestamp = event.timeStamp.coerceIn(queryStart, dayEnd)

            // IMPORTANT:
            // Handle system events BEFORE checking the target package.
            // Screen-off, keyguard and shutdown events can carry another package name.
            if (isSystemStopEvent(event.eventType)) {
                closeActiveActivities(timestamp)
                continue
            }

            // Ignore activity events from other packages.
            if (event.packageName != packageName) continue

            val activityKey =
                event.className?.takeIf { it.isNotBlank() }
                    ?: "<unknown-activity>"

            when {

                isForeground(event.eventType) -> {

                    // Duplicate RESUMED/foreground events must not double-count.
                    if (
                        activeActivities.add(activityKey) &&
                        activeActivities.size == 1
                    ) {
                        packageStart = timestamp
                    }
                }

                isBackground(event.eventType) -> {

                    // Closing one Activity must not close the package session
                    // while another Activity from the same package remains active.
                    if (
                        activeActivities.remove(activityKey) &&
                        activeActivities.isEmpty()
                    ) {
                        closeActiveActivities(timestamp)
                    }
                }
            }
        }

        // If the package remains foreground at query end, close the interval
        // at dayEnd while enforcing the maximum interval duration.
        if (activeActivities.isNotEmpty()) {
            closeActiveActivities(dayEnd)
        }

        // Sort and merge overlapping or touching intervals.
        // This preserves non-overlapping results for totals and chart buckets.
        if (intervals.size < 2) return intervals

        val sorted = intervals.sortedBy { it.startTime }
        val merged = mutableListOf<AppUsageInterval>()

        var currentStart = sorted.first().startTime
        var currentEnd = sorted.first().endTime

        for (next in sorted.drop(1)) {

            if (next.startTime <= currentEnd) {

                currentEnd = maxOf(currentEnd, next.endTime)

            } else {

                merged.add(
                    AppUsageInterval(
                        startTime = currentStart,
                        endTime = currentEnd
                    )
                )

                currentStart = next.startTime
                currentEnd = next.endTime
            }
        }

        merged.add(
            AppUsageInterval(
                startTime = currentStart,
                endTime = currentEnd
            )
        )

        return merged
    }
}
