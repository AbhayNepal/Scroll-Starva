package com.scrollstarva.app

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class ScrollAccessibilityService : AccessibilityService() {
    private val repository by lazy { TrackingRepository(this) }
    private val handler = Handler(Looper.getMainLooper())
    private var trackingPackage: String? = null
    private var trackingStartedAt = 0L
    private var lastScrollAt = 0L

    private val flushTimer = object : Runnable {
        override fun run() {
            flushActiveTime()
            handler.postDelayed(this, 10_000)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        handler.postDelayed(flushTimer, 10_000)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: return
        val platform = FeedDetector.platformFor(packageName) ?: run {
            stopTracking()
            return
        }

        val feedVisible = when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_SCROLLED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ->
                FeedDetector.isShortFormFeed(event.source, platform)
            else -> false
        }

        if (!feedVisible) {
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) stopTracking()
            return
        }

        startTracking(packageName)
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED && isVerticalScroll(event)) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastScrollAt > 250) {
                repository.addScroll(platform)
                lastScrollAt = now
            }
        }
    }

    override fun onInterrupt() = stopTracking()

    override fun onDestroy() {
        stopTracking()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun startTracking(packageName: String) {
        if (trackingPackage == packageName) return
        stopTracking()
        trackingPackage = packageName
        trackingStartedAt = SystemClock.elapsedRealtime()
    }

    private fun stopTracking() {
        flushActiveTime()
        trackingPackage = null
        trackingStartedAt = 0L
    }

    private fun flushActiveTime() {
        if (trackingStartedAt == 0L) return
        val now = SystemClock.elapsedRealtime()
        repository.addActiveMillis(now - trackingStartedAt)
        trackingStartedAt = now
    }

    private fun isVerticalScroll(event: AccessibilityEvent): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            event.scrollDeltaY != 0 || event.scrollDeltaX == 0
        } else {
            event.fromIndex != event.toIndex || event.scrollX == 0
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
        val text = StringBuilder()
        collectText(root, text, 0)
        val normalized = text.toString().lowercase()
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

    private fun collectText(node: AccessibilityNodeInfo, output: StringBuilder, depth: Int) {
        if (depth > 20) return
        node.text?.let { output.append(' ').append(it) }
        node.contentDescription?.let { output.append(' ').append(it) }
        for (index in 0 until node.childCount) {
            node.getChild(index)?.let { child ->
                collectText(child, output, depth + 1)
                child.recycle()
            }
        }
    }
}
