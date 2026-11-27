package com.scrollstarva.app

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Scroll Starva's design system: colour tokens, type styles and the building blocks every screen
 * (and the reminder overlay drawn by the accessibility service) is made from, so the whole app
 * shares one look. Everything is plain views built in code, matching the rest of the app.
 */
object Ui {

    // ---------------------------------------------------------------- colour tokens

    /** One full set of colour tokens. */
    class Palette(
        val bg: String, val surface: String, val surfaceMuted: String, val border: String, val borderStrong: String,
        val ink: String, val ink2: String, val ink3: String,
        val brand: String, val brandDeep: String, val brandSoft: String, val brandTint: String,
        val success: String, val successSoft: String, val warning: String, val warningSoft: String,
        val teal: String, val tealSoft: String, val violet: String, val violetSoft: String
    )

    private val LIGHT = Palette(
        "#F6F4F1", "#FFFFFF", "#F2EFEB", "#EAE5DF", "#DDD6CE",
        "#1B1A1F", "#5E5A66", "#9893A0",
        "#EE6352", "#C4463A", "#FDEBE7", "#FFF6F3",
        "#2E9466", "#E5F3EB", "#C9761F", "#FBEFDF",
        "#1F978B", "#E2F3F0", "#7160D3", "#EEEBFA"
    )

    private val DARK = Palette(
        "#121115", "#1C1B21", "#26252C", "#2D2B33", "#3C3943",
        "#F2F0F4", "#B8B4BF", "#837E8B",
        "#F0705F", "#FF9C8E", "#3A2522", "#291D1C",
        "#4FC08D", "#1B3027", "#E9A453", "#352A1C",
        "#3FC3B4", "#16302E", "#A193F2", "#28243F"
    )

    /** Appearance setting values (see [TrackingRepository.appearanceMode]). */
    const val APPEARANCE_SYSTEM = 0
    const val APPEARANCE_LIGHT = 1
    const val APPEARANCE_DARK = 2

    /** True while the dark palette is active. */
    var isDark = false
        private set
    private var c = resolve(LIGHT)

    /**
     * Picks the palette for [mode]; [APPEARANCE_SYSTEM] follows the phone's dark theme setting.
     * Call before building views (activity creation, the reminder overlay).
     */
    fun applyTheme(context: Context, mode: Int) {
        val systemDark = (context.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        isDark = when (mode) {
            APPEARANCE_DARK -> true
            APPEARANCE_LIGHT -> false
            else -> systemDark
        }
        c = resolve(if (isDark) DARK else LIGHT)
    }

    private class Resolved(p: Palette) {
        val bg = Color.parseColor(p.bg); val surface = Color.parseColor(p.surface)
        val surfaceMuted = Color.parseColor(p.surfaceMuted); val border = Color.parseColor(p.border)
        val borderStrong = Color.parseColor(p.borderStrong)
        val ink = Color.parseColor(p.ink); val ink2 = Color.parseColor(p.ink2); val ink3 = Color.parseColor(p.ink3)
        val brand = Color.parseColor(p.brand); val brandDeep = Color.parseColor(p.brandDeep)
        val brandSoft = Color.parseColor(p.brandSoft); val brandTint = Color.parseColor(p.brandTint)
        val success = Color.parseColor(p.success); val successSoft = Color.parseColor(p.successSoft)
        val warning = Color.parseColor(p.warning); val warningSoft = Color.parseColor(p.warningSoft)
        val teal = Color.parseColor(p.teal); val tealSoft = Color.parseColor(p.tealSoft)
        val violet = Color.parseColor(p.violet); val violetSoft = Color.parseColor(p.violetSoft)
    }

    private fun resolve(p: Palette) = Resolved(p)

    val BG get() = c.bg
    val SURFACE get() = c.surface
    val SURFACE_MUTED get() = c.surfaceMuted
    val BORDER get() = c.border
    val BORDER_STRONG get() = c.borderStrong

    val INK get() = c.ink
    val INK_2 get() = c.ink2
    val INK_3 get() = c.ink3

    val BRAND get() = c.brand
    val BRAND_DEEP get() = c.brandDeep
    val BRAND_SOFT get() = c.brandSoft
    val BRAND_TINT get() = c.brandTint

    val SUCCESS get() = c.success
    val SUCCESS_SOFT get() = c.successSoft
    val WARNING get() = c.warning
    val WARNING_SOFT get() = c.warningSoft
    val TEAL get() = c.teal
    val TEAL_SOFT get() = c.tealSoft
    val VIOLET get() = c.violet
    val VIOLET_SOFT get() = c.violetSoft

    // ---------------------------------------------------------------- type

    enum class Type(val sizeSp: Float, val weight: Int, val tracking: Float = 0f) {
        DISPLAY(36f, 800, -0.02f),
        HEADLINE(26f, 800, -0.015f),
        TITLE(19f, 700, -0.01f),
        SUBTITLE(16f, 700),
        BODY(15f, 400),
        BODY_STRONG(15f, 600),
        SMALL(13.5f, 400),
        CAPTION(12f, 500),
        LABEL(13f, 600),
        OVERLINE(11f, 700, 0.12f),
        STAT(24f, 800, -0.01f);

        /** Default colour, read from the active palette. */
        val color: Int
            get() = when (this) {
                BODY, SMALL, LABEL -> INK_2
                CAPTION, OVERLINE -> INK_3
                else -> INK
            }
    }

    private val fontCache = HashMap<Int, Typeface>()

    /** Plus Jakarta Sans at [weight] (400–800), falling back to the system font. */
    fun font(context: Context, weight: Int): Typeface = fontCache.getOrPut(weight) {
        val res = when {
            weight >= 800 -> R.font.plus_jakarta_extrabold
            weight >= 700 -> R.font.plus_jakarta_bold
            weight >= 600 -> R.font.plus_jakarta_semibold
            weight >= 500 -> R.font.plus_jakarta_medium
            else -> R.font.plus_jakarta_regular
        }
        try {
            context.resources.getFont(res)
        } catch (_: Exception) {
            Typeface.create(Typeface.SANS_SERIF, if (weight >= 600) Typeface.BOLD else Typeface.NORMAL)
        }
    }

    fun TextView.style(type: Type, color: Int = type.color): TextView {
        textSize = type.sizeSp
        typeface = font(context, type.weight)
        letterSpacing = type.tracking
        setTextColor(color)
        includeFontPadding = false
        if (type == Type.BODY || type == Type.SMALL || type == Type.BODY_STRONG) {
            setLineSpacing(0f, 1.3f)
        }
        if (type == Type.DISPLAY || type == Type.STAT) fontFeatureSettings = "tnum"
        return this
    }

    fun text(context: Context, value: CharSequence, type: Type, color: Int = type.color): TextView =
        TextView(context).apply {
            text = if (type == Type.OVERLINE) value.toString().uppercase() else value
            style(type, color)
        }

    // ---------------------------------------------------------------- metrics

    fun dp(context: Context, value: Float): Int = (value * context.resources.displayMetrics.density + .5f).toInt()
    private fun Context.px(value: Float) = dp(this, value)

    fun rounded(color: Int, radius: Float, stroke: Int = 0, strokeColor: Int = Color.TRANSPARENT) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius
            if (stroke > 0) setStroke(stroke, strokeColor)
        }

    // ---------------------------------------------------------------- surfaces

    /** White card with a hairline border. */
    fun card(context: Context, padding: Float = 20f, content: LinearLayout.() -> Unit = {}): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val p = context.px(padding)
            setPadding(p, p, p, p)
            background = rounded(SURFACE, context.px(20f).toFloat(), context.px(1f), BORDER)
            content()
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = context.px(6f)
                bottomMargin = context.px(6f)
            }
        }

    /** Rounded-square icon on a soft tint. */
    fun iconBadge(context: Context, icon: Int, tint: Int, soft: Int, size: Float = 36f): View =
        FrameLayout(context).apply {
            background = rounded(soft, context.px(size * .32f).toFloat())
            addView(ImageView(context).apply {
                setImageResource(icon)
                imageTintList = ColorStateList.valueOf(tint)
            }, FrameLayout.LayoutParams(context.px(size * .55f), context.px(size * .55f), Gravity.CENTER))
            layoutParams = LinearLayout.LayoutParams(context.px(size), context.px(size))
        }

    fun iconView(context: Context, icon: Int, tint: Int, size: Float = 20f): ImageView = ImageView(context).apply {
        setImageResource(icon)
        imageTintList = ColorStateList.valueOf(tint)
        layoutParams = LinearLayout.LayoutParams(context.px(size), context.px(size))
    }

    /** Icon badge, title (+ optional subtitle) and an optional trailing view. */
    fun cardHeader(
        context: Context,
        icon: Int?,
        tint: Int,
        soft: Int,
        title: String,
        subtitle: String? = null,
        trailing: View? = null
    ): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        if (icon != null) {
            addView(iconBadge(context, icon, tint, soft), LinearLayout.LayoutParams(context.px(36f), context.px(36f)).apply {
                marginEnd = context.px(12f)
            })
        }
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(context, title, Type.SUBTITLE))
            if (subtitle != null) {
                addView(text(context, subtitle, Type.CAPTION).apply { setPadding(0, context.px(3f), 0, 0) })
            }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        if (trailing != null) addView(trailing)
    }

    /** Label over a large value, with an optional caption. */
    fun stat(
        context: Context,
        label: String,
        value: String,
        caption: String? = null,
        valueColor: Int = INK,
        valueType: Type = Type.STAT,
        alignEnd: Boolean = false
    ): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = if (alignEnd) Gravity.END else Gravity.START
        addView(text(context, label, Type.CAPTION))
        addView(text(context, value, valueType, valueColor).apply {
            setPadding(0, context.px(6f), 0, 0)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        if (caption != null) addView(text(context, caption, Type.CAPTION).apply { setPadding(0, context.px(4f), 0, 0) })
    }

    /** Small tinted tile used in grids of numbers. */
    fun tile(context: Context, icon: Int, tint: Int, soft: Int, label: String, value: String): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val p = context.px(16f)
            setPadding(p, p, p, p)
            background = rounded(SURFACE, context.px(18f).toFloat(), context.px(1f), BORDER)
            addView(iconBadge(context, icon, tint, soft, 32f))
            addView(text(context, value, Type.STAT).apply {
                setPadding(0, context.px(14f), 0, 0)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            addView(text(context, label, Type.CAPTION).apply { setPadding(0, context.px(4f), 0, 0) })
        }

    /** Lays views out in rows of [columns], with even gaps. */
    fun grid(context: Context, views: List<View>, columns: Int = 2, gap: Float = 10f): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val g = context.px(gap)
            views.chunked(columns).forEachIndexed { rowIndex, row ->
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    row.forEachIndexed { i, view ->
                        addView(view, LinearLayout.LayoutParams(0, -1, 1f).apply { if (i > 0) marginStart = g })
                    }
                    repeat(columns - row.size) {
                        addView(View(context), LinearLayout.LayoutParams(0, 0, 1f).apply { marginStart = g })
                    }
                }, LinearLayout.LayoutParams(-1, -2).apply { if (rowIndex > 0) topMargin = g })
            }
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = context.px(6f)
                bottomMargin = context.px(6f)
            }
        }

    fun divider(context: Context, inset: Float = 0f): View = View(context).apply {
        setBackgroundColor(BORDER)
        layoutParams = LinearLayout.LayoutParams(-1, context.px(1f)).apply {
            marginStart = context.px(inset)
        }
    }

    /** Page section heading. */
    fun sectionTitle(context: Context, title: String, caption: String? = null): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.px(4f), context.px(22f), context.px(4f), context.px(6f))
            addView(text(context, title, Type.SUBTITLE))
            if (caption != null) addView(text(context, caption, Type.CAPTION).apply { setPadding(0, context.px(3f), 0, 0) })
        }

    /** Small rounded pill. */
    fun chip(context: Context, label: String, fg: Int, bg: Int, icon: Int? = null): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(context.px(if (icon != null) 8f else 10f), context.px(5f), context.px(10f), context.px(5f))
            background = rounded(bg, context.px(100f).toFloat())
            if (icon != null) {
                addView(iconView(context, icon, fg, 14f), LinearLayout.LayoutParams(context.px(14f), context.px(14f)).apply {
                    marginEnd = context.px(5f)
                })
            }
            addView(text(context, label, Type.CAPTION, fg).apply { typeface = font(context, 700) })
            layoutParams = LinearLayout.LayoutParams(-2, -2)
        }

    /** Tinted note with an icon. */
    fun banner(context: Context, icon: Int, message: CharSequence, tint: Int, soft: Int, title: String? = null): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            val p = context.px(16f)
            setPadding(p, p, p, p)
            background = rounded(soft, context.px(18f).toFloat())
            addView(iconView(context, icon, tint, 20f), LinearLayout.LayoutParams(context.px(20f), context.px(20f)).apply {
                marginEnd = context.px(12f)
                topMargin = context.px(1f)
            })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                if (title != null) addView(text(context, title, Type.BODY_STRONG).apply { setPadding(0, 0, 0, context.px(3f)) })
                addView(text(context, message, Type.SMALL, INK_2))
            }, LinearLayout.LayoutParams(0, -2, 1f))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = context.px(6f)
                bottomMargin = context.px(6f)
            }
        }

    /** Quote with a coloured rule on the left. */
    fun quote(context: Context, message: String, attribution: String? = null, accent: Int = BRAND): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(View(context).apply { background = rounded(accent, context.px(2f).toFloat()) },
                LinearLayout.LayoutParams(context.px(3f), -1).apply { marginEnd = context.px(14f) })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(text(context, message, Type.BODY, INK).apply { typeface = font(context, 500) })
                if (attribution != null) addView(text(context, attribution, Type.CAPTION).apply { setPadding(0, context.px(6f), 0, 0) })
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }

    // ---------------------------------------------------------------- buttons

    enum class ButtonStyle { PRIMARY, SECONDARY, OUTLINE, GHOST }

    fun button(
        context: Context,
        label: String,
        style: ButtonStyle = ButtonStyle.PRIMARY,
        icon: Int? = null,
        height: Float = 52f,
        onClick: () -> Unit
    ): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        setPadding(context.px(18f), 0, context.px(18f), 0)
        minimumHeight = context.px(height)
        isClickable = true
        isFocusable = true
        contentDescription = label
        val radius = context.px(14f).toFloat()
        val (bg, fg, stroke) = when (style) {
            ButtonStyle.PRIMARY -> Triple(BRAND, Color.WHITE, 0)
            ButtonStyle.SECONDARY -> Triple(BRAND_SOFT, BRAND_DEEP, 0)
            ButtonStyle.OUTLINE -> Triple(SURFACE, INK, context.px(1f))
            ButtonStyle.GHOST -> Triple(Color.TRANSPARENT, BRAND_DEEP, 0)
        }
        val shape = rounded(bg, radius, stroke, BORDER_STRONG)
        val ripple = if (style == ButtonStyle.PRIMARY) Color.argb(60, 255, 255, 255)
            else Color.argb(if (isDark) 40 else 28, Color.red(BRAND), Color.green(BRAND), Color.blue(BRAND))
        background = RippleDrawable(ColorStateList.valueOf(ripple), shape, rounded(Color.WHITE, radius))
        if (icon != null) {
            addView(iconView(context, icon, fg, 18f), LinearLayout.LayoutParams(context.px(18f), context.px(18f)).apply {
                marginEnd = context.px(8f)
            })
        }
        addView(text(context, label, Type.BODY_STRONG, fg).apply {
            gravity = Gravity.CENTER
            maxLines = 2
            setLineSpacing(0f, 1f)
        })
        setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> view.animate().scaleX(.98f).scaleY(.98f).setDuration(80).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    view.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
            }
            false
        }
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(-1, -2)
    }

    /** Sets the label of a view made by [button]. */
    fun setButtonLabel(button: View, label: String) {
        val group = button as? LinearLayout ?: return
        (0 until group.childCount).map(group::getChildAt).filterIsInstance<TextView>().firstOrNull()?.text = label
        button.contentDescription = label
    }

    // ---------------------------------------------------------------- progress

    /** Rounded progress bar; [fraction] above 1 fills the bar in [overColor]. */
    class ProgressBar(context: Context, fraction: Float, private val color: Int, private val track: Int = SURFACE_MUTED,
                      private val overColor: Int = BRAND) : View(context) {
        var fraction: Float = fraction
            set(value) { field = value; invalidate() }
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()

        override fun onDraw(canvas: Canvas) {
            val r = height / 2f
            paint.color = track
            rect.set(0f, 0f, width.toFloat(), height.toFloat())
            canvas.drawRoundRect(rect, r, r, paint)
            val f = fraction.coerceIn(0f, 1f)
            if (f <= 0f) return
            paint.color = if (fraction > 1f) overColor else color
            rect.set(0f, 0f, (width * f).coerceAtLeast(height.toFloat()), height.toFloat())
            canvas.drawRoundRect(rect, r, r, paint)
        }
    }
}
