package com.mukundbhujbal.timemirror.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.mukundbhujbal.timemirror.data.AppPreferences
import com.mukundbhujbal.timemirror.engine.TimerEngine
import com.mukundbhujbal.timemirror.service.TimerOverlayService
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
fun DashboardScreen(
    appPreferences: AppPreferences,
    onSelectAppsClick: () -> Unit,
    onHistoryClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onRequestNotificationPermission: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val timerSeconds by TimerEngine.timerSecondsFlow.collectAsState()
    val isRunning by TimerEngine.isRunningFlow.collectAsState()
    val monitoredPackages by appPreferences.monitoredPackagesFlow.collectAsState()
    val isTimerHidden by appPreferences.isTimerHiddenFlow.collectAsState()
    val hideUntilTimestamp by appPreferences.hideUntilTimestampFlow.collectAsState()
    val isMonitoringActive by appPreferences.isMonitoringActiveFlow.collectAsState()

    var hasOverlayPermission by remember { mutableStateOf(PermissionHelper.hasOverlayPermission(context)) }
    var hasUsagePermission by remember { mutableStateOf(PermissionHelper.hasUsageStatsPermission(context)) }
    var hasNotificationPermission by remember { mutableStateOf(PermissionHelper.hasNotificationPermission(context)) }
    var showDurationDialog by remember { mutableStateOf(false) }
    var showUsageAccessDialog by remember { mutableStateOf(false) }
    var showOverlayDialog by remember { mutableStateOf(false) }

    var remainingSeconds by remember { mutableStateOf(appPreferences.getRemainingHideDurationSeconds()) }

    // Live countdown ticker when timer is temporarily hidden
    LaunchedEffect(isTimerHidden, hideUntilTimestamp) {
        if (isTimerHidden) {
            val now = System.currentTimeMillis()
            if (hideUntilTimestamp <= now) {
                // Already expired when screen opened / resumed
                appPreferences.resumeTimerNow()
                TimerOverlayService.notifyConfigChanged(context)
                remainingSeconds = 0L
            } else {
                while (isActive) {
                    val currentNow = System.currentTimeMillis()
                    if (currentNow >= hideUntilTimestamp) {
                        appPreferences.resumeTimerNow()
                        TimerOverlayService.notifyConfigChanged(context)
                        remainingSeconds = 0L
                        break
                    }
                    remainingSeconds = ((hideUntilTimestamp - currentNow) / 1000L).coerceAtLeast(0L)
                    delay(1000L)
                }
            }
        } else {
            remainingSeconds = 0L
        }
    }

    // Re-check permissions automatically whenever user returns to the app (ON_RESUME)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasOverlayPermission = PermissionHelper.hasOverlayPermission(context)
                hasUsagePermission = PermissionHelper.hasUsageStatsPermission(context)
                hasNotificationPermission = PermissionHelper.hasNotificationPermission(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val hours = timerSeconds / 3600
    val minutes = (timerSeconds % 3600) / 60
    val seconds = timerSeconds % 60
    val formattedTime = String.format("%02d:%02d:%02d", hours, minutes, seconds)

    val allPermissionsGranted = hasOverlayPermission && hasUsagePermission && hasNotificationPermission

    fun formatRemainingCountdown(totalSec: Long): String {
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return String.format("%d:%02d:%02d", h, m, s)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, bottom = 16.dp, top = 44.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // App Header
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "TimeAware – भान",
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontSize = 30.sp,
                    color = Color(0xFF38B6FF),
                    shadow = Shadow(
                        color = Color(0xFF00B0FF),
                        offset = Offset.Zero,
                        blurRadius = 24f
                    ),
                    textDecoration = TextDecoration.Underline
                ),
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Cumulative On-Screen Timer",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }

        // Permission Warning Card if anything is missing
        if (!allPermissionsGranted) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f)
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = "Permission Required",
                            tint = MaterialTheme.colorScheme.error
                        )
                        Text(
                            text = "Permissions Required",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }

                    Text(
                        text = "TimeAware - भान needs the following permissions to monitor screen time and display watermarks:",
                        style = MaterialTheme.typography.bodySmall
                    )

                    if (!hasOverlayPermission) {
                        Button(
                            onClick = {
                                showOverlayDialog = true
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Text("Grant 'Draw Over Other Apps' Permission")
                        }
                    }

                    if (!hasUsagePermission) {
                        Button(
                            onClick = {
                                showUsageAccessDialog = true
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Text("Grant 'Usage Access' Permission")
                        }
                    }

                    if (!hasNotificationPermission) {
                        Button(
                            onClick = onRequestNotificationPermission,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Text("Grant 'Notifications' Permission")
                        }
                    }
                }
            }
        }

        // Timer Display Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 32.dp, horizontal = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "TODAY'S SCREEN TIME",
                    style = MaterialTheme.typography.labelMedium,
                    letterSpacing = 2.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Text(
                    text = formattedTime,
                    fontSize = 40.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = if (isRunning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                )

                Text(
                    text = if (isRunning) "Actively recording selected app" else "Timer paused (auto-resumes when selected app opens and monitoring service : running)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(4.dp))

                // Monitoring Service status
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(if (isMonitoringActive) Color(0xFF4CAF50) else Color(0xFFE53935))
                    )
                    Text(
                        text = if (isMonitoringActive) "Monitoring Service : Running" else "Monitoring Service : Stopped",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }

        // Conditional "Start Monitoring Service" Button (shown ONLY when monitoring is stopped)
        if (!isMonitoringActive) {
            Button(
                onClick = {
                    appPreferences.setMonitoringActive(true)
                    TimerOverlayService.startService(context)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = null
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Start Monitoring Service",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        // Settings & App Selection Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Temporary Hide On-Screen Timer Control (Visible ONLY when Monitoring Service is Running)
                if (isMonitoringActive) {
                    if (!isTimerHidden) {
                        // Visible State: Show "Hide Timer" Action
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "On-Screen Timer visible on selected-opened app",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f)
                            )

                            Spacer(modifier = Modifier.width(8.dp))

                            FilledTonalButton(
                                onClick = { showDurationDialog = true },
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.filledTonalButtonColors(
                                    containerColor = Color(0xFFE0F2FE),
                                    contentColor = Color(0xFF03456F)
                                )
                            ) {
                                Text("Hide Timer", color = Color(0xFF03456F))
                            }
                        }
                    } else {
                        // Hidden State: Show Countdown & "Resume Now" Action
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(8.dp)
                                                .clip(CircleShape)
                                                .background(Color(0xFFFF9800))
                                        )
                                        Text(
                                            text = "Timer Hidden",
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = "${formatRemainingCountdown(remainingSeconds)} remaining",
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        text = "Tracking as Without On-Screen Timer",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                Button(
                                    onClick = {
                                        appPreferences.resumeTimerNow()
                                        TimerOverlayService.notifyConfigChanged(context)
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.primary
                                    )
                                ) {
                                    Text("Resume Now", fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }

                    HorizontalDivider()
                }

                // "Select Apps to Monitor" Navigation Button
                Button(
                    onClick = onSelectAppsClick,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFE0F2FE),
                        contentColor = Color(0xFF03456F)
                    )
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.List,
                        contentDescription = null,
                        tint = Color(0xFF03456F)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "Select Monitored Apps...",
                            style = MaterialTheme.typography.labelLarge,
                            color = Color(0xFF03456F)
                        )
                        Text(
                            text = "(${monitoredPackages.size} selected)",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF03456F)
                        )
                    }
                }

                // "Screen Time History" Navigation Button
                Button(
                    onClick = onHistoryClick,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFE0F2FE),
                        contentColor = Color(0xFF03456F)
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.DateRange,
                        contentDescription = null,
                        tint = Color(0xFF03456F)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Screen Time History",
                        color = Color(0xFF03456F)
                    )
                }

                // "Settings" Navigation Button
                Button(
                    onClick = onSettingsClick,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFE0F2FE),
                        contentColor = Color(0xFF03456F)
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = null,
                        tint = Color(0xFF03456F)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Settings",
                        color = Color(0xFF03456F)
                    )
                }
            }
        }
    }

    // 1–6 Hours Hide Duration Selection Dialog
    if (showDurationDialog) {
        AlertDialog(
            onDismissRequest = { showDurationDialog = false },
            title = {
                Text(
                    text = "Hide On-Screen Timer",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Select how long to hide the floating watermark overlay. Screen-time tracking will continue normally as 'Without On-Screen Timer'.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    val durations = listOf(1, 2, 3, 4, 5, 6)
                    durations.forEach { hours ->
                        OutlinedButton(
                            onClick = {
                                appPreferences.hideTimerForHours(hours)
                                TimerOverlayService.notifyConfigChanged(context)
                                showDurationDialog = false
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = if (hours == 1) "1 Hour" else "$hours Hours",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showDurationDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Prominent Disclosure Dialog for Usage Access
    if (showUsageAccessDialog) {
        AlertDialog(
            onDismissRequest = { showUsageAccessDialog = false },
            title = {
                Text(
                    text = "Usage Access Required",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = "TimeAware needs Usage Access to detect which app is currently active so it can calculate screen time for the apps you choose to monitor.\n\n" +
                                "What TimeAware accesses:\n" +
                                "• The package name of the currently active app and its usage activity.\n\n" +
                                "Why it is needed:\n" +
                                "• To calculate your screen time and show the TimeAware awareness watermark while a monitored app is in use.\n\n" +
                                "Privacy:\n" +
                                "• Your screen-time information is processed and stored on your device.\n" +
                                "• TimeAware does not upload or share this information with a server.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showUsageAccessDialog = false
                        PermissionHelper.openUsageAccessSettings(context)
                    }
                ) {
                    Text("Continue to Settings")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showUsageAccessDialog = false }
                ) {
                    Text("Not Now")
                }
            }
        )
    }

    // Prominent Disclosure Dialog for Overlay Permission
    if (showOverlayDialog) {
        AlertDialog(
            onDismissRequest = { showOverlayDialog = false },
            title = {
                Text(
                    text = "Display Over Other Apps Permission",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = "TimeAware needs permission to display a small screen-time watermark over the apps you choose to monitor.\n\n" +
                                "Why it is needed:\n" +
                                "• The watermark shows your cumulative screen time while a monitored app is in use.\n\n" +
                                "How it behaves:\n" +
                                "• The watermark is non-interactive, so it does not block taps, scrolling, or typing in the app underneath.\n" +
                                "• TimeAware uses this permission only to display the screen-time watermark.\n\n" +
                                "Privacy:\n" +
                                "• TimeAware does not record or capture your screen content.\n" +
                                "• Your screen-time information is processed and stored on your device.\n" +
                                "• TimeAware does not upload or share this information with a server.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showOverlayDialog = false
                        PermissionHelper.openOverlaySettings(context)
                    }
                ) {
                    Text("Continue to Settings")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showOverlayDialog = false }
                ) {
                    Text("Not Now")
                }
            }
        )
    }
}

