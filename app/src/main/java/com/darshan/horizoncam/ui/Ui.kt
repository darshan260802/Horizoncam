package com.darshan.horizoncam.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.Gravity
import android.widget.TextView
import kotlin.math.min

/**
 * Design tokens for the camera screen (see docs/DESIGN.md › Color tokens). The viewfinder chrome is
 * always dark; contrast figures are WCAG ratios against [CHROME].
 */
object Ui {
    /** "Level" amber: on, selected or locked. The only accent. 11.7:1 on chrome. */
    const val ACCENT = 0xFFFFC83D.toInt()
    /** Text on an amber fill. 13.6:1. */
    const val INK = 0xFF000000.toInt()
    /** Panels and menus over the viewfinder: #161618 at 86%. */
    const val CHROME = 0xDB161618.toInt()
    /** Hairline along the edge of a chrome surface (light catching the material). */
    const val CHROME_EDGE = 0x24FFFFFF
    /** Secondary text on chrome. 7.0:1. */
    const val SECONDARY = 0xFFA1A1A8.toInt()
    /** Control fill on chrome, and its selected (amber-tinted) version. */
    const val FILL = 0x29FFFFFF
    const val FILL_SELECTED = 0x33FFC83D
    /** Recording only. */
    const val RECORD = 0xFFF5372B.toInt()

    // Older names, kept for the views that still use them.
    const val TEXT_DIM = SECONDARY
    const val PILL = FILL

    /** Largest text scale used for camera chrome (labels over the viewfinder). */
    private const val CHROME_MAX_SCALE = 1.3f

    fun dp(context: Context, v: Float): Int = (v * context.resources.displayMetrics.density + 0.5f).toInt()

    /**
     * Camera chrome follows the system text size, capped at 1.3×, like tab titles: the controls keep
     * their layout while menus and settings scale fully.
     */
    fun chromeText(tv: TextView, sp: Float) {
        val res = tv.resources
        val scale = min(res.configuration.fontScale, CHROME_MAX_SCALE)
        tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp * res.displayMetrics.density * scale)
    }

    fun chromeTextPx(context: Context, sp: Float): Float =
        sp * context.resources.displayMetrics.density * min(context.resources.configuration.fontScale, CHROME_MAX_SCALE)

    /** Soft shadow that keeps labels readable over a bright viewfinder. */
    fun legibleOnPreview(tv: TextView) {
        val d = tv.resources.displayMetrics.density
        tv.setShadowLayer(2.5f * d, 0f, 0.5f * d, 0x80000000.toInt())
    }

    fun rounded(color: Int, radiusPx: Float): GradientDrawable = GradientDrawable().apply {
        cornerRadius = radiusPx
        setColor(color)
    }

    /** The chrome material: translucent dark fill with a light hairline edge. */
    fun material(context: Context, radiusPx: Float): GradientDrawable = GradientDrawable().apply {
        cornerRadius = radiusPx
        setColor(CHROME)
        setStroke(maxOf(1, dp(context, 0.75f)), CHROME_EDGE)
    }

    fun ripple(content: Drawable?, mask: Drawable? = content): Drawable =
        RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), content, mask)

    /** A selectable pill (option strips, white balance). */
    fun pill(context: Context, label: String, selected: Boolean, onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = label
            gravity = Gravity.CENTER
            chromeText(this, 13.5f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            minWidth = dp(context, 56f)
            minHeight = dp(context, 44f)
            setPadding(dp(context, 16f), dp(context, 8f), dp(context, 16f), dp(context, 8f))
            styleSelected(this, selected)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            pressFeedback(0.94f)
        }

    fun styleSelected(v: TextView, selected: Boolean) {
        val r = dp(v.context, 22f).toFloat()
        val bg = rounded(if (selected) ACCENT else FILL, r)
        v.background = ripple(bg, rounded(Color.WHITE, r))
        v.setTextColor(if (selected) INK else Color.WHITE)
        v.isSelected = selected
    }
}
