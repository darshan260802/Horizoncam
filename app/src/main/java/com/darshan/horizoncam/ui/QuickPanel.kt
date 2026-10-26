package com.darshan.horizoncam.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

/** One button of the quick controls grid. */
class QuickItem(
    val key: String,
    val icon: Int,
    val label: String,
    val value: String,
    val active: Boolean,
    val onTap: () -> Unit
)

/**
 * "Quick controls" pop-up: a glass card with a grid of round buttons. Choices and sliders
 * open inside the card, right under the grid, so everything stays within thumb reach.
 */
class QuickPanel @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : LinearLayout(context, attrs) {

    private val grid = LinearLayout(context)
    private val detail = LinearLayout(context)
    private val detailTitle = TextView(context)
    private val detailBody = LinearLayout(context)
    var detailKey: String? = null
        private set

    private fun dp(v: Float) = Ui.dp(context, v)

    init {
        orientation = VERTICAL
        background = Ui.rounded(0xE6141414.toInt(), dp(28f).toFloat())
        setPadding(dp(10f), dp(14f), dp(10f), dp(12f))
        isClickable = true
        grid.orientation = VERTICAL
        addView(grid, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        detail.orientation = VERTICAL
        detail.visibility = View.GONE
        detail.setPadding(dp(8f), dp(10f), dp(8f), 0)
        detailTitle.setTextColor(Ui.TEXT_DIM)
        detailTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
        detail.addView(detailTitle)
        detailBody.orientation = VERTICAL
        detail.addView(detailBody, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8f) })
        addView(detail, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    fun setItems(items: List<QuickItem>) {
        grid.removeAllViews()
        // Four per row; five when that avoids a nearly empty last row.
        val cols = if (items.size > 8) 5 else 4
        var row: LinearLayout? = null
        items.forEachIndexed { i, item ->
            if (i % cols == 0) {
                row = LinearLayout(context).apply { orientation = HORIZONTAL }
                grid.addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { if (i > 0) topMargin = dp(10f) })
            }
            row!!.addView(cell(item), LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        }
        // Keep the last row aligned to the grid.
        val rest = (cols - items.size % cols) % cols
        repeat(rest) { row?.addView(View(context), LayoutParams(0, 1, 1f)) }
    }

    private fun cell(item: QuickItem): View {
        val col = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            isClickable = true
            isFocusable = true
            contentDescription = item.label
            setOnClickListener {
                performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                item.onTap()
            }
        }
        val selected = item.key == detailKey
        val circle = ImageView(context).apply {
            setImageResource(item.icon)
            scaleType = ImageView.ScaleType.CENTER
            val bg = when {
                selected -> 0x59FFFFFF
                else -> 0x2EFFFFFF
            }
            background = Ui.ripple(Ui.rounded(bg, dp(26f).toFloat()))
            imageTintList = ColorStateList.valueOf(if (item.active) Ui.ACCENT else Color.WHITE)
        }
        col.addView(circle, LayoutParams(dp(52f), dp(52f)))
        col.addView(TextView(context).apply {
            text = item.label
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            gravity = Gravity.CENTER
            maxLines = 1
            setPadding(0, dp(5f), 0, 0)
        })
        col.addView(TextView(context).apply {
            text = item.value
            setTextColor(if (item.active) Ui.ACCENT else Ui.TEXT_DIM)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            maxLines = 1
        })
        return col
    }

    /** Shows a row of choices for [key] under the grid. Tapping [key] again closes it. */
    fun showChoices(key: String, title: String, options: List<String>, selected: Int, onPick: (Int) -> Unit) {
        if (detailKey == key) { clearDetail(); return }
        openDetail(key, title)
        val pills = LinearLayout(context).apply { orientation = HORIZONTAL }
        val scroller = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(pills)
        }
        options.forEachIndexed { i, label ->
            val pill = Ui.pill(context, label, i == selected) {
                onPick(i)
                for (c in 0 until pills.childCount) Ui.styleSelected(pills.getChildAt(c) as TextView, c == i)
            }
            pills.addView(pill, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(8f) })
        }
        detailBody.addView(scroller)
    }

    /** Shows a slider for [key] (e.g. exposure), optionally with extra content below. */
    fun showSlider(key: String, title: String, max: Int, progress: Int, extra: View?, onChange: (Int) -> Unit): SeekBar {
        if (detailKey != key) openDetail(key, title) else detailTitle.text = title
        detailBody.removeAllViews()
        val seek = SeekBar(context).apply {
            this.max = max
            this.progress = progress
            progressTintList = ColorStateList.valueOf(Ui.ACCENT)
            thumbTintList = ColorStateList.valueOf(Ui.ACCENT)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) { if (fromUser) onChange(p) }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }
        detailBody.addView(seek, LayoutParams(LayoutParams.MATCH_PARENT, dp(40f)))
        if (extra != null) detailBody.addView(extra)
        return seek
    }

    fun setDetailTitle(title: String) {
        detailTitle.text = title
    }

    private fun openDetail(key: String, title: String) {
        detailKey = key
        detailTitle.text = title
        detailBody.removeAllViews()
        detail.visibility = View.VISIBLE
    }

    fun clearDetail() {
        detailKey = null
        detailBody.removeAllViews()
        detail.visibility = View.GONE
    }
}
