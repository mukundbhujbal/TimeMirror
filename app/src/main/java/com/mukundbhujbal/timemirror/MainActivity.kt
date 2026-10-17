package com.mukundbhujbal.timemirror

import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import com.mukundbhujbal.timemirror.data.AppPreferences
import com.mukundbhujbal.timemirror.data.HistoryRepository
import com.mukundbhujbal.timemirror.engine.TimerEngine
import com.mukundbhujbal.timemirror.service.TimerOverlayService
import com.mukundbhujbal.timemirror.ui.AppSelectionScreen
import com.mukundbhujbal.timemirror.ui.DashboardScreen
import com.mukundbhujbal.timemirror.ui.HistoryScreen
import com.mukundbhujbal.timemirror.ui.PermissionHelper
import com.mukundbhujbal.timemirror.ui.SettingsScreen
import com.mukundbhujbal.timemirror.ui.theme.TimeMirrorTheme

class MainActivity : ComponentActivity() {

    private lateinit var appPreferences: AppPreferences
    private lateinit var historyRepository: HistoryRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "MainActivity launched with action: ${intent?.action}")
        enableEdgeToEdge()

        appPreferences = AppPreferences.getInstance(this)
        historyRepository = HistoryRepository.getInstance(this)

        // Ensure install date is initialized on first run
        appPreferences.ensureInstallDate()

        val withTimerSec = appPreferences.getTodayWithTimerSeconds()
        val withoutTimerSec = appPreferences.getTodayWithoutTimerSeconds()
        val savedDate = appPreferences.getLastActiveDate()
        val today = AppPreferences.getTodayDateString()

        if (savedDate.isNotEmpty() && savedDate != today) {
            // Day rollover occurred while app was inactive: finalize previous day.
            // H1-F1: capture the authoritative stopped-time value once and use that exact
            // value for finalization and for the atomic counter/date rollover below.
            val recordedStoppedSec = appPreferences.getTodayMonitoringStoppedSeconds()
            historyRepository.finalizeDay(
                date = savedDate,
                withTimerSeconds = withTimerSec,
                withoutTimerSeconds = withoutTimerSec,
                stoppedSeconds = recordedStoppedSec,
                appUsageMap = appPreferences.getTodayAppUsageMap(),
                monitoredPackages = appPreferences.getMonitoredPackages()
            )
            TimerEngine.initialize(0L, 0L, today)
            appPreferences.advanceDayAndConsumeStoppedTime(0L, 0L, today, recordedStoppedSec)
            appPreferences.saveTodayAppUsageMap(emptyMap())
        } else {
            TimerEngine.initialize(withTimerSec, withoutTimerSec, today)
        }

        // If monitoring was active, ensure service is running
        if (appPreferences.isMonitoringActive() && PermissionHelper.hasAllRequiredPermissions(this)) {
            TimerOverlayService.startService(this)
        }

        setContent {
            TimeMirrorTheme {
                var currentScreen by rememberSaveable { mutableStateOf("dashboard") }

                BackHandler(enabled = currentScreen != "dashboard") {
                    currentScreen = "dashboard"
                }

                val notificationPermissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission(),
                    onResult = { isGranted ->
                        if (isGranted && appPreferences.isMonitoringActive()) {
                            TimerOverlayService.startService(this@MainActivity)
                        }
                    }
                )

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                        when (currentScreen) {
                            "select_apps" -> {
                                AppSelectionScreen(
                                    appPreferences = appPreferences,
                                    onBackClick = { currentScreen = "dashboard" }
                                )
                            }
                            "history" -> {
                                HistoryScreen(
                                    historyRepository = historyRepository,
                                    appPreferences = appPreferences,
                                    onBackClick = { currentScreen = "dashboard" }
                                )
                            }
                            "settings" -> {
                                SettingsScreen(
                                    appPreferences = appPreferences,
                                    onBackClick = { currentScreen = "dashboard" }
                                )
                            }
                            else -> {
                                DashboardScreen(
                                    appPreferences = appPreferences,
                                    onSelectAppsClick = { currentScreen = "select_apps" },
                                    onHistoryClick = { currentScreen = "history" },
                                    onSettingsClick = { currentScreen = "settings" },
                                    onRequestNotificationPermission = {
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                            notificationPermissionLauncher.launch(
                                                android.Manifest.permission.POST_NOTIFICATIONS
                                            )
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val today = AppPreferences.getTodayDateString()
        val savedDate = appPreferences.getLastActiveDate()

        // Ensure service is running if monitoring is active and all permissions are granted
        if (::appPreferences.isInitialized && appPreferences.isMonitoringActive() && PermissionHelper.hasAllRequiredPermissions(this)) {
            TimerOverlayService.startService(this)
        }

        // Refresh / auto-expire hide timer if duration expired while app was in background
        if (::appPreferences.isInitialized) {
            appPreferences.isTimerTemporarilyHidden()
        }

        // Accumulate stopped time if monitoring is currently OFF
        if (::appPreferences.isInitialized && !appPreferences.isMonitoringActive()) {
            appPreferences.accumulateMonitoringStoppedTime(today)
        }

        // Refresh timer if date rolled over while app was paused
        if (TimerEngine.checkAndApplyMidnightRollover(today)) {
            // H1-F1: capture the authoritative stopped-time value once and use that exact
            // value for finalization and for the atomic counter/date rollover below.
            val recordedStoppedSec = appPreferences.getTodayMonitoringStoppedSeconds()
            // F2: stopped time may only be consumed once it has actually been recorded.
            // One Boolean drives both the finalization branch and the consumed amount, so
            // the two decisions cannot diverge.
            val canFinalizePreviousDay =
                savedDate.isNotEmpty() && savedDate != today && ::historyRepository.isInitialized
            if (canFinalizePreviousDay) {
                historyRepository.finalizeDay(
                    date = savedDate,
                    withTimerSeconds = appPreferences.getTodayWithTimerSeconds(),
                    withoutTimerSeconds = appPreferences.getTodayWithoutTimerSeconds(),
                    stoppedSeconds = recordedStoppedSec,
                    appUsageMap = appPreferences.getTodayAppUsageMap(),
                    monitoredPackages = appPreferences.getMonitoredPackages()
                )
            }
            // F2: when nothing was finalized, consume 0L so the stopped-time key is left
            // unchanged (remaining = max(current - 0, 0) = current) while the ordinary
            // with/without counters and the tracking date are still reset atomically.
            val stoppedToConsume = if (canFinalizePreviousDay) recordedStoppedSec else 0L
            appPreferences.advanceDayAndConsumeStoppedTime(0L, 0L, today, stoppedToConsume)
            appPreferences.saveTodayAppUsageMap(emptyMap())
        }
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}
