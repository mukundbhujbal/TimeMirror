package com.mukundbhujbal.timemirror.util

import android.content.Context
import android.content.Intent
import android.print.PrintAttributes
import android.print.PrintManager
import android.webkit.WebView
import android.webkit.WebViewClient
import com.mukundbhujbal.timemirror.data.AppPreferences
import com.mukundbhujbal.timemirror.data.DailyHistoryRecord
import com.mukundbhujbal.timemirror.ui.DayChartData
import com.mukundbhujbal.timemirror.ui.MonthDayData
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import kotlin.math.ceil
import kotlin.math.max

/**
 * Utility helper providing native Android Print and Share functionality for
 * Daily, Weekly, and Monthly reports.
 * Uses 100% Android-native PrintManager, WebView, and Intent.ACTION_SEND.
 */
object ReportPrintShareHelper {

    // =========================================================================
    // 1. DAILY REPORT — PRINT & SHARE
    // =========================================================================

    fun printDailyReport(
        context: Context,
        record: DailyHistoryRecord
    ) {
        val userName = AppPreferences.getInstance(context).getUserName()
        val userHeaderHtml = if (userName.isNotBlank()) "<p style='margin: 2px 0 0 0; color: #1565C0; font-size: 14px; font-weight: bold;'>User: $userName</p>" else ""

        val dateFormatted = try {
            LocalDate.parse(record.date).format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy"))
        } catch (e: Exception) {
            record.date
        }

        val topAppsHtml = if (record.topApps.isEmpty()) {
            "<p style='color: #666;'>No monitored app usage recorded on this day.</p>"
        } else {
            val rows = record.topApps.mapIndexed { index, app ->
                val appName = getAppName(context, app.packageName)
                """
                <tr>
                    <td style='padding: 8px; border-bottom: 1px solid #eee;'><b>#${index + 1}</b> $appName</td>
                    <td style='padding: 8px; border-bottom: 1px solid #eee; text-align: right; font-family: monospace; font-weight: bold;'>${formatDuration(app.usageSeconds)}</td>
                </tr>
                """.trimIndent()
            }.joinToString("")

            """
            <table style='width: 100%; border-collapse: collapse; margin-top: 10px;'>
                <thead>
                    <tr style='background: #f5f5f5; text-align: left;'>
                        <th style='padding: 8px;'>App</th>
                        <th style='padding: 8px; text-align: right;'>Usage Time</th>
                    </tr>
                </thead>
                <tbody>$rows</tbody>
            </table>
            """.trimIndent()
        }

        val html = """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <title>Daily Screen Time Report - ${record.date}</title>
                <style>
                    body { font-family: -apple-system, Roboto, Helvetica, Arial, sans-serif; margin: 30px; color: #222; }
                    .header { border-bottom: 2px solid #2196F3; padding-bottom: 12px; margin-bottom: 24px; }
                    .header h1 { margin: 0; font-size: 24px; color: #1565C0; }
                    .header p { margin: 4px 0 0 0; color: #666; font-size: 14px; }
                    .hero { background: #f0f7ff; border-radius: 8px; padding: 20px; text-align: center; margin-bottom: 24px; }
                    .hero .label { font-size: 12px; letter-spacing: 1.5px; color: #555; font-weight: bold; }
                    .hero .value { font-size: 38px; font-weight: bold; color: #1565C0; font-family: monospace; margin: 8px 0; }
                    .hero .sub { font-size: 12px; color: #666; }
                    .card { border: 1px solid #e0e0e0; border-radius: 8px; padding: 16px; margin-bottom: 20px; }
                    .card h3 { margin-top: 0; margin-bottom: 12px; font-size: 16px; color: #333; }
                    .metric-row { display: flex; justify-content: space-between; padding: 10px 0; border-bottom: 1px solid #f0f0f0; }
                    .metric-row:last-child { border-bottom: none; }
                    .metric-label { font-weight: 500; font-size: 14px; color: #000000; }
                    .metric-value { font-weight: bold; font-family: monospace; font-size: 15px; }
                    .footer { font-size: 11px; color: #888; text-align: center; margin-top: 30px; border-top: 1px solid #eee; padding-top: 10px; }
                    @media print {
                        body { margin: 20px; }
                        .page-break { page-break-before: always; break-before: page; }
                    }
                    .page-break { page-break-before: always; break-before: page; }
                </style>
            </head>
            <body>
                <div class="header">
                    <h1>TimeAware - भान</h1>
                    $userHeaderHtml
                    <p>Daily Screen Time Report • $dateFormatted</p>
                </div>

                <div class="hero">
                    <div class="label">TOTAL SCREEN TIME</div>
                    <div class="value">${formatDuration(record.totalScreenTimeSeconds)}</div>
                    <div class="sub">(On-Screen Timer + Without On-Screen Timer)</div>
                </div>

                <div class="card">
                    <h3>Usage Breakdown</h3>
                    <div class="metric-row">
                        <span class="metric-label">With On-Screen Timer</span>
                        <span class="metric-value" style="color: #4CAF50;">${formatDuration(record.withOnScreenTimerSeconds)}</span>
                    </div>
                    <div class="metric-row">
                        <span class="metric-label">Without On-Screen Timer</span>
                        <span class="metric-value" style="color: #FF9800;">${formatDuration(record.withoutOnScreenTimerSeconds)}</span>
                    </div>
                    <div class="metric-row">
                        <span class="metric-label">Monitoring Stopped Time</span>
                        <span class="metric-value" style="color: #000000;">${formatDuration(record.monitoringStoppedSeconds)}</span>
                    </div>
                </div>

                <div class="card">
                    <h3>Top Monitored Apps</h3>
                    $topAppsHtml
                </div>

                <div class="footer">
                    Generated by TimeAware - भान on ${LocalDate.now()}
                </div>
            </body>
            </html>
        """.trimIndent()

        printHtml(context, "TimeAware_Daily_${record.date}", html)
    }

    fun shareDailyReport(
        context: Context,
        record: DailyHistoryRecord
    ) {
        val userName = AppPreferences.getInstance(context).getUserName()
        val userPrefix = if (userName.isNotBlank()) "User: $userName\n" else ""

        val dateFormatted = try {
            LocalDate.parse(record.date).format(DateTimeFormatter.ofPattern("d MMMM yyyy"))
        } catch (e: Exception) {
            record.date
        }

        val content = """
            TimeAware - भान
            Daily Screen Time Report

            ${userPrefix}Date: $dateFormatted

            Total Screen Time: ${formatDuration(record.totalScreenTimeSeconds)}
            (On-Screen Timer + Without On-Screen Timer)

            Monitoring Stopped Time: ${formatDuration(record.monitoringStoppedSeconds)}

            Screen Time:

            With On-Screen Timer:
            ${formatDuration(record.withOnScreenTimerSeconds)}

            Without On-Screen Timer:
            ${formatDuration(record.withoutOnScreenTimerSeconds)}

            Generated with TimeAware - भान
        """.trimIndent()

        shareText(context, "TimeAware Daily Report - ${record.date}", content)
    }

    // =========================================================================
    // 2. WEEKLY REPORT — PRINT & SHARE
    // =========================================================================

    fun printWeeklyReport(
        context: Context,
        weekNumber: Int,
        monday: LocalDate,
        sunday: LocalDate,
        weekDataList: List<DayChartData>,
        recordedDaysCount: Int,
        avgTotalScreenTime: Long,
        avgWithOST: Long,
        avgWithoutOST: Long,
        avgStopped: Long
    ) {
        val userName = AppPreferences.getInstance(context).getUserName()
        val userHeaderHtml = if (userName.isNotBlank()) "<p style='margin: 2px 0 0 0; color: #1565C0; font-size: 14px; font-weight: bold;'>User: $userName</p>" else ""

        val dateRangeStr = "${monday.format(DateTimeFormatter.ofPattern("d MMM"))} – ${sunday.format(DateTimeFormatter.ofPattern("d MMM yyyy"))}"
        val totalWeekScreenTime = weekDataList.sumOf { it.totalScreenTime }
        val totalWeekStopped = weekDataList.sumOf { it.stoppedSeconds }

        // Generate clean SVG chart for the 7 days (Bar 1 stacked With/Without OST, Bar 2 Stopped Time)
        val rawMax = weekDataList.maxOfOrNull { max(it.totalScreenTime, it.stoppedSeconds) } ?: 0L
        val maxSeconds = calculateNiceScaleCeiling(rawMax)
        val chartSvg = generateWeekChartSvg(weekDataList, maxSeconds)

        val tableRows = weekDataList.joinToString("") { day ->
            """
            <tr>
                <td style='padding: 8px; border-bottom: 1px solid #eee;'><b>${day.dayLabel}</b> (${day.date})</td>
                <td style='padding: 8px; border-bottom: 1px solid #eee; text-align: right; font-family: monospace; color: #1565C0;'>${if (day.hasData) formatDuration(day.totalScreenTime) else "-"}</td>
                <td style='padding: 8px; border-bottom: 1px solid #eee; text-align: right; font-family: monospace; color: #2E7D32;'>${if (day.hasData) formatDuration(day.withTimerSeconds) else "-"}</td>
                <td style='padding: 8px; border-bottom: 1px solid #eee; text-align: right; font-family: monospace; color: #E65100;'>${if (day.hasData) formatDuration(day.withoutTimerSeconds) else "-"}</td>
                <td style='padding: 8px; border-bottom: 1px solid #eee; text-align: right; font-family: monospace; color: #757575;'>${if (day.hasData) formatDuration(day.stoppedSeconds) else "-"}</td>
            </tr>
            """.trimIndent()
        }

        val html = """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <title>Weekly Screen Time Report - Week $weekNumber</title>
                <style>
                    body { font-family: -apple-system, Roboto, Helvetica, Arial, sans-serif; margin: 30px; color: #222; }
                    .header { border-bottom: 2px solid #2196F3; padding-bottom: 12px; margin-bottom: 20px; }
                    .header h1 { margin: 0; font-size: 24px; color: #1565C0; }
                    .header p { margin: 4px 0 0 0; color: #666; font-size: 14px; }
                    .grid { display: grid; grid-template-columns: 1fr 1fr; gap: 14px; margin-bottom: 16px; }
                    .hero { background: #f0f7ff; border-radius: 8px; padding: 14px; text-align: center; }
                    .hero .label { font-size: 11px; letter-spacing: 1.2px; color: #555; font-weight: bold; }
                    .hero .value { font-size: 26px; font-weight: bold; color: #1565C0; font-family: monospace; margin: 4px 0; }
                    .hero .sub { font-size: 11px; color: #666; }
                    .card { border: 1px solid #e0e0e0; border-radius: 8px; padding: 14px; margin-bottom: 16px; }
                    .card h3 { margin-top: 0; margin-bottom: 10px; font-size: 15px; color: #333; }
                    .metric-row { display: flex; justify-content: space-between; padding: 7px 0; border-bottom: 1px solid #f0f0f0; }
                    .metric-row:last-child { border-bottom: none; }
                    .metric-label { font-weight: 500; font-size: 13px; color: #222; }
                    .metric-value { font-weight: bold; font-family: monospace; font-size: 14px; }
                    table { width: 100%; border-collapse: collapse; margin-top: 10px; font-size: 13px; }
                    th { background: #f5f5f5; padding: 8px; font-weight: 600; text-align: left; }
                    .footer { font-size: 11px; color: #888; text-align: center; margin-top: 24px; border-top: 1px solid #eee; padding-top: 10px; }
                    @media print {
                        body { margin: 20px; }
                        .page-break { page-break-before: always; break-before: page; }
                    }
                    .page-break { page-break-before: always; break-before: page; }
                </style>
            </head>
            <body>
                <!-- PAGE 1: Header, Summary, Visual Graph, Weekly Averages & Legend -->
                <div class="header">
                    <h1>TimeAware - भान</h1>
                    $userHeaderHtml
                    <p>Weekly Screen Time Report • Week $weekNumber ($dateRangeStr)</p>
                </div>

                <div class="grid">
                    <div class="hero">
                        <div class="label">TOTAL WEEK SCREEN TIME</div>
                        <div class="value">${formatDuration(totalWeekScreenTime)}</div>
                        <div class="sub">(On-Screen Timer + Without On-Screen Timer)</div>
                    </div>
                    <div class="hero" style="background: #f9f9f9;">
                        <div class="label" style="color: #666;">TOTAL STOPPED TIME</div>
                        <div class="value" style="color: #666;">${formatDuration(totalWeekStopped)}</div>
                        <div class="sub">Master Monitoring OFF</div>
                    </div>
                </div>

                <div class="card">
                    <h3 style="text-align: center; margin-bottom: 8px;">Daily Screen Time &nbsp; &amp;<br>Monitoring Service Stopped Time</h3>
                    $chartSvg
                </div>

                <div class="card">
                    <h3>Weekly Averages</h3>
                    <div class="metric-row">
                        <span class="metric-label">Daily Total Screen Time</span>
                        <span class="metric-value" style="color: #1565C0;">${formatDuration(avgTotalScreenTime)}</span>
                    </div>
                    <div class="metric-row">
                        <span class="metric-label">Daily Screen Time With On-Screen Timer</span>
                        <span class="metric-value" style="color: #2E7D32;">${formatDuration(avgWithOST)}</span>
                    </div>
                    <div class="metric-row">
                        <span class="metric-label">Daily Screen Time Without On-Screen Timer</span>
                        <span class="metric-value" style="color: #E65100;">${formatDuration(avgWithoutOST)}</span>
                    </div>
                    <div class="metric-row">
                        <span class="metric-label">Daily Monitoring Stopped Time</span>
                        <span class="metric-value" style="color: #757575;">${formatDuration(avgStopped)}</span>
                    </div>
                </div>

                <!-- PAGE 2: Complete Daily Breakdown Table -->
                <div class="card page-break">
                    <h3>Daily Breakdown &nbsp;&nbsp;&nbsp;&nbsp;<span style="font-size: 13px; font-weight: normal; color: #555;">(OST = On-Screen Time)</span></h3>
                    <table>
                        <thead>
                            <tr>
                                <th>Day</th>
                                <th style="text-align: right;">Total</th>
                                <th style="text-align: right;">With OST</th>
                                <th style="text-align: right;">Without OST</th>
                                <th style="text-align: right;">Stopped</th>
                            </tr>
                        </thead>
                        <tbody>
                            $tableRows
                        </tbody>
                    </table>
                </div>

                <div class="footer">
                    Generated by TimeAware - भान on ${LocalDate.now()}
                </div>
            </body>
            </html>
        """.trimIndent()

        printHtml(context, "TimeAware_Week_${weekNumber}_${monday}", html)
    }

    fun shareWeeklyReport(
        context: Context,
        weekNumber: Int,
        monday: LocalDate,
        sunday: LocalDate,
        weekDataList: List<DayChartData>,
        recordedDaysCount: Int,
        avgTotalScreenTime: Long,
        avgWithOST: Long,
        avgWithoutOST: Long,
        avgStopped: Long
    ) {
        val userName = AppPreferences.getInstance(context).getUserName()
        val userPrefix = if (userName.isNotBlank()) "User: $userName\n" else ""

        val dateRangeStr = "${monday.format(DateTimeFormatter.ofPattern("d MMM"))} – ${sunday.format(DateTimeFormatter.ofPattern("d MMM yyyy"))}"
        val totalWeekScreenTime = weekDataList.sumOf { it.totalScreenTime }
        val totalWeekStopped = weekDataList.sumOf { it.stoppedSeconds }

        val content = """
            TimeAware - भान
            Weekly Screen Time Report

            ${userPrefix}Week: Week $weekNumber ($dateRangeStr)

            Weekly Total Screen Time: ${formatDuration(totalWeekScreenTime)}
            (On-Screen Timer + Without On-Screen Timer)

            Total Monitoring Stopped Time: ${formatDuration(totalWeekStopped)}

            Weekly Averages:

            Daily Total Screen Time:
            ${formatDuration(avgTotalScreenTime)}

            Daily Screen Time With On-Screen Timer:
            ${formatDuration(avgWithOST)}

            Daily Screen Time Without On-Screen Timer:
            ${formatDuration(avgWithoutOST)}

            Daily Monitoring Stopped Time:
            ${formatDuration(avgStopped)}

            Generated with TimeAware - भान
        """.trimIndent()

        shareText(context, "TimeAware Weekly Report - Week $weekNumber", content)
    }

    // =========================================================================
    // 3. MONTHLY REPORT — PRINT & SHARE
    // =========================================================================

    fun printMonthlyReport(
        context: Context,
        selectedYearMonth: YearMonth,
        monthDataList: List<MonthDayData>,
        daysInMonth: Int,
        recordedDaysCount: Int,
        avgTotalScreenTime: Long,
        avgWithOST: Long,
        avgWithoutOST: Long,
        avgStopped: Long
    ) {
        val userName = AppPreferences.getInstance(context).getUserName()
        val userHeaderHtml = if (userName.isNotBlank()) "<p style='margin: 2px 0 0 0; color: #1565C0; font-size: 14px; font-weight: bold;'>User: $userName</p>" else ""

        val monthFormatted = selectedYearMonth.format(DateTimeFormatter.ofPattern("MMMM yyyy"))
        val totalMonthScreenTime = monthDataList.sumOf { it.totalScreenTime }
        val totalMonthStopped = monthDataList.sumOf { it.stoppedSeconds }

        val rawMax = monthDataList.maxOfOrNull { max(it.totalScreenTime, it.stoppedSeconds) } ?: 0L
        val maxSeconds = calculateNiceScaleCeiling(rawMax)
        val chartSvg = generateMonthChartSvg(monthDataList, daysInMonth, maxSeconds)

        val recordedRows = monthDataList.filter { it.hasData }.joinToString("") { day ->
            """
            <tr>
                <td style='padding: 6px 8px; border-bottom: 1px solid #eee;'>Day ${day.dayOfMonth} (${day.date})</td>
                <td style='padding: 6px 8px; border-bottom: 1px solid #eee; text-align: right; font-family: monospace; color: #1565C0;'>${formatDuration(day.totalScreenTime)}</td>
                <td style='padding: 6px 8px; border-bottom: 1px solid #eee; text-align: right; font-family: monospace; color: #2E7D32;'>${formatDuration(day.withTimerSeconds)}</td>
                <td style='padding: 6px 8px; border-bottom: 1px solid #eee; text-align: right; font-family: monospace; color: #E65100;'>${formatDuration(day.withoutTimerSeconds)}</td>
                <td style='padding: 6px 8px; border-bottom: 1px solid #eee; text-align: right; font-family: monospace; color: #757575;'>${formatDuration(day.stoppedSeconds)}</td>
            </tr>
            """.trimIndent()
        }

        val html = """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <title>Monthly Screen Time Report - $monthFormatted</title>
                <style>
                    body { font-family: -apple-system, Roboto, Helvetica, Arial, sans-serif; margin: 30px; color: #222; }
                    .header { border-bottom: 2px solid #2196F3; padding-bottom: 12px; margin-bottom: 20px; }
                    .header h1 { margin: 0; font-size: 24px; color: #1565C0; }
                    .header p { margin: 4px 0 0 0; color: #666; font-size: 14px; }
                    .grid { display: grid; grid-template-columns: 1fr 1fr; gap: 14px; margin-bottom: 16px; }
                    .hero { background: #f0f7ff; border-radius: 8px; padding: 14px; text-align: center; }
                    .hero .label { font-size: 11px; letter-spacing: 1.2px; color: #555; font-weight: bold; }
                    .hero .value { font-size: 26px; font-weight: bold; color: #1565C0; font-family: monospace; margin: 4px 0; }
                    .hero .sub { font-size: 11px; color: #666; }
                    .card { border: 1px solid #e0e0e0; border-radius: 8px; padding: 14px; margin-bottom: 16px; }
                    .card h3 { margin-top: 0; margin-bottom: 10px; font-size: 15px; color: #333; }
                    .metric-row { display: flex; justify-content: space-between; padding: 7px 0; border-bottom: 1px solid #f0f0f0; }
                    .metric-row:last-child { border-bottom: none; }
                    .metric-label { font-weight: 500; font-size: 13px; color: #222; }
                    .metric-value { font-weight: bold; font-family: monospace; font-size: 14px; }
                    table { width: 100%; border-collapse: collapse; margin-top: 10px; font-size: 13px; }
                    th { background: #f5f5f5; padding: 8px; font-weight: 600; text-align: left; }
                    .footer { font-size: 11px; color: #888; text-align: center; margin-top: 24px; border-top: 1px solid #eee; padding-top: 10px; }
                    @media print {
                        body { margin: 20px; }
                        .page-break { page-break-before: always; break-before: page; }
                    }
                    .page-break { page-break-before: always; break-before: page; }
                </style>
            </head>
            <body>
                <!-- PAGE 1: Header, Summary, Visual Trend Graph, Monthly Averages & Legend -->
                <div class="header">
                    <h1>TimeAware - भान</h1>
                    $userHeaderHtml
                    <p>Monthly Screen Time Report • $monthFormatted</p>
                </div>

                <div class="grid">
                    <div class="hero">
                        <div class="label">MONTHLY TOTAL SCREEN TIME</div>
                        <div class="value">${formatDuration(totalMonthScreenTime)}</div>
                        <div class="sub">(On-Screen Timer + Without On-Screen Timer)</div>
                    </div>
                    <div class="hero" style="background: #f9f9f9;">
                        <div class="label" style="color: #666;">TOTAL STOPPED TIME</div>
                        <div class="value" style="color: #666;">${formatDuration(totalMonthStopped)}</div>
                        <div class="sub">Master Monitoring OFF</div>
                    </div>
                </div>

                <div class="card">
                    <h3>Monthly Visual Trend</h3>
                    $chartSvg
                </div>

                <div class="card">
                    <h3>Monthly Averages</h3>
                    <div class="metric-row">
                        <span class="metric-label">Daily Total Screen Time</span>
                        <span class="metric-value" style="color: #1565C0;">${formatDuration(avgTotalScreenTime)}</span>
                    </div>
                    <div class="metric-row">
                        <span class="metric-label">Daily Screen Time With On-Screen Timer</span>
                        <span class="metric-value" style="color: #2E7D32;">${formatDuration(avgWithOST)}</span>
                    </div>
                    <div class="metric-row">
                        <span class="metric-label">Daily Screen Time Without On-Screen Timer</span>
                        <span class="metric-value" style="color: #E65100;">${formatDuration(avgWithoutOST)}</span>
                    </div>
                    <div class="metric-row">
                        <span class="metric-label">Daily Monitoring Stopped Time</span>
                        <span class="metric-value" style="color: #757575;">${formatDuration(avgStopped)}</span>
                    </div>
                </div>

                <!-- PAGE 2: Complete Daily Breakdown Table -->
                <div class="card page-break">
                    <h3>Daily Breakdown &nbsp;&nbsp;&nbsp;&nbsp;<span style="font-size: 13px; font-weight: normal; color: #555;">(OST = On-Screen Time)</span></h3>
                    <table>
                        <thead>
                            <tr>
                                <th>Date</th>
                                <th style="text-align: right;">Total</th>
                                <th style="text-align: right;">With OST</th>
                                <th style="text-align: right;">Without OST</th>
                                <th style="text-align: right;">Stopped</th>
                            </tr>
                        </thead>
                        <tbody>
                            $recordedRows
                        </tbody>
                    </table>
                </div>

                <div class="footer">
                    Generated by TimeAware - भान on ${LocalDate.now()}
                </div>
            </body>
            </html>
        """.trimIndent()

        printHtml(context, "TimeAware_Month_${selectedYearMonth}", html)
    }

    fun shareMonthlyReport(
        context: Context,
        selectedYearMonth: YearMonth,
        monthDataList: List<MonthDayData>,
        recordedDaysCount: Int,
        avgTotalScreenTime: Long,
        avgWithOST: Long,
        avgWithoutOST: Long,
        avgStopped: Long
    ) {
        val userName = AppPreferences.getInstance(context).getUserName()
        val userPrefix = if (userName.isNotBlank()) "User: $userName\n" else ""

        val monthFormatted = selectedYearMonth.format(DateTimeFormatter.ofPattern("MMMM yyyy"))
        val totalMonthScreenTime = monthDataList.sumOf { it.totalScreenTime }
        val totalMonthStopped = monthDataList.sumOf { it.stoppedSeconds }

        val content = """
            TimeAware - भान
            Monthly Screen Time Report

            ${userPrefix}Month: $monthFormatted

            Monthly Total Screen Time: ${formatDuration(totalMonthScreenTime)}
            (On-Screen Timer + Without On-Screen Timer)

            Total Monitoring Stopped Time: ${formatDuration(totalMonthStopped)}

            Monthly Averages:

            Daily Total Screen Time:
            ${formatDuration(avgTotalScreenTime)}

            Daily Screen Time With On-Screen Timer:
            ${formatDuration(avgWithOST)}

            Daily Screen Time Without On-Screen Timer:
            ${formatDuration(avgWithoutOST)}

            Daily Monitoring Stopped Time:
            ${formatDuration(avgStopped)}

            Generated with TimeAware - भान
        """.trimIndent()

        shareText(context, "TimeAware Monthly Report - $monthFormatted", content)
    }

    private fun getAppName(context: Context, packageName: String): String {
        val pm = context.packageManager
        return try {
            val info = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(info).toString()
        } catch (e: Exception) {
            when (packageName) {
                "com.google.android.youtube" -> "YouTube"
                "com.whatsapp" -> "WhatsApp"
                "com.instagram.android" -> "Instagram"
                "com.facebook.katana" -> "Facebook"
                "com.android.chrome" -> "Chrome"
                else -> packageName
            }
        }
    }

    // =========================================================================
    // SVG CHART GENERATION HELPERS
    // =========================================================================

    private fun generateWeekChartSvg(dataList: List<DayChartData>, maxSeconds: Long): String {
        val svgWidth = 600
        val svgHeight = 220
        val leftPad = 50
        val rightPad = 20
        val topPad = 20
        val bottomPad = 35

        val chartWidth = svgWidth - leftPad - rightPad
        val chartHeight = svgHeight - topPad - bottomPad

        val slotWidth = chartWidth / dataList.size
        val barWidth = 14
        val spacing = 4

        // Grid lines
        val gridLines = (0..3).map { i ->
            val ratio = i / 3.0
            val y = topPad + chartHeight * (1.0 - ratio)
            val sec = (maxSeconds * ratio).toLong()
            val label = formatHoursLabel(sec)
            "<line x1='$leftPad' y1='$y' x2='${svgWidth - rightPad}' y2='$y' stroke='#e0e0e0' stroke-width='1' />" +
            "<text x='${leftPad - 8}' y='${y + 4}' font-size='10' fill='#777' text-anchor='end'>$label</text>"
        }.joinToString("")

        // Bars & Labels
        val bars = dataList.mapIndexed { idx, day ->
            val centerX = leftPad + (idx + 0.5) * slotWidth
            val bar1Left = centerX - barWidth - spacing / 2.0
            val bar2Left = centerX + spacing / 2.0
            val bottomY = topPad + chartHeight

            var content = ""
            if (day.hasData) {
                val withOSTHeight = (day.withTimerSeconds.toDouble() / maxSeconds) * chartHeight
                val withoutOSTHeight = (day.withoutTimerSeconds.toDouble() / maxSeconds) * chartHeight
                val stoppedHeight = (day.stoppedSeconds.toDouble() / maxSeconds) * chartHeight

                // Bar 1 segment 1 (With OST - #4CAF50)
                if (withOSTHeight > 0) {
                    content += "<rect x='$bar1Left' y='${bottomY - withOSTHeight}' width='$barWidth' height='$withOSTHeight' fill='#4CAF50' rx='2' />"
                }
                // Bar 1 segment 2 (Without OST - #FF9800)
                if (withoutOSTHeight > 0) {
                    content += "<rect x='$bar1Left' y='${bottomY - withOSTHeight - withoutOSTHeight}' width='$barWidth' height='$withoutOSTHeight' fill='#FF9800' rx='2' />"
                }
                // Bar 2 (Stopped Time - #BDBDBD Light Gray)
                if (stoppedHeight > 0) {
                    content += "<rect x='$bar2Left' y='${bottomY - stoppedHeight}' width='$barWidth' height='$stoppedHeight' fill='#BDBDBD' rx='2' />"
                }
            }

            content += "<text x='$centerX' y='${bottomY + 16}' font-size='11' fill='#555' font-weight='bold' text-anchor='middle'>${day.dayLabel}</text>"
            content += "<text x='$centerX' y='${bottomY + 28}' font-size='10' fill='#888' text-anchor='middle'>${day.dateLabel}</text>"
            content
        }.joinToString("")

        val legend = """
            <g transform='translate(70, 10)'>
                <circle cx='5' cy='-3' r='4' fill='#4CAF50' />
                <text x='15' y='1' font-size='11' fill='#444'>With On-Screen Timer</text>

                <circle cx='165' cy='-3' r='4' fill='#FF9800' />
                <text x='175' y='1' font-size='11' fill='#444'>Without On-Screen Timer</text>

                <circle cx='345' cy='-3' r='4' fill='#BDBDBD' />
                <text x='355' y='1' font-size='11' fill='#444'>Monitoring service stopped</text>
            </g>
        """.trimIndent()

        return """
            <svg viewBox='0 0 $svgWidth $svgHeight' width='100%' height='220' xmlns='http://www.w3.org/2000/svg'>
                $legend
                $gridLines
                $bars
            </svg>
        """.trimIndent()
    }

    private fun generateMonthChartSvg(dataList: List<MonthDayData>, daysInMonth: Int, maxSeconds: Long): String {
        val svgWidth = 600
        val svgHeight = 220
        val leftPad = 50
        val rightPad = 20
        val topPad = 20
        val bottomPad = 30

        val chartWidth = svgWidth - leftPad - rightPad
        val chartHeight = svgHeight - topPad - bottomPad
        val bottomY = topPad + chartHeight

        // Grid lines
        val gridLines = (0..3).map { i ->
            val ratio = i / 3.0
            val y = topPad + chartHeight * (1.0 - ratio)
            val sec = (maxSeconds * ratio).toLong()
            val label = formatHoursLabel(sec)
            "<line x1='$leftPad' y1='$y' x2='${svgWidth - rightPad}' y2='$y' stroke='#e0e0e0' stroke-width='1' />" +
            "<text x='${leftPad - 8}' y='${y + 4}' font-size='10' fill='#777' text-anchor='end'>$label</text>"
        }.joinToString("")

        // X-axis ticks: Last day of month is always shown as final label; skip preceding interval if it overlaps (e.g. 30 on a 31-day month)
        val stepDays = if (daysInMonth <= 28) 4 else 5
        val xTicks = (1..daysInMonth).filter { day ->
            day == 1 || day == daysInMonth || (day % stepDays == 0 && (daysInMonth - day) >= 2)
        }.joinToString("") { day ->
            val x = leftPad + ((day - 1.0) / (daysInMonth - 1.0)) * chartWidth
            "<text x='$x' y='${bottomY + 16}' font-size='10' fill='#777' text-anchor='middle'>$day</text>"
        }

        // Generate line path coordinates for Line 1 (Total Screen Time - #2196F3) and Line 2 (Stopped Time - #BDBDBD)
        var line1Points = ""
        var line2Points = ""
        var nodes = ""

        dataList.forEach { day ->
            val x = leftPad + ((day.dayOfMonth - 1.0) / (daysInMonth - 1.0)) * chartWidth
            if (day.hasData) {
                val y1 = bottomY - (day.totalScreenTime.toDouble() / maxSeconds) * chartHeight
                val y2 = bottomY - (day.stoppedSeconds.toDouble() / maxSeconds) * chartHeight

                line1Points += if (line1Points.isEmpty()) "M $x $y1" else " L $x $y1"
                line2Points += if (line2Points.isEmpty()) "M $x $y2" else " L $x $y2"

                nodes += "<circle cx='$x' cy='$y1' r='3' fill='#2196F3' />"
                nodes += "<circle cx='$x' cy='$y2' r='3' fill='#BDBDBD' />"
            }
        }

        val linesSvg = """
            ${if (line1Points.isNotEmpty()) "<path d='$line1Points' fill='none' stroke='#2196F3' stroke-width='2.5' stroke-linecap='round' stroke-linejoin='round' />" else ""}
            ${if (line2Points.isNotEmpty()) "<path d='$line2Points' fill='none' stroke='#BDBDBD' stroke-width='2.5' stroke-linecap='round' stroke-linejoin='round' />" else ""}
        """.trimIndent()

        val legend = """
            <g transform='translate(80, 10)'>
                <circle cx='5' cy='-3' r='4' fill='#2196F3' />
                <text x='15' y='1' font-size='11' fill='#444'>Daily Total Screen Time (Line 1)</text>

                <circle cx='250' cy='-3' r='4' fill='#BDBDBD' />
                <text x='260' y='1' font-size='11' fill='#444'>Daily Monitoring Stopped Time (Line 2)</text>
            </g>
        """.trimIndent()

        return """
            <svg viewBox='0 0 $svgWidth $svgHeight' width='100%' height='220' xmlns='http://www.w3.org/2000/svg'>
                $legend
                $gridLines
                $xTicks
                $linesSvg
                $nodes
            </svg>
        """.trimIndent()
    }

    // =========================================================================
    // PRINT & SHARE NATIVE PLATFORM EXECUTORS
    // =========================================================================

    private fun printHtml(context: Context, jobName: String, htmlContent: String) {
        val webView = WebView(context)
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                val printManager = context.getSystemService(Context.PRINT_SERVICE) as? PrintManager
                val printAdapter = webView.createPrintDocumentAdapter(jobName)
                val printAttributes = PrintAttributes.Builder()
                    .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                    .setResolution(PrintAttributes.Resolution("res1", "default", 300, 300))
                    .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
                    .build()
                printManager?.print(jobName, printAdapter, printAttributes)
            }
        }
        webView.loadDataWithBaseURL(null, htmlContent, "text/html; charset=utf-8", "UTF-8", null)
    }

    private fun shareText(context: Context, title: String, content: String) {
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, title)
            putExtra(Intent.EXTRA_TEXT, content)
        }
        val chooser = Intent.createChooser(sendIntent, "Share Screen Time Report")
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }

    private fun formatDuration(totalSeconds: Long): String {
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return String.format("%02d:%02d:%02d", hours, minutes, seconds)
    }

    private fun formatHoursLabel(seconds: Long): String {
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        return if (hours > 0 && minutes > 0) {
            "${hours}h${minutes}m"
        } else if (hours > 0) {
            "${hours}h"
        } else if (minutes > 0) {
            "${minutes}m"
        } else {
            "0h"
        }
    }

    private fun calculateNiceScaleCeiling(rawMaxSeconds: Long): Long {
        val oneHour = 3600L
        if (rawMaxSeconds <= oneHour) return oneHour
        val hours = ceil(rawMaxSeconds.toDouble() / oneHour).toLong()
        return when {
            hours <= 3L -> 3L * oneHour
            hours <= 6L -> 6L * oneHour
            hours <= 12L -> 12L * oneHour
            else -> 24L * oneHour
        }
    }
}
