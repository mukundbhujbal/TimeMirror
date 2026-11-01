package com.mukundbhujbal.timemirror.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context

/**
 * Home-screen widget that shows today's cumulative screen time and the monitored app icons.
 *
 * Display-only in Stage 1; click routing through Gateway is a later stage.
 */
class ScreenTimeWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        WidgetRenderer.updateScreenTime(context, appWidgetManager, appWidgetIds)
    }
}
