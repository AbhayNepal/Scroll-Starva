package com.scrollstarva.app

import android.content.Context
import android.graphics.PointF
import android.os.SystemClock
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import java.time.LocalTime

/**
 * Pip's dashboard behaviour, built on [PipStage]: greets the user when the app opens, flies over
 * to the focus heart to suggest a focus session, and reacts to taps.
 */
class PipCompanion(
    context: Context,
    overlay: FrameLayout,
    homeAnchor: View,
    private val heart: View,
    private val host: Host
) {
    interface Host {
        /** Today's focus progress toward the daily target, 0..1. */
        fun focusFraction(): Float
        fun todayFocusMinutes(): Int
        fun defaultFocusMinutes(): Int
        fun onStartFocusRequested()
        /** True while something else (a dialog, the tour) has the user's attention. */
        fun isBusyElsewhere(): Boolean
    }

    val stage = PipStage(context, overlay, homeAnchor)
    val pip: PipView get() = stage.pip
    private val prefs = context.getSharedPreferences("pip", Context.MODE_PRIVATE)
    private var released = false
    private var atHeart = false

    /** While true (e.g. during the onboarding tour), Pip only cheers when tapped. */
    var guideMode = false

    private val tapTimes = ArrayDeque<Long>()
    private var tapReaction = 0

    init {
        stage.onPipTapped = { onPipTapped() }
    }

    // ================================================================ entry points

    /** Called whenever the dashboard becomes visible (app opened or brought back). */
    fun onAppOpened() {
        if (released) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastGreetAt < GREET_COOLDOWN_MS) return
        lastGreetAt = now
        // Let the screen settle and any dialog appear first.
        stage.postDelayed(700) {
            if (!host.isBusyElsewhere() && !guideMode) greet()
        }
    }

    /** Called when the activity pauses: tidy up and send Pip home instantly. */
    fun onPause() {
        if (guideMode) return
        atHeart = false
        stage.resetHome()
        resetHeart()
    }

    fun release() {
        released = true
        stage.release()
        resetHeart()
    }

    // ================================================================ behaviours

    private fun greet() {
        pip.wave(1.8f)
        pip.lookAtViewer()
        val fraction = host.focusFraction()
        val extra = when {
            fraction >= 1f -> "Your focus heart is full today. I'm so proud of you! 💚"
            host.todayFocusMinutes() > 0 ->
                "You've already focused ${host.todayFocusMinutes()} min today. ${QUOTES.random()}"
            else -> QUOTES.random()
        }
        if (fraction >= 1f) pip.playCheer()
        stage.say("${timeGreeting()} $extra") {
            pip.clearLook()
            stage.postDelayed(900) { maybeSuggestFocus() }
        }
    }

    /** Flies to the heart and suggests a focus session, unless suggested recently or already full. */
    private fun maybeSuggestFocus(force: Boolean = false) {
        if (released || guideMode || !stage.isHome || stage.isFlying || host.isBusyElsewhere()) return
        if (host.focusFraction() >= 1f) return
        val last = prefs.getLong(KEY_LAST_SUGGEST, 0L)
        if (!force && System.currentTimeMillis() - last < SUGGEST_COOLDOWN_MS) return
        prefs.edit().putLong(KEY_LAST_SUGGEST, System.currentTimeMillis()).apply()
        suggestFocus()
    }

    private fun suggestFocus() {
        stage.hideBubble(immediately = true)
        val h = stage.centerOf(heart)
        // Hover below and slightly left of the heart, so the raised arm points up at it.
        val perch = PointF(
            (h.x - heart.width * 0.7f).coerceAtLeast(stage.pipSize / 2f + 8f),
            h.y + heart.height / 2f + stage.pipSize * 0.42f
        )
        stage.flyTo(perch, 1500L) {
            atHeart = true
            pip.pointToward(pip.angleTo(h.x - perch.x, h.y - perch.y))
            pip.lookToward(h.x - perch.x, h.y - perch.y)
            pulseHeart()
            stage.postDelayed(650) {
                pip.lookAtViewer()
                val minutes = host.defaultFocusMinutes()
                val percent = (host.focusFraction() * 100).toInt()
                val line = if (percent <= 0) {
                    "See this heart? It fills up when you focus. Want to try a $minutes-minute focus session?"
                } else {
                    "Your heart is $percent% full. A $minutes-minute focus session would top it up. Shall we?"
                }
                stage.say(
                    line,
                    actions = listOf(
                        PipAction("Maybe later") {
                            stage.say("No worries! I'll be right here when you're ready.") { goHome() }
                        },
                        PipAction("Start focus", primary = true) {
                            pip.playCheer()
                            goHome()
                            host.onStartFocusRequested()
                        }
                    ),
                    autoHideMs = 14_000L
                ) { if (atHeart) goHome() }
            }
        }
    }

    private fun goHome() {
        atHeart = false
        stage.flyHome()
    }

    private fun onPipTapped() {
        if (released || stage.isFlying) return
        if (guideMode || atHeart) {
            pip.playCheer()
            return
        }
        val now = SystemClock.elapsedRealtime()
        tapTimes.addLast(now)
        while (tapTimes.isNotEmpty() && now - tapTimes.first() > 1800) tapTimes.removeFirst()
        if (tapTimes.size >= 5) {
            tapTimes.clear()
            pip.dizzy()
            stage.say("Whoa… the room is spinning! 😵 Gentle taps, please.")
            return
        }
        when (tapReaction++ % 5) {
            0 -> { pip.playCheer(); stage.say(QUOTES.random()) }
            1 -> { pip.hop(); stage.say(GIGGLES.random()) }
            2 -> maybeSuggestFocus(force = true)
            3 -> {
                pip.wave(1.2f)
                val minutes = host.todayFocusMinutes()
                stage.say(
                    if (minutes > 0) "You've focused $minutes min today. Every minute counts!"
                    else "No focus time yet today. Even 10 minutes is a great start."
                )
            }
            else -> { pip.playCheer(); stage.say(QUOTES.random()) }
        }
    }

    private fun pulseHeart() {
        fun beat(times: Int) {
            if (times == 0 || released) return
            heart.animate().scaleX(1.22f).scaleY(1.22f).setDuration(170)
                .setInterpolator(OvershootInterpolator(2f))
                .withEndAction {
                    heart.animate().scaleX(1f).scaleY(1f).setDuration(200)
                        .withEndAction { beat(times - 1) }.start()
                }.start()
        }
        beat(3)
    }

    private fun resetHeart() {
        heart.animate().cancel()
        heart.scaleX = 1f
        heart.scaleY = 1f
    }

    private fun timeGreeting(): String = when (LocalTime.now().hour) {
        in 5..11 -> listOf("Good morning! ☀️", "Morning, sunshine!", "Rise and shine!").random()
        in 12..16 -> listOf("Good afternoon!", "Hey there, afternoon friend!", "Hi again!").random()
        in 17..21 -> listOf("Good evening! 🌙", "Evening! Nice to see you.", "Hey, welcome back!").random()
        else -> listOf("Up late? Be gentle with yourself. 🌙", "Hi, night owl.", "Still awake? Let's keep it calm.").random()
    }

    companion object {
        private const val KEY_LAST_SUGGEST = "last_focus_suggestion_at"
        private const val GREET_COOLDOWN_MS = 2 * 60_000L
        private const val SUGGEST_COOLDOWN_MS = 45 * 60_000L
        /** Process-wide, so rebuilding the screen doesn't re-trigger a greeting. */
        private var lastGreetAt = -GREET_COOLDOWN_MS

        val QUOTES = listOf(
            "Small pauses add up to a calmer mind.",
            "Your attention is precious. Spend it on purpose.",
            "You don't have to be perfect, just a little more mindful than yesterday.",
            "The feed will still be there. This moment won't.",
            "Every time you choose focus, you get a little stronger.",
            "Progress, not perfection. You've got this!",
            "Your future self will thank you for this minute.",
            "Less scrolling, more living. 🌱",
            "A focused hour beats a distracted day.",
            "Be where your feet are.",
            "You're building a habit one small choice at a time.",
            "Rest your thumbs, wake your mind.",
            "Deep work feels good. Let's find some today.",
            "It's okay to start small. Starting is the hard part.",
            "Your brain loves a break from the endless feed.",
            "Make today a little more yours."
        )

        private val GIGGLES = listOf(
            "Hehe, that tickles!",
            "Boop! You found my favourite spot.",
            "Hi hi! I'm wide awake now.",
            "Wheee! Do that again… gently."
        )
    }
}
