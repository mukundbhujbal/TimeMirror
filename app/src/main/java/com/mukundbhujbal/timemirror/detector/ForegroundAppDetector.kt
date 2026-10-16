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

    @Volatile
    private var lastKnownMonitoredPackages: Set<String> = emptySet()

    @Volatile
    private var lastVerificationRealtime: Long = 0L

    private val selfPackageName: String = context.packageName

    /**
     * Queries recent system events and returns the current top foreground package.
     */
    fun detectForegroundPackage(): String? = detectForegroundPackage(lastKnownMonitoredPackages)

    /**
     * Overloaded query taking currently monitored packages to enable
     * self-correction of unmonitored stale latches when event windows elapse.
     */
    fun detectForegroundPackage(monitoredPackages: Set<String>): String? {
        lastKnownMonitoredPackages = monitoredPackages
        val usm = usageStatsManager ?: return null

        val now = System.currentTimeMillis()
        val startTime = now - EVENT_LOOKBACK_WINDOW_MS

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
                val verificationStartTime = now - USAGE_STATS_VERIFICATION_WINDOW_MS
                val stats = usm.queryUsageStats(
                    UsageStatsManager.INTERVAL_DAILY,
                    verificationStartTime,
                    now
                )
                val mostRecent = stats
                    ?.filter { it.lastTimeUsed > 0L }
                    ?.maxByOrNull { it.lastTimeUsed }
                if (mostRecent != null) {
                    currentForegroundPackage = mostRecent.packageName
                }
            } else if (monitoredPackages.isNotEmpty() && !monitoredPackages.contains(currentForegroundPackage)) {
                // Stale unmonitored latch detection:
                // When no recent ACTIVITY_RESUMED event occurred in the 10s window and the detector
                // is currently latched to an unmonitored package (e.g. launcher), perform a throttled
                // wider UsageStats verification to determine if a monitored app is active.
                val nowRealtime = SystemClock.elapsedRealtime()
                if (nowRealtime - lastVerificationRealtime >= VERIFICATION_THROTTLE_MS) {
                    lastVerificationRealtime = nowRealtime
                    val verificationStartTime = now - USAGE_STATS_VERIFICATION_WINDOW_MS
                    val stats = usm.queryUsageStats(
                        UsageStatsManager.INTERVAL_DAILY,
                        verificationStartTime,
                        now
                    )
                    if (!stats.isNullOrEmpty()) {
                        val overallMostRecent = stats
                            .filter { it.lastTimeUsed > 0L }
                            .maxByOrNull { it.lastTimeUsed }

                        if (overallMostRecent != null) {
                            val mostRecentPkg = overallMostRecent.packageName
                            val currentStalePkg = currentForegroundPackage
                            val staleLastUsed = stats
                                .filter { it.packageName == currentStalePkg }
                                .maxOfOrNull { it.lastTimeUsed } ?: 0L

                            if (monitoredPackages.contains(mostRecentPkg) && overallMostRecent.lastTimeUsed > staleLastUsed) {
                                currentForegroundPackage = mostRecentPkg
                            } else if (mostRecentPkg != currentStalePkg && overallMostRecent.lastTimeUsed > staleLastUsed) {
                                currentForegroundPackage = mostRecentPkg
                            }
                        }
                    }
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
        lastKnownMonitoredPackages = monitoredPackages
        val activePackage = detectForegroundPackage(monitoredPackages)

        // Ignore self (TimeMirror) as external monitored app
        if (activePackage == selfPackageName || activePackage == null) {
            val nowRealtime = SystemClock.elapsedRealtime()
            // Grace period check for direct task switching
            if (lastMonitoredPackage != null && (nowRealtime - lastMonitoredExitTimestamp) < TRANSITION_GRACE_PERIOD_MS) {
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
            if (lastMonitoredPackage != null && (nowRealtime - lastMonitoredExitTimestamp) < TRANSITION_GRACE_PERIOD_MS) {
                return true
            }
            lastMonitoredPackage = null
            return false
        }
    }

    companion object {
        private const val EVENT_LOOKBACK_WINDOW_MS = 10000L // 10-second lookback window
        private const val USAGE_STATS_VERIFICATION_WINDOW_MS = 60 * 60 * 1000L // 1-hour verification window
        private const val VERIFICATION_THROTTLE_MS = 3000L // 3-second throttle for wider verification
        private const val TRANSITION_GRACE_PERIOD_MS = 800L // 800ms transition grace period
    }
}
