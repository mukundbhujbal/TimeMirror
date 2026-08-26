package com.mukundbhujbal.timemirror.data

/**
 * Represents usage statistics for an individual monitored application.
 */
data class AppUsageStat(
    val packageName: String,
    val usageSeconds: Long
)

/**
 * Daily history data record containing the 7 logical values for a single calendar day.
 * Total Screen Time is strictly derived from (withOnScreenTimerSeconds + withoutOnScreenTimerSeconds)
 * and is NEVER stored separately.
 */
data class DailyHistoryRecord(
    val date: String,                           // ISO format "YYYY-MM-DD"
    val withOnScreenTimerSeconds: Long,        // Seconds tracked with watermark overlay visible
    val withoutOnScreenTimerSeconds: Long,     // Seconds tracked with watermark overlay hidden
    val monitoringStoppedSeconds: Long,        // Seconds master monitoring was deliberately turned OFF
    val topApps: List<AppUsageStat>            // Min 0..3 monitored apps with highest usage today
) {
    /**
     * Derived Total Screen Time (always calculated, never stored in DB).
     */
    val totalScreenTimeSeconds: Long
        get() = withOnScreenTimerSeconds + withoutOnScreenTimerSeconds
}
