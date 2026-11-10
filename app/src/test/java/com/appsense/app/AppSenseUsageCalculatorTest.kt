package com.appsense.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSenseUsageCalculatorTest {

    private val dayStart = 1_000_000L
    private val packageName = "com.example.testapp"

    private fun event(
        time: Long,
        type: RawEventType,
        activity: String = "ActivityA",
        pkg: String = packageName
    ) = RawEvent(
        packageName = pkg,
        className = activity,
        type = type,
        timestamp = time
    )

    private fun calculate(
        events: List<RawEvent>,
        end: Long = dayStart + 60 * 60_000L,
        mergeGapMs: Long = 0L
    ) = AppSenseUsageCalculator.getIntervals(
        events = events,
        packageName = packageName,
        dayStart = dayStart,
        dayEnd = end,
        mergeGapMs = mergeGapMs
    )

    private fun totalUsage(
        intervals: List<AppUsageInterval>
    ): Long = intervals.sumOf {
        it.endTime - it.startTime
    }

    @Test
    fun t1_singleTenMinuteSession() {
        val intervals = calculate(
            listOf(
                event(dayStart, RawEventType.RESUMED),
                event(
                    dayStart + 10 * 60_000L,
                    RawEventType.PAUSED
                )
            )
        )

        assertEquals(600_000L, totalUsage(intervals))
    }

    @Test
    fun t2_activitySwitchWithFiftyMillisecondGap() {
        val intervals = calculate(
            listOf(
                event(dayStart, RawEventType.RESUMED, "ActivityA"),
                event(
                    dayStart + 100L,
                    RawEventType.PAUSED,
                    "ActivityA"
                ),
                event(
                    dayStart + 150L,
                    RawEventType.RESUMED,
                    "ActivityB"
                ),
                event(
                    dayStart + 200L,
                    RawEventType.PAUSED,
                    "ActivityB"
                )
            ),
            mergeGapMs = 50L
        )

        assertEquals(200L, totalUsage(intervals))
    }

    @Test
    fun t3_overlappingActivitiesAreNotDoubleCounted() {
        val intervals = calculate(
            listOf(
                event(dayStart, RawEventType.RESUMED, "ActivityA"),
                event(
                    dayStart + 100L,
                    RawEventType.RESUMED,
                    "ActivityB"
                ),
                event(
                    dayStart + 200L,
                    RawEventType.PAUSED,
                    "ActivityA"
                ),
                event(
                    dayStart + 300L,
                    RawEventType.PAUSED,
                    "ActivityB"
                )
            )
        )

        assertEquals(300L, totalUsage(intervals))
    }

    @Test
    fun t4_todaySessionIsClippedToToday() {
        val intervals = calculate(
            listOf(
                event(dayStart, RawEventType.RESUMED),
                event(
                    dayStart + 10 * 60_000L,
                    RawEventType.PAUSED
                )
            ),
            end = dayStart + 20 * 60_000L
        )

        assertEquals(600_000L, totalUsage(intervals))
        assertEquals(dayStart, intervals.first().startTime)
    }

    @Test
    fun t5_yesterdayEventsDoNotCountTowardToday() {
        val intervals = calculate(
            listOf(
                event(dayStart - 20_000L, RawEventType.RESUMED),
                event(dayStart - 10_000L, RawEventType.PAUSED)
            )
        )

        assertTrue(intervals.isEmpty())
    }

    @Test
    fun t6_foregroundAppCountsUntilQueryEnd() {
        val intervals = calculate(
            listOf(
                event(dayStart, RawEventType.RESUMED)
            ),
            end = dayStart + 5_000L
        )

        assertEquals(5_000L, totalUsage(intervals))
    }

    @Test
    fun t7_duplicateResumedDoesNotDoubleCount() {
        val intervals = calculate(
            listOf(
                event(dayStart, RawEventType.RESUMED),
                event(dayStart + 100L, RawEventType.RESUMED),
                event(dayStart + 1_000L, RawEventType.PAUSED)
            )
        )

        assertEquals(1_000L, totalUsage(intervals))
    }

    @Test
    fun t8_pausedThenStoppedDoesNotDoubleCount() {
        val intervals = calculate(
            listOf(
                event(dayStart, RawEventType.RESUMED),
                event(dayStart + 500L, RawEventType.PAUSED),
                event(dayStart + 600L, RawEventType.STOPPED)
            )
        )

        assertEquals(500L, totalUsage(intervals))
    }

    @Test
    fun t9_otherPackageEventsAreIgnored() {
        val intervals = calculate(
            listOf(
                event(
                    dayStart,
                    RawEventType.RESUMED,
                    pkg = "com.example.other"
                ),
                event(
                    dayStart + 1_000L,
                    RawEventType.PAUSED,
                    pkg = "com.example.other"
                )
            )
        )

        assertTrue(intervals.isEmpty())
    }

    @Test
    fun t10_twoSeparateSessionsAreCounted() {
        val intervals = calculate(
            listOf(
                event(dayStart, RawEventType.RESUMED),
                event(dayStart + 100L, RawEventType.PAUSED),
                event(dayStart + 200L, RawEventType.RESUMED),
                event(dayStart + 500L, RawEventType.PAUSED)
            )
        )

        assertEquals(400L, totalUsage(intervals))
        assertEquals(2, intervals.size)
    }

    @Test
    fun t11_eventAtDayEndDoesNotCreatePositiveDuration() {
        val end = dayStart + 10_000L

        val intervals = calculate(
            listOf(
                event(end, RawEventType.RESUMED)
            ),
            end = end
        )

        assertEquals(0L, totalUsage(intervals))
    }

    @Test
    fun r1_orphanedYesterdayForegroundDoesNotInflateToday() {
        val intervals = calculate(
            listOf(
                event(dayStart - 60_000L, RawEventType.RESUMED)
            ),
            end = dayStart + 30_000L
        )

        assertTrue(intervals.isEmpty())
    }

    @Test
    fun r2_screenOffClosesActiveSession() {
        val intervals = calculate(
            listOf(
                event(dayStart, RawEventType.RESUMED),
                event(dayStart + 2_000L, RawEventType.SCREEN_OFF)
            ),
            end = dayStart + 10_000L
        )

        assertEquals(2_000L, totalUsage(intervals))
    }

    @Test
    fun r3_mergeGapIsAppliedOnlyWhenRequested() {
        val events = listOf(
            event(dayStart, RawEventType.RESUMED),
            event(dayStart + 100L, RawEventType.PAUSED),
            event(dayStart + 150L, RawEventType.RESUMED),
            event(dayStart + 200L, RawEventType.PAUSED)
        )

        val separate = calculate(events)
        val merged = calculate(events, mergeGapMs = 50L)

        assertEquals(2, separate.size)
        assertEquals(1, merged.size)
        assertEquals(200L, totalUsage(merged))
    }
}