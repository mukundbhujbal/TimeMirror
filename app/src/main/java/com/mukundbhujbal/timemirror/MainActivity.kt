package com.mukundbhujbal.timemirror

import android.content.Intent
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
import com.mukundbhujbal.timemirror.ui.GatewayScreen
import com.mukundbhujbal.timemirror.ui.HistoryScreen
import com.mukundbhujbal.timemirror.ui.PermissionHelper
import com.mukundbhujbal.timemirror.ui.SettingsScreen
import com.mukundbhujbal.timemirror.ui.theme.TimeMirrorTheme
import com.mukundbhujbal.timemirror.widget.WidgetRenderer
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {

    private lateinit var appPreferences: AppPreferences
    private lateinit var historyRepository: HistoryRepository

    /** One-shot widget request: delivered by MainActivity, consumed once by the Gateway screen. */
    private val pendingGatewayRequest = MutableStateFlow<GatewayRequest?>(null)

    /** Id of the last widget request the Gateway consumed (survives configuration changes). */
    private var handledRequestId = 0L

    /** Restored handled id; prevents replaying a consumed request after process recreation. */
    private var restoredHandledRequestId = 0L

    private data class GatewayRequest(val packageName: String, val requestId: Long)

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

        // Widget tap that started this instance (cold start).
        restoredHandledRequestId = savedInstanceState?.getLong(EXTRA_GATEWAY_REQUEST_ID, 0L) ?: 0L
        handleGatewayIntent(intent)
        // The restore guard applies to this creation only; later widget taps (onNewIntent) are new.
        restoredHandledRequestId = 0L

        setContent {
            TimeMirrorTheme {
                var currentScreen by rememberSaveable { mutableStateOf("dashboard") }
                val gatewayRequest by pendingGatewayRequest.collectAsState()

                // A widget icon always opens the Gateway; the Gateway consumes the request once.
                LaunchedEffect(gatewayRequest) {
                    if (gatewayRequest != null) {
                        currentScreen = "gateway"
                    }
                }

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
                            "gateway" -> {
                                GatewayScreen(
                                    appPreferences = appPreferences,
                                    onBackClick = { currentScreen = "dashboard" },
                                    gatewayTarget = gatewayRequest?.packageName,
                                    gatewayRequestId = gatewayRequest?.requestId ?: 0L,
                                    onGatewayRequestHandled = {
                                        handledRequestId =
                                            gatewayRequest?.requestId ?: handledRequestId
                                        pendingGatewayRequest.value = null
                                    }
                                )
                            }
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
                                    onGatewayClick = { currentScreen = "gateway" },
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleGatewayIntent(intent)
    }

    /**
     * Converts a widget-launched intent into a one-shot Gateway request.
     *
     * The request id is stored in the intent itself when first assigned, so a configuration
     * change re-delivers the same id and the Gateway does not launch the target twice. A new
     * widget tap arrives through onNewIntent without an id and receives a fresh one.
     */
    private fun handleGatewayIntent(intent: Intent?) {
        val target = intent?.getStringExtra(EXTRA_GATEWAY_TARGET) ?: return
        val existingRequestId = intent.getLongExtra(EXTRA_GATEWAY_REQUEST_ID, 0L)
        if (existingRequestId > 0L) {
            // Same request re-delivered after a configuration change. If it was already
            // consumed, do not replay it.
            if (existingRequestId == restoredHandledRequestId) return
            pendingGatewayRequest.value = GatewayRequest(target, existingRequestId)
            return
        }
        if (restoredHandledRequestId > 0L) {
            // The activity was restored after this widget request was already consumed; do not
            // replay the launch. Keep the id so a further configuration change stays idempotent.
            intent.putExtra(EXTRA_GATEWAY_REQUEST_ID, restoredHandledRequestId)
            return
        }
        val newId = (System.nanoTime() and Long.MAX_VALUE).coerceAtLeast(1L)
        intent.putExtra(EXTRA_GATEWAY_REQUEST_ID, newId)
        pendingGatewayRequest.value = GatewayRequest(target, newId)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong(EXTRA_GATEWAY_REQUEST_ID, handledRequestId)
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

        // Lightweight foreground-event refresh: keeps both widgets current after date or selection
        // changes that happened while the app was away. No-op when no widget instance exists.
        WidgetRenderer.refreshAll(this)
    }

    companion object {
        private const val TAG = "MainActivity"

        /** Intent action used by widget PendingIntents to request a Gateway launch. */
        const val ACTION_OPEN_VIA_GATEWAY = "com.mukundbhujbal.timemirror.action.OPEN_VIA_GATEWAY"

        /** Exact target package of the widget icon that was tapped. */
        const val EXTRA_GATEWAY_TARGET = "com.mukundbhujbal.timemirror.extra.GATEWAY_TARGET"

        /** One-shot id so a pending request survives recreation without launching twice. */
        const val EXTRA_GATEWAY_REQUEST_ID = "com.mukundbhujbal.timemirror.extra.GATEWAY_REQUEST_ID"
    }
}
