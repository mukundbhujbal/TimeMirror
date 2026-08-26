package com.mukundbhujbal.timemirror.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.media.AudioAttributes
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * Manages the floating system watermark overlay using WindowManager.
 * 
 * 5-Minute (300-Second) Continuous Visual Cycle:
 * - Minutes 1–4 (0s–239s):
 *     • 15s Top-Left (Neon Orange #FF9500)
 *     • 15s Top-Right (Neon Sky Blue #00E5FF)
 *     • 15s Bottom-Right (Neon Green #00E676)
 *     • 15s Bottom-Left (Neon Yellow #FFEB3B)
 * - Minute 5 (240s–299s):
 *     • 15s Top-Left (Neon Orange #FF9500)   [240s–254s]
 *     • 15s Top-Right (Neon Sky Blue #00E5FF)[255s–269s]
 *     • 15s Bottom-Right (Neon Green #00E676)[270s–284s]
 *     • 12s Bottom-Left (Neon Yellow #FFEB3B)[285s–296s]
 *     • 3s  Center (Neon Red #FF1744)        [297s–299s]
 * 
 * Configured with FLAG_NOT_TOUCHABLE so all touches pass through to monitored apps.
 */
class OverlayController(private val context: Context) {

    private val windowManager: WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private val mainHandler = Handler(Looper.getMainLooper())

    private var overlayNeonView: NeonOverlayView? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    private var isAttached = false
    private var isVisible = false
    private var isInitializing = false

    private var currentGravity = Gravity.TOP or Gravity.START
    private var currentColor = Color.parseColor("#FF9500")
    private var currentX = 0
    private var currentY = 0
    private var lastVibratedSecond = -1L

    init {
        mainHandler.post {
            initOverlayView(makeVisibleImmediately = false)
        }
    }

    private fun initOverlayView(makeVisibleImmediately: Boolean = false) {
        if (isAttached || isInitializing) return
        if (!Settings.canDrawOverlays(context)) return

        isInitializing = true
        try {
            val density = context.resources.displayMetrics.density
            val marginX = (24 * density).toInt()
            val marginY = (48 * density).toInt()

            currentX = marginX
            currentY = marginY

            val neonView = NeonOverlayView(context).apply {
                text = "00:00:00"
                neonColor = currentColor
                visibility = if (makeVisibleImmediately) View.VISIBLE else View.GONE
            }

            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                flags,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = currentGravity
                x = currentX
                y = currentY
            }

            windowManager.addView(neonView, params)
            overlayNeonView = neonView
            layoutParams = params
            isAttached = true
            isVisible = makeVisibleImmediately
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            isInitializing = false
        }
    }

    /**
     * Updates the time text, 5-minute position cycle, and color cycle.
     */
    fun updateTime(totalSeconds: Long) {
        mainHandler.post {
            val neonView = overlayNeonView ?: return@post
            val params = layoutParams ?: return@post

            // 1. Time Formatting (HH:MM:SS)
            val hours = totalSeconds / 3600
            val minutes = (totalSeconds % 3600) / 60
            val seconds = totalSeconds % 60
            neonView.text = String.format("%02d:%02d:%02d", hours, minutes, seconds)

            // 2. 5-Minute (300-Second) Visual Cycle Calculation
            val cycleSeconds = (totalSeconds % 300).toInt()

            val density = context.resources.displayMetrics.density
            val defaultMarginX = (24 * density).toInt()
            val defaultMarginY = (48 * density).toInt()

            // 3. Position / Gravity & Offset Logic
            val (targetGravity, targetX, targetY) = when {
                // Minutes 1–4 (0s–239s): 4 corners repeating every 15s
                cycleSeconds < 240 -> when ((cycleSeconds % 60) / 15) {
                    0 -> Triple(Gravity.TOP or Gravity.START, defaultMarginX, defaultMarginY)       // Top-Left
                    1 -> Triple(Gravity.TOP or Gravity.END, defaultMarginX, defaultMarginY)         // Top-Right
                    2 -> Triple(Gravity.BOTTOM or Gravity.END, defaultMarginX, defaultMarginY)      // Bottom-Right
                    else -> Triple(Gravity.BOTTOM or Gravity.START, defaultMarginX, defaultMarginY) // Bottom-Left
                }
                // Minute 5 (240s–299s)
                cycleSeconds < 255 -> Triple(Gravity.TOP or Gravity.START, defaultMarginX, defaultMarginY)       // 240–254s (15s) Top-Left
                cycleSeconds < 270 -> Triple(Gravity.TOP or Gravity.END, defaultMarginX, defaultMarginY)         // 255–269s (15s) Top-Right
                cycleSeconds < 285 -> Triple(Gravity.BOTTOM or Gravity.END, defaultMarginX, defaultMarginY)      // 270–284s (15s) Bottom-Right
                cycleSeconds < 297 -> Triple(Gravity.BOTTOM or Gravity.START, defaultMarginX, defaultMarginY)    // 285–296s (12s) Bottom-Left
                else -> Triple(Gravity.CENTER, 0, 0)                                                             // 297–299s (3s)  Center
            }

            // 4. Color Logic (Neon Palette preserving 5-state cycle)
            val targetColor = when {
                // Minutes 1–4
                cycleSeconds < 240 -> when ((cycleSeconds % 60) / 15) {
                    0 -> Color.parseColor("#FF9500") // Neon Orange
                    1 -> Color.parseColor("#00E5FF") // Neon Sky Blue / Cyan
                    2 -> Color.parseColor("#00E676") // Neon Green
                    else -> Color.parseColor("#FFEB3B") // Neon Yellow
                }
                // Minute 5
                cycleSeconds < 255 -> Color.parseColor("#FF9500") // Neon Orange
                cycleSeconds < 270 -> Color.parseColor("#00E5FF") // Neon Sky Blue / Cyan
                cycleSeconds < 285 -> Color.parseColor("#00E676") // Neon Green
                cycleSeconds < 297 -> Color.parseColor("#FFEB3B") // Neon Yellow
                else -> Color.parseColor("#FF1744")               // Final 3 seconds: Neon Red
            }

            // 5. Apply Updates
            var needsLayoutUpdate = false

            if (params.gravity != targetGravity || params.x != targetX || params.y != targetY) {
                params.gravity = targetGravity
                params.x = targetX
                params.y = targetY
                currentGravity = targetGravity
                currentX = targetX
                currentY = targetY
                needsLayoutUpdate = true
            }

            if (currentColor != targetColor) {
                currentColor = targetColor
                neonView.neonColor = targetColor
            }

            if (needsLayoutUpdate && isAttached) {
                try {
                    windowManager.updateViewLayout(neonView, params)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            // 6. 3-Second Countdown Vibration (strictly when in Center position)
            if (targetGravity == Gravity.CENTER && lastVibratedSecond != totalSeconds) {
                lastVibratedSecond = totalSeconds
                triggerCountdownVibration()
            }
        }
    }

    private fun triggerCountdownVibration() {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager =
                    context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }

            if (vibrator != null && vibrator.hasVibrator()) {
                val effect = VibrationEffect.createOneShot(100L, VibrationEffect.DEFAULT_AMPLITUDE)
                when {
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                        val attrs = VibrationAttributes.Builder()
                            .setUsage(VibrationAttributes.USAGE_ALARM)
                            .build()
                        vibrator.vibrate(effect, attrs)
                    }
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.O -> {
                        val audioAttrs = AudioAttributes.Builder()
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .build()
                        @Suppress("DEPRECATION")
                        vibrator.vibrate(effect, audioAttrs)
                    }
                    else -> {
                        @Suppress("DEPRECATION")
                        val audioAttrs = AudioAttributes.Builder()
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .build()
                        @Suppress("DEPRECATION")
                        vibrator.vibrate(100L, audioAttrs)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun show() {
        mainHandler.post {
            if (!isAttached) {
                initOverlayView(makeVisibleImmediately = true)
            } else {
                overlayNeonView?.let {
                    if (it.visibility != View.VISIBLE) {
                        it.visibility = View.VISIBLE
                        isVisible = true
                    }
                }
            }
        }
    }

    fun hide() {
        mainHandler.post {
            overlayNeonView?.let {
                if (it.visibility != View.GONE) {
                    it.visibility = View.GONE
                    isVisible = false
                }
            }
        }
    }

    fun destroy() {
        mainHandler.post {
            overlayNeonView?.let {
                it.stopBreathing()
                if (isAttached) {
                    try {
                        windowManager.removeView(it)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                    isAttached = false
                    isVisible = false
                }
            }
            overlayNeonView = null
            layoutParams = null
        }
    }
}
