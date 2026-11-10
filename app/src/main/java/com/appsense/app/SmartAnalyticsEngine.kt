package com.appsense.app

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Pure Kotlin analytics engine for AppSense.
 * - Uses only available data within the last 10 calendar days.
 * - Never fabricates or zero-pads missing days.
 * - Splits cross-hour and cross-midnight sessions accurately.
 */
object SmartAnalyticsEngine {

    const val MAX_WINDOW_DAYS = 10
    private const val CONSISTENT_DAY_RATIO_THRESHOLD = 0.6f
    private const val HIGH_USAGE_SHARE_THRESHOLD = 20.0f
    private const val HIGH_USAGE_DAILY_AVG_MS = 20 * 60 * 1000L

    private data class HourSlice(
        val packageName: String,
        val appName: String,
        val dateKey: String,
        val dayOfWeek: Int,
        val hourOfDay: Int,
        val durationMs: Long,
        val scrollCount: Int
    )

    fun analyze(
        sessions: List<UsageSessionRecord>,
        selectedPackages: Set<String> = emptySet(),
        referenceTimeMs: Long = System.currentTimeMillis(),
        timeZone: TimeZone = TimeZone.getDefault()
    ): SmartAnalyticsReport {
        val windowStartMs = computeTenDayWindowStartMs(referenceTimeMs, timeZone)

        val filteredSessions = sessions.mapNotNull { session ->
            if (selectedPackages.isNotEmpty() && session.packageName !in selectedPackages) {
                return@mapNotNull null
            }
            val clippedStart = maxOf(session.startTimeMs, windowStartMs)
            val clippedEnd = minOf(session.endTimeMs, referenceTimeMs)
            if (clippedEnd <= clippedStart) return@mapNotNull null

            val originalDuration = (session.endTimeMs - session.startTimeMs).coerceAtLeast(1L)
            val clippedDuration = clippedEnd - clippedStart
            val scaledScrolls = ((session.scrollCount.toDouble() * clippedDuration) / originalDuration)
                .roundToInt()
                .coerceAtLeast(0)

            session.copy(
                startTimeMs = clippedStart,
                endTimeMs = clippedEnd,
                scrollCount = scaledScrolls
            )
        }

        val slices = filteredSessions.flatMap { sliceSessionByHour(it, timeZone) }
        val totalUsageDurationMs = slices.sumOf { it.durationMs }
        val totalScrolls = slices.sumOf { it.scrollCount }

        if (totalUsageDurationMs <= 0L || slices.isEmpty()) {
            return createEmptyReport()
        }

        val slicesByDate = slices.groupBy { it.dateKey }
            .filterValues { daySlices -> daySlices.sumOf { it.durationMs } > 0L }
        val availableDaysCount = slicesByDate.size

        val mostUsedApps = buildMostUsedApps(
            slices = slices,
            totalUsageDurationMs = totalUsageDurationMs,
            availableDaysCount = availableDaysCount
        )

        val peakUsage = buildPeakUsageAnalysis(
            slices = slices,
            totalUsageDurationMs = totalUsageDurationMs
        )

        val usagePatterns = buildUsagePatternAnalysis(
            slicesByDate = slicesByDate,
            mostUsedApps = mostUsedApps,
            timeZone = timeZone
        )

        val smartInsights = buildSmartInsights(
            availableDaysCount = availableDaysCount,
            mostUsedApps = mostUsedApps,
            peakUsage = peakUsage,
            usagePatterns = usagePatterns
        )

        return SmartAnalyticsReport(
            availableDaysCount = availableDaysCount,
            maxWindowDays = MAX_WINDOW_DAYS,
            totalUsageDurationMs = totalUsageDurationMs,
            totalScrolls = totalScrolls,
            hasData = true,
            mostUsedApps = mostUsedApps,
            peakUsage = peakUsage,
            usagePatterns = usagePatterns,
            smartInsights = smartInsights
        )
    }

    fun computeTenDayWindowStartMs(referenceTimeMs: Long, timeZone: TimeZone): Long {
        val cal = Calendar.getInstance(timeZone).apply {
            timeInMillis = referenceTimeMs
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_YEAR, -(MAX_WINDOW_DAYS - 1))
        }
        return cal.timeInMillis
    }

    private fun sliceSessionByHour(
        session: UsageSessionRecord,
        timeZone: TimeZone
    ): List<HourSlice> {
        val result = mutableListOf<HourSlice>()
        val totalDuration = (session.endTimeMs - session.startTimeMs).coerceAtLeast(1L)
        var currentMs = session.startTimeMs
        var allocatedScrolls = 0

        val cal = Calendar.getInstance(timeZone)
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            this.timeZone = timeZone
        }

        while (currentMs < session.endTimeMs) {
            cal.timeInMillis = currentMs
            val hourOfDay = cal.get(Calendar.HOUR_OF_DAY)
            val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK)
            val dateKey = dateFormat.format(Date(currentMs))

            // Next exact hour boundary in local timezone
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            cal.add(Calendar.HOUR_OF_DAY, 1)
            val nextHourMs = cal.timeInMillis

            val sliceEndMs = min(session.endTimeMs, nextHourMs)
            val sliceDuration = sliceEndMs - currentMs

            val sliceScrolls = if (sliceEndMs == session.endTimeMs) {
                (session.scrollCount - allocatedScrolls).coerceAtLeast(0)
            } else {
                ((session.scrollCount.toDouble() * sliceDuration) / totalDuration)
                    .roundToInt()
                    .coerceAtLeast(0)
            }
            allocatedScrolls += sliceScrolls

            if (sliceDuration > 0L) {
                result.add(
                    HourSlice(
                        packageName = session.packageName,
                        appName = session.appName.ifBlank { session.packageName },
                        dateKey = dateKey,
                        dayOfWeek = dayOfWeek,
                        hourOfDay = hourOfDay,
                        durationMs = sliceDuration,
                        scrollCount = sliceScrolls
                    )
                )
            }
            currentMs = sliceEndMs
        }

        return result
    }

    private fun buildMostUsedApps(
        slices: List<HourSlice>,
        totalUsageDurationMs: Long,
        availableDaysCount: Int
    ): List<AppUsageRankItem> {
        val groupedByPkg = slices.groupBy { it.packageName }

        val unranked = groupedByPkg.map { (pkg, appSlices) ->
            val appDuration = appSlices.sumOf { it.durationMs }
            val appScrolls = appSlices.sumOf { it.scrollCount }
            val activeDays = appSlices.map { it.dateKey }.distinct().size
            val appName = appSlices.firstOrNull { it.appName.isNotBlank() }?.appName ?: pkg
            val percentage = ((appDuration.toDouble() / totalUsageDurationMs.toDouble()) * 100.0).toFloat()

            val dayPresenceRatio = if (availableDaysCount > 0) {
                activeDays.toFloat() / availableDaysCount.toFloat()
            } else 0f
            val avgDailyForApp = if (activeDays > 0) appDuration / activeDays else 0L

            val isConsistentHigh = availableDaysCount >= 1 &&
                    dayPresenceRatio >= CONSISTENT_DAY_RATIO_THRESHOLD &&
                    (percentage >= HIGH_USAGE_SHARE_THRESHOLD || avgDailyForApp >= HIGH_USAGE_DAILY_AVG_MS)

            Triple(
                pkg to appName,
                appDuration to percentage,
                Triple(activeDays, appScrolls, isConsistentHigh)
            )
        }.sortedWith(
            compareByDescending<Triple<Pair<String, String>, Pair<Long, Float>, Triple<Int, Int, Boolean>>> {
                it.second.first
            }.thenBy { it.first.second }
        )

        return unranked.mapIndexed { index, item ->
            AppUsageRankItem(
                rank = index + 1,
                packageName = item.first.first,
                appName = item.first.second,
                totalDurationMs = item.second.first,
                usagePercentage = item.second.second,
                activeDaysCount = item.third.first,
                totalScrolls = item.third.second,
                isConsistentHighUsage = item.third.third
            )
        }
    }

    private fun buildPeakUsageAnalysis(
        slices: List<HourSlice>,
        totalUsageDurationMs: Long
    ): PeakUsageAnalysis {
        val hourlyDuration = LongArray(24)
        val hourlyScrolls = IntArray(24)

        for (slice in slices) {
            hourlyDuration[slice.hourOfDay] += slice.durationMs
            hourlyScrolls[slice.hourOfDay] += slice.scrollCount
        }

        val hourlyPoints = (0..23).map { hour ->
            val dur = hourlyDuration[hour]
            val pct = ((dur.toDouble() / totalUsageDurationMs.toDouble()) * 100.0).toFloat()
            HourlyUsagePoint(
                hourOfDay = hour,
                hourLabel = formatHourAmPm(hour),
                windowLabel = "${formatHourAmPm(hour)} – ${formatHourAmPm((hour + 1) % 24)}",
                totalDurationMs = dur,
                totalScrolls = hourlyScrolls[hour],
                percentageOfTotal = pct
            )
        }

        // Fix: Never start a peak window on an hour with 0 usage!
        // Also tie-break by choosing the window whose starting hour has higher usage.
        var bestStartHour = (0..23).maxByOrNull { hourlyDuration[it] } ?: 0
        var bestWindowDuration = -1L

        for (h in 0..23) {
            if (hourlyDuration[h] <= 0L) continue
            val nextHour = (h + 1) % 24
            val twoHourDuration = hourlyDuration[h] + hourlyDuration[nextHour]

            val isBetterWindow = twoHourDuration > bestWindowDuration ||
                    (twoHourDuration == bestWindowDuration && hourlyDuration[h] > hourlyDuration[bestStartHour])

            if (isBetterWindow) {
                bestWindowDuration = twoHourDuration
                bestStartHour = h
            }
        }

        val nextHourIndex = (bestStartHour + 1) % 24
        // If the second hour in the 2-hour window has actual usage, show 2-hour window (e.g. 9 PM – 11 PM).
        // If the second hour has 0 usage, show the exact 1-hour window (e.g. 9 PM – 10 PM) or 2-hour label.
        val peakEndHour = if (hourlyDuration[nextHourIndex] > 0L) {
            (bestStartHour + 2) % 24
        } else {
            (bestStartHour + 1) % 24
        }
        val peakWindowLabel = "${formatHourAmPm(bestStartHour)} – ${formatHourAmPm(peakEndHour)}"

        val bucketBreakdown = TimeOfDayBucket.values().map { bucket ->
            val bucketSlices = slices.filter { getTimeBucketForHour(it.hourOfDay) == bucket }
            val dur = bucketSlices.sumOf { it.durationMs }
            val scrolls = bucketSlices.sumOf { it.scrollCount }
            val pct = ((dur.toDouble() / totalUsageDurationMs.toDouble()) * 100.0).toFloat()
            TimeBucketSummary(
                bucket = bucket,
                totalDurationMs = dur,
                percentageOfTotal = pct,
                totalScrolls = scrolls
            )
        }

        val dominantBucket = bucketBreakdown
            .filter { it.totalDurationMs > 0L }
            .maxByOrNull { it.totalDurationMs }

        val maxScrollHour = (0..23).maxByOrNull { hourlyScrolls[it] }
        val maxScrollCount = if (maxScrollHour != null) hourlyScrolls[maxScrollHour] else 0
        val peakScrollHourFinal = if (maxScrollCount > 0) maxScrollHour else null
        val peakScrollWindowLabel = peakScrollHourFinal?.let {
            "${formatHourAmPm(it)} – ${formatHourAmPm((it + 1) % 24)}"
        }

        return PeakUsageAnalysis(
            peakTimeWindowLabel = peakWindowLabel,
            peakStartHour = bestStartHour,
            peakEndHour = peakEndHour,
            peakWindowDurationMs = bestWindowDuration.coerceAtLeast(0L),
            dominantBucket = dominantBucket,
            bucketBreakdown = bucketBreakdown,
            hourlyDistribution = hourlyPoints,
            peakScrollingHour = peakScrollHourFinal,
            peakScrollingWindowLabel = peakScrollWindowLabel,
            peakScrollingCount = maxScrollCount
        )
    }

    private fun buildUsagePatternAnalysis(
        slicesByDate: Map<String, List<HourSlice>>,
        mostUsedApps: List<AppUsageRankItem>,
        timeZone: TimeZone
    ): UsagePatternAnalysis {
        val dateParser = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            this.timeZone = timeZone
        }
        val fullDayFormatter = SimpleDateFormat("EEEE", Locale.US).apply {
            this.timeZone = timeZone
        }
        val shortDayFormatter = SimpleDateFormat("EEE", Locale.US).apply {
            this.timeZone = timeZone
        }

        val dailyTrends = slicesByDate.entries
            .sortedBy { it.key }
            .map { (dateKey, daySlices) ->
                val parsedDate = dateParser.parse(dateKey) ?: Date()
                val cal = Calendar.getInstance(timeZone).apply { time = parsedDate }
                val dow = cal.get(Calendar.DAY_OF_WEEK)
                val isWeekend = (dow == Calendar.SATURDAY || dow == Calendar.SUNDAY)

                DayUsageSummary(
                    dateKey = dateKey,
                    dayOfWeekName = fullDayFormatter.format(parsedDate),
                    shortDayLabel = shortDayFormatter.format(parsedDate),
                    isWeekend = isWeekend,
                    totalDurationMs = daySlices.sumOf { it.durationMs },
                    totalScrolls = daySlices.sumOf { it.scrollCount }
                )
            }

        val highestUsageDay = dailyTrends.maxByOrNull { it.totalDurationMs }

        val weekdayDays = dailyTrends.filter { !it.isWeekend }
        val weekendDays = dailyTrends.filter { it.isWeekend }

        val weekdayAvg = if (weekdayDays.isNotEmpty()) {
            weekdayDays.sumOf { it.totalDurationMs } / weekdayDays.size
        } else 0L

        val weekendAvg = if (weekendDays.isNotEmpty()) {
            weekendDays.sumOf { it.totalDurationMs } / weekendDays.size
        } else 0L

        val (trendDirection, trendChangePct) = computeTrend(dailyTrends)
        val consistentApps = mostUsedApps.filter { it.isConsistentHighUsage }

        return UsagePatternAnalysis(
            availableDaysCount = dailyTrends.size,
            maxWindowDays = MAX_WINDOW_DAYS,
            dailyTrends = dailyTrends,
            highestUsageDay = highestUsageDay,
            weekdayAvgDurationMs = weekdayAvg,
            weekendAvgDurationMs = weekendAvg,
            activeWeekdayCount = weekdayDays.size,
            activeWeekendCount = weekendDays.size,
            hasWeekdayData = weekdayDays.isNotEmpty(),
            hasWeekendData = weekendDays.isNotEmpty(),
            trendDirection = trendDirection,
            trendChangePercentage = trendChangePct,
            consistentHighUsageApps = consistentApps
        )
    }

    private fun computeTrend(dailyTrends: List<DayUsageSummary>): Pair<TrendDirection, Float> {
        if (dailyTrends.size < 2) {
            return TrendDirection.INSUFFICIENT_DATA to 0f
        }
        val splitIndex = dailyTrends.size / 2
        val earlierHalf = dailyTrends.subList(0, splitIndex)
        val recentHalf = dailyTrends.subList(splitIndex, dailyTrends.size)

        val earlierAvg = earlierHalf.map { it.totalDurationMs }.average()
        val recentAvg = recentHalf.map { it.totalDurationMs }.average()

        if (earlierAvg <= 0.0) {
            return TrendDirection.INSUFFICIENT_DATA to 0f
        }

        val changePct = (((recentAvg - earlierAvg) / earlierAvg) * 100.0).toFloat()
        val direction = when {
            changePct >= 12f -> TrendDirection.INCREASING
            changePct <= -12f -> TrendDirection.DECREASING
            else -> TrendDirection.STABLE
        }
        return direction to changePct
    }

    private fun buildSmartInsights(
        availableDaysCount: Int,
        mostUsedApps: List<AppUsageRankItem>,
        peakUsage: PeakUsageAnalysis,
        usagePatterns: UsagePatternAnalysis
    ): List<SmartInsight> {
        val insights = mutableListOf<SmartInsight>()

        mostUsedApps.firstOrNull()?.let { topApp ->
            val pctInt = topApp.usagePercentage.roundToInt()
            insights.add(
                SmartInsight(
                    category = InsightCategory.MOST_USED_APP,
                    title = "${topApp.appName}: Most used app",
                    detail = "Accounts for $pctInt% of your tracked usage (${formatDuration(topApp.totalDurationMs)}) across $availableDaysCount available day(s).",
                    highlightValue = "$pctInt%"
                )
            )
        }

        if (peakUsage.peakWindowDurationMs > 0L) {
            insights.add(
                SmartInsight(
                    category = InsightCategory.PEAK_TIME,
                    title = "Peak usage: ${peakUsage.peakTimeWindowLabel}",
                    detail = "Highest phone activity happens between ${peakUsage.peakTimeWindowLabel} (${formatDuration(peakUsage.peakWindowDurationMs)} total).",
                    highlightValue = peakUsage.peakTimeWindowLabel
                )
            )
        }

        usagePatterns.highestUsageDay?.let { topDay ->
            insights.add(
                SmartInsight(
                    category = InsightCategory.HIGHEST_DAY,
                    title = "${topDay.dayOfWeekName}: Highest usage day",
                    detail = "Recorded ${formatDuration(topDay.totalDurationMs)} on ${topDay.dateKey}.",
                    highlightValue = formatDuration(topDay.totalDurationMs)
                )
            )
        }

        peakUsage.dominantBucket?.let { dom ->
            val pctInt = dom.percentageOfTotal.roundToInt()
            insights.add(
                SmartInsight(
                    category = InsightCategory.TIME_BUCKET_SHARE,
                    title = "${dom.bucket.displayName} usage: $pctInt% of total usage",
                    detail = "You spend ${formatDuration(dom.totalDurationMs)} during ${dom.bucket.displayName.lowercase(Locale.US)} hours (${dom.bucket.timeRangeLabel}).",
                    highlightValue = "$pctInt%"
                )
            )
        }

        if (usagePatterns.hasWeekdayData && usagePatterns.hasWeekendData) {
            val wknd = usagePatterns.weekendAvgDurationMs
            val wkdy = usagePatterns.weekdayAvgDurationMs
            if (wknd > 0L && wkdy > 0L) {
                val diffPct = (((wknd - wkdy).toDouble() / wkdy.toDouble()) * 100.0).roundToInt()
                val comparisonTitle = when {
                    diffPct >= 10 -> "Weekend usage is $diffPct% higher than weekdays"
                    diffPct <= -10 -> "Weekday usage is ${abs(diffPct)}% higher than weekends"
                    else -> "Balanced weekday vs weekend usage"
                }
                insights.add(
                    SmartInsight(
                        category = InsightCategory.WEEKEND_VS_WEEKDAY,
                        title = comparisonTitle,
                        detail = "Weekday avg: ${formatDuration(wkdy)}/day • Weekend avg: ${formatDuration(wknd)}/day.",
                        highlightValue = if (diffPct >= 0) "+$diffPct%" else "$diffPct%"
                    )
                )
            }
        }

        if (peakUsage.peakScrollingWindowLabel != null && peakUsage.peakScrollingCount > 0) {
            insights.add(
                SmartInsight(
                    category = InsightCategory.SCROLL_INTENSITY,
                    title = "Peak scrolling: ${peakUsage.peakScrollingWindowLabel}",
                    detail = "Highest scrolling intensity detected around ${peakUsage.peakScrollingWindowLabel} (${peakUsage.peakScrollingCount} scrolls).",
                    highlightValue = "${peakUsage.peakScrollingCount} scrolls"
                )
            )
        }

        usagePatterns.consistentHighUsageApps.firstOrNull()?.let { consistentApp ->
            insights.add(
                SmartInsight(
                    category = InsightCategory.CONSISTENCY,
                    title = "Consistent high usage: ${consistentApp.appName}",
                    detail = "Used on ${consistentApp.activeDaysCount} of $availableDaysCount available days.",
                    highlightValue = "${consistentApp.activeDaysCount}/$availableDaysCount days"
                )
            )
        }

        return insights
    }

    fun getTimeBucketForHour(hourOfDay: Int): TimeOfDayBucket {
        return when (hourOfDay) {
            in 5..11 -> TimeOfDayBucket.MORNING
            in 12..16 -> TimeOfDayBucket.AFTERNOON
            in 17..20 -> TimeOfDayBucket.EVENING
            else -> TimeOfDayBucket.NIGHT
        }
    }

    fun formatHourAmPm(hour24: Int): String {
        val normalized = ((hour24 % 24) + 24) % 24
        return when {
            normalized == 0 -> "12 AM"
            normalized < 12 -> "$normalized AM"
            normalized == 12 -> "12 PM"
            else -> "${normalized - 12} PM"
        }
    }

    /**
     * Matches MainActivity's formatUsageTime so seconds are shown when < 1h,
     * while keeping clean "Xh Ym" / "Xm" formatting when seconds == 0.
     */
    fun formatDuration(durationMs: Long): String {
        if (durationMs <= 0L) return "0s"
        val totalSeconds = durationMs / 1000L
        val hours = (totalSeconds / 3600L).toInt()
        val minutes = ((totalSeconds % 3600L) / 60L).toInt()
        val seconds = (totalSeconds % 60L).toInt()

        return when {
            hours > 0 && minutes > 0 -> "${hours}h ${minutes}m"
            hours > 0 -> "${hours}h"
            minutes > 0 && seconds > 0 -> "${minutes}m ${seconds}s"
            minutes > 0 -> "${minutes}m"
            else -> "${seconds}s"
        }
    }

    private fun createEmptyReport(): SmartAnalyticsReport {
        val emptyBuckets = TimeOfDayBucket.values().map {
            TimeBucketSummary(it, 0L, 0f, 0)
        }
        val emptyHours = (0..23).map { hour ->
            HourlyUsagePoint(
                hourOfDay = hour,
                hourLabel = formatHourAmPm(hour),
                windowLabel = "${formatHourAmPm(hour)} – ${formatHourAmPm((hour + 1) % 24)}",
                totalDurationMs = 0L,
                totalScrolls = 0,
                percentageOfTotal = 0f
            )
        }
        return SmartAnalyticsReport(
            availableDaysCount = 0,
            maxWindowDays = MAX_WINDOW_DAYS,
            totalUsageDurationMs = 0L,
            totalScrolls = 0,
            hasData = false,
            mostUsedApps = emptyList(),
            peakUsage = PeakUsageAnalysis(
                peakTimeWindowLabel = "N/A",
                peakStartHour = 0,
                peakEndHour = 1,
                peakWindowDurationMs = 0L,
                dominantBucket = null,
                bucketBreakdown = emptyBuckets,
                hourlyDistribution = emptyHours,
                peakScrollingHour = null,
                peakScrollingWindowLabel = null,
                peakScrollingCount = 0
            ),
            usagePatterns = UsagePatternAnalysis(
                availableDaysCount = 0,
                maxWindowDays = MAX_WINDOW_DAYS,
                dailyTrends = emptyList(),
                highestUsageDay = null,
                weekdayAvgDurationMs = 0L,
                weekendAvgDurationMs = 0L,
                activeWeekdayCount = 0,
                activeWeekendCount = 0,
                hasWeekdayData = false,
                hasWeekendData = false,
                trendDirection = TrendDirection.INSUFFICIENT_DATA,
                trendChangePercentage = 0f,
                consistentHighUsageApps = emptyList()
            ),
            smartInsights = emptyList()
        )
    }
}