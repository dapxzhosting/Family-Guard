package com.familyguard.utils

import java.text.SimpleDateFormat
import java.util.*

class ScreenTimeReportGenerator {

    data class DailyUsage(
        val date: String,
        val appPackage: String,
        val durationMinutes: Long
    )

    data class WeeklyReport(
        val startDate: String,
        val endDate: String,
        val totalMinutes: Long,
        val topApps: List<Pair<String, Long>>,
        val averagePerDay: Long,
        val trendPercentage: Double
    )

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    fun groupByWeek(usageList: List<DailyUsage>): Map<String, List<DailyUsage>> {
        val calendar = Calendar.getInstance()
        return usageList.groupBy { usage ->
            val date = dateFormat.parse(usage.date) ?: Date()
            calendar.time = date
            calendar.set(Calendar.DAY_OF_WEEK, calendar.firstDayOfWeek)
            dateFormat.format(calendar.time)
        }
    }

    fun calculateTotalMinutes(usageList: List<DailyUsage>): Long {
        return usageList.sumOf { it.durationMinutes }
    }

    fun getTopApps(usageList: List<DailyUsage>, limit: Int = 5): List<Pair<String, Long>> {
        return usageList
            .groupBy { it.appPackage }
            .mapValues { entry -> entry.value.sumOf { it.durationMinutes } }
            .toList()
            .sortedByDescending { it.second }
            .take(limit)
    }

    fun calculateTrend(currentWeekMinutes: Long, previousWeekMinutes: Long): Double {
        if (previousWeekMinutes == 0L) return 0.0
        val diff = currentWeekMinutes - previousWeekMinutes
        return (diff.toDouble() / previousWeekMinutes.toDouble()) * 100
    }

    fun generateWeeklyReport(
        currentWeekUsage: List<DailyUsage>,
        previousWeekTotal: Long
    ): WeeklyReport {
        val total = calculateTotalMinutes(currentWeekUsage)
        val topApps = getTopApps(currentWeekUsage)
        val avgPerDay = if (currentWeekUsage.isNotEmpty()) total / 7 else 0L
        val trend = calculateTrend(total, previousWeekTotal)

        val sortedDates = currentWeekUsage.map { it.date }.sorted()
        val startDate = sortedDates.firstOrNull() ?: dateFormat.format(Date())
        val endDate = sortedDates.lastOrNull() ?: dateFormat.format(Date())

        return WeeklyReport(
            startDate = startDate,
            endDate = endDate,
            totalMinutes = total,
            topApps = topApps,
            averagePerDay = avgPerDay,
            trendPercentage = trend
        )
    }

    fun formatReportAsText(report: WeeklyReport): String {
        val hours = report.totalMinutes / 60
        val minutes = report.totalMinutes % 60
        val trendText = if (report.trendPercentage >= 0) {
            "naik ${String.format("%.1f", report.trendPercentage)}%"
        } else {
            "turun ${String.format("%.1f", -report.trendPercentage)}%"
        }

        val builder = StringBuilder()
        builder.append("Laporan Screen Time (${report.startDate} - ${report.endDate})\n")
        builder.append("Total pemakaian: ${hours} jam ${minutes} menit\n")
        builder.append("Dibanding minggu lalu: $trendText\n\n")
        builder.append("Aplikasi paling sering dipakai:\n")
        report.topApps.forEachIndexed { index, (app, minutes) ->
            builder.append("${index + 1}. $app - ${minutes} menit\n")
        }

        return builder.toString()
    }
}
