package com.darshan.horizoncam.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.LinearLayout
import kotlin.math.abs

/**
 * Lens buttons that double as a zoom dial: tap a lens, or drag sideways anywhere on the bar to
 * zoom smoothly. The selected lens sits on a dark disc that slides between buttons on a spring.
 */
class ZoomBar @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : LinearLayout(context, attrs) {

    interface DragListener {
        fun onZoomDragStart()
        /** Total horizontal travel in pixels since the drag started (right = zoom in). */
        fun onZoomDrag(totalDx: Float)
        fun onZoomDragEnd()
    }

    var dragListener: DragListener? = null
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val density = resources.displayMetrics.density
    private var downX = 0f
    private var dragging = false

    private var selected = -1
    private var indicatorX = 0f
    private val indicator = Spring({ indicatorX }, { indicatorX = it; invalidate() }, 0.3f * density)
    private val discPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xA6000000.toInt() }
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = Ui.CHROME_EDGE
    }

    init {
        setWillNotDraw(false)
    }

    /** Moves the selection disc to child [index] (-1 hides it). */
    fun setSelected(index: Int) {
        for (i in 0 until childCount) getChildAt(i).isSelected = i == index
        if (index == selected) return
        val first = selected < 0
        selected = index
        val c = getChildAt(index) ?: run { invalidate(); return }
        if (c.width == 0) {
            post { getChildAt(selected)?.let { indicator.snapTo(it.left + it.width / 2f) } }
            return
        }
        val x = c.left + c.width / 2f
        if (first) indicator.snapTo(x) else indicator.animateTo(x, Springs.QUICK)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        if (!indicator.isRunning) getChildAt(selected)?.let { indicator.snapTo(it.left + it.width / 2f) }
    }

    override fun dispatchDraw(canvas: Canvas) {
        if (selected in 0 until childCount) {
            val r = 20f * density
            canvas.drawCircle(indicatorX, height / 2f, r, discPaint)
            canvas.drawCircle(indicatorX, height / 2f, r, edgePaint)
        }
        super.dispatchDraw(canvas)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                dragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && abs(ev.x - downX) > slop) {
                    dragging = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    dragListener?.onZoomDragStart()
                    return true
                }
            }
        }
        return dragging
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && abs(ev.x - downX) > slop) {
                    dragging = true
                    dragListener?.onZoomDragStart()
                }
                if (dragging) dragListener?.onZoomDrag(ev.x - downX)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) dragListener?.onZoomDragEnd()
                dragging = false
                return true
            }
        }
        return super.onTouchEvent(ev)
    }
}
