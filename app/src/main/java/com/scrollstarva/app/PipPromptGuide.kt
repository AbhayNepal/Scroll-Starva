package com.scrollstarva.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Pip as a guide inside a reminder card (the marathon, daily-limit and focus prompts drawn by
 * the accessibility service). Pip sits next to a speech bubble, greets with the card's narration,
 * reacts to the card's pickers with short live tips, and when tapped walks through each option,
 * looking and pointing at it.
 *
 * These cards can appear several times a day, so the guide is deliberately quiet: Pip is in
 * [PipView.calm] mode, tips swap with a short fade instead of re-typing, only one gesture plays at
 * a time, and a repeat appearance skips the wave.
 */
class PipPromptGuide(context: Context) : LinearLayout(context) {

    val pip = PipView(context).apply { calm = true }

    private val handler = Handler(Looper.getMainLooper())
    private val badge = TextView(context)
    private val text = TextView(context)
    private val footer = TextView(context)
    private val bubble = LinearLayout(context)
    private val stops = mutableListOf<Stop>()
    private val tapTimes = ArrayDeque<Long>()

    private var narration = ""
    private var tourIndex = -1
    private var typewriter: Runnable? = null
    private var settle: Runnable? = null
    private var pendingHint: Runnable? = null
    private var lastHintText = ""
    private var released = false

    private val animationsOn: Boolean get() = ValueAnimator.areAnimatorsEnabled()

    private class Stop(val target: View, val text: () -> String)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        clipChildren = false
        clipToPadding = false
        setPadding(0, dp(6), 0, dp(4))

        pip.contentDescription = "Pip. Tap for a quick tour of your options."
        pip.setOnClickListener { onPipTapped() }
        addView(pip, LayoutParams(dp(78), dp(78)))

        badge.apply {
            textSize = 10f
            letterSpacing = 0.16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#E4604F"))
            text = "PIP"
        }
        text.apply {
            textSize = 15f
            setTextColor(Color.parseColor("#34434D"))
            setLineSpacing(0f, 1.15f)
        }
        footer.apply {
            textSize = 12f
            setTextColor(Color.parseColor("#9A8A7E"))
            setPadding(0, dp(6), 0, 0)
            text = "Tap me for a quick tour of your options\u00A0✨"
            visibility = GONE
        }
        bubble.apply {
            orientation = VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#FFF0C9"))
                cornerRadius = dp(16).toFloat()
            }
            isClickable = true
            setOnClickListener { if (typewriter != null) finishTyping() else onPipTapped() }
            addView(badge)
            addView(text, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(2)
            })
            addView(footer)
        }
        addView(bubble, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(8) })
    }

    // ================================================================ setup

    /** Registers an option for the tap-through tour. [text] is read when the stop is shown. */
    fun addStop(target: View, text: () -> String) {
        stops += Stop(target, text)
    }

    /** Shows the "tap me" footer (used for the first few cards ever, then dropped). */
    fun showTourTip(show: Boolean) {
        footer.visibility = if (show) VISIBLE else GONE
    }

    /**
     * Opening line. A [repeat] appearance (another card shown recently) skips the wave and
     * types faster, so frequent reminders don't feel like a performance.
     */
    fun greet(narration: String, repeat: Boolean) {
        this.narration = narration
        bubble.contentDescription = "Pip says: $narration"
        pip.lookAtViewer()
        if (animationsOn) {
            bubble.alpha = 0f
            bubble.scaleX = 0.9f
            bubble.scaleY = 0.9f
            bubble.pivotX = 0f
            bubble.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(120).setDuration(220)
                .setInterpolator(OvershootInterpolator(1.2f)).start()
            if (repeat) pip.hop() else pip.wave(1.3f)
            typeIn(narration, if (repeat) 12L else 18L)
        } else {
            text.text = narration
        }
        settleSoon(4_000L)
    }

    // ================================================================ live tips

    /**
     * A short tip tied to [target], e.g. while a picker is being turned. Calls are debounced so
     * spinning a wheel produces one calm update when it settles, not a flicker per step.
     */
    fun hint(target: View?, tip: String, delayMs: Long = 260L) {
        if (released) return
        pendingHint?.let { handler.removeCallbacks(it) }
        pendingHint = Runnable {
            pendingHint = null
            tourIndex = -1
            badge.text = "PIP"
            showTip(tip, target, point = false)
        }.also { handler.postDelayed(it, delayMs) }
    }

    // ================================================================ tour

    private fun onPipTapped() {
        if (released) return
        if (typewriter != null) {
            finishTyping()
            return
        }
        val now = SystemClock.elapsedRealtime()
        tapTimes.addLast(now)
        while (tapTimes.isNotEmpty() && now - tapTimes.first() > 2_000L) tapTimes.removeFirst()
        if (tapTimes.size >= 7) {
            tapTimes.clear()
            tourIndex = -1
            badge.text = "PIP"
            pip.stopPointing()
            pip.dizzy(1.6f)
            showTip("Whoa, spinning! 😵 One tap at a time and I'll show you around.", null, point = false)
            return
        }

        if (footer.visibility != GONE) {
            footer.visibility = GONE
            holdHeight()
        }
        if (stops.isEmpty()) {
            pip.hop()
            showTip(narration, null, point = false)
            return
        }
        tourIndex++
        if (tourIndex >= stops.size) {
            tourIndex = -1
            badge.text = "PIP"
            pip.stopPointing()
            pip.hop()
            showTip("That's everything! No wrong answers — pick whatever feels right. 💛", null, point = false)
            return
        }
        val stop = stops[tourIndex]
        badge.text = "OPTION ${tourIndex + 1} OF ${stops.size} · TAP FOR NEXT"
        stop.target.requestRectangleOnScreen(Rect(0, 0, stop.target.width, stop.target.height), false)
        if (tourIndex == 0) pip.hop()
        showTip(stop.text(), stop.target, point = true)
        nudge(stop.target)
    }

    // ================================================================ internals

    private fun showTip(tip: String, target: View?, point: Boolean) {
        cancelTyping()
        // Pointing waits a frame so a scroll triggered by the tour has moved the target.
        post {
            if (released) return@post
            if (target != null) {
                val (dx, dy) = offsetTo(target)
                pip.lookToward(dx, dy)
                if (point) pip.pointToward(pip.angleTo(dx, dy)) else pip.stopPointing()
            } else {
                pip.lookAtViewer()
            }
        }
        bubble.contentDescription = "Pip says: $tip"
        if (tip == lastHintText && text.text.toString() == tip) {
            settleSoon(3_200L)
            return
        }
        lastHintText = tip
        if (animationsOn) {
            text.animate().cancel()
            text.animate().alpha(0f).setDuration(90).withEndAction {
                text.text = tip
                holdHeight()
                pip.talking = true
                handler.postDelayed({ pip.talking = false }, (tip.length * 22L).coerceIn(400L, 1_400L))
                text.animate().alpha(1f).setDuration(140).start()
            }.start()
        } else {
            text.text = tip
        }
        bubble.announceForAccessibility(tip)
        settleSoon(if (tourIndex >= 0) 6_000L else 3_200L)
    }

    /**
     * Lets the bubble grow but not shrink between tips, so a shorter tip doesn't make the
     * bottom-anchored card jump. Skipped while the one-off footer tip is showing.
     */
    private fun holdHeight() {
        bubble.post {
            if (footer.visibility == GONE && bubble.height > bubble.minimumHeight) {
                bubble.minimumHeight = bubble.height
            }
        }
    }

    /** After a while, Pip stops pointing and looks back at the viewer. */
    private fun settleSoon(ms: Long) {
        settle?.let { handler.removeCallbacks(it) }
        settle = Runnable {
            pip.stopPointing()
            pip.lookAtViewer()
        }.also { handler.postDelayed(it, ms) }
    }

    /** A single soft pulse on the option being described. */
    private fun nudge(target: View) {
        if (!animationsOn) return
        target.animate().cancel()
        target.animate().scaleX(1.04f).scaleY(1.04f).setDuration(160).withEndAction {
            target.animate().scaleX(1f).scaleY(1f).setDuration(200).start()
        }.start()
    }

    private fun offsetTo(target: View): Pair<Float, Float> {
        val p = IntArray(2)
        val t = IntArray(2)
        pip.getLocationOnScreen(p)
        target.getLocationOnScreen(t)
        return Pair(
            t[0] + target.width / 2f - (p[0] + pip.width / 2f),
            t[1] + target.height / 2f - (p[1] + pip.height / 2f)
        )
    }

    private fun typeIn(full: String, delayMs: Long) {
        cancelTyping()
        lastHintText = full
        text.text = SpannableString(full).apply {
            setSpan(ForegroundColorSpan(Color.TRANSPARENT), 0, full.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        pip.talking = true
        var index = 0
        typewriter = object : Runnable {
            override fun run() {
                index = (index + 1).coerceAtMost(full.length)
                // The whole line is laid out from the start (untyped part transparent) so the
                // bubble doesn't grow line by line and nudge the card around.
                text.text = SpannableString(full).apply {
                    setSpan(
                        ForegroundColorSpan(Color.TRANSPARENT),
                        index, full.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
                if (index < full.length) {
                    val ch = full[index - 1]
                    handler.postDelayed(this, if (ch == '.' || ch == '!' || ch == '?') 120L else delayMs)
                } else {
                    typewriter = null
                    pip.talking = false
                }
            }
        }.also { handler.postDelayed(it, 260L) }
    }

    private fun finishTyping() {
        cancelTyping()
        text.text = lastHintText
    }

    private fun cancelTyping() {
        typewriter?.let { handler.removeCallbacks(it) }
        typewriter = null
        pip.talking = false
    }

    override fun onDetachedFromWindow() {
        released = true
        handler.removeCallbacksAndMessages(null)
        super.onDetachedFromWindow()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
