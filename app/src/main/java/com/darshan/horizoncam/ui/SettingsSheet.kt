package com.darshan.horizoncam.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.darshan.horizoncam.R

/** One configurable option: all choices are shown as pills, tap one to select it. */
class SettingRow(
    val section: String,
    val title: String,
    val description: String,
    val options: List<String>,
    val get: () -> Int,
    val set: (Int) -> Unit,
    val enabled: () -> Boolean = { true }
)

/** A tappable action row (diagnostics, about, ...). */
class ActionRow(val section: String, val title: String, val description: String, val action: () -> Unit)

/**
 * Bottom sheet with all settings, reachable with one thumb: slides up from the bottom,
 * closes by tapping outside, the close button, back, or dragging the handle down.
 */
@SuppressLint("ViewConstructor")
class SettingsSheet(context: Context, private val onClosed: () -> Unit) : FrameLayout(context) {

    private val scrim = View(context)
    private val card = LinearLayout(context)
    private val list = LinearLayout(context)
    private val pillRows = ArrayList<Pair<SettingRow, LinearLayout>>()
    private val rowViews = ArrayList<Pair<SettingRow, View>>()
    private var closing = false

    private fun dp(v: Float) = Ui.dp(context, v)

    init {
        scrim.setBackgroundColor(0x99000000.toInt())
        scrim.setOnClickListener { dismiss() }
        addView(scrim, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        card.orientation = LinearLayout.VERTICAL
        card.isClickable = true
        card.background = GradientDrawable().apply {
            setColor(0xF5121821.toInt())
            val r = dp(26f).toFloat()
            cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
        }
        val maxH = (context.resources.displayMetrics.heightPixels * 0.82f).toInt()
        val cardLp = LayoutParams(LayoutParams.MATCH_PARENT, maxH, Gravity.BOTTOM)
        addView(card, cardLp)

        // Drag handle + header (drag down to close).
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20f), dp(10f), dp(12f), dp(4f))
        }
        header.addView(View(context).apply {
            background = Ui.rounded(0x66FFFFFF, dp(2f).toFloat())
        }, LinearLayout.LayoutParams(dp(40f), dp(4f)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(8f) })
        val titleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(TextView(context).apply {
            text = "Settings"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        titleRow.addView(ImageButton(context).apply {
            setImageResource(R.drawable.ic_close)
            background = Ui.ripple(Ui.rounded(Ui.PILL, dp(22f).toFloat()))
            contentDescription = "Close settings"
            setOnClickListener { dismiss() }
        }, LinearLayout.LayoutParams(dp(44f), dp(44f)))
        header.addView(titleRow)
        card.addView(header)
        attachDragToClose(header)

        list.orientation = LinearLayout.VERTICAL
        val scroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = OVER_SCROLL_IF_CONTENT_SCROLLS
        }
        scroll.addView(list, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        card.addView(scroll, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachDragToClose(handle: View) {
        var startY = 0f
        handle.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { startY = ev.rawY; true }
                MotionEvent.ACTION_MOVE -> {
                    card.translationY = (ev.rawY - startY).coerceAtLeast(0f)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (card.translationY > dp(110f)) dismiss()
                    else card.animate().translationY(0f).setDuration(180).setInterpolator(DecelerateInterpolator()).start()
                    true
                }
                else -> false
            }
        }
    }

    fun setContent(rows: List<Any>) {
        list.removeAllViews()
        pillRows.clear()
        rowViews.clear()
        var lastSection = ""
        for (r in rows) {
            val section = when (r) {
                is SettingRow -> r.section
                is ActionRow -> r.section
                else -> continue
            }
            if (section != lastSection) {
                lastSection = section
                list.addView(TextView(context).apply {
                    text = section.uppercase()
                    setTextColor(Ui.ACCENT)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    letterSpacing = 0.12f
                    typeface = Typeface.DEFAULT_BOLD
                    setPadding(dp(20f), dp(18f), dp(20f), dp(4f))
                })
            }
            when (r) {
                is SettingRow -> addSettingRow(r)
                is ActionRow -> addActionRow(r)
            }
        }
        list.addView(View(context), LinearLayout.LayoutParams(1, dp(40f)))
        refresh()
    }

    private fun texts(title: String, desc: String): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(TextView(context).apply {
            text = title
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        })
        if (desc.isNotEmpty()) addView(TextView(context).apply {
            text = desc
            setTextColor(Ui.TEXT_DIM)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setPadding(0, dp(2f), 0, 0)
        })
    }

    private fun addSettingRow(r: SettingRow) {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20f), dp(10f), dp(20f), dp(10f))
        }
        row.addView(texts(r.title, r.description))
        val pills = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val scroller = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(pills)
        }
        row.addView(scroller, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8f) })
        r.options.forEachIndexed { i, label ->
            val pill = Ui.pill(context, label, false) {
                if (!r.enabled()) return@pill
                if (r.get() != i) {
                    r.set(i)
                    haptic()
                }
                refresh()
            }
            pills.addView(pill, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(8f) })
        }
        pillRows.add(r to pills)
        rowViews.add(r to row)
        list.addView(row)
    }

    private fun haptic() {
        performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
    }

    private fun addActionRow(a: ActionRow) {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20f), dp(14f), dp(20f), dp(14f))
            isClickable = true
            isFocusable = true
            background = Ui.ripple(null, Ui.rounded(Color.WHITE, 0f))
            setOnClickListener { a.action() }
        }
        row.addView(texts(a.title, a.description), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        list.addView(row)
    }

    fun refresh() {
        for ((r, pills) in pillRows) {
            val sel = r.get()
            for (i in 0 until pills.childCount) Ui.styleSelected(pills.getChildAt(i) as TextView, i == sel)
        }
        for ((r, v) in rowViews) v.alpha = if (r.enabled()) 1f else 0.4f
    }

    fun show() {
        closing = false
        visibility = View.VISIBLE
        scrim.alpha = 0f
        scrim.animate().alpha(1f).setDuration(200).start()
        card.post {
            card.translationY = card.height.toFloat()
            card.animate().translationY(0f).setDuration(260).setInterpolator(DecelerateInterpolator(2f)).setListener(null).start()
        }
    }

    fun dismiss() {
        if (closing) return
        closing = true
        scrim.animate().alpha(0f).setDuration(180).start()
        card.animate().translationY(card.height.toFloat()).setDuration(200).setInterpolator(DecelerateInterpolator())
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    card.animate().setListener(null)
                    onClosed()
                }
            }).start()
    }
}
