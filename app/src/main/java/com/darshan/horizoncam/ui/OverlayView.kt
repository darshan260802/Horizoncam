package com.darshan.horizoncam.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import com.darshan.horizoncam.gl.RenderState
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Draws the spirit level, focus ring, self-timer countdown, warnings and the capture blink
 * above the camera preview. It only asks for new frames while something on it is changing.
 */
class OverlayView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    fun interface StateSource { fun state(): RenderState? }

    var stateSource: StateSource? = null
    var previewArea = RectF()
    var displayDeg = 0
    var showLevel = true
    var lockActive = true
    /** Rotation applied to text so it reads upright for the user (View rotation semantics). */
    var uiRotation = 0f
    var countdown = 0
        set(v) { field = v; invalidate() }
    var levelColor = Ui.ACCENT
    var warning: String? = null
    var onLevelReached: (() -> Unit)? = null

    private val density = resources.displayMetrics.density
    /** Text follows the system size (capped like the rest of the camera chrome). */
    private val textScale get() = Ui.chromeTextPx(context, 1f)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        setShadowLayer(4f, 0f, 1f, 0x99000000.toInt())
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    /** Capture feedback: a brief dim of the viewfinder rather than a bright flash. */
    private val flashPaint = Paint().apply { color = Color.BLACK }
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x88000000.toInt() }

    private var focusX = 0f
    private var focusY = 0f
    private var focusStart = 0L
    private var focusResult: Boolean? = null
    private var focusLocked = false
    private var flashStart = 0L
    private var wasLevel = false
    private var evFraction = 0.5f
    private var evText = ""
    private var evShownAt = 0L
    private var labelText = ""
    private var labelShownAt = 0L

    /** Exposure drag indicator: [fraction] 0 = darkest, 1 = brightest. */
    fun showEv(fraction: Float, text: String) {
        evFraction = fraction.coerceIn(0f, 1f)
        evText = text
        evShownAt = SystemClock.uptimeMillis()
        invalidate()
    }

    /** Large transient label (e.g. zoom factor while dragging). */
    fun showLabel(text: String) {
        labelText = text
        labelShownAt = SystemClock.uptimeMillis()
        invalidate()
    }

    fun showFocus(x: Float, y: Float, locked: Boolean) {
        focusX = x; focusY = y
        focusStart = SystemClock.uptimeMillis()
        focusResult = null
        focusLocked = locked
        invalidate()
    }

    fun focusResult(ok: Boolean?) {
        focusResult = ok
        invalidate()
    }

    fun hideFocus() {
        focusStart = 0
        focusLocked = false
        invalidate()
    }

    fun flash() {
        flashStart = SystemClock.uptimeMillis()
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        postInvalidateOnAnimation()
    }

    /** Something is fading, ticking or tracking the sensor: keep drawing frames. */
    private var animating = false

    override fun onDraw(canvas: Canvas) {
        animating = false
        val now = SystemClock.uptimeMillis()
        val st = stateSource?.state()
        val area = if (previewArea.width() > 1) previewArea else RectF(0f, 0f, width.toFloat(), height.toFloat())
        val cx = area.centerX()
        val cy = area.centerY()

        // ---- spirit level ----
        if (showLevel && st != null && !st.unstable && countdown <= 0) {
            animating = true
            val tilt = st.tiltDeg
            val level = abs(tilt) < 0.7
            if (level && !wasLevel) onLevelReached?.invoke()
            wasLevel = level
            val baseLen = min(area.width(), area.height()) * 0.16f
            val gap = 18 * density
            val accent = levelColor
            canvas.save()
            canvas.translate(cx, cy)
            // Reference marks aligned with the phone's current orientation.
            canvas.save()
            canvas.rotate(-(st.betaDeg + displayDeg).toFloat())
            linePaint.strokeWidth = 2.5f * density
            linePaint.color = if (level) accent else 0xCCFFFFFF.toInt()
            linePaint.alpha = if (lockActive) 150 else 230
            canvas.drawLine(-gap - baseLen * 0.9f, 0f, -gap - baseLen * 0.35f, 0f, linePaint)
            canvas.drawLine(gap + baseLen * 0.35f, 0f, gap + baseLen * 0.9f, 0f, linePaint)
            canvas.restore()
            // World horizon line.
            if (!level) {
                canvas.save()
                canvas.rotate(-(st.rollDeg + displayDeg).toFloat())
                linePaint.color = Color.WHITE
                linePaint.alpha = if (lockActive) 140 else 255
                linePaint.strokeWidth = 2f * density
                canvas.drawLine(-gap - baseLen, 0f, -gap, 0f, linePaint)
                canvas.drawLine(gap, 0f, gap + baseLen, 0f, linePaint)
                canvas.restore()
            }
            // Centre dot + angle.
            linePaint.color = if (level) accent else Color.WHITE
            linePaint.alpha = 255
            canvas.drawCircle(0f, 0f, 3f * density, linePaint)
            canvas.rotate(uiRotation)
            textPaint.textSize = 13 * textScale
            textPaint.color = if (level) accent else Color.WHITE
            val txt = if (level) "LEVEL" else String.format("%.1f°", abs(tilt))
            canvas.drawText(txt, 0f, -gap - 6 * density, textPaint)
            canvas.restore()
        }

        // ---- warning ----
        val warn = warning ?: if (st != null && st.unstable && lockActive) "Tilt the camera toward the horizon" else null
        if (warn != null && countdown <= 0) {
            canvas.save()
            canvas.translate(cx, cy + min(area.height() * 0.3f, 160 * density))
            canvas.rotate(uiRotation)
            textPaint.textSize = 14 * textScale
            textPaint.color = Color.WHITE
            val w = textPaint.measureText(warn) + 28 * density
            val h = 30 * density
            canvas.drawRoundRect(-w / 2, -h / 2 - 5 * density, w / 2, h / 2 - 5 * density, h / 2, h / 2, bgPaint)
            canvas.drawText(warn, 0f, 0f, textPaint)
            canvas.restore()
        }

        // ---- focus ring ----
        if (focusStart > 0) {
            val age = now - focusStart
            val visibleMs = if (focusLocked) Long.MAX_VALUE else 1600L
            if (age < visibleMs) {
                if (!focusLocked) animating = true
                val t = min(1f, age / 200f)
                val r = (44 - 10 * t) * density
                ringPaint.strokeWidth = 2f * density
                ringPaint.color = when (focusResult) {
                    true -> levelColor
                    false -> 0xFFFF6B5E.toInt()
                    null -> Color.WHITE
                }
                val fade = if (focusLocked) 1f else max(0f, min(1f, (visibleMs - age) / 400f))
                ringPaint.alpha = (255 * fade).toInt()
                canvas.drawCircle(focusX, focusY, r, ringPaint)
                if (focusLocked) {
                    canvas.save()
                    canvas.translate(focusX, focusY - r - 14 * density)
                    canvas.rotate(uiRotation)
                    textPaint.textSize = 12 * textScale
                    textPaint.color = levelColor
                    canvas.drawText("AE/AF LOCK", 0f, 0f, textPaint)
                    canvas.restore()
                }
            } else {
                focusStart = 0
            }
        }

        // ---- exposure indicator ----
        if (evShownAt > 0) {
            val age = now - evShownAt
            if (age < 1400) {
                animating = true
                val a = if (age < 1000) 1f else 1f - (age - 1000) / 400f
                val x = area.right - 34 * density
                val half = min(area.height() * 0.28f, 110 * density)
                linePaint.color = Color.WHITE
                linePaint.alpha = (150 * a).toInt()
                linePaint.strokeWidth = 2f * density
                canvas.drawLine(x, cy - half, x, cy + half, linePaint)
                val ky = cy + half - 2 * half * evFraction
                linePaint.color = levelColor
                linePaint.alpha = (255 * a).toInt()
                canvas.drawCircle(x, ky, 9 * density, linePaint)
                canvas.save()
                canvas.translate(x - 30 * density, ky)
                canvas.rotate(uiRotation)
                textPaint.textSize = 13 * textScale
                textPaint.color = levelColor
                textPaint.alpha = (255 * a).toInt()
                canvas.drawText(evText, 0f, 5 * density, textPaint)
                textPaint.alpha = 255
                canvas.restore()
            } else evShownAt = 0
        }

        // ---- transient label ----
        if (labelShownAt > 0) {
            val age = now - labelShownAt
            if (age < 1100) {
                animating = true
                val a = if (age < 700) 1f else 1f - (age - 700) / 400f
                canvas.save()
                canvas.translate(cx, area.top + min(area.height() * 0.18f, 120 * density))
                canvas.rotate(uiRotation)
                textPaint.textSize = 30 * textScale
                textPaint.color = levelColor
                textPaint.alpha = (255 * a).toInt()
                canvas.drawText(labelText, 0f, 10 * density, textPaint)
                textPaint.alpha = 255
                canvas.restore()
            } else labelShownAt = 0
        }

        // ---- countdown ----
        if (countdown > 0) {
            canvas.save()
            canvas.translate(cx, cy)
            canvas.rotate(uiRotation)
            textPaint.textSize = 96 * density
            textPaint.color = Color.WHITE
            canvas.drawText(countdown.toString(), 0f, 34 * density, textPaint)
            canvas.restore()
        }

        // ---- capture flash ----
        if (flashStart > 0) {
            val age = now - flashStart
            if (age < 160) {
                animating = true
                flashPaint.alpha = (102 * (1f - age / 160f)).toInt()
                canvas.drawRect(area, flashPaint)
            } else flashStart = 0
        }

        if (animating) postInvalidateOnAnimation()
    }
}
