package com.darshan.horizoncam.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.util.TypedValue
import android.view.Gravity
import android.widget.TextView

/** Small design-system helpers shared by the camera UI. */
object Ui {
    const val ACCENT = 0xFFFFC83D.toInt()
    const val INK = 0xFF0B0F17.toInt()
    const val GLASS = 0xB3121821.toInt()
    const val PILL = 0x2EFFFFFF
    const val TEXT_DIM = 0xFF9AA3B2.toInt()
    const val RECORD = 0xFFFF4436.toInt()

    fun dp(context: Context, v: Float): Int = (v * context.resources.displayMetrics.density + 0.5f).toInt()

    fun rounded(color: Int, radiusPx: Float): GradientDrawable = GradientDrawable().apply {
        cornerRadius = radiusPx
        setColor(color)
    }

    fun ripple(content: Drawable?, mask: Drawable? = content): Drawable =
        RippleDrawable(ColorStateList.valueOf(0x40FFFFFF), content, mask)

    /** A selectable pill (used by option strips and the settings sheet). */
    fun pill(context: Context, label: String, selected: Boolean, onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = label
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            minWidth = dp(context, 56f)
            minHeight = dp(context, 40f)
            setPadding(dp(context, 16f), dp(context, 8f), dp(context, 16f), dp(context, 8f))
            styleSelected(this, selected)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

    fun styleSelected(v: TextView, selected: Boolean) {
        val r = dp(v.context, 20f).toFloat()
        val bg = rounded(if (selected) ACCENT else PILL, r)
        v.background = ripple(bg, rounded(Color.WHITE, r))
        v.setTextColor(if (selected) INK else Color.WHITE)
    }
}
