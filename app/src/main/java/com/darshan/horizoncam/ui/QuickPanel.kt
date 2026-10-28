package com.darshan.horizoncam.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.text.TextUtils
import android.util.AttributeSet
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import kotlin.math.abs

/** One button of the quick controls grid. [toggle] marks on/off controls for TalkBack. */
class QuickItem(
    val key: String,
    val icon: Int,
    val label: String,
    val value: String,
    val active: Boolean,
    val toggle: Boolean = false,
    val onTap: () -> Unit
)

/**
 * "Quick controls" sheet: a grid of round buttons on the chrome material. Choices and sliders open
 * inside the sheet, under the grid, so everything stays within thumb reach.
 *
 * It behaves like a sheet: it rises out of the quick-controls button, follows the finger 1:1 when
 * dragged, rubber-bands when pulled up, and on release projects the flick to decide whether to
 * close (handing the finger's velocity to the closing spring). The grabber is also a Close button
 * for TalkBack.
 */
class QuickPanel @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : LinearLayout(context, attrs) {

    /** Called once the sheet has finished closing (by drag, tap or [close]). */
    var onClosed: (() -> Unit)? = null
    var hapticsEnabled: () -> Boolean = { true }

    private val grid = LinearLayout(context)
    private val detail = LinearLayout(context)
    private val detailTitle = TextView(context)
    private val detailBody = LinearLayout(context)
    private val cells = LinkedHashMap<String, Cell>()
    private var lastItems: List<QuickItem> = emptyList()
    var detailKey: String? = null
        private set

    var isOpen = false
        private set

    private val density = resources.displayMetrics.density
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var velocity: VelocityTracker? = null
    private var downY = 0f
    private var downX = 0f
    private var startTy = 0f
    private var dragging = false

    private fun dp(v: Float) = Ui.dp(context, v)

    private class Cell(val root: LinearLayout, val icon: ImageView, val label: TextView, val value: TextView)

    init {
        orientation = VERTICAL
        background = Ui.material(context, dp(28f).toFloat())
        setPadding(dp(10f), 0, dp(10f), dp(14f))
        isClickable = true
        elevation = dp(8f).toFloat()

        // Grabber: shows the sheet can be dragged; tapping it (or TalkBack's double-tap) closes it.
        val grabber = FrameLayout(context).apply {
            contentDescription = "Close quick controls"
            isClickable = true
            isFocusable = true
            setOnClickListener { close() }
            addView(View(context).apply { background = Ui.rounded(0x66FFFFFF, dp(2.5f).toFloat()) },
                FrameLayout.LayoutParams(dp(36f), dp(5f), Gravity.CENTER))
        }
        addView(grabber, LayoutParams(LayoutParams.MATCH_PARENT, dp(28f)))

        grid.orientation = VERTICAL
        addView(grid, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        detail.orientation = VERTICAL
        detail.visibility = View.GONE
        detail.setPadding(dp(8f), dp(12f), dp(8f), 0)
        detailTitle.setTextColor(Ui.SECONDARY)
        Ui.chromeText(detailTitle, 13f)
        detailTitle.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        detail.addView(detailTitle)
        detailBody.orientation = VERTICAL
        detail.addView(detailBody, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8f) })
        addView(detail, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    // ------------------------------------------------------------------ content

    /** Shows [items]; when the set of controls is unchanged the cells are updated in place. */
    fun setItems(items: List<QuickItem>) {
        lastItems = items
        if (items.map { it.key } != cells.keys.toList()) rebuild(items)
        for (item in items) bind(cells.getValue(item.key), item)
    }

    private fun rebuild(items: List<QuickItem>) {
        grid.removeAllViews()
        cells.clear()
        val cols = 4
        var row: LinearLayout? = null
        items.forEachIndexed { i, item ->
            if (i % cols == 0) {
                row = LinearLayout(context).apply { orientation = HORIZONTAL }
                grid.addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { if (i > 0) topMargin = dp(12f) })
            }
            val cell = makeCell(item.key)
            cells[item.key] = cell
            row!!.addView(cell.root, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        }
        val rest = (cols - items.size % cols) % cols
        repeat(rest) { row?.addView(View(context), LayoutParams(0, 1, 1f)) }
    }

    private fun makeCell(key: String): Cell {
        val col = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            isClickable = true
            isFocusable = true
            minimumHeight = dp(48f)
            setOnClickListener {
                if (hapticsEnabled()) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                lastItems.firstOrNull { it.key == key }?.onTap?.invoke()
            }
        }
        val circle = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        col.addView(circle, LayoutParams(dp(52f), dp(52f)))
        val label = TextView(context).apply {
            setTextColor(Color.WHITE)
            Ui.chromeText(this, 12f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            maxLines = 2
            setPadding(dp(2f), dp(6f), dp(2f), 0)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        col.addView(label, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        val value = TextView(context).apply {
            Ui.chromeText(this, 11f)
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(2f), 0, dp(2f), 0)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        col.addView(value, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        // Press feedback on the circle, driven by the whole cell's touch.
        col.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> circle.springTo(SpringProp.SCALE, 0.9f, Springs.PRESS_DOWN)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> circle.springTo(SpringProp.SCALE, 1f, Springs.PRESS_UP)
            }
            false
        }
        return Cell(col, circle, label, value)
    }

    private fun bind(cell: Cell, item: QuickItem) {
        val selected = item.key == detailKey
        cell.icon.setImageResource(item.icon)
        cell.icon.background = Ui.ripple(Ui.rounded(if (selected) 0x59FFFFFF else Ui.FILL, dp(26f).toFloat()))
        cell.icon.imageTintList = ColorStateList.valueOf(if (item.active) Ui.ACCENT else Color.WHITE)
        cell.label.text = item.label
        cell.value.text = item.value
        cell.value.setTextColor(if (item.active) Ui.ACCENT else Ui.SECONDARY)
        cell.value.visibility = if (item.value.isEmpty()) View.GONE else View.VISIBLE
        // TalkBack: "Torch, off, switch" / "Timer, 3 sec, button".
        cell.root.contentDescription = item.label
        cell.root.isSelected = selected
        if (Build.VERSION.SDK_INT >= 30) cell.root.stateDescription = item.value.ifEmpty { null }
        else cell.root.contentDescription = if (item.value.isEmpty()) item.label else "${item.label}, ${item.value}"
        cell.root.accessibilityDelegate = if (item.toggle) object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = android.widget.Switch::class.java.name
                info.isCheckable = true
                info.isChecked = item.active
            }
        } else null
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

    /** Shows a slider for [key] (e.g. brightness), optionally with extra content below. */
    fun showSlider(key: String, title: String, max: Int, progress: Int, extra: View?, onChange: (Int) -> Unit): SeekBar {
        if (detailKey != key) openDetail(key, title) else detailTitle.text = title
        detailBody.removeAllViews()
        val seek = SeekBar(context).apply {
            this.max = max
            this.progress = progress
            contentDescription = title
            progressTintList = ColorStateList.valueOf(Ui.ACCENT)
            thumbTintList = ColorStateList.valueOf(Ui.ACCENT)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) { if (fromUser) onChange(p) }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }
        detailBody.addView(seek, LayoutParams(LayoutParams.MATCH_PARENT, dp(48f)))
        if (extra != null) detailBody.addView(extra, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4f) })
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
        detail.alpha = 0f
        detail.springTo(SpringProp.ALPHA, 1f, Springs.QUICK)
    }

    fun clearDetail() {
        detailKey = null
        detailBody.removeAllViews()
        detail.visibility = View.GONE
    }

    // ------------------------------------------------------------------ presentation

    /** Rises out of the quick-controls button (bottom right). */
    fun open() {
        isOpen = true
        clearDetail()
        visibility = View.VISIBLE
        post { pivotX = width - dp(42f).toFloat(); pivotY = height.toFloat() }
        if (!spring(SpringProp.ALPHA).isRunning && alpha >= 0.99f && translationY == 0f) {
            alpha = 0f
            scaleX = 0.92f
            scaleY = 0.92f
            translationY = dp(24f).toFloat()
        }
        springTo(SpringProp.ALPHA, 1f, Springs.QUICK)
        springTo(SpringProp.SCALE, 1f, Springs.DEFAULT)
        springTo(SpringProp.Y, 0f, Springs.DEFAULT)
    }

    /** Sinks back into the button along the same path, optionally carrying a drag's [velocity]. */
    fun close(velocity: Float? = null) {
        if (!isOpen) return
        isOpen = false
        val dragDismiss = velocity != null
        val endY = if (dragDismiss) height.toFloat() * 0.7f else dp(24f).toFloat()
        springTo(SpringProp.Y, endY, Springs.DEFAULT, velocity)
        if (!dragDismiss) springTo(SpringProp.SCALE, 0.92f, Springs.QUICK)
        springTo(SpringProp.ALPHA, 0f, Springs.QUICK) {
            visibility = View.GONE
            alpha = 1f
            scaleX = 1f
            scaleY = 1f
            translationY = 0f
            clearDetail()
            onClosed?.invoke()
        }
    }

    // ------------------------------------------------------------------ drag to dismiss

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downY = ev.rawY
                downX = ev.rawX
                dragging = false
                trackVelocity(ev, reset = true)
            }
            MotionEvent.ACTION_MOVE -> {
                trackVelocity(ev)
                val dy = ev.rawY - downY
                if (!dragging && abs(dy) > slop && abs(dy) > abs(ev.rawX - downX) * 1.2f) {
                    beginDrag(ev)
                    return true
                }
            }
        }
        return dragging
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        trackVelocity(ev, reset = ev.actionMasked == MotionEvent.ACTION_DOWN)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downY = ev.rawY
                downX = ev.rawX
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && abs(ev.rawY - downY) > slop) beginDrag(ev)
                if (dragging) {
                    val raw = startTy + (ev.rawY - downY)
                    // Follows the finger down; resists progressively when pulled up.
                    val ty = if (raw >= 0f) raw else Motion.rubberBand(raw, height.toFloat())
                    spring(SpringProp.Y).snapTo(ty)
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    dragging = false
                    val vt = velocity
                    vt?.computeCurrentVelocity(1000)
                    val v = vt?.yVelocity ?: 0f
                    val projected = translationY + Motion.project(v)
                    if (projected > height * 0.5f) {
                        close(v)
                    } else {
                        springTo(SpringProp.Y, 0f, if (abs(v) > 300f) Springs.FLICK else Springs.DEFAULT, v)
                    }
                }
                velocity?.recycle()
                velocity = null
                return true
            }
        }
        return super.onTouchEvent(ev)
    }

    private fun beginDrag(ev: MotionEvent) {
        dragging = true
        parent?.requestDisallowInterceptTouchEvent(true)
        spring(SpringProp.Y).stop()
        startTy = translationY
        downY = ev.rawY
    }

    private fun trackVelocity(ev: MotionEvent, reset: Boolean = false) {
        if (reset) {
            velocity?.recycle()
            velocity = VelocityTracker.obtain()
        }
        // Raw coordinates: the view itself moves while dragging.
        val e = MotionEvent.obtain(ev)
        e.setLocation(ev.rawX, ev.rawY)
        velocity?.addMovement(e)
        e.recycle()
    }
}
