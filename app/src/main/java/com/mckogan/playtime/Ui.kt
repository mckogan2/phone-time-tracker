package com.mckogan.playtime

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

/** Small helpers for building screens in code. */
object Ui {
    /** Dark colors on this screen. Set by [applyTheme] (and by the guard before drawing its cover). */
    @Volatile var dark = false

    private fun pick(light: Long, darkColor: Long) = (if (dark) darkColor else light).toInt()

    val BG get() = pick(0xFFFFF8F0, 0xFF121212)
    val TEXT get() = pick(0xFF222222, 0xFFEDEDED)
    val MUTED get() = pick(0xFF777777, 0xFF9E9E9E)
    val ACCENT get() = pick(0xFFFF7A45, 0xFFFF9A6B)
    val DANGER get() = pick(0xFFE5484D, 0xFFFF7B7F)
    val CARD get() = pick(0xFFFFFFFF, 0xFF1E1E1E)
    val TRACK get() = pick(0xFFECECEC, 0xFF2C2C2C)
    /** Banner backgrounds: setup needed, outside game hours, parent playing. */
    val WARN_BG get() = pick(0xFFFFE3D6, 0xFF3A2418)
    val HOURS_BG get() = pick(0xFFFFF1C2, 0xFF3A3218)
    val INFO_BG get() = pick(0xFFE6F0FF, 0xFF1A2233)

    // Dark mode is "tonal": instead of bright solid fills, a dark tint of the color with the color
    // itself for text and rings. Gray buttons are plain dark gray with light text.
    private const val DARK_BG = 0xFF121212.toInt()
    private const val DARK_GRAY = 0xFF2A2A2A.toInt()
    private const val DARK_ON_GRAY = 0xFFDDDDDD.toInt()

    /** The kids' colors, softer and lighter in dark mode so they read well on black. */
    private val KID_DARK = mapOf(
        0xFF4F7CFF.toInt() to 0xFF8AA4FF.toInt(), // blue
        0xFF2EB872.toInt() to 0xFF5FD39A.toInt(), // green
        0xFFB45CE6.toInt() to 0xFFD19BF0.toInt(), // purple
        0xFFFF8A3D.toInt() to 0xFFFFA766.toInt(), // orange
    )

    /** A kid's color for this screen's theme. */
    fun kid(color: Int): Int = if (!dark) color else KID_DARK[color] ?: blend(color, 0xFFFFFFFF.toInt(), 0.3f)

    /** [color] mixed into [base]: [amount] 0 = base, 1 = color. */
    fun blend(color: Int, base: Int, amount: Float): Int {
        fun ch(shift: Int) = (((color shr shift) and 0xFF) * amount + ((base shr shift) and 0xFF) * (1 - amount)).toInt()
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    private fun isGray(color: Int) = color == MUTED || color == TRACK

    /** Background for something "filled" with [color]: the color itself, or a dark tint of it in dark mode. */
    fun fill(color: Int): Int = when {
        !dark -> color
        isGray(color) -> DARK_GRAY
        else -> blend(color, DARK_BG, 0.2f)
    }

    /** Text on a [fill] of [color]. */
    private fun onFill(color: Int): Int = when {
        !dark -> 0xFFFFFFFF.toInt()
        isGray(color) -> DARK_ON_GRAY
        else -> color
    }

    const val THEME_SYSTEM = "system"
    const val THEME_LIGHT = "light"
    const val THEME_DARK = "dark"

    /** True if this phone should show the app dark: the parent's choice, or the phone's dark mode. */
    fun isDark(context: Context): Boolean = when (Store(context).theme) {
        THEME_DARK -> true
        THEME_LIGHT -> false
        else -> context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    }

    /** Call before super.onCreate: picks the colors and the matching system theme (dialogs, switches, status bar). */
    fun applyTheme(activity: Activity) {
        dark = isDark(activity)
        activity.setTheme(
            if (dark) android.R.style.Theme_DeviceDefault_NoActionBar
            else android.R.style.Theme_DeviceDefault_Light_NoActionBar
        )
    }

    fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    fun rounded(color: Int, radiusDp: Int, context: Context) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = context.dp(radiusDp).toFloat()
    }

    fun column(context: Context, paddingDp: Int = 0) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val p = context.dp(paddingDp)
        setPadding(p, p, p, p)
    }

    fun row(context: Context) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    fun text(
        context: Context,
        value: String,
        sizeSp: Float = 16f,
        color: Int = TEXT,
        bold: Boolean = false,
        center: Boolean = false,
    ) = TextView(context).apply {
        text = value
        textSize = sizeSp
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        if (center) gravity = Gravity.CENTER else textAlignment = View.TEXT_ALIGNMENT_VIEW_START
    }

    fun button(
        context: Context,
        label: String,
        color: Int = ACCENT,
        sizeSp: Float = 18f,
        onClick: () -> Unit,
    ) = Button(context).apply {
        text = label
        textSize = sizeSp
        isAllCaps = false
        setTextColor(onFill(color))
        if (dark && !isGray(color)) setTypeface(typeface, Typeface.BOLD)
        background = rounded(fill(color), 16, context)
        stateListAnimator = null
        val p = context.dp(12)
        setPadding(p * 2, p, p * 2, p)
        setOnClickListener { onClick() }
    }

    fun card(context: Context, color: Int = CARD) = column(context, 16).apply {
        background = rounded(color, 20, context)
        elevation = context.dp(2).toFloat()
    }

    /** A thick progress bar showing [fraction] (0..1) filled in [color]. */
    fun progress(context: Context, fraction: Float, color: Int) =
        ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progress = (fraction.coerceIn(0f, 1f) * 1000).toInt()
            progressTintList = ColorStateList.valueOf(color)
            progressBackgroundTintList = ColorStateList.valueOf(TRACK)
            scaleY = 3f
        }

    /** Phone-style number pad (1-2-3 left to right, also in Hebrew). Sends "0".."9" or "⌫". */
    fun keypad(context: Context, onKey: (String) -> Unit): GridLayout {
        val grid = GridLayout(context).apply {
            columnCount = 3
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "⌫", "0", "")
        for (key in keys) {
            val size = context.dp(80)
            val cell = text(context, key, 28f, bold = true, center = true).apply {
                if (key.isNotEmpty()) {
                    background = rounded(CARD, 40, context)
                    setOnClickListener { onKey(key) }
                }
            }
            val lp = GridLayout.LayoutParams().apply {
                width = size
                height = size
                val m = context.dp(8)
                setMargins(m, m, m, m)
            }
            grid.addView(cell, lp)
        }
        return grid
    }

    /** Shows a family join code, big and easy to read, with how to use it on the other phone. */
    fun showFamilyCode(activity: Activity, code: String, onClose: () -> Unit = {}) {
        AlertDialog.Builder(activity)
            .setTitle(R.string.sync_code_title)
            .setView(column(activity, 20).apply {
                add(text(activity, code.chunked(3).joinToString(" "), 40f, ACCENT, bold = true, center = true).apply {
                    textDirection = View.TEXT_DIRECTION_LTR
                })
                add(text(activity, activity.getString(R.string.sync_code_help), 15f), topMarginDp = 12)
            })
            .setPositiveButton(R.string.done) { _, _ -> onClose() }
            .setOnCancelListener { onClose() }
            .show()
    }

    fun LinearLayout.add(view: View, topMarginDp: Int = 0, fill: Boolean = true): View {
        val lp = LinearLayout.LayoutParams(
            if (fill) LinearLayout.LayoutParams.MATCH_PARENT else LinearLayout.LayoutParams.WRAP_CONTENT,
            view.layoutParams?.height ?: LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        lp.topMargin = context.dp(topMarginDp)
        addView(view, lp)
        return view
    }

    /** "12:05" style countdown, never negative. */
    fun formatClock(ms: Long): String {
        val totalSec = (ms.coerceAtLeast(0L) + 999) / 1000
        return "%d:%02d".format(totalSec / 60, totalSec % 60)
    }

    /** Round avatar: the kid's photo if there is one, otherwise their first letter on [color]. */
    fun kidAvatar(context: Context, kid: Kid, color: Int, sizeDp: Int): View {
        val bitmap = kid.photo?.let { Photos.bitmap(it) } ?: return avatar(context, kid.name, color, sizeDp)
        val size = context.dp(sizeDp)
        return ImageView(context).apply {
            setImageBitmap(bitmap)
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = circle(context, color)
            clipToOutline = true
            layoutParams = LinearLayout.LayoutParams(size, size)
        }
    }

    /** "10:00" style clock time for minutes after midnight, in the phone's 12/24-hour style. */
    fun formatTimeOfDay(context: Context, minutes: Int): String {
        val today = java.time.LocalDate.now().atTime(minutes / 60, minutes % 60)
        return formatTime(context, today.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli())
    }

    /** A filled circle in [color] with the first letter of [name]. */
    fun avatar(context: Context, name: String, color: Int, sizeDp: Int): TextView {
        val size = context.dp(sizeDp)
        return text(context, name.trim().take(1).uppercase(), sizeDp * 0.45f, onFill(color), bold = true, center = true).apply {
            background = circle(context, color)
            layoutParams = LinearLayout.LayoutParams(size, size)
        }
    }

    /** A circle filled with [color]; in dark mode a dark tint with a ring of the color. */
    private fun circle(context: Context, color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(fill(color))
        if (dark) setStroke(context.dp(2), color)
    }

    /** A switch; in dark mode its "on" color is the app's accent instead of the phone's. */
    fun switch(context: Context) = android.widget.Switch(context).apply {
        if (dark) {
            val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
            thumbTintList = ColorStateList(states, intArrayOf(ACCENT, 0xFFBDBDBD.toInt()))
            trackTintList = ColorStateList(states, intArrayOf(blend(ACCENT, DARK_BG, 0.5f), 0xFF555555.toInt()))
        }
    }

    /** Clock time like "14:30", in the phone's 12/24-hour style. */
    fun formatTime(context: Context, millis: Long): String =
        android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(millis))

    /** "32 min" style, rounded up, for notifications and parent screen. */
    fun formatMinutes(context: Context, ms: Long): String {
        val min = (ms.coerceAtLeast(0L) + 59_999) / 60_000
        return context.getString(R.string.minutes_short, min.toInt())
    }
}
