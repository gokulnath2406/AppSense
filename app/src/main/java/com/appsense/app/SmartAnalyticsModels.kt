package com.appsense.app

enum class TimeOfDayBucket(val displayName: String, val timeRangeLabel: String) {
    MORNING("Morning", "5 AM – 12 PM"),
    AFTERNOON("Afternoon", "12 PM – 5 PM"),
    EVENING("Evening", "5 PM – 9 PM"),
    NIGHT("Night", "9 PM – 5 AM")
}

enum class TrendDirection(val label: String) {
    INCREASING("Increasing"),
    DECREASING("Decreasing"),
    STABLE("Stable"),
    INSUFFICIENT_DATA("Insufficient Data")
}

enum class InsightCategory {
    MOST_USED_APP,
    PEAK_TIME,
    HIGHEST_DAY,
    TIME_BUCKET_SHARE,
    WEEKEND_VS_WEEKDAY,
    SCROLL_INTENSITY,
    CONSISTENCY,
    TREND
}

/**
 * Raw usage session record for a monitored app.
 */
data class UsageSessionRecord(
    val packageName: String,
    val appName: String,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val scrollCount: Int = 0
)

/**
 * 1. Most Used Apps item
 */
data class AppUsageRankItem(
    val rank: Int,
    val packageName: String,
    val appName: String,
    val totalDurationMs: Long,
    val usagePercentage: Float,
    val activeDaysCount: Int,
    val totalScrolls: Int,
    val isConsistentHighUsage: Boolean
)

/**
 * 2. Hour-wise usage point (0..23)
 */
data class HourlyUsagePoint(
    val hourOfDay: Int,
    val hourLabel: String,
    val windowLabel: String,
    val totalDurationMs: Long,
    val totalScrolls: Int,
    val percentageOfTotal: Float
)

/**
 * 2. Morning / Afternoon / Evening / Night summary
 */
data class TimeBucketSummary(
    val bucket: TimeOfDayBucket,
    val totalDurationMs: Long,
    val percentageOfTotal: Float,
    val totalScrolls: Int
)

/**
 * 2. Peak Usage Time analysis
 */
data class PeakUsageAnalysis(
    val peakTimeWindowLabel: String,
    val peakStartHour: Int,
    val peakEndHour: Int,
    val peakWindowDurationMs: Long,
    val dominantBucket: TimeBucketSummary?,
    val bucketBreakdown: List<TimeBucketSummary>,
    val hourlyDistribution: List<HourlyUsagePoint>,
    val peakScrollingHour: Int?,
    val peakScrollingWindowLabel: String?,
    val peakScrollingCount: Int
)

/**
 * 3. Single available day usage summary (only generated for days with actual data)
 */
data class DayUsageSummary(
    val dateKey: String,        // YYYY-MM-DD
    val dayOfWeekName: String,  // Monday..Sunday
    val shortDayLabel: String,  // Mon..Sun
    val isWeekend: Boolean,
    val totalDurationMs: Long,
    val totalScrolls: Int
)

/**
 * 3. Usage Pattern Analysis
 */
data class UsagePatternAnalysis(
    val availableDaysCount: Int,
    val maxWindowDays: Int = 10,
    val dailyTrends: List<DayUsageSummary>,
    val highestUsageDay: DayUsageSummary?,
    val weekdayAvgDurationMs: Long,
    val weekendAvgDurationMs: Long,
    val activeWeekdayCount: Int,
    val activeWeekendCount: Int,
    val hasWeekdayData: Boolean,
    val hasWeekendData: Boolean,
    val trendDirection: TrendDirection,
    val trendChangePercentage: Float,
    val consistentHighUsageApps: List<AppUsageRankItem>
)

/**
 * 4. Smart Insight card model
 */
data class SmartInsight(
    val category: InsightCategory,
    val title: String,
    val detail: String,
    val highlightValue: String
)

/**
 * Complete Smart Analytics Report
 */
data class SmartAnalyticsReport(
    val availableDaysCount: Int,
    val maxWindowDays: Int = 10,
    val totalUsageDurationMs: Long,
    val totalScrolls: Int,
    val hasData: Boolean,
    val mostUsedApps: List<AppUsageRankItem>,
    val peakUsage: PeakUsageAnalysis,
    val usagePatterns: UsagePatternAnalysis,
    val smartInsights: List<SmartInsight>
)