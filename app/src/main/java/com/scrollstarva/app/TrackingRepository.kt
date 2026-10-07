package com.scrollstarva.app

import android.content.Context
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.util.Date
import java.util.Locale

data class TrackingSnapshot(
    val scrolls: Int,
    val scrollsByPlatform: Map<FeedPlatform, Int>,
    val scrollsByPackage: Map<String, Int>,
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

data class MorningRecap(
    val date: LocalDate,
    val metrics: DailyRollup,
    val previousDayMetrics: DailyRollup,
    val streak: Int,
    val bestStreak: Int,
    val metGoals: Boolean
)

enum class DailyLimitType {
    SCROLLS,
    TIME
}

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
    const val MIN_MARATHON_MINUTES = 1
    const val MAX_MARATHON_MINUTES = 120
    const val DEFAULT_FOCUS_BREAK_MINUTES = 2
    const val MIN_FOCUS_BREAK_MINUTES = 1
    const val MAX_FOCUS_BREAK_MINUTES = 10
    const val FOCUS_SESSION_MILLIS = 25 * 60 * 1000L
    const val MAX_FOCUS_SESSION_HOURS = 8
    const val MAX_FOCUS_SESSION_MINUTES = MAX_FOCUS_SESSION_HOURS * 60
    const val DEFAULT_DAILY_SCROLL_TARGET = 100
    const val MIN_DAILY_SCROLL_TARGET = 10
    const val MAX_DAILY_SCROLL_TARGET = 1_000
    const val DEFAULT_DAILY_TIME_TARGET_MINUTES = 60
    const val MIN_DAILY_TIME_TARGET_MINUTES = 5
    const val MAX_DAILY_TIME_TARGET_MINUTES = 720
    val BREAK_REMINDER_SNOOZE_OPTIONS_MILLIS = listOf(
        1L * 60_000L,
        2L * 60_000L,
        5L * 60_000L,
        15L * 60_000L,
        30L * 60_000L,
        60L * 60_000L,
        120L * 60_000L
    )

    fun maxFocusBreakMinutes(marathonMinutes: Int): Int =
        minOf(MAX_FOCUS_BREAK_MINUTES, maxOf(MIN_FOCUS_BREAK_MINUTES, marathonMinutes - 1))
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
            scrollsByPackage = database.dailyScrollCountsByPackage(today()),
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

    fun addScroll(packageName: String) {
        resetLegacyCountersIfNeeded()
        val editor = preferences.edit()
            .putInt(KEY_SCROLLS, preferences.getInt(KEY_SCROLLS, 0) + 1)
        FeedPlatform.entries.firstOrNull { it.packageName == packageName }?.let { platform ->
            editor.putInt(scrollKey(platform), preferences.getInt(scrollKey(platform), 0) + 1)
        }
        editor.apply()
    }

    fun addActiveMillis(millis: Long) {
        if (millis <= 0) return
        resetLegacyCountersIfNeeded()
        preferences.edit()
            .putLong(KEY_ACTIVE_MILLIS, preferences.getLong(KEY_ACTIVE_MILLIS, 0) + millis)
            .apply()
    }

    fun beginSession(
        packageName: String,
        startedAtMillis: Long,
        countAsReentry: Boolean = true
    ): SessionStart = database.beginSession(packageName, startedAtMillis, countAsReentry)

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

    fun selectedPackages(): Set<String> {
        if (!preferences.contains(KEY_SELECTED_PLATFORMS)) {
            return FeedPlatform.entries.map { it.packageName }.toSet()
        }
        return preferences.getStringSet(KEY_SELECTED_PLATFORMS, emptySet())
            .orEmpty()
            .map { savedValue ->
                FeedPlatform.entries.firstOrNull { it.name == savedValue }?.packageName ?: savedValue
            }
            .toSet()
    }

    fun setSelectedPackages(packageNames: Set<String>) {
        require(packageNames.isNotEmpty()) { "Select at least one app to track" }
        preferences.edit()
            .putStringSet(KEY_SELECTED_PLATFORMS, packageNames)
            .apply()
    }

    fun defaultFocusMinutes(): Int = preferences.getInt(
        KEY_DEFAULT_FOCUS_MINUTES,
        TrackingTimerSettings.FOCUS_SESSION_MILLIS.toInt() / 60_000
    ).coerceIn(1, TrackingTimerSettings.MAX_FOCUS_SESSION_MINUTES)

    fun setDefaultFocusMinutes(minutes: Int) {
        require(minutes in 1..TrackingTimerSettings.MAX_FOCUS_SESSION_MINUTES) {
            "Default focus duration must be between 1 and ${TrackingTimerSettings.MAX_FOCUS_SESSION_MINUTES} minutes"
        }
        preferences.edit().putInt(KEY_DEFAULT_FOCUS_MINUTES, minutes).apply()
    }

    fun dailyScrollTarget(): Int = preferences.getInt(
        KEY_DAILY_SCROLL_TARGET,
        TrackingTimerSettings.DEFAULT_DAILY_SCROLL_TARGET
    ).coerceIn(
        TrackingTimerSettings.MIN_DAILY_SCROLL_TARGET,
        TrackingTimerSettings.MAX_DAILY_SCROLL_TARGET
    )

    fun dailyTimeTargetMinutes(): Int = preferences.getInt(
        KEY_DAILY_TIME_TARGET_MINUTES,
        TrackingTimerSettings.DEFAULT_DAILY_TIME_TARGET_MINUTES
    ).coerceIn(
        TrackingTimerSettings.MIN_DAILY_TIME_TARGET_MINUTES,
        TrackingTimerSettings.MAX_DAILY_TIME_TARGET_MINUTES
    )

    fun dailyLimitsAtEightyPercent(today: LocalDate = LocalDate.now()): Set<DailyLimitType> {
        val metrics = database.dailyRollup(today.toString())
        return buildSet {
            if (metrics.scrollCount * 100L >= dailyScrollTarget() * 80L) {
                add(DailyLimitType.SCROLLS)
            }
            if (metrics.activeSeconds * 100L >= dailyTimeTargetMinutes() * 60L * 80L) {
                add(DailyLimitType.TIME)
            }
        }
    }

    @Synchronized
    fun claimDailyLimitPrompts(
        limits: Set<DailyLimitType>,
        today: LocalDate = LocalDate.now()
    ): Set<DailyLimitType> {
        val date = today.toString()
        val newlyReached = limits.filterTo(mutableSetOf()) { limit ->
            preferences.getString(dailyLimitPromptKey(limit), null) != date
        }
        if (newlyReached.isNotEmpty()) {
            val saved = preferences.edit().also { editor ->
                newlyReached.forEach { limit -> editor.putString(dailyLimitPromptKey(limit), date) }
            }.commit()
            check(saved) { "Could not save the daily limit reminder state" }
        }
        return newlyReached
    }

    fun setDailyGoals(scrollTarget: Int, timeTargetMinutes: Int) {
        require(scrollTarget in TrackingTimerSettings.MIN_DAILY_SCROLL_TARGET..
            TrackingTimerSettings.MAX_DAILY_SCROLL_TARGET) {
            "Daily scroll target must be between ${TrackingTimerSettings.MIN_DAILY_SCROLL_TARGET} and " +
                TrackingTimerSettings.MAX_DAILY_SCROLL_TARGET
        }
        require(timeTargetMinutes in TrackingTimerSettings.MIN_DAILY_TIME_TARGET_MINUTES..
            TrackingTimerSettings.MAX_DAILY_TIME_TARGET_MINUTES) {
            "Daily time target must be between ${TrackingTimerSettings.MIN_DAILY_TIME_TARGET_MINUTES} and " +
                TrackingTimerSettings.MAX_DAILY_TIME_TARGET_MINUTES
        }
        finalizeGoalDays(LocalDate.now())
        preferences.edit()
            .putInt(KEY_DAILY_SCROLL_TARGET, scrollTarget)
            .putInt(KEY_DAILY_TIME_TARGET_MINUTES, timeTargetMinutes)
            .apply()
    }

    fun dailyGoalStreak(today: LocalDate = LocalDate.now()): Int {
        finalizeGoalDays(today)
        val successfulDays = preferences.getStringSet(KEY_GOAL_SUCCESS_DATES, emptySet())
            .orEmpty()
        var date = today.minusDays(1)
        var streak = 0
        while (date.toString() in successfulDays) {
            streak++
            date = date.minusDays(1)
        }
        return streak
    }

    fun bestDailyGoalStreak(today: LocalDate = LocalDate.now()): Int {
        finalizeGoalDays(today)
        return preferences.getInt(KEY_BEST_GOAL_STREAK, 0)
    }

    fun morningRecap(today: LocalDate = LocalDate.now()): MorningRecap {
        val yesterday = today.minusDays(1)
        val metrics = database.dailyRollup(yesterday.toString())
        val previousDayMetrics = database.dailyRollup(yesterday.minusDays(1).toString())
        val metGoals = metrics.visits > 0 &&
            metrics.scrollCount < dailyScrollTarget() &&
            metrics.activeSeconds < dailyTimeTargetMinutes() * 60L
        return MorningRecap(
            date = yesterday,
            metrics = metrics,
            previousDayMetrics = previousDayMetrics,
            streak = dailyGoalStreak(today),
            bestStreak = bestDailyGoalStreak(today),
            metGoals = metGoals
        )
    }

    fun shouldShowMorningRecap(today: LocalDate = LocalDate.now()): Boolean =
        preferences.getString(KEY_LAST_MORNING_RECAP_DATE, null) != today.toString()

    fun markMorningRecapSeen(today: LocalDate = LocalDate.now()) {
        preferences.edit()
            .putString(KEY_LAST_MORNING_RECAP_DATE, today.toString())
            .apply()
    }

    private fun finalizeGoalDays(today: LocalDate) {
        val trackingStart = preferences.getString(KEY_GOAL_TRACKING_START_DATE, null)
            ?.let(LocalDate::parse)
            ?: today.also {
                preferences.edit()
                    .putString(KEY_GOAL_TRACKING_START_DATE, it.toString())
                    .putString(KEY_GOAL_LAST_EVALUATED_DATE, it.minusDays(1).toString())
                    .apply()
            }
        val lastEvaluated = preferences.getString(KEY_GOAL_LAST_EVALUATED_DATE, null)
            ?.let(LocalDate::parse)
            ?: trackingStart.minusDays(1)
        val yesterday = today.minusDays(1)
        if (!lastEvaluated.isBefore(yesterday)) return

        val firstDate = maxOf(
            trackingStart,
            lastEvaluated.plusDays(1)
        )
        val scrollTarget = dailyScrollTarget()
        val timeTargetSeconds = dailyTimeTargetMinutes() * 60L
        val successfulDays = preferences.getStringSet(KEY_GOAL_SUCCESS_DATES, emptySet())
            .orEmpty()
            .toMutableSet()
        var date = firstDate
        while (!date.isAfter(yesterday)) {
            val metrics = database.dailyRollup(date.toString())
            if (metrics.visits > 0 &&
                metrics.scrollCount < scrollTarget &&
                metrics.activeSeconds < timeTargetSeconds
            ) {
                successfulDays += date.toString()
            }
            date = date.plusDays(1)
        }
        var bestStreak = preferences.getInt(KEY_BEST_GOAL_STREAK, 0)
        var run = 0
        var previousDate: LocalDate? = null
        successfulDays.asSequence()
            .map(LocalDate::parse)
            .sorted()
            .forEach { successDate ->
                run = if (previousDate?.plusDays(1) == successDate) run + 1 else 1
                bestStreak = maxOf(bestStreak, run)
                previousDate = successDate
            }
        preferences.edit()
            .putStringSet(KEY_GOAL_SUCCESS_DATES, successfulDays)
            .putString(KEY_GOAL_LAST_EVALUATED_DATE, yesterday.toString())
            .putInt(KEY_BEST_GOAL_STREAK, bestStreak)
            .apply()
    }

    fun isOnboardingComplete(): Boolean =
        preferences.getBoolean(KEY_ONBOARDING_COMPLETE, false)

    fun completeOnboarding() {
        preferences.edit().putBoolean(KEY_ONBOARDING_COMPLETE, true).apply()
    }

    fun muteBreakReminders(durationMillis: Long) {
        require(durationMillis in TrackingTimerSettings.BREAK_REMINDER_SNOOZE_OPTIONS_MILLIS) {
            "Break reminder snooze duration must be one of the supported options"
        }
        preferences.edit()
            .putLong(KEY_BREAK_REMINDERS_MUTED_UNTIL, System.currentTimeMillis() + durationMillis)
            .remove(KEY_REPEAT_BREAK_REMINDER_AT)
            .apply()
    }

    fun breakRemindersMutedUntilMillis(): Long =
        preferences.getLong(KEY_BREAK_REMINDERS_MUTED_UNTIL, 0L)
            .takeIf { it > System.currentTimeMillis() } ?: 0L

    fun areBreakRemindersMuted(nowMillis: Long = System.currentTimeMillis()): Boolean =
        preferences.getLong(KEY_BREAK_REMINDERS_MUTED_UNTIL, 0L) > nowMillis

    fun unmuteBreakReminders() {
        preferences.edit()
            .remove(KEY_BREAK_REMINDERS_MUTED_UNTIL)
            .apply()
    }

    fun scheduleBreakReminderAgain(delayMillis: Long) {
        require(delayMillis > 0L) { "Break reminder delay must be positive" }
        preferences.edit()
            .putLong(KEY_REPEAT_BREAK_REMINDER_AT, System.currentTimeMillis() + delayMillis)
            .apply()
    }

    fun repeatBreakReminderAtMillis(): Long =
        preferences.getLong(KEY_REPEAT_BREAK_REMINDER_AT, 0L)

    fun clearRepeatBreakReminder() {
        preferences.edit().remove(KEY_REPEAT_BREAK_REMINDER_AT).apply()
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
            .remove(KEY_REPEAT_BREAK_REMINDER_AT)
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

    private fun dailyLimitPromptKey(limit: DailyLimitType) =
        "daily_limit_prompt_${limit.name.lowercase(Locale.US)}"

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
        const val KEY_SELECTED_PLATFORMS = "selected_platforms"
        const val KEY_FOCUS_BREAK_MINUTES = "focus_break_minutes"
        const val KEY_BREAK_REMINDERS_MUTED_UNTIL = "break_reminders_muted_until"
        const val KEY_DEFAULT_FOCUS_MINUTES = "default_focus_minutes"
        const val KEY_ONBOARDING_COMPLETE = "onboarding_complete"
        const val KEY_REPEAT_BREAK_REMINDER_AT = "repeat_break_reminder_at"
        const val KEY_DAILY_SCROLL_TARGET = "daily_scroll_target"
        const val KEY_DAILY_TIME_TARGET_MINUTES = "daily_time_target_minutes"
        const val KEY_GOAL_TRACKING_START_DATE = "goal_tracking_start_date"
        const val KEY_GOAL_LAST_EVALUATED_DATE = "goal_last_evaluated_date"
        const val KEY_GOAL_SUCCESS_DATES = "goal_success_dates"
        const val KEY_BEST_GOAL_STREAK = "best_goal_streak"
        const val KEY_LAST_MORNING_RECAP_DATE = "last_morning_recap_date"
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
