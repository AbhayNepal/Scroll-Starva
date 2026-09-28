package com.scrollstarva.app

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class TrackingSnapshot(
    val scrolls: Int,
    val scrollsByPlatform: Map<FeedPlatform, Int>,
    val activeMillis: Long,
    val distanceKm: Double
)

class TrackingRepository(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = context.getSharedPreferences("tracking", Context.MODE_PRIVATE)

    fun snapshot(): TrackingSnapshot {
        resetIfNeeded()
        val scrolls = preferences.getInt(KEY_SCROLLS, 0)
        val scrollsByPlatform = FeedPlatform.entries.associateWith { platform ->
            preferences.getInt(scrollKey(platform), 0)
        }
        val activeMillis = preferences.getLong(KEY_ACTIVE_MILLIS, 0)
        val screen = contextDisplayMetrics(appContext)
        val heightMeters = if (screen.second > 0) screen.first / screen.second * 0.0254 else 0.14
        return TrackingSnapshot(scrolls, scrollsByPlatform, activeMillis, scrolls * heightMeters / 1000.0)
    }

    fun addScroll(platform: FeedPlatform) {
        resetIfNeeded()
        preferences.edit()
            .putInt(KEY_SCROLLS, preferences.getInt(KEY_SCROLLS, 0) + 1)
            .putInt(scrollKey(platform), preferences.getInt(scrollKey(platform), 0) + 1)
            .apply()
    }

    fun addActiveMillis(millis: Long) {
        if (millis <= 0) return
        resetIfNeeded()
        preferences.edit().putLong(KEY_ACTIVE_MILLIS, preferences.getLong(KEY_ACTIVE_MILLIS, 0) + millis).apply()
    }

    private fun resetIfNeeded() {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        if (preferences.getString(KEY_DATE, null) != today) {
            preferences.edit()
                .putString(KEY_DATE, today)
                .putInt(KEY_SCROLLS, 0)
                .putLong(KEY_ACTIVE_MILLIS, 0)
                .also { editor ->
                    FeedPlatform.entries.forEach { editor.putInt(scrollKey(it), 0) }
                }
                .apply()
        }
    }

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
