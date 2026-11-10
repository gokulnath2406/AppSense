package com.appsense.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class SmartAnalyticsEngineTest {

    private val testTimeZone: TimeZone = TimeZone.getTimeZone("Asia/Kolkata")

    private fun buildTimestamp(
        year: Int,
        month1Based: Int,
        day: Int,
        hour24: Int,
        minute: Int = 0
    ): Long {
        return Calendar.getInstance(testTimeZone).apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month1Based - 1)
            set(Calendar.DAY_OF_MONTH, day)
            set(Calendar.HOUR_OF_DAY, hour24)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    @Test
    fun mostUsedApps_ranksHighestToLowestWithAccuratePercentagesAndTime() {
        val refTime = buildTimestamp(2026, 11, 10, 23, 30)
        val dayStart = buildTimestamp(2026, 11, 10, 18, 0)

        val sessions = listOf(
            // Instagram: 60 mins (50%)
            UsageSessionRecord(
                packageName = "com.instagram.android",
                appName = "Instagram",
                startTimeMs = dayStart,
                endTimeMs = dayStart + 60 * 60_000L
            ),
            // YouTube: 45 mins (37.5%)
            UsageSessionRecord(
                packageName = "com.google.android.youtube",
                appName = "YouTube",
                startTimeMs = dayStart + 60 * 60_000L,
                endTimeMs = dayStart + 105 * 60_000L
            ),
            // X / Twitter: 15 mins (12.5%)
            UsageSessionRecord(
                packageName = "com.twitter.android",
                appName = "X",
                startTimeMs = dayStart + 105 * 60_000L,
                endTimeMs = dayStart + 120 * 60_000L
            )
        )

        val report = SmartAnalyticsEngine.analyze(
            sessions = sessions,
            selectedPackages = setOf(
                "com.instagram.android",
                "com.google.android.youtube",
                "com.twitter.android"
            ),
            referenceTimeMs = refTime,
            timeZone = testTimeZone
        )

        assertTrue(report.hasData)
        assertEquals(3, report.mostUsedApps.size)

        val first = report.mostUsedApps[0]
        assertEquals(1, first.rank)
        assertEquals("Instagram", first.appName)
        assertEquals(60 * 60_000L, first.totalDurationMs)
        assertEquals(50.0f, first.usagePercentage, 0.01f)

        val second = report.mostUsedApps[1]
        assertEquals(2, second.rank)
        assertEquals("YouTube", second.appName)
        assertEquals(37.5f, second.usagePercentage, 0.01f)

        val third = report.mostUsedApps[2]
        assertEquals(3, third.rank)
        assertEquals("X", third.appName)
        assertEquals(12.5f, third.usagePercentage, 0.01f)
    }

    @Test
    fun onlyAvailableDaysCounted_noFakeDataGeneratedForMissingDays() {
        // Reference: Nov 10, 2026. Only provide data on 3 out of 10 days (Nov 8, Nov 9, Nov 10).
        val refTime = buildTimestamp(2026, 11, 10, 23, 0)

        val sessions = listOf(
            // Nov 8 (Sunday): 120 mins
            UsageSessionRecord(
                packageName = "com.instagram.android",
                appName = "Instagram",
                startTimeMs = buildTimestamp(2026, 11, 8, 19, 0),
                endTimeMs = buildTimestamp(2026, 11, 8, 21, 0)
            ),
            // Nov 9 (Monday): 60 mins
            UsageSessionRecord(
                packageName = "com.instagram.android",
                appName = "Instagram",
                startTimeMs = buildTimestamp(2026, 11, 9, 20, 0),
                endTimeMs = buildTimestamp(2026, 11, 9, 21, 0)
            ),
            // Nov 10 (Tuesday): 60 mins
            UsageSessionRecord(
                packageName = "com.instagram.android",
                appName = "Instagram",
                startTimeMs = buildTimestamp(2026, 11, 10, 20, 0),
                endTimeMs = buildTimestamp(2026, 11, 10, 21, 0)
            ),
            // Session from 15 days ago (Oct 25) -> MUST be ignored outside 10-day window
            UsageSessionRecord(
                packageName = "com.instagram.android",
                appName = "Instagram",
                startTimeMs = buildTimestamp(2026, 10, 25, 10, 0),
                endTimeMs = buildTimestamp(2026, 10, 25, 12, 0)
            )
        )

        val report = SmartAnalyticsEngine.analyze(
            sessions = sessions,
            selectedPackages = setOf("com.instagram.android"),
            referenceTimeMs = refTime,
            timeZone = testTimeZone
        )

        // Strictly 3 available days, NOT 10 padded days
        assertEquals(3, report.availableDaysCount)
        assertEquals(3, report.usagePatterns.dailyTrends.size)
        assertEquals(1, report.usagePatterns.activeWeekendCount) // Sunday Nov 8
        assertEquals(2, report.usagePatterns.activeWeekdayCount) // Mon Nov 9, Tue Nov 10
        assertEquals(60 * 60_000L, report.usagePatterns.weekdayAvgDurationMs)
        assertEquals(120 * 60_000L, report.usagePatterns.weekendAvgDurationMs)
    }

    @Test
    fun peakUsageTime_detects9PmTo11PmWindowAndEveningShareCorrectly() {
        val refTime = buildTimestamp(2026, 11, 8, 23, 50)

        // Total = 200 minutes:
        // Morning (10 AM - 10:30 AM): 30 mins (15%)
        // Evening (6 PM - 7:20 PM): 80 mins (40%)
        // Night (9 PM - 10:30 PM): 90 mins (45%) -> Peak 2h window = 9 PM – 11 PM!
        val sessions = listOf(
            UsageSessionRecord(
                packageName = "com.instagram.android",
                appName = "Instagram",
                startTimeMs = buildTimestamp(2026, 11, 8, 10, 0),
                endTimeMs = buildTimestamp(2026, 11, 8, 10, 30)
            ),
            UsageSessionRecord(
                packageName = "com.instagram.android",
                appName = "Instagram",
                startTimeMs = buildTimestamp(2026, 11, 8, 18, 0),
                endTimeMs = buildTimestamp(2026, 11, 8, 19, 20)
            ),
            UsageSessionRecord(
                packageName = "com.instagram.android",
                appName = "Instagram",
                startTimeMs = buildTimestamp(2026, 11, 8, 21, 0),
                endTimeMs = buildTimestamp(2026, 11, 8, 22, 30)
            )
        )

        val report = SmartAnalyticsEngine.analyze(
            sessions = sessions,
            selectedPackages = setOf("com.instagram.android"),
            referenceTimeMs = refTime,
            timeZone = testTimeZone
        )

        assertEquals("9 PM – 11 PM", report.peakUsage.peakTimeWindowLabel)
        assertEquals(21, report.peakUsage.peakStartHour)
        assertEquals(23, report.peakUsage.peakEndHour)

        val eveningSummary = report.peakUsage.bucketBreakdown.first {
            it.bucket == TimeOfDayBucket.EVENING
        }
        assertEquals(40.0f, eveningSummary.percentageOfTotal, 0.1f)
    }

    @Test
    fun smartInsights_generatesExpectedInsightsFromExampleScenario() {
        // Sunday Nov 8, 2026 -> Highest day
        // Instagram -> Most used app
        // Peak window -> 9 PM – 11 PM
        val refTime = buildTimestamp(2026, 11, 9, 23, 0)

        val sessions = listOf(
            // Sunday Nov 8: 9 PM - 11 PM (120 mins Instagram)
            UsageSessionRecord(
                packageName = "com.instagram.android",
                appName = "Instagram",
                startTimeMs = buildTimestamp(2026, 11, 8, 21, 0),
                endTimeMs = buildTimestamp(2026, 11, 8, 23, 0),
                scrollCount = 340
            ),
            // Monday Nov 9: 6 PM - 7 PM (60 mins YouTube)
            UsageSessionRecord(
                packageName = "com.google.android.youtube",
                appName = "YouTube",
                startTimeMs = buildTimestamp(2026, 11, 9, 18, 0),
                endTimeMs = buildTimestamp(2026, 11, 9, 19, 0),
                scrollCount = 50
            )
        )

        val report = SmartAnalyticsEngine.analyze(
            sessions = sessions,
            selectedPackages = emptySet(),
            referenceTimeMs = refTime,
            timeZone = testTimeZone
        )

        val titles = report.smartInsights.map { it.title }
        assertTrue(titles.any { it.contains("Instagram: Most used app") })
        assertTrue(titles.any { it.contains("Peak usage: 9 PM – 11 PM") })
        assertTrue(titles.any { it.contains("Sunday: Highest usage day") })
    }

    @Test
    fun sessionCrossingMidnight_splitsAccuratelyAcrossDaysAndHours() {
        val refTime = buildTimestamp(2026, 11, 10, 12, 0)

        // Session from Nov 9 11:30 PM to Nov 10 12:30 AM (60 mins total: 30m on Nov 9, 30m on Nov 10)
        val sessions = listOf(
            UsageSessionRecord(
                packageName = "com.instagram.android",
                appName = "Instagram",
                startTimeMs = buildTimestamp(2026, 11, 9, 23, 30),
                endTimeMs = buildTimestamp(2026, 11, 10, 0, 30),
                scrollCount = 100
            )
        )

        val report = SmartAnalyticsEngine.analyze(
            sessions = sessions,
            referenceTimeMs = refTime,
            timeZone = testTimeZone
        )

        assertEquals(2, report.availableDaysCount)
        assertEquals(30 * 60_000L, report.usagePatterns.dailyTrends[0].totalDurationMs)
        assertEquals(30 * 60_000L, report.usagePatterns.dailyTrends[1].totalDurationMs)
        assertEquals(100, report.totalScrolls)
    }

    @Test
    fun consistentHighUsageApps_andScrollPeakDetection_workAccurately() {
        val refTime = buildTimestamp(2026, 11, 10, 23, 0)

        // Instagram used on all 3 available days; Reddit used on only 1 day for 10 mins
        val sessions = listOf(
            UsageSessionRecord(
                packageName = "com.instagram.android",
                appName = "Instagram",
                startTimeMs = buildTimestamp(2026, 11, 8, 22, 0),
                endTimeMs = buildTimestamp(2026, 11, 8, 22, 45),
                scrollCount = 200
            ),
            UsageSessionRecord(
                packageName = "com.instagram.android",
                appName = "Instagram",
                startTimeMs = buildTimestamp(2026, 11, 9, 22, 0),
                endTimeMs = buildTimestamp(2026, 11, 9, 22, 45),
                scrollCount = 250
            ),
            UsageSessionRecord(
                packageName = "com.instagram.android",
                appName = "Instagram",
                startTimeMs = buildTimestamp(2026, 11, 10, 22, 0),
                endTimeMs = buildTimestamp(2026, 11, 10, 22, 45),
                scrollCount = 300
            ),
            UsageSessionRecord(
                packageName = "com.reddit.frontpage",
                appName = "Reddit",
                startTimeMs = buildTimestamp(2026, 11, 10, 14, 0),
                endTimeMs = buildTimestamp(2026, 11, 10, 14, 10),
                scrollCount = 20
            )
        )

        val report = SmartAnalyticsEngine.analyze(
            sessions = sessions,
            referenceTimeMs = refTime,
            timeZone = testTimeZone
        )

        val consistentApps = report.usagePatterns.consistentHighUsageApps
        assertEquals(1, consistentApps.size)
        assertEquals("Instagram", consistentApps.first().appName)

        assertNotNull(report.peakUsage.peakScrollingHour)
        assertEquals(22, report.peakUsage.peakScrollingHour)
        assertEquals("10 PM – 11 PM", report.peakUsage.peakScrollingWindowLabel)
    }

    @Test
    fun emptyData_returnsSafeEmptyReportWithoutNaNOrCrash() {
        val report = SmartAnalyticsEngine.analyze(
            sessions = emptyList(),
            selectedPackages = setOf("com.instagram.android"),
            referenceTimeMs = System.currentTimeMillis(),
            timeZone = testTimeZone
        )

        assertFalse(report.hasData)
        assertEquals(0, report.availableDaysCount)
        assertEquals(0L, report.totalUsageDurationMs)
        assertTrue(report.mostUsedApps.isEmpty())
        assertTrue(report.smartInsights.isEmpty())
    }

    @Test
    fun singleHourUsage_doesNotShiftPeakStartToPreviousEmptyHour() {
        val refTime = buildTimestamp(2026, 11, 10, 23, 0)

        // Usage ONLY between 9:10 PM and 9:40 PM (hour 21 = 30m, hour 20 = 0m, hour 22 = 0m)
        val sessions = listOf(
            UsageSessionRecord(
                packageName = "com.instagram.android",
                appName = "Instagram",
                startTimeMs = buildTimestamp(2026, 11, 10, 21, 10),
                endTimeMs = buildTimestamp(2026, 11, 10, 21, 40)
            )
        )

        val report = SmartAnalyticsEngine.analyze(
            sessions = sessions,
            referenceTimeMs = refTime,
            timeZone = testTimeZone
        )

        // Must start at 21 (9 PM), NOT 20 (8 PM)
        assertEquals(21, report.peakUsage.peakStartHour)
        assertEquals("9 PM – 10 PM", report.peakUsage.peakTimeWindowLabel)
    }
}