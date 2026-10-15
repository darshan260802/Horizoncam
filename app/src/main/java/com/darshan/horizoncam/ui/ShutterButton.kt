package com.darshan.horizoncam.ui

import android.animation.ValueAnimator
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

/** Round shutter button that morphs between photo, video, time-lapse and recording states. */
class ShutterButton @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    var mode: CaptureMode = CaptureMode.VIDEO
        set(v) { field = v; invalidate() }

    var recording = false
        set(v) {
            if (field == v) return
            field = v
            animateMorph(if (v) 1f else 0f)
        }

    /** 0..1 progress ring (used for the self-timer). Negative hides it. */
    var progress = -1f
        set(v) { field = v; invalidate() }

    private var morph = 0f
    private var pressScale = 1f
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.WHITE }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val prog = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0xFFFFC83D.toInt(); strokeCap = Paint.Cap.ROUND }
    private val rect = RectF()
    private var animator: ValueAnimator? = null

    init {
        isClickable = true
        isFocusable = true
        contentDescription = "Shutter"
    }

    private fun animateMorph(target: Float) {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(morph, target).apply {
            duration = 220
            addUpdateListener { morph = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { pressScale = 0.88f; invalidate() }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { pressScale = 1f; invalidate() }
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
        ring.color = Color.WHITE
        canvas.drawCircle(cx, cy, r - stroke / 2f, ring)

        if (progress >= 0f) {
            prog.strokeWidth = stroke
            rect.set(cx - r + stroke / 2f, cy - r + stroke / 2f, cx + r - stroke / 2f, cy + r - stroke / 2f)
            canvas.drawArc(rect, -90f, 360f * progress, false, prog)
        }

        val photo = mode == CaptureMode.PHOTO && !recording
        fill.color = if (photo) Color.WHITE else RED
        val inner = (r - (if (photo) 6.5f else 8f) * d) * pressScale
        if (photo) {
            canvas.drawCircle(cx, cy, inner, fill)
            return
        }
        // Red dot morphs into a rounded square while recording.
        val half = inner * (1f - 0.52f * morph)
        val corner = half * (1f - 0.75f * morph)
        rect.set(cx - half, cy - half, cx + half, cy + half)
        canvas.drawRoundRect(rect, corner, corner, fill)
    }

    companion object {
        private const val RED = 0xFFF5372B.toInt()
    }
}
