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
    }
}
