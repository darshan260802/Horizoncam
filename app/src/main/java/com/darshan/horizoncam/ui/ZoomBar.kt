package com.darshan.horizoncam.ui

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.LinearLayout
import kotlin.math.abs

/**
 * Lens buttons that double as a zoom dial: tap a lens, or drag sideways anywhere on the
 * bar to zoom smoothly (one-handed, like a zoom wheel).
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
    private var downX = 0f
    private var dragging = false

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
