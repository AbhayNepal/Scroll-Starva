package com.scrollstarva.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

data class HabitDay(
    val date: LocalDate,
    val activeSeconds: Long,
    val scrollCount: Int,
    val visits: Int,
    val htiScore: Double?
)

data class ScrollActivity(
    val id: String,
    val packageName: String,
    val startMillis: Long,
    val endMillis: Long,
    val durationSeconds: Long,
    val strokeCount: Int,
    val averageCadenceSpm: Double,
    val agitationSeconds: Long,
    val isMicroCheck: Boolean,
    val isMarathon: Boolean,
    val gapSeconds: Long?,
    val isRapidReentry: Boolean
)

data class SessionStart(val activityId: String, val isRapidReentry: Boolean)

data class DailyRollup(
    val activeSeconds: Long,
    val visits: Int,
    val rapidReentries: Int,
    val microChecks: Int,
    val marathonSeconds: Long,
    val agitationSeconds: Long,
    val rollingMedianSeconds: Long,
    val htiScore: Double,
    val scrollCount: Int = 0
)

class TrackingDatabase(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE scroll_activities (
                activity_id TEXT PRIMARY KEY,
                target_app_package TEXT NOT NULL,
                date_key TEXT NOT NULL,
                start_timestamp INTEGER NOT NULL,
                end_timestamp INTEGER NOT NULL,
                duration_seconds INTEGER NOT NULL DEFAULT 0,
                stroke_count INTEGER NOT NULL DEFAULT 0,
                avg_cadence_spm REAL NOT NULL DEFAULT 0,
                agitation_duration_seconds INTEGER NOT NULL DEFAULT 0,
                is_micro_check INTEGER NOT NULL DEFAULT 0,
                is_marathon INTEGER NOT NULL DEFAULT 0,
                gap_from_previous_seconds INTEGER,
                is_rapid_reentry INTEGER NOT NULL DEFAULT 0,
                completed INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX activities_date_idx ON scroll_activities(date_key)")
        db.execSQL(
            """
            CREATE TABLE daily_habit_rollups (
                date_key TEXT PRIMARY KEY,
                total_active_seconds INTEGER NOT NULL,
                total_visits INTEGER NOT NULL,
                rapid_reentry_count INTEGER NOT NULL,
                micro_check_count INTEGER NOT NULL,
                marathon_duration_seconds INTEGER NOT NULL,
                agitation_duration_seconds INTEGER NOT NULL,
                rolling_7d_median_seconds INTEGER NOT NULL,
                hti_score REAL NOT NULL,
                last_updated_timestamp INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE scroll_activities ADD COLUMN date_key TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE scroll_activities ADD COLUMN completed INTEGER NOT NULL DEFAULT 1")
            db.execSQL("CREATE INDEX activities_date_idx ON scroll_activities(date_key)")
        }
    }

    fun beginSession(
        packageName: String,
        startedAtMillis: Long,
        countAsReentry: Boolean
    ): SessionStart {
        val db = writableDatabase
        val date = LocalDate.ofInstant(java.time.Instant.ofEpochMilli(startedAtMillis), ZoneId.systemDefault())
        var previousEnd: Long? = null
        db.rawQuery(
            """
            SELECT end_timestamp
            FROM scroll_activities
            WHERE completed = 1 AND target_app_package = ?
            ORDER BY end_timestamp DESC
            LIMIT 1
            """.trimIndent(),
            arrayOf(packageName)
        ).use { cursor ->
            if (cursor.moveToFirst()) previousEnd = cursor.getLong(0)
        }
        val gapMillis = previousEnd?.let { startedAtMillis - it }?.takeIf { it >= 0 }
        val rapid = countAsReentry && gapMillis != null && gapMillis < RAPID_REENTRY_MILLIS
        val id = UUID.randomUUID().toString()
        val values = ContentValues().apply {
            put("activity_id", id)
            put("target_app_package", packageName)
            put("date_key", date.toString())
            put("start_timestamp", startedAtMillis)
            put("end_timestamp", startedAtMillis)
            put("gap_from_previous_seconds", gapMillis?.div(1000))
            put("is_rapid_reentry", if (rapid) 1 else 0)
        }
        db.insertOrThrow("scroll_activities", null, values)
        refreshRollup(date)
        return SessionStart(id, rapid)
    }

    fun dailyScrollCountsByPackage(date: String): Map<String, Int> {
        val counts = mutableMapOf<String, Int>()
        readableDatabase.rawQuery(
            """
            SELECT target_app_package, COALESCE(SUM(stroke_count), 0)
            FROM scroll_activities
            WHERE date_key = ?
            GROUP BY target_app_package
            """.trimIndent(),
            arrayOf(date)
        ).use { cursor ->
            while (cursor.moveToNext()) {
                counts[cursor.getString(0)] = cursor.getInt(1)
            }
        }
        return counts
    }

    fun recordScroll(activityId: String) {
        writableDatabase.execSQL(
            "UPDATE scroll_activities SET stroke_count = stroke_count + 1 WHERE activity_id = ?",
            arrayOf(activityId)
        )
    }

    fun updateSession(
        activityId: String,
        endedAtMillis: Long,
        durationMillis: Long,
        strokeCount: Int,
        completed: Boolean
    ) {
        val durationSeconds = max(0L, durationMillis / 1000)
        val cadence = if (durationSeconds > 0) strokeCount * 60.0 / durationSeconds else 0.0
        val agitationSeconds = if (cadence > AGITATION_CADENCE_SPM) durationSeconds else 0L
        val values = ContentValues().apply {
            put("end_timestamp", endedAtMillis)
            put("duration_seconds", durationSeconds)
            put("stroke_count", strokeCount)
            put("avg_cadence_spm", cadence)
            put("agitation_duration_seconds", agitationSeconds)
            put("is_micro_check", if (completed && durationSeconds < MICRO_CHECK_SECONDS) 1 else 0)
            put("is_marathon", if (durationSeconds > MARATHON_SECONDS) 1 else 0)
            if (completed) put("completed", 1)
        }
        writableDatabase.update("scroll_activities", values, "activity_id = ?", arrayOf(activityId))
        val date = readableDatabase.rawQuery(
            "SELECT date_key FROM scroll_activities WHERE activity_id = ?",
            arrayOf(activityId)
        ).use { cursor ->
            if (!cursor.moveToFirst()) return
            LocalDate.parse(cursor.getString(0))
        }
        refreshRollup(date)
    }

    fun finishInterruptedSessions() {
        val db = writableDatabase
        val dates = mutableSetOf<LocalDate>()
        db.rawQuery(
            "SELECT DISTINCT date_key FROM scroll_activities WHERE completed = 0",
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                dates += LocalDate.parse(cursor.getString(0))
            }
        }
        db.execSQL(
            """
            UPDATE scroll_activities SET
                completed = 1,
                is_micro_check = CASE WHEN duration_seconds < ? THEN 1 ELSE 0 END
            WHERE completed = 0
            """.trimIndent(),
            arrayOf(MICRO_CHECK_SECONDS)
        )
        dates.forEach(::refreshRollup)
    }

    fun recentActivities(limit: Int): List<ScrollActivity> {
        val activities = mutableListOf<ScrollActivity>()
        readableDatabase.rawQuery(
            """
            SELECT activity_id, target_app_package, start_timestamp, end_timestamp,
                   duration_seconds, stroke_count, avg_cadence_spm,
                   agitation_duration_seconds, is_micro_check, is_marathon,
                   gap_from_previous_seconds, is_rapid_reentry
            FROM scroll_activities
            ORDER BY start_timestamp DESC
            LIMIT ?
            """.trimIndent(),
            arrayOf(limit.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                activities += ScrollActivity(
                    id = cursor.getString(0),
                    packageName = cursor.getString(1),
                    startMillis = cursor.getLong(2),
                    endMillis = cursor.getLong(3),
                    durationSeconds = cursor.getLong(4),
                    strokeCount = cursor.getInt(5),
                    averageCadenceSpm = cursor.getDouble(6),
                    agitationSeconds = cursor.getLong(7),
                    isMicroCheck = cursor.getInt(8) != 0,
                    isMarathon = cursor.getInt(9) != 0,
                    gapSeconds = if (cursor.isNull(10)) null else cursor.getLong(10),
                    isRapidReentry = cursor.getInt(11) != 0
                )
            }
        }
        return activities
    }

    fun dailyRollup(date: String): DailyRollup {
        val currentDate = LocalDate.parse(date)
        val days = queryDays(currentDate.minusDays(7), currentDate)
        val metrics = days[currentDate] ?: emptyDay()
        val baseline = (1L..7L)
            .mapNotNull { days[currentDate.minusDays(it)]?.activeSeconds }
            .filter { it >= DETOX_DAY_SECONDS }
            .sorted()
        val median = median(baseline)
        val result = metrics.copy(rollingMedianSeconds = median, htiScore = calculateScore(metrics, median))
        persistRollup(currentDate, result)
        return result
    }

    fun dailyHistory(dayCount: Int): List<HabitDay> {
        require(dayCount > 0)
        val today = LocalDate.now()
        val firstDate = today.minusDays(dayCount.toLong() - 1)
        val rawDays = queryDays(firstDate.minusDays(7), today)
        return (0 until dayCount).map { offset ->
            val date = firstDate.plusDays(offset.toLong())
            val metrics = rawDays[date] ?: emptyDay()
            val baseline = (1L..7L)
                .mapNotNull { rawDays[date.minusDays(it)]?.activeSeconds }
                .filter { it >= DETOX_DAY_SECONDS }
                .sorted()
            HabitDay(
                date = date,
                activeSeconds = metrics.activeSeconds,
                scrollCount = metrics.scrollCount,
                visits = metrics.visits,
                htiScore = if (metrics.visits > 0) calculateScore(metrics, median(baseline)) else null
            )
        }
    }

    private fun queryDays(firstDate: LocalDate, lastDate: LocalDate): Map<LocalDate, DailyRollup> {
        val days = mutableMapOf<LocalDate, DailyRollup>()
        readableDatabase.rawQuery(
            """
            SELECT date_key,
                   COALESCE(SUM(duration_seconds), 0),
                   COUNT(*),
                   COALESCE(SUM(is_rapid_reentry), 0),
                   COALESCE(SUM(is_micro_check), 0),
                   COALESCE(SUM(CASE WHEN is_marathon = 1 THEN duration_seconds ELSE 0 END), 0),
                   COALESCE(SUM(agitation_duration_seconds), 0),
                   COALESCE(SUM(stroke_count), 0)
            FROM scroll_activities
            WHERE date_key BETWEEN ? AND ?
            GROUP BY date_key
            """.trimIndent(),
            arrayOf(firstDate.toString(), lastDate.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                days[LocalDate.parse(cursor.getString(0))] = DailyRollup(
                    activeSeconds = cursor.getLong(1),
                    visits = cursor.getInt(2),
                    rapidReentries = cursor.getInt(3),
                    microChecks = cursor.getInt(4),
                    marathonSeconds = cursor.getLong(5),
                    agitationSeconds = cursor.getLong(6),
                    rollingMedianSeconds = 0,
                    htiScore = 100.0,
                    scrollCount = cursor.getInt(7)
                )
            }
        }
        return days
    }

    private fun refreshRollup(date: LocalDate) {
        dailyRollup(date.toString())
    }

    private fun persistRollup(date: LocalDate, rollup: DailyRollup) {
        val values = ContentValues().apply {
            put("date_key", date.toString())
            put("total_active_seconds", rollup.activeSeconds)
            put("total_visits", rollup.visits)
            put("rapid_reentry_count", rollup.rapidReentries)
            put("micro_check_count", rollup.microChecks)
            put("marathon_duration_seconds", rollup.marathonSeconds)
            put("agitation_duration_seconds", rollup.agitationSeconds)
            put("rolling_7d_median_seconds", rollup.rollingMedianSeconds)
            put("hti_score", rollup.htiScore)
            put("last_updated_timestamp", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict(
            "daily_habit_rollups",
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    private fun median(values: List<Long>): Long {
        if (values.isEmpty()) return 0
        val middle = values.size / 2
        return if (values.size % 2 == 1) {
            values[middle]
        } else {
            (values[middle - 1] + values[middle]) / 2
        }
    }

    private fun calculateScore(metrics: DailyRollup, medianSeconds: Long): Double {
        val volumeFactor = if (medianSeconds == 0L) {
            0.0
        } else {
            clamp((metrics.activeSeconds - medianSeconds).coerceAtLeast(0) * 100.0 / medianSeconds)
        }
        val compulsionFactor = clamp(
            (metrics.rapidReentries + 0.5 * metrics.microChecks) * 100.0 / max(1, metrics.visits)
        )
        val marathonFactor = clamp(metrics.marathonSeconds * 100.0 / max(1L, metrics.activeSeconds))
        val agitationFactor = clamp(metrics.agitationSeconds * 100.0 / max(1L, metrics.activeSeconds))
        return (100.0 - (
            0.35 * volumeFactor +
                0.30 * compulsionFactor +
                0.20 * marathonFactor +
                0.15 * agitationFactor
            )).coerceIn(0.0, 100.0)
    }

    private fun emptyDay() = DailyRollup(0, 0, 0, 0, 0, 0, 0, 100.0, 0)

    private fun clamp(value: Double): Double = min(100.0, max(0.0, value))

    private companion object {
        const val DATABASE_NAME = "scroll_starva.db"
        const val DATABASE_VERSION = 2
        const val RAPID_REENTRY_MILLIS = 5 * 60 * 1000L
        const val MICRO_CHECK_SECONDS = 60L
        const val MARATHON_SECONDS = 20 * 60L
        const val AGITATION_CADENCE_SPM = 25.0
        const val DETOX_DAY_SECONDS = 300L
    }
}
