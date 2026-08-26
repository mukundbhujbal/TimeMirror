package com.mukundbhujbal.timemirror.data

import android.graphics.drawable.Drawable

/**
 * Data model representing an installed application.
 */
data class AppInfo(
    val packageName: String,
    val appName: String,
    val icon: Drawable? = null,
    val isMonitored: Boolean = false
)
