package com.scrollstarva.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator

/**
 * Custom View depicting a circular outer ring with an inner Heart shape
 * that fills with animated green liquid based on daily focus progress.
 */
class FocusHeartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** Fraction of daily focus target completed, between 0.0f and 1.0f. */
    var fillFraction: Float = 0.25f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    private var wavePhase = 0f
    private val animator = ValueAnimator.ofFloat(0f, (2 * Math.PI).toFloat()).apply {
        duration = 1800L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            wavePhase = it.animatedValue as Float
            invalidate()
        }
    }

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.5f)
        color = Color.parseColor("#10B981")
    }

    private val heartBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        color = Color.parseColor("#059669")
    }

    private val emptyHeartPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#E6F4EA")
    }

    private val liquidPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#10B981")
    }

    private val heartPath = Path()
    private val wavePath = Path()

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!animator.isRunning) animator.start()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val size = minOf(w, h)
        if (size <= 0) return

        val centerX = w / 2f
        val centerY = h / 2f
        val ringRadius = (size / 2f) - dp(2f)

        // 1. Draw outer circular ring
        canvas.drawCircle(centerX, centerY, ringRadius, ringPaint)

        // 2. Build Heart Path inside circular bounds
        val heartScale = ringRadius * 0.72f
        heartPath.reset()
        val topY = centerY - (heartScale * 0.35f)
        val bottomY = centerY + (heartScale * 0.75f)
        val heartHeight = bottomY - topY

        heartPath.moveTo(centerX, bottomY)
        // Left curve
        heartPath.cubicTo(
            centerX - (heartScale * 0.85f), centerY + (heartScale * 0.25f),
            centerX - (heartScale * 1.05f), centerY - (heartScale * 0.40f),
            centerX - (heartScale * 0.50f), centerY - (heartScale * 0.65f)
        )
        heartPath.cubicTo(
            centerX - (heartScale * 0.22f), centerY - (heartScale * 0.82f),
            centerX, centerY - (heartScale * 0.38f),
            centerX, centerY - (heartScale * 0.35f)
        )
        // Right curve
        heartPath.cubicTo(
            centerX, centerY - (heartScale * 0.38f),
            centerX + (heartScale * 0.22f), centerY - (heartScale * 0.82f),
            centerX + (heartScale * 0.50f), centerY - (heartScale * 0.65f)
        )
        heartPath.cubicTo(
            centerX + (heartScale * 1.05f), centerY - (heartScale * 0.40f),
            centerX + (heartScale * 0.85f), centerY + (heartScale * 0.25f),
            centerX, bottomY
        )
        heartPath.close()

        // 3. Draw Empty Heart Background
        canvas.drawPath(heartPath, emptyHeartPaint)

        // 4. Draw Liquid Fill with Wave Animation
        canvas.save()
        canvas.clipPath(heartPath)

        val liquidY = bottomY - (heartHeight * fillFraction)
        val waveAmplitude = dp(2f)

        wavePath.reset()
        wavePath.moveTo(0f, h)
        var x = 0f
        while (x <= w) {
            val y = liquidY + (kotlin.math.sin((x * 0.08) + wavePhase).toFloat() * waveAmplitude)
            wavePath.lineTo(x, y)
            x += dp(2f)
        }
        wavePath.lineTo(w, h)
        wavePath.close()

        canvas.drawPath(wavePath, liquidPaint)
        canvas.restore()

        // 5. Draw Heart Border
        canvas.drawPath(heartPath, heartBorderPaint)
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
