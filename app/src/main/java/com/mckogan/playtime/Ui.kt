package com.mckogan.playtime

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

/** Small helpers for building screens in code. */
object Ui {
    const val BG = 0xFFFFF8F0.toInt()
    const val TEXT = 0xFF222222.toInt()
    const val MUTED = 0xFF777777.toInt()
    const val ACCENT = 0xFFFF7A45.toInt()
    const val DANGER = 0xFFE5484D.toInt()
    const val CARD = 0xFFFFFFFF.toInt()
    const val TRACK = 0xFFECECEC.toInt()

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
        setTextColor(0xFFFFFFFF.toInt())
        background = rounded(color, 16, context)
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

    /** "32 min" style, rounded up, for notifications and parent screen. */
    fun formatMinutes(context: Context, ms: Long): String {
        val min = (ms.coerceAtLeast(0L) + 59_999) / 60_000
        return context.getString(R.string.minutes_short, min.toInt())
    }
}
