package com.mukundbhujbal.timemirror.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.mukundbhujbal.timemirror.MainActivity
import com.mukundbhujbal.timemirror.R
import com.mukundbhujbal.timemirror.data.AppPreferences
import com.mukundbhujbal.timemirror.data.HistoryRepository
import com.mukundbhujbal.timemirror.detector.ForegroundAppDetector
import com.mukundbhujbal.timemirror.engine.TimerEngine
import com.mukundbhujbal.timemirror.overlay.OverlayController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground Service that continuously orchestrates:
 * 1. Foreground app detection
 * 2. Unified TimerEngine & Daily History tracking with midnight reset
 * 3. OverlayController watermark positioning and visibility
 * 4. Persistent notification updates
 */
class TimerOverlayService : Service() {

    private lateinit var appPreferences: AppPreferences
    private lateinit var foregroundDetector: ForegroundAppDetector
    private lateinit var overlayController: OverlayController
    private lateinit var historyRepository: HistoryRepository

    private val serviceScope = CoroutineScope(Dispatchers.Default + Job())
    private var orchestratorJob: Job? = null
    private var isScreenReceiverRegistered = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    orchestratorJob?.cancel()
                    orchestratorJob = null
                }
                Intent.ACTION_SCREEN_ON -> {
                    if (orchestratorJob == null || orchestratorJob?.isActive == false) {
                        startOrchestratorLoop()
                    }
                }
            }
        }
    }

    private var lastSavedSeconds = 0L
    private var todayWithTimerSec = 0L
    private var todayWithoutTimerSec = 0L
    private var todayStoppedSec = 0L
    private val todayAppUsageMap = mutableMapOf<String, Long>()
    private var currentTrackingDate = ""

    override fun onCreate() {
        super.onCreate()

        appPreferences = AppPreferences.getInstance(this)
        foregroundDetector = ForegroundAppDetector(this)
        overlayController = OverlayController(this)
        historyRepository = HistoryRepository.getInstance(this)

        val savedDate = appPreferences.getLastActiveDate()
        val today = AppPreferences.getTodayDateString()
        currentTrackingDate = today

        todayWithTimerSec = appPreferences.getTodayWithTimerSeconds()
        todayWithoutTimerSec = appPreferences.getTodayWithoutTimerSeconds()
        todayStoppedSec = appPreferences.getTodayMonitoringStoppedSeconds()
        todayAppUsageMap.clear()
        todayAppUsageMap.putAll(appPreferences.getTodayAppUsageMap())

        if (savedDate.isNotEmpty() && savedDate != today) {
            // New day on service startup: finalize previous day's record
            historyRepository.finalizeDay(
                date = savedDate,
                withTimerSeconds = todayWithTimerSec,
                withoutTimerSeconds = todayWithoutTimerSec,
                stoppedSeconds = todayStoppedSec,
                appUsageMap = todayAppUsageMap,
                monitoredPackages = appPreferences.getMonitoredPackages()
            )
            todayWithTimerSec = 0L
            todayWithoutTimerSec = 0L
            todayStoppedSec = 0L
            todayAppUsageMap.clear()

            TimerEngine.initialize(0L, 0L, today)
            appPreferences.saveTodayHistoryCounters(0L, 0L, 0L, today)
            appPreferences.saveTodayAppUsageMap(emptyMap())
        } else {
            TimerEngine.initialize(todayWithTimerSec, todayWithoutTimerSec, today)
        }

        createNotificationChannel()
        startForegroundServiceNotification()
        registerScreenReceiver()
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (powerManager.isInteractive) {
            startOrchestratorLoop()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_SERVICE -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_UPDATE_CONFIG -> {
                // Config updated, will be picked up on next tick
            }
        }
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (powerManager.isInteractive && (orchestratorJob == null || orchestratorJob?.isActive == false)) {
            startOrchestratorLoop()
        }
        return START_STICKY
    }

    private fun startOrchestratorLoop() {
        orchestratorJob?.cancel()
        orchestratorJob = serviceScope.launch {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            while (isActive) {
                if (!powerManager.isInteractive) {
                    overlayController.hide()
                    delay(1000)
                    continue
                }

                val today = AppPreferences.getTodayDateString()
                val isNewDay = TimerEngine.checkAndApplyMidnightRollover(today) ||
                        (currentTrackingDate.isNotEmpty() && currentTrackingDate != today)

                if (isNewDay) {
                    val previousDate = if (currentTrackingDate.isNotEmpty()) currentTrackingDate else appPreferences.getLastActiveDate()
                    if (previousDate.isNotEmpty() && previousDate != today) {
                        historyRepository.finalizeDay(
                            date = previousDate,
                            withTimerSeconds = todayWithTimerSec,
                            withoutTimerSeconds = todayWithoutTimerSec,
                            stoppedSeconds = todayStoppedSec,
                            appUsageMap = todayAppUsageMap,
                            monitoredPackages = appPreferences.getMonitoredPackages()
                        )
                    }
                    todayWithTimerSec = 0L
                    todayWithoutTimerSec = 0L
                    todayStoppedSec = 0L
                    todayAppUsageMap.clear()
                    currentTrackingDate = today

                    TimerEngine.resetToday(today)
                    appPreferences.saveTodayHistoryCounters(0L, 0L, 0L, today)
                    appPreferences.saveTodayAppUsageMap(emptyMap())
                }

                val monitoredPackages = appPreferences.getMonitoredPackages()
                val isMonitoredActive = foregroundDetector.isMonitoredAppActive(monitoredPackages)
                val showOnScreenTime = appPreferences.isShowOnScreenTimeEnabled()

                if (isMonitoredActive) {
                    if (showOnScreenTime) {
                        todayWithTimerSec++
                        TimerEngine.updateCounters(todayWithTimerSec, todayWithoutTimerSec)
                        TimerEngine.resume()
                        overlayController.show()
                        overlayController.updateTime(TimerEngine.getElapsedTodaySeconds())
                    } else {
                        todayWithoutTimerSec++
                        TimerEngine.updateCounters(todayWithTimerSec, todayWithoutTimerSec)
                        TimerEngine.resume()
                        overlayController.hide()
                    }

                    val detectedPkg = foregroundDetector.detectForegroundPackage()
                    if (detectedPkg != null && monitoredPackages.contains(detectedPkg)) {
                        todayAppUsageMap[detectedPkg] = (todayAppUsageMap[detectedPkg] ?: 0L) + 1L
                    }
                } else {
                    TimerEngine.pause()
                    overlayController.hide()
                }

                TimerEngine.tick()
                val elapsedSeconds = TimerEngine.getElapsedTodaySeconds()

                // Save to SharedPreferences periodically (every 5 seconds)
                if (elapsedSeconds != lastSavedSeconds && elapsedSeconds % 5L == 0L) {
                    lastSavedSeconds = elapsedSeconds
                    appPreferences.saveTodayHistoryCounters(
                        todayWithTimerSec,
                        todayWithoutTimerSec,
                        todayStoppedSec,
                        today
                    )
                    appPreferences.saveTodayAppUsageMap(todayAppUsageMap)
                }

                // Update notification text
                updateNotification(elapsedSeconds, isMonitoredActive)

                delay(1000)
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_description)
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun startForegroundServiceNotification() {
        val notification = buildNotification(TimerEngine.getElapsedTodaySeconds(), false)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                } else {
                    0
                }
                startForeground(NOTIFICATION_ID, notification, fgsType)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            try {
                startForeground(NOTIFICATION_ID, notification)
            } catch (fallbackEx: Exception) {
                fallbackEx.printStackTrace()
            }
        }
    }

    private fun updateNotification(seconds: Long, isTracking: Boolean) {
        val notification = buildNotification(seconds, isTracking)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(seconds: Long, isTracking: Boolean): Notification {
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60
        val timeString = String.format("%02d:%02d:%02d", hours, minutes, secs)

        val statusText = if (isTracking) "Monitoring active app • $timeString" else "Idle • $timeString"

        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name) + " Active")
            .setContentText(statusText)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun registerScreenReceiver() {
        if (!isScreenReceiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            }
            ContextCompat.registerReceiver(this, screenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            isScreenReceiverRegistered = true
        }
    }

    private fun unregisterScreenReceiver() {
        if (isScreenReceiverRegistered) {
            try {
                unregisterReceiver(screenReceiver)
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                isScreenReceiverRegistered = false
            }
        }
    }

    override fun onDestroy() {
        unregisterScreenReceiver()
        orchestratorJob?.cancel()
        overlayController.destroy()
        TimerEngine.pause()
        val today = AppPreferences.getTodayDateString()
        appPreferences.saveTodayHistoryCounters(
            todayWithTimerSec,
            todayWithoutTimerSec,
            todayStoppedSec,
            today
        )
        appPreferences.saveTodayAppUsageMap(todayAppUsageMap)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val CHANNEL_ID = "timemirror_monitoring_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START_SERVICE = "com.mukundbhujbal.timemirror.action.START"
        const val ACTION_STOP_SERVICE = "com.mukundbhujbal.timemirror.action.STOP"
        const val ACTION_UPDATE_CONFIG = "com.mukundbhujbal.timemirror.action.UPDATE_CONFIG"

        fun startService(context: Context) {
            val intent = Intent(context, TimerOverlayService::class.java).apply {
                action = ACTION_START_SERVICE
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, TimerOverlayService::class.java).apply {
                action = ACTION_STOP_SERVICE
            }
            context.stopService(intent)
        }

        fun notifyConfigChanged(context: Context) {
            val intent = Intent(context, TimerOverlayService::class.java).apply {
                action = ACTION_UPDATE_CONFIG
            }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                // Ignore if service is not running
            }
        }
    }
}
