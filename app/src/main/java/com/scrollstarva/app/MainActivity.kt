package com.scrollstarva.app

import android.content.Intent
import android.content.Context
import android.app.AlertDialog
import android.app.Dialog
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.animation.ValueAnimator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.NumberPicker
import android.widget.SeekBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.view.animation.OvershootInterpolator
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.pow

private enum class DashboardTab(val title: String, val heading: String, val subtitle: String) {
    OVERVIEW("Home", "Your mindful day", "Small choices can build a steadier relationship with your attention."),
    TIME("Time", "Time in supported apps", "Notice your time patterns without judging yourself."),
    SCROLLS("Scrolls", "Your scrolling activity", "Every pause and choice is part of your progress."),
    PROGRESS("Progress", "Your progress", "Progress is personal. Small steps still count.")
}

private enum class ChartStyle { BARS, LINE }

private data class ChartPoint(val label: String, val value: Float, val detailLabel: String = label)

private data class InstalledTrackedApp(
    val packageName: String,
    val label: String,
    val icon: Drawable
)

private data class OnboardingTourStepData(
    val tab: DashboardTab,
    val badge: String,
    val title: String,
    val message: String,
    val nextButtonText: String,
    val cardIndex: Int
)

/**
 * Dims the screen except for a rounded cutout around [targetView]. Pip and his speech bubble are
 * raised above this overlay by the tour, so the overlay itself only blocks touches to the page.
 */
private class SpotlightTourOverlay(
    context: Context,
    private val targetView: View?
) : FrameLayout(context) {
    private val density = resources.displayMetrics.density
    private val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#B8140C08")
    }
    private val cutoutPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F26B5E")
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#66F26B5E")
        style = Paint.Style.STROKE
        strokeWidth = 7f * density
    }
    private val rect = RectF()
    private val startedAt = android.os.SystemClock.uptimeMillis()

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        setWillNotDraw(false)
        isClickable = true
        isFocusable = true
        setOnTouchListener { _, _ -> true }
        alpha = 0f
        animate().alpha(1f).setDuration(220).start()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrimPaint)
        val target = targetView ?: return
        if (!target.isAttachedToWindow || target.visibility != VISIBLE) return
        val location = IntArray(2)
        target.getLocationOnScreen(location)
        val own = IntArray(2)
        getLocationOnScreen(own)
        val pad = 8f * density
        rect.set(
            location[0] - own[0] - pad,
            location[1] - own[1] - pad,
            location[0] - own[0] + target.width + pad,
            location[1] - own[1] + target.height + pad
        )
        val radius = 20f * density
        canvas.drawRoundRect(rect, radius, radius, cutoutPaint)
        // A softly pulsing ring draws the eye to the highlighted card.
        val pulse = ((android.os.SystemClock.uptimeMillis() - startedAt) % 1600L) / 1600f
        glowPaint.alpha = (110 * (1f - pulse)).toInt()
        val grow = pulse * 6f * density
        rect.inset(-grow, -grow)
        canvas.drawRoundRect(rect, radius + grow, radius + grow, glowPaint)
        rect.inset(grow, grow)
        canvas.drawRoundRect(rect, radius, radius, ringPaint)
        postInvalidateOnAnimation()
    }
}

class MainActivity : android.app.Activity() {
    private lateinit var repository: TrackingRepository
    private lateinit var rootFrame: FrameLayout
    private lateinit var dashboardRoot: LinearLayout
    private lateinit var pageContent: LinearLayout
    private lateinit var pageScroll: ScrollView
    private lateinit var pageHeading: TextView
    private lateinit var pageSubtitle: TextView
    private lateinit var navigationBar: LinearLayout
    private lateinit var buddyView: PipView
    private var pipCompanion: PipCompanion? = null
    private lateinit var buddyAnchor: View
    private lateinit var snapshot: TrackingSnapshot
    private var focusMode = false
    private var isFastSpeedEnabled = false
    private var lastFocusTickNanos = 0L
    private lateinit var focusTimerView: TextView
    private lateinit var focusStatusView: TextView
    private lateinit var fastSpeedButton: TextView
    private lateinit var scrollBreakContainer: LinearLayout
    private lateinit var scrollBreakTimerView: TextView
    private lateinit var headerHeartView: FocusHeartView
    private lateinit var heroHeartView: FocusHeartView
    private val diagnosticHandler = Handler(Looper.getMainLooper())
    private val appMetadataExecutor = Executors.newSingleThreadExecutor()
    private var installedTrackedAppsCache: List<InstalledTrackedApp>? = null
    private var selectedTab = DashboardTab.OVERVIEW
    private var selectedAccent = CORAL
    private var activityResumed = false
    private var morningRecapDialog: Dialog? = null
    private var onboardingTourStep = ONBOARDING_SETTINGS_STEP
    private var onboardingFocusMinutes =
        (TrackingTimerSettings.FOCUS_SESSION_MILLIS / 60_000L).toInt()
    private var onboardingMarathonMinutes = TrackingTimerSettings.DEFAULT_MARATHON_MINUTES
    private var onboardingSelectedPackages = FeedPlatform.entries.map { it.packageName }.toMutableSet()
    private var onboardingScreen: View? = null
    private var onboardingFocusPicker: FocusDurationPicker? = null
    private var tourOverlayView: View? = null
    private var onboardingSetupPage = 0
    private var setupStage: PipStage? = null
    private var onboardingAppList: LinearLayout? = null
    private var accessibilitySettingsOpened = false
    private val onboardingTourSteps = listOf(
        OnboardingTourStepData(
            tab = DashboardTab.OVERVIEW,
            badge = "STEP 1 OF 10 · HOME TAB",
            title = "Welcome to Scroll Starva",
            message = "Pip is here to help you build a calmer relationship with your phone. Your daily flame stays lit as long as you stay within your mindful goals.",
            nextButtonText = "Next: Daily Limits →",
            cardIndex = 0
        ),
        OnboardingTourStepData(
            tab = DashboardTab.OVERVIEW,
            badge = "STEP 2 OF 10 · HOME TAB",
            title = "Track Your Daily Limits",
            message = "Here are your daily scroll and app time targets. As you check supported feeds, these numbers update live so you always know where you stand.",
            nextButtonText = "Next: Time Tab →",
            cardIndex = 1
        ),
        OnboardingTourStepData(
            tab = DashboardTab.TIME,
            badge = "STEP 3 OF 10 · TIME TAB",
            title = "Understand Your Time",
            message = "Welcome to the Time tab! This chart shows your daily screen time in supported apps over the last 15 days, helping you spot patterns without judgment.",
            nextButtonText = "Next: Scrolls & Thumb Travel →",
            cardIndex = 2
        ),
        OnboardingTourStepData(
            tab = DashboardTab.SCROLLS,
            badge = "STEP 4 OF 10 · SCROLLS TAB",
            title = "Scrolls & Thumb Travel",
            message = "On the Scrolls tab, you can view your total scroll flicks for today along with your estimated thumb travel distance in kilometers. Every pause is a win!",
            nextButtonText = "Next: App Breakdown →",
            cardIndex = 0
        ),
        OnboardingTourStepData(
            tab = DashboardTab.SCROLLS,
            badge = "STEP 5 OF 10 · SCROLLS TAB",
            title = "Scrolls by App Today",
            message = "This section breaks down your scrolling activity app by app, showing you exactly which apps consume the most attention during your daily routines.",
            nextButtonText = "Next: Progress Tab →",
            cardIndex = 2
        ),
        OnboardingTourStepData(
            tab = DashboardTab.PROGRESS,
            badge = "STEP 6 OF 10 · PROGRESS TAB",
            title = "Customize Tracked Apps",
            message = "Welcome to Progress! Tap 'Choose apps' here to select which social apps count toward your time, goals, and scroll totals.",
            nextButtonText = "Next: Daily Goals →",
            cardIndex = 1
        ),
        OnboardingTourStepData(
            tab = DashboardTab.PROGRESS,
            badge = "STEP 7 OF 10 · PROGRESS TAB",
            title = "Set Daily Goals",
            message = "Here you can set your target max scrolls and maximum foreground time limits per day to keep your digital routine balanced.",
            nextButtonText = "Next: Break Reminders →",
            cardIndex = 3
        ),
        OnboardingTourStepData(
            tab = DashboardTab.PROGRESS,
            badge = "STEP 8 OF 10 · PROGRESS TAB",
            title = "Feed Break Reminders",
            message = "Set this reminder slider so Pip can gently check in with you during long feed sessions when you've been scrolling continuously.",
            nextButtonText = "Next: Focus Sessions →",
            cardIndex = 5
        ),
        OnboardingTourStepData(
            tab = DashboardTab.PROGRESS,
            badge = "STEP 9 OF 10 · PROGRESS TAB",
            title = "Start a Focus Session",
            message = "When you want to step away from all social feeds, pick a focus duration here and tap Start Focus to give your full attention to what matters.",
            nextButtonText = "Next: Mindful Score →",
            cardIndex = 6
        ),
        OnboardingTourStepData(
            tab = DashboardTab.PROGRESS,
            badge = "STEP 10 OF 10 · PROGRESS TAB",
            title = "Habit Taper Index (Mindful Score)",
            message = "This is your Habit Taper Index—your daily 0–100 mindful score. It rewards longer breaks between app opens, fewer marathon sessions, and a calmer scroll pace!",
            nextButtonText = "Complete Tour 🎉",
            cardIndex = 7
        )
    )

    private val focusTimerRefresh = object : Runnable {
        override fun run() {
            if (!focusMode || !activityResumed) {
                lastFocusTickNanos = 0L
                return
            }
            val nowNanos = System.nanoTime()
            val focusSession = repository.activeFocusSession()
            if (focusSession == null) {
                lastFocusTickNanos = 0L
                leaveFocusMode()
                return
            }
            if (lastFocusTickNanos > 0L && isFastSpeedEnabled && !focusSession.isPaused) {
                val realDeltaMs = ((nowNanos - lastFocusTickNanos) / 1_000_000L).coerceIn(1L, 500L)
                val extraMs = (30L - 1L) * realDeltaMs
                repository.advanceFocusEndsAt(extraMs)
            }
            lastFocusTickNanos = nowNanos

            val activeSession = repository.activeFocusSession() ?: run {
                lastFocusTickNanos = 0L
                leaveFocusMode()
                return
            }

            focusTimerView.text = formatFocusCountdown(activeSession)
            focusStatusView.text = if (activeSession.isPaused) {
                "Focus paused during your short scroll break."
            } else {
                "One calm, intentional stretch. You’ve got this."
            }
            if (::scrollBreakContainer.isInitialized) {
                if (activeSession.isPaused && activeSession.reminderAtMillis > 0L) {
                    scrollBreakContainer.visibility = View.VISIBLE
                    scrollBreakTimerView.text = formatBreakCountdown(activeSession.reminderAtMillis)
                } else {
                    scrollBreakContainer.visibility = View.GONE
                }
            }
            val completedSeconds = repository.todayFocusSeconds()
            val targetMin = repository.dailyFocusTargetMinutes()
            val fraction = (completedSeconds.toFloat() / (targetMin * 60f)).coerceIn(0f, 1f)
            if (::headerHeartView.isInitialized) {
                headerHeartView.fillFraction = fraction
            }
            if (::heroHeartView.isInitialized) {
                heroHeartView.fillFraction = activeSession.progressFraction
            }
            diagnosticHandler.postDelayed(this, FOCUS_TIMER_REFRESH_MILLIS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }
        repository = TrackingRepository(this)
        preloadInstalledTrackedApps()
        snapshot = repository.snapshot()
        onboardingFocusMinutes = repository.defaultFocusMinutes()
        onboardingMarathonMinutes = repository.marathonMinutes()
        onboardingSelectedPackages = repository.selectedPackages().toMutableSet()
        renderAppState()
    }

    override fun onResume() {
        super.onResume()
        activityResumed = true
        if (::repository.isInitialized) {
            renderAppState()
        }
    }

    override fun onPause() {
        activityResumed = false
        pipCompanion?.onPause()
        diagnosticHandler.removeCallbacks(focusTimerRefresh)
        super.onPause()
    }

    override fun onDestroy() {
        pipCompanion?.release()
        setupStage?.release()
        diagnosticHandler.removeCallbacksAndMessages(null)
        appMetadataExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun preloadInstalledTrackedApps() {
        appMetadataExecutor.execute {
            val apps = queryInstalledTrackedApps()
            runOnUiThread {
                installedTrackedAppsCache = apps
                onboardingAppList?.let { list ->
                    if (list.isAttachedToWindow) fillOnboardingAppList(list, setupStage)
                }
                if (!isFinishing && !isDestroyed && activityResumed &&
                    onboardingScreen == null && ::dashboardRoot.isInitialized && !focusMode
                ) {
                    refresh()
                }
            }
        }
    }

    private fun buildScreen(): View {
        onboardingScreen = null
        rootFrame = FrameLayout(this)
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
            text = brandTitleText()
            textSize = 12f
            letterSpacing = .18f
            setTextColor(CORAL_DARK)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        headerHeartView = FocusHeartView(this).apply {
            val completedSeconds = repository.todayFocusSeconds()
            val targetMin = repository.dailyFocusTargetMinutes()
            fillFraction = (completedSeconds.toFloat() / (targetMin * 60f)).coerceIn(0f, 1f)
            showBubbles = false
            contentDescription = "Mindful Focus Heart. Tap to view progress or start focus timer."
            setOnClickListener { showFocusHeartPopup() }
        }
        brandRow.addView(headerHeartView, LinearLayout.LayoutParams(dp(44), dp(44)).apply {
            marginEnd = dp(10)
        })
        // Pip itself lives in an overlay above the dashboard (so it can fly); this is its perch.
        buddyAnchor = View(this)
        brandRow.addView(buddyAnchor, LinearLayout.LayoutParams(dp(64), dp(64)))
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
        rootFrame.addView(dashboardRoot, FrameLayout.LayoutParams(-1, -1))
        pipCompanion?.release()
        pipCompanion = PipCompanion(this, rootFrame, buddyAnchor, headerHeartView, pipHost)
        buddyView = checkNotNull(pipCompanion).pip
        renderTab()
        return rootFrame
    }

    private fun renderAppState() {
        if (!isAccessibilityEnabled()) {
            focusMode = false
            diagnosticHandler.removeCallbacks(focusTimerRefresh)
            showTrackingSetupScreen()
            return
        }
        if (!repository.isOnboardingComplete()) {
            focusMode = false
            diagnosticHandler.removeCallbacks(focusTimerRefresh)
            showOnboardingScreen()
            return
        }
        val focusSession = repository.activeFocusSession()
        if (focusSession != null) {
            if (!focusMode) showFocusScreen(focusSession)
            else if (activityResumed) focusTimerRefresh.run()
            return
        }
        if (focusMode) {
            leaveFocusMode()
            return
        }
        if (onboardingScreen != null || !::dashboardRoot.isInitialized) {
            setContentView(buildScreen())
        } else {
            refresh()
        }
        showMorningRecapIfNeeded()
        pipCompanion?.onAppOpened()
    }

    // =====================================================================================
    // Onboarding: Pip guides the user through permission, a 3-step setup and a dashboard tour.
    // =====================================================================================

    /** Pieces of an onboarding page built by [onboardingScaffold]. */
    private class OnboardingPage(
        val root: FrameLayout,
        val content: LinearLayout,
        val buttons: LinearLayout,
        val stage: PipStage
    )

    /**
     * Shared layout for every pre-tour onboarding page, so they look like one flow:
     * step label + progress, Pip's perch with room for his speech bubble beside him,
     * scrolling content, and a pinned button bar.
     */
    private fun onboardingScaffold(stepLabel: String, stepIndex: Int?, stepCount: Int): OnboardingPage {
        setupStage?.release()
        val root = FrameLayout(this).apply { setBackgroundColor(CREAM) }
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(column, FrameLayout.LayoutParams(-1, -1))

        column.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), 0)
            addView(TextView(this@MainActivity).apply {
                text = stepLabel
                textSize = 11f
                letterSpacing = .18f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(CORAL_DARK)
            })
            if (stepIndex != null) {
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    for (i in 0 until stepCount) {
                        addView(View(this@MainActivity).apply {
                            background = roundedBackground(
                                if (i <= stepIndex) CORAL else Color.parseColor("#F3E3DA"),
                                dp(3).toFloat()
                            )
                        }, LinearLayout.LayoutParams(0, dp(6), 1f).apply {
                            if (i > 0) marginStart = dp(6)
                        })
                    }
                }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
            }
        })

        // Pip's perch; the bubble appears to its right inside this row.
        val anchor = View(this)
        column.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(14), dp(6), dp(14), 0)
            addView(anchor, LinearLayout.LayoutParams(dp(96), dp(96)))
        }, LinearLayout.LayoutParams(-1, dp(118)))

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), dp(16))
        }
        column.addView(ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            addView(content, android.view.ViewGroup.LayoutParams(-1, -2))
        }, LinearLayout.LayoutParams(-1, 0, 1f))

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(10), dp(20), dp(18))
        }
        column.addView(buttons, LinearLayout.LayoutParams(-1, -2))

        val stage = PipStage(this, root, anchor, pipSizeDp = 96)
        stage.onPipTapped = {
            stage.pip.playCheer()
            stage.say(PipCompanion.QUOTES.random(), placement = PipStage.Placement.RIGHT)
        }
        setupStage = stage
        return OnboardingPage(root, content, buttons, stage)
    }

    private fun onboardingTitle(title: String, subtitle: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(TextView(this@MainActivity).apply {
            text = title
            textSize = 26f
            setTextColor(INK)
            setTypeface(typeface, Typeface.BOLD)
        })
        addView(TextView(this@MainActivity).apply {
            text = subtitle
            textSize = 15f
            setTextColor(MUTED)
            setLineSpacing(0f, 1.15f)
            setPadding(0, dp(6), 0, dp(8))
        })
    }

    private fun onboardingSectionLabel(text: String) = TextView(this).apply {
        this.text = text
        textSize = 11f
        letterSpacing = .16f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(CORAL_DARK)
        setPadding(0, 0, 0, dp(8))
    }

    private fun onboardingPrimaryButton(text: String, action: () -> Unit) = actionButton(text, action).apply {
        textSize = 16f
        setTypeface(typeface, Typeface.BOLD)
        background = roundedBackground(CORAL, dp(16).toFloat())
        stateListAnimator = null
        minHeight = dp(54)
    }

    private fun onboardingSecondaryButton(text: String, action: () -> Unit) = actionButton(text, action).apply {
        textSize = 16f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(CORAL_DARK)
        background = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = dp(16).toFloat()
            setStroke(dp(1), Color.parseColor("#F3C9BF"))
        }
        stateListAnimator = null
        minHeight = dp(54)
    }

    private fun setOnboardingButtons(page: OnboardingPage, back: View?, next: View) {
        page.buttons.removeAllViews()
        if (back != null) {
            page.buttons.addView(back, LinearLayout.LayoutParams(0, dp(54), 0.38f).apply { marginEnd = dp(10) })
        }
        page.buttons.addView(next, LinearLayout.LayoutParams(0, dp(54), 1f))
    }

    private fun showTrackingSetupScreen() {
        if (onboardingScreen?.tag == TRACKING_SETUP_TAG) {
            // Back from Android settings without switching tracking on: Pip helps out.
            if (accessibilitySettingsOpened) {
                accessibilitySettingsOpened = false
                setupStage?.let {
                    it.pip.lookAtViewer()
                    it.say(
                        "Not switched on yet. Tap the button, find Scroll Starva in the list and turn it on. I'll wait! ⏳",
                        placement = PipStage.Placement.RIGHT, autoHideMs = 9_000L
                    )
                }
            }
            return
        }
        val page = onboardingScaffold("WELCOME", null, 0)
        page.content.addView(onboardingTitle(
            "Let's switch on tracking",
            "To notice when you're stuck in a feed, I need Android's Accessibility permission."
        ))

        page.content.addView(card {
            addView(onboardingSectionLabel("HOW IT WORKS"))
            listOf(
                "👀" to "Notices which of your chosen apps is open",
                "📏" to "Counts scrolls and time so you can see your progress",
                "🔒" to "Everything stays on this device"
            ).forEach { (icon, line) ->
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(5), 0, dp(5))
                    addView(TextView(this@MainActivity).apply {
                        text = icon
                        textSize = 17f
                        gravity = Gravity.CENTER
                        background = roundedBackground(Color.parseColor("#FFF1EA"), dp(12).toFloat())
                    }, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(12) })
                    addView(label(line, INK, 15f), LinearLayout.LayoutParams(0, -2, 1f))
                })
            }
        })

        lateinit var consentRow: View
        val consent = CheckBox(this).apply {
            text = "I understand and consent to this on-device tracking."
            textSize = 15f
            setTextColor(INK)
            buttonTintList = android.content.res.ColorStateList.valueOf(CORAL)
            setPadding(dp(6), dp(4), 0, dp(4))
        }
        page.content.addView(card {
            addView(onboardingSectionLabel("YOUR PRIVACY"))
            addView(label(
                "Scroll Starva uses Android Accessibility to read the active app and visible screen labels in the supported apps you select. This lets it identify short-form feeds, count scrolls, measure app time, and show break reminders. The activity data is stored on this device and is not sent to Scroll Starva servers.",
                MUTED,
                13.5f
            ).apply { setLineSpacing(0f, 1.15f) })
            consentRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(6), dp(8), dp(6))
                background = roundedBackground(Color.parseColor("#FFF6F1"), dp(14).toFloat())
                addView(consent, LinearLayout.LayoutParams(-1, -2))
            }
            addView(consentRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
        })

        page.content.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = roundedBackground(Color.parseColor("#F4EFE8"), dp(14).toFloat())
            addView(label("💡", INK, 15f), LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(10) })
            addView(label(
                "If Android says \"Restricted setting\": open Settings → Apps → Scroll Starva → ⋮ (top right) → Allow restricted settings, then try again.",
                MUTED,
                13f
            ), LinearLayout.LayoutParams(0, -2, 1f))
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        val continueButton = onboardingPrimaryButton("Continue to accessibility settings") {
            if (!consent.isChecked) {
                page.stage.pointOut(consentRow, "Tick this box first, so I know you're okay with it 🙂")
                return@onboardingPrimaryButton
            }
            accessibilitySettingsOpened = true
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }.apply { alpha = .6f }
        consent.setOnCheckedChangeListener { _, checked ->
            continueButton.alpha = if (checked) 1f else .6f
            if (checked) {
                page.stage.pip.playCheer()
                page.stage.say("Thank you! 💛 Now tap the button below and switch me on.", placement = PipStage.Placement.RIGHT)
            } else {
                page.stage.pip.lookAtViewer()
                page.stage.say("That's okay. Take your time.", placement = PipStage.Placement.RIGHT)
            }
        }
        setOnboardingButtons(page, null, continueButton)

        onboardingScreen = page.root.apply { tag = TRACKING_SETUP_TAG }
        setContentView(page.root)
        page.stage.postDelayed(500) {
            page.stage.pip.wave(1.8f)
            page.stage.say("Hi, I'm Pip! 👋 I'll help you scroll less and live more.", placement = PipStage.Placement.RIGHT) {
                page.stage.postDelayed(300) {
                    page.stage.say(
                        "First, I need your OK to notice when a feed is open.",
                        placement = PipStage.Placement.RIGHT, autoHideMs = 0L
                    )
                }
            }
        }
    }

    private fun showOnboardingScreen() {
        when {
            onboardingTourStep == ONBOARDING_SETTINGS_STEP -> showOnboardingSettings()
            else -> showOnboardingTourStep()
        }
    }

    /** Three short setup pages: apps → focus length → break reminder. */
    private fun showOnboardingSettings() {
        val pageIndex = onboardingSetupPage.coerceIn(0, 2)
        val page = onboardingScaffold("SETUP · STEP ${pageIndex + 1} OF 3", pageIndex, 3)
        onboardingAppList = null
        val stage = page.stage
        fun pipSays(text: String, keep: Boolean = false) =
            stage.say(text, placement = PipStage.Placement.RIGHT, autoHideMs = if (keep) 0L else null)

        val back = if (pageIndex > 0) onboardingSecondaryButton("Back") {
            onboardingSetupPage = pageIndex - 1
            showOnboardingSettings()
        } else null

        when (pageIndex) {
            0 -> {
                page.content.addView(onboardingTitle(
                    "Which apps should I watch?",
                    "Pick the feeds that pull you in. They'll count toward your time, goals and scroll totals."
                ))
                val listCard = card { }
                val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                listCard.addView(list)
                page.content.addView(listCard)
                onboardingAppList = list
                fillOnboardingAppList(list, stage)
                setOnboardingButtons(page, null, onboardingPrimaryButton("Next") {
                    if (onboardingSelectedPackages.isEmpty()) {
                        stage.pointOut(listCard, "Pick at least one app, so I have something to keep an eye on!")
                        return@onboardingPrimaryButton
                    }
                    repository.setSelectedPackages(onboardingSelectedPackages)
                    onboardingSetupPage = 1
                    showOnboardingSettings()
                })
                stage.postDelayed(450) {
                    stage.pip.wave(1.4f)
                    pipSays("Tick the apps that steal your time. I'll keep an eye on them 👀", keep = true)
                }
            }
            1 -> {
                page.content.addView(onboardingTitle(
                    "How long do you like to focus?",
                    "This is your default focus session. You can still pick a different length each time."
                ))
                val picker = FocusDurationPicker(this, onboardingFocusMinutes * 60_000L)
                page.content.addView(card {
                    addView(onboardingSectionLabel("DEFAULT FOCUS TIME"))
                    addView(picker)
                })
                var pendingReaction: Runnable? = null
                picker.onDurationChanged = { millis ->
                    onboardingFocusMinutes = (millis / 60_000L).toInt()
                    pendingReaction?.let { diagnosticHandler.removeCallbacks(it) }
                    pendingReaction = Runnable {
                        val m = onboardingFocusMinutes
                        val line = when {
                            m < 10 -> "$m minutes? A tiny step is still a step! 🐣"
                            m < 20 -> "$m minutes. Short and sweet!"
                            m in 20..30 -> "$m minutes. The classic. Nice choice! 🍅"
                            m <= 60 -> "$m minutes of deep work? Love it! 💪"
                            else -> "Whoa, ${m / 60}h ${m % 60}m! Remember to stretch 🧘"
                        }
                        stage.pip.hop()
                        pipSays(line)
                    }.also { diagnosticHandler.postDelayed(it, 650) }
                }
                setOnboardingButtons(page, back, onboardingPrimaryButton("Next") {
                    repository.setDefaultFocusMinutes(onboardingFocusMinutes)
                    onboardingSetupPage = 2
                    showOnboardingSettings()
                })
                stage.postDelayed(450) {
                    pipSays(
                        if (onboardingFocusMinutes == 25) "25 minutes is a classic. Spin the wheels to choose yours!"
                        else "Spin the wheels to pick your focus length. Not sure? 25 minutes is a classic!",
                        keep = true
                    )
                }
            }
            else -> {
                page.content.addView(onboardingTitle(
                    "When should I check in?",
                    "If you've been in a feed nonstop for this long, I'll gently remind you to take a break."
                ))
                val value = TextView(this).apply {
                    textSize = 34f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(CORAL_DARK)
                    gravity = Gravity.CENTER
                }
                fun showValue() { value.text = "$onboardingMarathonMinutes min" }
                showValue()
                page.content.addView(card {
                    addView(onboardingSectionLabel("BREAK REMINDER"))
                    addView(value, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
                    addView(SeekBar(this@MainActivity).apply {
                        max = TrackingTimerSettings.MAX_MARATHON_MINUTES - TrackingTimerSettings.MIN_MARATHON_MINUTES
                        progress = onboardingMarathonMinutes - TrackingTimerSettings.MIN_MARATHON_MINUTES
                        progressTintList = android.content.res.ColorStateList.valueOf(CORAL)
                        thumbTintList = android.content.res.ColorStateList.valueOf(CORAL)
                        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                                onboardingMarathonMinutes = progress + TrackingTimerSettings.MIN_MARATHON_MINUTES
                                showValue()
                            }

                            override fun onStartTrackingTouch(seekBar: SeekBar) {
                                stage.pip.lookToward(0f, 1f)
                            }

                            override fun onStopTrackingTouch(seekBar: SeekBar) {
                                val m = onboardingMarathonMinutes
                                stage.pip.hop()
                                pipSays(when {
                                    m <= 10 -> "Ooh, strict! I'll be quick to tap your shoulder."
                                    m <= 30 -> "$m minutes sounds nicely balanced 👌"
                                    m <= 60 -> "Okay, I'll be patient. $m minutes it is."
                                    else -> "That's a long scroll… but I'll still be here 😌"
                                })
                            }
                        })
                    })
                    addView(LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        addView(label("${TrackingTimerSettings.MIN_MARATHON_MINUTES} min", MUTED, 12f),
                            LinearLayout.LayoutParams(0, -2, 1f))
                        addView(label("${TrackingTimerSettings.MAX_MARATHON_MINUTES} min", MUTED, 12f))
                    })
                })
                setOnboardingButtons(page, back, onboardingPrimaryButton("Start the tour") {
                    repository.setDefaultFocusMinutes(onboardingFocusMinutes)
                    repository.setMarathonMinutes(onboardingMarathonMinutes)
                    repository.setSelectedPackages(onboardingSelectedPackages)
                    setupStage?.release()
                    setupStage = null
                    onboardingTourStep = 0
                    showOnboardingTourStep()
                })
                stage.postDelayed(450) {
                    pipSays("Last one! When you've scrolled for a while, I'll gently tap your shoulder.", keep = true)
                }
            }
        }

        onboardingScreen = page.root
        setContentView(page.root)
    }

    /** Fills the onboarding app list; called again once installed apps finish loading. */
    private fun fillOnboardingAppList(list: LinearLayout, stage: PipStage?) {
        list.removeAllViews()
        val apps = installedTrackedAppsCache
        if (apps == null) {
            list.addView(label("Looking for your apps…", MUTED, 14f).apply { setPadding(0, dp(8), 0, dp(8)) })
            return
        }
        if (apps.isEmpty()) {
            list.addView(label("No supported apps were found on this phone.", MUTED, 14f))
            return
        }
        // Supported short-form feeds first; everything else below.
        val feedPackages = FeedPlatform.entries.map { it.packageName }.toSet()
        val (feeds, others) = apps.partition { it.packageName in feedPackages }
        val ordered = feeds + others
        ordered.forEachIndexed { index, app ->
            if (index == 0 && feeds.isNotEmpty()) list.addView(onboardingSectionLabel("POPULAR FEEDS"))
            if (index == feeds.size && others.isNotEmpty()) {
                list.addView(onboardingSectionLabel("OTHER APPS").apply {
                    if (feeds.isNotEmpty()) setPadding(0, dp(16), 0, dp(8))
                })
            }
            val check = TextView(this).apply {
                textSize = 14f
                gravity = Gravity.CENTER
                setTypeface(typeface, Typeface.BOLD)
            }
            fun paint(selected: Boolean) {
                check.text = if (selected) "✓" else ""
                check.setTextColor(Color.WHITE)
                check.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    if (selected) setColor(CORAL) else {
                        setColor(Color.WHITE)
                        setStroke(dp(2), Color.parseColor("#E3D6CE"))
                    }
                }
            }
            paint(app.packageName in onboardingSelectedPackages)
            list.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), dp(8), dp(4), dp(8))
                isClickable = true
                contentDescription = app.label
                addView(ImageView(this@MainActivity).apply {
                    setImageDrawable(app.icon)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                }, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(14) })
                addView(label(app.label, INK, 16f), LinearLayout.LayoutParams(0, -2, 1f))
                addView(check, LinearLayout.LayoutParams(dp(26), dp(26)))
                setOnClickListener {
                    val selected = app.packageName !in onboardingSelectedPackages
                    if (selected) onboardingSelectedPackages.add(app.packageName)
                    else onboardingSelectedPackages.remove(app.packageName)
                    paint(selected)
                    check.animate().scaleX(1.25f).scaleY(1.25f).setDuration(110)
                        .withEndAction { check.animate().scaleX(1f).scaleY(1f).setDuration(140).start() }
                        .start()
                    stage?.let { s ->
                        val c = s.centerOf(this)
                        val p = s.centerOf(s.pip)
                        s.pip.lookToward(c.x - p.x, c.y - p.y)
                        if (selected) s.pip.hop()
                        s.say(
                            if (selected) "Got it, I'll keep an eye on ${app.label} 👀"
                            else "Okay, ${app.label} is off my list.",
                            placement = PipStage.Placement.RIGHT
                        )
                    }
                }
            })
            if (index < ordered.lastIndex && index != feeds.size - 1) list.addView(divider())
        }
    }

    private fun showOnboardingTourStep() {
        if (onboardingTourStep !in onboardingTourSteps.indices) {
            finishOnboarding()
            return
        }
        val step = onboardingTourSteps[onboardingTourStep]

        onboardingScreen = null
        if (!::rootFrame.isInitialized || rootFrame.parent == null) {
            setContentView(buildScreen())
        }

        selectedTab = step.tab
        renderTab()

        // Lock manual user scrolling during tour
        pageScroll.setOnTouchListener { _, _ -> true }

        val targetView = pageContent.getChildAt(step.cardIndex)
        tourOverlayView?.let { rootFrame.removeView(it) }
        val overlay = SpotlightTourOverlay(this, targetView)
        rootFrame.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        tourOverlayView = overlay

        val companion = pipCompanion ?: return
        companion.guideMode = true
        val stage = companion.stage
        stage.bringToFront()
        stage.hideBubble(immediately = true)

        pageScroll.postDelayed({
            val targetTop = ((targetView?.top ?: 0) - dp(16)).coerceAtLeast(0)
            pageScroll.smoothScrollTo(0, targetTop)
            // Wait for the scroll to settle before Pip flies over to explain.
            pageScroll.postDelayed({
                overlay.invalidate()
                val isLast = onboardingTourStep == onboardingTourSteps.lastIndex
                val actions = buildList {
                    if (onboardingTourStep > 0) add(PipAction("Back") {
                        onboardingTourStep--
                        showOnboardingTourStep()
                    })
                    add(PipAction("Skip") { finishOnboarding() })
                    add(PipAction(if (isLast) "Finish 🎉" else "Next", primary = true) {
                        stage.pip.playCheer()
                        if (isLast) finishOnboarding() else {
                            onboardingTourStep++
                            showOnboardingTourStep()
                        }
                    })
                }
                stage.guide(targetView, step.message, title = step.title, badge = step.badge, actions = actions)
            }, 380L)
        }, 80L)
    }

    private fun finishOnboarding() {
        repository.completeOnboarding()
        if (::pageScroll.isInitialized) {
            pageScroll.setOnTouchListener(null)
        }
        tourOverlayView?.let { rootFrame.removeView(it) }
        tourOverlayView = null
        setupStage?.release()
        setupStage = null
        onboardingScreen = null
        selectedTab = DashboardTab.OVERVIEW
        snapshot = repository.snapshot()
        setContentView(buildScreen())
        renderAppState()
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

    private fun showMorningRecapIfNeeded() {
        if (morningRecapDialog?.isShowing == true || !repository.shouldShowMorningRecap()) return
        val recap = repository.morningRecap()
        val dialog = Dialog(this)
        morningRecapDialog = dialog
        // buildMorningRecap installs the dismiss listener (marks the recap seen, releases Pip).
        dialog.setContentView(buildMorningRecap(recap, dialog))
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(CREAM))
            setLayout(
                android.view.WindowManager.LayoutParams.MATCH_PARENT,
                android.view.WindowManager.LayoutParams.MATCH_PARENT
            )
            statusBarColor = CREAM
            navigationBarColor = CREAM
            decorView.systemUiVisibility =
                decorView.systemUiVisibility or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }
    }

    private fun buildMorningRecap(recap: MorningRecap, dialog: Dialog): View {
        val root = FrameLayout(this).apply { setBackgroundColor(CREAM) }
        val scrollView = ScrollView(this).apply {
            clipToPadding = false
            setPadding(0, 0, 0, dp(184))
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(12))
        }
        scrollView.addView(content)
        root.addView(scrollView, FrameLayout.LayoutParams(-1, -1))

        content.addView(label("YESTERDAY · ${detailDate(recap.date).uppercase(Locale.getDefault())}", CORAL_DARK, 12f))
        content.addView(TextView(this).apply {
            text = "A little look back"
            textSize = 28f
            setTextColor(INK)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(6), 0, dp(12))
        })
        // Pip's perch for the morning greeting; his bubble appears to the right of it.
        val pipHome = View(this)
        content.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(pipHome, LinearLayout.LayoutParams(dp(96), dp(96)))
        }, LinearLayout.LayoutParams(-1, dp(132)))

        val focusCards = mutableListOf<View>()
        val streakCard = card {
            addView(label("YOUR BURNING STREAK", CORAL_DARK, 12f))
            addView(GoalFlameView(this@MainActivity, recap.metGoals),
                LinearLayout.LayoutParams(-1, dp(112)))
            addView(metric(
                "🔥 ${recap.streak} ${if (recap.streak == 1) "day" else "days"}",
                28f
            ))
            addView(label(
                if (recap.metGoals) {
                    "You met both goals yesterday. Your flame is still burning."
                } else {
                    "Yesterday was a reset, not a verdict. Your best is ${recap.bestStreak} " +
                        if (recap.bestStreak == 1) "day." else "days."
                },
                MUTED,
                14f
            ).apply { setPadding(0, dp(4), 0, 0) })
        }
        content.addView(streakCard)
        focusCards += streakCard

        val usageCard = card {
            addView(label("TIME & SCROLLS", CORAL_DARK, 12f))
            addView(metricRow(
                "Supported-app time",
                formatDuration(recap.metrics.activeSeconds),
                "Scrolls",
                recap.metrics.scrollCount.toString()
            ))
            addView(metricRow("Sessions", recap.metrics.visits.toString(), null, null).apply {
                setPadding(0, dp(10), 0, 0)
            })
            addView(label(usageInsight(recap), MUTED, 14f).apply {
                setPadding(0, dp(8), 0, 0)
            })
        }
        content.addView(usageCard)
        focusCards += usageCard

        val habitCard = card {
            addView(label("YOUR MINDFUL PATTERN", CORAL_DARK, 12f))
            addView(metric(
                "${recap.metrics.htiScore.toInt()} / 100",
                27f
            ))
            addView(metricRow(
                "Quick checks",
                recap.metrics.microChecks.toString(),
                "Quick returns",
                recap.metrics.rapidReentries.toString()
            ))
            addView(metricRow(
                "Long sessions",
                formatDuration(recap.metrics.marathonSeconds),
                "Fast-scroll time",
                formatDuration(recap.metrics.agitationSeconds)
            ).apply { setPadding(0, dp(10), 0, 0) })
        }
        content.addView(habitCard)
        focusCards += habitCard

        val nextStepCard = card {
            addView(label("ONE GENTLE THOUGHT FOR TODAY", CORAL_DARK, 12f))
            addView(TextView(this@MainActivity).apply {
                text = recapSuggestion(recap)
                textSize = 16f
                setTextColor(INK)
                setPadding(0, dp(8), 0, 0)
            })
        }
        content.addView(nextStepCard)
        focusCards += nextStepCard

        lateinit var stage: PipStage
        val continueButton = actionButton("Let’s make today count") {
            stage.pip.playCheer()
            stage.postDelayed(450) { dialog.dismiss() }
        }.apply {
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            background = roundedBackground(CORAL, dp(16).toFloat())
            stateListAnimator = null
        }
        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(20), dp(18))
            setBackgroundColor(CREAM)
            addView(continueButton, LinearLayout.LayoutParams(-1, dp(54)))
        }
        root.addView(footer, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))

        stage = PipStage(this, root, pipHome, pipSizeDp = 96)
        stage.onPipTapped = { stage.pip.playCheer() }

        // Pip greets from the top, then flies down to narrate whichever card is in focus.
        var narrating = false
        var currentFocus = -1
        fun focusedCard(): Int? {
            if (focusCards.isEmpty() || root.height == 0) return null
            val marker = scrollView.scrollY + (scrollView.height * .35f).toInt()
            return focusCards.indices.minByOrNull { section ->
                val view = focusCards[section]
                when {
                    marker < view.top -> view.top - marker
                    marker > view.bottom -> marker - view.bottom
                    else -> 0
                }
            }
        }
        fun narrate(force: Boolean = false) {
            val index = focusedCard() ?: return
            if (index == currentFocus && !force) return
            currentFocus = index
            focusCards.forEachIndexed { cardIndex, view ->
                view.background = roundedBackground(
                    if (cardIndex == index) Color.parseColor("#FFF2CB") else Color.WHITE,
                    dp(18).toFloat()
                )
            }
            stage.bottomInset = footer.height
            stage.guide(
                focusCards[index],
                recapBuddyMessage(recap, index),
                dock = PipStage.Placement.DOCK_BOTTOM
            )
        }
        fun startNarrating() {
            if (narrating) return
            narrating = true
            // Leave room under the last card for the docked bubble.
            scrollView.setPadding(0, 0, 0, dp(330))
            narrate(force = true)
        }
        val settle = Runnable { if (narrating) narrate() }
        scrollView.setOnScrollChangeListener { _, _, _, _, _ ->
            if (!narrating) startNarrating()
            scrollView.removeCallbacks(settle)
            scrollView.postDelayed(settle, 220)
        }
        stage.postDelayed(450) {
            stage.pip.wave(1.8f)
            if (recap.metGoals) stage.pip.playCheer()
            stage.say(
                morningGreeting(recap),
                placement = PipStage.Placement.RIGHT,
                autoHideMs = 6_500L
            ) { stage.postDelayed(250) { startNarrating() } }
        }
        dialog.setOnDismissListener {
            stage.release()
            repository.markMorningRecapSeen(recap.date.plusDays(1))
            morningRecapDialog = null
        }
        return root
    }

    private fun morningGreeting(recap: MorningRecap): String {
        val messages = if (recap.metGoals) {
            listOf(
                "Great work looking after your attention yesterday. Your ${recap.streak}-day streak is glowing—let’s make today count too.",
                "You showed up for yourself yesterday. Let’s bring that same care into today; I’m right here with you.",
                "Yesterday’s effort matters, and so does starting fresh today. We’ll take it one moment at a time.",
                "You made space for what matters yesterday. Let’s keep that gentle momentum going today.",
                "Your choices yesterday added up. Whatever today brings, we can meet it together.",
                "That was a thoughtful day yesterday. Let’s carry one small win into this one.",
                "You’re building something steady, one day at a time. I’m here for today too."
            )
        } else {
            listOf(
                "Sometimes we lose our rhythm. That doesn’t erase your progress—today is a fresh start, and I’m with you.",
                "Yesterday was one day, not your whole story. Let’s make today count together, one small choice at a time.",
                "No guilt, just a new day. We can find our rhythm again together.",
                "A tough day can happen to anyone. Let’s start again gently—I’m right here.",
                "Yesterday doesn’t define you. One pause today can be a kind new beginning.",
                "You haven’t lost your progress. Let’s focus on the next small choice, together.",
                "Today is another chance to care for your attention. We’ll take it step by step."
            )
        }
        return messages[(recap.date.toEpochDay() % messages.size).toInt()]
    }

    private fun usageInsight(recap: MorningRecap): String {
        val yesterday = recap.metrics.activeSeconds
        val previous = recap.previousDayMetrics.activeSeconds
        if (recap.metrics.visits == 0) {
            return "No supported-app sessions were recorded yesterday. Quiet days count too."
        }
        if (recap.previousDayMetrics.visits == 0) {
            return "This is a useful starting point. A few more days will make your personal pattern clearer."
        }
        val difference = kotlin.math.abs(yesterday - previous)
        return when {
            yesterday < previous -> "That’s ${formatDuration(difference)} less time than the day before. Notice what helped."
            yesterday > previous -> "That’s ${formatDuration(difference)} more than the day before. If it felt like a lot, try one small pause today."
            else -> "Your time was about the same as the day before. Keep noticing what feels intentional."
        }
    }

    private fun recapSuggestion(recap: MorningRecap): String = when {
        recap.metGoals -> "You kept both intentions yesterday. Pick one small thing you want your attention for today, and protect a little space for it."
        recap.metrics.visits == 0 -> "Choose one moment today to check in with yourself before opening a feed. There’s no need to make up for yesterday."
        recap.metrics.marathonSeconds > 0 ->
            "A short break between sessions can help you reset. Try deciding what you want to do before you open a feed."
        recap.metrics.rapidReentries > 0 || recap.metrics.microChecks > 0 ->
            "When you notice an automatic check, take one breath and ask what you came to do. Even one pause is progress."
        else -> "Keep your day gentle: choose one intentional pause and give your attention to something that matters to you."
    }

    private fun recapBuddyMessage(recap: MorningRecap, section: Int): String = when (section) {
        0 -> if (recap.metGoals) {
            "Both goals met yesterday—your streak keeps burning."
        } else {
            "Your streak can start again with today's small choices."
        }
        1 -> usageInsight(recap)
        2 -> if (recap.metrics.visits > 0) {
            "This score is a guide to your pattern, not a grade."
        } else {
            "Your pattern will become clearer as tracking gathers days."
        }
        else -> recapSuggestion(recap)
    }

    private fun showFocusDurationPickerDialog() {
        var dialog: Dialog? = null
        val durationPicker = FocusDurationPicker(
            this,
            repository.defaultFocusMinutes() * 60_000L
        )
        val view = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
            background = roundedBackground(CREAM, dp(20).toFloat())

            addView(TextView(this@MainActivity).apply {
                text = "START FOCUS SESSION"
                textSize = 12f
                letterSpacing = 0.14f
                setTextColor(CORAL_DARK)
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
            })

            addView(TextView(this@MainActivity).apply {
                text = "Choose focus duration"
                textSize = 20f
                setTextColor(INK)
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(0, dp(6), 0, dp(12))
            })

            addView(durationPicker)

            addView(actionButton("Start Timer") {
                dialog?.dismiss()
                repository.startFocusSession(
                    durationPicker.durationMillis,
                    FocusSessionQuotes.random()
                )
                showFocusScreen(checkNotNull(repository.activeFocusSession()))
            }, LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(16)
            })
        }

        dialog = AlertDialog.Builder(this)
            .setView(view)
            .create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.show()
    }

    private fun showFocusHeartPopup() {
        val completedSeconds = repository.todayFocusSeconds()
        val completedMin = completedSeconds / 60
        val targetMin = repository.dailyFocusTargetMinutes()
        val fraction = (completedSeconds.toFloat() / (targetMin * 60f)).coerceIn(0f, 1f)
        val percent = (fraction * 100).toInt()

        val messages = listOf(
            "Take a focus break and fill your heart with mindfulness! 💚",
            "Every minute of intentional focus brings calm and clarity to your day.",
            "Give your mind room to breathe. Ready to add green liquid to your heart?",
            "Small moments of presence build a peaceful mind. Start a timer!",
            "Be productive today to fill your heart with focus and peace."
        )
        val selectedMessage = messages[(completedMin % messages.size).toInt()]

        var dialog: Dialog? = null
        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
            background = roundedBackground(CREAM, dp(20).toFloat())

            val popupHeart = FocusHeartView(this@MainActivity).apply {
                fillFraction = fraction
                showBubbles = false
            }
            addView(popupHeart, LinearLayout.LayoutParams(dp(80), dp(80)).apply {
                bottomMargin = dp(16)
            })

            addView(TextView(this@MainActivity).apply {
                text = "YOUR MINDFUL HEART"
                textSize = 12f
                letterSpacing = 0.14f
                setTextColor(CORAL_DARK)
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
            })

            addView(TextView(this@MainActivity).apply {
                text = selectedMessage
                textSize = 16f
                setTextColor(INK)
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(0, dp(10), 0, dp(12))
            })

            addView(TextView(this@MainActivity).apply {
                text = "Today's Focus: ${completedMin}m / ${targetMin}m ($percent% filled)"
                textSize = 13f
                setTextColor(MUTED)
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, dp(20))
            })

            addView(actionButton("Start Focus Session") {
                dialog?.dismiss()
                showFocusDurationPickerDialog()
            }, LinearLayout.LayoutParams(-1, -2))
        }

        dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.show()
    }

    private fun showFocusScreen(focusSession: FocusSession) {
        focusMode = true
        pipCompanion?.onPause()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(20), dp(24), dp(20))
            setBackgroundColor(CREAM)
        }
        root.addView(TextView(this).apply {
            text = brandTitleText()
            textSize = 12f
            letterSpacing = .18f
            setTextColor(CORAL_DARK)
            gravity = Gravity.CENTER
        })

        heroHeartView = FocusHeartView(this).apply {
            fillFraction = focusSession.progressFraction
            showBubbles = true
        }
        root.addView(heroHeartView, LinearLayout.LayoutParams(dp(160), dp(160)).apply {
            topMargin = dp(16)
            bottomMargin = dp(12)
        })

        root.addView(TextView(this).apply {
            text = "Your focus time"
            textSize = 22f
            setTextColor(INK)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
        })

        root.addView(TextView(this).apply {
            text = if (focusSession.isPaused) {
                "Focus paused during your short scroll break."
            } else {
                "One calm, intentional stretch. You’ve got this."
            }
            textSize = 13f
            setTextColor(MUTED)
            gravity = Gravity.CENTER
            setPadding(0, dp(2), 0, dp(2))
        }.also { focusStatusView = it })

        focusTimerView = TextView(this).apply {
            text = formatFocusCountdown(focusSession)
            textSize = 32f
            setTextColor(CORAL_DARK)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(2))
        }
        root.addView(focusTimerView)

        fastSpeedButton = TextView(this).apply {
            text = if (isFastSpeedEnabled) "⚡ 30x Fast ON" else "⚡ 30x Fast OFF"
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = roundedBackground(
                if (isFastSpeedEnabled) Color.parseColor("#D1FAE5") else Color.parseColor("#F3F4F6"),
                dp(12).toFloat()
            )
            setTextColor(if (isFastSpeedEnabled) Color.parseColor("#065F46") else Color.parseColor("#4B5563"))
            setOnClickListener {
                isFastSpeedEnabled = !isFastSpeedEnabled
                text = if (isFastSpeedEnabled) "⚡ 30x Fast ON" else "⚡ 30x Fast OFF"
                background = roundedBackground(
                    if (isFastSpeedEnabled) Color.parseColor("#D1FAE5") else Color.parseColor("#F3F4F6"),
                    dp(12).toFloat()
                )
                setTextColor(if (isFastSpeedEnabled) Color.parseColor("#065F46") else Color.parseColor("#4B5563"))
            }
        }
//        root.addView(fastSpeedButton, LinearLayout.LayoutParams(-2, -2).apply {
//            gravity = Gravity.CENTER_HORIZONTAL
//            bottomMargin = dp(8)
//        })

        scrollBreakContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = roundedBackground(Color.parseColor("#FFF3F0"), dp(16).toFloat())
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = dp(12)
            }
            visibility = if (focusSession.isPaused && focusSession.reminderAtMillis > 0L) View.VISIBLE else View.GONE

            addView(TextView(this@MainActivity).apply {
                text = "☕ SCROLL BREAK ACTIVE"
                textSize = 11f
                letterSpacing = 0.12f
                setTextColor(CORAL_DARK)
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
            })
            scrollBreakTimerView = TextView(this@MainActivity).apply {
                text = formatBreakCountdown(focusSession.reminderAtMillis)
                textSize = 20f
                setTextColor(INK)
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(0, dp(2), 0, dp(2))
            }
            addView(scrollBreakTimerView)
            addView(TextView(this@MainActivity).apply {
                text = "Enjoy your break! Focus will automatically resume when timer reaches 00:00."
                textSize = 11f
                setTextColor(MUTED)
                gravity = Gravity.CENTER
            })
        }
        root.addView(scrollBreakContainer)

        root.addView(card {
            addView(label("A NOTE TO CARRY WITH YOU", CORAL_DARK, 11f))
            addView(TextView(this@MainActivity).apply {
                text = "“${focusSession.quote}”"
                textSize = 16f
                setTextColor(INK)
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(6), 0, dp(2))
            })
        })

        root.addView(actionButton("End focus early") { leaveFocusMode() }.apply {
            setTextColor(CORAL_DARK)
            background = roundedBackground(Color.WHITE, dp(12).toFloat())
        }, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(16)
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
        isFastSpeedEnabled = false
        lastFocusTickNanos = 0L
        snapshot = repository.snapshot()
        setContentView(buildScreen())
        refresh()
        showMorningRecapIfNeeded()
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

    private fun formatBreakCountdown(reminderAtMillis: Long): String {
        val remainingMillis = (reminderAtMillis - System.currentTimeMillis()).coerceAtLeast(0L)
        val remainingSeconds = (remainingMillis + 999L) / 1_000L
        val minutes = remainingSeconds / 60L
        val seconds = remainingSeconds % 60L
        return String.format(Locale.US, "%02d:%02d remaining", minutes, seconds)
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
        }
        selectedAccent = when (tab) {
            DashboardTab.OVERVIEW -> CORAL
            DashboardTab.TIME -> TEAL
            DashboardTab.SCROLLS -> PURPLE
            DashboardTab.PROGRESS -> GREEN
        }
        dashboardRoot.setBackgroundColor(when (tab) {
            DashboardTab.OVERVIEW -> CREAM
            DashboardTab.TIME -> Color.parseColor("#F0FAF8")
            DashboardTab.SCROLLS -> Color.parseColor("#F6F3FC")
            DashboardTab.PROGRESS -> Color.parseColor("#F1F8F2")
        })
        pageScroll.scrollTo(0, 0)
        updateNavigation()
        animatePageIn()
    }

    private fun buildOverview() {
        val today = snapshot.dailyHistory.lastOrNull()
        val scrolls = today?.scrollCount ?: 0
        val activeSeconds = today?.activeSeconds ?: 0L
        val scrollTarget = repository.dailyScrollTarget()
        val timeTargetMinutes = repository.dailyTimeTargetMinutes()
        val onTrack = scrolls < scrollTarget && activeSeconds < timeTargetMinutes * 60L
        val score = (
            ((scrollTarget - scrolls).toDouble() / scrollTarget.coerceAtLeast(1)) +
                ((timeTargetMinutes * 60L - activeSeconds).toDouble() /
                    (timeTargetMinutes * 60L).coerceAtLeast(1L))
            ).coerceIn(0.0, 2.0) * 50.0
        val streak = repository.dailyGoalStreak()
        val bestStreak = repository.bestDailyGoalStreak()
        buddyView.setSad(!onTrack)

        val mutedUntilMillis = repository.breakRemindersMutedUntilMillis()
        if (mutedUntilMillis > 0L) {
            val remainingMinutes = ((mutedUntilMillis - System.currentTimeMillis() + 59_999L) / 60_000L)
            pageContent.addView(card {
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(TextView(this@MainActivity).apply {
                        text = "🔇"
                        textSize = 20f
                        contentDescription = "Muted"
                    }, LinearLayout.LayoutParams(dp(34), -2))
                    addView(label("BREAK REMINDERS MUTED", CORAL_DARK, 12f),
                        LinearLayout.LayoutParams(0, -2, 1f))
                })
                addView(label(
                    "Reminders are paused for ${formatDuration(remainingMinutes * 60L)}. Unmute any time to receive them again.",
                    MUTED,
                    14f
                ).apply { setPadding(0, dp(8), 0, dp(12)) })
                addView(actionButton("Unmute reminders") {
                    repository.unmuteBreakReminders()
                    refresh()
                    Toast.makeText(
                        this@MainActivity,
                        "Break reminders are unmuted",
                        Toast.LENGTH_SHORT
                    ).show()
                })
            })
        }

        pageContent.addView(card {
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(
                    label("TODAY'S GOALS", if (onTrack) GREEN else CORAL_DARK, 12f),
                    LinearLayout.LayoutParams(0, -2, 1f)
                )
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.END
                    addView(label("YOUR BEST STREAK", MUTED, 10f).apply {
                        gravity = Gravity.END
                    })
                    addView(TextView(this@MainActivity).apply {
                        text = "🔥 $bestStreak ${if (bestStreak == 1) "day" else "days"}"
                        textSize = 14f
                        setTextColor(INK)
                        setTypeface(typeface, Typeface.BOLD)
                        gravity = Gravity.END
                        contentDescription =
                            "Your all-time best streak: $bestStreak ${if (bestStreak == 1) "day" else "days"}"
                    })
                })
            })
            addView(GoalFlameView(this@MainActivity, onTrack), LinearLayout.LayoutParams(-1, dp(148)))
            addView(metric(if (onTrack) "Your flame is burning" else "A limit was crossed", 22f))
            addView(label(
                if (onTrack) "Both goals are still within reach." else
                    "The flame is resting. One tough day doesn’t undo your progress.",
                MUTED,
                14f
            ).apply { setPadding(0, dp(2), 0, dp(8)) })
            addView(metricRow(
                "Streak",
                "$streak ${if (streak == 1) "day" else "days"}",
                "Mindful score",
                "${score.toInt()} / 100"
            ))
            addView(TextView(this@MainActivity).apply {
                val quote = if (onTrack) {
                    dailyMotivationalQuote()
                } else {
                    "A hard day does not erase your progress. Pause, be kind to yourself, and begin again."
                }
                text = "Pip: “$quote”"
                textSize = 15f
                setTextColor(INK)
                setPadding(0, dp(14), 0, dp(2))
            })
            addView(label(
                "A tracked day adds to your streak at its end when both goals are met.",
                MUTED,
                12f
            ).apply { setPadding(0, dp(8), 0, 0) })
        })

        pageContent.addView(card {
            addView(label("YOUR DAILY LIMITS", CORAL_DARK, 12f))
            addView(metricRow(
                "Scrolls",
                "$scrolls / $scrollTarget",
                "App time",
                "${formatDuration(activeSeconds)} / ${formatDuration(timeTargetMinutes * 60L)}"
            ))
        })

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
                "App foreground",
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
            addView(label("while supported apps are in the foreground", MUTED, 14f))
        })
        pageContent.addView(sectionTitle("Daily app foreground time · last 15 days"))
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
                    "As you use supported apps, this chart will start to reveal your own pattern."
                } else {
                    "On days with supported app activity, your average foreground time is ${formatDuration(average)}. Use this as information, not a judgment."
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
        pageContent.addView(sectionTitle("Scrolls by app today"))
        pageContent.addView(card {
            addView(selectedAppInsights())
        })
        pageContent.addView(sectionTitle("Daily scrolls · last 15 days"))
        pageContent.addView(chartCard(
            points = history.map {
                ChartPoint(chartDate(it.date), it.scrollCount.toFloat(), detailDate(it.date))
            },
            style = ChartStyle.BARS,
            valueFormatter = { String.format(Locale.US, "%.0f", it) }
        ))
        pageContent.addView(sectionTitle("Recent supported app sessions"))
        pageContent.addView(activityFeedCard(snapshot.activities))
    }

    private fun buildProgressTab() {
        val history = snapshot.dailyHistory
        pageContent.addView(sectionTitle("Apps to track"))
        pageContent.addView(selectedAppsSettingsCard())
        pageContent.addView(sectionTitle("Daily goals"))
        pageContent.addView(dailyGoalSettingsCard())
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
                repository.defaultFocusMinutes() * 60_000L
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
            addView(label("TODAY'S HABIT TAPER INDEX (MINDFUL SCORE)", CORAL_DARK, 12f))
            val currentScoreFloat = history.lastOrNull()?.htiScore
            val scoreText = currentScoreFloat?.let { "${it.toInt()} / 100" } ?: "— / 100"
            addView(metric(scoreText, 34f))
            currentScoreFloat?.let { valScore ->
                val ratingLabel = when {
                    valScore >= 80 -> "🌟 Great balance & intentional scrolling"
                    valScore >= 55 -> "🌱 Steady control with a few long checks"
                    else -> "💡 Heavy feed loops today—a fresh start awaits"
                }
                addView(label(ratingLabel, INK, 14f).apply { setPadding(0, dp(2), 0, dp(6)) })
            }
            addView(TextView(this@MainActivity).apply {
                text = progressContext(history)
                textSize = 14f
                setTextColor(MUTED)
                setLineSpacing(dp(2).toFloat(), 1f)
                setPadding(0, dp(6), 0, 0)
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

    private fun selectedAppsSettingsCard(): View = card {
        addView(label("TRACK YOUR SELECTED APPS", CORAL_DARK, 12f))
        val selected = repository.selectedPackages().toMutableSet()
        addView(appSelectionRows(selected, readOnly = true).apply {
            setPadding(0, dp(8), 0, dp(12))
        })
        addView(actionButton("Choose apps") {
            val dialogSelection = repository.selectedPackages().toMutableSet()
            val appChoices = appSelectionRows(dialogSelection)
            val scrollView = ScrollView(this@MainActivity).apply {
                isFillViewport = true
                isVerticalScrollBarEnabled = true
                setPadding(dp(24), dp(8), dp(24), dp(8))
                addView(appChoices)
            }
            val dialog = AlertDialog.Builder(this@MainActivity)
                .setTitle("Apps to track")
                .setView(scrollView)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save", null)
                .create()
            dialog.setOnShowListener {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    if (dialogSelection.isEmpty()) {
                        Toast.makeText(
                            this@MainActivity,
                            "Select at least one app to track",
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        repository.setSelectedPackages(dialogSelection)
                        refresh()
                        dialog.dismiss()
                    }
                }
            }
            dialog.show()
        })
    }

    private fun appSelectionRows(
        selectedPackages: MutableSet<String>,
        readOnly: Boolean = false
    ): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val installedApps = installedTrackedApps()
            .filter { !readOnly || it.packageName in selectedPackages }
        installedApps.forEach { app ->
            val row = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), dp(5), dp(4), dp(5))
                val icon = ImageView(this@MainActivity).apply {
                    setImageDrawable(app.icon)
                    contentDescription = "${app.label} app icon"
                    scaleType = ImageView.ScaleType.FIT_CENTER
                }
                addView(icon, LinearLayout.LayoutParams(dp(40), dp(40)).apply {
                    marginEnd = dp(12)
                })
                if (readOnly) {
                    addView(label(app.label, INK, 15f), LinearLayout.LayoutParams(0, -2, 1f))
                    addView(label(
                        "${snapshot.scrollsByPackage[app.packageName] ?: 0} scrolls",
                        MUTED,
                        13f
                    ))
                } else {
                    val checkBox = CheckBox(this@MainActivity).apply {
                        text = app.label
                        textSize = 15f
                        isChecked = app.packageName in selectedPackages
                        setOnCheckedChangeListener { _, checked ->
                            if (checked) selectedPackages.add(app.packageName)
                            else selectedPackages.remove(app.packageName)
                        }
                    }
                    addView(checkBox, LinearLayout.LayoutParams(0, -2, 1f))
                }
            }
            addView(row)
            if (app != installedApps.lastOrNull()) addView(divider())
        }
        if (installedTrackedAppsCache == null) {
            addView(label("Loading installed apps…", MUTED, 14f))
        } else if (installedApps.isEmpty()) {
            addView(label(
                if (readOnly) "No selected apps are currently installed." else
                    "No launchable apps were found.",
                MUTED,
                14f
            ))
        }
    }

    private fun selectedAppInsights(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        if (installedTrackedAppsCache == null) {
            addView(label("Loading app insights…", MUTED, 14f))
            return@apply
        }
        val selectedPackages = repository.selectedPackages()
        val installedByPackage = installedTrackedApps().associateBy { it.packageName }
        val installedSelectedApps = selectedPackages
            .mapNotNull(installedByPackage::get)
            .sortedBy { it.label.lowercase(Locale.getDefault()) }
        installedSelectedApps.forEachIndexed { index, app ->
                val row = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    val icon = ImageView(this@MainActivity).apply {
                        setImageDrawable(app.icon)
                        contentDescription = "${app.label} app icon"
                        scaleType = ImageView.ScaleType.FIT_CENTER
                    }
                    addView(icon, LinearLayout.LayoutParams(dp(36), dp(36)).apply {
                        marginEnd = dp(12)
                    })
                    addView(LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(label(app.label, MUTED, 13f))
                        addView(metric(
                            "${snapshot.scrollsByPackage[app.packageName] ?: 0} scrolls",
                            20f
                        ))
                    }, LinearLayout.LayoutParams(0, -2, 1f))
                }
                addView(row)
                if (index < installedSelectedApps.lastIndex) addView(divider())
            }
        if (installedSelectedApps.isEmpty()) {
            addView(label("No selected app scrolls yet.", MUTED, 14f))
        }
    }

    private fun installedTrackedApps(): List<InstalledTrackedApp> {
        return installedTrackedAppsCache.orEmpty()
    }

    private fun queryInstalledTrackedApps(): List<InstalledTrackedApp> {
        val launchIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(launchIntent, 0)
            .asSequence()
            .map { resolveInfo ->
                val packageName = resolveInfo.activityInfo.packageName
                InstalledTrackedApp(
                    packageName = packageName,
                    label = resolveInfo.loadLabel(packageManager).toString(),
                    icon = resolveInfo.loadIcon(packageManager)
                )
            }
            .filter { it.packageName != packageName }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase(Locale.getDefault()) }
            .toList()
    }

    private fun dailyGoalSettingsCard(): View = card {
        addView(label("SET YOUR DAILY LIMITS", CORAL_DARK, 12f))
        lateinit var goalsDisplay: LinearLayout
        lateinit var goalsEditor: LinearLayout
        goalsDisplay = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(metricRow(
                "Scroll limit",
                "${repository.dailyScrollTarget()} scrolls",
                "Time limit",
                formatDuration(repository.dailyTimeTargetMinutes() * 60L)
            ))
            addView(actionButton("Edit goals") {
                goalsDisplay.visibility = View.GONE
                goalsEditor.visibility = View.VISIBLE
            }.apply {
                setTextColor(CORAL_DARK)
                background = roundedBackground(Color.parseColor("#FFF1ED"), dp(12).toFloat())
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        }
        goalsEditor = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        val scrollPicker = NumberPicker(this@MainActivity).apply {
            minValue = TrackingTimerSettings.MIN_DAILY_SCROLL_TARGET
            maxValue = TrackingTimerSettings.MAX_DAILY_SCROLL_TARGET
            value = repository.dailyScrollTarget()
            wrapSelectorWheel = false
            descendantFocusability = NumberPicker.FOCUS_BLOCK_DESCENDANTS
            contentDescription = "Daily scroll goal"
        }
        val timePicker = NumberPicker(this@MainActivity).apply {
            minValue = TrackingTimerSettings.MIN_DAILY_TIME_TARGET_MINUTES
            maxValue = TrackingTimerSettings.MAX_DAILY_TIME_TARGET_MINUTES
            value = repository.dailyTimeTargetMinutes()
            wrapSelectorWheel = false
            descendantFocusability = NumberPicker.FOCUS_BLOCK_DESCENDANTS
            contentDescription = "Daily supported-app foreground time goal in minutes"
        }
        val pickers = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                addView(label("Scrolls", MUTED, 13f), LinearLayout.LayoutParams(-1, -2))
                addView(scrollPicker, LinearLayout.LayoutParams(-1, dp(110)))
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                addView(label("Minutes", MUTED, 13f), LinearLayout.LayoutParams(-1, -2))
                addView(timePicker, LinearLayout.LayoutParams(-1, dp(110)))
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        goalsEditor.addView(pickers)
        addView(label(
            "A tracked day counts toward your streak when both totals stay below these limits.",
            MUTED,
            13f
        ).apply { setPadding(0, dp(4), 0, dp(10)) })
        goalsEditor.addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(actionButton("Save") {
                repository.setDailyGoals(scrollPicker.value, timePicker.value)
                refresh()
                Toast.makeText(this@MainActivity, "Daily goals saved", Toast.LENGTH_SHORT).show()
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(actionButton("Cancel") {
                goalsEditor.visibility = View.GONE
                goalsDisplay.visibility = View.VISIBLE
            }.apply {
                setTextColor(CORAL_DARK)
                background = roundedBackground(Color.parseColor("#FFF1ED"), dp(12).toFloat())
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8) })
        })
        addView(goalsDisplay)
        addView(goalsEditor)
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
                "${TrackingTimerSettings.MAX_MARATHON_MINUTES} minutes in supported apps",
            MUTED,
            12f
        ))

    }



    private fun activityFeedCard(activities: List<ScrollActivity>): View = card {
        if (activities.isEmpty()) {
            addView(label("Supported-app foreground sessions will show up here.", MUTED, 14f))
            return@card
        }
        val appsByPackage = installedTrackedApps().associateBy { it.packageName }
        activities.take(10).forEachIndexed { index, activity ->
            val appName = appsByPackage[activity.packageName]?.label
                ?: FeedPlatform.entries.firstOrNull { it.packageName == activity.packageName }?.label
                ?: activity.packageName
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
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                appsByPackage[activity.packageName]?.let { app ->
                    addView(ImageView(this@MainActivity).apply {
                        setImageDrawable(app.icon)
                        contentDescription = "$appName app icon"
                        scaleType = ImageView.ScaleType.FIT_CENTER
                    }, LinearLayout.LayoutParams(dp(36), dp(36)).apply {
                        marginEnd = dp(10)
                    })
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
                }, LinearLayout.LayoutParams(0, -2, 1f))
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

    /** What Pip needs to know about the app; Pip never touches the repository directly. */
    private val pipHost = object : PipCompanion.Host {
        override fun focusFraction(): Float {
            val targetSeconds = repository.dailyFocusTargetMinutes() * 60f
            return (repository.todayFocusSeconds() / targetSeconds).coerceIn(0f, 1f)
        }

        override fun todayFocusMinutes(): Int = (repository.todayFocusSeconds() / 60L).toInt()

        override fun defaultFocusMinutes(): Int = repository.defaultFocusMinutes()

        override fun onStartFocusRequested() {
            showFocusDurationPickerDialog()
        }

        override fun isBusyElsewhere(): Boolean =
            !activityResumed || focusMode || tourOverlayView != null ||
                morningRecapDialog?.isShowing == true
    }

    private fun dailyMotivationalQuote(): String {
        val quotes = listOf(
            "Every mindful choice feeds your flame. Keep choosing what matters.",
            "Your attention is yours to guide, one small moment at a time.",
            "You are building a habit, not chasing perfection.",
            "A little awareness today can make room for what matters tomorrow."
        )
        return quotes[java.time.LocalDate.now().dayOfYear % quotes.size]
    }

    private inner class GoalFlameView(
        context: android.content.Context,
        private val burning: Boolean
    ) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var animationAttached = false
        private var animationStartedAt = 0L

        init {
            contentDescription = if (burning) {
                "A live animated flame showing both daily goals are on track."
            } else {
                "Live smoke animation above an extinguished flame because a daily goal was crossed."
            }
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            animationAttached = true
            animationStartedAt = android.os.SystemClock.uptimeMillis()
            invalidate()
        }

        override fun onDetachedFromWindow() {
            animationAttached = false
            super.onDetachedFromWindow()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val scale = minOf(width / 120f, height / 140f)
            canvas.save()
            canvas.translate(width / 2f, height / 2f + dp(6))
            canvas.scale(scale, scale)
            paint.style = Paint.Style.FILL
            val elapsedMillis = android.os.SystemClock.uptimeMillis() - animationStartedAt

            if (burning) {
                val firePhase = (elapsedMillis % FIRE_ANIMATION_MILLIS).toFloat() /
                    FIRE_ANIMATION_MILLIS
                val wave = kotlin.math.sin(firePhase * Math.PI * 2).toFloat()
                val flicker = (wave + 1f) / 2f
                val sway = wave * 9f
                paint.color = Color.argb(
                    (35f + flicker * 35f).toInt(),
                    242,
                    107,
                    53
                )
                canvas.drawOval(RectF(-38f, -31f - flicker * 5f, 38f, 57f), paint)
                paint.color = Color.parseColor("#F26B35")
                paint.shader = android.graphics.LinearGradient(
                    0f,
                    -42f,
                    0f,
                    52f,
                    intArrayOf(
                        Color.parseColor("#FFF7B0"),
                        Color.parseColor("#FFB52E"),
                        Color.parseColor("#F26B35"),
                        Color.parseColor("#C93C24")
                    ),
                    null,
                    android.graphics.Shader.TileMode.CLAMP
                )
                canvas.drawPath(flamePath(firePhase, 1f, -38f, 51f), paint)
                paint.shader = null
                paint.shader = android.graphics.LinearGradient(
                    0f,
                    -10f,
                    0f,
                    51f,
                    intArrayOf(
                        Color.parseColor("#FFFDE0"),
                        Color.parseColor("#FFE36A"),
                        Color.parseColor("#FF9A24")
                    ),
                    null,
                    android.graphics.Shader.TileMode.CLAMP
                )
                canvas.drawPath(flamePath(firePhase + .24f, .53f, -13f, 51f), paint)
                paint.shader = null
                paint.color = Color.parseColor("#FFFBE0")
                val coreWidth = 7f + flicker * 3f
                canvas.drawOval(
                    RectF(-coreWidth + sway * .2f, 27f - flicker * 3f,
                        coreWidth + sway * .2f, 49f),
                    paint
                )
                paint.color = GOLD
                for (index in 0..2) {
                    val emberPhase = (firePhase + index / 3f) % 1f
                    val emberY = 27f - emberPhase * 58f
                    val emberX = kotlin.math.sin((emberPhase + index) * Math.PI * 2).toFloat() * 22f
                    paint.alpha = ((1f - emberPhase) * 210f).toInt().coerceIn(0, 210)
                    canvas.drawCircle(emberX, emberY, 1.5f + (1f - emberPhase), paint)
                }
                paint.alpha = 255
            } else {
                val smokePhase = (elapsedMillis % SMOKE_ANIMATION_MILLIS).toFloat() /
                    SMOKE_ANIMATION_MILLIS
                drawSmokeColumn(canvas, smokePhase, -9f, .0f)
                drawSmokeColumn(canvas, (smokePhase + .5f) % 1f, 9f, .35f)
            }
            paint.color = Color.parseColor("#8C5D47")
            canvas.drawRoundRect(RectF(-30f, 51f, 30f, 59f), 4f, 4f, paint)
            canvas.restore()
            if (animationAttached) postInvalidateOnAnimation()
        }

        private fun drawSmokeColumn(
            canvas: Canvas,
            phase: Float,
            initialDrift: Float,
            phaseOffset: Float
        ) {
            val path = Path()
            val steps = 44
            for (index in 0..steps) {
                val progress = index / steps.toFloat()
                val y = 45f - progress * 92f
                val amplitude = 4f + progress * 9f
                val wave = kotlin.math.sin(
                    (progress * 2.6f - phase + phaseOffset) * Math.PI * 2
                ).toFloat()
                val secondaryWave = kotlin.math.sin(
                    (progress * 5.1f + phase * .7f + phaseOffset) * Math.PI * 2
                ).toFloat()
                val x = initialDrift + wave * amplitude + secondaryWave * 2.5f * progress
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }

            paint.style = Paint.Style.STROKE
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeJoin = Paint.Join.ROUND
            paint.strokeWidth = 19f
            paint.shader = android.graphics.LinearGradient(
                0f,
                45f,
                0f,
                -47f,
                intArrayOf(
                    Color.argb(0, 125, 135, 145),
                    Color.argb(95, 125, 135, 145),
                    Color.argb(55, 145, 152, 160),
                    Color.argb(0, 145, 152, 160)
                ),
                null,
                android.graphics.Shader.TileMode.CLAMP
            )
            canvas.drawPath(path, paint)
            paint.shader = null
            paint.style = Paint.Style.FILL
        }

        private fun flamePath(
            phase: Float,
            widthScale: Float,
            tipY: Float,
            baseY: Float
        ): Path {
            val path = Path()
            val steps = 28
            val height = baseY - tipY
            path.moveTo(-3f * widthScale, baseY)
            for (index in 0..steps) {
                val t = 1f - index / steps.toFloat()
                val y = tipY + height * t
                val envelope = kotlin.math.sin(t * Math.PI).toFloat()
                val halfWidth = (5f + 26f * envelope.pow(.72f)) * widthScale
                val edgeWave = kotlin.math.sin(phase * Math.PI * 2 + t * Math.PI * 5).toFloat()
                val centerDrift = kotlin.math.sin(phase * Math.PI * 2 + t * Math.PI * 1.7).toFloat() *
                    5f * envelope
                val leftEdge = centerDrift - halfWidth + edgeWave * 3.5f * envelope * widthScale
                path.lineTo(leftEdge, y)
            }
            for (index in 0..steps) {
                val t = index / steps.toFloat()
                val y = tipY + height * t
                val envelope = kotlin.math.sin(t * Math.PI).toFloat()
                val halfWidth = (5f + 26f * envelope.pow(.72f)) * widthScale
                val edgeWave = kotlin.math.sin(phase * Math.PI * 2 + t * Math.PI * 5 + 1.1).toFloat()
                val centerDrift = kotlin.math.sin(phase * Math.PI * 2 + t * Math.PI * 1.7).toFloat() *
                    5f * envelope
                val rightEdge = centerDrift + halfWidth + edgeWave * 3.5f * envelope * widthScale
                path.lineTo(rightEdge, y)
            }
            path.lineTo(3f * widthScale, baseY)
            path.close()
            return path
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
            changePercent >= 5 -> "Your supported-app foreground time is down about $changePercent% compared with the previous week. That steady effort is worth celebrating."
            changePercent > -5 -> "Your tracked pattern is holding fairly steady. If you want, try one small intentional pause today; there’s no need to change everything at once."
            else -> "Your supported-app foreground time has been higher lately. That’s information, not failure—one small pause today can be a fresh start."
        }
    }

    private fun progressContext(history: List<HabitDay>): String {
        val earlierDays = history.dropLast(1).filter { it.visits > 0 }
        return if (earlierDays.size < 7) {
            "The Habit Taper Index is your daily 0–100 mindful scrolling score. Higher scores mean taking longer breaks between app opens, avoiding marathon sessions, and scrolling at a calm pace. Because you're in your first week, this is an early estimate while your 7-day baseline grows!"
        } else {
            "Your score measures how intentional your scrolling was today. It increases when you space out your app opens, keep sessions under 20 minutes, and avoid fast compulsive flicking. Focus on your 15-day trend rather than judging any single day."
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

    private fun appVersionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName?.trim().orEmpty()
    } catch (_: Exception) {
        ""
    }

    private fun brandTitleText(): String {
        val version = appVersionName()
        return if (version.isNotBlank()) "SCROLL STARVA · v$version" else "SCROLL STARVA"
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

    /**
     * Color themes, layout tags, step indices, and refresh timing constants for main activity.
     */
    private companion object {
        /** Interval in milliseconds for refreshing diagnostic event logs. */
        const val DIAGNOSTIC_REFRESH_MILLIS = 750L

        /** Interval in milliseconds for updating active focus timer countdown. */
        const val FOCUS_TIMER_REFRESH_MILLIS = 200L

        /** Duration in milliseconds for flame animation cycles. */
        const val FIRE_ANIMATION_MILLIS = 850L

        /** Duration in milliseconds for smoke animation cycles. */
        const val SMOKE_ANIMATION_MILLIS = 2_100L

        /** Onboarding step index representing the initial settings & timer configuration screen. */
        const val ONBOARDING_SETTINGS_STEP = -1

        /** Layout tag identifying the tracking setup / consent screen. */
        const val TRACKING_SETUP_TAG = "tracking_setup"

        /** Primary cream background color. */
        val CREAM = Color.parseColor("#FFF8EF")

        /** Primary ink text color. */
        val INK = Color.parseColor("#17202A")

        /** Muted subtitle text color. */
        val MUTED = Color.parseColor("#65727E")

        /** Coral primary accent color. */
        val CORAL = Color.parseColor("#F26B5E")

        /** Dark coral header/badge accent color. */
        val CORAL_DARK = Color.parseColor("#C94D43")

        /** Success green accent color. */
        val GREEN = Color.parseColor("#287A57")

        /** Time tab teal accent color. */
        val TEAL = Color.parseColor("#2C9A91")

        /** Scrolls tab purple accent color. */
        val PURPLE = Color.parseColor("#8371C8")

        /** Gold streak/award accent color. */
        val GOLD = Color.parseColor("#F5C451")
    }
}
