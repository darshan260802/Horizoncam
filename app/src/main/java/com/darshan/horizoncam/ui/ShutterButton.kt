package com.darshan.horizoncam.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.darshan.horizoncam.core.CaptureMode
import kotlin.math.min

/**
 * Round shutter: a white disc for photos, a red dot for video that morphs into a stop square while
 * recording. It presses in on touch-down and every change runs on a spring, so it can be pressed
 * again mid-animation.
 */
class ShutterButton @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    var mode: CaptureMode = CaptureMode.VIDEO
        set(v) {
            field = v
            updateDescription()
            invalidate()
        }

    var recording = false
        set(v) {
            if (field == v) return
            field = v
            morph.animateTo(if (v) 1f else 0f, Springs.QUICK)
            updateDescription()
        }

    /** 0..1 progress ring (self-timer). Negative hides it. */
    var progress = -1f
        set(v) { field = v; invalidate() }

    private var morphValue = 0f
    private var pressScale = 1f
    private val morph = Spring({ morphValue }, { morphValue = it; invalidate() }, 0.002f)
    private val press = Spring({ pressScale }, { pressScale = it; invalidate() }, 0.0015f)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.WHITE }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val prog = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Ui.ACCENT; strokeCap = Paint.Cap.ROUND }
    private val rect = RectF()

    init {
        isClickable = true
        isFocusable = true
        updateDescription()
    }

    private fun updateDescription() {
        contentDescription = when {
            recording -> "Stop recording"
            mode == CaptureMode.PHOTO -> "Take photo"
            else -> "Start recording"
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> press.animateTo(0.88f, Springs.PRESS_DOWN)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> press.animateTo(1f, Springs.PRESS_UP)
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f
        val d = resources.displayMetrics.density
        val stroke = 3.5f * d
        ring.strokeWidth = stroke
        canvas.drawCircle(cx, cy, r - stroke / 2f, ring)

        if (progress >= 0f) {
            prog.strokeWidth = stroke
            rect.set(cx - r + stroke / 2f, cy - r + stroke / 2f, cx + r - stroke / 2f, cy + r - stroke / 2f)
            canvas.drawArc(rect, -90f, 360f * progress, false, prog)
        }

        val m = morphValue.coerceIn(0f, 1.2f)
        val photo = mode == CaptureMode.PHOTO && m < 0.01f
        fill.color = if (photo) Color.WHITE else Ui.RECORD
        val inner = (r - (if (photo) 6.5f else 8f) * d) * pressScale
        if (photo) {
            canvas.drawCircle(cx, cy, inner, fill)
            return
        }
        // Red dot morphs into a rounded square while recording.
        val half = inner * (1f - 0.52f * m)
        val corner = half * (1f - 0.75f * m.coerceAtMost(1f))
        rect.set(cx - half, cy - half, cx + half, cy + half)
        canvas.drawRoundRect(rect, corner, corner, fill)
    }
}
