package com.mukundbhujbal.timemirror.phoenix

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

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
    private const val TAG = "PhoenixAlarmManager"

    /**
     * Outcome of a watchdog scheduling attempt. EXACT/INEXACT only mean the corresponding
     * AlarmManager call returned successfully; neither guarantees receiver delivery nor that
     * the foreground service will actually start.
     */
    enum class ScheduleResult { EXACT, INEXACT, FAILED }

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
     * Schedules a single one-shot watchdog alarm.
     *
     * Uses an exact alarm when the exact-alarm special access is available; otherwise (or if
     * the exact attempt fails safely) it falls back to an inexact alarm with the SAME
     * PendingIntent identity, so no second watchdog is created. Android may defer inexact
     * alarms, so this is a reliability improvement, not a timing guarantee.
     *
     * @param context Application context.
     * @param triggerAtMillis The time in milliseconds that the alarm should go off.
     * @param type The alarm type, defaults to [AlarmManager.RTC_WAKEUP].
     * @return [ScheduleResult.EXACT], [ScheduleResult.INEXACT] or [ScheduleResult.FAILED].
     */
    fun schedule(
        context: Context,
        triggerAtMillis: Long,
        type: Int = AlarmManager.RTC_WAKEUP
    ): ScheduleResult {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        if (alarmManager == null) {
            Log.e(TAG, "Watchdog scheduling FAILED: AlarmManager unavailable")
            return ScheduleResult.FAILED
        }
        val pendingIntent = createPendingIntent(context)

        // 1) Exact path, unchanged when the special access is available.
        if (canSchedule(context)) {
            try {
                alarmManager.setExactAndAllowWhileIdle(type, triggerAtMillis, pendingIntent)
                Log.d(TAG, "Watchdog armed (EXACT) for $triggerAtMillis")
                return ScheduleResult.EXACT
            } catch (e: SecurityException) {
                Log.w(TAG, "Exact-alarm access lost during scheduling; falling back to inexact", e)
            } catch (e: Exception) {
                Log.w(TAG, "Exact watchdog scheduling failed; falling back to inexact", e)
            }
        } else {
            Log.i(TAG, "Exact-alarm access unavailable; using inexact watchdog")
        }

        // 2) Inexact fallback with the same PendingIntent identity: it replaces any pending
        //    watchdog instead of adding a duplicate. Delivery may be deferred by the system.
        return try {
            alarmManager.setAndAllowWhileIdle(type, triggerAtMillis, pendingIntent)
            Log.d(TAG, "Watchdog armed (INEXACT, delivery not guaranteed) for $triggerAtMillis")
            ScheduleResult.INEXACT
        } catch (e: Exception) {
            Log.e(TAG, "Watchdog scheduling FAILED (exact unavailable and inexact failed)", e)
            ScheduleResult.FAILED
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
            // Safe: swallow the exception to prevent a crash, but keep it observable.
            Log.w(TAG, "Watchdog cancellation failed", e)
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
