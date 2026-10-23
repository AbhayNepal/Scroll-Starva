package com.scrollstarva.app

import android.content.Context
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.util.Date
import java.util.Locale

/**
 * Encapsulates a complete point-in-time snapshot of user activity and mindful metrics for today.
 *
 * @property scrolls Total scroll count recorded today across all tracked apps.
 * @property scrollsByPlatform Map of scroll counts grouped by [FeedPlatform].
 * @property scrollsByPackage Map of scroll counts grouped by target package name.
 * @property activeMillis Total active foreground time today in milliseconds.
 * @property distanceKm Estimated physical thumb travel distance in kilometers.
 * @property analyticsActiveSeconds Active foreground time in seconds from daily rollup.
 * @property htiScore Habit Taper Index score (0.0 to 100.0) quantifying mindful usage.
 * @property rollingMedianSeconds 7-day rolling median foreground time in seconds.
 * @property visits Total app launch/re-entry events recorded today.
 * @property rapidReentries Count of rapid app re-entries within 5 minutes of closing.
 * @property microChecks Count of quick feed checks lasting less than 60 seconds.
 * @property marathonSeconds Total time spent in continuous sessions longer than 20 minutes.
 * @property agitationSeconds Total time spent scrolling at high cadence (>25 SPM).
 * @property activities List of recent 30 recorded scroll activities.
 * @property dailyHistory Daily rollup metrics for the last 15 days.
 */
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

/**
 * Holds yesterday's summary data for the morning recap card.
 *
 * @property date Date of yesterday's activity.
 * @property metrics Yesterday's daily rollup metrics.
 * @property previousDayMetrics Metrics for the day before yesterday.
 * @property streak Current consecutive goal streak in days.
 * @property bestStreak All-time best goal streak in days.
 * @property metGoals True if both daily scroll and time targets were met yesterday.
 */
data class MorningRecap(
    val date: LocalDate,
    val metrics: DailyRollup,
    val previousDayMetrics: DailyRollup,
    val streak: Int,
    val bestStreak: Int,
    val metGoals: Boolean
)

/**
 * Specifies the type of daily goal limit that can be reached.
 */
enum class DailyLimitType {
    SCROLLS,
    TIME
}

/**
 * Represents an active or paused focus session.
 *
 * @property endsAtMillis Epoch timestamp in milliseconds when the focus session ends.
 * @property quote Motivational quote associated with this focus session.
 * @property pausedRemainingMillis Remaining focus time in milliseconds if paused for a scroll break.
 * @property reminderAtMillis Epoch timestamp in milliseconds when a scroll break ends.
 */
data class FocusSession(
    val endsAtMillis: Long,
    val durationMillis: Long = 0L,
    val quote: String,
    val pausedRemainingMillis: Long = 0L,
    val reminderAtMillis: Long = 0L
) {
    /** True if the focus session is currently paused for a scroll break. */
    val isPaused: Boolean get() = pausedRemainingMillis > 0L

    /** Active focus session progress from 0.0f to 1.0f. */
    val progressFraction: Float
        get() {
            if (durationMillis <= 0L) return 0f
            val remaining = if (isPaused) pausedRemainingMillis else (endsAtMillis - System.currentTimeMillis()).coerceAtLeast(0L)
            return (1f - (remaining.toFloat() / durationMillis.toFloat())).coerceIn(0f, 1f)
        }
}

/**
 * Defines default bounds and duration constants for timers, focus sessions, and goal targets.
 */
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

    /**
     * Calculates the maximum allowable scroll break duration based on marathon reminder length.
     *
     * @param marathonMinutes Configured marathon reminder interval in minutes.
     * @return Maximum allowed scroll break duration in minutes.
     */
    fun maxFocusBreakMinutes(marathonMinutes: Int): Int =
        minOf(MAX_FOCUS_BREAK_MINUTES, maxOf(MIN_FOCUS_BREAK_MINUTES, marathonMinutes - 1))
}

/**
 * Main repository managing local preferences, database sessions, focus state, and goal evaluations.
 *
 * @param context Android Application Context or Activity Context.
 */
class TrackingRepository(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = context.getSharedPreferences("tracking", Context.MODE_PRIVATE)
    private val database = TrackingDatabase(appContext)

    /**
     * Generates a complete [TrackingSnapshot] containing today's live scroll metrics, HTI score, and history.
     */
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

    /**
     * Increments today's scroll count for the specified package.
     *
     * @param packageName Target app package name where the scroll occurred.
     */
    fun addScroll(packageName: String) {
        resetLegacyCountersIfNeeded()
        val editor = preferences.edit()
            .putInt(KEY_SCROLLS, preferences.getInt(KEY_SCROLLS, 0) + 1)
        FeedPlatform.entries.firstOrNull { it.packageName == packageName }?.let { platform ->
            editor.putInt(scrollKey(platform), preferences.getInt(scrollKey(platform), 0) + 1)
        }
        editor.apply()
    }

    /**
     * Accumulates active foreground usage time in milliseconds.
     *
     * @param millis Time duration in milliseconds to add.
     */
    fun addActiveMillis(millis: Long) {
        if (millis <= 0) return
        resetLegacyCountersIfNeeded()
        preferences.edit()
            .putLong(KEY_ACTIVE_MILLIS, preferences.getLong(KEY_ACTIVE_MILLIS, 0) + millis)
            .apply()
    }

    /**
     * Begins a new feed session in the tracking database.
     *
     * @param packageName Target app package name.
     * @param startedAtMillis Epoch start timestamp in milliseconds.
     * @param countAsReentry True if this session counts as a rapid re-entry.
     */
    fun beginSession(
        packageName: String,
        startedAtMillis: Long,
        countAsReentry: Boolean = true
    ): SessionStart = database.beginSession(packageName, startedAtMillis, countAsReentry)

    /** Records a scroll stroke for the specified active session. */
    fun recordSessionScroll(activityId: String) = database.recordScroll(activityId)

    /** Checkpoints an ongoing session in the database without marking it complete. */
    fun checkpointSession(
        activityId: String,
        endedAtMillis: Long,
        durationMillis: Long,
        strokeCount: Int
    ) = database.updateSession(activityId, endedAtMillis, durationMillis, strokeCount, completed = false)

    /** Finalizes and completes a feed session in the database. */
    fun finishSession(
        activityId: String,
        endedAtMillis: Long,
        durationMillis: Long,
        strokeCount: Int
    ) = database.updateSession(activityId, endedAtMillis, durationMillis, strokeCount, completed = true)

    /** Cleans up any leftover uncompleted sessions from app/service restarts. */
    fun finishInterruptedSessions() = database.finishInterruptedSessions()

    /** Returns the configured marathon reminder duration in minutes. */
    fun marathonMinutes(): Int = preferences.getInt(
        KEY_MARATHON_MINUTES,
        TrackingTimerSettings.DEFAULT_MARATHON_MINUTES
    ).coerceIn(
        TrackingTimerSettings.MIN_MARATHON_MINUTES,
        TrackingTimerSettings.MAX_MARATHON_MINUTES
    )

    /** Sets the marathon reminder duration in minutes. */
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

    /** Returns the set of app package names selected for tracking. */
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

    /** Sets the list of app package names to track. */
    fun setSelectedPackages(packageNames: Set<String>) {
        require(packageNames.isNotEmpty()) { "Select at least one app to track" }
        preferences.edit()
            .putStringSet(KEY_SELECTED_PLATFORMS, packageNames)
            .apply()
    }

    /** Returns the default focus session duration in minutes. */
    fun defaultFocusMinutes(): Int = preferences.getInt(
        KEY_DEFAULT_FOCUS_MINUTES,
        TrackingTimerSettings.FOCUS_SESSION_MILLIS.toInt() / 60_000
    ).coerceIn(1, TrackingTimerSettings.MAX_FOCUS_SESSION_MINUTES)

    /** Sets the default focus session duration in minutes. */
    fun setDefaultFocusMinutes(minutes: Int) {
        require(minutes in 1..TrackingTimerSettings.MAX_FOCUS_SESSION_MINUTES) {
            "Default focus duration must be between 1 and ${TrackingTimerSettings.MAX_FOCUS_SESSION_MINUTES} minutes"
        }
        preferences.edit().putInt(KEY_DEFAULT_FOCUS_MINUTES, minutes).apply()
    }

    /** Returns daily focus target in minutes (default 180 min = 3 hours). */
    fun dailyFocusTargetMinutes(): Int = preferences.getInt(
        KEY_DAILY_FOCUS_TARGET_MINUTES,
        180
    ).coerceIn(15, 720)

    /** Sets daily focus target in minutes. */
    fun setDailyFocusTargetMinutes(minutes: Int) {
        require(minutes in 15..720) { "Daily focus target must be between 15 and 720 minutes" }
        preferences.edit().putInt(KEY_DAILY_FOCUS_TARGET_MINUTES, minutes).apply()
    }

    /** Returns total focus seconds completed today plus active focus session elapsed time. */
    fun todayFocusSeconds(): Long {
        val todayStr = LocalDate.now().toString()
        val savedDate = preferences.getString(KEY_FOCUS_SECONDS_DATE, null)
        val completedSeconds = if (savedDate == todayStr) {
            preferences.getLong(KEY_TODAY_FOCUS_SECONDS, 0L)
        } else {
            0L
        }
        val activeSession = activeFocusSession()
        val activeElapsed = if (activeSession != null && !activeSession.isPaused) {
            val totalSessionMillis = if (activeSession.durationMillis > 0L) activeSession.durationMillis else defaultFocusMinutes() * 60_000L
            val remainingMillis = (activeSession.endsAtMillis - System.currentTimeMillis()).coerceAtLeast(0L)
            ((totalSessionMillis - remainingMillis) / 1_000L).coerceAtLeast(0L)
        } else {
            0L
        }
        return completedSeconds + activeElapsed
    }

    /** Adds completed focus seconds to today's cumulative total. */
    fun addCompletedFocusSeconds(seconds: Long) {
        if (seconds <= 0) return
        val todayStr = LocalDate.now().toString()
        val currentTotal = if (preferences.getString(KEY_FOCUS_SECONDS_DATE, null) == todayStr) {
            preferences.getLong(KEY_TODAY_FOCUS_SECONDS, 0L)
        } else {
            0L
        }
        preferences.edit()
            .putString(KEY_FOCUS_SECONDS_DATE, todayStr)
            .putLong(KEY_TODAY_FOCUS_SECONDS, currentTotal + seconds)
            .apply()
    }

    /** Returns the daily scroll limit target. */
    fun dailyScrollTarget(): Int = preferences.getInt(
        KEY_DAILY_SCROLL_TARGET,
        TrackingTimerSettings.DEFAULT_DAILY_SCROLL_TARGET
    ).coerceIn(
        TrackingTimerSettings.MIN_DAILY_SCROLL_TARGET,
        TrackingTimerSettings.MAX_DAILY_SCROLL_TARGET
    )

    /** Returns the daily time limit target in minutes. */
    fun dailyTimeTargetMinutes(): Int = preferences.getInt(
        KEY_DAILY_TIME_TARGET_MINUTES,
        TrackingTimerSettings.DEFAULT_DAILY_TIME_TARGET_MINUTES
    ).coerceIn(
        TrackingTimerSettings.MIN_DAILY_TIME_TARGET_MINUTES,
        TrackingTimerSettings.MAX_DAILY_TIME_TARGET_MINUTES
    )

    /**
     * Checks which daily limits have reached 80% or more of their target today.
     *
     * @param today LocalDate to evaluate against.
     * @return Set of [DailyLimitType] exceeding 80% threshold.
     */
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

    /** Claims and records that prompt reminders for 80% daily limits have been shown for today. */
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

    /** Sets daily scroll and time limit targets. */
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

    /** Returns current consecutive daily goal streak in days. */
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

    /** Returns all-time best daily goal streak in days. */
    fun bestDailyGoalStreak(today: LocalDate = LocalDate.now()): Int {
        finalizeGoalDays(today)
        return preferences.getInt(KEY_BEST_GOAL_STREAK, 0)
    }

    /** Constructs yesterday's [MorningRecap] dataset. */
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

    /** Determines if yesterday had tracked activity and needs a morning recap dialog. */
    fun shouldShowMorningRecap(today: LocalDate = LocalDate.now()): Boolean {
        if (!isOnboardingComplete()) return false
        if (preferences.getString(KEY_LAST_MORNING_RECAP_DATE, null) == today.toString()) return false
        val yesterday = today.minusDays(1)
        val metrics = database.dailyRollup(yesterday.toString())
        return metrics.visits > 0 || metrics.activeSeconds > 0L || metrics.scrollCount > 0
    }

    /** Marks today's morning recap as viewed. */
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

    /** Returns true if onboarding setup and tour have been completed. */
    fun isOnboardingComplete(): Boolean =
        preferences.getBoolean(KEY_ONBOARDING_COMPLETE, false)

    /** Marks onboarding setup and tour complete. */
    fun completeOnboarding() {
        preferences.edit().putBoolean(KEY_ONBOARDING_COMPLETE, true).apply()
    }

    /** Mutes break reminders for the specified duration in milliseconds. */
    fun muteBreakReminders(durationMillis: Long) {
        require(durationMillis in TrackingTimerSettings.BREAK_REMINDER_SNOOZE_OPTIONS_MILLIS) {
            "Break reminder snooze duration must be one of the supported options"
        }
        preferences.edit()
            .putLong(KEY_BREAK_REMINDERS_MUTED_UNTIL, System.currentTimeMillis() + durationMillis)
            .remove(KEY_REPEAT_BREAK_REMINDER_AT)
            .apply()
    }

    /** Returns epoch timestamp until which break reminders are muted, or 0 if active. */
    fun breakRemindersMutedUntilMillis(): Long =
        preferences.getLong(KEY_BREAK_REMINDERS_MUTED_UNTIL, 0L)
            .takeIf { it > System.currentTimeMillis() } ?: 0L

    /** Returns true if break reminders are currently muted. */
    fun areBreakRemindersMuted(nowMillis: Long = System.currentTimeMillis()): Boolean =
        preferences.getLong(KEY_BREAK_REMINDERS_MUTED_UNTIL, 0L) > nowMillis

    /** Unmutes break reminders immediately. */
    fun unmuteBreakReminders() {
        preferences.edit()
            .remove(KEY_BREAK_REMINDERS_MUTED_UNTIL)
            .apply()
    }

    /** Schedules a repeat break reminder after a delay in milliseconds. */
    fun scheduleBreakReminderAgain(delayMillis: Long) {
        require(delayMillis > 0L) { "Break reminder delay must be positive" }
        preferences.edit()
            .putLong(KEY_REPEAT_BREAK_REMINDER_AT, System.currentTimeMillis() + delayMillis)
            .apply()
    }

    /** Returns timestamp for pending repeat break reminder, or 0 if none. */
    fun repeatBreakReminderAtMillis(): Long =
        preferences.getLong(KEY_REPEAT_BREAK_REMINDER_AT, 0L)

    /** Clears any pending repeat break reminder. */
    fun clearRepeatBreakReminder() {
        preferences.edit().remove(KEY_REPEAT_BREAK_REMINDER_AT).apply()
    }

    /** Returns configured focus break duration in minutes. */
    fun focusBreakMinutes(): Int {
        val maxBreak = TrackingTimerSettings.maxFocusBreakMinutes(marathonMinutes())
        return preferences.getInt(
            KEY_FOCUS_BREAK_MINUTES,
            TrackingTimerSettings.DEFAULT_FOCUS_BREAK_MINUTES
        ).coerceIn(TrackingTimerSettings.MIN_FOCUS_BREAK_MINUTES, maxBreak)
    }

    /** Sets focus break duration in minutes. */
    fun setFocusBreakMinutes(minutes: Int) {
        val maxBreak = TrackingTimerSettings.maxFocusBreakMinutes(marathonMinutes())
        require(minutes in TrackingTimerSettings.MIN_FOCUS_BREAK_MINUTES..maxBreak) {
            "Focus break must be between ${TrackingTimerSettings.MIN_FOCUS_BREAK_MINUTES} and $maxBreak minutes"
        }
        preferences.edit().putInt(KEY_FOCUS_BREAK_MINUTES, minutes).apply()
    }

    /** Starts a new focus session for the specified duration and quote. */
    fun startFocusSession(durationMillis: Long, quote: String): FocusSession {
        require(durationMillis > 0) { "Focus duration must be positive" }
        val focusSession = FocusSession(
            endsAtMillis = System.currentTimeMillis() + durationMillis,
            durationMillis = durationMillis,
            quote = quote
        )
        preferences.edit()
            .putLong(KEY_FOCUS_ENDS_AT, focusSession.endsAtMillis)
            .putLong(KEY_FOCUS_DURATION_MILLIS, focusSession.durationMillis)
            .putString(KEY_FOCUS_QUOTE, focusSession.quote)
            .remove(KEY_REPEAT_BREAK_REMINDER_AT)
            .remove(KEY_FOCUS_PAUSED_REMAINING)
            .remove(KEY_FOCUS_REMINDER_AT)
            .apply()
        return focusSession
    }

    /** Returns active [FocusSession] if currently running or paused, or null if ended. */
    fun activeFocusSession(): FocusSession? {
        val endsAtMillis = preferences.getLong(KEY_FOCUS_ENDS_AT, 0L)
        val pausedRemainingMillis = preferences.getLong(KEY_FOCUS_PAUSED_REMAINING, 0L)
        val durationMillis = preferences.getLong(KEY_FOCUS_DURATION_MILLIS, defaultFocusMinutes() * 60_000L)
        if (pausedRemainingMillis <= 0L && endsAtMillis <= System.currentTimeMillis()) {
//            endFocusSession()
            return null
        }
        return FocusSession(
            endsAtMillis = endsAtMillis,
            durationMillis = durationMillis,
            quote = preferences.getString(KEY_FOCUS_QUOTE, null)
                ?: FocusSessionQuotes.random(),
            pausedRemainingMillis = pausedRemainingMillis,
            reminderAtMillis = preferences.getLong(KEY_FOCUS_REMINDER_AT, 0L)
        )
    }

    /** Accelerates active focus session by subtracting extra milliseconds from endsAtMillis. */
    fun advanceFocusEndsAt(deltaMillis: Long) {
        if (deltaMillis <= 0L) return
        val endsAtMillis = preferences.getLong(KEY_FOCUS_ENDS_AT, 0L)
        if (endsAtMillis > System.currentTimeMillis()) {
            val newEndsAt = (endsAtMillis - deltaMillis).coerceAtLeast(System.currentTimeMillis())
            preferences.edit().putLong(KEY_FOCUS_ENDS_AT, newEndsAt).apply()
        }
    }

    /** Pauses active focus session for a scroll break duration in milliseconds. */
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

    /** Resumes a paused focus session and calculates the new end timestamp. */
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

    /** Returns true if a focus scroll break timer has expired and needs reminder/resumption. */
    fun isFocusReminderDue(nowMillis: Long = System.currentTimeMillis()): Boolean {
        val session = activeFocusSession() ?: return false
        return session.isPaused &&
            session.reminderAtMillis > 0L &&
            session.reminderAtMillis <= nowMillis
    }

    /** Marks focus scroll break reminder complete and unpauses focus session. */
    fun markFocusReminderShown() {
        resumeFocusSession()
    }

    /** Ends and clears the active focus session immediately. */
    fun endFocusSession() {
        val session = activeFocusSession()
        if (session != null && !session.isPaused) {
            val totalSessionMillis = if (session.durationMillis > 0L) session.durationMillis else defaultFocusMinutes() * 60_000L
            val remainingMillis = (session.endsAtMillis - System.currentTimeMillis()).coerceAtLeast(0L)
            val elapsedSeconds = ((totalSessionMillis - remainingMillis) / 1_000L).coerceAtLeast(0L)
            addCompletedFocusSeconds(elapsedSeconds)
        }
        preferences.edit()
            .remove(KEY_FOCUS_ENDS_AT)
            .remove(KEY_FOCUS_DURATION_MILLIS)
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

    /**
     * Preference keys used for storing tracking, session, goal, and timer state in SharedPreferences.
     */
    private companion object {
        const val KEY_DAILY_FOCUS_TARGET_MINUTES = "daily_focus_target_minutes"
        const val KEY_TODAY_FOCUS_SECONDS = "today_focus_seconds"
        const val KEY_FOCUS_SECONDS_DATE = "focus_seconds_date"
        /** Key storing the current date string (yyyy-MM-dd) for daily counter resets. */
        const val KEY_DATE = "date"
        /** Key storing today's cumulative scroll count. */
        const val KEY_SCROLLS = "scrolls"
        /** Key storing today's cumulative active foreground time in milliseconds. */
        const val KEY_ACTIVE_MILLIS = "active_millis"
        /** Key storing epoch end-timestamp in milliseconds for active focus session. */
        const val KEY_FOCUS_ENDS_AT = "focus_ends_at"
        /** Key storing initial duration in milliseconds for active focus session. */
        const val KEY_FOCUS_DURATION_MILLIS = "focus_duration_millis"
        /** Key storing motivational quote for active focus session. */
        const val KEY_FOCUS_QUOTE = "focus_quote"
        /** Key storing remaining focus milliseconds when paused for a scroll break. */
        const val KEY_FOCUS_PAUSED_REMAINING = "focus_paused_remaining"
        /** Key storing epoch timestamp when a scroll break ends. */
        const val KEY_FOCUS_REMINDER_AT = "focus_reminder_at"
        /** Key storing configured marathon reminder threshold in minutes. */
        const val KEY_MARATHON_MINUTES = "marathon_minutes"
        /** Key storing set of selected app package names to track. */
        const val KEY_SELECTED_PLATFORMS = "selected_platforms"
        /** Key storing configured focus break duration in minutes. */
        const val KEY_FOCUS_BREAK_MINUTES = "focus_break_minutes"
        /** Key storing epoch timestamp until which break reminders are muted. */
        const val KEY_BREAK_REMINDERS_MUTED_UNTIL = "break_reminders_muted_until"
        /** Key storing default focus session duration in minutes. */
        const val KEY_DEFAULT_FOCUS_MINUTES = "default_focus_minutes"
        /** Key storing boolean flag indicating whether onboarding setup and tour are complete. */
        const val KEY_ONBOARDING_COMPLETE = "onboarding_complete"
        /** Key storing epoch timestamp for pending repeat break reminder. */
        const val KEY_REPEAT_BREAK_REMINDER_AT = "repeat_break_reminder_at"
        /** Key storing daily scroll target count. */
        const val KEY_DAILY_SCROLL_TARGET = "daily_scroll_target"
        /** Key storing daily foreground time target in minutes. */
        const val KEY_DAILY_TIME_TARGET_MINUTES = "daily_time_target_minutes"
        /** Key storing the date string when goal tracking first began. */
        const val KEY_GOAL_TRACKING_START_DATE = "goal_tracking_start_date"
        /** Key storing the date string when daily goal streak was last evaluated. */
        const val KEY_GOAL_LAST_EVALUATED_DATE = "goal_last_evaluated_date"
        /** Key storing set of date strings where both daily goals were successfully met. */
        const val KEY_GOAL_SUCCESS_DATES = "goal_success_dates"
        /** Key storing all-time best daily goal streak in days. */
        const val KEY_BEST_GOAL_STREAK = "best_goal_streak"
        /** Key storing date string of the last viewed morning recap. */
        const val KEY_LAST_MORNING_RECAP_DATE = "last_morning_recap_date"
    }
}

/**
 * Provides inspirational focus quotes.
 */
object FocusSessionQuotes {
    private val quotes = listOf(
        "One focused moment can change the shape of your day.",
        "You do not need to do everything. Just begin with what matters.",
        "Give your attention to the life you want to build.",
        "Small, steady steps carry you further than a perfect plan."
    )

    /** Returns a random motivational quote. */
    fun random(): String = quotes.random()
}
