package com.mukundbhujbal.timemirror.detector

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.SystemClock

/**
 * Detects the currently active foreground application using UsageStatsManager.
 * Retains active package state between polls to handle continuous usage sessions
 * and includes debounce handling for rapid app switching.
 */
class ForegroundAppDetector(private val context: Context) {

    private val usageStatsManager: UsageStatsManager? =
        context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager

    @Volatile
    private var currentForegroundPackage: String? = null

    @Volatile
    private var lastMonitoredPackage: String? = null

    @Volatile
    private var lastMonitoredExitTimestamp: Long = 0L

    private val selfPackageName: String = context.packageName

    /**
     * Queries recent system events and returns the current top foreground package.
     */
    fun detectForegroundPackage(): String? {
        val usm = usageStatsManager ?: return null

        val now = System.currentTimeMillis()
        val startTime = now - 10000 // 10-second lookback window

        try {
            val events = usm.queryEvents(startTime, now)
            val event = UsageEvents.Event()

            var latestResumeEventTime = 0L
            var latestResumePackage: String? = null

            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                    if (event.timeStamp >= latestResumeEventTime) {
                        latestResumeEventTime = event.timeStamp
                        latestResumePackage = event.packageName
                    }
                }
            }

            if (latestResumePackage != null) {
                currentForegroundPackage = latestResumePackage
            } else if (currentForegroundPackage == null) {
                // Fallback on initial launch if no recent resume events in the 10s window
                val stats = usm.queryUsageStats(
                    UsageStatsManager.INTERVAL_DAILY,
                    startTime,
                    now
                )
                val mostRecent = stats?.maxByOrNull { it.lastTimeUsed }
                if (mostRecent != null && mostRecent.lastTimeUsed > 0) {
                    currentForegroundPackage = mostRecent.packageName
                }
            }
        } catch (e: Exception) {
            // Handle security or unexpected exceptions gracefully
        }

        return currentForegroundPackage
    }

    /**
     * Determines whether the detected package is a monitored external app.
     * Incorporates a transition grace period (800ms) when switching directly
     * between monitored apps to ensure continuous timer operation.
     */
    fun isMonitoredAppActive(monitoredPackages: Set<String>): Boolean {
        val activePackage = detectForegroundPackage()

        // Ignore self (TimeMirror) as external monitored app
        if (activePackage == selfPackageName || activePackage == null) {
            val nowRealtime = SystemClock.elapsedRealtime()
            // Grace period check for direct task switching
            if (lastMonitoredPackage != null && (nowRealtime - lastMonitoredExitTimestamp) < 800L) {
                return true
            }
            return false
        }

        val isMonitored = monitoredPackages.contains(activePackage)

        if (isMonitored) {
            lastMonitoredPackage = activePackage
            lastMonitoredExitTimestamp = SystemClock.elapsedRealtime()
            return true
        } else {
            val nowRealtime = SystemClock.elapsedRealtime()
            if (lastMonitoredPackage != null && (nowRealtime - lastMonitoredExitTimestamp) < 800L) {
                return true
            }
            lastMonitoredPackage = null
            return false
        }
    }
}
