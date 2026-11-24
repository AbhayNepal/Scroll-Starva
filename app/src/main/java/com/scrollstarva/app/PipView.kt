package com.scrollstarva.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Pip: a small round seed-sprite with a two-leaf sprout that doubles as a propeller.
 *
 * The view animates itself every frame (breathing, blinking, glancing around) and exposes a small
 * set of actions — talk, fly, point, wave, cheer, get dizzy — that a director such as
 * [PipCompanion] combines into behaviour. All drawing happens in a 100×100 design space centred
 * on the view, so the character scales cleanly from a 30dp icon to a large hero.
 */
class PipView(context: Context) : View(context) {

    // ---------------------------------------------------------------- public state

    /** Mouth flaps while true (set by the speech bubble's typewriter). */
    var talking = false

    /** Sprout spins as a propeller, feet dangle and the body bobs faster while true. */
    var flying = false

    /** Body lean in degrees; eased toward every frame. */
    var tiltTarget = 0f

    private var sad = false

    // ---------------------------------------------------------------- animation state

    private var time = 0f
    private var lastFrameNanos = 0L

    private var blink = 0f
    private var nextBlinkAt = 2f
    private var blinkStartedAt = -10f
    private var doubleBlink = false

    private var lookX = 0f
    private var lookY = 0f
    private var lookTargetX = 0f
    private var lookTargetY = 0f
    private var lookOverride = false
    private var nextGlanceAt = 3f

    private var mouthOpen = 0f
    private var tilt = 0f
    private var propellerAngle = 0f
    private var propellerSpeed = 0f
    private var flyBlend = 0f

    private var pointAmount = 0f
    private var pointTarget = 0f
    private var pointAngle = 0f

    private var waveUntil = -1f
    private var waveBlend = 0f

    private var cheerStartedAt = -10f
    private var hopStartedAt = -10f
    private var dizzyUntil = -1f
    private var nextIdleActionAt = 9f

    // ---------------------------------------------------------------- paints

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rect = RectF()

    private val bodyLight = Color.parseColor("#FFB8A3")
    private val bodyMid = Color.parseColor("#F7846F")
    private val bodyDark = Color.parseColor("#E4604F")
    private val belly = Color.parseColor("#FFEBDD")
    private val ink = Color.parseColor("#2A1F33")
    private val leafLight = Color.parseColor("#6FD39B")
    private val leafDark = Color.parseColor("#2E9E6A")
    private val cheek = Color.parseColor("#FF8FA3")
    private val gold = Color.parseColor("#F5C451")

    init {
        // Shadow layers on paths need software rendering.
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        bodyPaint.shader = RadialGradient(
            -10f, -8f, 42f,
            intArrayOf(bodyLight, bodyMid, bodyDark),
            floatArrayOf(0f, 0.6f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    // ---------------------------------------------------------------- actions

    /** Jump with happy eyes and a burst of sparkles. */
    fun playCheer() {
        cheerStartedAt = time
        hopStartedAt = time
    }

    fun setSad(isSad: Boolean) {
        sad = isSad
        contentDescription = if (isSad) {
            "Pip looks a little sad because a daily goal was crossed."
        } else {
            "Pip, your focus buddy. Tap to chat."
        }
    }

    /** Look toward a direction in view coordinates (any length; it is normalised). */
    fun lookToward(dx: Float, dy: Float) {
        val len = kotlin.math.sqrt(dx * dx + dy * dy).coerceAtLeast(0.001f)
        lookTargetX = dx / len
        lookTargetY = dy / len
        lookOverride = true
    }

    /** Look straight at the viewer. */
    fun lookAtViewer() {
        lookTargetX = 0f
        lookTargetY = 0.15f
        lookOverride = true
    }

    fun clearLook() {
        lookOverride = false
    }

    /** Extend an arm toward [angleRad] (view coordinates, 0 = right, positive = down). */
    fun pointToward(angleRad: Float) {
        pointAngle = angleRad
        pointTarget = 1f
    }

    fun stopPointing() {
        pointTarget = 0f
    }

    fun wave(seconds: Float = 1.6f) {
        waveUntil = time + seconds
    }

    fun dizzy(seconds: Float = 2.2f) {
        dizzyUntil = time + seconds
    }

    fun hop() {
        hopStartedAt = time
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        lastFrameNanos = 0L
        postInvalidateOnAnimation()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE) {
            lastFrameNanos = 0L
            postInvalidateOnAnimation()
        }
    }

    // ---------------------------------------------------------------- frame update

    private fun step(dt: Float) {
        time += dt
        val ease = { rate: Float -> (dt * rate).coerceAtMost(1f) }

        // Blinking (sometimes a double blink)
        if (time >= nextBlinkAt) {
            blinkStartedAt = time
            doubleBlink = Math.random() < 0.25
            nextBlinkAt = time + 2.2f + (Math.random() * 3.5).toFloat()
        }
        val sinceBlink = time - blinkStartedAt
        blink = when {
            sinceBlink < 0.14f -> sin(sinceBlink / 0.14f * PI)
            doubleBlink && sinceBlink in 0.22f..0.36f -> sin((sinceBlink - 0.22f) / 0.14f * PI)
            else -> 0f
        }

        // Idle glances
        if (!lookOverride && time >= nextGlanceAt) {
            if (Math.random() < 0.35) {
                lookTargetX = 0f
                lookTargetY = 0.1f
            } else {
                lookTargetX = (Math.random() * 2 - 1).toFloat()
                lookTargetY = (Math.random() * 1.2 - 0.6).toFloat()
            }
            nextGlanceAt = time + 1.4f + (Math.random() * 2.6).toFloat()
        }
        lookX += (lookTargetX - lookX) * ease(9f)
        lookY += (lookTargetY - lookY) * ease(9f)

        // Occasional idle flourish when nothing else is going on
        if (time >= nextIdleActionAt) {
            if (!flying && !talking && pointTarget == 0f) {
                when ((Math.random() * 3).toInt()) {
                    0 -> hop()
                    1 -> wave(1.0f)
                    else -> { /* a slow glance is enough */ }
                }
            }
            nextIdleActionAt = time + 8f + (Math.random() * 8).toFloat()
        }

        // Mouth
        val mouthTarget = if (talking) {
            (0.55f + 0.45f * sin(time * 17f) * sin(time * 5.3f + 1f)).coerceIn(0.1f, 1f)
        } else 0f
        mouthOpen += (mouthTarget - mouthOpen) * ease(22f)

        // Flight blend, propeller, tilt
        flyBlend += ((if (flying) 1f else 0f) - flyBlend) * ease(4f)
        val spinTarget = if (flying) 26f else if (time - cheerStartedAt < 0.8f) 14f else 0f
        propellerSpeed += (spinTarget - propellerSpeed) * ease(3f)
        propellerAngle += propellerSpeed * dt
        if (propellerSpeed < 0.5f) {
            // Settle leaves back to a calm resting orientation.
            val rest = Math.round(propellerAngle / PI) * PI
            propellerAngle += (rest - propellerAngle) * ease(4f)
        }
        val dizzyWobble = if (time < dizzyUntil) sin(time * 9f) * 10f else 0f
        tilt += (tiltTarget + dizzyWobble - tilt) * ease(6f)

        // Arms
        pointAmount += (pointTarget - pointAmount) * ease(7f)
        waveBlend += ((if (time < waveUntil) 1f else 0f) - waveBlend) * ease(8f)
    }

    // ---------------------------------------------------------------- drawing

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val now = System.nanoTime()
        val dt = if (lastFrameNanos == 0L) 0.016f else ((now - lastFrameNanos) / 1e9f).coerceIn(0.001f, 0.05f)
        lastFrameNanos = now
        step(dt)

        val unit = minOf(width, height) / 100f
        if (unit <= 0f) return
        canvas.save()
        canvas.translate(width / 2f, height / 2f)
        canvas.scale(unit, unit)

        // Vertical motion: breathing bob, faster hover in flight, hop arcs.
        val bob = sin(time * (2.2f + 3.8f * flyBlend)) * (1.4f + 1.6f * flyBlend)
        val hopT = (time - hopStartedAt) / 0.55f
        val hop = if (hopT in 0f..1f) -sin(hopT * PI) * 11f else 0f
        val landSquash = if (hopT in 0.85f..1.15f) (1f - abs(hopT - 1f) / 0.15f) * 0.08f else 0f
        val breath = sin(time * 2.2f) * 0.022f
        val sy = 1f + breath - landSquash + (if (hopT in 0.1f..0.6f) 0.05f else 0f)
        val sx = 1f - breath + landSquash
        val bodyY = 6f + bob + hop

        drawGroundShadow(canvas, hop)

        canvas.save()
        canvas.rotate(tilt, 0f, bodyY + 10f)

        drawFeet(canvas, bodyY)
        drawArm(canvas, bodyY, left = true)
        drawArm(canvas, bodyY, left = false)

        // Body
        canvas.save()
        canvas.translate(0f, bodyY)
        canvas.scale(sx, sy)
        paint.style = Paint.Style.FILL
        bodyPaint.setShadowLayer(3f, 0f, 2.5f, Color.argb(45, 120, 40, 30))
        rect.set(-27f, -25f, 27f, 25f)
        canvas.drawOval(rect, bodyPaint)
        bodyPaint.clearShadowLayer()
        paint.color = belly
        rect.set(-15f, 3f, 15f, 22f)
        canvas.drawOval(rect, paint)
        // Glossy highlight
        paint.color = Color.argb(110, 255, 255, 255)
        rect.set(-19f, -19f, -7f, -11f)
        canvas.save()
        canvas.rotate(-28f, -13f, -15f)
        canvas.drawOval(rect, paint)
        canvas.restore()
        drawFace(canvas)
        canvas.restore()

        drawSprout(canvas, bodyY - 24f * sy)
        canvas.restore()

        drawCheerSparkles(canvas, bodyY)
        canvas.restore()

        if (isShown) postInvalidateOnAnimation()
    }

    private fun drawGroundShadow(canvas: Canvas, hop: Float) {
        val lift = (flyBlend + (-hop / 11f) * 0.6f).coerceIn(0f, 1f)
        paint.style = Paint.Style.FILL
        paint.color = Color.argb((38 * (1f - lift * 0.8f)).toInt(), 60, 30, 20)
        val rx = 18f * (1f - lift * 0.45f)
        rect.set(-rx, 41f, rx, 46f)
        canvas.drawOval(rect, paint)
    }

    private fun drawFeet(canvas: Canvas, bodyY: Float) {
        paint.style = Paint.Style.FILL
        paint.color = bodyDark
        for (side in intArrayOf(-1, 1)) {
            val dangle = flyBlend * (4f + sin(time * 7f + side) * 2f)
            canvas.save()
            canvas.translate(side * 10f, bodyY + 23f + dangle)
            canvas.rotate(side * flyBlend * 18f)
            rect.set(-6.5f, -3.5f, 6.5f, 3.5f)
            canvas.drawOval(rect, paint)
            canvas.restore()
        }
    }

    private fun drawArm(canvas: Canvas, bodyY: Float, left: Boolean) {
        val side = if (left) -1f else 1f
        val shoulderX = side * 22f
        val shoulderY = bodyY + 6f

        // Resting arm hangs down-out with a gentle sway; flying arms lift outward.
        val sway = sin(time * 2.2f + (if (left) 0f else 1.3f)) * 6f
        var angleDeg = 90f - side * (28f + sway + flyBlend * 35f)
        var length = 11f

        // Pointing uses the arm on the side of the target.
        val pointsRight = cos(pointAngle) >= 0f
        if (pointAmount > 0.01f && (pointsRight != left)) {
            val targetDeg = Math.toDegrees(pointAngle.toDouble()).toFloat()
            angleDeg = lerpAngle(angleDeg, targetDeg, pointAmount)
            length = 11f + 7f * pointAmount
        } else if (waveBlend > 0.01f && !left) {
            val waveDeg = -55f + sin(time * 14f) * 22f
            angleDeg = lerpAngle(angleDeg, waveDeg, waveBlend)
        }

        val rad = Math.toRadians(angleDeg.toDouble())
        val handX = shoulderX + (cos(rad) * length).toFloat()
        val handY = shoulderY + (sin(rad) * length).toFloat()
        strokePaint.color = bodyMid
        strokePaint.strokeWidth = 7f
        canvas.drawLine(shoulderX, shoulderY, handX, handY, strokePaint)
        paint.style = Paint.Style.FILL
        paint.color = bodyLight
        canvas.drawCircle(handX, handY, 3.6f, paint)
    }

    private fun drawFace(canvas: Canvas) {
        val dizzy = time < dizzyUntil
        val happy = time - cheerStartedAt < 1.1f
        val eyeDX = lookX * 2.6f
        val eyeDY = lookY * 2.2f - (if (sad) -1.5f else 0f)

        // Cheeks
        paint.style = Paint.Style.FILL
        paint.color = cheek
        paint.alpha = 120
        rect.set(-21f, 3f, -13f, 8f); canvas.drawOval(rect, paint)
        rect.set(13f, 3f, 21f, 8f); canvas.drawOval(rect, paint)
        paint.alpha = 255

        for (side in intArrayOf(-1, 1)) {
            val ex = side * 9f + eyeDX
            val ey = -5f + eyeDY
            when {
                dizzy -> drawSwirlEye(canvas, ex, ey, side)
                happy -> {
                    strokePaint.color = ink
                    strokePaint.strokeWidth = 2.6f
                    rect.set(ex - 4.5f, ey - 2f, ex + 4.5f, ey + 5f)
                    canvas.drawArc(rect, 200f, 140f, false, strokePaint)
                }
                else -> {
                    val open = (1f - blink).coerceAtLeast(0.08f)
                    paint.color = ink
                    rect.set(ex - 4.6f, ey - 6f * open, ex + 4.6f, ey + 6f * open)
                    canvas.drawOval(rect, paint)
                    if (open > 0.4f) {
                        paint.color = Color.WHITE
                        canvas.drawCircle(ex - 1.5f + lookX * 0.6f, ey - 2.4f * open, 1.8f, paint)
                        canvas.drawCircle(ex + 1.6f, ey + 2f * open, 0.85f, paint)
                    }
                }
            }
            if (sad && !dizzy) {
                strokePaint.color = ink
                strokePaint.strokeWidth = 1.8f
                canvas.drawLine(ex - side * 5f, ey - 9f, ex + side * 2f, ey - 11f, strokePaint)
            }
        }

        // Mouth
        val mx = eyeDX * 0.5f
        val my = 6.5f + eyeDY * 0.3f
        strokePaint.color = ink
        strokePaint.strokeWidth = 2f
        when {
            mouthOpen > 0.05f -> {
                paint.color = Color.parseColor("#6B2737")
                val h = 1.5f + mouthOpen * 4.5f
                rect.set(mx - 3.6f, my - 1f, mx + 3.6f, my - 1f + h)
                canvas.drawOval(rect, paint)
                paint.color = Color.parseColor("#FF8C8C")
                rect.set(mx - 2f, my - 1f + h * 0.55f, mx + 2f, my - 1f + h)
                canvas.drawOval(rect, paint)
            }
            dizzy -> {
                path.reset()
                path.moveTo(mx - 4f, my + 1f)
                path.quadTo(mx - 2f, my - 1f, mx, my + 1f)
                path.quadTo(mx + 2f, my + 3f, mx + 4f, my + 1f)
                canvas.drawPath(path, strokePaint)
            }
            sad -> {
                rect.set(mx - 3.5f, my + 1f, mx + 3.5f, my + 6f)
                canvas.drawArc(rect, 200f, 140f, false, strokePaint)
            }
            happy -> {
                paint.color = Color.parseColor("#6B2737")
                rect.set(mx - 4.5f, my - 3f, mx + 4.5f, my + 5f)
                canvas.drawArc(rect, 0f, 180f, true, paint)
            }
            else -> {
                rect.set(mx - 3.5f, my - 3.5f, mx + 3.5f, my + 2.5f)
                canvas.drawArc(rect, 20f, 140f, false, strokePaint)
            }
        }
    }

    private fun drawSwirlEye(canvas: Canvas, x: Float, y: Float, side: Int) {
        strokePaint.color = ink
        strokePaint.strokeWidth = 1.6f
        path.reset()
        val turns = 2.2f
        val steps = 28
        val spin = time * 8f * side
        for (i in 0..steps) {
            val f = i / steps.toFloat()
            val a = spin + f * turns * 2f * PI
            val r = 0.5f + f * 4.6f
            val px = x + cos(a) * r
            val py = y + sin(a) * r
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        canvas.drawPath(path, strokePaint)
    }

    private fun drawSprout(canvas: Canvas, topY: Float) {
        val sway = sin(time * 1.8f) * 7f * (1f - flyBlend)
        canvas.save()
        canvas.rotate(sway, 0f, topY)
        // Stem
        strokePaint.color = leafDark
        strokePaint.strokeWidth = 2.8f
        val stemTop = topY - 9f
        canvas.drawLine(0f, topY + 1f, 0f, stemTop, strokePaint)

        // Spinning blur disc while flying fast
        if (propellerSpeed > 6f) {
            paint.style = Paint.Style.FILL
            paint.color = leafLight
            paint.alpha = ((propellerSpeed / 26f) * 70).toInt().coerceIn(0, 70)
            rect.set(-19f, stemTop - 3.5f, 19f, stemTop + 2.5f)
            canvas.drawOval(rect, paint)
            paint.alpha = 255
        }

        // Two leaves; horizontal scale fakes rotation around the stem.
        val spread = cos(propellerAngle)
        for (side in intArrayOf(-1, 1)) {
            canvas.save()
            canvas.translate(0f, stemTop)
            canvas.scale(spread, 1f)
            canvas.rotate(side * -18f)
            path.reset()
            path.moveTo(0f, 0f)
            path.cubicTo(side * 5f, -8f, side * 14f, -8f, side * 17f, -2f)
            path.cubicTo(side * 13f, 3f, side * 5f, 3f, 0f, 0f)
            path.close()
            paint.style = Paint.Style.FILL
            paint.color = if ((side > 0) == (spread > 0)) leafLight else leafDark
            canvas.drawPath(path, paint)
            strokePaint.color = leafDark
            strokePaint.strokeWidth = 1f
            canvas.drawLine(0f, 0f, side * 12f, -2.5f, strokePaint)
            canvas.restore()
        }
        // Little bud where the leaves meet
        paint.color = leafDark
        canvas.drawCircle(0f, stemTop, 2f, paint)
        canvas.restore()
    }

    private fun drawCheerSparkles(canvas: Canvas, bodyY: Float) {
        val t = (time - cheerStartedAt) / 0.9f
        if (t !in 0f..1f) return
        paint.style = Paint.Style.FILL
        paint.color = gold
        paint.alpha = (255 * (1f - t)).toInt()
        for (i in 0 until 6) {
            val a = (i / 6f) * 2f * PI - PI / 2f + 0.3f
            val d = 30f + 14f * t
            drawStar(canvas, cos(a) * d, bodyY + sin(a) * d, 3.6f * (1f - t * 0.4f))
        }
        paint.alpha = 255
    }

    private fun drawStar(canvas: Canvas, x: Float, y: Float, r: Float) {
        val inner = r * 0.3f
        path.reset()
        path.moveTo(x, y - r)
        path.quadTo(x + inner, y - inner, x + r, y)
        path.quadTo(x + inner, y + inner, x, y + r)
        path.quadTo(x - inner, y + inner, x - r, y)
        path.quadTo(x - inner, y - inner, x, y - r)
        path.close()
        canvas.drawPath(path, paint)
    }

    private fun lerpAngle(from: Float, to: Float, f: Float): Float {
        var diff = (to - from) % 360f
        if (diff > 180f) diff -= 360f
        if (diff < -180f) diff += 360f
        return from + diff * f
    }

    /** Angle from this view's centre to a point, in view coordinates. */
    fun angleTo(dx: Float, dy: Float): Float = atan2(dy, dx)

    private companion object {
        const val PI = Math.PI.toFloat()
    }
}
