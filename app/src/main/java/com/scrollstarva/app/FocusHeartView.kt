package com.scrollstarva.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Region
import android.graphics.RegionIterator
import android.graphics.Shader
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

/**
 * A glass heart floating in a soft halo. A progress arc around it tracks the daily focus target,
 * and the heart itself holds liquid at the same fill level, with natural layered waves,
 * a surface shine, rising ingestion bubbles and a fountain spray when nearly empty.
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

    /** Seconds of animation time; drives every animated component. */
    private var waveTime = 0f
    /** Fill level actually drawn; eases toward [fillFraction] so level changes glide. Negative = not yet set. */
    private var displayedFraction = -1f
    private var lastTimeNanos = 0L

    // ---- Geometry (recomputed in onSizeChanged) ----
    private var centerX = 0f
    private var centerY = 0f
    private var ringRadius = 0f
    private var ringWidth = 0f
    private var heartScale = 0f
    private var tipY = 0f
    private val heartPath = Path()
    private val heartBounds = RectF()
    private val reflectionPath = Path()
    private val arcRect = RectF()
    private val shadowRect = RectF()

    // ---- Paints ----
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#D1FAE5")
    }

    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val fullRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private var fullRingShader: SweepGradient? = null
    private val fullRingMatrix = Matrix()

    private val beadPaint =Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }

    private val beadGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#10B981")
    }

    private val orbitDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#6EE7B7")
    }

    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private val glassPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private val heartBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
    }

    private val reflectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.argb(170, 255, 255, 255)
    }

    private val glintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(200, 255, 255, 255)
    }

    private val backLiquidPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#5EEAD4")
        alpha = 120
    }

    private val frontLiquidPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private val surfaceHighlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.argb(130, 255, 255, 255)
    }

    private val sparklePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
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

    private val wavePath = Path()
    private val backWavePath = Path()
    private val highlightPath = Path()
    private val sparklePath = Path()

    private var rowWidths = FloatArray(0)
    private var areaTableTop = Int.MIN_VALUE
    private var areaTableBottom = Int.MIN_VALUE
    private var liquidShaderTop = Float.NaN

    // ---- Fountain simulation (focus timer screen only) ----
    // A nozzle at the heart's tip pushes a jet upward. While the water above the nozzle is shallow,
    // the jet breaks the surface and sprays droplets that arc back into the pool. As the pool deepens,
    // the water absorbs the jet's energy: the spray shrinks, becomes a surface swell, and finally
    // only bubbles rising through the liquid show that something is still flowing in.

    /** Air bubble rising through the liquid from the nozzle. */
    private class Bubble {
        var alive = false
        var x = 0f
        var y = 0f
        var vy = 0f
        var radius = 0f
        var baseRadius = 0f
        var drift = 0f
        var wobblePhase = 0f
        var wobbleFreq = 0f
        var startY = 0f
    }

    /** Droplet thrown into the air by the jet. */
    private class Droplet {
        var alive = false
        var x = 0f
        var y = 0f
        var vx = 0f
        var vy = 0f
        var radius = 0f
        var age = 0f
    }

    /** Expanding ring where a bubble pops or a droplet lands on the surface. */
    private class SurfaceRing {
        var alive = false
        var x = 0f
        var y = 0f
        var age = 0f
        var maxRadius = 0f
    }

    private val bubblePool = Array(28) { Bubble() }
    private val dropletPool = Array(70) { Droplet() }
    private val ringPool = Array(16) { SurfaceRing() }
    private var bubbleSpawnDebt = 0f
    private var dropletSpawnDebt = 0f
    /** Smoothed jet power (0 = fully damped by water, 1 = free jet). Negative = not yet initialised. */
    private var jetPower = -1f

    private val popRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
    }

    // Wave state shared between the liquid drawing and the fountain (set every frame in drawLiquid).
    private var waveLiquidY = 0f
    private var waveAmplitude = 0f
    private var waveSizeAmplitude = 0f
    private var waveK1 = 0f
    private var waveK2 = 0f
    private var waveK3 = 0f
    private var waveTilt = 0f
    private var waveMound = 0f
    private var waveMoundWidth = 1f

    /** Positions (angle in radians, distance factor, phase) of the full-heart twinkles. */
    private val sparkles = arrayOf(
        floatArrayOf(-2.35f, 1.02f, 0.0f),
        floatArrayOf(-0.75f, 1.05f, 1.7f),
        floatArrayOf(0.35f, 1.00f, 3.1f),
        floatArrayOf(2.55f, 0.98f, 4.4f)
    )

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

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val size = minOf(w, h).toFloat()
        if (size <= 0f) return
        centerX = w / 2f
        centerY = h / 2f

        ringWidth = (size * 0.045f).coerceIn(dp(2f), dp(6f))
        ringRadius = size / 2f - ringWidth * 1.6f
        arcRect.set(centerX - ringRadius, centerY - ringRadius, centerX + ringRadius, centerY + ringRadius)
        trackPaint.strokeWidth = ringWidth
        progressPaint.strokeWidth = ringWidth

        // Sweep gradient starting at 12 o'clock.
        val sweep = SweepGradient(
            centerX, centerY,
            intArrayOf(
                Color.parseColor("#5EEAD4"),
                Color.parseColor("#34D399"),
                Color.parseColor("#10B981"),
                Color.parseColor("#047857"),
                Color.parseColor("#5EEAD4")
            ),
            floatArrayOf(0f, 0.3f, 0.6f, 0.95f, 1f)
        )
        sweep.setLocalMatrix(Matrix().apply { setRotate(-90f, centerX, centerY) })
        progressPaint.shader = sweep

        // Cyclic (same colour at both ends) so the completed ring has no seam.
        fullRingShader = SweepGradient(
            centerX, centerY,
            intArrayOf(
                Color.parseColor("#10B981"),
                Color.parseColor("#6EE7B7"),
                Color.parseColor("#10B981"),
                Color.parseColor("#047857"),
                Color.parseColor("#10B981")
            ),
            floatArrayOf(0f, 0.2f, 0.45f, 0.75f, 1f)
        )
        fullRingPaint.shader = fullRingShader
        fullRingPaint.strokeWidth = ringWidth

        haloPaint.shader = RadialGradient(
            centerX, centerY, ringRadius,
            intArrayOf(Color.parseColor("#ECFDF5"), Color.parseColor("#D1FAE5"), Color.argb(0, 209, 250, 229)),
            floatArrayOf(0f, 0.65f, 1f),
            Shader.TileMode.CLAMP
        )

        // Plump heart, slightly lifted so its shadow fits underneath.
        heartScale = ringRadius * 0.50f
        val cy = centerY - heartScale * 0.02f
        buildHeart(centerX, cy, heartScale)
        heartPath.computeBounds(heartBounds, true)
        tipY = cy + heartScale * 0.78f

        glassPaint.shader = RadialGradient(
            heartBounds.centerX() - heartScale * 0.3f, heartBounds.top + heartBounds.height() * 0.3f,
            heartScale * 1.6f,
            Color.WHITE, Color.parseColor("#E6FBF3"),
            Shader.TileMode.CLAMP
        )
        heartBorderPaint.strokeWidth = (size * 0.028f).coerceIn(dp(1.4f), dp(3.2f))
        heartBorderPaint.shader = LinearGradient(
            0f, heartBounds.top, 0f, heartBounds.bottom,
            Color.parseColor("#34D399"), Color.parseColor("#047857"),
            Shader.TileMode.CLAMP
        )

        shadowRect.set(
            centerX - heartScale * 0.7f, tipY + heartScale * 0.04f,
            centerX + heartScale * 0.7f, tipY + heartScale * 0.22f
        )
        shadowPaint.shader = RadialGradient(
            shadowRect.centerX(), shadowRect.centerY(), shadowRect.width() / 2f,
            Color.argb(70, 4, 120, 87), Color.argb(0, 4, 120, 87),
            Shader.TileMode.CLAMP
        )

        // Glass reflection: a curved streak hugging the inside of the left lobe.
        reflectionPath.reset()
        reflectionPath.moveTo(centerX - heartScale * 0.74f, cy - heartScale * 0.08f)
        reflectionPath.cubicTo(
            centerX - heartScale * 0.80f, cy - heartScale * 0.40f,
            centerX - heartScale * 0.66f, cy - heartScale * 0.62f,
            centerX - heartScale * 0.42f, cy - heartScale * 0.66f
        )
        reflectionPaint.strokeWidth = (heartScale * 0.09f).coerceIn(dp(1f), dp(4f))

        liquidShaderTop = Float.NaN
        areaTableTop = Int.MIN_VALUE
    }

    private fun buildHeart(cx: Float, cy: Float, s: Float) {
        heartPath.reset()
        heartPath.moveTo(cx, cy + s * 0.78f)
        // Left half
        heartPath.cubicTo(cx - s * 0.14f, cy + s * 0.64f, cx - s * 0.96f, cy + s * 0.20f, cx - s * 0.96f, cy - s * 0.26f)
        heartPath.cubicTo(cx - s * 0.96f, cy - s * 0.62f, cx - s * 0.66f, cy - s * 0.82f, cx - s * 0.42f, cy - s * 0.82f)
        heartPath.cubicTo(cx - s * 0.20f, cy - s * 0.82f, cx - s * 0.05f, cy - s * 0.70f, cx, cy - s * 0.52f)
        // Right half (mirror)
        heartPath.cubicTo(cx + s * 0.05f, cy - s * 0.70f, cx + s * 0.20f, cy - s * 0.82f, cx + s * 0.42f, cy - s * 0.82f)
        heartPath.cubicTo(cx + s * 0.66f, cy - s * 0.82f, cx + s * 0.96f, cy - s * 0.62f, cx + s * 0.96f, cy - s * 0.26f)
        heartPath.cubicTo(cx + s * 0.96f, cy + s * 0.20f, cx + s * 0.14f, cy + s * 0.64f, cx, cy + s * 0.78f)
        heartPath.close()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val size = minOf(w, h)
        if (size <= 0 || heartScale <= 0f) return

        var deltaSec = 0.016f
        val nowNanos = System.nanoTime()
        if (lastTimeNanos > 0L) {
            val realDelta = (nowNanos - lastTimeNanos) / 1_000_000_000f
            if (realDelta in 0.001f..0.15f) {
                deltaSec = realDelta
            }
        }
        lastTimeNanos = nowNanos

        waveTime += deltaSec
        displayedFraction = if (displayedFraction < 0f) {
            fillFraction
        } else {
            displayedFraction + (fillFraction - displayedFraction) * (deltaSec * 2.5f).coerceAtMost(1f)
        }
        val fraction = displayedFraction
        val t = waveTime
        val detailed = size >= dp(64f)

        // 1. Breathing halo
        val breath = 1f + 0.03f * sin(t * 1.2f)
        canvas.drawCircle(centerX, centerY, ringRadius * breath, haloPaint)

        // 2. Track + progress arc with a glowing bead at its head
        canvas.drawCircle(centerX, centerY, ringRadius, trackPaint)
        if (fraction > 0.003f) {
            val sweepAngle = 360f * fraction
            if (fraction >= 0.995f) {
                // Goal reached: seamless ring with a slowly travelling shimmer, no head bead.
                fullRingMatrix.setRotate(t * 40f, centerX, centerY)
                fullRingShader?.setLocalMatrix(fullRingMatrix)
                canvas.drawCircle(centerX, centerY, ringRadius, fullRingPaint)
            } else {
                canvas.drawArc(arcRect, -90f, sweepAngle, false, progressPaint)
                drawBead(canvas, sweepAngle, t)
            }
        }

        // 3. Slowly orbiting dots inside the ring (only where there is room for detail)
        if (detailed) {
            val orbitRadius = ringRadius - ringWidth * 2.4f
            val dots = 28
            val rotation = t * 0.18f
            for (i in 0 until dots) {
                val a = rotation + (2f * PI_F * i / dots)
                val twinkle = 0.5f + 0.5f * sin(t * 1.5f + i * 0.9f)
                orbitDotPaint.alpha = (40 + 90 * twinkle).toInt()
                canvas.drawCircle(
                    centerX + orbitRadius * cos(a),
                    centerY + orbitRadius * sin(a),
                    ringWidth * (0.14f + 0.1f * twinkle),
                    orbitDotPaint
                )
            }
        }

        // 4. Soft shadow under the heart (gently pulses with the halo)
        canvas.save()
        canvas.scale(breath, 1f, shadowRect.centerX(), shadowRect.centerY())
        canvas.drawOval(shadowRect, shadowPaint)
        canvas.restore()

        // 5. Frosted glass body
        canvas.drawPath(heartPath, glassPaint)

        // 6. Liquid, clipped to the heart
        canvas.save()
        canvas.clipPath(heartPath)
        prepareWave(fraction, t)
        // Jet power depends on the water depth and decides the surface swell, so it goes in between.
        if (showBubbles) {
            updateJetPower(deltaSec)
        } else {
            waveMound = 0f
        }
        drawLiquid(canvas, fraction, h)
        if (showBubbles) drawFountain(canvas, deltaSec, t)
        canvas.restore()

        // 7. Glass rim and reflection
        canvas.drawPath(heartPath, heartBorderPaint)
        canvas.drawPath(reflectionPath, reflectionPaint)
        canvas.drawCircle(
            centerX - heartScale * 0.30f, centerY - heartScale * 0.62f,
            reflectionPaint.strokeWidth * 0.55f, glintPaint
        )

        // 8. Twinkles around a full heart
        if (fraction >= 0.995f) {
            for (s in sparkles) {
                val pulse = (sin(t * 2.2f + s[2]) * 0.5f + 0.5f)
                val dist = heartScale * 1.12f * s[1]
                val sx = centerX + dist * cos(s[0])
                val sy = centerY + dist * sin(s[0])
                sparklePaint.alpha = (60 + 195 * pulse).toInt()
                drawSparkle(canvas, sx, sy, heartScale * (0.07f + 0.07f * pulse))
            }
        }

        if (isShown) {
            postInvalidateOnAnimation()
        }
    }

    /** Computes this frame's resting level and wave parameters. */
    private fun prepareWave(fraction: Float, t: Float) {
        val heartWidth = heartBounds.width()
        val heartHeight = tipY - heartBounds.top

        // Wave height scales with the heart, shrinks in the narrow tip at low fill, and "breathes" slowly.
        waveSizeAmplitude = (heartHeight * 0.055f).coerceIn(dp(1.2f), dp(5f))
        val tipDamping = 0.3f + 0.7f * smoothstep(0f, 0.4f, fraction)
        val breathing = 0.85f + 0.15f * sin(t * 0.7f)
        waveAmplitude = waveSizeAmplitude * tipDamping * breathing

        // Even when full, keep the surface just under the lobe tops so a wavy line stays visible.
        val topMargin = waveSizeAmplitude * 2.2f + heartBorderPaint.strokeWidth
        // Level by volume, not height: X% of the goal fills X% of the heart's area.
        waveLiquidY = levelForAreaFraction(fraction, heartBounds.top + topMargin)

        waveK1 = (2f * PI_F) / (heartWidth * 1.15f)
        waveK2 = (2f * PI_F) / (heartWidth * 0.55f)
        waveK3 = (2f * PI_F) / (heartWidth * 0.28f)
        waveTilt = sin(t * 0.9f) * 0.55f + sin(t * 0.37f + 1.1f) * 0.25f
        waveMoundWidth = heartWidth * 0.11f
    }

    /** Height of the liquid surface at [x] (smaller y = higher), including the fountain swell. */
    private fun surfaceY(x: Float, phaseOffset: Float = 0f, levelOffset: Float = 0f): Float {
        val t = waveTime
        val rel = (x - centerX) / (heartBounds.width() / 2f)
        val swell = sin(waveK1 * x - t * 1.6f + phaseOffset) * 0.55f
        val wave = sin(waveK2 * x + t * 2.3f + 1.3f + phaseOffset * 1.7f) * 0.30f
        val ripple = sin(waveK3 * x - t * 3.4f + 2.1f + phaseOffset * 0.6f) * 0.15f
        var y = waveLiquidY + levelOffset + waveAmplitude * (swell + wave + ripple + waveTilt * rel)
        if (waveMound > 0f) {
            val d = (x - centerX) / waveMoundWidth
            y -= waveMound * kotlin.math.exp(-d * d)
        }
        return y
    }

    /** Draws the layered liquid at the level computed by [prepareWave]. */
    private fun drawLiquid(canvas: Canvas, fraction: Float, h: Float) {
        if (fraction <= 0.001f) return
        val liquidY = waveLiquidY
        val amplitude = waveAmplitude
        val sizeAmplitude = waveSizeAmplitude

        val startX = heartBounds.left - dp(2f)
        val endX = heartBounds.right + dp(2f)
        val step = dp(1.5f)

        // Back layer: slightly higher, out of phase, translucent (gives depth)
        backWavePath.reset()
        backWavePath.moveTo(startX, h)
        var x = startX
        while (x <= endX) {
            backWavePath.lineTo(x, surfaceY(x, 2.4f, -amplitude * 0.35f))
            x += step
        }
        backWavePath.lineTo(endX, surfaceY(endX, 2.4f, -amplitude * 0.35f))
        backWavePath.lineTo(endX, h)
        backWavePath.close()
        canvas.drawPath(backWavePath, backLiquidPaint)

        // Front layer with a shine along its crest
        wavePath.reset()
        highlightPath.reset()
        wavePath.moveTo(startX, h)
        x = startX
        var first = true
        while (x <= endX) {
            val y = surfaceY(x, 0f, 0f)
            wavePath.lineTo(x, y)
            if (first) highlightPath.moveTo(x, y) else highlightPath.lineTo(x, y)
            first = false
            x += step
        }
        wavePath.lineTo(endX, surfaceY(endX, 0f, 0f))
        wavePath.lineTo(endX, h)
        wavePath.close()

        // Lighter just under the surface, deeper toward the tip.
        if (liquidShaderTop != liquidY) {
            frontLiquidPaint.shader = LinearGradient(
                0f, liquidY - amplitude, 0f, tipY,
                intArrayOf(Color.parseColor("#6EE7B7"), Color.parseColor("#10B981"), Color.parseColor("#047857")),
                floatArrayOf(0f, 0.45f, 1f),
                Shader.TileMode.CLAMP
            )
            liquidShaderTop = liquidY
        }
        canvas.drawPath(wavePath, frontLiquidPaint)

        if (fraction > 0.02f) {
            surfaceHighlightPaint.strokeWidth = (sizeAmplitude * 0.35f).coerceIn(dp(0.8f), dp(1.6f))
            canvas.drawPath(highlightPath, surfaceHighlightPaint)
        }
    }

    private val nozzleY: Float get() = tipY - dp(2f)

    /**
     * Water above the nozzle soaks up the jet. Power falls from 1 (no water) to 0 once the pool is
     * deeper than [PIERCE_DEPTH_RATIO] of the heart height; past that the jet can no longer break the
     * surface. A small swell over the nozzle lingers a little longer as the last sign of upwelling.
     */
    private fun updateJetPower(deltaSec: Float) {
        val heartHeight = tipY - heartBounds.top
        val depth = (nozzleY - waveLiquidY).coerceAtLeast(0f)
        val pierceDepth = heartHeight * PIERCE_DEPTH_RATIO
        val target = 1f - smoothstep(0f, pierceDepth, depth)
        jetPower = if (jetPower < 0f) target else jetPower + (target - jetPower) * (deltaSec * 3f).coerceAtMost(1f)

        // Swell is largest when the jet is just barely held under, and fades as the pool deepens.
        val underwater = depth > dp(1f)
        val upwelling = if (underwater) {
            val held = 1f - jetPower
            val fadeDeep = 1f - smoothstep(pierceDepth, pierceDepth * 2.2f, depth)
            (0.35f + 0.65f * jetPower) * (0.4f + 0.6f * held) * (0.4f + 0.6f * fadeDeep)
        } else 0f
        val flicker = 0.85f + 0.15f * sin(waveTime * 9f) + 0.08f * sin(waveTime * 23f)
        waveMound = waveSizeAmplitude * 1.6f * upwelling * flicker
    }

    private fun drawFountain(canvas: Canvas, deltaSec: Float, t: Float) {
        val heartHeight = tipY - heartBounds.top
        val gravity = heartHeight * 5.5f
        val nozzle = nozzleY
        val hasWater = waveLiquidY < nozzle - dp(1f)
        val surfaceAtNozzle = if (hasWater) surfaceY(centerX) else nozzle

        // --- Bubbles: carried by the jet through the water, then buoyancy alone ---
        if (hasWater) {
            bubbleSpawnDebt += deltaSec * (5f + 18f * jetPower)
            while (bubbleSpawnDebt >= 1f) {
                bubbleSpawnDebt -= 1f
                bubblePool.firstOrNull { !it.alive }?.let { b ->
                    b.alive = true
                    b.x = centerX + (rand() - 0.5f) * dp(2f)
                    b.y = nozzle
                    b.startY = nozzle
                    b.baseRadius = dp(0.7f + rand() * 1.0f) * (heartHeight / dp(110f)).coerceIn(0.6f, 1.3f)
                    b.radius = b.baseRadius
                    b.vy = -(heartHeight * (0.35f + 0.2f * rand()) + heartHeight * 1.6f * jetPower)
                    b.drift = (rand() - 0.5f) * heartHeight * (0.12f + 0.25f * (1f - jetPower))
                    b.wobblePhase = rand() * 6.28f
                    b.wobbleFreq = 5f + rand() * 4f
                }
            }
        }
        val buoyantSpeed = -heartHeight * 0.45f
        for (b in bubblePool) {
            if (!b.alive) continue
            // Jet momentum decays toward the gentle terminal rise speed.
            b.vy += (buoyantSpeed - b.vy) * (deltaSec * 2.2f).coerceAtMost(1f)
            b.y += b.vy * deltaSec
            val risen = ((b.startY - b.y) / heartHeight).coerceIn(0f, 1f)
            b.x += b.drift * deltaSec * (0.3f + risen)
            val wobble = sin(t * b.wobbleFreq + b.wobblePhase) * dp(0.9f) * risen
            // Bubbles expand slightly as pressure drops near the surface.
            b.radius = b.baseRadius * (1f + 0.45f * risen)
            val bx = b.x + wobble
            val surface = surfaceY(bx)
            if (b.y - b.radius <= surface || !hasWater) {
                b.alive = false
                if (hasWater) spawnRing(bx, surface, b.radius * 3.2f)
                continue
            }
            canvas.drawCircle(bx, b.y, b.radius, bubbleFillPaint)
            canvas.drawCircle(bx, b.y, b.radius, bubbleBorderPaint)
        }

        // --- Spray: only while the jet still has the power to break the surface ---
        if (jetPower > 0.03f) {
            dropletSpawnDebt += deltaSec * 75f * jetPower
            // Launch speed for a jet that would reach ~85% of the heart height with no water.
            val freeSpeed = kotlin.math.sqrt(2f * gravity * heartHeight * 0.85f)
            while (dropletSpawnDebt >= 1f) {
                dropletSpawnDebt -= 1f
                dropletPool.firstOrNull { !it.alive }?.let { d ->
                    d.alive = true
                    d.age = 0f
                    d.x = centerX + (rand() - 0.5f) * dp(1.5f)
                    d.y = surfaceAtNozzle
                    val speed = freeSpeed * kotlin.math.sqrt(jetPower) * (0.82f + 0.22f * rand())
                    val spread = (rand() - 0.5f) * (0.25f + 0.3f * (1f - jetPower))
                    d.vx = speed * spread
                    d.vy = -speed
                    d.radius = dp(0.8f + rand() * 1.1f) * (heartHeight / dp(110f)).coerceIn(0.6f, 1.3f)
                }
            }
        } else {
            dropletSpawnDebt = 0f
        }
        for (d in dropletPool) {
            if (!d.alive) continue
            d.age += deltaSec
            d.vy += gravity * deltaSec
            d.x += d.vx * deltaSec
            d.y += d.vy * deltaSec
            // A droplet ends when it falls back into the pool (or to the nozzle when empty).
            val floor = if (hasWater) surfaceY(d.x) else nozzle
            if (d.vy > 0f && d.y >= floor) {
                d.alive = false
                if (hasWater && rand() < 0.35f) spawnRing(d.x, floor, d.radius * 2.5f)
                continue
            }
            if (d.y > tipY + dp(4f) || d.age > 3f) {
                d.alive = false
                continue
            }
            sprayPaint.alpha = (if (d.vy < 0f) 235 else 200)
            canvas.drawCircle(d.x, d.y, d.radius, sprayPaint)
        }

        // --- Pop / splash rings on the surface ---
        for (r in ringPool) {
            if (!r.alive) continue
            r.age += deltaSec
            val life = r.age / 0.45f
            if (life >= 1f) {
                r.alive = false
                continue
            }
            popRingPaint.alpha = ((1f - life) * 170).toInt()
            popRingPaint.strokeWidth = dp(0.8f)
            canvas.drawOval(
                r.x - r.maxRadius * life, r.y - r.maxRadius * life * 0.35f,
                r.x + r.maxRadius * life, r.y + r.maxRadius * life * 0.35f,
                popRingPaint
            )
        }
    }

    private fun spawnRing(x: Float, y: Float, maxRadius: Float) {
        ringPool.firstOrNull { !it.alive }?.let { r ->
            r.alive = true
            r.x = x
            r.y = y
            r.age = 0f
            r.maxRadius = maxRadius
        }
    }

    private fun rand(): Float = Math.random().toFloat()

    /** Glowing bead marking the head of the progress arc. */
    private fun drawBead(canvas: Canvas, sweepAngle: Float, t: Float) {
        val headAngle = Math.toRadians((-90f + sweepAngle).toDouble()).toFloat()
        val bx = centerX + ringRadius * cos(headAngle)
        val by = centerY + ringRadius * sin(headAngle)
        beadGlowPaint.alpha = (60 + 40 * sin(t * 2.4f)).toInt()
        canvas.drawCircle(bx, by, ringWidth * 1.25f, beadGlowPaint)
        canvas.drawCircle(bx, by, ringWidth * 0.38f, beadPaint)
    }

    /** Four-point star with concave sides. */
    private fun drawSparkle(canvas: Canvas, x: Float, y: Float, r: Float) {
        val inner = r * 0.22f
        sparklePath.reset()
        sparklePath.moveTo(x, y - r)
        sparklePath.quadTo(x + inner, y - inner, x + r, y)
        sparklePath.quadTo(x + inner, y + inner, x, y + r)
        sparklePath.quadTo(x - inner, y + inner, x - r, y)
        sparklePath.quadTo(x - inner, y - inner, x, y - r)
        sparklePath.close()
        canvas.drawPath(sparklePath, sparklePaint)
    }

    /**
     * Returns the y at which the liquid surface encloses [fraction] of the heart area lying below [topLimitY].
     * Row widths of the heart are rasterised once per size and cached.
     */
    private fun levelForAreaFraction(fraction: Float, topLimitY: Float): Float {
        val top = heartBounds.top.toInt()
        val bottom = kotlin.math.ceil(heartBounds.bottom).toInt()
        if (top != areaTableTop || bottom != areaTableBottom) {
            val rows = (bottom - top).coerceAtLeast(1)
            val widths = FloatArray(rows)
            val region = Region()
            region.setPath(
                heartPath,
                Region(heartBounds.left.toInt() - 1, top, kotlin.math.ceil(heartBounds.right).toInt() + 1, bottom)
            )
            val iterator = RegionIterator(region)
            val rect = Rect()
            while (iterator.next(rect)) {
                for (y in rect.top until rect.bottom) {
                    val row = y - top
                    if (row in 0 until rows) widths[row] += rect.width().toFloat()
                }
            }
            rowWidths = widths
            areaTableTop = top
            areaTableBottom = bottom
        }
        val widths = rowWidths
        val limitRow = (topLimitY.toInt() - top).coerceIn(0, widths.size - 1)
        var total = 0f
        for (row in widths.size - 1 downTo limitRow) total += widths[row]
        if (total <= 0f || fraction <= 0f) return heartBounds.bottom
        val target = total * fraction.coerceAtMost(1f)
        var accumulated = 0f
        for (row in widths.size - 1 downTo limitRow) {
            val rowWidth = widths[row]
            if (accumulated + rowWidth >= target && rowWidth > 0f) {
                // Interpolate within the row for smooth, non-stepping motion.
                val within = (target - accumulated) / rowWidth
                return top + row + 1 - within
            }
            accumulated += rowWidth
        }
        return topLimitY
    }

    private fun smoothstep(edge0: Float, edge1: Float, value: Float): Float {
        val s = ((value - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return s * s * (3f - 2f * s)
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private companion object {
        const val PI_F = Math.PI.toFloat()
        /** Water depth above the nozzle (as a share of heart height) at which the jet stops breaking the surface. */
        const val PIERCE_DEPTH_RATIO = 0.32f
    }
}
