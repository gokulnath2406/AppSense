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

/** Android-independent representation of a usage event. */
data class RawEvent(
    val packageName: String,
    val className: String?,
    val type: RawEventType,
    val timestamp: Long
)

enum class RawEventType {
    RESUMED,
    PAUSED,
    STOPPED,
    SCREEN_OFF,
    KEYGUARD_SHOWN,
    DEVICE_SHUTDOWN,
    OTHER
}

object AppSenseUsageCalculator {

    // Reconstruct foreground state from before midnight.
    // A 48-hour lookback provides context for sessions crossing midnight.
    private const val LOOKBACK_MILLIS =
        48L * 60L * 60L * 1000L

    // Preserve the existing maximum interval duration.
    private const val MAX_INTERVAL_MILLIS =
        6L * 60L * 60L * 1000L

    /**
     * Android wrapper.
     *
     * Converts Android UsageEvents to RawEvent and delegates
     * interval calculation to the pure-function overload.
     *
     * Existing callers can continue using this function.
     */
    fun getIntervals(
        context: Context,
        packageName: String,
        dayStart: Long,
        dayEnd: Long,
        mergeGapMs: Long = 0L
    ): List<AppUsageInterval> {

        if (dayEnd <= dayStart) return emptyList()

        val queryStart =
            (dayStart - LOOKBACK_MILLIS).coerceAtLeast(0L)

        val manager =
            context.getSystemService(Context.USAGE_STATS_SERVICE)
                    as? UsageStatsManager
                ?: return emptyList()

        val usageEvents =
            manager.queryEvents(queryStart, dayEnd)
                ?: return emptyList()

        val rawEvents = mutableListOf<RawEvent>()
        val event = UsageEvents.Event()

        while (usageEvents.hasNextEvent()) {

            usageEvents.getNextEvent(event)

            val rawType = when (event.eventType) {

                UsageEvents.Event.MOVE_TO_FOREGROUND ->
                    RawEventType.RESUMED

                UsageEvents.Event.MOVE_TO_BACKGROUND ->
                    RawEventType.PAUSED

                else -> {
                    when {

                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                                event.eventType ==
                                UsageEvents.Event.ACTIVITY_RESUMED ->
                            RawEventType.RESUMED

                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                                event.eventType ==
                                UsageEvents.Event.ACTIVITY_PAUSED ->
                            RawEventType.PAUSED

                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                                event.eventType ==
                                UsageEvents.Event.ACTIVITY_STOPPED ->
                            RawEventType.STOPPED

                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                                event.eventType ==
                                UsageEvents.Event.SCREEN_NON_INTERACTIVE ->
                            RawEventType.SCREEN_OFF

                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                                event.eventType ==
                                UsageEvents.Event.KEYGUARD_SHOWN ->
                            RawEventType.KEYGUARD_SHOWN

                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                                event.eventType ==
                                UsageEvents.Event.DEVICE_SHUTDOWN ->
                            RawEventType.DEVICE_SHUTDOWN

                        else -> RawEventType.OTHER
                    }
                }
            }

            rawEvents.add(
                RawEvent(
                    packageName = event.packageName.orEmpty(),
                    className = event.className,
                    type = rawType,
                    timestamp = event.timeStamp
                )
            )
        }

        return getIntervals(
            events = rawEvents,
            packageName = packageName,
            dayStart = dayStart,
            dayEnd = dayEnd,
            mergeGapMs = mergeGapMs
        )
    }

    /**
     * Pure interval calculation.
     *
     * This overload has no Android Context or UsageStatsManager dependency,
     * allowing it to be tested with ordinary JUnit4 tests.
     */
    fun getIntervals(
        events: List<RawEvent>,
        packageName: String,
        dayStart: Long,
        dayEnd: Long,
        mergeGapMs: Long = 0L
    ): List<AppUsageInterval> {

        if (dayEnd <= dayStart) return emptyList()

        val activeActivities = mutableSetOf<String>()
        val intervals = mutableListOf<AppUsageInterval>()

        var packageStart: Long? = null

        /**
         * Close the current package session, clipping it to the requested
         * time range and preserving the existing maximum-duration cap.
         */
        fun closeSession(timestamp: Long) {

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

        // Process events chronologically within the requested day.
        val orderedEvents = events
            .asSequence()
            .filter {
                it.timestamp >= dayStart &&
                        it.timestamp <= dayEnd
            }
            .sortedBy { it.timestamp }
            .toList()

        for (rawEvent in orderedEvents) {

            // System events may belong to a different package.
            // Process them before filtering by target package.
            when (rawEvent.type) {

                RawEventType.SCREEN_OFF,
                RawEventType.KEYGUARD_SHOWN,
                RawEventType.DEVICE_SHUTDOWN -> {
                    closeSession(rawEvent.timestamp)
                    continue
                }

                else -> Unit
            }

            if (rawEvent.packageName != packageName) continue

            val activityKey =
                rawEvent.className
                    ?.takeIf { it.isNotBlank() }
                    ?: "<unknown-activity>"

            when (rawEvent.type) {

                RawEventType.RESUMED -> {

                    // Duplicate foreground events must not double-count.
                    if (
                        activeActivities.add(activityKey) &&
                        activeActivities.size == 1
                    ) {
                        packageStart = rawEvent.timestamp
                    }
                }

                RawEventType.PAUSED,
                RawEventType.STOPPED -> {

                    // Do not close the package session while another
                    // Activity from the same package remains active.
                    if (
                        activeActivities.remove(activityKey) &&
                        activeActivities.isEmpty()
                    ) {
                        closeSession(rawEvent.timestamp)
                    }
                }

                RawEventType.SCREEN_OFF,
                RawEventType.KEYGUARD_SHOWN,
                RawEventType.DEVICE_SHUTDOWN,
                RawEventType.OTHER -> Unit
            }
        }

        // Close any session still active at the query end.
        if (activeActivities.isNotEmpty()) {
            closeSession(dayEnd)
        }

        if (intervals.size < 2) return intervals

        // Preserve the existing interval sorting and merging behavior.
        val sorted = intervals.sortedBy { it.startTime }
        val merged = mutableListOf<AppUsageInterval>()

        var currentStart = sorted.first().startTime
        var currentEnd = sorted.first().endTime

        val allowedGap = mergeGapMs.coerceAtLeast(0L)

        for (next in sorted.drop(1)) {

            if (
                next.startTime <= currentEnd ||
                next.startTime - currentEnd <= allowedGap
            ) {

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