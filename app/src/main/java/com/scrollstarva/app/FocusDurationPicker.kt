package com.scrollstarva.app

import android.content.Context
import android.view.Gravity
import android.widget.LinearLayout

class FocusDurationPicker(context: Context, initialDurationMillis: Long) : LinearLayout(context) {
    private val hoursPicker: HorizontalWheelPicker
    private val minutesPicker: HorizontalWheelPicker

    val durationMillis: Long
        get() = (hoursPicker.selectedValue * 60L + minutesPicker.selectedValue) * 60_000L

    init {
        val initialMinutes = (initialDurationMillis / 60_000L)
            .coerceIn(1L, TrackingTimerSettings.MAX_FOCUS_SESSION_MINUTES.toLong())
            .toInt()
        val initialHours = initialMinutes / 60

        orientation = VERTICAL
        gravity = Gravity.CENTER
        hoursPicker = HorizontalWheelPicker(
            context,
            label = "Hours",
            minValue = 0,
            maxValue = TrackingTimerSettings.MAX_FOCUS_SESSION_HOURS,
            initialValue = initialHours,
            swipeDpPerStep = HOURS_SWIPE_DP_PER_STEP
        )
        minutesPicker = HorizontalWheelPicker(
            context,
            label = "Minutes",
            minValue = if (initialHours == 0) 1 else 0,
            maxValue = if (initialHours == TrackingTimerSettings.MAX_FOCUS_SESSION_HOURS) 0 else 59,
            initialValue = initialMinutes % 60,
            swipeDpPerStep = MINUTES_SWIPE_DP_PER_STEP
        )
        hoursPicker.onValueChanged = { hours ->
            when (hours) {
                0 -> minutesPicker.setRange(1, 59)
                TrackingTimerSettings.MAX_FOCUS_SESSION_HOURS -> minutesPicker.setRange(0, 0)
                else -> minutesPicker.setRange(0, 59)
            }
        }
        addView(hoursPicker, LayoutParams(-1, dp(106)))
        addView(minutesPicker, LayoutParams(-1, dp(106)))
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val HOURS_SWIPE_DP_PER_STEP = 90f
        const val MINUTES_SWIPE_DP_PER_STEP = 20f
    }
}
