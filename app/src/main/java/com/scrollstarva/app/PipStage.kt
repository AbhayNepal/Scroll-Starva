package com.scrollstarva.app

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/** A button inside Pip's speech bubble. */
data class PipAction(val label: String, val primary: Boolean = false, val onClick: () -> Unit)

/**
 * The reusable part of Pip: the character in an overlay [FrameLayout], parked over [homeAnchor]
 * when at rest, able to fly anywhere in the overlay, point at views, and talk in a speech bubble.
 * Screens add their own behaviour on top (see [PipCompanion] for the dashboard).
 */
class PipStage(
    private val context: Context,
    private val overlay: FrameLayout,
    private val homeAnchor: View,
    pipSizeDp: Int = 76
) {
    enum class Placement {
        /** Bubble under Pip, tail up. */
        BELOW,
        /** Bubble over Pip, tail down. */
        ABOVE,
        /** Bubble to Pip's right, tail left. */
        RIGHT,
        /** Full-width bubble docked to the top of the overlay; Pip hangs under it. */
        DOCK_TOP,
        /** Full-width bubble docked to the bottom of the overlay; Pip sits on it. */
        DOCK_BOTTOM
    }

    val pip = PipView(context)
    val pipSize = dp(pipSizeDp)
    var onPipTapped: (() -> Unit)? = null

    /** Space kept free at the bottom of the overlay (e.g. a pinned button) when docking bubbles. */
    var bottomInset = 0

    private val handler = Handler(Looper.getMainLooper())
    private val bubble = SpeechBubble(context)
    private var flight: ValueAnimator? = null
    private var typewriter: Runnable? = null
    private var autoHide: Runnable? = null
    private var onBubbleHidden: (() -> Unit)? = null
    private var fullText = ""
    private var released = false

    /** True while Pip is parked over its home anchor. */
    var isHome = true
        private set
    val isFlying: Boolean get() = flight != null
    val isTalking: Boolean get() = bubble.visibility == View.VISIBLE

    init {
        overlay.addView(pip, FrameLayout.LayoutParams(pipSize, pipSize, Gravity.TOP or Gravity.START))
        overlay.addView(bubble, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.START
        ))
        bubble.visibility = View.GONE
        pip.setSad(false)
        pip.setOnClickListener {
            if (typewriter != null) finishTyping() else onPipTapped?.invoke()
        }
        homeAnchor.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (isHome && flight == null) snapHome()
        }
        overlay.post { snapHome() }
    }

    // ================================================================ speech

    /**
     * Shows a speech bubble next to Pip's current position and types [text] into it.
     * [autoHideMs]: null = based on length, 0 = stay until dismissed or an action is tapped.
     */
    fun say(
        text: String,
        title: String? = null,
        badge: String? = null,
        actions: List<PipAction> = emptyList(),
        placement: Placement = Placement.BELOW,
        autoHideMs: Long? = null,
        onHidden: (() -> Unit)? = null
    ) {
        if (released) return
        prepareBubble(text, title, badge, actions, placement, onHidden)
        revealBubble(autoHideMs)
    }

    /**
     * Tour-style explanation: docks a bubble to the top or bottom of the screen (whichever keeps
     * [target] visible), flies Pip to the bubble's edge, points at [target], then talks.
     */
    fun guide(
        target: View?,
        text: String,
        title: String? = null,
        badge: String? = null,
        actions: List<PipAction> = emptyList(),
        dock: Placement? = null
    ) {
        if (released) return
        val targetCenter = target?.let { centerOf(it) }
        val dockTop = when (dock) {
            Placement.DOCK_TOP -> true
            Placement.DOCK_BOTTOM -> false
            else -> targetCenter != null && targetCenter.y > overlay.height / 2f
        }
        val placement = if (dockTop) Placement.DOCK_TOP else Placement.DOCK_BOTTOM
        hideBubble(immediately = true)
        prepareBubble(text, title, badge, actions, placement, null)
        bubble.visibility = View.INVISIBLE

        val bubbleTop = bubble.translationY
        val bubbleBottom = bubbleTop + bubble.layoutParams.height
        val perchX = overlay.width - dp(16) - pipSize * 0.55f
        val perchY = if (dockTop) bubbleBottom + pipSize * 0.40f else bubbleTop - pipSize * 0.36f
        val arrive = {
            bubble.setTail(if (dockTop) Tail.BOTTOM else Tail.TOP, perchX - bubble.translationX)
            if (targetCenter != null) {
                pip.pointToward(pip.angleTo(targetCenter.x - perchX, targetCenter.y - perchY))
                pip.lookToward(targetCenter.x - perchX, targetCenter.y - perchY)
            }
            handler.postDelayed({
                if (released) return@postDelayed
                pip.lookAtViewer()
                bubble.visibility = View.GONE
                revealBubble(autoHideMs = 0L, typingDelayMs = 14L)
            }, 380)
        }
        val here = centerOf(pip)
        if (abs(here.x - perchX) < dp(6) && abs(here.y - perchY) < dp(6) && !isHome) {
            pip.hop()
            arrive()
        } else {
            flyTo(PointF(perchX, perchY), 1100L) { arrive() }
        }
    }

    private fun prepareBubble(
        text: String,
        title: String?,
        badge: String?,
        actions: List<PipAction>,
        placement: Placement,
        onHidden: (() -> Unit)?
    ) {
        cancelBubbleTimers()
        onBubbleHidden = onHidden
        fullText = text
        bubble.setHeader(badge, title)
        bubble.setActions(actions.map { action ->
            PipAction(action.label, action.primary) {
                onBubbleHidden = null
                hideBubble(immediately = true)
                action.onClick()
            }
        })
        bubble.text.text = text
        bubble.contentDescription = listOfNotNull(title, text).joinToString(". ")
        layoutBubble(placement)
        bubble.text.text = ""
        bubble.setOnClickListener {
            if (typewriter != null) finishTyping() else if (actions.isEmpty()) hideBubble()
        }
    }

    private fun revealBubble(autoHideMs: Long?, typingDelayMs: Long = 22L) {
        if (bubble.visibility != View.VISIBLE) {
            bubble.visibility = View.VISIBLE
            bubble.alpha = 0f
            bubble.scaleX = 0.7f
            bubble.scaleY = 0.7f
            bubble.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(240)
                .setInterpolator(OvershootInterpolator(1.4f)).start()
        }
        bubble.announceForAccessibility(bubble.contentDescription)

        val text = fullText
        pip.talking = true
        var index = 0
        val hideAfter = autoHideMs ?: (2600L + text.length * 50L)
        typewriter = object : Runnable {
            override fun run() {
                index = (index + 1).coerceAtMost(text.length)
                bubble.text.text = text.substring(0, index)
                if (index < text.length) {
                    val ch = text[index - 1]
                    val pause = if (ch == '.' || ch == '!' || ch == '?') 150L else typingDelayMs
                    handler.postDelayed(this, pause)
                } else {
                    typewriter = null
                    pip.talking = false
                    if (hideAfter > 0) scheduleAutoHide(hideAfter)
                }
            }
        }
        handler.postDelayed(typewriter!!, 100)
    }

    fun finishTyping() {
        val t = typewriter ?: return
        handler.removeCallbacks(t)
        typewriter = null
        pip.talking = false
        bubble.text.text = fullText
    }

    private fun scheduleAutoHide(ms: Long) {
        autoHide?.let { handler.removeCallbacks(it) }
        autoHide = Runnable { hideBubble() }.also { handler.postDelayed(it, ms) }
    }

    private fun cancelBubbleTimers() {
        typewriter?.let { handler.removeCallbacks(it) }
        autoHide?.let { handler.removeCallbacks(it) }
        typewriter = null
        autoHide = null
        pip.talking = false
    }

    fun hideBubble(immediately: Boolean = false) {
        cancelBubbleTimers()
        val callback = onBubbleHidden
        onBubbleHidden = null
        if (bubble.visibility != View.GONE) {
            if (immediately) {
                bubble.animate().cancel()
                bubble.visibility = View.GONE
            } else {
                bubble.animate().alpha(0f).scaleX(0.85f).scaleY(0.85f).setDuration(170)
                    .withEndAction { bubble.visibility = View.GONE }.start()
            }
        }
        if (!immediately) callback?.invoke()
    }

    private fun layoutBubble(placement: Placement) {
        val margin = dp(12)
        val p = centerOf(pip)
        val maxWidth = when (placement) {
            Placement.DOCK_TOP, Placement.DOCK_BOTTOM -> overlay.width - margin * 2
            Placement.RIGHT -> (overlay.width - (p.x + pipSize * 0.42f).toInt() - margin)
            else -> (overlay.width - margin * 2).coerceAtMost(dp(300))
        }.coerceAtLeast(dp(140))

        val tail = when (placement) {
            Placement.BELOW, Placement.DOCK_BOTTOM -> Tail.TOP
            Placement.ABOVE, Placement.DOCK_TOP -> Tail.BOTTOM
            Placement.RIGHT -> Tail.LEFT
        }
        bubble.setTail(tail, 0f)
        bubble.layoutParams = bubble.layoutParams.apply {
            width = if (placement == Placement.DOCK_TOP || placement == Placement.DOCK_BOTTOM) maxWidth
            else FrameLayout.LayoutParams.WRAP_CONTENT
            height = FrameLayout.LayoutParams.WRAP_CONTENT
        }
        bubble.measure(
            View.MeasureSpec.makeMeasureSpec(
                maxWidth,
                if (bubble.layoutParams.width > 0) View.MeasureSpec.EXACTLY else View.MeasureSpec.AT_MOST
            ),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val bw = bubble.measuredWidth
        val bh = bubble.measuredHeight
        // Lock size to the full message so the bubble doesn't jitter while text types in.
        bubble.layoutParams = bubble.layoutParams.apply {
            width = bw
            height = bh
        }

        val maxX = (overlay.width - bw - margin).toFloat().coerceAtLeast(margin.toFloat())
        val maxY = (overlay.height - bottomInset - bh - margin).toFloat().coerceAtLeast(margin.toFloat())
        val (x, y) = when (placement) {
            Placement.BELOW -> Pair(
                (p.x - bw * 0.72f).coerceIn(margin.toFloat(), maxX),
                (p.y + pipSize * 0.42f).coerceIn(margin.toFloat(), maxY)
            )
            Placement.ABOVE -> Pair(
                (p.x - bw * 0.72f).coerceIn(margin.toFloat(), maxX),
                (p.y - pipSize * 0.45f - bh).coerceIn(margin.toFloat(), maxY)
            )
            Placement.RIGHT -> Pair(
                p.x + pipSize * 0.42f,
                (p.y - dp(34)).coerceIn(margin.toFloat(), maxY)
            )
            Placement.DOCK_TOP -> Pair(margin.toFloat(), margin.toFloat())
            Placement.DOCK_BOTTOM -> Pair(margin.toFloat(), maxY)
        }
        bubble.translationX = x
        bubble.translationY = y
        val tailPos = when (tail) {
            Tail.LEFT -> p.y - y
            else -> p.x - x
        }
        bubble.setTail(tail, tailPos)
        bubble.pivotX = if (tail == Tail.LEFT) 0f else tailPos
        bubble.pivotY = when (tail) {
            Tail.TOP -> 0f
            Tail.BOTTOM -> bh.toFloat()
            Tail.LEFT -> tailPos
        }
    }

    // ================================================================ movement

    fun flyTo(target: PointF, durationMs: Long = 1300L, onArrive: () -> Unit = {}) {
        if (released) return
        flight?.cancel()
        val start = centerOf(pip)
        val distance = kotlin.math.hypot(target.x - start.x, target.y - start.y)
        // A playful arc: rise first, then swoop toward the target. Short hops arc less.
        val lift = (distance * 0.35f).coerceIn(dp(30).toFloat(), dp(90).toFloat())
        val c1 = PointF(start.x + (target.x - start.x) * 0.15f, start.y - lift)
        val c2 = PointF(target.x - (target.x - start.x) * 0.2f, minOf(target.y, start.y) - lift * 0.8f)
        isHome = false
        pip.flying = true
        pip.stopPointing()
        var lastX = start.x
        flight = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                val t = it.animatedValue as Float
                val x = cubic(start.x, c1.x, c2.x, target.x, t)
                val y = cubic(start.y, c1.y, c2.y, target.y, t)
                val vx = x - lastX
                lastX = x
                pip.tiltTarget = (vx / dp(1) * 3.5f).coerceIn(-22f, 22f)
                if (abs(vx) > 0.5f) pip.lookToward(vx, 0.2f)
                placePip(x, y)
            }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) { cancelled = true }
                override fun onAnimationEnd(animation: Animator) {
                    if (flight === animation) flight = null
                    pip.tiltTarget = 0f
                    if (!cancelled && !released) onArrive()
                }
            })
            start()
        }
    }

    fun flyHome(onArrive: () -> Unit = {}) {
        hideBubble()
        pip.stopPointing()
        flyTo(homeCenter(), 1100L) {
            isHome = true
            pip.flying = false
            pip.clearLook()
            pip.hop()
            onArrive()
        }
    }

    /** Flies next to [target], points at it and says [text]; then returns home when the bubble closes. */
    fun pointOut(target: View, text: String) {
        if (released || isFlying) return
        hideBubble(immediately = true)
        val t = centerOf(target)
        val rightSide = t.x < overlay.width / 2f
        val perch = PointF(
            if (rightSide) (t.x + target.width / 2f).coerceAtMost(overlay.width - pipSize * 0.6f)
            else (t.x - target.width / 2f).coerceAtLeast(pipSize * 0.6f),
            (t.y - pipSize * 0.75f).coerceAtLeast(pipSize * 0.6f)
        )
        flyTo(perch, 900L) {
            pip.pointToward(pip.angleTo(t.x - perch.x, t.y - perch.y))
            pip.lookToward(t.x - perch.x, t.y - perch.y)
            say(text, placement = if (perch.y > overlay.height / 2f) Placement.ABOVE else Placement.BELOW) {
                flyHome()
            }
        }
    }

    /** Puts Pip back on its perch immediately, cancelling any flight or speech. */
    fun resetHome() {
        hideBubble(immediately = true)
        flight?.cancel()
        flight = null
        isHome = true
        pip.flying = false
        pip.stopPointing()
        pip.clearLook()
        pip.tiltTarget = 0f
        snapHome()
    }

    /** Raises Pip and the bubble above anything added to the overlay later (e.g. a tour scrim). */
    fun bringToFront() {
        pip.bringToFront()
        bubble.bringToFront()
        pip.translationZ = dp(10).toFloat()
        bubble.translationZ = dp(12).toFloat()
    }

    fun release() {
        released = true
        handler.removeCallbacksAndMessages(null)
        flight?.cancel()
        bubble.animate().cancel()
    }

    fun postDelayed(delayMs: Long, action: () -> Unit) {
        handler.postDelayed({ if (!released) action() }, delayMs)
    }

    // ================================================================ geometry

    fun snapHome() {
        val c = homeCenter()
        if (c.x == 0f && c.y == 0f) return
        placePip(c.x, c.y)
    }

    private fun homeCenter(): PointF = centerOf(homeAnchor)

    private fun placePip(cx: Float, cy: Float) {
        pip.translationX = cx - pipSize / 2f
        pip.translationY = cy - pipSize / 2f
    }

    fun centerOf(view: View): PointF {
        if (view === pip) {
            return PointF(pip.translationX + pipSize / 2f, pip.translationY + pipSize / 2f)
        }
        val v = IntArray(2)
        val o = IntArray(2)
        view.getLocationInWindow(v)
        overlay.getLocationInWindow(o)
        return PointF(
            (v[0] - o[0]).toFloat() + view.width * view.scaleX / 2f,
            (v[1] - o[1]).toFloat() + view.height * view.scaleY / 2f
        )
    }

    private fun cubic(a: Float, b: Float, c: Float, d: Float, t: Float): Float {
        val u = 1f - t
        return u * u * u * a + 3f * u * u * t * b + 3f * u * t * t * c + t * t * t * d
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()
}

internal enum class Tail { TOP, BOTTOM, LEFT }

/** Rounded speech bubble with a tail, an optional header, and optional action buttons. */
private class SpeechBubble(context: Context) : LinearLayout(context) {
    val text = TextView(context)
    private val badgeView = TextView(context)
    private val titleView = TextView(context)
    private val actionsRow = LinearLayout(context)
    private val shape = BubbleBackground(context)
    private val d = context.resources.displayMetrics.density
    private var tail = Tail.TOP

    init {
        orientation = VERTICAL
        background = shape
        elevation = 8 * d
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                val t = (TAIL_DP * d).toInt()
                when (tail) {
                    Tail.TOP -> outline.setRoundRect(0, t, view.width, view.height, 18 * d)
                    Tail.BOTTOM -> outline.setRoundRect(0, 0, view.width, view.height - t, 18 * d)
                    Tail.LEFT -> outline.setRoundRect(t, 0, view.width, view.height, 18 * d)
                }
            }
        }
        isClickable = true

        badgeView.apply {
            textSize = 10f
            letterSpacing = 0.16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Ui.BRAND_DEEP)
        }
        addView(badgeView)
        titleView.apply {
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Ui.INK)
            setPadding(0, (2 * d).toInt(), 0, (2 * d).toInt())
        }
        addView(titleView)
        text.textSize = 15f
        text.setTextColor(Ui.INK_2)
        text.setLineSpacing(0f, 1.18f)
        addView(text, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = (2 * d).toInt()
        })
        actionsRow.orientation = HORIZONTAL
        actionsRow.gravity = Gravity.CENTER_VERTICAL
        addView(actionsRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = (12 * d).toInt()
        })
        setTail(Tail.TOP, 0f)
    }

    fun setTail(side: Tail, position: Float) {
        tail = side
        shape.tail = side
        shape.tailPos = position
        val t = (TAIL_DP * d).toInt()
        val h = (16 * d).toInt()
        val v = (12 * d).toInt()
        when (side) {
            Tail.TOP -> setPadding(h, v + t, h, v)
            Tail.BOTTOM -> setPadding(h, v, h, v + t)
            Tail.LEFT -> setPadding(h + t, v, h, v)
        }
        invalidateOutline()
        invalidate()
    }

    fun setHeader(badge: String?, title: String?) {
        badgeView.text = badge ?: "PIP"
        titleView.text = title.orEmpty()
        titleView.visibility = if (title.isNullOrEmpty()) GONE else VISIBLE
    }

    fun setActions(actions: List<PipAction>) {
        actionsRow.removeAllViews()
        actionsRow.visibility = if (actions.isEmpty()) GONE else VISIBLE
        val secondary = actions.filter { !it.primary }
        val primary = actions.filter { it.primary }
        secondary.forEach { actionsRow.addView(button(it), wrap()) }
        actionsRow.addView(View(context), LayoutParams(0, 1, 1f))
        primary.forEach { actionsRow.addView(button(it), wrap()) }
    }

    private fun wrap() = LayoutParams(LayoutParams.WRAP_CONTENT, (40 * d).toInt())

    private fun button(action: PipAction) = TextView(context).apply {
        text = action.label
        textSize = 14f
        setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.CENTER
        setPadding((16 * d).toInt(), 0, (16 * d).toInt(), 0)
        setTextColor(if (action.primary) Color.WHITE else Ui.INK_2)
        background = GradientDrawable().apply {
            cornerRadius = 20 * d
            setColor(if (action.primary) Ui.BRAND else Color.TRANSPARENT)
        }
        setOnClickListener { action.onClick() }
    }

    companion object {
        const val TAIL_DP = 9f
    }
}

private class BubbleBackground(context: Context) : Drawable() {
    var tail = Tail.TOP
    var tailPos = 0f
    private val d = context.resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.SURFACE }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * d
        color = Ui.BORDER
    }
    private val path = Path()
    private val rect = RectF()

    override fun draw(canvas: Canvas) {
        val b = bounds
        val t = SpeechBubble.TAIL_DP * d
        val r = 18 * d
        val half = 9 * d
        rect.set(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat())
        when (tail) {
            Tail.TOP -> rect.top += t
            Tail.BOTTOM -> rect.bottom -= t
            Tail.LEFT -> rect.left += t
        }
        path.reset()
        path.addRoundRect(rect, r, r, Path.Direction.CW)
        val tailPath = Path()
        when (tail) {
            Tail.TOP -> {
                val x = tailPos.coerceIn(rect.left + r, rect.right - r)
                tailPath.moveTo(x - half, rect.top + 1)
                tailPath.quadTo(x - 2 * d, rect.top - t * 0.2f, x, b.top.toFloat())
                tailPath.quadTo(x + 2 * d, rect.top - t * 0.2f, x + half, rect.top + 1)
            }
            Tail.BOTTOM -> {
                val x = tailPos.coerceIn(rect.left + r, rect.right - r)
                tailPath.moveTo(x - half, rect.bottom - 1)
                tailPath.quadTo(x - 2 * d, rect.bottom + t * 0.2f, x, b.bottom.toFloat())
                tailPath.quadTo(x + 2 * d, rect.bottom + t * 0.2f, x + half, rect.bottom - 1)
            }
            Tail.LEFT -> {
                val y = tailPos.coerceIn(rect.top + r, rect.bottom - r)
                tailPath.moveTo(rect.left + 1, y - half)
                tailPath.quadTo(rect.left - t * 0.2f, y - 2 * d, b.left.toFloat(), y)
                tailPath.quadTo(rect.left - t * 0.2f, y + 2 * d, rect.left + 1, y + half)
            }
        }
        tailPath.close()
        path.op(tailPath, Path.Op.UNION)
        canvas.drawPath(path, fill)
        canvas.drawPath(path, border)
    }

    override fun setAlpha(alpha: Int) { fill.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { fill.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
