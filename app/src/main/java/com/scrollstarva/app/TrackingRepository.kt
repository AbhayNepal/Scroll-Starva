package com.scrollstarva.app

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class TrackingSnapshot(
    val scrolls: Int,
    val scrollsByPlatform: Map<FeedPlatform, Int>,
    val activeMillis: Long,
    val distanceKm: Double,
    val analyticsActiveSeconds: Long,
    val htiScore: Double,
    val rollingMedianSeconds: Long,
    val visits: Int,
    val rapidReentries: Int,
    val microChecks: Int,
    val marathonSeconds: Long,
    val agitationSeconds: Long,
    val activities: List<ScrollActivity>,
    val dailyHistory: List<HabitDay>
)

data class FocusSession(
    val endsAtMillis: Long,
    val quote: String,
    val pausedRemainingMillis: Long = 0L,
    val reminderAtMillis: Long = 0L
) {
    val isPaused: Boolean get() = pausedRemainingMillis > 0L
}

object TrackingTimerSettings {
    const val DEFAULT_MARATHON_MINUTES = 20
    const val MIN_MARATHON_MINUTES = 5
    const val MAX_MARATHON_MINUTES = 120
    const val DEFAULT_FOCUS_BREAK_MINUTES = 2
    const val MIN_FOCUS_BREAK_MINUTES = 1
    const val MAX_FOCUS_BREAK_MINUTES = 10
    const val FOCUS_SESSION_MILLIS = 25 * 60 * 1000L
    const val MAX_FOCUS_SESSION_HOURS = 8
    const val MAX_FOCUS_SESSION_MINUTES = MAX_FOCUS_SESSION_HOURS * 60

    fun maxFocusBreakMinutes(marathonMinutes: Int): Int =
        minOf(MAX_FOCUS_BREAK_MINUTES, marathonMinutes - 1)
}

class TrackingRepository(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = context.getSharedPreferences("tracking", Context.MODE_PRIVATE)
    private val database = TrackingDatabase(appContext)

    fun snapshot(): TrackingSnapshot {
        resetLegacyCountersIfNeeded()
        val scrolls = preferences.getInt(KEY_SCROLLS, 0)
        val scrollsByPlatform = FeedPlatform.entries.associateWith { platform ->
            preferences.getInt(scrollKey(platform), 0)
        }
        val activeMillis = preferences.getLong(KEY_ACTIVE_MILLIS, 0)
        val screen = contextDisplayMetrics(appContext)
        val heightMeters = if (screen.second > 0) screen.first / screen.second * 0.0254 else 0.14
        val daily = database.dailyRollup(today())
        return TrackingSnapshot(
            scrolls = scrolls,
            scrollsByPlatform = scrollsByPlatform,
            activeMillis = activeMillis,
            distanceKm = scrolls * heightMeters / 1000.0,
            analyticsActiveSeconds = daily.activeSeconds,
            htiScore = daily.htiScore,
            rollingMedianSeconds = daily.rollingMedianSeconds,
            visits = daily.visits,
            rapidReentries = daily.rapidReentries,
            microChecks = daily.microChecks,
            marathonSeconds = daily.marathonSeconds,
            agitationSeconds = daily.agitationSeconds,
            activities = database.recentActivities(30),
            dailyHistory = database.dailyHistory(15)
        )
    }

    fun addScroll(platform: FeedPlatform) {
        resetLegacyCountersIfNeeded()
        preferences.edit()
            .putInt(KEY_SCROLLS, preferences.getInt(KEY_SCROLLS, 0) + 1)
            .putInt(scrollKey(platform), preferences.getInt(scrollKey(platform), 0) + 1)
            .apply()
    }

    fun addActiveMillis(millis: Long) {
        if (millis <= 0) return
        resetLegacyCountersIfNeeded()
        preferences.edit()
            .putLong(KEY_ACTIVE_MILLIS, preferences.getLong(KEY_ACTIVE_MILLIS, 0) + millis)
            .apply()
    }

    fun beginSession(
        platform: FeedPlatform,
        startedAtMillis: Long,
        countAsReentry: Boolean = true
    ): SessionStart = database.beginSession(platform, startedAtMillis, countAsReentry)

    fun recordSessionScroll(activityId: String) = database.recordScroll(activityId)

    fun checkpointSession(
        activityId: String,
        endedAtMillis: Long,
        durationMillis: Long,
        strokeCount: Int
    ) = database.updateSession(activityId, endedAtMillis, durationMillis, strokeCount, completed = false)

    fun finishSession(
        activityId: String,
        endedAtMillis: Long,
        durationMillis: Long,
        strokeCount: Int
    ) = database.updateSession(activityId, endedAtMillis, durationMillis, strokeCount, completed = true)

    fun finishInterruptedSessions() = database.finishInterruptedSessions()

    fun marathonMinutes(): Int = preferences.getInt(
        KEY_MARATHON_MINUTES,
        TrackingTimerSettings.DEFAULT_MARATHON_MINUTES
    ).coerceIn(
        TrackingTimerSettings.MIN_MARATHON_MINUTES,
        TrackingTimerSettings.MAX_MARATHON_MINUTES
    )

    fun setMarathonMinutes(minutes: Int) {
        require(minutes in TrackingTimerSettings.MIN_MARATHON_MINUTES..
            TrackingTimerSettings.MAX_MARATHON_MINUTES) {
            "Marathon duration must be between ${TrackingTimerSettings.MIN_MARATHON_MINUTES} and " +
                "${TrackingTimerSettings.MAX_MARATHON_MINUTES} minutes"
        }
        val maxBreak = TrackingTimerSettings.maxFocusBreakMinutes(minutes)
        preferences.edit()
            .putInt(KEY_MARATHON_MINUTES, minutes)
            .putInt(KEY_FOCUS_BREAK_MINUTES, focusBreakMinutes().coerceAtMost(maxBreak))
            .apply()
    }

    fun focusBreakMinutes(): Int {
        val maxBreak = TrackingTimerSettings.maxFocusBreakMinutes(marathonMinutes())
        return preferences.getInt(
            KEY_FOCUS_BREAK_MINUTES,
            TrackingTimerSettings.DEFAULT_FOCUS_BREAK_MINUTES
        ).coerceIn(TrackingTimerSettings.MIN_FOCUS_BREAK_MINUTES, maxBreak)
    }

    fun setFocusBreakMinutes(minutes: Int) {
        val maxBreak = TrackingTimerSettings.maxFocusBreakMinutes(marathonMinutes())
        require(minutes in TrackingTimerSettings.MIN_FOCUS_BREAK_MINUTES..maxBreak) {
            "Focus break must be between ${TrackingTimerSettings.MIN_FOCUS_BREAK_MINUTES} and $maxBreak minutes"
        }
        preferences.edit().putInt(KEY_FOCUS_BREAK_MINUTES, minutes).apply()
    }

    fun startFocusSession(durationMillis: Long, quote: String): FocusSession {
        require(durationMillis > 0) { "Focus duration must be positive" }
        val focusSession = FocusSession(System.currentTimeMillis() + durationMillis, quote)
        preferences.edit()
            .putLong(KEY_FOCUS_ENDS_AT, focusSession.endsAtMillis)
            .putString(KEY_FOCUS_QUOTE, focusSession.quote)
            .remove(KEY_FOCUS_PAUSED_REMAINING)
            .remove(KEY_FOCUS_REMINDER_AT)
            .apply()
        return focusSession
    }

    fun activeFocusSession(): FocusSession? {
        val endsAtMillis = preferences.getLong(KEY_FOCUS_ENDS_AT, 0L)
        val pausedRemainingMillis = preferences.getLong(KEY_FOCUS_PAUSED_REMAINING, 0L)
        if (pausedRemainingMillis <= 0L && endsAtMillis <= System.currentTimeMillis()) {
            endFocusSession()
            return null
        }
        return FocusSession(
            endsAtMillis = endsAtMillis,
            quote = preferences.getString(KEY_FOCUS_QUOTE, null)
                ?: FocusSessionQuotes.random(),
            pausedRemainingMillis = pausedRemainingMillis,
            reminderAtMillis = preferences.getLong(KEY_FOCUS_REMINDER_AT, 0L)
        )
    }

    fun pauseFocusForScrollBreak(reminderDelayMillis: Long): FocusSession? {
        require(reminderDelayMillis > 0) { "Reminder delay must be positive" }
        val session = activeFocusSession() ?: return null
        val remaining = if (session.isPaused) {
            session.pausedRemainingMillis
        } else {
            (session.endsAtMillis - System.currentTimeMillis()).coerceAtLeast(0L)
        }
        if (remaining <= 0L) {
            endFocusSession()
            return null
        }

        val paused = session.copy(
            endsAtMillis = 0L,
            pausedRemainingMillis = remaining,
            reminderAtMillis = System.currentTimeMillis() + reminderDelayMillis
        )
        preferences.edit()
            .putLong(KEY_FOCUS_ENDS_AT, 0L)
            .putLong(KEY_FOCUS_PAUSED_REMAINING, paused.pausedRemainingMillis)
            .putLong(KEY_FOCUS_REMINDER_AT, paused.reminderAtMillis)
            .apply()
        return paused
    }

    fun resumeFocusSession(): FocusSession? {
        val session = activeFocusSession() ?: return null
        if (!session.isPaused) return session
        val resumed = session.copy(
            endsAtMillis = System.currentTimeMillis() + session.pausedRemainingMillis,
            pausedRemainingMillis = 0L,
            reminderAtMillis = 0L
        )
        preferences.edit()
            .putLong(KEY_FOCUS_ENDS_AT, resumed.endsAtMillis)
            .remove(KEY_FOCUS_PAUSED_REMAINING)
            .remove(KEY_FOCUS_REMINDER_AT)
            .apply()
        return resumed
    }

    fun isFocusReminderDue(nowMillis: Long = System.currentTimeMillis()): Boolean {
        val session = activeFocusSession() ?: return false
        return session.isPaused &&
            session.reminderAtMillis > 0L &&
            session.reminderAtMillis <= nowMillis
    }

    fun markFocusReminderShown() {
        preferences.edit().remove(KEY_FOCUS_REMINDER_AT).apply()
    }

    fun endFocusSession() {
        preferences.edit()
            .remove(KEY_FOCUS_ENDS_AT)
            .remove(KEY_FOCUS_QUOTE)
            .remove(KEY_FOCUS_PAUSED_REMAINING)
            .remove(KEY_FOCUS_REMINDER_AT)
            .apply()
    }

    private fun resetLegacyCountersIfNeeded() {
        val date = today()
        if (preferences.getString(KEY_DATE, null) != date) {
            preferences.edit()
                .putString(KEY_DATE, date)
                .putInt(KEY_SCROLLS, 0)
                .putLong(KEY_ACTIVE_MILLIS, 0)
                .also { editor ->
                    FeedPlatform.entries.forEach { editor.putInt(scrollKey(it), 0) }
                }
                .apply()
        }
    }

    private fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    private fun scrollKey(platform: FeedPlatform) = "${KEY_SCROLLS}_${platform.name.lowercase(Locale.US)}"

    private fun contextDisplayMetrics(context: Context): Pair<Float, Float> {
        val metrics = context.resources.displayMetrics
        return metrics.heightPixels.toFloat() to metrics.ydpi
    }

    private companion object {
        const val KEY_DATE = "date"
        const val KEY_SCROLLS = "scrolls"
        const val KEY_ACTIVE_MILLIS = "active_millis"
        const val KEY_FOCUS_ENDS_AT = "focus_ends_at"
        const val KEY_FOCUS_QUOTE = "focus_quote"
        const val KEY_FOCUS_PAUSED_REMAINING = "focus_paused_remaining"
        const val KEY_FOCUS_REMINDER_AT = "focus_reminder_at"
        const val KEY_MARATHON_MINUTES = "marathon_minutes"
        const val KEY_FOCUS_BREAK_MINUTES = "focus_break_minutes"
    }
}

object FocusSessionQuotes {
    private val quotes = listOf(
        "One focused moment can change the shape of your day.",
        "You do not need to do everything. Just begin with what matters.",
        "Give your attention to the life you want to build.",
        "Small, steady steps carry you further than a perfect plan."
    )

    fun random(): String = quotes.random()
}
