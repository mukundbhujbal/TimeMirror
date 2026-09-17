package com.mukundbhujbal.timemirror.phoenix

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Foundation BroadcastReceiver for Phoenix recovery alarms.
 *
 * Responds strictly to [PhoenixAlarmManager.ACTION_PHOENIX_ALARM].
 *
 * NOTE: Foundation only. No recovery logic, service restart, or
 * state inspection is performed in this step.
 */
class PhoenixReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action == PhoenixAlarmManager.ACTION_PHOENIX_ALARM) {
            Log.d(TAG, "Phoenix alarm broadcast received")
        }
    }

    companion object {
        private const val TAG = "PhoenixReceiver"
    }
}
