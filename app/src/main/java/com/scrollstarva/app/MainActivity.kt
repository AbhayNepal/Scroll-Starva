package com.scrollstarva.app

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale

class MainActivity : android.app.Activity() {
    private lateinit var repository: TrackingRepository
    private lateinit var scrollCount: TextView
    private lateinit var timeCount: TextView
    private lateinit var distanceCount: TextView
    private lateinit var status: TextView
    private val platformScrollCounts = mutableMapOf<FeedPlatform, TextView>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = TrackingRepository(this)
        setContentView(buildScreen())
    }

    override fun onResume() {
        super.onResume()
        if (::repository.isInitialized) refresh()
    }

    private fun buildScreen(): View {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(28), dp(24), dp(24))
            setBackgroundColor(Color.parseColor("#FFF8EF"))
        }
        content.addView(TextView(this).apply {
            text = "SCROLL STARVA"
            textSize = 13f
            setTextColor(Color.parseColor("#C94D43"))
            letterSpacing = .18f
        })
        content.addView(TextView(this).apply {
            text = "How far did\nyour thumb travel?"
            textSize = 34f
            setTextColor(Color.parseColor("#17202A"))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(12), 0, dp(24))
        })
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
            setBackgroundColor(Color.WHITE)
        }
        distanceCount = metric("0.000 km", 38f)
        card.addView(distanceCount)
        card.addView(label("estimated physical distance today"))
        content.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })

        val metrics = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        scrollCount = metric("0", 24f)
        timeCount = metric("0m", 24f)
        metrics.addView(metricBlock(scrollCount, "scrolls"))
        metrics.addView(metricBlock(timeCount, "feed time"), LinearLayout.LayoutParams(0, -2, 1f))
        content.addView(metrics)

        content.addView(TextView(this).apply {
            text = "SCROLLS BY APP"
            textSize = 13f
            setTextColor(Color.parseColor("#C94D43"))
            letterSpacing = .12f
            setPadding(0, dp(28), 0, dp(8))
        })
        content.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
            setBackgroundColor(Color.WHITE)
            FeedPlatform.entries.forEach { platform ->
                val count = TextView(this@MainActivity).apply {
                    text = "0"
                    textSize = 16f
                    setTextColor(Color.parseColor("#17202A"))
                }
                platformScrollCounts[platform] = count
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(8), 0, dp(8))
                    addView(TextView(this@MainActivity).apply {
                        text = platform.label
                        textSize = 14f
                        setTextColor(Color.parseColor("#65727E"))
                    }, LinearLayout.LayoutParams(0, -2, 1f))
                    addView(count)
                })
            }
        }, LinearLayout.LayoutParams(-1, -2))

        status = label("")
        status.setPadding(0, dp(28), 0, dp(12))
        content.addView(status)
        content.addView(Button(this).apply {
            text = "Enable tracking"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#F26B5E"))
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }, LinearLayout.LayoutParams(-1, dp(52)))
        content.addView(TextView(this).apply {
            text = "Scroll Starva only reads visible accessibility labels from TikTok, YouTube, Instagram, and Facebook to recognize short-form feeds. Nothing leaves your device."
            textSize = 12f
            setTextColor(Color.parseColor("#65727E"))
            setPadding(0, dp(18), 0, 0)
        })
        return ScrollView(this).apply { addView(content) }
    }

    private fun refresh() {
        val snapshot = repository.snapshot()
        distanceCount.text = String.format(Locale.US, "%.3f km", snapshot.distanceKm)
        scrollCount.text = snapshot.scrolls.toString()
        platformScrollCounts.forEach { (platform, count) ->
            count.text = snapshot.scrollsByPlatform[platform]?.toString() ?: "0"
        }
        timeCount.text = formatMinutes(snapshot.activeMillis)
        status.text = if (isAccessibilityEnabled()) "Tracking is on" else "Tracking is off - enable accessibility access to begin"
    }

    private fun isAccessibilityEnabled(): Boolean =
        Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?.contains(packageName, ignoreCase = true) == true

    private fun metric(text: String, size: Float) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(Color.parseColor("#17202A"))
        setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun metricBlock(value: TextView, title: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.START
        addView(value)
        addView(label(title))
    }

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(Color.parseColor("#65727E"))
    }

    private fun formatMinutes(millis: Long): String {
        val minutes = millis / 60_000
        return if (minutes < 60) "${minutes}m" else "${minutes / 60}h ${minutes % 60}m"
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
