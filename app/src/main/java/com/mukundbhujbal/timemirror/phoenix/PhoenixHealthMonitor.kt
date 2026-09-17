package com.mukundbhujbal.timemirror.phoenix

import android.os.SystemClock

/**
 * Lightweight, in-memory health signal for the TimeAware monitoring loop.
 *
 * Process-local and thread-safe. Records the timestamp of the latest successful
 * orchestrator loop tick using [SystemClock.elapsedRealtime].
 *
 * NOTE: This is NOT screen-time state and is NOT persisted to disk.
 * When the process dies, the heartbeat naturally resets to 0L.
 */
object PhoenixHealthMonitor {

    @Volatile
    private var lastHeartbeatRealtime: Long = 0L

    /**
     * Records the current [SystemClock.elapsedRealtime] as the latest successful
     * monitoring loop heartbeat. Must be invoked only from the active orchestrator tick.
     */
    fun recordHeartbeat() {
        lastHeartbeatRealtime = SystemClock.elapsedRealtime()
    }

    /**
     * Returns the elapsed realtime in milliseconds when the last heartbeat was recorded,
     * or 0L if no heartbeat has been recorded in this process lifecycle.
     */
    fun getLastHeartbeatRealtime(): Long = lastHeartbeatRealtime

    /**
     * Checks if a heartbeat was recorded within the given [thresholdMillis].
     *
     * @param thresholdMillis Maximum allowed age of the heartbeat in milliseconds.
     * @param nowRealtime Current [SystemClock.elapsedRealtime], defaults to current elapsed realtime.
     * @return True if a heartbeat exists and was recorded within [thresholdMillis], false otherwise.
     */
    fun isHealthy(
        thresholdMillis: Long,
        nowRealtime: Long = SystemClock.elapsedRealtime()
    ): Boolean {
        val last = lastHeartbeatRealtime
        if (last <= 0L) return false
        val age = nowRealtime - last
        return age in 0L..thresholdMillis
    }

    /**
     * Resets the in-memory heartbeat timestamp.
     */
    fun reset() {
        lastHeartbeatRealtime = 0L
    }
}
