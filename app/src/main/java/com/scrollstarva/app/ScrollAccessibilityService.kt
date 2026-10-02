package com.scrollstarva.app

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.LinearLayout
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
    private var trackingStartedAtElapsed = 0L
    private var trackingStartedAtWall = 0L
    private var feedStartedAtElapsed = 0L
    private var lastCheckpointElapsed = 0L
    private var sessionScrollCount = 0
    private var lastScrollAt = 0L
    private var marathonReminderShown = false
    private var interventionView: android.view.View? = null
    private var countdownRunnable: Runnable? = null
    private val delayedStop = Runnable { stopTracking() }

    private val flushTimer = object : Runnable {
        override fun run() {
            checkpointAndCheckInterventions()
            handler.postDelayed(this, CHECKPOINT_INTERVAL_MILLIS)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        repository.finishInterruptedSessions()
        AccessibilityDiagnostics.record("Accessibility service connected and tracking enabled")
        handler.postDelayed(flushTimer, CHECKPOINT_INTERVAL_MILLIS)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString()
        if (packageName == this.packageName) return

        val platform = packageName?.let(FeedDetector::platformFor)
        if (platform == null) {
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                scheduleStopTracking()
            }
            return
        }

        val sourceLabels = FeedDetector.visibleLabels(event.source)
        val windowLabels = FeedDetector.visibleLabels(rootInActiveWindow)
        val visibleLabels = listOf(sourceLabels, windowLabels)
            .filter(String::isNotBlank)
            .distinct()
            .joinToString(" | ")
            .take(MAX_COMBINED_LABEL_LENGTH)
        val feedVisible = when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_SCROLLED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ->
                FeedDetector.isShortFormFeed(visibleLabels, platform)
            else -> false
        }
        val feedMatch = FeedDetector.feedMatch(visibleLabels, platform)
        AccessibilityDiagnostics.record(
            "Package: $packageName\n" +
                "Event: ${eventTypeName(event.eventType)} (${event.eventType})\n" +
                "Source class: ${event.className ?: "(unknown)"}\n" +
                "Platform: ${platform.label}\n" +
                "Feed detection evaluated: ${event.eventType in FEED_DETECTION_EVENT_TYPES}\n" +
                "Feed visible: $feedVisible\n" +
                "Matching feed label: ${feedMatch ?: "none"}\n" +
                "Scroll delta: ${scrollDeltaDescription(event)}\n" +
                "Event source labels: ${sourceLabels.ifBlank { "(none)" }}\n" +
                "Active window labels: ${windowLabels.ifBlank { "(none)" }}\n" +
                "Visible labels: ${visibleLabels.ifBlank { "(none exposed by this event)" }}"
        )

        if (!feedVisible) {
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
            } else {
                AccessibilityDiagnostics.record(
                    "Scroll decision: IGNORED by debounce\n" +
                        "Package: $packageName\n" +
                        "Delta: ${scrollDeltaDescription(event)}\n" +
                        "Elapsed since previous count: ${now - lastScrollAt}ms; threshold: ${SCROLL_DEBOUNCE_MILLIS}ms"
                )
            }
        } else if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            AccessibilityDiagnostics.record(
                "Scroll decision: IGNORED as non-vertical\n" +
                    "Package: $packageName\n" +
                    "Delta: ${scrollDeltaDescription(event)}"
            )
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
        marathonReminderShown = false

        AccessibilityDiagnostics.record(
            "Session started\nApp: ${platform.label}\n" +
                "Rapid re-entry for this app: ${session.isRapidReentry}"
        )
        if (session.isRapidReentry) showPauseOverlay()
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
        removeIntervention()
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

        if (nowElapsed - feedStartedAtElapsed >= MARATHON_MILLIS && !marathonReminderShown) {
            marathonReminderShown = true
            showMarathonReminder()
        }

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
        if (newSession.isRapidReentry) showPauseOverlay()
        if (nowElapsed - feedStartedAtElapsed >= MARATHON_MILLIS && !marathonReminderShown) {
            marathonReminderShown = true
            showMarathonReminder()
        }
    }

    private fun showPauseOverlay() {
        showIntervention(
            title = "Pause before reopening",
            message = "Take one slow breath. This app was opened again within five minutes.",
            countdownSeconds = COOLING_PAUSE_SECONDS
        )
    }

    private fun showMarathonReminder() {
        showIntervention(
            title = "Time for a short break?",
            message = "You have been in this feed for 20 minutes. Look away, stretch, or take a short walk.",
            countdownSeconds = 0
        )
    }

    private fun showIntervention(title: String, message: String, countdownSeconds: Int) {
        removeIntervention()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(32), dp(32), dp(32), dp(32))
            setBackgroundColor(Color.argb(238, 23, 32, 42))
        }
        root.addView(TextView(this).apply {
            text = title
            textSize = 25f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = message
            textSize = 17f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(0, dp(18), 0, dp(20))
        })

        val countdown = TextView(this).apply {
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
        }
        root.addView(countdown)
        if (countdownSeconds == 0) {
            root.addView(Button(this).apply {
                text = "Close reminder"
                setOnClickListener { removeIntervention() }
            })
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        windowManager.addView(root, params)
        interventionView = root

        if (countdownSeconds > 0) {
            var remaining = countdownSeconds
            val runnable = object : Runnable {
                override fun run() {
                    if (remaining > 0) {
                        countdown.text = "Continuing in $remaining seconds"
                        remaining--
                        handler.postDelayed(this, 1_000)
                    } else {
                        removeIntervention()
                    }
                }
            }
            countdownRunnable = runnable
            runnable.run()
        }
    }

    private fun removeIntervention() {
        countdownRunnable?.let(handler::removeCallbacks)
        countdownRunnable = null
        interventionView?.let { view ->
            windowManager.removeView(view)
            interventionView = null
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun isVerticalScroll(event: AccessibilityEvent): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            event.scrollDeltaY != 0 || event.scrollDeltaX == 0
        } else {
            event.fromIndex != event.toIndex || event.scrollX == 0
        }

    private companion object {
        val FEED_DETECTION_EVENT_TYPES = setOf(
            AccessibilityEvent.TYPE_VIEW_SCROLLED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        )
        const val CHECKPOINT_INTERVAL_MILLIS = 10_000L
        const val SCROLL_DEBOUNCE_MILLIS = 250L
        const val MARATHON_MILLIS = 20 * 60 * 1000L
        const val COOLING_PAUSE_SECONDS = 10
        const val FEED_EXIT_GRACE_MILLIS = 2_500L
        const val MAX_COMBINED_LABEL_LENGTH = 1_800
    }
}

enum class FeedPlatform(val packageName: String, val label: String) {
    TIKTOK("com.zhiliaoapp.musically", "TikTok"),
    YOUTUBE("com.google.android.youtube", "YouTube"),
    INSTAGRAM("com.instagram.android", "Instagram"),
    FACEBOOK("com.facebook.katana", "Facebook")
}

object FeedDetector {
    private val feedTerms = setOf(
        "shorts", "shorts player", "reels", "reel", "tiktok",
        "for you", "fyp", "videos", "video player"
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
                normalized.contains("following") || normalized.contains("tiktok")
            FeedPlatform.YOUTUBE -> normalized.contains("shorts") ||
                normalized.contains("shorts player")
            FeedPlatform.INSTAGRAM -> normalized.contains("reels") ||
                normalized.contains("reel")
            FeedPlatform.FACEBOOK -> normalized.contains("reels") ||
                normalized.contains("video player")
        } || feedTerms.any(normalized::contains)
    }

    fun feedMatch(visibleLabels: String, platform: FeedPlatform): String? {
        val normalized = visibleLabels.lowercase()
        val platformTerms = when (platform) {
            FeedPlatform.TIKTOK -> listOf("for you", "following", "tiktok")
            FeedPlatform.YOUTUBE -> listOf("shorts player", "shorts")
            FeedPlatform.INSTAGRAM -> listOf("reels", "reel")
            FeedPlatform.FACEBOOK -> listOf("reels", "video player")
        }
        return (platformTerms + feedTerms).firstOrNull(normalized::contains)
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
