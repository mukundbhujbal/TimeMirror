package com.mukundbhujbal.timemirror.phoenix

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Phoenix Exact Alarm infrastructure component.
 *
 * Provides low-level primitives to:
 * 1. Check whether exact alarms can currently be scheduled ([canSchedule]).
 * 2. Schedule a one-shot exact alarm with idle allowance ([schedule]).
 * 3. Cancel the scheduled Phoenix alarm ([cancel]).
 *
 * NOTE: This is infrastructure only. No recovery logic, automatic scheduling,
 * or background receiver is implemented in this step.
 */
object PhoenixAlarmManager {

    const val ACTION_PHOENIX_ALARM = "com.mukundbhujbal.timemirror.action.PHOENIX_ALARM"
    private const val PHOENIX_ALARM_REQUEST_CODE = 2001

    /**
     * Verifies whether the application has permission to schedule exact alarms.
     * On Android 12+ (API 31+), queries [AlarmManager.canScheduleExactAlarms].
     * On earlier versions, exact alarms are permitted by default.
     */
    fun canSchedule(context: Context): Boolean {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                alarmManager.canScheduleExactAlarms()
            } catch (e: Exception) {
                false
            }
        } else {
            true
        }
    }

    /**
     * Schedules a single one-shot exact alarm that fires even while the device is in idle / Doze mode.
     *
     * @param context Application context.
     * @param triggerAtMillis The time in milliseconds that the alarm should go off.
     * @param type The alarm type, defaults to [AlarmManager.RTC_WAKEUP].
     * @return True if the alarm was successfully scheduled, false otherwise.
     */
    fun schedule(
        context: Context,
        triggerAtMillis: Long,
        type: Int = AlarmManager.RTC_WAKEUP
    ): Boolean {
        if (!canSchedule(context)) {
            return false
        }
        return try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return false
            val pendingIntent = createPendingIntent(context)
            alarmManager.setExactAndAllowWhileIdle(type, triggerAtMillis, pendingIntent)
            true
        } catch (e: SecurityException) {
            false
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Cancels any pending Phoenix exact alarm.
     */
    fun cancel(context: Context) {
        try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val pendingIntent = createPendingIntent(context)
            alarmManager.cancel(pendingIntent)
        } catch (e: Exception) {
            // Safe: swallow exception to prevent crash
        }
    }

    private fun createPendingIntent(context: Context): PendingIntent {
        val intent = Intent(ACTION_PHOENIX_ALARM).apply {
            setPackage(context.packageName)
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        return PendingIntent.getBroadcast(context, PHOENIX_ALARM_REQUEST_CODE, intent, flags)
    }
}
