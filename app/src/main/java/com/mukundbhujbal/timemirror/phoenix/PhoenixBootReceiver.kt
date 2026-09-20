package com.mukundbhujbal.timemirror.phoenix

import android.app.ForegroundServiceStartNotAllowedException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.mukundbhujbal.timemirror.data.AppPreferences
import com.mukundbhujbal.timemirror.service.TimerOverlayService

/**
 * BroadcastReceiver for handling system boot and package update recovery.
 *
 * Listens for [Intent.ACTION_BOOT_COMPLETED] and [Intent.ACTION_MY_PACKAGE_REPLACED].
 * When monitoring is active, restores the TimerOverlayService.
 *
 * NOTE: Does NOT schedule a Phoenix alarm directly. The normal ACTION_SCREEN_OFF
 * path in TimerOverlayService will schedule the next Phoenix alarm.
 */
class PhoenixBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        val action = intent?.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }

        val appContext = context?.applicationContext ?: return
        val appPreferences = AppPreferences.getInstance(appContext)

        if (!appPreferences.isMonitoringActive()) {
            Log.d(TAG, "Boot: monitoring OFF -> NO-OP")
            return
        }

        Log.d(TAG, "Boot recovery: initiating service launch for action=$action")
        try {
            TimerOverlayService.startService(appContext)
            Log.d(TAG, "Boot recovery: TimerOverlayService.startService() successfully dispatched")
        } catch (e: Throwable) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && e is ForegroundServiceStartNotAllowedException) {
                Log.e(TAG, "Boot recovery: ForegroundServiceStartNotAllowedException launching service", e)
            } else {
                Log.e(TAG, "Boot recovery: failed to launch TimerOverlayService", e)
            }
        }
    }

    companion object {
        private const val TAG = "PhoenixBootReceiver"
    }
}
