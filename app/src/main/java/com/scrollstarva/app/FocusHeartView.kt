package com.scrollstarva.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Custom View depicting a sleek outer ring and an inner Heart shape filled with
 * liquid that features continuous sinusoidal sloshing waves, rising fluid bubbles,
 * and an initial scattering fountain nozzle spray when the heart is empty.
 */
class FocusHeartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** Fraction of daily focus target completed, between 0.0f and 1.0f. */
    var fillFraction: Float = 0.0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            postInvalidateOnAnimation()
        }

    /** Whether to render rising fluid ingestion stream bubbles (enabled on focus timer screen). */
    var showBubbles: Boolean = false
        set(value) {
            field = value
            postInvalidateOnAnimation()
        }

    private var primaryWavePhase = 0f
    private var secondaryWavePhase = 0f
    private var lastTimeNanos = 0L

    private val outerRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(3f)
        color = Color.parseColor("#10B981")
    }

    private val heartBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.5f)
        color = Color.parseColor("#059669")
    }

    private val emptyHeartPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#ECFDF5")
    }

    private val backLiquidPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#34D399")
        alpha = 130
    }

    private val frontLiquidPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#10B981")
    }

    private val bubbleBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = Color.argb(180, 255, 255, 255)
    }

    private val bubbleFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(80, 255, 255, 255)
    }

    private val sprayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#10B981")
    }

    private val heartPath = Path()
    private val wavePath = Path()
    private val backWavePath = Path()

    // 1. Rising Bubbles Data
    private data class Bubble(
        var relX: Float,
        var progress: Float,
        var radiusDp: Float,
        var speed: Float,
        var phase: Float
    )

    private val bubbles = listOf(
        Bubble(0.0f, 0.05f, 1.1f, 1.15f, 0.0f),
        Bubble(0.0f, 0.20f, 0.9f, 1.35f, 0.8f),
        Bubble(0.0f, 0.40f, 1.3f, 1.05f, 1.6f),
        Bubble(0.0f, 0.60f, 1.0f, 1.25f, 2.4f),
        Bubble(0.0f, 0.80f, 1.2f, 1.10f, 3.2f),
        Bubble(0.0f, 0.95f, 0.8f, 1.40f, 4.0f)
    )

    // 2. Empty Heart Spray Nozzle Particles Data
    private data class SprayParticle(
        var x: Float = 0f,
        var y: Float = 0f,
        var vx: Float = 0f,
        var vy: Float = 0f,
        var life: Float = 0f,
        var maxLife: Float = 1f,
        var sizeDp: Float = 2f
    )

    private val sprayParticles = Array(12) { SprayParticle() }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        lastTimeNanos = System.nanoTime()
        postInvalidateOnAnimation()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE) {
            lastTimeNanos = System.nanoTime()
            postInvalidateOnAnimation()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val size = minOf(w, h)
        if (size <= 0) return

        var deltaSec = 0.016f
        val nowNanos = System.nanoTime()
        if (lastTimeNanos > 0L) {
            val realDelta = (nowNanos - lastTimeNanos) / 1_000_000_000f
            if (realDelta in 0.001f..0.15f) {
                deltaSec = realDelta
            }
        }
        lastTimeNanos = nowNanos

        primaryWavePhase += deltaSec * 3.8f
        secondaryWavePhase -= deltaSec * 6.2f

        val centerX = w / 2f
        val centerY = h / 2f
        val outerRadius = (size / 2f) - dp(8f)

        // 1. Static outer circle ring
        canvas.drawCircle(centerX, centerY, outerRadius, outerRingPaint)

        // 2. Build Heart Path inside bounds
        val heartScale = outerRadius * 0.68f
        heartPath.reset()
        val topY = centerY - (heartScale * 0.35f)
        val bottomY = centerY + (heartScale * 0.75f)
        val heartHeight = bottomY - topY

        heartPath.moveTo(centerX, bottomY)
        // Left lobe
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
        // Right lobe
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

        // 3. Draw Heart Base Container
        canvas.drawPath(heartPath, emptyHeartPaint)

        // 4. Clip to Heart boundary for all liquid animations
        canvas.save()
        canvas.clipPath(heartPath)

        val liquidY = bottomY - (heartHeight * fillFraction)

        // Dynamically scale wave amplitude & wavelength proportional to heart view size
        val baseAmplitude = (size * 0.038f).coerceIn(dp(1.8f), dp(4.5f))
        val wavelength = (w * 0.52f).coerceAtLeast(dp(16f))
        val frequency = (2 * Math.PI / wavelength).toFloat()

        // --- Back Fluid Wave (Deeper layer moving in reverse phase) ---
        backWavePath.reset()
        backWavePath.moveTo(0f, h)
        var x = 0f
        while (x <= w) {
            val distFromCenter = abs(x - centerX) / (w / 2f)
            val wallReflectionFactor = 1f + (distFromCenter * 0.8f)

            val y = (liquidY + (sin((x * frequency) + secondaryWavePhase) * (baseAmplitude * 0.75f) * wallReflectionFactor)).toFloat()
            backWavePath.lineTo(x, y)
            x += dp(2f)
        }
        backWavePath.lineTo(w, h)
        backWavePath.close()
        canvas.drawPath(backWavePath, backLiquidPaint)

        // --- Front Fluid Wave (Main continuous wiggling wave hitting walls) ---
        wavePath.reset()
        wavePath.moveTo(0f, h)
        x = 0f
        while (x <= w) {
            val distFromCenter = abs(x - centerX) / (w / 2f)
            val wallBounce = 1f + (distFromCenter * distFromCenter * 1.1f)

            val primaryWave = sin((x * frequency) + primaryWavePhase)
            val wallWiggle = cos((x * frequency * 2.2f) - (primaryWavePhase * 1.5f)) * 0.35f

            val y = (liquidY + ((primaryWave + wallWiggle) * baseAmplitude * wallBounce)).toFloat()
            wavePath.lineTo(x, y)
            x += dp(2f)
        }
        wavePath.lineTo(w, h)
        wavePath.close()
        canvas.drawPath(wavePath, frontLiquidPaint)

        // 5. Draw Straight Ingestion Stream Bubbles (Only on focus timer screen when showBubbles is true)
        if (showBubbles && fillFraction >= 0.04f) {
            for (b in bubbles) {
                b.progress += deltaSec * b.speed
                if (b.progress >= 1.0f) {
                    b.progress = 0f
                }
                val currentY = bottomY - (heartHeight * fillFraction * b.progress)
                val streamX = centerX + (sin((b.progress * 8f) + b.phase) * dp(1.2f))
                val r = dp(b.radiusDp)

                canvas.drawCircle(streamX, currentY, r, bubbleFillPaint)
                canvas.drawCircle(streamX, currentY, r, bubbleBorderPaint)
            }
        }

        // 6. Draw Empty Heart Fountain Nozzle Spray Particles (Only on focus timer screen when showBubbles is true and when fill is low/empty)
        if (showBubbles && fillFraction < 0.25f) {
            val nozzleY = bottomY - dp(2f)
            for (p in sprayParticles) {
                p.life -= deltaSec
                if (p.life <= 0f) {
                    p.maxLife = 0.4f + (Math.random().toFloat() * 0.4f)
                    p.life = p.maxLife
                    p.x = centerX + ((Math.random().toFloat() - 0.5f) * dp(4f))
                    p.y = nozzleY
                    p.vx = (Math.random().toFloat() - 0.5f) * dp(45f)
                    p.vy = -dp(70f + (Math.random().toFloat() * 60f))
                    p.sizeDp = 1.4f + (Math.random().toFloat() * 1.6f)
                }

                p.x += p.vx * deltaSec
                p.y += p.vy * deltaSec
                p.vy += dp(90f) * deltaSec // Gravity pull

                val alpha = ((p.life / p.maxLife) * 230).toInt().coerceIn(0, 255)
                sprayPaint.alpha = alpha
                canvas.drawCircle(p.x, p.y, dp(p.sizeDp), sprayPaint)
            }
        }

        canvas.restore()

        // 7. Heart Contour Stroke
        canvas.drawPath(heartPath, heartBorderPaint)

        if (isShown) {
            postInvalidateOnAnimation()
        }
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
