package com.mukundbhujbal.timemirror.data

/**
 * Recovery Snapshot model representing the state captured at SCREEN_OFF.
 * Used during SCREEN_ON / Phoenix recovery to restore exact state.
 */
data class RecoverySnapshot(
    val monitoringActive: Boolean = false,
    val hideTimerActive: Boolean = false,
    val hideTimerRemainingSec: Long = 0L,
    val timerRunning: Boolean = false,
    val timerElapsedSec: Long = 0L,
    val screenOffTimestamp: Long = 0L,
    val valid: Boolean = false
) {
    companion object {
        val INVALID = RecoverySnapshot(valid = false)
    }
}
