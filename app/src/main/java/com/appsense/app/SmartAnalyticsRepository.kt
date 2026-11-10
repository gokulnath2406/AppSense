package com.appsense.app

import android.content.Context
import android.content.pm.PackageManager
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Uses AppSenseUsageCalculator (exact same engine as MainActivity Dashboard)
 * across the last 10 calendar days so hourly & daily timings match 100%.
 */
class SmartAnalyticsRepository(private val context: Context) {

    companion object {
        private const val SCROLL_PREFS_NAME = "appsense_smart_scroll_telemetry"

        fun recordScrollEvent(
            context: Context,
            packageName: String,
            scrollDelta: Int = 1,
            timestampMs: Long = System.currentTimeMillis()
        ) {
            if (scrollDelta <= 0) return
            val prefs = context.getSharedPreferences(SCROLL_PREFS_NAME, Context.MODE_PRIVATE)
            val bucketKey = buildHourKey(packageName, timestampMs)
            val current = prefs.getInt(bucketKey, 0)
            prefs.edit().putInt(bucketKey, current + scrollDelta).apply()
        }

        private fun buildHourKey(packageName: String, timestampMs: Long): String {
            val sdf = SimpleDateFormat("yyyy-MM-dd-HH", Locale.US)
            return "${packageName}_${sdf.format(Date(timestampMs))}"
        }
    }

    fun loadSmartAnalyticsReport(
        selectedPackages: Set<String>,
        referenceTimeMs: Long = System.currentTimeMillis(),
        timeZone: TimeZone = TimeZone.getDefault()
    ): SmartAnalyticsReport {
        if (selectedPackages.isEmpty()) {
            return SmartAnalyticsEngine.analyze(
                sessions = emptyList(),
                selectedPackages = emptySet(),
                referenceTimeMs = referenceTimeMs,
                timeZone = timeZone
            )
        }

        val sessions = extractSessionsUsingAppSenseCalculator(
            selectedPackages = selectedPackages,
            referenceTimeMs = referenceTimeMs,
            timeZone = timeZone
        )

        return SmartAnalyticsEngine.analyze(
            sessions = sessions,
            selectedPackages = selectedPackages,
            referenceTimeMs = referenceTimeMs,
            timeZone = timeZone
        )
    }

    private fun extractSessionsUsingAppSenseCalculator(
        selectedPackages: Set<String>,
        referenceTimeMs: Long,
        timeZone: TimeZone
    ): List<UsageSessionRecord> {
        val scrollPrefs = context.getSharedPreferences(SCROLL_PREFS_NAME, Context.MODE_PRIVATE)
        val appNameCache = mutableMapOf<String, String>()
        val consumedScrollKeys = mutableSetOf<String>()
        val sessions = mutableListOf<UsageSessionRecord>()

        // Loop day-by-day for the last 10 calendar days (9 days ago .. today)
        for (dayOffset in (SmartAnalyticsEngine.MAX_WINDOW_DAYS - 1) downTo 0) {
            val dayStartCal = Calendar.getInstance(timeZone).apply {
                timeInMillis = referenceTimeMs
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                add(Calendar.DAY_OF_YEAR, -dayOffset)
            }
            val dayStart = dayStartCal.timeInMillis

            val dayEndCal = (dayStartCal.clone() as Calendar).apply {
                add(Calendar.DAY_OF_MONTH, 1)
            }
            val dayEnd = minOf(dayEndCal.timeInMillis, referenceTimeMs)

            if (dayEnd <= dayStart) continue

            val intervalsByPackage = try {
                AppSenseUsageCalculator.getIntervalsForPackages(
                    context = context,
                    packageNames = selectedPackages,
                    dayStart = dayStart,
                    dayEnd = dayEnd
                )
            } catch (e: Exception) {
                emptyMap()
            }

            for (pkg in selectedPackages) {
                val intervals = intervalsByPackage[pkg].orEmpty()
                val appName = resolveAppName(pkg, appNameCache)

                for (interval in intervals) {
                    val start = maxOf(interval.startTime, dayStart)
                    val end = minOf(interval.endTime, dayEnd)
                    if (end <= start) continue

                    val hourKey = buildHourKey(pkg, start)
                    val scrolls = if (consumedScrollKeys.add(hourKey)) {
                        scrollPrefs.getInt(hourKey, 0)
                    } else 0

                    sessions.add(
                        UsageSessionRecord(
                            packageName = pkg,
                            appName = appName,
                            startTimeMs = start,
                            endTimeMs = end,
                            scrollCount = scrolls
                        )
                    )
                }
            }
        }

        return sessions
    }

    private fun resolveAppName(packageName: String, cache: MutableMap<String, String>): String {
        cache[packageName]?.let { return it }
        val pm = context.packageManager
        val label = try {
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            packageName.substringAfterLast('.')
                .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() }
        }
        cache[packageName] = label
        return label
    }
}