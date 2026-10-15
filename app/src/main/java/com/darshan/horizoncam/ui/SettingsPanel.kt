package com.darshan.horizoncam.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.darshan.horizoncam.R

/** One configurable option shown in the settings panel. Tapping cycles through [options]. */
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

/** Full-screen settings sheet built in code (no AndroidX needed). */
class SettingsPanel(context: Context, private val onClose: () -> Unit) : FrameLayout(context) {

    private val dp = context.resources.displayMetrics.density
    private val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val valueViews = ArrayList<Pair<SettingRow, TextView>>()
    private val rowViews = ArrayList<Pair<SettingRow, View>>()

    init {
        setBackgroundColor(0xF00B0F17.toInt())
        isClickable = true
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((20 * dp).toInt(), (12 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt())
        }
        header.addView(TextView(context).apply {
            text = "Settings"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(ImageButton(context).apply {
            setImageResource(R.drawable.ic_close)
            setBackgroundResource(R.drawable.bg_circle)
            contentDescription = "Close settings"
            setOnClickListener { onClose() }
        }, LinearLayout.LayoutParams((44 * dp).toInt(), (44 * dp).toInt()))
        column.addView(header)
        val scroll = ScrollView(context).apply { isVerticalScrollBarEnabled = false }
        scroll.addView(list, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        column.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        addView(column, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    fun setContent(rows: List<Any>) {
        list.removeAllViews()
        valueViews.clear()
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
                    setTextColor(0xFFFFC83D.toInt())
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    letterSpacing = 0.12f
                    typeface = Typeface.DEFAULT_BOLD
                    setPadding((20 * dp).toInt(), (18 * dp).toInt(), (20 * dp).toInt(), (6 * dp).toInt())
                })
            }
            when (r) {
                is SettingRow -> addSettingRow(r)
                is ActionRow -> addActionRow(r)
            }
        }
        list.addView(View(context), LinearLayout.LayoutParams(1, (48 * dp).toInt()))
        refresh()
    }

    private fun rowContainer(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding((20 * dp).toInt(), (12 * dp).toInt(), (20 * dp).toInt(), (12 * dp).toInt())
        isClickable = true
        isFocusable = true
        foreground = with(TypedValue()) {
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, this, true)
            context.getDrawable(resourceId)
        }
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
            setTextColor(0xFF9AA3B2.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setPadding(0, (2 * dp).toInt(), 0, 0)
        })
    }

    private fun addSettingRow(r: SettingRow) {
        val row = rowContainer()
        row.addView(texts(r.title, r.description), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val value = TextView(context).apply {
            setTextColor(0xFF0B0F17.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding((12 * dp).toInt(), (6 * dp).toInt(), (12 * dp).toInt(), (6 * dp).toInt())
            background = GradientDrawable().apply {
                cornerRadius = 14 * dp
                setColor(0xFFFFC83D.toInt())
            }
        }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.leftMargin = (12 * dp).toInt()
        row.addView(value, lp)
        row.setOnClickListener {
            if (!r.enabled()) return@setOnClickListener
            val next = (r.get() + 1) % r.options.size
            r.set(next)
            refresh()
        }
        valueViews.add(r to value)
        rowViews.add(r to row)
        list.addView(row)
    }

    private fun addActionRow(a: ActionRow) {
        val row = rowContainer()
        row.addView(texts(a.title, a.description), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.setOnClickListener { a.action() }
        list.addView(row)
    }

    fun refresh() {
        for ((r, v) in valueViews) {
            val idx = r.get().coerceIn(0, r.options.size - 1)
            v.text = r.options[idx]
        }
        for ((r, v) in rowViews) {
            v.alpha = if (r.enabled()) 1f else 0.4f
        }
    }
}
