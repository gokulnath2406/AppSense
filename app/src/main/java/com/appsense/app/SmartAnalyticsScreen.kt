package com.appsense.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private val DashboardBg = Color(0xFFF7F8FA)
private val HeroCardBg = Color(0xFF16181D)
private val CardWhite = Color.White
private val SoftGrayBg = Color(0xFFF0F1F3)
private val BarTrackGray = Color(0xFFE9EBEF)
private val SoftBlueBadge = Color(0xFFE8EEFF)
private val PrimaryBlue = Color(0xFF1769E0)
private val PeakAccentOrange = Color(0xFFE67E22)
private val ConsistentTeal = Color(0xFF16A085)
private val TextDark = Color(0xFF16181D)
private val TextMuted = Color(0xFF747985)
private val LabelUpperGray = Color(0xFF858993)

@Composable
fun SmartAnalyticsScreen(
    selectedPackages: Set<String>,
    onBackClick: () -> Unit
) {
    val context = LocalContext.current
    val repository = remember { SmartAnalyticsRepository(context) }
    val scope = rememberCoroutineScope()

    var report by remember { mutableStateOf<SmartAnalyticsReport?>(null) }
    var isLoading by remember { mutableStateOf(true) }

    fun refreshData() {
        scope.launch {
            isLoading = true
            val loaded = withContext(Dispatchers.IO) {
                repository.loadSmartAnalyticsReport(selectedPackages)
            }
            report = loaded
            isLoading = false
        }
    }

    LaunchedEffect(selectedPackages) {
        refreshData()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .background(DashboardBg)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp, vertical = 20.dp)
        ) {
            // HEADER
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(onClick = onBackClick) {
                    Text("←")
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Usage intelligence",
                        style = MaterialTheme.typography.headlineSmall,
                        color = TextDark
                    )

                    val subtitleText = report?.let {
                        "Based on ${it.availableDaysCount} of ${it.maxWindowDays} available days"
                    } ?: "Analyzing last 10 days…"

                    Text(
                        text = subtitleText,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted
                    )
                }

                Surface(
                    modifier = Modifier
                        .height(42.dp)
                        .width(42.dp),
                    shape = RoundedCornerShape(14.dp),
                    color = CardWhite,
                    tonalElevation = 2.dp
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        IconButton(onClick = { refreshData() }) {
                            Text(
                                text = "↻",
                                style = MaterialTheme.typography.titleMedium,
                                color = Color(0xFF4F535C)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(22.dp))

            if (isLoading) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(22.dp),
                    color = CardWhite,
                    tonalElevation = 1.dp
                ) {
                    Column(
                        modifier = Modifier.padding(28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "Analyzing your usage patterns…",
                            style = MaterialTheme.typography.titleMedium,
                            color = TextDark
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Reading available data from the last 10 days.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted
                        )
                    }
                }
            } else {
                val currentReport = report
                if (currentReport == null || !currentReport.hasData) {
                    EmptyAnalyticsCard(selectedPackagesCount = selectedPackages.size)
                } else {
                    OverviewHeroCard(currentReport)

                    Spacer(modifier = Modifier.height(16.dp))

                    if (currentReport.smartInsights.isNotEmpty()) {
                        SmartInsightsCard(currentReport.smartInsights)
                        Spacer(modifier = Modifier.height(16.dp))
                    }

                    MostUsedAppsCard(currentReport.mostUsedApps)

                    Spacer(modifier = Modifier.height(16.dp))

                    PeakUsageTimeCard(
                        peakUsage = currentReport.peakUsage,
                        availableDaysCount = currentReport.availableDaysCount
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    UsagePatternCard(currentReport.usagePatterns)

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = BarTrackGray
                        ) {
                            Spacer(
                                modifier = Modifier
                                    .width(4.dp)
                                    .height(34.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Text(
                            text = "Insights are generated strictly from real available data — no fake stats for missing days.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF737782)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OverviewHeroCard(report: SmartAnalyticsReport) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        color = HeroCardBg,
        tonalElevation = 0.dp
    ) {
        Column(modifier = Modifier.padding(24.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "TOTAL TRACKED USAGE",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color(0xFFB9BDC6)
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = SmartAnalyticsEngine.formatDuration(report.totalUsageDurationMs),
                        style = MaterialTheme.typography.displaySmall,
                        color = Color.White
                    )
                }

                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = Color.White.copy(alpha = 0.10f)
                ) {
                    Text(
                        text = "${report.availableDaysCount}/${report.maxWindowDays} DAYS",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
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
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Peak activity window",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFFB9BDC6)
                    )
                    Text(
                        text = report.peakUsage.peakTimeWindowLabel,
                        style = MaterialTheme.typography.titleSmall,
                        color = Color.White
                    )
                }
            }
        }
    }
}

@Composable
private fun SmartInsightsCard(insights: List<SmartInsight>) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = CardWhite,
        tonalElevation = 1.dp
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(
                text = "SMART INSIGHTS",
                style = MaterialTheme.typography.labelSmall,
                color = LabelUpperGray
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "Key takeaways",
                style = MaterialTheme.typography.titleMedium,
                color = TextDark
            )

            Spacer(modifier = Modifier.height(14.dp))

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                insights.forEach { insight ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        color = DashboardBg
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = insight.title,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = TextDark
                                )
                                Spacer(modifier = Modifier.height(3.dp))
                                Text(
                                    text = insight.detail,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextMuted
                                )
                            }

                            Spacer(modifier = Modifier.width(10.dp))

                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = SoftBlueBadge
                            ) {
                                Text(
                                    text = insight.highlightValue,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = PrimaryBlue
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MostUsedAppsCard(apps: List<AppUsageRankItem>) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = CardWhite,
        tonalElevation = 1.dp
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(
                text = "MOST USED APPS",
                style = MaterialTheme.typography.labelSmall,
                color = LabelUpperGray
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "Ranked highest to lowest",
                style = MaterialTheme.typography.titleMedium,
                color = TextDark
            )

            Spacer(modifier = Modifier.height(16.dp))

            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                apps.forEach { app ->
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(44.dp)
                                    .height(44.dp)
                                    .background(
                                        if (app.rank == 1) SoftBlueBadge else SoftGrayBg,
                                        RoundedCornerShape(14.dp)
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "#${app.rank}",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = if (app.rank == 1) PrimaryBlue else TextDark
                                )
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = app.appName,
                                        style = MaterialTheme.typography.titleMedium,
                                        color = TextDark,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (app.isConsistentHighUsage) {
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = ConsistentTeal.copy(alpha = 0.12f)
                                        ) {
                                            Text(
                                                text = "Consistent",
                                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = ConsistentTeal
                                            )
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(2.dp))

                                Text(
                                    text = "Active on ${app.activeDaysCount} day${if (app.activeDaysCount == 1) "" else "s"}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextMuted
                                )
                            }

                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = SmartAnalyticsEngine.formatDuration(app.totalDurationMs),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = TextDark
                                )
                                Text(
                                    text = "${app.usagePercentage.roundToInt()}%",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = PrimaryBlue
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        DashboardProgressBar(
                            progress = (app.usagePercentage / 100f).coerceIn(0f, 1f),
                            fillColor = if (app.rank == 1) PrimaryBlue else Color(0xFF2E86C1)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PeakUsageTimeCard(
    peakUsage: PeakUsageAnalysis,
    availableDaysCount: Int
) {
    val safeDays = availableDaysCount.coerceAtLeast(1)
    var showDailyAvg by remember(availableDaysCount) {
        mutableStateOf(availableDaysCount > 1)
    }

    val hourScrollState = rememberScrollState()
    val density = LocalDensity.current

    // Auto-scroll the 24-hour chart so the Peak Hour is centered/visible on open
    LaunchedEffect(peakUsage.peakStartHour) {
        val targetHour = (peakUsage.peakStartHour - 2).coerceAtLeast(0)
        val itemStepPx = with(density) { 58.dp.roundToPx() } // 50.dp width + 8.dp spacing
        hourScrollState.animateScrollTo(targetHour * itemStepPx)
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = CardWhite,
        tonalElevation = 1.dp
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(
                text = "PEAK USAGE TIME",
                style = MaterialTheme.typography.labelSmall,
                color = LabelUpperGray
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "Time of day & 24-hour pattern",
                style = MaterialTheme.typography.titleMedium,
                color = TextDark
            )

            Spacer(modifier = Modifier.height(14.dp))

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = SoftBlueBadge
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "PEAK ACTIVITY WINDOW",
                            style = MaterialTheme.typography.labelSmall,
                            color = PrimaryBlue
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = peakUsage.peakTimeWindowLabel,
                            style = MaterialTheme.typography.titleMedium,
                            color = TextDark
                        )
                    }
                    Text(
                        text = SmartAnalyticsEngine.formatDuration(peakUsage.peakWindowDurationMs),
                        style = MaterialTheme.typography.titleMedium,
                        color = PrimaryBlue
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            val buckets = peakUsage.bucketBreakdown
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (rowIdx in 0..1) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        for (colIdx in 0..1) {
                            val item = buckets[rowIdx * 2 + colIdx]
                            val isDominant = peakUsage.dominantBucket?.bucket == item.bucket
                            Surface(
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(16.dp),
                                color = if (isDominant) SoftBlueBadge else DashboardBg
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(
                                        text = item.bucket.displayName,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = if (isDominant) PrimaryBlue else TextDark
                                    )
                                    Text(
                                        text = item.bucket.timeRangeLabel,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TextMuted
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = "${item.percentageOfTotal.roundToInt()}% · ${SmartAnalyticsEngine.formatDuration(item.totalDurationMs)}",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = TextDark
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Clean Header Row that never wraps or collides
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "HOURLY USAGE",
                        style = MaterialTheme.typography.labelSmall,
                        color = LabelUpperGray
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = if (showDailyAvg && availableDaysCount > 1) {
                            "Daily average · 24 hours"
                        } else {
                            "Total ($availableDaysCount day${if (availableDaysCount == 1) "" else "s"}) · 24 hours"
                        },
                        style = MaterialTheme.typography.titleSmall,
                        color = TextDark
                    )
                }

                if (availableDaysCount > 1) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Surface(
                            modifier = Modifier.clickable { showDailyAvg = true },
                            shape = RoundedCornerShape(10.dp),
                            color = if (showDailyAvg) SoftBlueBadge else SoftGrayBg
                        ) {
                            Text(
                                text = "Daily Avg",
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (showDailyAvg) PrimaryBlue else TextMuted
                            )
                        }
                        Surface(
                            modifier = Modifier.clickable { showDailyAvg = false },
                            shape = RoundedCornerShape(10.dp),
                            color = if (!showDailyAvg) SoftBlueBadge else SoftGrayBg
                        ) {
                            Text(
                                text = "Total",
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (!showDailyAvg) PrimaryBlue else TextMuted
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            val displayedDurations = peakUsage.hourlyDistribution.map { point ->
                if (showDailyAvg && safeDays > 1) {
                    point.totalDurationMs / safeDays
                } else {
                    point.totalDurationMs
                }
            }

            val maxHourDuration = displayedDurations
                .maxOrNull()
                ?.coerceAtLeast(1L) ?: 1L

            val secondWindowHour = (peakUsage.peakStartHour + 1) % 24
            val isTwoHourWindow = peakUsage.peakEndHour == (peakUsage.peakStartHour + 2) % 24

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(hourScrollState),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                peakUsage.hourlyDistribution.forEachIndexed { idx, point ->
                    val displayMillis = displayedDurations[idx]
                    val isPeakHour = point.totalDurationMs > 0L && (
                            point.hourOfDay == peakUsage.peakStartHour ||
                                    (isTwoHourWindow && point.hourOfDay == secondWindowHour)
                            )

                    Column(
                        modifier = Modifier.width(50.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // Fixed height box so all bars align on the exact same baseline
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(18.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            if (displayMillis > 0L) {
                                Text(
                                    text = formatCompactBarLabel(displayMillis),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isPeakHour) PeakAccentOrange else Color(0xFF737782),
                                    maxLines = 1,
                                    softWrap = false,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        // Clipped outer track so small bars never bleed outside rounded corners
                        Box(
                            modifier = Modifier
                                .width(22.dp)
                                .height(112.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(BarTrackGray),
                            contentAlignment = Alignment.BottomCenter
                        ) {
                            if (displayMillis > 0L) {
                                val heightFraction = (displayMillis.toFloat() / maxHourDuration.toFloat())
                                    .coerceIn(0.07f, 1f)
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height((112f * heightFraction).dp)
                                        .background(
                                            if (isPeakHour) PeakAccentOrange else PrimaryBlue
                                        )
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        Text(
                            text = point.hourLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isPeakHour) PeakAccentOrange else Color(0xFF737782),
                            maxLines = 1,
                            softWrap = false,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Legend Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(PrimaryBlue, CircleShape)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Regular hours",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF737782)
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(PeakAccentOrange, CircleShape)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Peak (${peakUsage.peakTimeWindowLabel})",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF737782),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/**
 * Compact label for the top of each 24-hour bar so adjacent bars never overlap.
 */
private fun formatCompactBarLabel(durationMs: Long): String {
    if (durationMs <= 0L) return ""
    val totalSeconds = ((durationMs + 500L) / 1000L).coerceAtLeast(1L)
    val hours = (totalSeconds / 3600L).toInt()
    val minutes = ((totalSeconds % 3600L) / 60L).toInt()
    val seconds = (totalSeconds % 60L).toInt()

    return when {
        hours > 0 && minutes > 0 -> "${hours}h ${minutes}m"
        hours > 0 -> "${hours}h"
        minutes > 0 -> "${minutes}m"
        else -> "${seconds}s"
    }
}

@Composable
private fun UsagePatternCard(patterns: UsagePatternAnalysis) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = CardWhite,
        tonalElevation = 1.dp
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(
                text = "USAGE PATTERN ANALYSIS",
                style = MaterialTheme.typography.labelSmall,
                color = LabelUpperGray
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "Weekdays vs weekends & daily trend",
                style = MaterialTheme.typography.titleMedium,
                color = TextDark
            )

            Spacer(modifier = Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Surface(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(16.dp),
                    color = DashboardBg
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = "WEEKDAY AVG",
                            style = MaterialTheme.typography.labelSmall,
                            color = LabelUpperGray
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = if (patterns.hasWeekdayData) {
                                SmartAnalyticsEngine.formatDuration(patterns.weekdayAvgDurationMs)
                            } else "No data",
                            style = MaterialTheme.typography.titleLarge,
                            color = TextDark
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "${patterns.activeWeekdayCount} active day(s)",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted
                        )
                    }
                }

                Surface(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(16.dp),
                    color = DashboardBg
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = "WEEKEND AVG",
                            style = MaterialTheme.typography.labelSmall,
                            color = LabelUpperGray
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = if (patterns.hasWeekendData) {
                                SmartAnalyticsEngine.formatDuration(patterns.weekendAvgDurationMs)
                            } else "No data",
                            style = MaterialTheme.typography.titleLarge,
                            color = PrimaryBlue
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "${patterns.activeWeekendCount} active day(s)",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            Text(
                text = "DAILY BREAKDOWN (${patterns.availableDaysCount} AVAILABLE DAYS)",
                style = MaterialTheme.typography.labelSmall,
                color = LabelUpperGray
            )

            Spacer(modifier = Modifier.height(12.dp))

            val maxDayDuration = patterns.dailyTrends
                .maxOfOrNull { it.totalDurationMs }
                ?.coerceAtLeast(1L) ?: 1L

            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                patterns.dailyTrends.forEach { day ->
                    val isHighest = patterns.highestUsageDay?.dateKey == day.dateKey
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "${day.dayOfWeekName} · ${day.dateKey}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (isHighest) FontWeight.SemiBold else FontWeight.Normal,
                                    color = TextDark
                                )
                                if (isHighest) {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = SoftBlueBadge
                                    ) {
                                        Text(
                                            text = "Highest",
                                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = PrimaryBlue
                                        )
                                    }
                                }
                            }

                            Text(
                                text = SmartAnalyticsEngine.formatDuration(day.totalDurationMs),
                                style = MaterialTheme.typography.titleSmall,
                                color = if (isHighest) PrimaryBlue else TextMuted
                            )
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        DashboardProgressBar(
                            progress = (day.totalDurationMs.toFloat() / maxDayDuration.toFloat()).coerceIn(0f, 1f),
                            fillColor = if (isHighest) PrimaryBlue else ConsistentTeal
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DashboardProgressBar(
    progress: Float,
    fillColor: Color
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(BarTrackGray)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .fillMaxHeight()
                .clip(RoundedCornerShape(4.dp))
                .background(fillColor)
        )
    }
}

@Composable
private fun EmptyAnalyticsCard(selectedPackagesCount: Int) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = CardWhite,
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "No usage data available yet",
                style = MaterialTheme.typography.titleMedium,
                color = TextDark
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = if (selectedPackagesCount == 0) {
                    "Add tracked apps on your Dashboard to start generating Smart Usage Intelligence."
                } else {
                    "AppSense only analyzes real usage data and never creates fake stats for empty days."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = TextMuted,
                textAlign = TextAlign.Center
            )
        }
    }
}