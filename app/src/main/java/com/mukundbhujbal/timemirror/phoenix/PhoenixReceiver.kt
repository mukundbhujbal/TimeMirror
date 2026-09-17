package com.mukundbhujbal.timemirror.phoenix

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import com.mukundbhujbal.timemirror.data.AppPreferences

/**
 * State-aware BroadcastReceiver for Phoenix recovery alarms.
 *
 * Responds strictly to [PhoenixAlarmManager.ACTION_PHOENIX_ALARM].
 * Evaluates monitoring state, screen state, and service health.
 *
 * NOTE: Diagnostic evaluation only in this step.
 * Actual recovery execution is deferred to subsequent Phoenix steps.
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
        } else {
            Log.d(TAG, "Phoenix check: monitoring ON + screen ON + service UNHEALTHY -> RECOVERY NEEDED (simulated / no-op)")
        }
    }

    companion object {
        private const val TAG = "PhoenixReceiver"
        const val DEFAULT_HEALTH_THRESHOLD_MILLIS = 3000L
    }
}
