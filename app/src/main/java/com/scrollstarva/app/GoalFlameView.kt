package com.scrollstarva.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The daily-goals flame. While both goals are on track it burns: three layered tongues of fire
 * (outer, middle, core) that flicker slowly and out of step, inside a soft breathing glow, over a
 * small ember bed. When a limit has been crossed it rests: a dim ember with a thin wisp of smoke,
 * which reads as "paused, can relight" rather than "burnt out".
 *
 * Drawn in a 120×140 design space centred on the view. Motion is deliberately slow and smooth so
 * the card stays calm to look at.
 */
class GoalFlameView(context: Context, private val burning: Boolean) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rect = RectF()
    private var startedAt = 0L
    private var attached = false

    // Fire colours (outer → core); the same in light and dark mode, fire is fire.
    private val outerTip = Color.parseColor("#FFB25C")
    private val outerBase = Color.parseColor("#EE5A45")
    private val midTip = Color.parseColor("#FFD27A")
    private val midBase = Color.parseColor("#FF8A47")
    private val coreTip = Color.parseColor("#FFF8E1")
    private val coreBase = Color.parseColor("#FFDC8A")
    private val ember = Color.parseColor("#E8573F")
    private val emberHot = Color.parseColor("#FFB25C")

    init {
        contentDescription = if (burning) {
            "A live animated flame showing both daily goals are on track."
        } else {
            "A resting ember with a wisp of smoke because a daily goal was crossed."
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attached = true
        startedAt = SystemClock.uptimeMillis()
        invalidate()
    }

    override fun onDetachedFromWindow() {
        attached = false
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val scale = minOf(width / 120f, height / 140f)
        if (scale <= 0f) return
        val t = (SystemClock.uptimeMillis() - startedAt) / 1000f
        canvas.save()
        canvas.translate(width / 2f, height / 2f)
        canvas.scale(scale, scale)
        if (burning) drawFire(canvas, t) else drawResting(canvas, t)
        canvas.restore()
        if (attached) postInvalidateOnAnimation()
    }

    // ---------------------------------------------------------------- burning

    private fun drawFire(canvas: Canvas, t: Float) {
        val breathe = .5f + .5f * sin(t * 2f * PI.toFloat() / 2.6f)

        // Soft glow behind the flame.
        val glowRadius = 54f + breathe * 6f
        paint.color = Color.WHITE
        paint.shader = RadialGradient(
            0f, 14f, glowRadius,
            intArrayOf(alpha(emberHot, if (Ui.isDark) 120 else 95), alpha(ember, 40), alpha(ember, 0)),
            floatArrayOf(0f, .55f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(0f, 14f, glowRadius, paint)
        paint.shader = null

        drawBed(canvas, t, lit = true)

        // Three tongues, each with its own phase so they never move in lockstep.
        drawTongue(canvas, t, phase = 0f, halfWidth = 34f, tipY = -42f, baseY = 48f, tip = outerTip, base = outerBase)
        drawTongue(canvas, t, phase = 1.7f, halfWidth = 23f, tipY = -18f, baseY = 48f, tip = midTip, base = midBase)
        drawTongue(canvas, t, phase = 3.1f, halfWidth = 12f, tipY = 8f, baseY = 47f, tip = coreTip, base = coreBase)

        // A few slow sparks drifting up.
        for (i in 0 until 3) {
            val p = ((t / 2.4f) + i / 3f) % 1f
            val x = sin((p * 2.2f + i * 1.3f) * PI.toFloat()) * (10f + i * 5f)
            val y = 10f - p * 78f
            paint.color = alpha(midTip, ((1f - p) * 220f).toInt())
            canvas.drawCircle(x, y, 1.8f * (1f - p * .5f), paint)
        }
    }

    /**
     * One tongue of flame: a teardrop whose tip sways more than its base, with gentle ripples
     * along the edges. All motion comes from slow, mixed sine waves.
     */
    private fun drawTongue(
        canvas: Canvas, t: Float, phase: Float,
        halfWidth: Float, tipY: Float, baseY: Float, tip: Int, base: Int
    ) {
        val twoPi = 2f * PI.toFloat()
        val sway = 4.5f * sin(t * twoPi * .45f + phase) + 2f * sin(t * twoPi * 1.1f + phase * 1.7f)
        val lift = 4f * (.5f + .5f * sin(t * twoPi * .8f + phase * 2.3f))
        val top = tipY - lift
        val height = baseY - top
        val steps = 26

        path.reset()
        // Right edge from base to tip, then left edge back down.
        for (side in intArrayOf(1, -1)) {
            for (step in 0..steps) {
                val i = if (side == 1) step else steps - step
                val u = i / steps.toFloat()                       // 0 at base, 1 at tip
                val profile = (1f - u).pow(.78f) *
                    sqrt(sin(PI.toFloat() * (u * .82f + .18f)).coerceAtLeast(0f))
                val ripple = 1.6f * sin(t * twoPi * 1.25f + u * 7f + phase + side) * u
                val x = sway * u.pow(1.6f) + side * (halfWidth * profile + ripple)
                val y = baseY - height * u
                if (step == 0 && side == 1) path.moveTo(x, y) else path.lineTo(x, y)
            }
        }
        // Rounded bottom, ending exactly where the right edge started.
        val baseHalf = halfWidth * sqrt(sin(PI.toFloat() * .18f))
        path.quadTo(0f, baseY + halfWidth * .55f, baseHalf, baseY)
        path.close()

        paint.color = Color.WHITE
        paint.shader = LinearGradient(0f, top, 0f, baseY + halfWidth * .4f, tip, base, Shader.TileMode.CLAMP)
        canvas.drawPath(path, paint)
        paint.shader = null
    }

    // ---------------------------------------------------------------- resting

    private fun drawResting(canvas: Canvas, t: Float) {
        val pulse = .5f + .5f * sin(t * 2f * PI.toFloat() / 3.2f)

        // A faint warm glow, much smaller than when burning.
        paint.color = Color.WHITE
        paint.shader = RadialGradient(
            0f, 46f, 34f,
            intArrayOf(alpha(ember, (30 + pulse * 35).toInt()), alpha(ember, 0)),
            null,
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(0f, 46f, 34f, paint)
        paint.shader = null

        drawSmoke(canvas, t)
        drawBed(canvas, t, lit = false, pulse = pulse)
    }

    private fun drawSmoke(canvas: Canvas, t: Float) {
        val twoPi = 2f * PI.toFloat()
        val smoke = if (Ui.isDark) Color.rgb(150, 146, 158) else Color.rgb(150, 145, 156)
        for (wisp in 0 until 2) {
            val p = ((t / 3.4f) + wisp * .5f) % 1f          // each wisp rises and fades in turn
            path.reset()
            val steps = 30
            for (i in 0..steps) {
                val u = i / steps.toFloat()
                val y = 44f - u * (62f + p * 18f)
                val x = (wisp - .5f) * 6f + sin((u * 1.1f - t * .3f + wisp * .9f) * twoPi) * (1f + u * 5f)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            paint.style = Paint.Style.STROKE
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeWidth = 2.6f + p * 1.6f
            paint.color = Color.WHITE
            val strength = (sin(p * PI.toFloat()) * (if (Ui.isDark) 120f else 105f)).toInt()
            paint.shader = LinearGradient(
                0f, 38f, 0f, -50f,
                intArrayOf(alpha(smoke, 0), alpha(smoke, strength), alpha(smoke, 0)),
                floatArrayOf(0f, .35f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawPath(path, paint)
            paint.shader = null
            paint.style = Paint.Style.FILL
        }
    }

    // ---------------------------------------------------------------- base

    /** Small ember bed with a soft shadow under it; hot spots glow when [lit]. */
    private fun drawBed(canvas: Canvas, t: Float, lit: Boolean, pulse: Float = 1f) {
        paint.style = Paint.Style.FILL
        paint.color = alpha(Color.BLACK, if (Ui.isDark) 70 else 22)
        rect.set(-30f, 55f, 30f, 63f)
        canvas.drawOval(rect, paint)

        // Three overlapping coals, the middle one a little higher.
        val coals = arrayOf(
            floatArrayOf(-13f, 53f, 11f, 4.6f),
            floatArrayOf(13f, 53.5f, 10f, 4.2f),
            floatArrayOf(0f, 51.5f, 12f, 5.2f)
        )
        val glow = if (lit) 1f else .35f + .45f * pulse
        coals.forEachIndexed { i, (cx, cy, rx, ry) ->
            rect.set(cx - rx, cy - ry, cx + rx, cy + ry)
            paint.color = Color.WHITE
            paint.shader = LinearGradient(
                0f, cy - ry, 0f, cy + ry,
                // Charcoal, warmed from above when the fire is lit.
                if (lit) intArrayOf(Color.parseColor("#6A3A30"), Color.parseColor("#2B2326"))
                else intArrayOf(Color.parseColor("#4A4246"), Color.parseColor("#26222A")),
                null,
                Shader.TileMode.CLAMP
            )
            canvas.drawOval(rect, paint)
            paint.shader = null
            // Glowing seam along the top of each coal.
            val flicker = .6f + .4f * sin(t * 2f * PI.toFloat() * (.7f + i * .23f) + i * 2f)
            paint.color = alpha(emberHot, (glow * flicker * 210f).toInt().coerceIn(0, 255))
            rect.set(cx - rx * .6f, cy - ry * .5f, cx + rx * .6f, cy + ry * .05f)
            canvas.drawOval(rect, paint)
            // A thin bright crack inside the seam.
            paint.color = alpha(Color.parseColor("#FFE2A8"), (glow * flicker * 200f).toInt().coerceIn(0, 255))
            rect.set(cx - rx * .32f, cy - ry * .32f, cx + rx * .32f, cy - ry * .08f)
            canvas.drawOval(rect, paint)
        }
    }

    private fun alpha(color: Int, a: Int): Int =
        Color.argb(a.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))
}
