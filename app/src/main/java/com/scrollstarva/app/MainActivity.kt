package com.scrollstarva.app

import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.animation.ValueAnimator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.view.animation.OvershootInterpolator
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

private enum class DashboardTab(val title: String, val heading: String, val subtitle: String) {
    OVERVIEW("Today", "Your daily check-in", "A little more awareness, one day at a time."),
    TIME("Time", "Time in your feeds", "Notice your time patterns without judging yourself."),
    SCROLLS("Scrolls", "Your scrolling activity", "Every pause and choice is part of your progress."),
    PROGRESS("Progress", "Your progress", "Progress is personal. Small steps still count."),
    DEBUG("Debug", "Tracking diagnostics", "See exactly what Android sends to the tracker.")
}

private enum class ChartStyle { BARS, LINE }

private data class ChartPoint(val label: String, val value: Float, val detailLabel: String = label)

class MainActivity : android.app.Activity() {
    private lateinit var repository: TrackingRepository
    private lateinit var dashboardRoot: LinearLayout
    private lateinit var pageContent: LinearLayout
    private lateinit var pageScroll: ScrollView
    private lateinit var pageHeading: TextView
    private lateinit var pageSubtitle: TextView
    private lateinit var navigationBar: LinearLayout
    private lateinit var buddyView: FloatingBuddyView
    private lateinit var diagnosticLogView: TextView
    private lateinit var snapshot: TrackingSnapshot
    private var focusMode = false
    private lateinit var focusTimerView: TextView
    private lateinit var focusStatusView: TextView
    private val diagnosticHandler = Handler(Looper.getMainLooper())
    private var selectedTab = DashboardTab.OVERVIEW
    private var buddyMessageIndex = 0
    private var selectedAccent = CORAL
    private var activityResumed = false
    private val diagnosticRefresh = object : Runnable {
        override fun run() {
            if (selectedTab == DashboardTab.DEBUG && ::diagnosticLogView.isInitialized) {
                updateDiagnosticLog()
                diagnosticHandler.postDelayed(this, DIAGNOSTIC_REFRESH_MILLIS)
            }
        }
    }
    private val focusTimerRefresh = object : Runnable {
        override fun run() {
            if (!focusMode || !activityResumed) return
            val focusSession = repository.activeFocusSession()
            if (focusSession == null) {
                leaveFocusMode()
                return
            }
            focusTimerView.text = formatFocusCountdown(focusSession)
            focusStatusView.text = if (focusSession.isPaused) {
                "Focus paused during your short scroll break."
            } else {
                "One calm, intentional stretch. You’ve got this."
            }
            diagnosticHandler.postDelayed(this, FOCUS_TIMER_REFRESH_MILLIS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = TrackingRepository(this)
        snapshot = repository.snapshot()
        val focusSession = repository.activeFocusSession()
        if (focusSession != null) {
            showFocusScreen(focusSession)
        } else {
            setContentView(buildScreen())
        }
    }

    override fun onResume() {
        super.onResume()
        activityResumed = true
        if (::repository.isInitialized) {
            val focusSession = repository.activeFocusSession()
            if (focusSession != null) {
                if (!focusMode) showFocusScreen(focusSession)
                else focusTimerRefresh.run()
            } else if (focusMode) {
                leaveFocusMode()
            } else {
                refresh()
                startDiagnosticRefresh()
            }
        }
    }

    override fun onPause() {
        activityResumed = false
        diagnosticHandler.removeCallbacks(diagnosticRefresh)
        diagnosticHandler.removeCallbacks(focusTimerRefresh)
        super.onPause()
    }

    override fun onDestroy() {
        diagnosticHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun buildScreen(): View {
        dashboardRoot = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(CREAM)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(16))
        }
        val brandRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        brandRow.addView(TextView(this).apply {
            text = "SCROLL STARVA"
            textSize = 12f
            letterSpacing = .18f
            setTextColor(CORAL_DARK)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        buddyView = FloatingBuddyView().apply {
            contentDescription = "Pip, your friendly progress buddy. Tap for encouragement."
            setOnClickListener { cheerBuddy() }
        }
        brandRow.addView(buddyView, LinearLayout.LayoutParams(dp(64), dp(64)))
        header.addView(brandRow)
        pageHeading = TextView(this).apply {
            textSize = 29f
            setTextColor(INK)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(10), 0, dp(4))
        }
        header.addView(pageHeading)
        pageSubtitle = TextView(this).apply {
            textSize = 14f
            setTextColor(MUTED)
        }
        header.addView(pageSubtitle)
        dashboardRoot.addView(header)

        pageScroll = ScrollView(this)
        pageContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), 0, dp(20), dp(20))
        }
        pageScroll.addView(pageContent)
        dashboardRoot.addView(pageScroll, LinearLayout.LayoutParams(-1, 0, 1f))

        navigationBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(10), dp(12), dp(12))
            setBackgroundColor(Color.WHITE)
            elevation = dp(8).toFloat()
        }
        DashboardTab.entries.forEach { tab ->
            navigationBar.addView(
                navButton(tab),
                LinearLayout.LayoutParams(0, dp(46), 1f).apply {
                    marginStart = dp(3)
                    marginEnd = dp(3)
                }
            )
        }
        dashboardRoot.addView(navigationBar)
        renderTab()
        return dashboardRoot
    }

    private fun navButton(tab: DashboardTab): Button = Button(this).apply {
        text = tab.title
        textSize = 12f
        isAllCaps = false
        minWidth = 0
        minHeight = 0
        setPadding(dp(2), 0, dp(2), 0)
        setOnClickListener {
            selectedTab = tab
            renderTab()
        }
    }

    private fun refresh() {
        snapshot = repository.snapshot()
        renderTab()
    }

    private fun showFocusScreen(focusSession: FocusSession) {
        focusMode = true
        diagnosticHandler.removeCallbacks(diagnosticRefresh)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(32), dp(28), dp(32))
            setBackgroundColor(CREAM)
        }
        root.addView(TextView(this).apply {
            text = "SCROLL STARVA"
            textSize = 13f
            letterSpacing = .18f
            setTextColor(CORAL_DARK)
            gravity = Gravity.CENTER
        })
        root.addView(TextView(this).apply {
            text = "Your focus time"
            textSize = 29f
            setTextColor(INK)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, dp(38), 0, dp(4))
        })
        root.addView(TextView(this).apply {
            text = if (focusSession.isPaused) {
                "Focus paused during your short scroll break."
            } else {
                "One calm, intentional stretch. You’ve got this."
            }
            textSize = 15f
            setTextColor(MUTED)
            gravity = Gravity.CENTER
        }.also { focusStatusView = it })
        focusTimerView = TextView(this).apply {
            text = formatFocusCountdown(focusSession)
            textSize = 68f
            setTextColor(CORAL_DARK)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, dp(36), 0, dp(28))
        }
        root.addView(focusTimerView)
        root.addView(card {
            addView(label("A NOTE TO CARRY WITH YOU", CORAL_DARK, 12f))
            addView(TextView(this@MainActivity).apply {
                text = "“${focusSession.quote}”"
                textSize = 20f
                setTextColor(INK)
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(10), 0, dp(4))
            })
        })
        root.addView(actionButton("End focus early") { leaveFocusMode() }.apply {
            setTextColor(CORAL_DARK)
            background = roundedBackground(Color.WHITE, dp(12).toFloat())
        }, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(24)
        })
        setContentView(root)
        if (activityResumed) {
            diagnosticHandler.removeCallbacks(focusTimerRefresh)
            diagnosticHandler.post(focusTimerRefresh)
        }
    }

    private fun leaveFocusMode() {
        diagnosticHandler.removeCallbacks(focusTimerRefresh)
        repository.endFocusSession()
        focusMode = false
        snapshot = repository.snapshot()
        setContentView(buildScreen())
        refresh()
    }

    private fun formatFocusCountdown(focusSession: FocusSession): String {
        val remainingMillis = if (focusSession.isPaused) {
            focusSession.pausedRemainingMillis
        } else {
            (focusSession.endsAtMillis - System.currentTimeMillis()).coerceAtLeast(0L)
        }
        val remainingSeconds = (remainingMillis + 999L) / 1_000L
        val hours = remainingSeconds / 3_600L
        val minutes = (remainingSeconds % 3_600L) / 60L
        val seconds = remainingSeconds % 60L
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }

    private fun renderTab() {
        if (!::pageContent.isInitialized) return
        val tab = selectedTab
        pageHeading.text = tab.heading
        pageSubtitle.text = tab.subtitle
        pageContent.removeAllViews()
        when (tab) {
            DashboardTab.OVERVIEW -> buildOverview()
            DashboardTab.TIME -> buildTimeTab()
            DashboardTab.SCROLLS -> buildScrollsTab()
            DashboardTab.PROGRESS -> buildProgressTab()
            DashboardTab.DEBUG -> buildDebugTab()
        }
        selectedAccent = when (tab) {
            DashboardTab.OVERVIEW -> CORAL
            DashboardTab.TIME -> TEAL
            DashboardTab.SCROLLS -> PURPLE
            DashboardTab.PROGRESS -> GREEN
            DashboardTab.DEBUG -> INK
        }
        dashboardRoot.setBackgroundColor(when (tab) {
            DashboardTab.OVERVIEW -> CREAM
            DashboardTab.TIME -> Color.parseColor("#F0FAF8")
            DashboardTab.SCROLLS -> Color.parseColor("#F6F3FC")
            DashboardTab.PROGRESS -> Color.parseColor("#F1F8F2")
            DashboardTab.DEBUG -> Color.parseColor("#F0F2F5")
        })
        diagnosticHandler.removeCallbacks(diagnosticRefresh)
        if (tab == DashboardTab.DEBUG) startDiagnosticRefresh()
        pageScroll.scrollTo(0, 0)
        updateNavigation()
        animatePageIn()
    }

    private fun buildOverview() {
        val today = snapshot.dailyHistory.lastOrNull()
        pageContent.addView(card {
            addView(label("TODAY'S SUMMARY", CORAL_DARK, 12f))
            addView(metric("You're showing up for yourself.", 22f))
            addView(TextView(this@MainActivity).apply {
                text = encouragement(snapshot.dailyHistory)
                textSize = 15f
                setTextColor(INK)
                setPadding(0, dp(12), 0, 0)
            })
        })

        pageContent.addView(sectionTitle("A quick look"))
        val checkIn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        checkIn.addView(
            metricRow(
                "Feed time",
                formatDuration(today?.activeSeconds ?: 0),
                "Scrolls",
                (today?.scrollCount ?: 0).toString()
            )
        )
        checkIn.addView(
            metricRow(
                "Habit score",
                snapshot.dailyHistory.lastOrNull()?.htiScore?.let { "${it.toInt()} / 100" } ?: "—",
                "Sessions",
                (today?.visits ?: snapshot.visits).toString()
            ),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) }
        )
        pageContent.addView(checkIn)

        pageContent.addView(sectionTitle("Tracking"))
        val trackingOn = isAccessibilityEnabled()
        pageContent.addView(card {
            addView(label(
                if (trackingOn) "Tracking is on" else "Tracking is off",
                if (trackingOn) GREEN else CORAL_DARK,
                16f
            ))
            addView(TextView(this@MainActivity).apply {
                text = "Your activity is saved only on this device. You can pause tracking any time in Accessibility settings."
                textSize = 14f
                setTextColor(MUTED)
                setPadding(0, dp(8), 0, dp(14))
            })
            addView(actionButton(if (trackingOn) "Open tracking settings" else "Set up tracking") {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            })
        })
    }

    private fun buildTimeTab() {
        val history = snapshot.dailyHistory
        val today = history.lastOrNull()
        pageContent.addView(card {
            addView(label("TODAY", CORAL_DARK, 12f))
            addView(metric(formatDuration(today?.activeSeconds ?: 0), 32f))
            addView(label("in supported short-form feeds", MUTED, 14f))
        })
        pageContent.addView(sectionTitle("Daily feed time · last 15 days"))
        pageContent.addView(chartCard(
            points = history.map {
                ChartPoint(chartDate(it.date), it.activeSeconds.toFloat(), detailDate(it.date))
            },
            style = ChartStyle.BARS,
            valueFormatter = ::formatChartDuration
        ))
        val activeDays = history.filter { it.activeSeconds > 0 }
        val average = if (activeDays.isNotEmpty()) {
            activeDays.map { it.activeSeconds }.average().toLong()
        } else {
            0L
        }
        pageContent.addView(card {
            addView(label("A gentle perspective", CORAL_DARK, 12f))
            addView(TextView(this@MainActivity).apply {
                text = if (activeDays.isEmpty()) {
                    "As you use tracked feeds, this chart will start to reveal your own pattern."
                } else {
                    "On days with tracked feed activity, your average is ${formatDuration(average)}. Use this as information, not a judgment."
                }
                textSize = 15f
                setTextColor(INK)
                setPadding(0, dp(8), 0, 0)
            })
        })
    }

    private fun buildScrollsTab() {
        val history = snapshot.dailyHistory
        val today = history.lastOrNull()
        pageContent.addView(card {
            addView(label("TODAY'S SCROLLS", CORAL_DARK, 12f))
            addView(metric((today?.scrollCount ?: 0).toString(), 32f))
            addView(label("Estimated thumb travel: ${String.format(Locale.US, "%.3f km", snapshot.distanceKm)}", MUTED, 14f))
        })
        pageContent.addView(sectionTitle("Daily scrolls · last 15 days"))
        pageContent.addView(chartCard(
            points = history.map {
                ChartPoint(chartDate(it.date), it.scrollCount.toFloat(), detailDate(it.date))
            },
            style = ChartStyle.BARS,
            valueFormatter = { String.format(Locale.US, "%.0f", it) }
        ))
        pageContent.addView(sectionTitle("Recent feed sessions"))
        pageContent.addView(activityFeedCard(snapshot.activities))
        pageContent.addView(sectionTitle("Scrolls by app today"))
        pageContent.addView(card {
            FeedPlatform.entries.forEach { platform ->
                val count = snapshot.scrollsByPlatform[platform] ?: 0
                addView(metricRow(platform.label, count.toString(), null, null))
            }
        })
    }

    private fun buildProgressTab() {
        val history = snapshot.dailyHistory
        pageContent.addView(sectionTitle("Timers & focus"))
        pageContent.addView(timerSettingsCard())
        pageContent.addView(card {
            addView(label("A FOCUSED PAUSE", CORAL_DARK, 12f))
            addView(TextView(this@MainActivity).apply {
                text = "Choose a focus duration, then step away from the feed and give your attention to one thing that matters."
                textSize = 15f
                setTextColor(INK)
                setPadding(0, dp(8), 0, dp(4))
            })
            val durationPicker = FocusDurationPicker(
                this@MainActivity,
                TrackingTimerSettings.FOCUS_SESSION_MILLIS
            )
            addView(durationPicker)
            addView(actionButton("Start focus") {
                repository.startFocusSession(
                    durationPicker.durationMillis,
                    FocusSessionQuotes.random()
                )
                showFocusScreen(checkNotNull(repository.activeFocusSession()))
            })
        })
        pageContent.addView(card {
            addView(label("TODAY'S HABIT TAPER INDEX", CORAL_DARK, 12f))
            val score = history.lastOrNull()?.htiScore?.let { "${it.toInt()} / 100" } ?: "— / 100"
            addView(metric(score, 34f))
            addView(TextView(this@MainActivity).apply {
                text = progressContext(history)
                textSize = 15f
                setTextColor(INK)
                setPadding(0, dp(8), 0, 0)
            })
        })
        pageContent.addView(sectionTitle("Your score trend · last 15 days"))
        pageContent.addView(chartCard(
            points = history.mapNotNull { day ->
                day.htiScore?.let {
                    ChartPoint(chartDate(day.date), it.toFloat(), detailDate(day.date))
                }
            },
            style = ChartStyle.LINE,
            valueFormatter = { String.format(Locale.US, "%.0f", it) },
            minValue = 0f,
            maxValue = 100f
        ))
        pageContent.addView(card {
            addView(label("NOTICE THE SMALL WINS", CORAL_DARK, 12f))
            addView(TextView(this@MainActivity).apply {
                text = encouragement(history)
                textSize = 15f
                setTextColor(INK)
                setPadding(0, dp(8), 0, 0)
            })
        })
    }

    private fun timerSettingsCard(): View = card {
        var marathonMinutes = repository.marathonMinutes()
        val marathonLabel = label("Break reminder: $marathonMinutes minutes", INK, 15f)
        addView(marathonLabel)
        addView(SeekBar(this@MainActivity).apply {
            max = TrackingTimerSettings.MAX_MARATHON_MINUTES -
                TrackingTimerSettings.MIN_MARATHON_MINUTES
            progress = marathonMinutes - TrackingTimerSettings.MIN_MARATHON_MINUTES
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    marathonMinutes = progress + TrackingTimerSettings.MIN_MARATHON_MINUTES
                    marathonLabel.text = "Break reminder: $marathonMinutes minutes"
                }

                override fun onStartTrackingTouch(seekBar: SeekBar) = Unit

                override fun onStopTrackingTouch(seekBar: SeekBar) {
                    repository.setMarathonMinutes(marathonMinutes)
                    refresh()
                }
            })
        })
        addView(label(
            "${TrackingTimerSettings.MIN_MARATHON_MINUTES}–" +
                "${TrackingTimerSettings.MAX_MARATHON_MINUTES} minutes in tracked feeds",
            MUTED,
            12f
        ))

    }

    private fun buildDebugTab() {
        val enabled = isAccessibilityEnabled()
        pageContent.addView(card {
            addView(label(
                if (enabled) "ACCESSIBILITY SERVICE: ENABLED" else "ACCESSIBILITY SERVICE: OFF",
                if (enabled) GREEN else CORAL_DARK,
                13f
            ))
            addView(label(
                "Recent events include package, event type, source class, detected feed label, " +
                    "visible accessibility text, scroll deltas, and whether the count was accepted.",
                INK,
                14f
            ).apply { setPadding(0, dp(8), 0, dp(12)) })
            val actions = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(actionButton("Copy log") { copyDiagnosticLog() },
                    LinearLayout.LayoutParams(0, -2, 1f))
                addView(actionButton("Clear") {
                    AccessibilityDiagnostics.clear()
                    updateDiagnosticLog()
                }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8) })
            }
            addView(actions)
        })
        pageContent.addView(sectionTitle("Recent accessibility events"))
        pageContent.addView(card {
            diagnosticLogView = TextView(this@MainActivity).apply {
                text = "Waiting for events from supported apps…"
                textSize = 12f
                typeface = Typeface.MONOSPACE
                setTextColor(INK)
                setTextIsSelectable(true)
                setLineSpacing(dp(2).toFloat(), 1f)
            }
            addView(diagnosticLogView)
        })
        pageContent.addView(card {
            addView(label("PRIVACY", CORAL_DARK, 12f))
            addView(label(
                "Diagnostics are kept in memory on this device and retain at most 120 recent entries. " +
                    "Visible labels can contain text shown in the feed. Use Copy log only when you intend to share it.",
                INK,
                13f
            ).apply { setPadding(0, dp(6), 0, 0) })
        })
        updateDiagnosticLog()
    }

    private fun startDiagnosticRefresh() {
        diagnosticHandler.removeCallbacks(diagnosticRefresh)
        if (activityResumed && selectedTab == DashboardTab.DEBUG) {
            diagnosticHandler.post(diagnosticRefresh)
        }
    }

    private fun updateDiagnosticLog() {
        if (!::diagnosticLogView.isInitialized) return
        val entries = AccessibilityDiagnostics.recent()
        diagnosticLogView.text = if (entries.isEmpty()) {
            "No diagnostics yet.\n\nOpen a supported app, scroll a few times, then return here."
        } else {
            AccessibilityDiagnostics.format(entries)
        }
    }

    private fun copyDiagnosticLog() {
        val clipboard = getSystemService(ClipboardManager::class.java)
        checkNotNull(clipboard) { "Clipboard service is unavailable" }.setPrimaryClip(
            ClipData.newPlainText("Scroll Starva diagnostics", AccessibilityDiagnostics.format(
                AccessibilityDiagnostics.recent()
            ))
        )
        Toast.makeText(this, "Diagnostic log copied", Toast.LENGTH_SHORT).show()
    }

    private fun activityFeedCard(activities: List<ScrollActivity>): View = card {
        if (activities.isEmpty()) {
            addView(label("Tracked feed sessions will show up here.", MUTED, 14f))
            return@card
        }
        activities.take(10).forEachIndexed { index, activity ->
            val appName = FeedPlatform.entries
                .firstOrNull { it.packageName == activity.packageName }
                ?.label ?: activity.packageName
            val time = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
                .format(Date(activity.startMillis))
            val type = when {
                activity.isMicroCheck -> "Micro-check"
                activity.isMarathon -> "Long session"
                else -> "Session"
            }
            val dwell = if (activity.strokeCount > 0) {
                activity.durationSeconds.toDouble() / activity.strokeCount
            } else {
                0.0
            }
            addView(TextView(this@MainActivity).apply {
                text = "$time | $appName | ${formatDuration(activity.durationSeconds)}\n" +
                    "$type · ${activity.strokeCount} scrolls · " +
                    "${String.format(Locale.US, "%.1f", activity.averageCadenceSpm)} SPM · " +
                    "${String.format(Locale.US, "%.1f", dwell)}s per scroll" +
                    if (activity.isRapidReentry) "\nOpened again within five minutes" else ""
                textSize = 14f
                setTextColor(INK)
                setPadding(0, dp(10), 0, dp(10))
            })
            if (index < minOf(activities.size, 10) - 1) addView(divider())
        }
    }

    private fun chartCard(
        points: List<ChartPoint>,
        style: ChartStyle,
        valueFormatter: (Float) -> String,
        minValue: Float? = null,
        maxValue: Float? = null
    ): View = card {
        val insight = label("Tap a chart point to explore a day", MUTED, 12f)
        addView(HistoryChartView(
            this@MainActivity,
            points,
            style,
            valueFormatter,
            minValue,
            maxValue,
            onPointSelected = { point ->
                insight.text = "${point.detailLabel}: ${valueFormatter(point.value)}"
            }
        ),
            LinearLayout.LayoutParams(-1, dp(225)))
        addView(insight)
    }

    private inner class HistoryChartView(
        context: android.content.Context,
        private val points: List<ChartPoint>,
        private val style: ChartStyle,
        private val valueFormatter: (Float) -> String,
        private val fixedMin: Float?,
        private val fixedMax: Float?,
        private val onPointSelected: (ChartPoint) -> Unit
    ) : View(context) {
        private val chartPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val gridColor = Color.parseColor("#EEE8E0")
        private var selectedIndex: Int? = null

        init {
            isClickable = true
            contentDescription = "Interactive chart. Tap a point to hear its value."
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (points.isEmpty()) return false
            if (event.action == MotionEvent.ACTION_UP) {
                val left = dp(12).toFloat()
                val right = width - dp(12).toFloat()
                val step = (right - left) / points.size
                val index = ((event.x - left) / step).toInt().coerceIn(points.indices)
                selectedIndex = index
                val selected = points[index]
                onPointSelected(selected)
                contentDescription = "${selected.detailLabel}: ${valueFormatter(selected.value)}"
                invalidate()
                performClick()
                return true
            }
            return event.action == MotionEvent.ACTION_DOWN
        }

        override fun performClick(): Boolean {
            super.performClick()
            return true
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (points.isEmpty()) return

            val left = dp(12).toFloat()
            val right = width - dp(12).toFloat()
            val top = dp(18).toFloat()
            val bottom = height - dp(34).toFloat()
            val chartHeight = bottom - top
            val values = points.map { it.value }
            val minimum = fixedMin ?: 0f
            val maximum = fixedMax ?: max(1f, values.maxOrNull() ?: 0f)
            val range = max(1f, maximum - minimum)

            chartPaint.color = gridColor
            chartPaint.strokeWidth = dp(1).toFloat()
            for (line in 0..3) {
                val y = top + chartHeight * line / 3f
                canvas.drawLine(left, y, right, y, chartPaint)
            }

            val step = (right - left) / points.size
            selectedIndex?.let { index ->
                val x = left + step * (index + .5f)
                chartPaint.color = Color.argb(35, 23, 32, 42)
                canvas.drawRoundRect(
                    RectF(x - step * .42f, top, x + step * .42f, bottom),
                    dp(8).toFloat(),
                    dp(8).toFloat(),
                    chartPaint
                )
            }
            if (style == ChartStyle.BARS) {
                points.forEachIndexed { index, point ->
                    val barWidth = minOf(dp(14).toFloat(), step * .56f)
                    val x = left + step * (index + .5f)
                    val barHeight = ((point.value - minimum).coerceAtLeast(0f) / range) * chartHeight
                    chartPaint.color = selectedAccent
                    canvas.drawRoundRect(
                        RectF(x - barWidth / 2, bottom - barHeight, x + barWidth / 2, bottom),
                        dp(4).toFloat(),
                        dp(4).toFloat(),
                        chartPaint
                    )
                }
            } else {
                val path = Path()
                points.forEachIndexed { index, point ->
                    val x = left + step * (index + .5f)
                    val y = bottom - ((point.value - minimum).coerceIn(0f, range) / range) * chartHeight
                    if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                chartPaint.color = selectedAccent
                chartPaint.style = Paint.Style.STROKE
                chartPaint.strokeWidth = dp(3).toFloat()
                chartPaint.strokeCap = Paint.Cap.ROUND
                chartPaint.strokeJoin = Paint.Join.ROUND
                canvas.drawPath(path, chartPaint)
                chartPaint.style = Paint.Style.FILL
                points.forEachIndexed { index, point ->
                    val x = left + step * (index + .5f)
                    val y = bottom - ((point.value - minimum).coerceIn(0f, range) / range) * chartHeight
                    canvas.drawCircle(x, y, dp(3).toFloat(), chartPaint)
                }
            }

            chartPaint.color = MUTED
            chartPaint.textSize = dp(10).toFloat()
            chartPaint.textAlign = Paint.Align.CENTER
            points.forEachIndexed { index, point ->
                if (points.size <= 8 || index % 2 == 0 || index == points.lastIndex) {
                    canvas.drawText(point.label, left + step * (index + .5f), height - dp(9).toFloat(), chartPaint)
                }
            }
            chartPaint.textAlign = Paint.Align.RIGHT
            chartPaint.textSize = dp(9).toFloat()
            canvas.drawText(valueFormatter(maximum), right, top - dp(5).toFloat(), chartPaint)
            selectedIndex?.let { index ->
                chartPaint.textAlign = Paint.Align.CENTER
                chartPaint.textSize = dp(11).toFloat()
                chartPaint.typeface = Typeface.DEFAULT_BOLD
                chartPaint.color = INK
                val selected = points[index]
                val y = if (style == ChartStyle.BARS) {
                    val barHeight = ((selected.value - minimum).coerceAtLeast(0f) / range) * chartHeight
                    max(top + dp(14), bottom - barHeight - dp(8))
                } else {
                    bottom - ((selected.value - minimum).coerceIn(0f, range) / range) * chartHeight - dp(10)
                }
                canvas.drawText(valueFormatter(selected.value), left + step * (index + .5f), y, chartPaint)
                chartPaint.typeface = Typeface.DEFAULT
            }
        }
    }

    private fun cheerBuddy() {
        val messages = arrayOf(
            "One mindful moment is a win.",
            "Your progress belongs to you.",
            "A little pause can make a big difference.",
            "Keep going at your own pace."
        )
        pageSubtitle.text = messages[buddyMessageIndex++ % messages.size]
        buddyView.playCheer()
    }

    private inner class FloatingBuddyView : View(this@MainActivity) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var bobOffset = 0f
        private var cheerAmount = 0f
        private val bobAnimator = ValueAnimator.ofFloat(-3f, 3f).apply {
            duration = 1_350
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            addUpdateListener {
                bobOffset = it.animatedValue as Float
                invalidate()
            }
        }
        private var cheerAnimator: ValueAnimator? = null

        init {
            setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            bobAnimator.start()
        }

        override fun onDetachedFromWindow() {
            bobAnimator.cancel()
            cheerAnimator?.cancel()
            super.onDetachedFromWindow()
        }

        fun playCheer() {
            cheerAnimator?.cancel()
            cheerAnimator = ValueAnimator.ofFloat(0f, 1f, 0f).apply {
                duration = 560
                addUpdateListener {
                    cheerAmount = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
            animate()
                .rotationBy(12f)
                .scaleX(1.14f)
                .scaleY(1.14f)
                .setDuration(170)
                .withEndAction {
                    animate().rotation(0f).scaleX(1f).scaleY(1f).setDuration(220).start()
                }
                .start()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val unit = minOf(width, height) / 72f
            canvas.save()
            canvas.translate(width / 2f, height / 2f + bobOffset * unit / 3f)
            canvas.scale(unit, unit)

            if (cheerAmount > 0f) {
                paint.color = GOLD
                paint.alpha = (255 * cheerAmount).toInt()
                paint.style = Paint.Style.FILL
                for (index in 0..3) {
                    val angle = Math.toRadians((index * 90 + 45).toDouble())
                    val distance = 24f + 5f * cheerAmount
                    drawSpark(
                        canvas,
                        (kotlin.math.cos(angle) * distance).toFloat(),
                        (kotlin.math.sin(angle) * distance).toFloat(),
                        3f
                    )
                }
                paint.alpha = 255
            }

            paint.setShadowLayer(2f, 0f, 2f, Color.argb(35, 23, 32, 42))
            paint.color = TEAL
            paint.style = Paint.Style.FILL
            canvas.drawRoundRect(RectF(-21f, -19f, 21f, 21f), 15f, 15f, paint)
            paint.clearShadowLayer()

            paint.color = Color.parseColor("#B8E8D8")
            canvas.drawOval(RectF(-12f, -1f, 12f, 15f), paint)

            paint.color = GOLD
            val sprout = Path().apply {
                moveTo(0f, -17f)
                cubicTo(-2f, -24f, -10f, -24f, -9f, -29f)
                cubicTo(-2f, -29f, 1f, -25f, 1f, -20f)
                cubicTo(3f, -27f, 8f, -29f, 12f, -26f)
                cubicTo(11f, -19f, 6f, -17f, 0f, -17f)
                close()
            }
            canvas.drawPath(sprout, paint)

            paint.color = INK
            canvas.drawCircle(-7f, -6f, 1.7f, paint)
            canvas.drawCircle(7f, -6f, 1.7f, paint)
            paint.color = Color.parseColor("#F3A49A")
            canvas.drawCircle(-12f, 0f, 2.5f, paint)
            canvas.drawCircle(12f, 0f, 2.5f, paint)
            paint.color = INK
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.6f
            canvas.drawArc(RectF(-4f, -3f, 4f, 4f), 15f, 150f, false, paint)
            paint.style = Paint.Style.FILL

            paint.color = PURPLE
            canvas.drawOval(RectF(-14f, 17f, -4f, 21f), paint)
            canvas.drawOval(RectF(4f, 17f, 14f, 21f), paint)
            canvas.restore()
        }

        private fun drawSpark(canvas: Canvas, x: Float, y: Float, radius: Float) {
            val spark = Path().apply {
                moveTo(x, y - radius)
                lineTo(x + radius * .35f, y - radius * .35f)
                lineTo(x + radius, y)
                lineTo(x + radius * .35f, y + radius * .35f)
                lineTo(x, y + radius)
                lineTo(x - radius * .35f, y + radius * .35f)
                lineTo(x - radius, y)
                lineTo(x - radius * .35f, y - radius * .35f)
                close()
            }
            canvas.drawPath(spark, paint)
        }
    }

    private fun animatePageIn() {
        pageContent.alpha = 0f
        pageContent.translationY = dp(12).toFloat()
        pageContent.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(260)
            .start()
        for (index in 0 until pageContent.childCount) {
            val child = pageContent.getChildAt(index)
            child.alpha = 0f
            child.translationY = dp(10).toFloat()
            child.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay((index * 35L).coerceAtMost(210L))
                .setDuration(260)
                .start()
        }
    }

    private fun encouragement(history: List<HabitDay>): String {
        val completedDays = history.dropLast(1)
        val recent = completedDays.takeLast(7)
        val previous = completedDays.dropLast(7).takeLast(7)
        val recentTracked = recent.count { it.visits > 0 }
        val previousTracked = previous.count { it.visits > 0 }
        if (recentTracked < 3 || previousTracked < 3) {
            return "Your personal baseline is taking shape. Each tracked day adds context, and choosing a mindful pause is already a meaningful step."
        }
        val recentTime = recent.sumOf { it.activeSeconds }
        val previousTime = previous.sumOf { it.activeSeconds }
        if (previousTime == 0L) {
            return "You’re building a clearer picture of your habits. Progress is personal, and small intentional choices count."
        }
        val changePercent = ((previousTime - recentTime) * 100.0 / previousTime).toInt()
        return when {
            changePercent >= 5 -> "Your tracked feed time is down about $changePercent% compared with the previous week. That steady effort is worth celebrating."
            changePercent > -5 -> "Your tracked pattern is holding fairly steady. If you want, try one small intentional pause today; there’s no need to change everything at once."
            else -> "Your tracked feed time has been higher lately. That’s information, not failure—one small pause today can be a fresh start."
        }
    }

    private fun progressContext(history: List<HabitDay>): String {
        val earlierDays = history.dropLast(1).filter { it.visits > 0 }
        return if (earlierDays.size < 7) {
            "This is an early estimate while your 7-day baseline grows. It’s a guide, not a grade—your progress takes time to reveal itself."
        } else {
            "The score combines time, short returns, longer sessions, and estimated fast scrolling. Look for your trend over time rather than judging one day."
        }
    }

    private fun metricRow(
        firstLabel: String,
        firstValue: String,
        secondLabel: String?,
        secondValue: String?
    ): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(label(firstLabel, MUTED, 13f))
            addView(metric(firstValue, 22f))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        if (secondLabel != null && secondValue != null) {
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.END
                addView(label(secondLabel, MUTED, 13f))
                addView(metric(secondValue, 22f))
            })
        }
    }

    private fun card(content: LinearLayout.() -> Unit): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = roundedBackground(Color.WHITE, dp(18).toFloat())
            content()
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(8)
                bottomMargin = dp(8)
            }
        }

    private fun actionButton(text: String, action: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        setTextColor(Color.WHITE)
        background = roundedBackground(CORAL, dp(12).toFloat())
        setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> view.animate().scaleX(.97f).scaleY(.97f).setDuration(90).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    view.animate().scaleX(1f).scaleY(1f).setDuration(130).start()
            }
            false
        }
        setOnClickListener { action() }
    }

    private fun updateNavigation() {
        DashboardTab.entries.forEachIndexed { index, tab ->
            val button = navigationBar.getChildAt(index) as? Button ?: return@forEachIndexed
            val selected = tab == selectedTab
            button.background = roundedBackground(
                if (selected) selectedAccent else Color.parseColor("#F4F0EC"),
                dp(14).toFloat()
            )
            button.setTextColor(if (selected) Color.WHITE else INK)
            button.animate()
                .scaleX(if (selected) 1.04f else 1f)
                .scaleY(if (selected) 1.04f else 1f)
                .setInterpolator(OvershootInterpolator())
                .setDuration(150)
                .start()
        }
    }

    private fun sectionTitle(text: String) = TextView(this).apply {
        this.text = text
        textSize = 17f
        setTextColor(INK)
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(18), 0, dp(4))
    }

    private fun label(text: String, color: Int = MUTED, size: Float = 13f) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
    }

    private fun metric(text: String, size: Float) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(INK)
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(4), 0, dp(2))
    }

    private fun divider() = View(this).apply {
        setBackgroundColor(Color.parseColor("#EEE8E0"))
        layoutParams = LinearLayout.LayoutParams(-1, dp(1))
    }

    private fun roundedBackground(color: Int, radius: Float) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius
        }

    private fun isAccessibilityEnabled(): Boolean =
        Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?.contains(packageName, ignoreCase = true) == true

    private fun chartDate(date: java.time.LocalDate): String =
        date.format(java.time.format.DateTimeFormatter.ofPattern("d", Locale.getDefault()))

    private fun detailDate(date: java.time.LocalDate): String =
        date.format(java.time.format.DateTimeFormatter.ofPattern("MMM d", Locale.getDefault()))

    private fun formatDuration(seconds: Long): String {
        val minutes = seconds / 60
        return if (minutes < 60) {
            String.format(Locale.US, "%dm", minutes)
        } else {
            String.format(Locale.US, "%dh %02dm", minutes / 60, minutes % 60)
        }
    }

    private fun formatChartDuration(seconds: Float): String {
        val minutes = seconds.toLong() / 60
        return if (minutes < 60) "${minutes}m" else "${minutes / 60}h"
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val DIAGNOSTIC_REFRESH_MILLIS = 750L
        const val FOCUS_TIMER_REFRESH_MILLIS = 1_000L
        val CREAM = Color.parseColor("#FFF8EF")
        val INK = Color.parseColor("#17202A")
        val MUTED = Color.parseColor("#65727E")
        val CORAL = Color.parseColor("#F26B5E")
        val CORAL_DARK = Color.parseColor("#C94D43")
        val GREEN = Color.parseColor("#287A57")
        val TEAL = Color.parseColor("#2C9A91")
        val PURPLE = Color.parseColor("#8371C8")
        val GOLD = Color.parseColor("#F5C451")
    }
}
