package com.mukundbhujbal.timemirror.ui

import android.app.DatePickerDialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mukundbhujbal.timemirror.data.AppPreferences
import com.mukundbhujbal.timemirror.data.AppUsageStat
import com.mukundbhujbal.timemirror.data.DailyHistoryRecord
import com.mukundbhujbal.timemirror.data.HistoryRepository
import com.mukundbhujbal.timemirror.engine.TimerEngine
import com.mukundbhujbal.timemirror.util.ReportPrintShareHelper
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.temporal.WeekFields
import java.util.*
import kotlin.math.ceil
import kotlin.math.max

// --- Chart Palette Identifiers ---
private val COLOR_A_WITH_OST = Color(0xFF4CAF50)          // Colour A: With On-Screen Timer (Green)
private val COLOR_B_WITHOUT_OST = Color(0xFFFF9800)       // Colour B: Without On-Screen Timer (Orange)
private val COLOR_C_STOPPED_TIME = Color(0xFFE53935)      // Colour C: Monitoring Stopped Time for Day Tab (Red)
private val COLOR_STOPPED_LIGHT_GRAY = Color(0xFFBDBDBD)  // Colour: Monitoring Stopped Time (Light Gray)
private val COLOR_D_TOTAL_SCREEN = Color(0xFF2196F3)      // Colour D: Daily Total Screen Time (Blue for line 1)
private val COLOR_E_STOPPED_LINE = Color(0xFFBDBDBD)      // Colour E: Daily Monitoring Stopped Time (Light Gray)

enum class HistoryTab(val title: String) {
    DAY("DAY"),
    WEEK("WEEK"),
    MONTH("MONTH")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    historyRepository: HistoryRepository,
    appPreferences: AppPreferences,
    onBackClick: () -> Unit
) {
    var selectedTab by remember { mutableStateOf(HistoryTab.DAY) }

    val installDateStr = remember { historyRepository.getInstallDate() }
    val installDate = remember(installDateStr) {
        try {
            LocalDate.parse(installDateStr)
        } catch (e: Exception) {
            LocalDate.now()
        }
    }
    val today = remember { LocalDate.now() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Screen Time History") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Three Primary Tabs: [ DAY ] [ WEEK ] [ MONTH ]
            PrimaryTabRow(
                selectedTabIndex = selectedTab.ordinal,
                containerColor = MaterialTheme.colorScheme.surface
            ) {
                HistoryTab.values().forEach { tab ->
                    Tab(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        text = {
                            Text(
                                text = tab.title,
                                fontWeight = if (selectedTab == tab) FontWeight.Bold else FontWeight.Medium,
                                fontSize = 15.sp
                            )
                        }
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
            ) {
                when (selectedTab) {
                    HistoryTab.DAY -> DayReportTab(
                        historyRepository = historyRepository,
                        appPreferences = appPreferences,
                        installDate = installDate,
                        today = today
                    )
                    HistoryTab.WEEK -> WeekReportTab(
                        historyRepository = historyRepository,
                        appPreferences = appPreferences,
                        installDate = installDate,
                        today = today
                    )
                    HistoryTab.MONTH -> MonthReportTab(
                        historyRepository = historyRepository,
                        appPreferences = appPreferences,
                        installDate = installDate,
                        today = today
                    )
                }
            }
        }
    }
}

// ==========================================
// 1. DAY TAB IMPLEMENTATION
// ==========================================
@Composable
fun DayReportTab(
    historyRepository: HistoryRepository,
    appPreferences: AppPreferences,
    installDate: LocalDate,
    today: LocalDate
) {
    val context = LocalContext.current
    var selectedDate by remember { mutableStateOf(today) }

    val isPrevEnabled = selectedDate.isAfter(installDate)
    val isNextEnabled = selectedDate.isBefore(today)

    // Observe live TimerEngine flow for today's real-time synchronization
    val liveTimerSeconds by TimerEngine.timerSecondsFlow.collectAsState()

    // Resolve daily record (live for today observing TimerEngine, stored DB record for past days)
    val dailyRecord = if (selectedDate == today) {
        val withTimer = TimerEngine.getWithTimerSeconds()
        val withoutTimer = TimerEngine.getWithoutTimerSeconds()
        val stopped = appPreferences.getTodayMonitoringStoppedSeconds()
        val appUsageMap = appPreferences.getTodayAppUsageMap()
        val monitoredPackages = appPreferences.getMonitoredPackages()

        val topApps = appUsageMap.entries
            .filter { monitoredPackages.contains(it.key) && it.value > 0L }
            .sortedByDescending { it.value }
            .take(3)
            .map { AppUsageStat(it.key, it.value) }

        @Suppress("UNUSED_VARIABLE")
        val tickSync = liveTimerSeconds

        DailyHistoryRecord(
            date = today.toString(),
            withOnScreenTimerSeconds = withTimer,
            withoutOnScreenTimerSeconds = withoutTimer,
            monitoringStoppedSeconds = stopped,
            topApps = topApps
        )
    } else {
        remember(selectedDate) {
            historyRepository.getRecordForDate(selectedDate.toString())
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // Date Selector Bar with Calendar Picker
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = {
                        if (isPrevEnabled) selectedDate = selectedDate.minusDays(1)
                    },
                    enabled = isPrevEnabled
                ) {
                    Icon(imageVector = Icons.Default.ChevronLeft, contentDescription = "Previous Day")
                }

                Surface(
                    onClick = {
                        openDatePickerDialog(
                            context = context,
                            currentDate = selectedDate,
                            minDate = installDate,
                            maxDate = today,
                            onDateSelected = { newDate -> selectedDate = newDate }
                        )
                    },
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.padding(horizontal = 4.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CalendarMonth,
                            contentDescription = "Select Date",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = if (selectedDate == today) "Today" else selectedDate.format(DateTimeFormatter.ofPattern("EEEE")),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = selectedDate.format(DateTimeFormatter.ofPattern("d MMM yyyy")),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                IconButton(
                    onClick = {
                        if (isNextEnabled) selectedDate = selectedDate.plusDays(1)
                    },
                    enabled = isNextEnabled
                ) {
                    Icon(imageVector = Icons.Default.ChevronRight, contentDescription = "Next Day")
                }
            }
        }

        // Daily Report Container
        if (dailyRecord != null) {
            DailyReportDetails(record = dailyRecord)
        } else {
            InsufficientDataCard(message = "TimeAware was installed recently. There isn't enough data to generate this report yet.")
        }
    }
}

@Composable
fun DailyReportDetails(record: DailyHistoryRecord) {
    val context = LocalContext.current

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // Total Screen Time Hero Card (strictly derived: withOST + withoutOST)
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "TOTAL SCREEN TIME",
                    style = MaterialTheme.typography.labelMedium,
                    letterSpacing = 2.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = formatDuration(record.totalScreenTimeSeconds),
                    fontSize = 42.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "(On-Screen Timer + Without On-Screen Timer)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Breakdown Card (With OST, Without OST, Monitoring Stopped)
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Usage Breakdown",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                MetricRow(
                    label = "With On-Screen Timer",
                    value = formatDuration(record.withOnScreenTimerSeconds),
                    valueColor = COLOR_A_WITH_OST
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                MetricRow(
                    label = "Without On-Screen Timer",
                    value = formatDuration(record.withoutOnScreenTimerSeconds),
                    valueColor = COLOR_B_WITHOUT_OST
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                MetricRow(
                    label = "Monitoring Stopped Time",
                    value = formatDuration(record.monitoringStoppedSeconds),
                    valueColor = COLOR_C_STOPPED_TIME,
                    subtitle = "Master Monitoring was OFF"
                )
            }
        }

        // Top Monitored Apps Card (Min 1, Max 3)
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Top Monitored Apps",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                if (record.topApps.isEmpty()) {
                    Text(
                        text = "No monitored app usage recorded on this day.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                } else {
                    record.topApps.forEachIndexed { index, appStat ->
                        TopAppItemRow(
                            context = context,
                            rank = index + 1,
                            packageName = appStat.packageName,
                            usageSeconds = appStat.usageSeconds
                        )
                        if (index < record.topApps.size - 1) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        }
                    }
                }
            }
        }

        // Action Bar: Print & Share
        ReportActionBar(
            onPrintClick = {
                ReportPrintShareHelper.printDailyReport(context, record)
            },
            onShareClick = {
                ReportPrintShareHelper.shareDailyReport(context, record)
            }
        )
    }
}

// ==========================================
// 2. WEEK TAB IMPLEMENTATION
// ==========================================

data class DayChartData(
    val date: LocalDate,
    val dayLabel: String,       // e.g. "Mon"
    val dateLabel: String,      // e.g. "17"
    val withTimerSeconds: Long,
    val withoutTimerSeconds: Long,
    val totalScreenTime: Long,  // Derived: withTimer + withoutTimer
    val stoppedSeconds: Long,
    val hasData: Boolean
)

@Composable
fun WeekReportTab(
    historyRepository: HistoryRepository,
    appPreferences: AppPreferences,
    installDate: LocalDate,
    today: LocalDate
) {
    val context = LocalContext.current
    val weekFields = remember { WeekFields.ISO }

    var currentMonday by remember {
        mutableStateOf(today.with(weekFields.dayOfWeek(), 1L))
    }

    val currentSunday = remember(currentMonday) { currentMonday.plusDays(6) }
    val weekNumber = remember(currentMonday) { currentMonday.get(weekFields.weekOfWeekBasedYear()) }

    val installMonday = remember(installDate) { installDate.with(weekFields.dayOfWeek(), 1L) }
    val todayMonday = remember(today) { today.with(weekFields.dayOfWeek(), 1L) }

    val isPrevEnabled = currentMonday.isAfter(installMonday)
    val isNextEnabled = currentMonday.isBefore(todayMonday)

    // Build the 7 days of the selected week
    val weekDays = remember(currentMonday) {
        (0..6).map { currentMonday.plusDays(it.toLong()) }
    }

    // Query records in this week's range
    val weekRecordsMap = remember(currentMonday, appPreferences) {
        val dbRecords = historyRepository.getRecordsInRange(currentMonday.toString(), currentSunday.toString())
        val map = dbRecords.associateBy { it.date }.toMutableMap()

        // If current week includes today, ensure today's live record is factored
        if (!currentMonday.isAfter(today) && !currentSunday.isBefore(today)) {
            val withTimer = appPreferences.getTodayWithTimerSeconds()
            val withoutTimer = appPreferences.getTodayWithoutTimerSeconds()
            val stopped = appPreferences.getTodayMonitoringStoppedSeconds()
            map[today.toString()] = DailyHistoryRecord(
                date = today.toString(),
                withOnScreenTimerSeconds = withTimer,
                withoutOnScreenTimerSeconds = withoutTimer,
                monitoringStoppedSeconds = stopped,
                topApps = emptyList()
            )
        }
        map
    }

    val weekDataList = remember(weekDays, weekRecordsMap) {
        weekDays.map { date ->
            val record = weekRecordsMap[date.toString()]
            val withTimer = record?.withOnScreenTimerSeconds ?: 0L
            val withoutTimer = record?.withoutOnScreenTimerSeconds ?: 0L
            val total = withTimer + withoutTimer // strictly derived
            val stopped = record?.monitoringStoppedSeconds ?: 0L
            val hasData = record != null && (total > 0L || stopped > 0L)

            DayChartData(
                date = date,
                dayLabel = date.format(DateTimeFormatter.ofPattern("EEE")),
                dateLabel = date.format(DateTimeFormatter.ofPattern("d")),
                withTimerSeconds = withTimer,
                withoutTimerSeconds = withoutTimer,
                totalScreenTime = total,
                stoppedSeconds = stopped,
                hasData = hasData
            )
        }
    }

    val hasAnyData = remember(weekDataList) { weekDataList.any { it.hasData } }

    // Summary calculations (averages calculated at runtime across recorded days)
    val recordedDaysCount = remember(weekDataList) {
        weekDataList.count { it.hasData }.coerceAtLeast(1)
    }
    val totalScreenTimeSum = remember(weekDataList) { weekDataList.sumOf { it.totalScreenTime } }
    val totalWithOSTSum = remember(weekDataList) { weekDataList.sumOf { it.withTimerSeconds } }
    val totalWithoutOSTSum = remember(weekDataList) { weekDataList.sumOf { it.withoutTimerSeconds } }
    val totalStoppedSum = remember(weekDataList) { weekDataList.sumOf { it.stoppedSeconds } }

    val avgTotalScreenTime = remember(totalScreenTimeSum, recordedDaysCount) { totalScreenTimeSum / recordedDaysCount }
    val avgWithOST = remember(totalWithOSTSum, recordedDaysCount) { totalWithOSTSum / recordedDaysCount }
    val avgWithoutOST = remember(totalWithoutOSTSum, recordedDaysCount) { totalWithoutOSTSum / recordedDaysCount }
    val avgStopped = remember(totalStoppedSum, recordedDaysCount) { totalStoppedSum / recordedDaysCount }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // Week Selector Bar
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = {
                        if (isPrevEnabled) currentMonday = currentMonday.minusWeeks(1)
                    },
                    enabled = isPrevEnabled
                ) {
                    Icon(imageVector = Icons.Default.ChevronLeft, contentDescription = "Previous Week")
                }

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(vertical = 4.dp)
                ) {
                    Text(
                        text = "Week ${String.format("%02d", weekNumber)}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "${currentMonday.format(DateTimeFormatter.ofPattern("d MMM"))} – ${currentSunday.format(DateTimeFormatter.ofPattern("d MMM yyyy"))}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                IconButton(
                    onClick = {
                        if (isNextEnabled) currentMonday = currentMonday.plusWeeks(1)
                    },
                    enabled = isNextEnabled
                ) {
                    Icon(imageVector = Icons.Default.ChevronRight, contentDescription = "Next Week")
                }
            }
        }

        if (hasAnyData) {
            // Week Bar Graph Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = "Daily Screen Time   &\nMonitoring Service Stopped Time",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Week Graph: 2 Bars per day (Bar 1 stacked With/Without OST, Bar 2 Stopped Time)
                    WeekBarChart(
                        dataList = weekDataList,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp)
                    )

                    // Week Graph Legend
                    WeekGraphLegend()
                }
            }

            // Week Summary Card (4 Daily Averages calculated at runtime)
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Weekly Averages",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Based on $recordedDaysCount active day(s)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    MetricRow(
                        label = "Daily Total Screen Time",
                        value = formatDuration(avgTotalScreenTime),
                        valueColor = Color.White
                    )

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    MetricRow(
                        label = "Daily Screen Time\nWith On-Screen Timer",
                        value = formatDuration(avgWithOST),
                        valueColor = COLOR_A_WITH_OST,
                        labelTextAlign = TextAlign.Center
                    )

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    MetricRow(
                        label = "Daily Screen Time\nWithout On-Screen Timer",
                        value = formatDuration(avgWithoutOST),
                        valueColor = COLOR_B_WITHOUT_OST,
                        labelTextAlign = TextAlign.Center
                    )

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    MetricRow(
                        label = "Daily Monitoring Stopped Time",
                        value = formatDuration(avgStopped),
                        valueColor = COLOR_STOPPED_LIGHT_GRAY
                    )
                }
            }

            // Action Bar: Print & Share
            ReportActionBar(
                onPrintClick = {
                    ReportPrintShareHelper.printWeeklyReport(
                        context = context,
                        weekNumber = weekNumber,
                        monday = currentMonday,
                        sunday = currentSunday,
                        weekDataList = weekDataList,
                        recordedDaysCount = recordedDaysCount,
                        avgTotalScreenTime = avgTotalScreenTime,
                        avgWithOST = avgWithOST,
                        avgWithoutOST = avgWithoutOST,
                        avgStopped = avgStopped
                    )
                },
                onShareClick = {
                    ReportPrintShareHelper.shareWeeklyReport(
                        context = context,
                        weekNumber = weekNumber,
                        monday = currentMonday,
                        sunday = currentSunday,
                        weekDataList = weekDataList,
                        recordedDaysCount = recordedDaysCount,
                        avgTotalScreenTime = avgTotalScreenTime,
                        avgWithOST = avgWithOST,
                        avgWithoutOST = avgWithoutOST,
                        avgStopped = avgStopped
                    )
                }
            )
        } else {
            InsufficientDataCard(message = "TimeAware was installed recently. There isn't enough data to generate this report yet.")
        }
    }
}

@Composable
fun WeekBarChart(
    dataList: List<DayChartData>,
    modifier: Modifier = Modifier
) {
    val axisColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()

    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height

        val leftPad = 48.dp.toPx()
        val bottomPad = 32.dp.toPx()
        val topPad = 12.dp.toPx()
        val rightPad = 12.dp.toPx()

        val chartWidth = width - leftPad - rightPad
        val chartHeight = height - topPad - bottomPad

        // Determine max Y scale across all 7 days
        val rawMax = dataList.maxOfOrNull { max(it.totalScreenTime, it.stoppedSeconds) } ?: 0L
        val maxSeconds = calculateNiceScaleCeiling(rawMax)

        // Draw 3 Horizontal Grid lines & Y-axis labels
        val gridSteps = 3
        val paint = Paint().apply {
            color = labelColor
            textSize = 10.sp.toPx()
            textAlign = Paint.Align.RIGHT
            isAntiAlias = true
        }

        for (i in 0..gridSteps) {
            val ratio = i.toFloat() / gridSteps
            val y = topPad + chartHeight * (1f - ratio)
            val sec = (maxSeconds * ratio).toLong()

            drawLine(
                color = axisColor,
                start = Offset(leftPad, y),
                end = Offset(width - rightPad, y),
                strokeWidth = 1.dp.toPx()
            )

            val label = formatHoursLabel(sec)
            drawContext.canvas.nativeCanvas.drawText(
                label,
                leftPad - 8.dp.toPx(),
                y + 4.dp.toPx(),
                paint
            )
        }

        // Draw 7 Days Dual Bars
        val daySlotWidth = chartWidth / dataList.size
        val barWidth = 8.dp.toPx()
        val barSpacing = 3.dp.toPx()

        val dayPaint = Paint().apply {
            color = labelColor
            textSize = 10.sp.toPx()
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }

        dataList.forEachIndexed { index, dayData ->
            val slotCenterX = leftPad + (index + 0.5f) * daySlotWidth
            val bar1Left = slotCenterX - barWidth - barSpacing / 2f
            val bar2Left = slotCenterX + barSpacing / 2f

            val bottomY = topPad + chartHeight

            if (dayData.hasData) {
                // --- BAR 1: Stacked Total Screen Time (With OST + Without OST) ---
                val withOSTHeight = (dayData.withTimerSeconds.toFloat() / maxSeconds) * chartHeight
                val withoutOSTHeight = (dayData.withoutTimerSeconds.toFloat() / maxSeconds) * chartHeight

                // Bottom segment: With OST (Colour A)
                if (withOSTHeight > 0f) {
                    drawRoundRect(
                        color = COLOR_A_WITH_OST,
                        topLeft = Offset(bar1Left, bottomY - withOSTHeight),
                        size = Size(barWidth, withOSTHeight),
                        cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx())
                    )
                }

                // Top segment: Without OST (Colour B)
                if (withoutOSTHeight > 0f) {
                    drawRoundRect(
                        color = COLOR_B_WITHOUT_OST,
                        topLeft = Offset(bar1Left, bottomY - withOSTHeight - withoutOSTHeight),
                        size = Size(barWidth, withoutOSTHeight),
                        cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx())
                    )
                }

                // --- BAR 2: Monitoring Stopped Time (Light Gray) ---
                val stoppedHeight = (dayData.stoppedSeconds.toFloat() / maxSeconds) * chartHeight
                if (stoppedHeight > 0f) {
                    drawRoundRect(
                        color = COLOR_STOPPED_LIGHT_GRAY,
                        topLeft = Offset(bar2Left, bottomY - stoppedHeight),
                        size = Size(barWidth, stoppedHeight),
                        cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx())
                    )
                }
            }

            // X-axis day label (e.g. "Mon 17")
            drawContext.canvas.nativeCanvas.drawText(
                dayData.dayLabel,
                slotCenterX,
                bottomY + 16.dp.toPx(),
                dayPaint
            )
            drawContext.canvas.nativeCanvas.drawText(
                dayData.dateLabel,
                slotCenterX,
                bottomY + 28.dp.toPx(),
                dayPaint
            )
        }
    }
}

@Composable
fun WeekGraphLegend() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            LegendItem(color = COLOR_A_WITH_OST, text = "With On-Screen Timer")
            LegendItem(color = COLOR_B_WITHOUT_OST, text = "Without On-Screen Timer")
            LegendItem(color = COLOR_STOPPED_LIGHT_GRAY, text = "Monitoring service stopped")
        }
    }
}

// ==========================================
// 3. MONTH TAB IMPLEMENTATION
// ==========================================

data class MonthDayData(
    val dayOfMonth: Int,
    val date: LocalDate,
    val totalScreenTime: Long,  // Derived: withOST + withoutOST
    val withTimerSeconds: Long,
    val withoutTimerSeconds: Long,
    val stoppedSeconds: Long,
    val hasData: Boolean
)

@Composable
fun MonthReportTab(
    historyRepository: HistoryRepository,
    appPreferences: AppPreferences,
    installDate: LocalDate,
    today: LocalDate
) {
    val context = LocalContext.current
    val installYearMonth = remember(installDate) { YearMonth.from(installDate) }
    val todayYearMonth = remember(today) { YearMonth.from(today) }

    var selectedYearMonth by remember { mutableStateOf(todayYearMonth) }

    val isPrevEnabled = selectedYearMonth.isAfter(installYearMonth)
    val isNextEnabled = selectedYearMonth.isBefore(todayYearMonth)

    val monthStart = remember(selectedYearMonth) { selectedYearMonth.atDay(1) }
    val monthEnd = remember(selectedYearMonth) { selectedYearMonth.atEndOfMonth() }

    val monthRecordsMap = remember(selectedYearMonth, appPreferences) {
        val dbRecords = historyRepository.getRecordsInRange(monthStart.toString(), monthEnd.toString())
        val map = dbRecords.associateBy { it.date }.toMutableMap()

        // If current month is selected, include today's live record
        if (selectedYearMonth == todayYearMonth) {
            val withTimer = appPreferences.getTodayWithTimerSeconds()
            val withoutTimer = appPreferences.getTodayWithoutTimerSeconds()
            val stopped = appPreferences.getTodayMonitoringStoppedSeconds()
            map[today.toString()] = DailyHistoryRecord(
                date = today.toString(),
                withOnScreenTimerSeconds = withTimer,
                withoutOnScreenTimerSeconds = withoutTimer,
                monitoringStoppedSeconds = stopped,
                topApps = emptyList()
            )
        }
        map
    }

    val daysInMonth = remember(selectedYearMonth) { selectedYearMonth.lengthOfMonth() }
    val monthDataList = remember(selectedYearMonth, monthRecordsMap) {
        (1..daysInMonth).map { dayNum ->
            val date = selectedYearMonth.atDay(dayNum)
            val record = monthRecordsMap[date.toString()]
            val withTimer = record?.withOnScreenTimerSeconds ?: 0L
            val withoutTimer = record?.withoutOnScreenTimerSeconds ?: 0L
            val total = withTimer + withoutTimer // strictly derived
            val stopped = record?.monitoringStoppedSeconds ?: 0L
            val hasData = record != null && (total > 0L || stopped > 0L)

            MonthDayData(
                dayOfMonth = dayNum,
                date = date,
                totalScreenTime = total,
                withTimerSeconds = withTimer,
                withoutTimerSeconds = withoutTimer,
                stoppedSeconds = stopped,
                hasData = hasData
            )
        }
    }

    val hasAnyData = remember(monthDataList) { monthDataList.any { it.hasData } }

    // Summary calculations across recorded days
    val recordedDaysCount = remember(monthDataList) {
        monthDataList.count { it.hasData }.coerceAtLeast(1)
    }
    val totalScreenTimeSum = remember(monthDataList) { monthDataList.sumOf { it.totalScreenTime } }
    val totalWithOSTSum = remember(monthDataList) { monthDataList.sumOf { it.withTimerSeconds } }
    val totalWithoutOSTSum = remember(monthDataList) { monthDataList.sumOf { it.withoutTimerSeconds } }
    val totalStoppedSum = remember(monthDataList) { monthDataList.sumOf { it.stoppedSeconds } }

    val avgTotalScreenTime = remember(totalScreenTimeSum, recordedDaysCount) { totalScreenTimeSum / recordedDaysCount }
    val avgWithOST = remember(totalWithOSTSum, recordedDaysCount) { totalWithOSTSum / recordedDaysCount }
    val avgWithoutOST = remember(totalWithoutOSTSum, recordedDaysCount) { totalWithoutOSTSum / recordedDaysCount }
    val avgStopped = remember(totalStoppedSum, recordedDaysCount) { totalStoppedSum / recordedDaysCount }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // Month Selector Bar
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = {
                        if (isPrevEnabled) selectedYearMonth = selectedYearMonth.minusMonths(1)
                    },
                    enabled = isPrevEnabled
                ) {
                    Icon(imageVector = Icons.Default.ChevronLeft, contentDescription = "Previous Month")
                }

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(vertical = 4.dp)
                ) {
                    Text(
                        text = selectedYearMonth.format(DateTimeFormatter.ofPattern("MMMM yyyy")),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                IconButton(
                    onClick = {
                        if (isNextEnabled) selectedYearMonth = selectedYearMonth.plusMonths(1)
                    },
                    enabled = isNextEnabled
                ) {
                    Icon(imageVector = Icons.Default.ChevronRight, contentDescription = "Next Month")
                }
            }
        }

        if (hasAnyData) {
            // Monthly Line Graph Card (2 Lines: Daily Total Screen Time & Daily Monitoring Stopped Time)
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = "Monthly Trends",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )

                    MonthLineChart(
                        dataList = monthDataList,
                        daysInMonth = daysInMonth,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp)
                    )

                    // Month Graph Legend
                    MonthGraphLegend()
                }
            }

            // Month Summary Card (4 Daily Averages calculated at runtime)
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Monthly Averages",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Based on $recordedDaysCount active day(s)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    MetricRow(
                        label = "Daily Total Screen Time",
                        value = formatDuration(avgTotalScreenTime),
                        valueColor = Color.White
                    )

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    MetricRow(
                        label = "Daily Screen Time\nWith On-Screen Timer",
                        value = formatDuration(avgWithOST),
                        valueColor = COLOR_A_WITH_OST,
                        labelTextAlign = TextAlign.Center
                    )

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    MetricRow(
                        label = "Daily Screen Time\nWithout On-Screen Timer",
                        value = formatDuration(avgWithoutOST),
                        valueColor = COLOR_B_WITHOUT_OST,
                        labelTextAlign = TextAlign.Center
                    )

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    MetricRow(
                        label = "Daily Monitoring Stopped Time",
                        value = formatDuration(avgStopped),
                        valueColor = COLOR_STOPPED_LIGHT_GRAY
                    )
                }
            }

            // Action Bar: Print & Share
            ReportActionBar(
                onPrintClick = {
                    ReportPrintShareHelper.printMonthlyReport(
                        context = context,
                        selectedYearMonth = selectedYearMonth,
                        monthDataList = monthDataList,
                        daysInMonth = daysInMonth,
                        recordedDaysCount = recordedDaysCount,
                        avgTotalScreenTime = avgTotalScreenTime,
                        avgWithOST = avgWithOST,
                        avgWithoutOST = avgWithoutOST,
                        avgStopped = avgStopped
                    )
                },
                onShareClick = {
                    ReportPrintShareHelper.shareMonthlyReport(
                        context = context,
                        selectedYearMonth = selectedYearMonth,
                        monthDataList = monthDataList,
                        recordedDaysCount = recordedDaysCount,
                        avgTotalScreenTime = avgTotalScreenTime,
                        avgWithOST = avgWithOST,
                        avgWithoutOST = avgWithoutOST,
                        avgStopped = avgStopped
                    )
                }
            )
        } else {
            InsufficientDataCard(message = "TimeAware was installed recently. There isn't enough data to generate this report yet.")
        }
    }
}

@Composable
fun MonthLineChart(
    dataList: List<MonthDayData>,
    daysInMonth: Int,
    modifier: Modifier = Modifier
) {
    val axisColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()

    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height

        val leftPad = 48.dp.toPx()
        val bottomPad = 24.dp.toPx()
        val topPad = 12.dp.toPx()
        val rightPad = 16.dp.toPx()

        val chartWidth = width - leftPad - rightPad
        val chartHeight = height - topPad - bottomPad

        // Determine max Y scale across month
        val rawMax = dataList.maxOfOrNull { max(it.totalScreenTime, it.stoppedSeconds) } ?: 0L
        val maxSeconds = calculateNiceScaleCeiling(rawMax)

        // Draw Horizontal Grid lines & Y labels
        val gridSteps = 3
        val paint = Paint().apply {
            color = labelColor
            textSize = 10.sp.toPx()
            textAlign = Paint.Align.RIGHT
            isAntiAlias = true
        }

        for (i in 0..gridSteps) {
            val ratio = i.toFloat() / gridSteps
            val y = topPad + chartHeight * (1f - ratio)
            val sec = (maxSeconds * ratio).toLong()

            drawLine(
                color = axisColor,
                start = Offset(leftPad, y),
                end = Offset(width - rightPad, y),
                strokeWidth = 1.dp.toPx()
            )

            val label = formatHoursLabel(sec)
            drawContext.canvas.nativeCanvas.drawText(
                label,
                leftPad - 8.dp.toPx(),
                y + 4.dp.toPx(),
                paint
            )
        }

        // Draw X-axis tick labels (e.g. 1, 5, 10, 15, 20, 25, end)
        val xPaint = Paint().apply {
            color = labelColor
            textSize = 10.sp.toPx()
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }

        val stepDays = when {
            daysInMonth <= 28 -> 4
            else -> 5
        }

        val bottomY = topPad + chartHeight

        for (day in 1..daysInMonth) {
            if (day == 1 || day == daysInMonth || (day % stepDays == 0 && (daysInMonth - day) >= 2)) {
                val x = leftPad + ((day - 1).toFloat() / (daysInMonth - 1).coerceAtLeast(1)) * chartWidth
                drawContext.canvas.nativeCanvas.drawText(
                    "$day",
                    x,
                    bottomY + 18.dp.toPx(),
                    xPaint
                )
            }
        }

        // Draw LINE 1 (Daily Total Screen Time — Colour D) & LINE 2 (Daily Stopped Time — Colour E)
        val line1Path = Path()
        val line2Path = Path()

        var hasLine1Started = false
        var hasLine2Started = false

        dataList.forEach { dayData ->
            val day = dayData.dayOfMonth
            val x = leftPad + ((day - 1).toFloat() / (daysInMonth - 1).coerceAtLeast(1)) * chartWidth

            if (dayData.hasData) {
                // Line 1: Total Screen Time (Derived)
                val y1 = bottomY - (dayData.totalScreenTime.toFloat() / maxSeconds) * chartHeight
                if (!hasLine1Started) {
                    line1Path.moveTo(x, y1)
                    hasLine1Started = true
                } else {
                    line1Path.lineTo(x, y1)
                }

                // Line 2: Stopped Time
                val y2 = bottomY - (dayData.stoppedSeconds.toFloat() / maxSeconds) * chartHeight
                if (!hasLine2Started) {
                    line2Path.moveTo(x, y2)
                    hasLine2Started = true
                } else {
                    line2Path.lineTo(x, y2)
                }
            }
        }

        // Stroke Lines
        if (hasLine1Started) {
            drawPath(
                path = line1Path,
                color = COLOR_D_TOTAL_SCREEN,
                style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
        }

        if (hasLine2Started) {
            drawPath(
                path = line2Path,
                color = COLOR_E_STOPPED_LINE,
                style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
        }

        // Draw Nodes
        dataList.forEach { dayData ->
            if (dayData.hasData) {
                val day = dayData.dayOfMonth
                val x = leftPad + ((day - 1).toFloat() / (daysInMonth - 1).coerceAtLeast(1)) * chartWidth

                val y1 = bottomY - (dayData.totalScreenTime.toFloat() / maxSeconds) * chartHeight
                drawCircle(color = COLOR_D_TOTAL_SCREEN, radius = 3.dp.toPx(), center = Offset(x, y1))

                val y2 = bottomY - (dayData.stoppedSeconds.toFloat() / maxSeconds) * chartHeight
                drawCircle(color = COLOR_E_STOPPED_LINE, radius = 3.dp.toPx(), center = Offset(x, y2))
            }
        }
    }
}

@Composable
fun MonthGraphLegend() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        LegendItem(color = COLOR_D_TOTAL_SCREEN, text = "Daily Total Screen Time (Line 1)")
        LegendItem(color = COLOR_STOPPED_LIGHT_GRAY, text = "Daily Monitoring Stopped Time (Line 2)")
    }
}

// ==========================================
// REUSABLE UI & UTILITY HELPERS
// ==========================================

@Composable
fun ReportActionBar(
    onPrintClick: () -> Unit,
    onShareClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        OutlinedButton(
            onClick = onPrintClick,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(8.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Print,
                contentDescription = "Print",
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text("Print", fontWeight = FontWeight.SemiBold)
        }

        Button(
            onClick = onShareClick,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(8.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Share,
                contentDescription = "Share",
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text("Share", fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
fun LegendItem(color: Color, text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(color, CircleShape)
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
fun MetricRow(
    label: String,
    value: String,
    valueColor: Color = Color.White,
    subtitle: String? = null,
    labelTextAlign: TextAlign = TextAlign.Start
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                textAlign = labelTextAlign
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = labelTextAlign
                )
            }
        }
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            color = valueColor
        )
    }
}

@Composable
fun TopAppItemRow(
    context: Context,
    rank: Int,
    packageName: String,
    usageSeconds: Long
) {
    val pm = context.packageManager
    val appName = remember(packageName) {
        try {
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo).toString()
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
    val appIcon = remember(packageName) {
        try {
            val drawable = pm.getApplicationIcon(packageName)
            drawableToBitmap(drawable)
        } catch (e: Exception) {
            null
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(28.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = "$rank",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        if (appIcon != null) {
            Image(
                bitmap = appIcon.asImageBitmap(),
                contentDescription = appName,
                modifier = Modifier.size(36.dp)
            )
        } else {
            Spacer(modifier = Modifier.size(36.dp))
        }

        Spacer(modifier = Modifier.width(12.dp))

        Text(
            text = appName,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f)
        )

        Text(
            text = formatDuration(usageSeconds),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
fun InsufficientDataCard(message: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp)
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
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

private fun openDatePickerDialog(
    context: Context,
    currentDate: LocalDate,
    minDate: LocalDate,
    maxDate: LocalDate,
    onDateSelected: (LocalDate) -> Unit
) {
    val calendar = Calendar.getInstance()
    val dialog = DatePickerDialog(
        context,
        { _, year, month, dayOfMonth ->
            val selected = LocalDate.of(year, month + 1, dayOfMonth)
            if (!selected.isBefore(minDate) && !selected.isAfter(maxDate)) {
                onDateSelected(selected)
            }
        },
        currentDate.year,
        currentDate.monthValue - 1,
        currentDate.dayOfMonth
    )

    calendar.set(minDate.year, minDate.monthValue - 1, minDate.dayOfMonth, 0, 0, 0)
    dialog.datePicker.minDate = calendar.timeInMillis

    calendar.set(maxDate.year, maxDate.monthValue - 1, maxDate.dayOfMonth, 23, 59, 59)
    dialog.datePicker.maxDate = calendar.timeInMillis

    dialog.show()
}

private fun drawableToBitmap(drawable: Drawable): Bitmap? {
    return try {
        val width = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else 96
        val height = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else 96
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = AndroidCanvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        bitmap
    } catch (e: Exception) {
        null
    }
}
