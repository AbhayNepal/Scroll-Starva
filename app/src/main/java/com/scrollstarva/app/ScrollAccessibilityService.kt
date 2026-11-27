package com.scrollstarva.app

import android.accessibilityservice.AccessibilityService
import android.animation.ValueAnimator
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
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import java.time.LocalDate
import java.time.ZoneId

class ScrollAccessibilityService : AccessibilityService() {
    private val repository by lazy { TrackingRepository(this) }
    private val windowManager by lazy {
        checkNotNull(getSystemService(WindowManager::class.java)) { "WindowManager is unavailable" }
    }
    private val handler = Handler(Looper.getMainLooper())
    private var trackingPackage: String? = null
    private var trackingAppLabel: String? = null
    private var trackingActivityId: String? = null
    private var trackingStartedAtElapsed = 0L
    private var trackingStartedAtWall = 0L
    private var lastEventElapsed = SystemClock.elapsedRealtime()
    private var isScreenOffReceiverRegistered = false
    private val screenOffReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    AccessibilityDiagnostics.record("Screen turned off -> stopping active session")
                    stopTracking()
                    removeIntervention()
                }
                // Coming back to a tracked app after unlocking often sends no window event, so
                // look at the foreground app instead of waiting for the first scroll.
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT ->
                    handler.postDelayed(resumeTrackingIfForeground, SCREEN_ON_SETTLE_MILLIS)
            }
        }
    }
    private val resumeTrackingIfForeground = Runnable {
        val active = activeWindowPackage()
        if (active != null && active != packageName && active in repository.selectedPackages()) {
            startTracking(active)
        }
    }
    private val systemOverlayPackages = setOf(
        "com.android.systemui",
        "android",
        "com.samsung.android.biometrics.app.setting",
        "com.samsung.android.honeyboard",
        "com.google.android.inputmethod.latin"
    )
    private var sessionScrollCount = 0
    private var lastScrollEventAt = 0L
    private var lastScrollDeltaY = 0
    private var lastPagerCheckAt = 0L
    private var currentPagerPage: AccessibilityNodeInfo? = null
    private var pendingStopElapsed = 0L
    private var lastInAppElapsed = 0L
    private var lastInAppWall = 0L
    private var pendingStopWall = 0L
    private var lastLegacyScrollPosition: LegacyScrollPosition? = null
    private var marathonReminderShown = false
    private var lastMarathonNarration: String? = null
    private var lastLimitNarration: String? = null
    private var lastFocusNarration: String? = null
    private var lastPromptShownAtElapsed = 0L
    private var interventionView: android.view.View? = null
    private val delayedStop = Runnable {
        // End the session when the user left, not when the grace period ran out.
        val endElapsed = pendingStopElapsed
        val endWall = pendingStopWall
        pendingStopElapsed = 0L
        if (endElapsed > 0L) stopTracking(endElapsed, endWall) else stopTracking()
    }
    private val repeatMarathonReminderRunnable = Runnable {
        showPendingMarathonReminderIfDue()
    }
    private val focusReminderRunnable = Runnable {
        if (repository.isFocusReminderDue()) {
            repository.markFocusReminderShown()
            if (isTrackedAppForeground()) {
                showFocusInterruptionPrompt()
            } else {
                removeIntervention()
                showFocusResumedNotification()
            }
        }
    }

    private val flushTimer = object : Runnable {
        override fun run() {
            repository.markMonitoredToday()
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
        try {
            if (!isScreenOffReceiverRegistered) {
                try {
                    registerReceiver(screenOffReceiver, android.content.IntentFilter().apply {
                        addAction(Intent.ACTION_SCREEN_OFF)
                        addAction(Intent.ACTION_SCREEN_ON)
                        addAction(Intent.ACTION_USER_PRESENT)
                    })
                    isScreenOffReceiverRegistered = true
                } catch (_: Exception) {}
            }
            updatePackageFilters()
            repository.finishInterruptedSessions()
            AccessibilityDiagnostics.record("Accessibility service connected and tracking enabled")
            val activeWindow = try { rootInActiveWindow } catch (_: Exception) { null }
            val activePackageName = activeWindow?.packageName?.toString()
            if (activePackageName != null && activePackageName in repository.selectedPackages()) {
                // A service (re)start is not the user re-opening the app.
                startTracking(activePackageName, countAsReentry = false)
                showPendingMarathonReminderIfDue()
            }
            scheduleFocusReminder()
            schedulePendingMarathonReminder()
            handler.removeCallbacks(flushTimer)
            handler.postDelayed(flushTimer, CHECKPOINT_INTERVAL_MILLIS)
        } catch (e: Exception) {
            AccessibilityDiagnostics.record("Error in onServiceConnected: ${e.message}")
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        lastEventElapsed = SystemClock.elapsedRealtime()
        val packageName = event.packageName?.toString()

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            updatePackageFilters()
        }

        if (packageName == null || packageName == this.packageName || packageName !in repository.selectedPackages()) {
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && packageName !in systemOverlayPackages) {
                val activePkg = rootInActiveWindow?.packageName?.toString()
                if (activePkg == null || (activePkg !in repository.selectedPackages() && activePkg !in systemOverlayPackages) || activePkg == this.packageName) {
                    if (trackingPackage != null) scheduleStopTracking()
                    removeIntervention()
                }
            }
            return
        }
        // Content changes (e.g. a playing video) never start a session; they're only used to spot
        // Shorts-style page swaps in the app already being tracked.
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            if (packageName == trackingPackage) checkPagerPage(packageName)
            return
        }

        // Apps can send window events while in the background (e.g. as they close behind the
        // launcher). Only treat one as "the user is here" if that app is actually in front; if the
        // window isn't settled yet, look again shortly.
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val active = activeWindowPackage()
            if (active != null && active != packageName && active !in systemOverlayPackages) {
                handler.removeCallbacks(resumeTrackingIfForeground)
                handler.postDelayed(resumeTrackingIfForeground, FOREGROUND_RECHECK_MILLIS)
                return
            }
        }
        if (packageName == trackingPackage) markInApp()

        val focusSession = repository.activeFocusSession()
        if (focusSession != null) {
            if (!focusSession.isPaused) {
                startTracking(packageName)
                showFocusInterruptionPrompt()
            } else if (focusSession.reminderAtMillis > 0L && System.currentTimeMillis() >= focusSession.reminderAtMillis) {
                repository.resumeFocusSession()
                startTracking(packageName)
                showFocusInterruptionPrompt()
            }
            // On scroll break (focusSession.isPaused) -> allow browsing during break without prompt.
            // Either way the time and scrolls below are recorded like any other use of the app.
        }

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            recordWindowDiagnostics(event, packageName)
        }

        // Every moment in a tracked app counts as screen time and every vertical scroll gesture
        // in it counts as a scroll, whichever screen of the app it happens on.
        cancelPendingStop()
        startTracking(packageName)
        showPendingMarathonReminderIfDue()
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED && isVerticalScroll(event)) {
            countScrollGesture(packageName, event)
        }
    }

    /**
     * Some full-screen video pagers (YouTube Shorts) swap pages without sending scroll events.
     * Each page is its own view, so a different page filling the screen means one swipe.
     */
    private fun checkPagerPage(packageName: String) {
        val pagerId = FeedDetector.platformFor(packageName)?.pagerViewId ?: return
        val now = SystemClock.elapsedRealtime()
        if (now - lastPagerCheckAt < PAGER_CHECK_INTERVAL_MILLIS) return
        lastPagerCheckAt = now
        val root = try { rootInActiveWindow } catch (_: Exception) { null } ?: return
        if (root.packageName?.toString() != packageName) return
        val pager = try { root.findAccessibilityNodeInfosByViewId(pagerId).firstOrNull() } catch (_: Exception) { null }
        if (pager == null) {
            currentPagerPage = null
            return
        }
        val screenHeight = resources.displayMetrics.heightPixels
        val bounds = android.graphics.Rect()
        var page: AccessibilityNodeInfo? = null
        var pageHeight = 0
        for (i in 0 until pager.childCount) {
            val child = try { pager.getChild(i) } catch (_: Exception) { null } ?: continue
            child.getBoundsInScreen(bounds)
            val visible = (minOf(bounds.bottom, screenHeight) - maxOf(bounds.top, 0)).coerceAtLeast(0)
            if (visible > pageHeight) {
                page = child
                pageHeight = visible
            }
        }
        // Only a settled page (mostly on screen) counts, so a half-finished swipe isn't counted.
        if (page == null || pageHeight < screenHeight / 2) return
        val previous = currentPagerPage
        currentPagerPage = page
        if (previous != null && previous != page) {
            sessionScrollCount++
            trackingActivityId?.let(repository::recordSessionScroll)
            maybeShowDailyLimitPrompt()
            AccessibilityDiagnostics.record("Scroll decision: COUNTED\nPackage: $packageName\nPager page changed")
        }
    }

    /**
     * Counts one scroll per gesture. A swipe or fling reports a stream of scroll events (every
     * ~100 ms) whose distance shrinks as it slows down, so a long fling through a list counts
     * once, the same as one snap in a short-video feed. A new gesture starts when:
     * - the stream has been quiet for [SCROLL_GESTURE_GAP_MILLIS], or
     * - the direction flips, or
     * - the distance jumps back up after a fling's slow tail (the user swiped again before the
     *   previous fling stopped, which leaves almost no quiet gap).
     */
    private fun countScrollGesture(packageName: String, event: AccessibilityEvent) {
        val now = SystemClock.elapsedRealtime()
        val delta = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) event.scrollDeltaY else 0
        val previous = lastScrollDeltaY
        val quietGap = now - lastScrollEventAt > SCROLL_GESTURE_GAP_MILLIS
        val directionFlipped = delta != 0 && previous != 0 && (delta > 0) != (previous > 0)
        val freshSwipe = kotlin.math.abs(previous) < dp(GESTURE_TAIL_DP) &&
            kotlin.math.abs(delta) >= dp(GESTURE_SWIPE_DP) &&
            kotlin.math.abs(delta) >= kotlin.math.abs(previous) * 3
        lastScrollEventAt = now
        lastScrollDeltaY = delta
        if (!quietGap && !directionFlipped && !freshSwipe) return
        trackingActivityId?.let(repository::recordSessionScroll)
        sessionScrollCount++
        maybeShowDailyLimitPrompt()
        AccessibilityDiagnostics.record(
            "Scroll decision: COUNTED\n" +
                "Package: $packageName\n" +
                "Delta: ${scrollDeltaDescription(event)}\n" +
                "Gesture gap: passed (> ${SCROLL_GESTURE_GAP_MILLIS}ms quiet)"
        )
    }

    private fun recordWindowDiagnostics(event: AccessibilityEvent, packageName: String) {
        val platform = FeedDetector.platformFor(packageName)
        val visibleLabels = listOf(
            FeedDetector.visibleLabels(event.source),
            FeedDetector.visibleLabels(rootInActiveWindow)
        )
            .filter(String::isNotBlank)
            .distinct()
            .joinToString(" | ")
            .take(MAX_COMBINED_LABEL_LENGTH)
        val feedMatch = platform?.let { FeedDetector.feedMatch(visibleLabels, it) }
        AccessibilityDiagnostics.record(
            "Package: $packageName\n" +
                "Event: ${eventTypeName(event.eventType)} (${event.eventType})\n" +
                "App: ${resolveAppLabel(packageName)}\n" +
                "Short-form feed visible: ${feedMatch != null}\n" +
                "Matching feed label: ${feedMatch ?: "none"}\n" +
                "Visible labels: ${visibleLabels.ifBlank { "(none exposed by this event)" }}"
        )
    }

    override fun onInterrupt() = stopTracking()

    override fun onDestroy() {
        stopTracking()
        removeIntervention()
        if (isScreenOffReceiverRegistered) {
            try {
                unregisterReceiver(screenOffReceiver)
                isScreenOffReceiverRegistered = false
            } catch (_: Exception) {}
        }
        handler.removeCallbacks(delayedStop)
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun startTracking(packageName: String, countAsReentry: Boolean = true) {
        if (trackingPackage == packageName) {
            cancelPendingStop()
            return
        }
        stopTracking()

        val nowWall = System.currentTimeMillis()
        val session = repository.beginSession(packageName, nowWall, countAsReentry)
        trackingPackage = packageName
        trackingAppLabel = resolveAppLabel(packageName)
        trackingActivityId = session.activityId
        trackingStartedAtElapsed = SystemClock.elapsedRealtime()
        trackingStartedAtWall = nowWall
        lastInAppElapsed = trackingStartedAtElapsed
        lastInAppWall = nowWall
        sessionScrollCount = 0
        lastScrollEventAt = 0L
        lastScrollDeltaY = 0
        currentPagerPage = null
        lastLegacyScrollPosition = null
        marathonReminderShown = false

        AccessibilityDiagnostics.record(
            "Session started\nApp: $trackingAppLabel\n" +
                "Rapid re-entry for this app: ${session.isRapidReentry}"
        )
//        if (session.isRapidReentry) showPauseOverlay()
    }

    /**
     * Ends the current session. [endElapsed]/[endWall] let a caller end it at the moment the user
     * actually left (e.g. before a grace period or a stretch with no foreground signal).
     */
    private fun stopTracking(
        endElapsed: Long = SystemClock.elapsedRealtime(),
        endWall: Long = System.currentTimeMillis()
    ) {
        cancelPendingStop()
        val activityId = trackingActivityId
        if (activityId != null) {
            val duration = maxOf(0L, endElapsed - trackingStartedAtElapsed)
            repository.finishSession(
                activityId,
                maxOf(endWall, trackingStartedAtWall),
                duration,
                sessionScrollCount
            )
            AccessibilityDiagnostics.record(
                "Session ended\nApp: ${trackingAppLabel ?: trackingPackage}\n" +
                    "Duration: ${duration / 1000}s\nScrolls recorded: $sessionScrollCount"
            )
        }
        trackingPackage = null
        trackingAppLabel = null
        trackingActivityId = null
        trackingStartedAtElapsed = 0L
        trackingStartedAtWall = 0L
        sessionScrollCount = 0
        marathonReminderShown = false
    }

    private fun scheduleStopTracking() {
        if (pendingStopElapsed > 0L) return  // already leaving; keep the original leave time
        pendingStopElapsed = SystemClock.elapsedRealtime()
        pendingStopWall = System.currentTimeMillis()
        handler.postDelayed(delayedStop, FEED_EXIT_GRACE_MILLIS)
    }

    /** Notes that the tracked app was just seen in use (an event from it, or it's in front). */
    private fun markInApp() {
        lastInAppElapsed = SystemClock.elapsedRealtime()
        lastInAppWall = System.currentTimeMillis()
    }

    private fun cancelPendingStop() {
        handler.removeCallbacks(delayedStop)
        pendingStopElapsed = 0L
    }

    private fun activeWindowPackage(): String? =
        try { rootInActiveWindow?.packageName?.toString() } catch (_: Exception) { null }

    private fun updatePackageFilters() {
        val info = serviceInfo ?: return
        if (info.packageNames != null) {
            info.packageNames = null
            this.serviceInfo = info
        }
    }

    private fun showFocusResumedNotification() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notificationManager = getSystemService(NotificationManager::class.java)
        if (notificationManager != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    FOCUS_NOTIFICATION_CHANNEL_ID,
                    "Focus & Reminders",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Notifications for focus session status and reminders"
                    enableVibration(true)
                }
                notificationManager.createNotificationChannel(channel)
            }

            val notification = Notification.Builder(this, FOCUS_NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("Focus Resumed 🎯")
                .setContentText("Your scroll break has ended and your focus session is back on track.")
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build()

            try {
                notificationManager.notify(FOCUS_RESUMED_NOTIFICATION_ID, notification)
            } catch (_: Exception) {}
        }

        try {
            startActivity(intent)
        } catch (_: Exception) {}

        handler.post {
            Toast.makeText(
                this,
                "Focus Resumed: Your scroll break is complete.",
                Toast.LENGTH_SHORT
            ).show()
        }
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

        // Watching a video sends no events, so silence alone doesn't mean the user left. Ask which
        // app is in front; only fall back to an idle timeout when that can't be determined.
        val activePackage = activeWindowPackage()
        when {
            activePackage == trackingPackage -> markInApp()
            activePackage != null && activePackage !in systemOverlayPackages -> {
                // The switch was missed; end the session when the app was last seen in use.
                AccessibilityDiagnostics.record("$activePackage is in front -> stopping active session")
                if (pendingStopElapsed > 0L) stopTracking(pendingStopElapsed, pendingStopWall)
                else stopTracking(lastInAppElapsed, lastInAppWall)
                return
            }
            nowElapsed - lastEventElapsed > INACTIVITY_TIMEOUT_MILLIS -> {
                AccessibilityDiagnostics.record("No foreground signal or events -> stopping active session")
                val idleFor = nowElapsed - lastEventElapsed
                stopTracking(lastEventElapsed, System.currentTimeMillis() - idleFor)
                return
            }
        }
        val duration = maxOf(0L, nowElapsed - trackingStartedAtElapsed)
        repository.checkpointSession(
            activityId,
            System.currentTimeMillis(),
            duration,
            sessionScrollCount
        )

        maybeShowDailyLimitPrompt()
        maybeShowMarathonReminder(nowElapsed)

        if (LocalDate.now() != java.time.Instant.ofEpochMilli(trackingStartedAtWall)
                .atZone(ZoneId.systemDefault())
                .toLocalDate()
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
        sessionScrollCount = 0
        lastScrollEventAt = 0L
        lastScrollDeltaY = 0
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
            extraContent = { prompt, guide ->
                val maxBreak = TrackingTimerSettings.maxFocusBreakMinutes(repository.marathonMinutes())
                val breakPicker = HorizontalWheelPicker(
                    this,
                    label = "Scroll break (minutes)",
                    minValue = TrackingTimerSettings.MIN_FOCUS_BREAK_MINUTES,
                    maxValue = maxBreak,
                    initialValue = selectedBreakMinutes,
                    swipeDpPerStep = 20f
                )
                val breakTip = {
                    "Swipe here to choose how long your scroll break lasts. Right now: " +
                        "${minutesLabel(selectedBreakMinutes)}, then I’ll nudge you back."
                }
                breakPicker.onValueChanged = {
                    selectedBreakMinutes = it
                    guide.hint(breakPicker, breakPickerTip(it))
                }
                guide.addStop(addPromptSelector(prompt, breakPicker), "Scroll break wheel", breakTip)
                val breakButton = addPromptAction(prompt, "Take this scroll break", Ui.ButtonStyle.PRIMARY) {
                    repository.setFocusBreakMinutes(selectedBreakMinutes)
                    if (repository.pauseFocusForScrollBreak(selectedBreakMinutes * 60_000L) != null) {
                        scheduleFocusReminder()
                    }
                    removeIntervention()
                }
                guide.addStop(breakButton, "Take this scroll break") {
                    "This pauses your focus timer for ${minutesLabel(selectedBreakMinutes)}. Enjoy it guilt-free!"
                }
            },
            actions = listOf(
                PromptAction(
                    "End focus early",
                    "Stops the session now. That’s okay—every minute you focused still counts."
                ) {
                    handler.removeCallbacks(focusReminderRunnable)
                    repository.endFocusSession()
                    removeIntervention()
                },
                PromptAction(
                    "Resume focus",
                    "Takes you straight back to your focus timer. I’ll be right there with you."
                ) {
                    handler.removeCallbacks(focusReminderRunnable)
                    repository.resumeFocusSession()
                    removeIntervention()
                    returnToFocusScreen()
                }
            )
        )
    }

    private fun showBreakPrompt(title: String, narration: String, message: String) {
        Ui.applyTheme(this, repository.appearanceMode())  // before the pickers below are built
        val durationPicker = FocusDurationPicker(
            this,
            repository.defaultFocusMinutes() * 60_000L
        )
        val snoozeOptions = TrackingTimerSettings.BREAK_REMINDER_SNOOZE_OPTIONS_MILLIS
        var selectedSnoozeIndex = 0
        val interval = marathonReminderIntervalLabel()
        showPromptCard(
            title = title,
            narration = narration,
            message = message,
            quote = FocusSessionQuotes.random(),
            extraContent = { prompt, guide ->
                prompt.addView(TextView(this).apply {
                    text = "Focus duration (up to 8 hours)"
                    textSize = 13f
                    typeface = Ui.font(this@ScrollAccessibilityService, 600)
                    setTextColor(Ui.INK_2)
                    setPadding(0, dp(4), 0, 0)
                })
                durationPicker.onDurationChanged = { millis ->
                    guide.hint(durationPicker, focusDurationTip(millis))
                }
                guide.addStop(addPromptSelector(prompt, durationPicker), "Focus duration wheels") {
                    "Swipe the hours and minutes to pick a focus length. " +
                        "It’s set to ${durationLabel(durationPicker.durationMillis)}."
                }
                val focusButton = addPromptAction(prompt, "Start focus", Ui.ButtonStyle.PRIMARY) {
                    startFocusSession(durationPicker.durationMillis)
                }
                guide.addStop(focusButton, "Start focus") {
                    "Starts a ${durationLabel(durationPicker.durationMillis)} focus session and closes " +
                        "this app for you. My favourite option! 🌱"
                }
                val snoozeLabel = TextView(this).apply {
                    text = snoozeDescription(snoozeOptions[selectedSnoozeIndex])
                    textSize = 13f
                    typeface = Ui.font(this@ScrollAccessibilityService, 600)
                    setTextColor(Ui.INK_2)
                    setPadding(0, dp(4), 0, 0)
                }
                val snoozePicker = HorizontalWheelPicker(
                    this,
                    label = "Mute duration",
                    minValue = snoozeOptions.indices.first,
                    maxValue = snoozeOptions.indices.last,
                    initialValue = selectedSnoozeIndex,
                    swipeDpPerStep = 45f,
                    formatValue = { index -> snoozeWheelLabel(snoozeOptions[index]) }
                ).apply {
                    onValueChanged = { index ->
                        selectedSnoozeIndex = index
                        snoozeLabel.text = snoozeDescription(snoozeOptions[index])
                        guide.hint(this, "Okay—I’ll stay quiet for ${snoozeWheelLabel(snoozeOptions[index])} if you mute me. 🤫")
                    }
                }
                guide.addStop(addPromptSelector(prompt, snoozePicker), "Mute duration wheel") {
                    "Need some quiet? Swipe to choose how long I stay away. " +
                        "Currently ${snoozeWheelLabel(snoozeOptions[selectedSnoozeIndex])}."
                }
                prompt.addView(snoozeLabel)
                val muteButton = addPromptAction(prompt, "Mute break reminders", Ui.ButtonStyle.SECONDARY) {
                    repository.muteBreakReminders(snoozeOptions[selectedSnoozeIndex])
                    handler.removeCallbacks(repeatMarathonReminderRunnable)
                    marathonReminderShown = false
                    removeIntervention()
                }
                guide.addStop(muteButton, "Mute break reminders") {
                    "No check-ins from me for ${snoozeWheelLabel(snoozeOptions[selectedSnoozeIndex])}. " +
                        "I’ll tiptoe away. 🐾"
                }
            },
            actions = listOf(
                PromptAction(
                    "Remind me again in $interval",
                    "Keep scrolling for now, and I’ll pop back in after $interval."
                ) {
                    repository.scheduleBreakReminderAgain(marathonReminderIntervalMillis())
                    marathonReminderShown = false
                    removeIntervention()
                    schedulePendingMarathonReminder()
                },
                PromptAction(
                    "Maybe later",
                    "Just closes this card. No worries—I’ll be around."
                ) { removeIntervention() }
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
        minutesLabel(repository.marathonMinutes())

    private fun snoozeWheelLabel(durationMillis: Long): String =
        if (durationMillis < 60_000L) {
            "${durationMillis / 1_000L} sec"
        } else if (durationMillis < 60 * 60_000L) {
            "${durationMillis / 60_000L} min"
        } else {
            "${durationMillis / (60 * 60_000L)} hour${if (durationMillis >= 2 * 60 * 60_000L) "s" else ""}"
        }

    private fun minutesLabel(minutes: Int): String = "$minutes ${if (minutes == 1) "minute" else "minutes"}"

    private fun durationLabel(millis: Long): String {
        val totalMinutes = millis / 60_000L
        val hours = totalMinutes / 60L
        val minutes = totalMinutes % 60L
        return when {
            hours == 0L -> "$minutes min"
            minutes == 0L -> "$hours h"
            else -> "$hours h $minutes min"
        }
    }

    private fun focusDurationTip(millis: Long): String {
        val minutes = millis / 60_000L
        val label = durationLabel(millis)
        return when {
            minutes < 15 -> "$label—a quick reset. Small steps count! 👣"
            minutes <= 45 -> "$label sounds just right. Nice pick!"
            minutes <= 120 -> "$label of focus? Ooh, ambitious. I like it. 💪"
            else -> "$label—deep-work mode! Remember to stretch and sip water."
        }
    }

    private fun breakPickerTip(minutes: Int): String = when {
        minutes <= 3 -> "${minutesLabel(minutes)}—a tiny breather, then back to it."
        minutes <= 10 -> "${minutesLabel(minutes)} break. Enjoy it, I’ll keep your spot warm."
        else -> "${minutesLabel(minutes)}. I’ll knock gently when time’s up. 🚪"
    }

    /** A card button plus the line Pip says about it during the tour. */
    private class PromptAction(val label: String, val tip: String, val onClick: () -> Unit)

    private fun showPromptCard(
        title: String,
        narration: String,
        message: String,
        quote: String,
        extraContent: ((LinearLayout, PipPromptGuide) -> Unit)?,
        actions: List<PromptAction>
    ) {
        removeIntervention()
        val now = SystemClock.elapsedRealtime()
        val repeatAppearance = lastPromptShownAtElapsed != 0L &&
            now - lastPromptShownAtElapsed < PROMPT_REPEAT_WINDOW_MILLIS
        lastPromptShownAtElapsed = now
        val pipPrefs = getSharedPreferences(PIP_PROMPT_PREFS, MODE_PRIVATE)
        val cardsSeen = pipPrefs.getInt(KEY_PROMPT_CARDS_SEEN, 0)
        pipPrefs.edit().putInt(KEY_PROMPT_CARDS_SEEN, cardsSeen + 1).apply()

        Ui.applyTheme(this, repository.appearanceMode())
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(12))
            background = Ui.rounded(Ui.SURFACE, dp(24).toFloat(), dp(1), Ui.BORDER)
            elevation = dp(16).toFloat()
        }
        card.addView(Ui.text(this, title, Ui.Type.TITLE))
        // Pip stays pinned above the scrolling options so it can guide through all of them.
        val guide = PipPromptGuide(this)
        card.addView(guide, LinearLayout.LayoutParams(-1, -2))

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        content.addView(Ui.text(this, message, Ui.Type.SMALL).apply { setPadding(0, dp(4), 0, dp(14)) })
        content.addView(Ui.quote(this, quote), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })
        extraContent?.invoke(content, guide)
        actions.forEachIndexed { index, action ->
            val button = addPromptAction(content, action.label, Ui.ButtonStyle.OUTLINE, action = action.onClick)
            guide.addStop(button, action.label) { action.tip }
        }

        val maxBodyHeight = (resources.displayMetrics.heightPixels * .82f).toInt()
            .coerceAtLeast(dp(320)) - dp(150)
        val body = object : ScrollView(this) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val cappedHeight = View.MeasureSpec.makeMeasureSpec(
                    maxBodyHeight,
                    View.MeasureSpec.AT_MOST
                )
                super.onMeasure(widthMeasureSpec, cappedHeight)
            }
        }.apply {
            isFillViewport = false
            clipToPadding = false
            isVerticalFadingEdgeEnabled = true
            setFadingEdgeLength(dp(16))
            addView(content, android.view.ViewGroup.LayoutParams(-1, -2))
        }
        card.addView(body, LinearLayout.LayoutParams(-1, -2))

        val root = android.widget.FrameLayout(this).apply {
            clipToPadding = false
            setPadding(0, dp(8), 0, dp(8))
            addView(card, android.widget.FrameLayout.LayoutParams(-1, -2))
        }
        val params = WindowManager.LayoutParams(
            (resources.displayMetrics.widthPixels - dp(32)).coerceAtLeast(dp(280)),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            x = 0
            y = dp(24)
        }
        windowManager.addView(root, params)
        interventionView = root

        if (ValueAnimator.areAnimatorsEnabled()) {
            card.alpha = 0f
            card.translationY = dp(36).toFloat()
            card.animate().alpha(1f).translationY(0f).setDuration(260)
                .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
        }
        guide.showTourTip(cardsSeen < PROMPT_TOUR_TIP_CARDS)
        guide.greet(narration, repeat = repeatAppearance)
    }

    private fun addPromptSelector(parent: LinearLayout, selector: View, height: Int? = null): View {
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
        return inset
    }

    private fun addPromptAction(
        parent: LinearLayout,
        text: String,
        style: Ui.ButtonStyle,
        action: () -> Unit
    ): View {
        val button = Ui.button(this, text, style, height = 48f, onClick = action)
        parent.addView(button, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(5)
            bottomMargin = dp(5)
        })
        return button
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

    /**
     * Constants for accessibility service notifications, intervals, event types, and label lengths.
     */
    private companion object {
        const val FOCUS_NOTIFICATION_CHANNEL_ID = "scroll_starva_focus"
        const val FOCUS_RESUMED_NOTIFICATION_ID = 1001
        /** Idle fallback, used only when the foreground app can't be read. */
        const val INACTIVITY_TIMEOUT_MILLIS = 3 * 60_000L

        /** Delay after the screen turns on before checking which app is in front. */
        const val SCREEN_ON_SETTLE_MILLIS = 800L

        /** Minimum time between pager page checks (they run on content-change events). */
        const val PAGER_CHECK_INTERVAL_MILLIS = 250L

        /** Delay before re-checking the foreground app when a window event arrives mid-switch. */
        const val FOREGROUND_RECHECK_MILLIS = 600L

        /** A card shown within this window of the previous one greets more quietly. */
        const val PROMPT_REPEAT_WINDOW_MILLIS = 20 * 60_000L

        /** Pip's "tap me for a tour" tip is shown on this many cards, then dropped. */
        const val PROMPT_TOUR_TIP_CARDS = 3
        const val PIP_PROMPT_PREFS = "pip_prompt_guide"
        const val KEY_PROMPT_CARDS_SEEN = "cards_seen"

        /** Interval in milliseconds for checking active session progress and updating checkpoints. */
        const val CHECKPOINT_INTERVAL_MILLIS = 10_000L

        /** Quiet time between scroll events after which the next event starts a new gesture. */
        const val SCROLL_GESTURE_GAP_MILLIS = 300L

        /** A scroll step smaller than this is the slow tail of a fling. */
        const val GESTURE_TAIL_DP = 24

        /** A step at least this large right after a tail is a new swipe. */
        const val GESTURE_SWIPE_DP = 48

        /** Grace period in milliseconds before concluding a session when exiting a tracked app. */
        const val FEED_EXIT_GRACE_MILLIS = 2_500L

        /** Maximum character length of combined visible accessibility node labels. */
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

/**
 * @property pagerViewId View id of a full-screen video pager that changes pages without sending
 * scroll events (YouTube Shorts); page changes there are counted as scrolls instead.
 */
enum class FeedPlatform(val packageName: String, val label: String, val pagerViewId: String? = null) {
    TIKTOK("com.zhiliaoapp.musically", "TikTok"),
    YOUTUBE("com.google.android.youtube", "YouTube", "com.google.android.youtube:id/reel_recycler"),
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
        try {
            node.text?.let { output.append(' ').append(it) }
            node.contentDescription?.let { output.append(' ').append(it) }
            for (index in 0 until node.childCount) {
                if (output.length >= MAX_DIAGNOSTIC_LABEL_LENGTH) break
                val child = try { node.getChild(index) } catch (_: Exception) { null }
                if (child != null) {
                    collectText(child, output, depth + 1)
                }
            }
        } catch (_: Exception) {}
    }

    private const val MAX_DIAGNOSTIC_LABEL_LENGTH = 1_200
}
