package com.scrollstarva.app

import android.accessibilityservice.AccessibilityService
import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.content.Intent
import android.view.Gravity
import android.view.WindowManager
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.ScrollView
import android.widget.TextView
import java.time.LocalDate
import java.time.ZoneId

class ScrollAccessibilityService : AccessibilityService() {
    private val repository by lazy { TrackingRepository(this) }
    private val windowManager by lazy {
        checkNotNull(getSystemService(WindowManager::class.java)) { "WindowManager is unavailable" }
    }
    private val handler = Handler(Looper.getMainLooper())
    private var trackingPackage: String? = null
    private var trackingPlatform: FeedPlatform? = null
    private var trackingAppLabel: String? = null
    private var trackingActivityId: String? = null
    private var trackingStartedAtElapsed = 0L
    private var trackingStartedAtWall = 0L
    private var trackingFeedActive = false
    private var lastCheckpointElapsed = 0L
    private var sessionScrollCount = 0
    private var lastScrollAt = 0L
    private var lastLegacyScrollPosition: LegacyScrollPosition? = null
    private var marathonReminderShown = false
    private var lastMarathonNarration: String? = null
    private var lastLimitNarration: String? = null
    private var lastFocusNarration: String? = null
    private var interventionView: android.view.View? = null
    private val delayedStop = Runnable { stopTracking() }
    private val repeatMarathonReminderRunnable = Runnable {
        showPendingMarathonReminderIfDue()
    }
    private val focusReminderRunnable = Runnable {
        if (repository.isFocusReminderDue()) {
            repository.markFocusReminderShown()
            showFocusInterruptionPrompt()
        }
    }

    private val flushTimer = object : Runnable {
        override fun run() {
            showPendingMarathonReminderIfDue()
            checkpointAndCheckInterventions()
            handler.postDelayed(this, CHECKPOINT_INTERVAL_MILLIS)
        }
    }

    private fun scheduleFocusReminder() {
        handler.removeCallbacks(focusReminderRunnable)
        val focusSession = repository.activeFocusSession() ?: return
        if (!focusSession.isPaused) return
        if (focusSession.reminderAtMillis == 0L) {
            showFocusInterruptionPrompt()
            return
        }
        handler.postDelayed(
            focusReminderRunnable,
            (focusSession.reminderAtMillis - System.currentTimeMillis()).coerceAtLeast(0L)
        )
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        repository.finishInterruptedSessions()
        AccessibilityDiagnostics.record("Accessibility service connected and tracking enabled")
        val activeWindow = rootInActiveWindow
        val activePackageName = activeWindow?.packageName?.toString()
        if (activePackageName != null && activePackageName in repository.selectedPackages()) {
            startTracking(activePackageName)
            trackingFeedActive = trackingPlatform?.let { platform ->
                FeedDetector.isShortFormFeed(FeedDetector.visibleLabels(activeWindow), platform)
            } ?: false
            if (trackingFeedActive) {
                showPendingMarathonReminderIfDue()
            }
        }
        scheduleFocusReminder()
        schedulePendingMarathonReminder()
        handler.postDelayed(flushTimer, CHECKPOINT_INTERVAL_MILLIS)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString()
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (packageName == this.packageName ||
                packageName == null ||
                packageName !in repository.selectedPackages()
            ) {
                if (trackingPackage != null) scheduleStopTracking()
                return
            }
        }

        if (packageName == this.packageName) return

        if (packageName == null || packageName == this.packageName) {
            return
        }
        if (packageName !in repository.selectedPackages()) {
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                trackingPackage != null
            ) {
                scheduleStopTracking()
            }
            return
        }
        val platform = FeedDetector.platformFor(packageName)

        val focusSession = repository.activeFocusSession()
        if (focusSession != null) {
            if (!focusSession.isPaused || focusSession.reminderAtMillis == 0L) {
                if (trackingPackage != null) stopTracking()
                showFocusInterruptionPrompt()
                return
            }
        }

        val appForegroundEvent = event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        val continuingTrackedFeed = event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED &&
            platform != null && trackingPackage == packageName && trackingFeedActive
        val sourceLabels = if (continuingTrackedFeed) "" else FeedDetector.visibleLabels(event.source)
        val windowLabels = if (continuingTrackedFeed) "" else FeedDetector.visibleLabels(rootInActiveWindow)
        val visibleLabels = listOf(sourceLabels, windowLabels)
            .filter(String::isNotBlank)
            .distinct()
            .joinToString(" | ")
            .take(MAX_COMBINED_LABEL_LENGTH)
        val feedVisible = continuingTrackedFeed ||
            (platform != null && event.eventType in FEED_DETECTION_EVENT_TYPES &&
                FeedDetector.isShortFormFeed(visibleLabels, platform))
        val feedMatch = if (continuingTrackedFeed || platform == null) null
            else FeedDetector.feedMatch(visibleLabels, platform)
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            AccessibilityDiagnostics.record(
                "Package: $packageName\n" +
                    "Event: ${eventTypeName(event.eventType)} (${event.eventType})\n" +
                    "App: ${resolveAppLabel(packageName)}\n" +
                    "Feed visible: $feedVisible\n" +
                    "Matching feed label: ${feedMatch ?: "none"}\n" +
                    "Visible labels: ${visibleLabels.ifBlank { "(none exposed by this event)" }}"
            )
        }

        val countableAppScroll = platform == null &&
            event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED
        if (!appForegroundEvent && !feedVisible && !continuingTrackedFeed && !countableAppScroll) {
            return
        }

        handler.removeCallbacks(delayedStop)
        startTracking(packageName)
        trackingFeedActive = feedVisible
        showPendingMarathonReminderIfDue()
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED &&
            (platform == null || feedVisible) && isVerticalScroll(event)
        ) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastScrollAt > SCROLL_DEBOUNCE_MILLIS) {
                repository.addScroll(packageName)
                trackingActivityId?.let(repository::recordSessionScroll)
                sessionScrollCount++
                lastScrollAt = now
                maybeShowDailyLimitPrompt()
                AccessibilityDiagnostics.record(
                    "Scroll decision: COUNTED\n" +
                        "Package: $packageName\n" +
                        "Delta: ${scrollDeltaDescription(event)}\n" +
                        "Debounce: passed (> ${SCROLL_DEBOUNCE_MILLIS}ms)"
                )
            }
        }
    }

    override fun onInterrupt() = stopTracking()

    override fun onDestroy() {
        stopTracking()
        removeIntervention()
        handler.removeCallbacks(delayedStop)
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun startTracking(packageName: String) {
        if (trackingPackage == packageName) return
        stopTracking()

        val nowWall = System.currentTimeMillis()
        val session = repository.beginSession(packageName, nowWall)
        trackingPackage = packageName
        trackingPlatform = FeedDetector.platformFor(packageName)
        trackingAppLabel = resolveAppLabel(packageName)
        trackingActivityId = session.activityId
        trackingStartedAtElapsed = SystemClock.elapsedRealtime()
        trackingFeedActive = false
        trackingStartedAtWall = nowWall
        lastCheckpointElapsed = trackingStartedAtElapsed
        sessionScrollCount = 0
        lastScrollAt = 0L
        lastLegacyScrollPosition = null
        marathonReminderShown = false

        AccessibilityDiagnostics.record(
            "Session started\nApp: $trackingAppLabel\n" +
                "Rapid re-entry for this app: ${session.isRapidReentry}"
        )
//        if (session.isRapidReentry) showPauseOverlay()
    }

    private fun stopTracking() {
        handler.removeCallbacks(delayedStop)
        val activityId = trackingActivityId
        if (activityId != null) {
            val nowElapsed = SystemClock.elapsedRealtime()
            val duration = maxOf(0L, nowElapsed - trackingStartedAtElapsed)
            repository.addActiveMillis(maxOf(0L, nowElapsed - lastCheckpointElapsed))
            repository.finishSession(
                activityId,
                System.currentTimeMillis(),
                duration,
                sessionScrollCount
            )
            AccessibilityDiagnostics.record(
                "Session ended\nApp: ${trackingAppLabel ?: trackingPackage}\n" +
                    "Duration: ${duration / 1000}s\nScrolls recorded: $sessionScrollCount"
            )
        }
        trackingPackage = null
        trackingPlatform = null
        trackingAppLabel = null
        trackingActivityId = null
        trackingStartedAtElapsed = 0L
        trackingStartedAtWall = 0L
        trackingFeedActive = false
        lastCheckpointElapsed = 0L
        sessionScrollCount = 0
        marathonReminderShown = false
    }

    private fun scheduleStopTracking() {
        handler.removeCallbacks(delayedStop)
        handler.postDelayed(delayedStop, FEED_EXIT_GRACE_MILLIS)
    }

    private fun resolveAppLabel(packageName: String): String =
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()

    private fun scrollDeltaDescription(event: AccessibilityEvent): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            "x=${event.scrollDeltaX}, y=${event.scrollDeltaY}"
        } else {
            "fromIndex=${event.fromIndex}, toIndex=${event.toIndex}, x=${event.scrollX}, y=${event.scrollY}"
        }

    private fun eventTypeName(type: Int): String = when (type) {
        AccessibilityEvent.TYPE_VIEW_SCROLLED -> "VIEW_SCROLLED"
        AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "WINDOW_STATE_CHANGED"
        AccessibilityEvent.TYPE_WINDOWS_CHANGED -> "WINDOWS_CHANGED"
        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> "WINDOW_CONTENT_CHANGED"
        else -> "OTHER"
    }

    private fun checkpointAndCheckInterventions() {
        showPendingMarathonReminderIfDue()
        val activityId = trackingActivityId ?: return
        val nowElapsed = SystemClock.elapsedRealtime()
        val duration = maxOf(0L, nowElapsed - trackingStartedAtElapsed)
        repository.addActiveMillis(maxOf(0L, nowElapsed - lastCheckpointElapsed))
        repository.checkpointSession(
            activityId,
            System.currentTimeMillis(),
            duration,
            sessionScrollCount
        )
        lastCheckpointElapsed = nowElapsed

        maybeShowDailyLimitPrompt()
        maybeShowMarathonReminder(nowElapsed)

        if (LocalDate.now() != LocalDate.ofInstant(
                java.time.Instant.ofEpochMilli(trackingStartedAtWall),
                ZoneId.systemDefault()
            )
        ) {
            rollSessionOverAtMidnight(nowElapsed)
        }
    }

    private fun rollSessionOverAtMidnight(nowElapsed: Long) {
        val packageName = trackingPackage ?: return
        val activityId = trackingActivityId ?: return
        val nowWall = System.currentTimeMillis()
        val todayStart = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val priorDuration = maxOf(0L, todayStart - trackingStartedAtWall)
        repository.finishSession(activityId, todayStart, priorDuration, sessionScrollCount)

        val elapsedSinceMidnight = maxOf(0L, nowWall - todayStart)
        val newStartElapsed = nowElapsed - elapsedSinceMidnight
        val newSession = repository.beginSession(packageName, todayStart, countAsReentry = false)
        trackingPackage = packageName
        trackingActivityId = newSession.activityId
        trackingStartedAtElapsed = newStartElapsed
        trackingStartedAtWall = todayStart
        lastCheckpointElapsed = nowElapsed
        sessionScrollCount = 0
        lastScrollAt = 0L
        repository.checkpointSession(
            newSession.activityId,
            nowWall,
            elapsedSinceMidnight,
            sessionScrollCount
        )
//        if (newSession.isRapidReentry) showPauseOverlay()
        maybeShowMarathonReminder(nowElapsed)
    }

    private fun maybeShowMarathonReminder(nowElapsed: Long) {
        if (trackingActivityId == null ||
            trackingPackage == null ||
            repository.repeatBreakReminderAtMillis() > 0L ||
            marathonReminderShown ||
            repository.areBreakRemindersMuted() ||
            repository.activeFocusSession() != null ||
            interventionView != null ||
            !isTrackedAppForeground()
        ) {
            return
        }
        val reminderIntervalMillis = marathonReminderIntervalMillis()
        val nextReminderAt = trackingStartedAtElapsed + reminderIntervalMillis
        if (nowElapsed < nextReminderAt) return

        marathonReminderShown = true
        showMarathonReminder()
    }

    private fun schedulePendingMarathonReminder() {
        handler.removeCallbacks(repeatMarathonReminderRunnable)
        val reminderAtMillis = repository.repeatBreakReminderAtMillis()
        if (reminderAtMillis == 0L) return
        handler.postDelayed(
            repeatMarathonReminderRunnable,
            (reminderAtMillis - System.currentTimeMillis()).coerceAtLeast(0L)
        )
    }

    private fun showPendingMarathonReminderIfDue() {
        val reminderAtMillis = repository.repeatBreakReminderAtMillis()
        if (reminderAtMillis == 0L || reminderAtMillis > System.currentTimeMillis() ||
            trackingActivityId == null || trackingPackage == null ||
            repository.activeFocusSession() != null ||
            repository.areBreakRemindersMuted() ||
            interventionView != null
        ) {
            return
        }
        handler.removeCallbacks(repeatMarathonReminderRunnable)
        repository.clearRepeatBreakReminder()
        marathonReminderShown = true
        showMarathonReminder()
    }

    private fun showMarathonReminder() {
        val interval = marathonReminderIntervalLabel()
        val narration = nextNarration(
            listOf(
                "Hey buddy, I said I’d check in after $interval. Want to claim a little space for the rest of your day?",
                "I promised to pop by after $interval—here I am, cheering you on. Shall we give your attention a break?",
                "Your $interval reminder is here! You’ve got this; let’s choose what you want the next part of today to feel like.",
                "As promised, I’m checking in after $interval. Ready to pause the scroll and make room for something you love?",
                "We set this little check-in for $interval, and I’m glad you’re here. How about a reset before the next chapter?"
            ),
            lastMarathonNarration
        ).also { lastMarathonNarration = it }
        val elapsedMinutes = maxOf(0L, SystemClock.elapsedRealtime() - trackingStartedAtElapsed) / 60_000L
        showBreakPrompt(
            title = "A little pause, buddy?",
            narration = narration,
            message = "You’ve been using ${trackingAppLabel ?: "this app"} for $elapsedMinutes minutes. A short pause can help you come back with a clearer mind."
        )
    }

    private fun maybeShowDailyLimitPrompt() {
        if (trackingActivityId == null ||
            interventionView != null ||
            repository.activeFocusSession() != null ||
            repository.areBreakRemindersMuted() ||
            !isTrackedAppForeground()
        ) {
            return
        }
        val limits = repository.claimDailyLimitPrompts(repository.dailyLimitsAtEightyPercent())
        if (limits.isEmpty()) return

        val metrics = checkNotNull(repository.snapshot().dailyHistory.lastOrNull()) {
            "Today's daily summary is unavailable"
        }
        val narration = nextNarration(
            listOf(
                "You’ve made it a good way toward today’s intention. Want to save a little room for something else you care about?",
                "A quick Pip check-in: your daily goal is getting close. Let’s take a gentle pause and keep some attention in reserve.",
                "Look at you showing up! You’re nearing one of today’s limits—how about a tiny reset before carrying on?",
                "You’ve used a big part of today’s allowance. Let’s make the next choice a thoughtful one; I’m cheering for you.",
                "Your progress is looking lively! You’re close to your daily mark, so let’s pause and decide what matters next."
            ),
            lastLimitNarration
        ).also { lastLimitNarration = it }
        val details = limits.map { limit ->
            when (limit) {
                DailyLimitType.SCROLLS -> {
                    val target = repository.dailyScrollTarget()
                    val used = metrics.scrollCount
                    val percent = (used * 100L / target).coerceAtLeast(80L)
                    "scroll goal ($used of $target, $percent%)"
                }
                DailyLimitType.TIME -> {
                    val targetMinutes = repository.dailyTimeTargetMinutes()
                    val usedSeconds = metrics.activeSeconds
                    val percent = (usedSeconds * 100 / (targetMinutes * 60L)).coerceAtLeast(80)
                    "time goal (${formatUsageDuration(usedSeconds)} of $targetMinutes minutes, $percent%)"
                }
            }
        }
        showBreakPrompt(
            title = "A little room for today",
            narration = narration,
            message = "You’ve reached 80% or more of your daily ${details.joinToString(" and ")}. A short pause can help you choose how you’d like to spend the rest of today."
        )
    }

    private fun showFocusInterruptionPrompt() {
        if (interventionView != null) return
        val focusSession = repository.activeFocusSession() ?: return
        val remainingMillis = if (focusSession.isPaused) {
            focusSession.pausedRemainingMillis
        } else {
            (focusSession.endsAtMillis - System.currentTimeMillis()).coerceAtLeast(0L)
        }
        if (remainingMillis <= 0L) {
            repository.endFocusSession()
            return
        }
        var selectedBreakMinutes = repository.focusBreakMinutes()
        showPromptCard(
            title = "Your focus time isn’t finished",
            narration = nextNarration(
                listOf(
                    "A gentle nudge, buddy—your focus time is still here whenever you’re ready to come back.",
                    "Pip’s here with a smile. Want to take a short scroll break, or return to the thing you chose to focus on?",
                    "You made a promise to yourself to focus. No pressure—choose whether to pause or pick it back up.",
                    "A little detour is okay. I’m cheering you on whenever you’re ready to return to your focus."
                ),
                lastFocusNarration
            ).also { lastFocusNarration = it },
            message = "You still have ${formatMinutes(remainingMillis)} of focus time. Choose what feels right.",
            quote = focusSession.quote,
            extraContent = { prompt ->
                val maxBreak = TrackingTimerSettings.maxFocusBreakMinutes(repository.marathonMinutes())
                val breakPicker = HorizontalWheelPicker(
                    this,
                    label = "Scroll break (minutes)",
                    minValue = TrackingTimerSettings.MIN_FOCUS_BREAK_MINUTES,
                    maxValue = maxBreak,
                    initialValue = selectedBreakMinutes,
                    swipeDpPerStep = 20f
                )
                breakPicker.onValueChanged = { selectedBreakMinutes = it }
                addPromptSelector(prompt, breakPicker)
                addPromptAction(prompt, "Take this scroll break", primary = true) {
                    repository.setFocusBreakMinutes(selectedBreakMinutes)
                    if (repository.pauseFocusForScrollBreak(selectedBreakMinutes * 60_000L) != null) {
                        scheduleFocusReminder()
                    }
                    removeIntervention()
                }
            },
            actions = listOf(
                "End focus early" to {
                    handler.removeCallbacks(focusReminderRunnable)
                    repository.endFocusSession()
                    removeIntervention()
                },
                "Resume focus" to {
                    handler.removeCallbacks(focusReminderRunnable)
                    repository.resumeFocusSession()
                    removeIntervention()
                    returnToFocusScreen()
                }
            )
        )
    }

    private fun showBreakPrompt(title: String, narration: String, message: String) {
        val durationPicker = FocusDurationPicker(
            this,
            repository.defaultFocusMinutes() * 60_000L
        )
        val snoozeOptions = TrackingTimerSettings.BREAK_REMINDER_SNOOZE_OPTIONS_MILLIS
        var selectedSnoozeIndex = 0
        showPromptCard(
            title = title,
            narration = narration,
            message = message,
            quote = FocusSessionQuotes.random(),
            extraContent = { prompt ->
                prompt.addView(TextView(this).apply {
                    text = "Focus duration (up to 8 hours)"
                    textSize = 14f
                    setTextColor(Color.parseColor("#65727E"))
                    setPadding(0, dp(4), 0, 0)
                })
                addPromptSelector(prompt, durationPicker)
                addPromptAction(prompt, "Start focus", primary = true) {
                    startFocusSession(durationPicker.durationMillis)
                }
                val snoozeLabel = TextView(this).apply {
                    text = snoozeDescription(snoozeOptions[selectedSnoozeIndex])
                    textSize = 14f
                    setTextColor(Color.parseColor("#65727E"))
                    setPadding(0, dp(4), 0, 0)
                }
                addPromptSelector(prompt, NumberPicker(this).apply {
                    minValue = snoozeOptions.indices.first
                    maxValue = snoozeOptions.indices.last
                    displayedValues = snoozeOptions.map(::snoozeWheelLabel).toTypedArray()
                    value = selectedSnoozeIndex
                    wrapSelectorWheel = false
                    descendantFocusability = NumberPicker.FOCUS_BLOCK_DESCENDANTS
                    contentDescription = "Mute break reminders duration"
                    setOnValueChangedListener { _, _, index ->
                        selectedSnoozeIndex = index
                        snoozeLabel.text = snoozeDescription(snoozeOptions[index])
                    }
                }, dp(96))
                prompt.addView(snoozeLabel)
                addPromptAction(prompt, "Mute break reminders", primary = true) {
                    repository.muteBreakReminders(snoozeOptions[selectedSnoozeIndex])
                    handler.removeCallbacks(repeatMarathonReminderRunnable)
                    marathonReminderShown = false
                    removeIntervention()
                }
            },
            actions = listOf(
                "Remind me again in ${marathonReminderIntervalLabel()}" to {
                    repository.scheduleBreakReminderAgain(marathonReminderIntervalMillis())
                    marathonReminderShown = false
                    removeIntervention()
                    schedulePendingMarathonReminder()
                },
                "Maybe later" to { removeIntervention() }
            )
        )
    }

    private fun nextNarration(options: List<String>, previous: String?): String =
        options.filterNot { it == previous }.random()

    private fun isTrackedAppForeground(): Boolean =
        trackingPackage != null &&
            rootInActiveWindow?.packageName?.toString() == trackingPackage

    private fun snoozeDescription(durationMillis: Long): String =
        "Don’t bother me for the next ${snoozeWheelLabel(durationMillis)}."

    private fun marathonReminderIntervalMillis(): Long =
        repository.marathonMinutes() * 60_000L

    private fun marathonReminderIntervalLabel(): String =
        "${repository.marathonMinutes()} minutes"

    private fun snoozeWheelLabel(durationMillis: Long): String =
        if (durationMillis < 60_000L) {
            "${durationMillis / 1_000L} sec"
        } else if (durationMillis < 60 * 60_000L) {
            "${durationMillis / 60_000L} min"
        } else {
            "${durationMillis / (60 * 60_000L)} hour${if (durationMillis >= 2 * 60 * 60_000L) "s" else ""}"
        }

    private fun showPromptCard(
        title: String,
        narration: String,
        message: String,
        quote: String,
        extraContent: ((LinearLayout) -> Unit)?,
        actions: List<Pair<String, () -> Unit>>
    ) {
        removeIntervention()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(16))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#FFF8EF"))
                cornerRadius = dp(22).toFloat()
                setStroke(dp(1), Color.parseColor("#F1D8C5"))
            }
            elevation = dp(12).toFloat()
        }
        content.addView(TextView(this).apply {
            text = title
            textSize = 20f
            setTextColor(Color.parseColor("#17202A"))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        content.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(4))
            addView(BuddyCelebrationView(), LinearLayout.LayoutParams(dp(72), dp(68)))
            addView(TextView(this@ScrollAccessibilityService).apply {
                text = "Pip: “$narration”"
                textSize = 15f
                setTextColor(Color.parseColor("#34434D"))
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#FFF0C9"))
                    cornerRadius = dp(16).toFloat()
                }
            }, LinearLayout.LayoutParams(0, -2, 1f))
        })
        content.addView(TextView(this).apply {
            text = message
            textSize = 15f
            setTextColor(Color.parseColor("#65727E"))
            setPadding(0, dp(8), 0, dp(6))
        })
        content.addView(TextView(this).apply {
            text = "“$quote”"
            textSize = 14f
            setTextColor(Color.parseColor("#C94D43"))
            setPadding(0, dp(2), 0, dp(12))
        })
        extraContent?.invoke(content)
        actions.forEachIndexed { index, (actionText, action) ->
            addPromptAction(content, actionText, primary = index == 0, action = action)
        }

        val maxPromptHeight = (resources.displayMetrics.heightPixels * .82f).toInt()
            .coerceAtLeast(dp(320))
        val root = object : ScrollView(this) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val cappedHeight = View.MeasureSpec.makeMeasureSpec(
                    maxPromptHeight,
                    View.MeasureSpec.AT_MOST
                )
                super.onMeasure(widthMeasureSpec, cappedHeight)
            }
        }.apply {
            isFillViewport = false
            clipToPadding = false
            setPadding(0, dp(8), 0, dp(8))
            addView(content, android.view.ViewGroup.LayoutParams(-1, -2))
        }
        val params = WindowManager.LayoutParams(
            (resources.displayMetrics.widthPixels - dp(32)).coerceAtLeast(dp(280)),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            x = 0
            y = dp(24)
        }
        windowManager.addView(root, params)
        interventionView = root
    }

    private inner class BuddyCelebrationView : View(this@ScrollAccessibilityService) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var phase = 0f
        private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1_000L
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                phase = it.animatedValue as Float
                invalidate()
            }
        }

        init {
            contentDescription = "Pip smiles, jumps with excitement, and sends out ripples"
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            animator.start()
        }

        override fun onDetachedFromWindow() {
            animator.cancel()
            super.onDetachedFromWindow()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val scale = minOf(width / dp(72).toFloat(), height / dp(68).toFloat())
            if (scale <= 0f) return
            canvas.save()
            canvas.scale(scale, scale)

            val centerX = dp(36).toFloat()
            val floorY = dp(59).toFloat()
            val jump = if (phase < .42f) {
                kotlin.math.sin(phase / .42f * Math.PI).toFloat() * dp(7)
            } else {
                0f
            }
            val ripplePhase = ((phase - .42f) / .58f).coerceIn(0f, 1f)
            if (phase >= .42f) {
                for (offset in listOf(0f, .35f)) {
                    val progress = (ripplePhase + offset).coerceAtMost(1f)
                    paint.color = Color.argb(
                        ((1f - progress) * 150f).toInt(),
                        44,
                        154,
                        145
                    )
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = dp(1.5f)
                    canvas.drawOval(
                        RectF(
                            centerX - dp(7) - dp(18) * progress,
                            floorY - dp(3) - dp(5) * progress,
                            centerX + dp(7) + dp(18) * progress,
                            floorY + dp(3) + dp(5) * progress
                        ),
                        paint
                    )
                }
            }

            val headY = floorY - dp(27) - jump
            paint.style = Paint.Style.FILL
            paint.color = Color.parseColor("#2C9A91")
            canvas.drawRoundRect(
                RectF(centerX - dp(19), headY - dp(19), centerX + dp(19), headY + dp(17)),
                dp(13).toFloat(),
                dp(13).toFloat(),
                paint
            )
            paint.color = Color.parseColor("#F5C451")
            val sprout = Path().apply {
                moveTo(centerX, headY - dp(17))
                cubicTo(centerX - dp(2), headY - dp(22), centerX + dp(7), headY - dp(24),
                    centerX + dp(12), headY - dp(22))
                cubicTo(centerX + dp(11), headY - dp(19), centerX + dp(6), headY - dp(16),
                    centerX, headY - dp(17))
                close()
            }
            canvas.drawPath(sprout, paint)
            paint.color = Color.parseColor("#17202A")
            canvas.drawCircle(centerX - dp(7), headY - dp(3), dp(1.5f), paint)
            canvas.drawCircle(centerX + dp(7), headY - dp(3), dp(1.5f), paint)
            paint.color = Color.parseColor("#F3A49A")
            canvas.drawCircle(centerX - dp(12), headY + dp(3), dp(2f), paint)
            canvas.drawCircle(centerX + dp(12), headY + dp(3), dp(2f), paint)
            paint.color = Color.parseColor("#17202A")
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(1.5f)
            canvas.drawArc(
                RectF(centerX - dp(8), headY - dp(1), centerX + dp(8), headY + dp(10)),
                15f,
                150f,
                false,
                paint
            )
            canvas.restore()
        }
    }

    private fun addPromptSelector(parent: LinearLayout, selector: View, height: Int? = null) {
        val inset = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(12), 0, dp(12), 0)
            addView(selector, LinearLayout.LayoutParams(-1, height ?: -2))
        }
        parent.addView(inset, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(2)
            bottomMargin = dp(4)
        })
    }

    private fun addPromptAction(
        parent: LinearLayout,
        text: String,
        primary: Boolean,
        action: () -> Unit
    ) {
        val availableWidthDp =
            (resources.displayMetrics.widthPixels / resources.displayMetrics.density).toInt()
        val buttonWidth = dp(minOf(250, availableWidthDp - 112).coerceAtLeast(180))
        val button = Button(this).apply {
            this.text = text
            textSize = 14f
            maxLines = 2
            minWidth = 0
            minimumWidth = 0
            minHeight = dp(48)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            isAllCaps = false
            setTextColor(if (primary) Color.WHITE else Color.parseColor("#17202A"))
            background = GradientDrawable().apply {
                setColor(
                    if (primary) Color.parseColor("#F26B5E")
                    else Color.parseColor("#F3E8DC")
                )
                cornerRadius = dp(12).toFloat()
            }
            setOnClickListener { action() }
        }
        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(button, LinearLayout.LayoutParams(buttonWidth, -2))
        }
        parent.addView(actionRow, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(4)
            bottomMargin = dp(4)
        })
    }

    private fun startFocusSession(durationMillis: Long) {
        repository.startFocusSession(
            durationMillis,
            FocusSessionQuotes.random()
        )
        if (trackingPackage != null) stopTracking()
        removeIntervention()
        returnToFocusScreen()
    }

    private fun returnToFocusScreen() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
        )
    }

    private fun removeIntervention() {
        interventionView?.let { view ->
            windowManager.removeView(view)
            interventionView = null
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private fun formatMinutes(millis: Long): String {
        val minutes = (millis + 59_999L) / 60_000L
        return "$minutes ${if (minutes == 1L) "minute" else "minutes"}"
    }

    private fun formatUsageDuration(seconds: Long): String {
        val minutes = seconds / 60L
        val remainingSeconds = seconds % 60L
        return if (remainingSeconds == 0L) {
            "$minutes min"
        } else {
            "$minutes min ${remainingSeconds}s"
        }
    }

    private fun isVerticalScroll(event: AccessibilityEvent): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return event.scrollDeltaY != 0
        }

        val current = LegacyScrollPosition(
            packageName = event.packageName?.toString(),
            className = event.className?.toString(),
            fromIndex = event.fromIndex,
            toIndex = event.toIndex,
            scrollX = event.scrollX,
            scrollY = event.scrollY
        )
        val previous = lastLegacyScrollPosition
        lastLegacyScrollPosition = current
        if (previous == null ||
            current.packageName != previous.packageName ||
            current.className != previous.className
        ) {
            return false
        }

        val horizontalMoved = current.scrollX != previous.scrollX
        val verticalMoved = current.scrollY != previous.scrollY ||
            current.fromIndex != previous.fromIndex ||
            current.toIndex != previous.toIndex
        return verticalMoved && !horizontalMoved
    }

    private companion object {
        val FEED_DETECTION_EVENT_TYPES = setOf(
            AccessibilityEvent.TYPE_VIEW_SCROLLED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        )
        const val CHECKPOINT_INTERVAL_MILLIS = 10_000L
        const val SCROLL_DEBOUNCE_MILLIS = 750L
        const val FEED_EXIT_GRACE_MILLIS = 2_500L
        const val MAX_COMBINED_LABEL_LENGTH = 1_800
    }

    private data class LegacyScrollPosition(
        val packageName: String?,
        val className: String?,
        val fromIndex: Int,
        val toIndex: Int,
        val scrollX: Int,
        val scrollY: Int
    )
}

enum class FeedPlatform(val packageName: String, val label: String) {
    TIKTOK("com.zhiliaoapp.musically", "TikTok"),
    YOUTUBE("com.google.android.youtube", "YouTube"),
    INSTAGRAM("com.instagram.android", "Instagram"),
    FACEBOOK("com.facebook.katana", "Facebook")
}

object FeedDetector {
    private val youtubeShortsPlayerTerms = setOf(
        "shorts player",
        "remix this short",
        "see more videos using this sound"
    )
    private val instagramReelsViewerTerms = setOf(
        "reel by",
        "reels viewer"
    )

    fun platformFor(packageName: String): FeedPlatform? =
        FeedPlatform.entries.firstOrNull { it.packageName == packageName }

    fun isShortFormFeed(root: AccessibilityNodeInfo?, platform: FeedPlatform): Boolean {
        if (root == null) return false
        return isShortFormFeed(visibleLabels(root), platform)
    }

    fun isShortFormFeed(visibleLabels: String, platform: FeedPlatform): Boolean {
        val normalized = visibleLabels.lowercase()
        return when (platform) {
            FeedPlatform.TIKTOK -> normalized.contains("for you") ||
                normalized.contains("following") || normalized.contains("tiktok") ||
                normalized.contains("fyp")
            FeedPlatform.YOUTUBE -> normalized.contains("shorts") &&
                youtubeShortsPlayerTerms.any(normalized::contains)
            FeedPlatform.INSTAGRAM -> instagramReelsViewerTerms.any(normalized::contains)
            FeedPlatform.FACEBOOK -> normalized.contains("reels") ||
                normalized.contains("video player")
        }
    }

    fun feedMatch(visibleLabels: String, platform: FeedPlatform): String? {
        val normalized = visibleLabels.lowercase()
        val platformTerms = when (platform) {
            FeedPlatform.TIKTOK -> listOf("for you", "following", "tiktok", "fyp")
            FeedPlatform.YOUTUBE -> youtubeShortsPlayerTerms.toList()
            FeedPlatform.INSTAGRAM -> instagramReelsViewerTerms.toList()
            FeedPlatform.FACEBOOK -> listOf("reels", "video player")
        }
        return platformTerms.firstOrNull(normalized::contains)
    }

    fun visibleLabels(root: AccessibilityNodeInfo?): String {
        if (root == null) return ""
        val text = StringBuilder()
        collectText(root, text, 0)
        return text.toString().trim().take(MAX_DIAGNOSTIC_LABEL_LENGTH)
    }

    private fun collectText(node: AccessibilityNodeInfo, output: StringBuilder, depth: Int) {
        if (depth > 20 || output.length >= MAX_DIAGNOSTIC_LABEL_LENGTH) return
        node.text?.let { output.append(' ').append(it) }
        node.contentDescription?.let { output.append(' ').append(it) }
        for (index in 0 until node.childCount) {
            if (output.length >= MAX_DIAGNOSTIC_LABEL_LENGTH) break
            node.getChild(index)?.let { child ->
                collectText(child, output, depth + 1)
                child.recycle()
            }
        }
    }

    private const val MAX_DIAGNOSTIC_LABEL_LENGTH = 1_200
}
