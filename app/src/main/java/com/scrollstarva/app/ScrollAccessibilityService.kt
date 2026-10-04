package com.scrollstarva.app

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.content.Intent
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.NumberPicker
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
    private var trackingActivityId: String? = null
    private var lastWindowStatePackage: String? = null
    private var trackingStartedAtElapsed = 0L
    private var trackingStartedAtWall = 0L
    private var feedStartedAtElapsed = 0L
    private var lastCheckpointElapsed = 0L
    private var sessionScrollCount = 0
    private var lastScrollAt = 0L
    private var lastLegacyScrollPosition: LegacyScrollPosition? = null
    private var marathonReminderShown = false
    private var interventionView: android.view.View? = null
    private val delayedStop = Runnable { stopTracking() }
    private val focusReminderRunnable = Runnable {
        if (repository.isFocusReminderDue()) {
            repository.markFocusReminderShown()
            showFocusInterruptionPrompt()
        }
    }

    private val flushTimer = object : Runnable {
        override fun run() {
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
        scheduleFocusReminder()
        handler.postDelayed(flushTimer, CHECKPOINT_INTERVAL_MILLIS)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString()
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (packageName == this.packageName || packageName?.let(FeedDetector::platformFor) == null) {
                lastWindowStatePackage = null
                if (trackingPackage != null) scheduleStopTracking()
                return
            }
            if (lastWindowStatePackage == packageName) return
            lastWindowStatePackage = packageName
        }

        if (packageName == this.packageName) return

        val platform = packageName?.let(FeedDetector::platformFor)
        if (platform == null) {
            return
        }

        val focusSession = repository.activeFocusSession()
        if (focusSession != null) {
            if (!focusSession.isPaused || focusSession.reminderAtMillis == 0L) {
                if (trackingPackage != null) stopTracking()
                showFocusInterruptionPrompt()
                return
            }
        }

        val continuingTrackedFeed = event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED &&
            trackingPackage == platform.packageName
        val sourceLabels = if (continuingTrackedFeed) "" else FeedDetector.visibleLabels(event.source)
        val windowLabels = if (continuingTrackedFeed) "" else FeedDetector.visibleLabels(rootInActiveWindow)
        val visibleLabels = listOf(sourceLabels, windowLabels)
            .filter(String::isNotBlank)
            .distinct()
            .joinToString(" | ")
            .take(MAX_COMBINED_LABEL_LENGTH)
        val feedVisible = continuingTrackedFeed ||
            (event.eventType in FEED_DETECTION_EVENT_TYPES &&
                FeedDetector.isShortFormFeed(visibleLabels, platform))
        val feedMatch = if (continuingTrackedFeed) null else FeedDetector.feedMatch(visibleLabels, platform)
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            AccessibilityDiagnostics.record(
                "Package: $packageName\n" +
                    "Event: ${eventTypeName(event.eventType)} (${event.eventType})\n" +
                    "Platform: ${platform.label}\n" +
                    "Feed visible: $feedVisible\n" +
                    "Matching feed label: ${feedMatch ?: "none"}\n" +
                    "Visible labels: ${visibleLabels.ifBlank { "(none exposed by this event)" }}"
            )
        }

        if (!feedVisible && !continuingTrackedFeed) {
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                AccessibilityDiagnostics.record("Decision: schedule tracking stop in ${FEED_EXIT_GRACE_MILLIS}ms (feed not detected)")
                scheduleStopTracking()
            }
            return
        }

        handler.removeCallbacks(delayedStop)
        startTracking(platform)
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED && isVerticalScroll(event)) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastScrollAt > SCROLL_DEBOUNCE_MILLIS) {
                repository.addScroll(platform)
                trackingActivityId?.let(repository::recordSessionScroll)
                sessionScrollCount++
                lastScrollAt = now
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

    private fun startTracking(platform: FeedPlatform) {
        if (trackingPackage == platform.packageName) return
        stopTracking()

        val nowWall = System.currentTimeMillis()
        val session = repository.beginSession(platform, nowWall)
        trackingPackage = platform.packageName
        trackingPlatform = platform
        trackingActivityId = session.activityId
        trackingStartedAtElapsed = SystemClock.elapsedRealtime()
        feedStartedAtElapsed = trackingStartedAtElapsed
        trackingStartedAtWall = nowWall
        lastCheckpointElapsed = trackingStartedAtElapsed
        sessionScrollCount = 0
        lastScrollAt = 0L
        lastLegacyScrollPosition = null
        marathonReminderShown = false

        AccessibilityDiagnostics.record(
            "Session started\nApp: ${platform.label}\n" +
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
                "Session ended\nApp: ${trackingPlatform?.label ?: trackingPackage}\n" +
                    "Duration: ${duration / 1000}s\nScrolls recorded: $sessionScrollCount"
            )
        }
        trackingPackage = null
        trackingPlatform = null
        trackingActivityId = null
        trackingStartedAtElapsed = 0L
        trackingStartedAtWall = 0L
        feedStartedAtElapsed = 0L
        lastCheckpointElapsed = 0L
        sessionScrollCount = 0
        marathonReminderShown = false
    }

    private fun scheduleStopTracking() {
        handler.removeCallbacks(delayedStop)
        handler.postDelayed(delayedStop, FEED_EXIT_GRACE_MILLIS)
    }

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
        val platform = trackingPlatform ?: return
        val activityId = trackingActivityId ?: return
        val nowWall = System.currentTimeMillis()
        val todayStart = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val priorDuration = maxOf(0L, todayStart - trackingStartedAtWall)
        repository.finishSession(activityId, todayStart, priorDuration, sessionScrollCount)

        val elapsedSinceMidnight = maxOf(0L, nowWall - todayStart)
        val newStartElapsed = nowElapsed - elapsedSinceMidnight
        val newSession = repository.beginSession(platform, todayStart, countAsReentry = false)
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
        val marathonMillis = repository.marathonMinutes() * 60_000L
        if (nowElapsed - feedStartedAtElapsed < marathonMillis ||
            marathonReminderShown ||
            repository.areBreakRemindersMuted()
        ) {
            return
        }
        marathonReminderShown = true
        showMarathonReminder()
    }

    private fun showMarathonReminder() {
        showBreakPrompt(maxOf(0L, SystemClock.elapsedRealtime() - feedStartedAtElapsed))
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
                prompt.addView(breakPicker)
            },
            actions = listOf(
                "End focus early" to {
                    handler.removeCallbacks(focusReminderRunnable)
                    repository.endFocusSession()
                    removeIntervention()
                },
                "Take this scroll break" to {
                    repository.setFocusBreakMinutes(selectedBreakMinutes)
                    if (repository.pauseFocusForScrollBreak(selectedBreakMinutes * 60_000L) != null) {
                        scheduleFocusReminder()
                    }
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

    private fun showBreakPrompt(elapsedMillis: Long) {
        val minutes = elapsedMillis / 60_000L
        val durationPicker = FocusDurationPicker(this, TrackingTimerSettings.FOCUS_SESSION_MILLIS)
        val snoozeOptions = TrackingTimerSettings.BREAK_REMINDER_SNOOZE_OPTIONS_MILLIS
        var selectedSnoozeIndex = 0
        showPromptCard(
            title = "A gentle pause",
            message = "You’ve been scrolling for $minutes minutes. A short break can help you return with a clearer mind.",
            quote = FocusSessionQuotes.random(),
            extraContent = { prompt ->
                prompt.addView(TextView(this).apply {
                    text = "Focus duration (up to 8 hours)"
                    textSize = 14f
                    setTextColor(Color.parseColor("#65727E"))
                    setPadding(0, dp(4), 0, 0)
                })
                prompt.addView(durationPicker)
                val snoozeLabel = TextView(this).apply {
                    text = snoozeDescription(snoozeOptions[selectedSnoozeIndex])
                    textSize = 14f
                    setTextColor(Color.parseColor("#65727E"))
                    setPadding(0, dp(4), 0, 0)
                }
                prompt.addView(snoozeLabel)
                prompt.addView(NumberPicker(this).apply {
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
                }, LinearLayout.LayoutParams(-1, dp(96)))
            },
            actions = listOf(
                "Start focus" to { startFocusSession(durationPicker.durationMillis) },
                "Mute break reminders" to {
                    repository.muteBreakReminders(
                        TrackingTimerSettings.BREAK_REMINDER_SNOOZE_OPTIONS_MILLIS[selectedSnoozeIndex]
                    )
                    marathonReminderShown = false
                    removeIntervention()
                },
                "Maybe later" to { removeIntervention() }
            )
        )
    }

    private fun snoozeDescription(durationMillis: Long): String =
        "Don’t bother me for the next ${snoozeWheelLabel(durationMillis)}."

    private fun snoozeWheelLabel(durationMillis: Long): String =
        if (durationMillis < 60 * 60_000L) {
            "${durationMillis / 60_000L} min"
        } else {
            "${durationMillis / (60 * 60_000L)} hour${if (durationMillis >= 2 * 60 * 60_000L) "s" else ""}"
        }


    private fun showPromptCard(
        title: String,
        message: String,
        quote: String,
        extraContent: ((LinearLayout) -> Unit)?,
        actions: List<Pair<String, () -> Unit>>
    ) {
        removeIntervention()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(16))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#FFF8EF"))
                cornerRadius = dp(22).toFloat()
                setStroke(dp(1), Color.parseColor("#F1D8C5"))
            }
            elevation = dp(12).toFloat()
        }
        root.addView(TextView(this).apply {
            text = title
            textSize = 20f
            setTextColor(Color.parseColor("#17202A"))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = message
            textSize = 15f
            setTextColor(Color.parseColor("#65727E"))
            setPadding(0, dp(8), 0, dp(6))
        })
        root.addView(TextView(this).apply {
            text = "“$quote”"
            textSize = 14f
            setTextColor(Color.parseColor("#C94D43"))
            setPadding(0, dp(2), 0, dp(12))
        })
        extraContent?.invoke(root)
        actions.forEachIndexed { index, (actionText, action) ->
            root.addView(Button(this).apply {
                text = actionText
                isAllCaps = false
                if (index == 0) {
                    setTextColor(Color.WHITE)
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#F26B5E"))
                        cornerRadius = dp(12).toFloat()
                    }
                }
                setOnClickListener { action() }
            })
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            val margin = dp(16)
            x = margin
            y = dp(24)
        }
        windowManager.addView(root, params)
        interventionView = root
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
        performGlobalAction(GLOBAL_ACTION_HOME)
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        )
    }

    private fun removeIntervention() {
        interventionView?.let { view ->
            windowManager.removeView(view)
            interventionView = null
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun formatMinutes(millis: Long): String {
        val minutes = (millis + 59_999L) / 60_000L
        return "$minutes ${if (minutes == 1L) "minute" else "minutes"}"
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
