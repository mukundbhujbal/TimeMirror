package com.mukundbhujbal.timemirror.phoenix

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import com.mukundbhujbal.timemirror.data.AppPreferences
import com.mukundbhujbal.timemirror.service.TimerOverlayService

/**
 * State-aware BroadcastReceiver for Phoenix recovery alarms.
 *
 * Responds strictly to [PhoenixAlarmManager.ACTION_PHOENIX_ALARM].
 * Evaluates monitoring state, screen state, service health, and snapshot validity
 * before dispatching a recovery start of [TimerOverlayService].
 */
class PhoenixReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action != PhoenixAlarmManager.ACTION_PHOENIX_ALARM) return
        val appContext = context?.applicationContext ?: return

        // Step 1 — Check Authoritative Monitoring State
        val appPreferences = AppPreferences.getInstance(appContext)
        if (!appPreferences.isMonitoringActive()) {
            Log.d(TAG, "Phoenix check: monitoring OFF -> DISARMED / NO-OP")
            return
        }

        try {
            // Step 2 — Check Screen State (Quiescent Preservation)
            val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (powerManager?.isInteractive == false) {
                Log.d(TAG, "Phoenix check: monitoring ON + screen OFF -> QUIESCENT / NO-OP")
                return
            }

            // Step 3 — Check Service Loop Health (Screen ON + Monitoring ON)
            val isHealthy = PhoenixHealthMonitor.isHealthy(thresholdMillis = DEFAULT_HEALTH_THRESHOLD_MILLIS)
            if (isHealthy) {
                Log.d(TAG, "Phoenix check: monitoring ON + screen ON + service HEALTHY -> NO-OP")
                return
            }

            // Step 4 — Check Recovery Snapshot Validity Gate
            val snapshot = appPreferences.getRecoverySnapshot()
            if (!snapshot.valid) {
                Log.d(TAG, "Phoenix check: monitoring ON + screen ON + service UNHEALTHY + snapshot INVALID -> NO-OP")
                return
            }

            // Additional Snapshot Sanity Check: Timestamp Validity
            val now = System.currentTimeMillis()
            if (snapshot.screenOffTimestamp <= 0L || snapshot.screenOffTimestamp > now) {
                Log.d(TAG, "Phoenix check: snapshot timestamp invalid (screenOffTimestamp=${snapshot.screenOffTimestamp}, now=$now) -> NO-OP")
                return
            }

            // Step 5 — Dispatch Validated Phoenix Recovery Launch
            Log.d(TAG, "Phoenix recovery: initiating service launch (snapshot elapsed=${snapshot.timerElapsedSec}s)")
            try {
                TimerOverlayService.startService(appContext)
                Log.d(TAG, "Phoenix recovery: TimerOverlayService.startService() successfully dispatched")
            } catch (e: Exception) {
                Log.e(TAG, "Phoenix recovery: failed to launch TimerOverlayService", e)
            }
        } finally {
            if (appPreferences.isMonitoringActive()) {
                val triggerAtMillis =
                    System.currentTimeMillis() + PHOENIX_ALARM_DELAY_MILLIS
                val result = PhoenixAlarmManager.schedule(appContext, triggerAtMillis)
                if (result == PhoenixAlarmManager.ScheduleResult.FAILED) {
                    Log.e(TAG, "Watchdog re-arm failed (exact and inexact unavailable); recovery chain stopped")
                }
            }
        }
    }

    companion object {
        private const val TAG = "PhoenixReceiver"
        const val DEFAULT_HEALTH_THRESHOLD_MILLIS = 3000L
        private const val PHOENIX_ALARM_DELAY_MILLIS = 15 * 60 * 1000L
    }
}
