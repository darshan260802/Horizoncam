package com.darshan.horizoncam.ui

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import com.darshan.horizoncam.core.LockMode
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * HorizonCam's signature glyph: a lens circle with the horizon running through it.
 *
 * - Off: dashed circle, and the line tilts with your hand. It shows the tilt your video will have.
 * - Steady: solid circle, and the line stays level.
 * - Steady + horizon lock: solid circle with rotation arrows, and the line stays level whatever
 *   you do.
 *
 * The shape differs per mode, so the state never relies on the amber colour alone.
 */
class HorizonGlyph(private val density: Float) : Drawable() {

    var mode: LockMode = LockMode.LOCK_360
        set(v) { field = v; invalidateSelf() }

    /** Horizon angle in degrees (clockwise), as seen in the recorded frame. */
    var angle = 0f
        set(v) { field = v; invalidateSelf() }

    var color: Int = Ui.ACCENT
        set(v) { field = v; invalidateSelf() }

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val dashed = DashPathEffect(floatArrayOf(2.2f * density, 2.6f * density), 0f)
    private val arrow = Path()

    override fun getIntrinsicWidth() = (24 * density).toInt()
    override fun getIntrinsicHeight() = (24 * density).toInt()

    override fun draw(canvas: Canvas) {
        val b = bounds
        val cx = b.exactCenterX()
        val cy = b.exactCenterY()
        val size = min(b.width(), b.height()).toFloat()
        val r = size * 0.33f
        stroke.color = color
        stroke.alpha = glyphAlpha

        // Lens circle.
        stroke.strokeWidth = 1.6f * density
        stroke.pathEffect = if (mode == LockMode.OFF) dashed else null
        canvas.drawCircle(cx, cy, r, stroke)
        stroke.pathEffect = null

        // Rotation arrows for the 360° lock: two arrowheads chasing each other round the circle.
        if (mode == LockMode.LOCK_360) {
            stroke.strokeWidth = 1.6f * density
            for (a in floatArrayOf(-45f, 135f)) {
                val rad = Math.toRadians(a.toDouble())
                // Radial (n) and clockwise tangent (t) directions at the arrow tip.
                val nx = cos(rad).toFloat()
                val ny = sin(rad).toFloat()
                val tx = -ny
                val ty = nx
                val px = cx + r * nx
                val py = cy + r * ny
                val len = 3.2f * density
                arrow.reset()
                arrow.moveTo(px - tx * len - nx * len * 0.9f, py - ty * len - ny * len * 0.9f)
                arrow.lineTo(px, py)
                arrow.lineTo(px - tx * len + nx * len * 0.9f, py - ty * len + ny * len * 0.9f)
                canvas.drawPath(arrow, stroke)
            }
        }

        // The horizon: runs past the circle, like a horizon seen through a lens.
        canvas.save()
        canvas.rotate(angle, cx, cy)
        stroke.strokeWidth = 2.2f * density
        val half = size * 0.47f
        canvas.drawLine(cx - half, cy, cx + half, cy, stroke)
        canvas.restore()
    }

    private var glyphAlpha = 255

    override fun setAlpha(alpha: Int) {
        glyphAlpha = alpha
        invalidateSelf()
    }

    override fun getAlpha(): Int = glyphAlpha

    override fun setColorFilter(colorFilter: ColorFilter?) {
        stroke.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
