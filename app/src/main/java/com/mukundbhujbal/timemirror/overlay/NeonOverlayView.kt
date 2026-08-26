package com.mukundbhujbal.timemirror.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.TypedValue
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator

/**
 * Custom View rendering the On-Screen Timer in authentic Neon Sign Style:
 * - Ultra-bright, crisp central core (illuminated gas tube look)
 * - Layered ambient soft neon glow around characters
 * - Completely transparent background preserving monitored app visibility
 * - Subtle smooth breathing pulse on the neon glow (readability 100% maintained)
 */
class NeonOverlayView(context: Context) : View(context) {

    private val density = context.resources.displayMetrics.density
    private val textSizeSp = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP,
        21f,
        context.resources.displayMetrics
    )

    var text: String = "00:00:00"
        set(value) {
            if (field != value) {
                field = value
                requestLayout()
                invalidate()
            }
        }

    var neonColor: Int = Color.parseColor("#00E5FF")
        set(value) {
            if (field != value) {
                field = value
                updateGlowPaints()
                invalidate()
            }
        }

    private var breathingFactor: Float = 1.0f
    private var breathingAnimator: ValueAnimator? = null

    // Layer 0: Subtle Dark Separation Halo (for contrast on bright/busy backgrounds)
    private val darkHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textSize = textSizeSp
        color = Color.argb(140, 0, 0, 0)
        setShadowLayer(8f * density, 0f, 0f, Color.argb(160, 0, 0, 0))
    }

    // Layer 1: Wide Ambient Glow
    private val ambientGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textSize = textSizeSp
    }

    // Layer 2: Concentrated Inner Neon Halo
    private val innerHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.8f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textSize = textSizeSp
    }

    // Layer 3: Ultra-bright Central Core
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textSize = textSizeSp
    }

    init {
        // Use software layer to ensure 100% smooth blur mask / shadow layer on all Android devices
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        setBackgroundColor(Color.TRANSPARENT)
        updateGlowPaints()
        setupBreathingAnimator()
    }

    private fun updateGlowPaints() {
        val radiusFactor = breathingFactor.coerceIn(0.7f, 1.3f)

        // Subtle dark separation halo
        darkHaloPaint.color = Color.argb(140, 0, 0, 0)
        darkHaloPaint.setShadowLayer(8f * density * radiusFactor, 0f, 0f, Color.argb(160, 0, 0, 0))

        // Ambient outer glow (soft blur)
        ambientGlowPaint.color = neonColor
        ambientGlowPaint.alpha = (175 * radiusFactor).toInt().coerceIn(0, 255)
        ambientGlowPaint.setShadowLayer(14f * density * radiusFactor, 0f, 0f, neonColor)

        // Inner halo (concentrated neon color)
        innerHaloPaint.color = neonColor
        innerHaloPaint.alpha = 255
        innerHaloPaint.setShadowLayer(6f * density * radiusFactor, 0f, 0f, neonColor)

        // Core text with crisp slight highlight
        corePaint.setShadowLayer(2f * density, 0f, 0f, neonColor)
    }

    private fun setupBreathingAnimator() {
        breathingAnimator = ValueAnimator.ofFloat(0.85f, 1.15f).apply {
            duration = 1800L
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { animation ->
                breathingFactor = animation.animatedValue as Float
                updateGlowPaints()
                invalidate()
            }
        }
    }

    fun startBreathing() {
        if (breathingAnimator?.isStarted != true) {
            breathingAnimator?.start()
        }
    }

    fun stopBreathing() {
        breathingAnimator?.cancel()
        breathingFactor = 1.0f
        updateGlowPaints()
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (visibility == VISIBLE) {
            startBreathing()
        }
    }

    override fun onDetachedFromWindow() {
        stopBreathing()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE) {
            startBreathing()
        } else {
            stopBreathing()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val textWidth = corePaint.measureText(text)
        val fontMetrics = corePaint.fontMetrics
        val textHeight = fontMetrics.descent - fontMetrics.ascent

        // Generous padding to prevent shadow clipping
        val horizontalPadding = (28 * density).toInt()
        val verticalPadding = (14 * density).toInt()

        val desiredWidth = (textWidth + horizontalPadding * 2).toInt()
        val desiredHeight = (textHeight + verticalPadding * 2).toInt()

        setMeasuredDimension(
            resolveSize(desiredWidth, widthMeasureSpec),
            resolveSize(desiredHeight, heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val x = width / 2f
        val y = (height / 2f) - ((corePaint.descent() + corePaint.ascent()) / 2f)

        // 0. Draw subtle dark separation halo (prevents merging on bright/busy backgrounds)
        canvas.drawText(text, x, y, darkHaloPaint)

        // 1. Draw wide ambient neon glow
        canvas.drawText(text, x, y, ambientGlowPaint)

        // 2. Draw concentrated inner halo
        canvas.drawText(text, x, y, innerHaloPaint)

        // 3. Draw crisp illuminated gas tube core (pure bright center)
        canvas.drawText(text, x, y, corePaint)
    }
}
