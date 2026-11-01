package com.mukundbhujbal.timemirror.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.view.View
import android.widget.RemoteViews
import androidx.core.graphics.drawable.toBitmap
import com.mukundbhujbal.timemirror.MainActivity
import com.mukundbhujbal.timemirror.R
import com.mukundbhujbal.timemirror.data.AppPreferences
import com.mukundbhujbal.timemirror.engine.TimerEngine

/**
 * Shared rendering and refresh helpers for the TimeAware Screen-Time home-screen widget.
 *
 * The widget is display-only: no polling loop, no service and no background work; the selected
 * apps and the timer value come from the existing sources of truth
 * ([AppPreferences.getMonitoredPackages] and [TimerEngine]).
 *
 * [refreshAll] and [refreshScreenTime] are safe to call whenever the selection or timer state
 * changes; both are no-ops when no widget instance exists.
 */
object WidgetRenderer {

    private const val ICON_SIZE_PX = 96

    /** Stable PendingIntent request-code namespace for the widget type. */
    private const val WIDGET_TYPE_SCREEN_TIME = 0

    private val SCREEN_TIME_SLOTS = intArrayOf(
        R.id.widget_st_icon_1,
        R.id.widget_st_icon_2,
        R.id.widget_st_icon_3,
        R.id.widget_st_icon_4
    )

    private data class WidgetApp(
        val packageName: String,
        val label: String,
        val icon: Bitmap?
    )

    // --- Public refresh API (safe to call from the app or the existing service) ---

    /** Refreshes the Screen-Time widget. No-op when no widget instance exists. */
    fun refreshAll(context: Context) {
        refreshScreenTime(context)
    }

    /** Refreshes the Screen-Time widget. */
    fun refreshScreenTime(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, ScreenTimeWidgetProvider::class.java))
        updateScreenTime(context, manager, ids)
    }

    // --- Provider entry points ---

    fun updateScreenTime(context: Context, manager: AppWidgetManager, ids: IntArray) {
        if (ids.isEmpty()) return
        val views = buildScreenTime(context)
        ids.forEach { id -> manager.updateAppWidget(id, views) }
    }

    // --- Rendering ---

    private fun buildScreenTime(context: Context): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_screen_time)
        views.setTextViewText(R.id.widget_st_timer, formatSeconds(todaySeconds(context)))
        bindIcons(
            context = context,
            views = views,
            slots = SCREEN_TIME_SLOTS,
            overflowViewId = R.id.widget_st_overflow,
            apps = loadApps(context),
            widgetType = WIDGET_TYPE_SCREEN_TIME
        )
        return views
    }

    private fun bindIcons(
        context: Context,
        views: RemoteViews,
        slots: IntArray,
        overflowViewId: Int,
        apps: List<WidgetApp>,
        widgetType: Int
    ) {
        slots.forEachIndexed { index, slotId ->
            val app = apps.getOrNull(index)
            if (app?.icon != null) {
                views.setImageViewBitmap(slotId, app.icon)
                // Every icon routes through MainActivity -> Gateway, never directly to the app.
                views.setOnClickPendingIntent(
                    slotId,
                    gatewayPendingIntent(context, app.packageName, widgetType, index)
                )
                views.setViewVisibility(slotId, View.VISIBLE)
                views.setContentDescription(slotId, app.label)
            } else {
                views.setViewVisibility(slotId, View.GONE)
            }
        }

        val overflow = (apps.size - slots.size).coerceAtLeast(0)
        if (overflow > 0) {
            views.setViewVisibility(overflowViewId, View.VISIBLE)
            views.setTextViewText(overflowViewId, "+$overflow")
        } else {
            views.setViewVisibility(overflowViewId, View.GONE)
        }
    }

    /**
     * One immutable PendingIntent per widget slot, carrying the exact target package.
     *
     * The request code is derived from the widget type and the slot index, so it is stable across
     * refreshes (FLAG_UPDATE_CURRENT safely replaces the extras when the selection changes) and
     * unique between slots. The intent only starts [MainActivity]; the Gateway decides how and
     * when the target is launched.
     */
    private fun gatewayPendingIntent(
        context: Context,
        packageName: String,
        widgetType: Int,
        slotIndex: Int
    ): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = MainActivity.ACTION_OPEN_VIA_GATEWAY
            putExtra(MainActivity.EXTRA_GATEWAY_TARGET, packageName)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val requestCode = widgetType * 100 + slotIndex
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    // --- Data ---

    /**
     * Today's cumulative seconds. [AppPreferences] provides the persisted fallback; when the
     * monitoring service is alive, [TimerEngine] (the sole timer authority) holds the live value.
     * A persisted total from a different day is never shown as today's.
     */
    private fun todaySeconds(context: Context): Long {
        val preferences = AppPreferences.getInstance(context)
        val today = AppPreferences.getTodayDateString()
        if (preferences.getLastActiveDate() != today) return 0L
        val persisted = preferences.getTodayWithTimerSeconds() +
                preferences.getTodayWithoutTimerSeconds()
        val live = TimerEngine.getElapsedTodaySeconds()
        return maxOf(persisted, live)
    }

    private fun loadApps(context: Context): List<WidgetApp> {
        val packageManager = context.packageManager
        val monitored = AppPreferences.getInstance(context).getMonitoredPackages()
        return monitored.sorted().map { packageName ->
            val label = try {
                packageManager.getApplicationLabel(
                    packageManager.getApplicationInfo(packageName, 0)
                ).toString()
            } catch (e: Exception) {
                packageName
            }
            val icon: Bitmap? = try {
                packageManager.getApplicationIcon(packageName).toBitmap(ICON_SIZE_PX, ICON_SIZE_PX)
            } catch (e: Exception) {
                null
            }
            WidgetApp(packageName = packageName, label = label, icon = icon)
        }
    }

    private fun formatSeconds(seconds: Long): String {
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60
        return String.format("%02d:%02d:%02d", hours, minutes, secs)
    }
}
