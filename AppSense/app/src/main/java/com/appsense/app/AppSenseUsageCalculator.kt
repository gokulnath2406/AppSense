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
    // Reconstruct foreground state from before midnight. A 48-hour lookback gives
    // UsageEvents enough context for sessions that cross midnight, even when event
    // delivery/query boundaries are imperfect. Returned intervals are still clipped
    // to the requested day, so previous-day usage is never added to today's total.
    private const val LOOKBACK_MILLIS = 48L * 60L * 60L * 1000L

    /**
     * Returns non-overlapping package-foreground intervals clipped to [dayStart, dayEnd].
     * Activity events are tracked independently, so pausing one Activity does not close the
     * package session while another Activity from the same package remains resumed.
     */
    fun getIntervals(
        context: Context,
        packageName: String,
        dayStart: Long,
        dayEnd: Long
    ): List<AppUsageInterval> {
        if (dayEnd <= dayStart) return emptyList()

        val queryStart = (dayStart - LOOKBACK_MILLIS).coerceAtLeast(0L)
        val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return emptyList()
        val events = manager.queryEvents(queryStart, dayEnd) ?: return emptyList()
        val event = UsageEvents.Event()
        val activeActivities = mutableSetOf<String>()
        val intervals = mutableListOf<AppUsageInterval>()
        var packageStart: Long? = null

        fun isForeground(type: Int): Boolean =
            type == UsageEvents.Event.MOVE_TO_FOREGROUND ||
                    (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                            type == UsageEvents.Event.ACTIVITY_RESUMED)

        fun isBackground(type: Int): Boolean =
            type == UsageEvents.Event.MOVE_TO_BACKGROUND ||
                    (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                            (type == UsageEvents.Event.ACTIVITY_PAUSED ||
                                    type == UsageEvents.Event.ACTIVITY_STOPPED))

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.packageName != packageName) continue

            val timestamp = event.timeStamp.coerceIn(queryStart, dayEnd)
            val activityKey = event.className?.takeIf { it.isNotBlank() } ?: "<unknown-activity>"

            when {
                isForeground(event.eventType) -> {
                    // Duplicate RESUMED/foreground events must not double-count.
                    if (activeActivities.add(activityKey) && activeActivities.size == 1) {
                        packageStart = timestamp
                    }
                }

                isBackground(event.eventType) -> {
                    if (activeActivities.remove(activityKey) && activeActivities.isEmpty()) {
                        val start = maxOf(packageStart ?: timestamp, dayStart)
                        val end = minOf(timestamp, dayEnd)
                        if (end > start) intervals.add(AppUsageInterval(start, end))
                        packageStart = null
                    }
                }
            }
        }

        // If the package remains foreground at query end, count up to the same end boundary.
        if (activeActivities.isNotEmpty()) {
            val start = maxOf(packageStart ?: dayStart, dayStart)
            val end = dayEnd
            if (end > start) intervals.add(AppUsageInterval(start, end))
        }

        // Sort and merge overlaps/touching intervals to guarantee additive totals and chart buckets.
        if (intervals.size < 2) return intervals
        val sorted = intervals.sortedBy { it.startTime }
        val merged = mutableListOf<AppUsageInterval>()
        var currentStart = sorted.first().startTime
        var currentEnd = sorted.first().endTime
        for (next in sorted.drop(1)) {
            if (next.startTime <= currentEnd) {
                currentEnd = maxOf(currentEnd, next.endTime)
            } else {
                merged.add(AppUsageInterval(currentStart, currentEnd))
                currentStart = next.startTime
                currentEnd = next.endTime
            }
        }
        merged.add(AppUsageInterval(currentStart, currentEnd))
        return merged
    }
}
