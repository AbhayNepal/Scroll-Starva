package com.scrollstarva.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.sign

class HorizontalWheelPicker(
    context: Context,
    label: String,
    minValue: Int,
    maxValue: Int,
    initialValue: Int,
    private val swipeDpPerStep: Float,
    private val formatValue: (Int) -> String = { String.format("%02d", it) }
) : LinearLayout(context) {
    private val wheel: WheelView
    var onValueChanged: ((Int) -> Unit)? = null

    var selectedValue: Int
        get() = wheel.selectedValue
        set(value) = wheel.setSelectedValue(value)

    init {
        orientation = VERTICAL
        val heading = TextView(context).apply {
            text = label
            textSize = 12f
            setTextColor(0xFF65727E.toInt())
            gravity = Gravity.CENTER
        }
        addView(heading, LayoutParams(-1, dp(18)))
        wheel = WheelView(context, label, minValue, maxValue, initialValue, formatValue)
        wheel.valueChanged = { value -> onValueChanged?.invoke(value) }
        addView(wheel, LayoutParams(-1, dp(50)))
    }

    fun setRange(minValue: Int, maxValue: Int) {
        wheel.setRange(minValue, maxValue)
    }

    private inner class WheelView(
        context: Context,
        private val label: String,
        minValue: Int,
        maxValue: Int,
        initialValue: Int,
        private val formatValue: (Int) -> String
    ) : View(context) {
        private val density = resources.displayMetrics.density
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
        }
        private val selectorPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val itemSpacing = dp(56).toFloat()
        private val stepDistance = dp(swipeDpPerStep).toFloat()
        private var minValue = minValue
        private var maxValue = maxValue
        var selectedValue = initialValue.coerceIn(minValue, maxValue)
            private set
        var valueChanged: ((Int) -> Unit)? = null
        private var downX = 0f
        private var downY = 0f
        private var lastTouchX = 0f
        private var dragOffset = 0f
        private var isHorizontalDrag = false
        private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        private var settlingAnimator: ValueAnimator? = null
        private var selectedColor = 0xFFC94D43.toInt()
        private var secondaryColor = 0xFF65727E.toInt()

        init {
            isFocusable = true
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
            contentDescription = description()
        }

        fun setRange(minValue: Int, maxValue: Int) {
            require(minValue <= maxValue)
            this.minValue = minValue
            this.maxValue = maxValue
            setSelectedValue(selectedValue.coerceIn(minValue, maxValue))
            invalidate()
        }

        fun setSelectedValue(value: Int) {
            val updated = value.coerceIn(minValue, maxValue)
            if (updated == selectedValue) return
            selectedValue = updated
            dragOffset = 0f
            contentDescription = description()
            invalidate()
            valueChanged?.invoke(updated)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val centerX = width / 2f
            val centerY = height / 2f
            val selectorWidth = dp(76).toFloat()
            val selectorHeight = dp(34).toFloat()

            selectorPaint.color = 0xFFF1D8C5.toInt()
            selectorPaint.style = Paint.Style.STROKE
            selectorPaint.strokeWidth = dp(1).toFloat()
            canvas.drawRoundRect(
                RectF(
                    centerX - selectorWidth / 2f,
                    centerY - selectorHeight / 2f,
                    centerX + selectorWidth / 2f,
                    centerY + selectorHeight / 2f
                ),
                dp(10).toFloat(),
                dp(10).toFloat(),
                selectorPaint
            )
            selectorPaint.style = Paint.Style.FILL

            val visualOffset = dragOffset * itemSpacing / stepDistance
            for (offset in -2..2) {
                val value = selectedValue + offset
                if (value !in minValue..maxValue) continue
                val x = centerX + offset * itemSpacing + visualOffset
                if (x < -itemSpacing || x > width + itemSpacing) continue
                val distance = abs(offset + dragOffset / stepDistance).coerceAtMost(2f)
                val formattedText = formatValue(value)
                textPaint.textSize = if (formattedText.length > 4) {
                    dp(13f).toFloat()
                } else if (formattedText.length > 2) {
                    dp(15f).toFloat()
                } else {
                    dp((20f - distance * 3f)).toFloat()
                }
                textPaint.typeface = if (offset == 0 && abs(dragOffset) < stepDistance / 2f) {
                    android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                } else {
                    android.graphics.Typeface.DEFAULT
                }
                textPaint.color = if (distance < .45f) selectedColor else secondaryColor
                textPaint.alpha = (255 - distance.toInt() * 68).coerceAtLeast(65)
                canvas.drawText(
                    formattedText,
                    x,
                    centerY - (textPaint.ascent() + textPaint.descent()) / 2f,
                    textPaint
                )
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    settlingAnimator?.cancel()
                    downX = event.x
                    downY = event.y
                    lastTouchX = event.x
                    isHorizontalDrag = false
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaX = event.x - lastTouchX
                    val totalDeltaX = abs(event.x - downX)
                    val totalDeltaY = abs(event.y - downY)

                    if (!isHorizontalDrag) {
                        if (totalDeltaX > touchSlop && totalDeltaX > totalDeltaY) {
                            isHorizontalDrag = true
                            parent?.requestDisallowInterceptTouchEvent(true)
                        } else if (totalDeltaY > touchSlop && totalDeltaY > totalDeltaX) {
                            parent?.requestDisallowInterceptTouchEvent(false)
                            return false
                        }
                    }

                    if (isHorizontalDrag) {
                        lastTouchX = event.x
                        dragOffset += deltaX
                        while (dragOffset <= -stepDistance && selectedValue < maxValue) {
                            dragOffset += stepDistance
                            selectedValue++
                            notifyValueChanged()
                        }
                        while (dragOffset >= stepDistance && selectedValue > minValue) {
                            dragOffset -= stepDistance
                            selectedValue--
                            notifyValueChanged()
                        }
                        if ((selectedValue == minValue && dragOffset > 0f) ||
                            (selectedValue == maxValue && dragOffset < 0f)
                        ) {
                            dragOffset = 0f
                        }
                        invalidate()
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    if (isHorizontalDrag) {
                        if (abs(dragOffset) >= stepDistance / 2f) {
                            val direction = -sign(dragOffset).toInt()
                            val updated = (selectedValue + direction).coerceIn(minValue, maxValue)
                            if (updated != selectedValue) {
                                selectedValue = updated
                                dragOffset -= -direction * stepDistance
                                notifyValueChanged()
                            }
                        }
                        settleWheel()
                        performClick()
                    }
                    parent?.requestDisallowInterceptTouchEvent(false)
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    parent?.requestDisallowInterceptTouchEvent(false)
                    settleWheel()
                    return true
                }
            }
            return true
        }

        override fun performClick(): Boolean {
            super.performClick()
            return true
        }

        override fun onDetachedFromWindow() {
            settlingAnimator?.cancel()
            super.onDetachedFromWindow()
        }

        override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(info)
            info.className = "android.widget.SeekBar"
            info.text = description()
            info.addAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            info.addAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
        }

        override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
            return when (action) {
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> changeBy(1)
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> changeBy(-1)
                else -> super.performAccessibilityAction(action, arguments)
            }
        }

        private fun changeBy(amount: Int): Boolean {
            val updated = (selectedValue + amount).coerceIn(minValue, maxValue)
            if (updated == selectedValue) return false
            selectedValue = updated
            notifyValueChanged()
            invalidate()
            return true
        }

        private fun notifyValueChanged() {
            contentDescription = description()
            valueChanged?.invoke(selectedValue)
            sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_SELECTED)
        }

        private fun description() = "$label: ${formatValue(selectedValue)}"

        private fun settleWheel() {
            settlingAnimator?.cancel()
            settlingAnimator = ValueAnimator.ofFloat(dragOffset, 0f).apply {
                duration = WHEEL_SETTLE_MILLIS
                interpolator = DecelerateInterpolator()
                addUpdateListener { animation ->
                    dragOffset = animation.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }

        private fun dp(value: Int): Int = (value * density).toInt()
        private fun dp(value: Float): Int = (value * density).toInt()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val WHEEL_SETTLE_MILLIS = 140L
    }
}
